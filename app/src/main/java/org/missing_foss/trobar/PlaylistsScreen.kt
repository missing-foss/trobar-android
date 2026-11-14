// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.ShoppingBasket
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.material3.RadioButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.launch

/** The Playlists tab: the playlists the owner may see, most available
 * first, each with its owner as the browser marks it, how much of it the
 * library holds and when it last synced; one opened shows its entries and
 * which of them resolved. The provider sync is started from the list and
 * followed while it runs. A tap on a playlist opens it; a double-tap stages
 * it for the header's device, as a double-tap stages an album, and so does
 * a double-tap on an open playlist's summary. List and detail are two pages
 * on a phone-width window and side by side above it, as in the Library. */
@Composable
fun PlaylistsScreen(
    pairing: Prefs.Pairing,
    nav: PlaylistsNav,
    shared: AppState,
    vm: PlaylistsViewModel = viewModel(factory = PlaylistsViewModel.factory(LocalContext.current, pairing, shared)),
) {
    val state by vm.state.collectAsState()
    val sharedState by shared.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val compact = isCompactWidth()
    val open = state.open
    // The header reads the devices and the basket from the shared holder;
    // another tab may not have read them yet.
    LaunchedEffect(shared) { shared.ensureLoaded() }
    // A removal's Undo belongs to the playlist it was made in.
    LaunchedEffect(state.openId) {
        snackbar.currentSnackbarData?.takeIf { it.visuals.actionLabel != null }?.dismiss()
    }
    // Leaving the tab sends a removal still waiting on its Undo.
    DisposableEffect(Unit) { onDispose { vm.commitEdits() } }
    // Back on the tab -- from the subscriptions, where one added or removed
    // changes the list, or from anywhere else: the list is read again.
    LaunchedEffect(Unit) { vm.reenter() }
    // Another tab sent the user here: a row of Home's Playlists widget.
    LaunchedEffect(sharedState.playlistTarget) {
        sharedState.playlistTarget?.let {
            vm.open(it)
            shared.playlistTargetOpened()
        }
    }
    state.error?.let { message ->
        LaunchedEffect(message) {
            snackbar.showSnackbar(message)
            vm.consumeError()
        }
    }
    BackHandler(enabled = compact && open != null) { vm.open(null) }
    TabDrillDownOpen(Routes.PLAYLISTS, compact && open != null)
    // Registered after, so it wins while the review is open: back closes the
    // review first, then the playlist.
    BackHandler(enabled = state.review != null) { vm.closeReview() }
    val flight = rememberBasketFlight()
    val answer = rememberStageAnswer(flight, snackbar)
    // A playlist has no cover: the flight carries the image fallback, as
    // the basket row shows it.
    val stage: (Long, Rect) -> Unit = { id, from -> answer(vm.stage(id), null, from) }
    val detail = rememberDetailActions(vm, nav, snackbar, answer)
    val changedMessage = stringResource(R.string.playlist_changed_elsewhere)
    if (state.changedElsewhere) {
        LaunchedEffect(Unit) {
            snackbar.showSnackbar(changedMessage)
            vm.consumeError()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                PlaylistsTopBar(
                    onBack = if (compact && open != null) ({ vm.open(null) }) else null,
                    basketCount = sharedState.basket?.size ?: 0,
                    nav = nav,
                    shared = sharedState,
                    onChooseDevice = shared::chooseDevice,
                    flight = flight,
                    sort = if (compact && open != null) null else SortChoice(state.sort, vm.sorting::by),
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { innerPadding ->
            Box(Modifier.fillMaxSize().padding(innerPadding)) {
                when {
                    state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    compact -> if (open != null) PlaylistDetail(open, state, vm, stage, detail, sharedState) else PlaylistList(state, vm, stage)
                    else -> Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(LIST_WEIGHT)) { PlaylistList(state, vm, stage) }
                        Box(Modifier.weight(DETAIL_WEIGHT)) {
                            if (open != null) PlaylistDetail(open, state, vm, stage, detail, sharedState)
                            else Text(stringResource(R.string.playlists_pick_one), modifier = Modifier.align(Alignment.Center).padding(32.dp))
                        }
                    }
                }
            }
        }
        BasketFlightOverlay(flight, pairing)
    }
}

