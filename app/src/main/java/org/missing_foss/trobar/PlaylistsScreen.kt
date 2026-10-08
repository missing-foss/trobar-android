// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddLink
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
    // Back on the tab -- from the subscriptions, where one added or removed
    // changes the list, or from anywhere else: the list is read again.
    LaunchedEffect(Unit) { vm.reenter() }
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

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                PlaylistsTopBar(
                    drillDown = compact && open != null,
                    onBack = { vm.open(null) },
                    basketCount = sharedState.basket?.size ?: 0,
                    nav = nav,
                    shared = sharedState,
                    onChooseDevice = shared::chooseDevice,
                    flight = flight,
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { innerPadding ->
            Box(Modifier.fillMaxSize().padding(innerPadding)) {
                when {
                    state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    compact -> if (open != null) PlaylistDetail(open, state, vm, stage) else PlaylistList(state, vm, stage)
                    else -> Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(LIST_WEIGHT)) { PlaylistList(state, vm, stage) }
                        Box(Modifier.weight(DETAIL_WEIGHT)) {
                            if (open != null) PlaylistDetail(open, state, vm, stage)
                            else Text(stringResource(R.string.playlists_pick_one), modifier = Modifier.align(Alignment.Center).padding(32.dp))
                        }
                    }
                }
            }
        }
        BasketFlightOverlay(flight, pairing)
    }
}

/** Where the Playlists tab can send the user. */
class PlaylistsNav(val onOpenBasket: () -> Unit, val onOpenSubscriptions: () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistsTopBar(
    drillDown: Boolean,
    onBack: () -> Unit,
    basketCount: Int,
    nav: PlaylistsNav,
    shared: SharedState,
    onChooseDevice: (Long) -> Unit,
    flight: BasketFlight,
) {
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
            if (drillDown) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back_content_description))
                }
            }
        },
        actions = {
            if (drillDown) DeviceSelector(shared, onChooseDevice)
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
        val origin = p.inferredOriginProvider?.takeIf { it.isNotEmpty() && p.goldenOwnerUsername == null }
        if (origin != null) {
            Glyph(
                origin, stringResource(R.string.playlists_inferred_origin, providerName(origin)),
                ORIGIN_GLYPH_SIZE, MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Glyph(provider: String?, name: String, size: Dp, tint: Color) {
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

/** The playlist's summary, then its entries in playlist order; one the
 * library has no match for says so. */
@Composable
private fun PlaylistDetail(p: AppPlaylist, state: PlaylistsUiState, vm: PlaylistsViewModel, stage: (Long, Rect) -> Unit) {
    val stageLabel = stringResource(R.string.stage_action)
    LazyColumn(Modifier.fillMaxSize().testTag("playlist_entries")) {
        item {
            Column(
                Modifier.fillMaxWidth().stageOnDoubleTap(stageLabel) { from -> stage(p.id, from) }.padding(16.dp).testTag("playlist_summary"),
            ) {
                // The page, not the top bar, carries the name, on every width.
                PlaylistSummary(p, titleStyle = MaterialTheme.typography.titleLarge, wrapTitle = true)
            }
        }
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
        items(state.entries, key = { it.position }) { e ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    e.position.toString(), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline, modifier = Modifier.width(POSITION_WIDTH),
                )
                Column(Modifier.weight(1f)) {
                    Text(e.title ?: stringResource(R.string.playlists_untitled_entry), style = MaterialTheme.typography.bodyLarge)
                    val by = listOfNotNull(e.artist, e.album).joinToString(" · ")
                    if (by.isNotEmpty()) {
                        Text(by, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
                if (!e.matched) {
                    Text(
                        stringResource(R.string.playlists_entry_unmatched), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

/** The provider's name as the browser shows it; an unknown one as sent. */
@Composable
private fun providerName(provider: String?): String = when (provider) {
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
    "music_assistant" to "Music Assistant",
)

// The browser's glyph for each provider; its neutral music glyph for none or
// for one it has no mark for, never a blank.
private val PROVIDER_ICONS = mapOf(
    "roon" to R.drawable.ic_provider_roon, "subsonic" to R.drawable.ic_provider_subsonic,
    "jellyfin" to R.drawable.ic_provider_jellyfin, "emby" to R.drawable.ic_provider_emby, "plex" to R.drawable.ic_provider_plex,
    "lms" to R.drawable.ic_provider_lms, "filesystem" to R.drawable.ic_provider_filesystem, "tidal" to R.drawable.ic_provider_tidal,
    "spotify" to R.drawable.ic_provider_spotify, "ytmusic" to R.drawable.ic_provider_ytmusic,
    "music_assistant" to R.drawable.ic_provider_music_assistant,
)
private val PROVIDER_GLYPH_SIZE = 16.dp
private val ORIGIN_GLYPH_SIZE = 14.dp

private const val LIST_WEIGHT = 2f
private const val DETAIL_WEIGHT = 3f
private val SPINNER_SIZE = 16.dp
private val POSITION_WIDTH = 32.dp
