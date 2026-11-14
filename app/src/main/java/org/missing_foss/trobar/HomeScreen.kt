// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.items as gridItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ShoppingBasket
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/** The Home tab: the account's widgets in its order, hidden ones skipped,
 * each fetched only while visible; pull down to refetch what is shown.
 * An id the account ordered but this build cannot render is a small
 * card saying so, kept in its place. All widgets off is a message and a
 * way to the preferences, as in the browser. */
private fun widgetLinks(nav: HomeNavigation, shared: AppState, stagePlaylist: (Long, Rect) -> Unit) = WidgetLinks(
    onOpenDevice = nav.onOpenDevice,
    onOpenPlaylists = nav.onOpenPlaylists,
    onOpenPlaylist = { id ->
        shared.openPlaylist(id)
        nav.onOpenPlaylists()
    },
    onStagePlaylist = stagePlaylist,
    onOpenAlbum = { artist, album ->
        shared.openInLibrary(LibraryTarget(artist, album, details = true))
        nav.onOpenLibrary()
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    pairing: Prefs.Pairing,
    nav: HomeNavigation,
    basketCount: Int? = null,
    widgets: Map<String, Widget> = WidgetRegistry.all,
    shared: AppState,
    vm: HomeViewModel = viewModel(factory = HomeViewModel.factory(LocalContext.current, pairing, shared, widgets)),
) {
    val state by vm.state.collectAsState()
    val sharedState by shared.state.collectAsState()
    val thanksCardShown by Prefs.thanksCardShown(LocalContext.current).collectAsState(initial = false)
    val snackbar = remember { SnackbarHostState() }
    state.error?.let { message ->
        LaunchedEffect(message) {
            snackbar.showSnackbar(message)
            vm.consumeError()
        }
    }
    val context = LocalContext.current
    val urls = remember(pairing) { AppApiClient(context, pairing.serverUrl, pairing.token) }
    val flight = rememberBasketFlight()
    val answer = rememberStageAnswer(flight, snackbar)
    val actions = WidgetActions(
        settings = state.prefs.settings,
        host = WidgetHost(
            visible = state.orderedVisibleIds.toSet(),
            loaded = state.loaded,
            staging = state.staging,
            leavesOutThisPhone = state.appApiLevel >= APP_API_HOME_LEAVES_OUT_THIS_PHONE,
            thisDeviceName = sharedState.devices.firstOrNull { it.id == sharedState.thisDeviceId }?.name,
            covers = Covers(
                pairing,
                url = { artist, album -> urls.coverUrl(artist, album) },
                artistImage = { urls.artistImageUrl(it, small = true) },
            ),
        ),
        onOpenLibrary = nav.onOpenLibrary,
        links = widgetLinks(nav, shared) { id, from -> answer(vm.stage("playlist", id.toString()), null, from) },
        onStage = { target, coverUrl, from -> answer(vm.stage("album", target), coverUrl, from) },
        onSetMonths = vm::setMonths,
    )

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TrobarWordmarkTitle()
                            DeviceSelector(sharedState, shared::chooseDevice)
                        }
                    },
                    actions = {
                        IconButton(onClick = nav.onOpenPrefs) {
                            Icon(Icons.Filled.Tune, contentDescription = stringResource(R.string.widget_prefs_title))
                        }
                        IconButton(onClick = nav.onOpenBasket, modifier = Modifier.basketFlightTarget(flight)) {
                            BadgedBox(badge = { if (basketCount != null && basketCount > 0) Badge { Text(basketCount.toString()) } }) {
                                Icon(Icons.Filled.ShoppingBasket, contentDescription = stringResource(R.string.basket_title))
                            }
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { innerPadding ->
            Box(Modifier.fillMaxSize().padding(innerPadding)) {
                when {
                    state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    state.orderedVisibleIds.isEmpty() -> EmptyDashboard(nav.onOpenPrefs)
                    else -> PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
                        val ids = state.orderedVisibleIds.filter { shownOnHome(widgets[it], state.data[it]) }
                        val card: @Composable (String) -> Unit = { id -> WidgetCard(id, widgets[id], state.data[id], actions) }
                        if (isCompactWidth()) {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize().testTag("dashboard"),
                                contentPadding = PaddingValues(12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                // Only when due: an empty item would still take a gap.
                                if (thanksCardShown) item(key = "thanks_card") { ThanksCardIfDue() }
                                items(ids, key = { it }) { id -> card(id) }
                            }
                        } else {
                            HomeColumns(ids, thanksCardShown, card)
                        }
                    }
                }
            }
        }
        BasketFlightOverlay(flight, pairing)
    }
}

