// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The Home widgets' preferences: this device's own widgets, order and
 * settings, stored on the server through the narrow PATCH. The browser
 * dashboard keeps a set of its own; a device that never had one starts
 * from a copy of it, and from then on neither moves the other. The list is
 * what is on Home: a row is swiped away to remove its widget (with Undo),
 * and Add offers the ones that are not, each added at the end. An
 * admin-only widget is offered to admins only. Moves step among the listed
 * rows; a hidden widget keeps its place in the stored order. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetPrefsScreen(
    pairing: Prefs.Pairing,
    onBack: () -> Unit,
    shared: AppState,
    vm: HomeViewModel = viewModel(factory = HomeViewModel.factory(LocalContext.current, pairing, shared)),
) {
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Rows swiped away whose write has not come back yet: out of the list at
    // once, and back in if the write fails.
    var removing by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(state.prefs.disabled) { removing = removing - state.prefs.disabled.toSet() }
    state.error?.let { message ->
        LaunchedEffect(message) {
            removing = emptySet()
            snackbar.showSnackbar(message)
            vm.consumeError()
        }
    }
    val ordered = state.orderedVisibleIds.filter { it !in removing }
    // What Add offers: the hidden widgets, less one that needs a listening
    // history when the feed has said there is none (it would not show on
    // Home). Not known yet, or not readable: offered, with its caption.
    LaunchedEffect(state.hiddenIds) { vm.checkHistory() }
    val offered = state.hiddenIds.filter { WidgetRegistry.all[it]?.needsListeningHistory != true || state.historyConfigured[it] != false }
    var adding by remember { mutableStateOf(false) }
    val remove: (String) -> Unit = { id ->
        removing = removing + id
        vm.remove(id)
        val label = WidgetIds.label(id)?.let { context.getString(it) } ?: id
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(
                context.getString(R.string.widget_removed, label),
                actionLabel = context.getString(R.string.widget_undo), duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                removing = removing - id
                vm.restore(id)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.widget_prefs_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back_content_description))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        // A section label over a card, as Settings lays out its own. Each
        // cover widget's cover count is on its own row, beside its months.
        val scroll = rememberScrollState()
        val drag = remember { RowDrag() }
        AutoScrollWhileDragging(drag, scroll)
        Box(Modifier.fillMaxSize().padding(innerPadding).onGloballyPositioned { drag.viewport = it.boundsInWindow() }) {
            Column(
                Modifier.align(Alignment.TopCenter).then(adaptiveContentWidth()).fillMaxHeight()
                    .padding(16.dp).verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionLabel(stringResource(R.string.widget_prefs_section_widgets), info = stringResource(R.string.widget_prefs_intro))
                WidgetList(state, ordered, drag, vm, canAdd = offered.isNotEmpty(), onRemove = remove, onAdd = { adding = true })
            }
        }
        if (adding) {
            AddWidgetSheet(offered, state.historyConfigured, onPick = { vm.add(it); adding = false }, onDismiss = { adding = false })
        }
    }
}

private class WidgetRowState(
    val id: String,
    val months: Int?,
    val coverLimit: Int,
    val playlistsLimit: Int,
    val first: Boolean,
    val last: Boolean,
)

@Composable
private fun WidgetRow(
    row: WidgetRowState,
    onMove: (up: Boolean) -> Unit,
    onMonths: (Int) -> Unit,
    onCoverLimit: (Int) -> Unit,
    onPlaylistsLimit: (Int) -> Unit,
    handle: Modifier,
) {
    val id = row.id
    val label = WidgetIds.label(id)?.let { stringResource(it) } ?: id
    Column(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Drag to reorder. Left out of TalkBack, which cannot drag: the
            // arrows beside the name carry the move, and their labels.
            Icon(
                Icons.Filled.DragIndicator, contentDescription = null, tint = MaterialTheme.colorScheme.outline,
                modifier = handle.padding(8.dp).size(24.dp).clearAndSetSemantics { testTag = "drag_$id" },
            )
            LabelBesideControls(
                modifier = Modifier.weight(1f),
                label = {
                    Column {
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                        if (id == WidgetIds.ADMINISTRATION) {
                            Text(
                                stringResource(R.string.widget_admins_only),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        // Listed even when Home leaves it out, so its absence there
                        // has an explanation here.
                        if (WidgetRegistry.all[id]?.needsListeningHistory == true) {
                            Text(
                                stringResource(R.string.widget_needs_history),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                },
                controls = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onMove(true) }, enabled = !row.first) {
                            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.widget_move_up, label))
                        }
                        IconButton(onClick = { onMove(false) }, enabled = !row.last) {
                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.widget_move_down, label))
                        }
                    }
                },
            )
        }
        if (id in WidgetIds.MONTHS_WIDGETS) {
            // The web's clamp is 1-24 and the server holds the same range;
            // the buttons stop at the ends rather than send a value the
            // server would refuse.
            val value = row.months ?: DEFAULT_MONTHS
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp)) {
                Text(stringResource(R.string.widget_look_back), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                IconButton(onClick = { onMonths(value - 1) }, enabled = value > WidgetIds.MONTHS_RANGE.first) { Text("−") }
                Text(pluralStringResource(R.plurals.widget_months, value, value), style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = { onMonths(value + 1) }, enabled = value < WidgetIds.MONTHS_RANGE.last) { Text("+") }
            }
        }
        if (id == WidgetIds.PLAYLISTS) {
            val value = row.playlistsLimit
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp).testTag("playlists_limit")) {
                Text(stringResource(R.string.widget_playlists_how_many), style = MaterialTheme.typography.bodySmall)
                val fewer = stringResource(R.string.widget_playlists_fewer)
                IconButton(
                    onClick = { onPlaylistsLimit(value - 1) }, enabled = value > WidgetIds.PLAYLISTS_RANGE.first,
                    modifier = Modifier.semantics { contentDescription = fewer },
                ) { Text("−") }
                Text(value.toString(), style = MaterialTheme.typography.bodyMedium)
                IconButton(
                    onClick = { onPlaylistsLimit(value + 1) }, enabled = value < WidgetIds.PLAYLISTS_RANGE.last,
                ) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.widget_playlists_more)) }
            }
        }
        if (id in WidgetIds.COVER_WIDGETS) {
            // This widget's own cover count. A FlowRow: at 320 dp and 2.0x
            // the four chips do not fit one line, and a Row squeezed the last
            // until its number broke in two.
            FlowRow(
                modifier = Modifier.padding(start = 8.dp, bottom = 4.dp).testTag("covers_$id"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.widget_covers), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                WidgetIds.COVER_LIMITS.forEach { n ->
                    FilterChip(selected = row.coverLimit == n, onClick = { onCoverLimit(n) }, label = { Text(n.toString()) })
                }
            }
        }
    }
}