/** What the open playlist's rows do. A removal shows with its Undo, and only
 * the latest one's snackbar decides when the list is sent. */
@Composable
private fun rememberDetailActions(
    vm: PlaylistsViewModel,
    nav: PlaylistsNav,
    snackbar: SnackbarHostState,
    answer: (StageStart, String?, Rect?) -> Unit,
): PlaylistDetailActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var removals by remember { mutableIntStateOf(0) }
    return PlaylistDetailActions(
        syncAll = { id -> answer(vm.stage(id), null, null) },
        stageTrack = { id -> answer(vm.stageTrack(id), null, null) },
        openLibrary = { artist, album ->
            vm.openInLibrary(artist, album)
            nav.onOpenLibrary()
        },
        removeEntry = { entry ->
            val held = vm.removeEntry(entry)
            val title = entry.title ?: context.getString(R.string.playlists_untitled_entry)
            removals += 1
            val mine = removals
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                val result = snackbar.showSnackbar(
                    context.getString(R.string.playlist_track_removed, title),
                    actionLabel = context.getString(R.string.widget_undo), duration = SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed && held != null) vm.undoRemove(held)
                // Only the last removal's snackbar decides; a newer one took this one's place.
                if (mine == removals) vm.commitEdits()
            }
        },
        moveEntry = { from, to ->
            snackbar.currentSnackbarData?.dismiss()
            vm.moveEntry(from, to)
        },
        browseLibrary = nav.onOpenLibrary,
    )
}

/** The list's order and how to change it, for the top bar's menu. */
class SortChoice(val current: PlaylistSort, val onChoose: (PlaylistSort) -> Unit)

/** Where the Playlists tab can send the user. */
class PlaylistsNav(val onOpenBasket: () -> Unit, val onOpenSubscriptions: () -> Unit, val onOpenLibrary: () -> Unit = {})

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistsTopBar(
    /** Set only on a phone with a playlist open: the drill-down's back. */
    onBack: (() -> Unit)?,
    basketCount: Int,
    nav: PlaylistsNav,
    shared: SharedState,
    onChooseDevice: (Long) -> Unit,
    flight: BasketFlight,
    sort: SortChoice? = null,
) {
    val drillDown = onBack != null
    TopAppBar(
        modifier = Modifier.testTag("playlists_top_bar"),
        // The wordmark is the tab's title. A playlist opened on a phone is a
        // drill-down: back, and the icons, with its name on the page below,
        // where it has the full width -- beside three icons it was cut to a
        // few letters at a large font size.
        title = {
            if (!drillDown) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TrobarWordmarkTitle()
                    DeviceSelector(shared, onChooseDevice)
                }
            }
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back_content_description))
                }
            }
        },
        actions = {
            if (drillDown) DeviceSelector(shared, onChooseDevice)
            if (sort != null) SortMenu(sort.current, sort.onChoose)
            IconButton(onClick = nav.onOpenSubscriptions) {
                Icon(Icons.Filled.AddLink, contentDescription = stringResource(R.string.subs_title))
            }
            IconButton(onClick = nav.onOpenBasket, modifier = Modifier.basketFlightTarget(flight)) {
                BadgedBox(badge = { if (basketCount > 0) Badge { Text(basketCount.toString()) } }) {
                    Icon(Icons.Filled.ShoppingBasket, contentDescription = stringResource(R.string.basket_title))
                }
            }
        },
    )
}

/** The count and the sync button, then the playlists; none yet says how
 * they arrive. */