/** A widget built from a listening history is shown only once its feed is
 * in and says a history is set up: not while it loads, so it never appears
 * and then goes, and not when there is none, rather than a card saying so.
 * A failed load still shows, with its error. Display only: this device's
 * order and hidden list are never written, so the widget comes back by
 * itself, in its place, once an account is added. */
private fun shownOnHome(widget: Widget?, data: WidgetData?): Boolean {
    if (widget?.needsListeningHistory != true) return true
    return when (data) {
        is WidgetData.Loaded -> (data.data as? Feed)?.configured != false
        is WidgetData.Failed -> true
        else -> false
    }
}

@Composable
private fun EmptyDashboard(onOpenPrefs: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.dashboard_empty), style = MaterialTheme.typography.bodyLarge)
        OutlinedButton(onClick = onOpenPrefs) { Text(stringResource(R.string.dashboard_choose_widgets)) }
    }
}

/** One card per id: the widget's content when this build has one and its
 * data is in; a spinner while it loads; the failure's text when it did
 * not; and the unavailable card for an id with no renderer here. */
@Composable
private fun WidgetCard(id: String, widget: Widget?, data: WidgetData?, actions: WidgetActions) {
    Card(modifier = Modifier.fillMaxWidth().testTag("widget-$id")) {
        Column(Modifier.padding(16.dp)) {
            val label = WidgetIds.label(id)
            val title = if (label != null) stringResource(label) else id
            val info = widget?.info?.let { stringResource(it) }
            val controls = widget?.titleControls(actions)
            val titleText = @Composable { modifier: Modifier ->
                if (info == null) {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = modifier)
                } else {
                    LabelWithInfo(title, info, modifier, style = MaterialTheme.typography.titleMedium)
                }
            }
            if (controls == null) {
                titleText(Modifier.fillMaxWidth())
            } else {
                // Beside the title while each of its words still fits, under
                // it as one piece once one would not.
                LabelBesideControls(
                    label = { titleText(Modifier) },
                    controls = controls,
                    modifier = Modifier.fillMaxWidth().testTag("widget-title-$id"),
                )
            }
            when {
                widget == null -> Text(
                    stringResource(R.string.widget_unavailable),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                )
                data is WidgetData.Loaded -> widget.Content(data.data, actions)
                data is WidgetData.Failed -> Text(
                    data.message ?: stringResource(R.string.widget_failed),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                )
                else -> CircularProgressIndicator(Modifier.padding(top = 8.dp))
            }
        }
    }
}

/** A wide window's Home: as many columns as the width holds, so a card never
 * stretches across a large tablet. */
@Composable
private fun HomeColumns(ids: List<String>, thanksCardShown: Boolean, card: @Composable (String) -> Unit) {
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(HOME_CARD_MIN_WIDTH),
        modifier = Modifier.fillMaxSize().testTag("dashboard"),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalItemSpacing = 12.dp,
    ) {
        if (thanksCardShown) {
            item(key = "thanks_card", span = StaggeredGridItemSpan.FullLine) { ThanksCardIfDue() }
        }
        gridItems(ids, key = { it }) { id -> card(id) }
    }
}

// Wide enough that an 840 dp window keeps one column and its covers their size.
private val HOME_CARD_MIN_WIDTH = 560.dp