/** What is on Home, in order -- or a line saying nothing is -- and the
 * button that offers the rest. */
@Composable
private fun WidgetList(
    state: HomeUiState,
    ordered: List<String>,
    drag: RowDrag,
    vm: HomeViewModel,
    canAdd: Boolean,
    onRemove: (String) -> Unit,
    onAdd: () -> Unit,
) {
    if (ordered.isEmpty()) {
        Text(
            stringResource(R.string.widget_prefs_empty), style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("widget_prefs_empty"),
        )
    } else {
        ElevatedCard(Modifier.fillMaxWidth().testTag("widget_prefs_widgets")) {
            ordered.forEachIndexed { index, id ->
                key(id) {
                    if (index > 0) HorizontalDivider()
                    val dragged = drag.id == id
                    Box(
                        Modifier.fillMaxWidth()
                            .testTag("widget_row_$id")
                            .onGloballyPositioned { drag.heights[id] = it.size.height }
                            .zIndex(if (dragged) 1f else 0f)
                            .graphicsLayer { translationY = if (dragged) drag.offset else drag.shiftFor(index, ordered) }
                            .then(if (dragged) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest) else Modifier),
                    ) {
                        SwipeToRemove(id, onRemove = { onRemove(id) }) {
                            WidgetRow(
                                row = WidgetRowState(
                                    id, months = state.prefs.settings.months[id],
                                    coverLimit = state.prefs.settings.coverLimitOf(id),
                                    playlistsLimit = state.prefs.settings.playlistsLimit,
                                    first = index == 0, last = index == ordered.lastIndex,
                                ),
                                onMove = { up -> vm.move(id, up) },
                                onMonths = { vm.setMonths(id, it) },
                                onCoverLimit = { vm.setCoverLimit(id, it) },
                                onPlaylistsLimit = vm::setPlaylistsLimit,
                                handle = Modifier.dragHandle(drag, id, ordered) { from, to -> vm.moveTo(from, to) },
                            )
                        }
                    }
                }
            }
        }
    }
    OutlinedButton(
        onClick = onAdd, enabled = canAdd,
        modifier = Modifier.testTag("widget_add"),
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
        Text(stringResource(R.string.widget_add))
    }
}

/** A widget row that is swiped away, either way, to remove its widget.
 * TalkBack cannot swipe, so the same removal is a custom action on the row,
 * as the arrows carry the drag's move. */
@Composable
private fun SwipeToRemove(id: String, onRemove: () -> Unit, content: @Composable () -> Unit) {
    val label = WidgetIds.label(id)?.let { stringResource(it) } ?: id
    val removeLabel = stringResource(R.string.widget_remove, label)
    val swipe = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) onRemove()
            value != SwipeToDismissBoxValue.Settled
        },
    )
    SwipeToDismissBox(
        state = swipe,
        modifier = Modifier.semantics {
            customActions = listOf(CustomAccessibilityAction(removeLabel) { onRemove(); true })
        },
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 16.dp),
                contentAlignment = if (swipe.dismissDirection == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
    ) {
        Box(Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow)) { content() }
    }
}

/** The widgets not on Home, to put one back: the same captions as a row,
 * so it is clear why an added widget may not show yet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddWidgetSheet(ids: List<String>, historyConfigured: Map<String, Boolean>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("widget_add_sheet")) {
        Text(
            stringResource(R.string.widget_add_title), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        ids.forEach { id ->
            Column(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { onPick(id) }
                    .padding(horizontal = 16.dp, vertical = 12.dp).testTag("widget_add_$id"),
            ) {
                Text(WidgetIds.label(id)?.let { stringResource(it) } ?: id, style = MaterialTheme.typography.bodyLarge)
                val caption = MaterialTheme.typography.labelSmall
                if (id == WidgetIds.ADMINISTRATION) {
                    Text(stringResource(R.string.widget_admins_only), style = caption, color = MaterialTheme.colorScheme.outline)
                }
                // Only while it is not known to be set up; once it is, the entry is like any other.
                if (WidgetRegistry.all[id]?.needsListeningHistory == true && historyConfigured[id] != true) {
                    Text(stringResource(R.string.widget_needs_history), style = caption, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** The server's default when a user never set a months value. */
private const val DEFAULT_MONTHS = 3