@Composable
private fun PlaylistList(state: PlaylistsUiState, vm: PlaylistsViewModel, stage: (Long, Rect) -> Unit) {
    val stageLabel = stringResource(R.string.stage_action)
    var naming by remember { mutableStateOf(false) }
    if (naming) {
        NewPlaylistDialog(
            onDismiss = { naming = false },
            onCreate = {
                naming = false
                vm.create(it)
            },
        )
    }
    Column(Modifier.fillMaxSize()) {
        // A Row measures the button first and leaves the count what remains,
        // one character wide on a narrow phone at a large font scale; a
        // FlowRow puts the button on the next line when both do not fit.
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                pluralStringResource(R.plurals.playlists_count, state.playlists.size, state.playlists.size),
                style = MaterialTheme.typography.titleSmall,
            )
            if (state.canWrite) {
                TextButton(onClick = { naming = true }, modifier = Modifier.testTag("playlist_new")) {
                    Text(stringResource(R.string.playlist_new))
                }
            }
            TextButton(onClick = vm::startSync, enabled = !state.syncing) {
                if (state.syncing) {
                    CircularProgressIndicator(Modifier.size(SPINNER_SIZE).testTag("playlists_syncing"), strokeWidth = 2.dp)
                    Text(stringResource(R.string.playlists_syncing), modifier = Modifier.padding(start = 8.dp))
                } else {
                    Text(stringResource(R.string.playlists_refresh))
                }
            }
        }
        if (state.playlists.isEmpty()) {
            Text(
                stringResource(R.string.playlists_empty), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(16.dp),
            )
        }
        LazyColumn(Modifier.fillMaxSize().testTag("playlist_list")) {
            itemsIndexed(state.playlists, key = { _, p -> p.id }) { i, p ->
                // Between rows only: nothing above the first or below the last.
                if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp).testTag("playlist_divider"))
                Column(
                    Modifier.fillMaxWidth()
                        .openOnTapStageOnDoubleTap(stageLabel, onOpen = { vm.open(p.id) }, onStage = { from -> stage(p.id, from) })
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    PlaylistSummary(p, titleStyle = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/** Title and owner marks, the availability bar and its figures, then the
 * source and the last sync -- the browser's row, less its mirror marks and
 * its actions. */
@Composable
private fun PlaylistSummary(p: AppPlaylist, titleStyle: TextStyle, wrapTitle: Boolean = false) {
    // Someone else's playlist, shared into the house: its owner. A row
    // reached through another member's linked account: whose it is.
    val owner = p.ownerUsername?.takeIf { !p.isOwn }
    val sharedBy = p.goldenOwnerUsername
    if (wrapTitle) {
        // The detail: the name on a line of its own, the full width, wrapping
        // rather than cut; the marks below it, where they cannot squeeze it.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // Before the title, where the browser puts it, and on its first
            // line when it wraps: sitting on the first baseline.
            ProviderGlyphs(p, Modifier.alignBy { it.measuredHeight })
            // The screen's title now the top bar has none, so TalkBack can jump to it.
            Text(p.title, style = titleStyle, modifier = Modifier.weight(1f).alignByBaseline().semantics { heading() })
        }
        if (owner != null || sharedBy != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                owner?.let { Mark(it) }
                sharedBy?.let { Mark(stringResource(R.string.playlists_shared_by, it)) }
            }
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ProviderGlyphs(p) // before the title, where the browser puts it
            Text(p.title, style = titleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            owner?.let { Mark(it) }
            sharedBy?.let { Mark(stringResource(R.string.playlists_shared_by, it)) }
        }
    }
    LinearProgressIndicator(
        progress = { p.availabilityPct() / 100f },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        drawStopIndicator = {},
    )
    val pct = p.availabilityPct()
    Text(
        stringResource(R.string.playlists_availability, p.matchedCount, p.trackCount, pct),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
    )
    // Its own line: beside the availability figure, which takes the width it
    // needs first, it was squeezed into a column a character or two wide.
    if (p.unresolvedCount > 0) {
        Text(
            stringResource(R.string.playlists_unresolved, p.unresolvedCount),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary,
        )
    }
    Text(syncedText(p.lastSyncedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
}

/**
 * The playlist's provider as its glyph, as the browser shows it, with the
 * name for TalkBack and on a long press. Then, lighter, the provider its
 * tracks were matched to when it came in through another one -- left out
 * when the "shared by" mark already says where it came from, as in the
 * browser.
 */
@Composable
private fun ProviderGlyphs(p: AppPlaylist, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = modifier) {
        Glyph(p.sourceProvider, providerName(p.sourceProvider), PROVIDER_GLYPH_SIZE, MaterialTheme.colorScheme.outline)
        if (p.sourceProvider in PUBLIC_LINK_PROVIDERS) PublicLinkBadge()
        val origin = p.inferredOriginProvider?.takeIf { it.isNotEmpty() && p.goldenOwnerUsername == null }
        if (origin != null) {
            Glyph(
                origin, stringResource(R.string.playlists_inferred_origin, providerName(origin)),
                ORIGIN_GLYPH_SIZE, MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

/** Added by a public link: the link of the button that adds them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PublicLinkBadge() {
    val name = stringResource(R.string.playlists_public_link)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(name) } },
        state = rememberTooltipState(),
    ) {
        Icon(
            Icons.Filled.Link, contentDescription = name, tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(ORIGIN_GLYPH_SIZE).testTag("public_link_badge"),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Glyph(provider: String?, name: String, size: Dp, tint: Color) {
    // Tagged with the glyph drawn, since a test cannot see a painter.
    val known = provider?.takeIf { it in PROVIDER_ICONS }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(name) } },
        state = rememberTooltipState(),
    ) {
        Icon(
            painterResource(PROVIDER_ICONS[known] ?: R.drawable.ic_provider_unknown), contentDescription = name, tint = tint,
            modifier = Modifier.size(size).testTag("provider_glyph_${known ?: "unknown"}"),
        )
    }
}


@Composable
private fun Mark(text: String) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(
            text, style = MaterialTheme.typography.labelSmall, maxLines = 1,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** What the open playlist's button and its entries' sheet do. */
private class PlaylistDetailActions(
    val syncAll: (Long) -> Unit,
    val stageTrack: (Long) -> Unit,
    val openLibrary: (artist: String, album: String?) -> Unit,
    val removeEntry: (PlaylistEntry) -> Unit,
    val moveEntry: (from: Int, to: Int) -> Unit,
    val browseLibrary: () -> Unit,
)

/** The playlist's summary, then its entries in playlist order; one the
 * library has no match for says so. A matched entry opens [EntrySheet], and
 * an unmatched one the review, where it can be excluded. */
@Composable
private fun PlaylistDetail(
    p: AppPlaylist, state: PlaylistsUiState, vm: PlaylistsViewModel, stage: (Long, Rect) -> Unit,
    actions: PlaylistDetailActions, shared: SharedState,
) {
    val stageLabel = stringResource(R.string.stage_action)
    var sheet by remember(p.id) { mutableStateOf<PlaylistEntry?>(null) }
    sheet?.let { entry ->
        EntrySheet(entry, shared.destination, actions, vm, onDismiss = { sheet = null })
    }
    val listState = rememberLazyListState()
    val drag = remember { RowDrag() }
    AutoScrollWhileDragging(drag, listState)
    LazyColumn(
        Modifier.fillMaxSize().onGloballyPositioned { drag.viewport = it.boundsInWindow() }.testTag("playlist_entries"),
        state = listState,
    ) {
        item {
            Column(
                Modifier.fillMaxWidth().stageOnDoubleTap(stageLabel) { from -> stage(p.id, from) }.padding(16.dp).testTag("playlist_summary"),
            ) {
                // The page, not the top bar, carries the name, on every width.
                PlaylistSummary(p, titleStyle = MaterialTheme.typography.titleLarge, wrapTitle = true)
                val staged = shared.staging.stagedHere("playlist", p.id.toString())
                OutlinedButton(
                    onClick = { actions.syncAll(p.id) },
                    modifier = Modifier.padding(top = 8.dp).testTag("playlist_sync"),
                ) {
                    Text(stringResource(if (staged) R.string.library_artist_in_basket else R.string.playlists_sync_entire))
                }
            }
        }
        if (state.canEditOpen) item { PlaylistEditActions(p, vm) }
        // Only where the server says the owner may change it: a control that
        // could only fail is not shown.
        if (p.canShare) item { Sharing(p, busy = state.sharingBusy, onToggle = vm::toggleShared) }
        // Not offered where Lidarr is not set up -- that is fixed in the
        // browser, not here -- even for requests switched on before it went
        // away: without Lidarr they do nothing.
        if (p.lidarr.configured) {
            item { LidarrRequests(p, busy = state.lidarrBusy, onToggle = vm::toggleLidarr) }
        }
        val review = state.review
        if (review != null) {
            reviewItems(review, onClose = vm::closeReview, onExclude = vm::setExcluded)
            return@LazyColumn
        }
        if (p.unresolvedCount > 0) {
            item {
                TextButton(onClick = vm::openReview, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(stringResource(R.string.playlists_unresolved, p.unresolvedCount) + " · " + stringResource(R.string.review_open))
                }
            }
        }
        if (state.entriesLoading) {
            item { Box(Modifier.fillMaxWidth().padding(32.dp)) { CircularProgressIndicator(Modifier.align(Alignment.Center)) } }
        }
        entryRows(state, vm, actions, drag) { sheet = it }
    }
}

/** The entries, each as the open playlist allows. */
private fun LazyListScope.entryRows(
    state: PlaylistsUiState,
    vm: PlaylistsViewModel,
    actions: PlaylistDetailActions,
    drag: RowDrag,
    onOpen: (PlaylistEntry) -> Unit,
) {
    val editable = state.canEditOpen
    val order = state.entries.map(::entryKey)
    if (editable && !state.entriesLoading && state.entries.isEmpty()) {
        item { EmptyPlaylist(actions.browseLibrary) }
    }
    itemsIndexed(state.entries, key = { _, e -> entryKey(e) }) { index, e ->
        // Matched, but its track has since left the library: nothing to
        // open or stage.
        val tap: (() -> Unit)? = when {
            !e.matched -> vm::openReview
            e.trackId != null -> ({ onOpen(e) })
            else -> null
        }
        if (editable) {
            val key = entryKey(e)
            val edit = EntryEdit(
                first = index == 0, last = index == state.entries.lastIndex,
                onMove = { up -> actions.moveEntry(index, if (up) index - 1 else index + 1) },
                onRemove = { actions.removeEntry(e) },
                handle = Modifier.dragHandle(drag, key, order) { id, to -> actions.moveEntry(order.indexOf(id), to) },
            )
            EditableEntry(
                e, edit, tap,
                Modifier.onGloballyPositioned { drag.heights[key] = it.size.height }.followingDrag(drag, key, index, order),
            )
        } else {
            Row(
                Modifier.fillMaxWidth()
                    .then(if (tap != null) Modifier.clickable(role = Role.Button, onClick = tap) else Modifier)
                    .padding(horizontal = 16.dp, vertical = 8.dp).testTag("playlist_entry_${e.position}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    e.position.toString(), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline, modifier = Modifier.width(POSITION_WIDTH),
                )
                Column(Modifier.weight(1f)) { EntryLines(e) }
            }
        }
    }
}

private fun entryKey(e: PlaylistEntry): String = e.entryId?.let { "e$it" } ?: "p${e.position}"

/** An entry's title and where it comes from, and a mark if unmatched. */
@Composable
internal fun EntryLines(e: PlaylistEntry) {
    Text(e.title ?: stringResource(R.string.playlists_untitled_entry), style = MaterialTheme.typography.bodyLarge)
    val by = listOfNotNull(e.artist, e.album).joinToString(" · ")
    if (by.isNotEmpty()) {
        Text(by, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
    if (!e.matched) {
        Text(
            stringResource(R.string.playlists_entry_unmatched), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}

/** What can be done with one matched entry. Add to the basket says when it is
 * already there; a track with no album has no album to open. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntrySheet(
    e: PlaylistEntry, device: AppDevice?, actions: PlaylistDetailActions, vm: PlaylistsViewModel, onDismiss: () -> Unit,
) {
    val trackId = e.trackId ?: return
    val staged = vm.stagedHere("track", trackId.toString())
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("entry_sheet")) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
            Text(e.title ?: stringResource(R.string.playlists_untitled_entry), style = MaterialTheme.typography.titleMedium)
            val by = listOfNotNull(e.artist, e.album).joinToString(" · ")
            if (by.isNotEmpty()) {
                Text(by, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider()
        val name = device?.name
        val stageText = when {
            name == null -> stringResource(R.string.stage_action)
            staged -> stringResource(R.string.stage_already_staged, name)
            else -> stringResource(R.string.stage_action_for, name)
        }
        EntryAction(stageText, "entry_stage", enabled = !staged) {
            actions.stageTrack(trackId)
            onDismiss()
        }
        e.openArtist?.let { artist ->
            EntryAction(stringResource(R.string.playlists_open_artist), "entry_artist") {
                actions.openLibrary(artist, null)
                onDismiss()
            }
            e.openAlbum?.let { album ->
                EntryAction(stringResource(R.string.playlists_open_album), "entry_album") {
                    actions.openLibrary(artist, album)
                    onDismiss()
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun EntryAction(text: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp).testTag(tag),
    )
}

/** The provider's name as the browser shows it; an unknown one as sent. */
@Composable
internal fun providerName(provider: String?): String = when (provider) {
    null, "" -> stringResource(R.string.playlists_unknown_source)
    else -> PROVIDER_NAMES[provider] ?: provider
}

/** "Synced 5 minutes ago"; the server's own text when it cannot be read as
 * its UTC timestamp. */
@Composable
private fun syncedText(raw: String?): String =
    if (raw == null) stringResource(R.string.playlists_never_synced) else stringResource(R.string.playlists_synced, relativeTime(raw))

/** A server timestamp (UTC, "2026-09-20 10:00:00") as "5 minutes ago" in the
 * phone's language; the server's own text when it cannot be read so. */
internal fun relativeTime(raw: String): String = try {
    val at = LocalDateTime.parse(raw.replace(' ', 'T')).toInstant(ZoneOffset.UTC).toEpochMilli()
    DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
} catch (ignored: DateTimeParseException) {
    raw
}

// Product names: the same in every language, as the browser writes them.
private val PROVIDER_NAMES = mapOf(
    "roon" to "Roon", "subsonic" to "Subsonic", "jellyfin" to "Jellyfin", "emby" to "Emby", "plex" to "Plex",
    "lms" to "Lyrion Music Server", "filesystem" to "Filesystem", "tidal" to "Tidal", "spotify" to "Spotify", "ytmusic" to "YouTube Music",
    "spotify_public" to "Spotify", "tidal_public" to "Tidal", "music_assistant" to "Music Assistant",
)

// Only a public link makes a playlist from these.
private val PUBLIC_LINK_PROVIDERS = setOf("ytmusic", "spotify_public", "tidal_public")

// The browser's glyph for each provider; its neutral music glyph for none or
// for one it has no mark for, never a blank.
private val PROVIDER_ICONS = mapOf(
    "roon" to R.drawable.ic_provider_roon, "subsonic" to R.drawable.ic_provider_subsonic,
    "jellyfin" to R.drawable.ic_provider_jellyfin, "emby" to R.drawable.ic_provider_emby, "plex" to R.drawable.ic_provider_plex,
    "lms" to R.drawable.ic_provider_lms, "filesystem" to R.drawable.ic_provider_filesystem, "tidal" to R.drawable.ic_provider_tidal,
    "tidal_public" to R.drawable.ic_provider_tidal,
    "spotify" to R.drawable.ic_provider_spotify, "spotify_public" to R.drawable.ic_provider_spotify,
    "ytmusic" to R.drawable.ic_provider_ytmusic, "music_assistant" to R.drawable.ic_provider_music_assistant,
)
internal val PROVIDER_GLYPH_SIZE = 16.dp
private val ORIGIN_GLYPH_SIZE = 14.dp

private const val LIST_WEIGHT = 2f
private const val DETAIL_WEIGHT = 3f
private val SPINNER_SIZE = 16.dp
private val POSITION_WIDTH = 32.dp

/** The list's order, behind an icon: the choice ticked. */
@Composable
private fun SortMenu(sort: PlaylistSort, onSort: (PlaylistSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.playlists_sort))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                stringResource(R.string.playlists_sort),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            PlaylistSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.label)) },
                    leadingIcon = { RadioButton(selected = option == sort, onClick = null) },
                    onClick = { onSort(option); open = false },
                )
            }
        }
    }
}
