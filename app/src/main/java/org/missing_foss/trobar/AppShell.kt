// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import android.net.Uri
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch

private class Tab(val route: String, val name: Int, val icon: ImageVector)

private val TABS = listOf(
    Tab(Routes.HOME, R.string.tab_home, Icons.Filled.Home),
    Tab(Routes.LIBRARY, R.string.tab_library, Icons.Filled.LibraryMusic),
    Tab(Routes.PLAYLISTS, R.string.tab_playlists, Icons.AutoMirrored.Filled.QueueMusic),
    Tab(Routes.SETTINGS, R.string.settings_title, Icons.Filled.Settings),
)

/** The paired-and-configured app: a bottom bar with Home, Library,
 * Playlists and Settings, and About, the basket and the widget preferences pushed over
 * whichever tab is showing. The gate in MainActivity decides whether this
 * is shown at all; nothing here knows about pairing state beyond the
 * pairing it is handed.
 *
 * Home is the start destination; back from any other tab returns to it,
 * then leaves. The sync has no screen of its own: a line above the bottom
 * bar says what it is doing on every tab, and leads to its detail at the
 * top of Settings. The server probe that screen ran on open runs here, at
 * start and whenever Settings opens; when it fails, Settings shows the
 * repair screen in its place, as the Sync tab did. */
@Composable
fun AppShell(
    pairing: Prefs.Pairing,
    treeUri: String,
    onServerUrlChanged: (String) -> Unit,
    onFolderChanged: (Uri) -> Unit,
    onUnpair: () -> Unit,
    onReEnroll: () -> Unit,
    sharedLink: SharedLinkIn = SharedLinkIn(null) {},
) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    TakeSharedLink(nav, ready = backStack != null, sharedLink)
    val route = backStack?.destination?.route
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The one holder of what the tabs share, the activity's, keyed by
    // the pairing. The badge reads its basket, which every stage, send and
    // removal writes: right after the write, with no read of its own.
    val shared: AppState = viewModel(key = AppState.key(pairing), factory = AppState.factory(context, pairing))
    val basketCount = shared.state.collectAsState().value.basket?.size
    val probe = rememberServerProbe(pairing)
    val syncStatus = rememberSyncStatus()
    val tabDrillDown = remember { TabDrillDown() }
    CompositionLocalProvider(LocalTabDrillDown provides tabDrillDown) {
    Scaffold(
        bottomBar = {
            if (route in Routes.TABS) Column {
                SyncIndicator(syncStatus, probe.state, onOpen = { nav.switchTab(Routes.SETTINGS) })
                NavigationBar {
                    TABS.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = { nav.switchTab(tab.route) },
                            // Icons alone: four labels do not fit a phone at
                            // large text. The name is the icon's, for screen
                            // readers.
                            icon = { Icon(tab.icon, contentDescription = stringResource(tab.name)) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(innerPadding).swipeBetweenTabs(route, tabDrillDown.isOpen(route)) { nav.switchTab(it) },
        ) {
            composable(Routes.HOME) {
                AppApiGate(pairing) { HomeTab(pairing, nav, shared, basketCount) }
            }
            composable(Routes.LIBRARY) {
                AppApiGate(pairing) {
                    LibraryScreen(
                        pairing = pairing,
                        onOpenBasket = { nav.navigate(Routes.BASKET) },
                        shared = shared,
                    )
                }
            }
            playlistRoutes(pairing, nav, shared)
            composable(Routes.BASKET) {
                BasketScreen(
                    pairing = pairing,
                    onBack = { nav.popBackStack() },
                    onSyncNow = { scope.launch { SyncWorker.triggerManualSync(context) } },
                    onShowSync = { nav.switchTab(Routes.SETTINGS) },
                    shared = shared,
                )
            }
            composable(Routes.SETTINGS) {
                SettingsTab(pairing, treeUri, probe, syncStatus, nav, HostActions(onServerUrlChanged, onFolderChanged, onUnpair, onReEnroll))
            }
            composable(Routes.WIDGET_PREFS) {
                WidgetPrefsScreen(pairing = pairing, shared = shared, onBack = { nav.popBackStack() })
            }
            composable(Routes.ABOUT) {
                AboutScreen(onBack = { nav.popBackStack() })
            }
        }
    }
    }
}

/** The Playlists tab and the subscriptions pushed over it; both need the
 * App API level that brought the playlist routes. */
private fun NavGraphBuilder.playlistRoutes(pairing: Prefs.Pairing, nav: NavHostController, shared: AppState) {
    composable(Routes.PLAYLISTS) {
        AppApiGate(pairing, minLevel = APP_API_PLAYLISTS) {
            PlaylistsScreen(
                pairing = pairing,
                nav = PlaylistsNav(
                    onOpenBasket = { nav.navigate(Routes.BASKET) },
                    onOpenSubscriptions = { nav.navigate(Routes.subscriptions()) },
                ),
                shared = shared,
            )
        }
    }
    subscriptionsRoute(pairing, nav)
}

/** The playlist subscriptions, pushed over whatever is showing: over the
 * Playlists tab in Full mode, over the Sync screen in Simple mode, where only
 * a shared link opens it. */
internal fun NavGraphBuilder.subscriptionsRoute(pairing: Prefs.Pairing, nav: NavHostController) {
    composable(
        Routes.SUBSCRIPTIONS,
        arguments = listOf(navArgument(Routes.SUBSCRIPTIONS_URL) { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) { entry ->
        AppApiGate(pairing, minLevel = APP_API_PLAYLISTS) {
            SubscriptionsScreen(
                pairing = pairing,
                sharedUrl = entry.arguments?.getString(Routes.SUBSCRIPTIONS_URL),
                onBack = { nav.popBackStack() },
            )
        }
    }
}

/** A link shared from another app, waiting for the shell, and how to say
 * it was taken. */
class SharedLinkIn(val link: String?, val onTaken: () -> Unit)

/** Opens the subscriptions with a shared link in the field: taken once, and
 * the owner confirms with Add. Not before [ready] -- the first entry on the
 * stack: until then the graph is not set, and a navigation -- a share that
 * cold-starts the app -- would crash. */
@Composable
internal fun TakeSharedLink(nav: NavHostController, ready: Boolean, shared: SharedLinkIn) {
    LaunchedEffect(shared.link, ready) {
        val link = shared.link
        if (link != null && ready) {
            nav.navigate(Routes.subscriptions(link))
            shared.onTaken()
        }
    }
}

/** What the activity hands the shell for the pairing and the music folder. */
internal class HostActions(
    val onServerUrlChanged: (String) -> Unit,
    val onFolderChanged: (Uri) -> Unit,
    val onUnpair: () -> Unit,
    val onReEnroll: () -> Unit,
)

/** Settings as a tab: the sync's detail on top. Opening it re-probes the
 * server, as opening the Sync tab did; a failed probe shows the repair
 * screen in its place. In Simple mode it is pushed over the Sync screen
 * instead: [onBack] returns there, and [syncStatus] is null since the card is
 * the screen underneath. */
@Composable
internal fun SettingsTab(
    pairing: Prefs.Pairing,
    treeUri: String,
    probe: ServerProbe,
    syncStatus: SyncStatus?,
    nav: NavHostController,
    host: HostActions,
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { probe.retry() }
    if (probe.state != ConnState.OK) {
        ConnectionProblemScreen(
            unauthorized = probe.state == ConnState.UNAUTHORIZED,
            serverUrl = pairing.serverUrl,
            onRetry = probe::retry,
            onReEnroll = host.onReEnroll,
            // The retry follows the write, not the call: the new address is
            // stored asynchronously, and a probe started before it lands
            // would still ask the old one.
            onServerUrlChanged = { url ->
                scope.launch {
                    Prefs.setServerUrl(context, url)
                    probe.retry()
                }
            },
        )
    } else {
        SettingsScreen(
            pairing = pairing,
            treeUri = treeUri,
            nav = SettingsNav(onOpenAbout = { nav.navigate(Routes.ABOUT) }, onBack = onBack),
            onServerUrlChanged = host.onServerUrlChanged,
            onFolderChanged = host.onFolderChanged,
            onUnpair = host.onUnpair,
            syncStatus = { syncStatus?.let { SyncStatusCard(pairing, it) } },
        )
    }
}

@Composable
private fun HomeTab(pairing: Prefs.Pairing, nav: NavHostController, shared: AppState, basketCount: Int?) {
    HomeScreen(
        pairing = pairing,
        nav = HomeNavigation(
            onOpenBasket = { nav.navigate(Routes.BASKET) },
            onOpenPrefs = { nav.navigate(Routes.WIDGET_PREFS) },
            onOpenLibrary = { nav.switchTab(Routes.LIBRARY) },
        ),
        shared = shared,
        basketCount = basketCount,
    )
}

/**
 * A horizontal swipe on one of the four tabs moves to the next or previous
 * one, in the bottom bar's order, through the same [switchTab] as a tap, so
 * each tab keeps its state and back still lands on Home. Nothing past either
 * end, nothing on a pushed screen, and nothing while a tab shows a
 * drill-down page -- an artist or a playlist opened on a phone -- which
 * keeps the behaviour it had, as a pushed screen does.
 *
 * Whatever scrolls sideways inside a tab -- the similar-artists strip, a
 * cover row -- sees the drag first and keeps it: a child consumes it before
 * this parent's detector does, and a consumed drag never starts a tab swipe.
 * The system back gesture comes from the screen's edges and is the system's
 * before the app sees it.
 */
private fun Modifier.swipeBetweenTabs(route: String?, drillDown: Boolean, onSwitch: (String) -> Unit): Modifier {
    val index = TABS.indexOfFirst { it.route == route }
    if (index < 0 || drillDown) return this
    return composed {
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        pointerInput(index, rtl) {
            val threshold = TAB_SWIPE_DISTANCE.toPx()
            var total = 0f
            detectHorizontalDragGestures(
                onDragStart = { total = 0f },
                onHorizontalDrag = { _, dx -> total += dx },
                onDragEnd = {
                    // Leftwards is forward, as a page turns; the other way in a
                    // right-to-left layout.
                    val forward = if (rtl) total >= threshold else total <= -threshold
                    val backward = if (rtl) total <= -threshold else total >= threshold
                    val target = when {
                        forward -> index + 1
                        backward -> index - 1
                        else -> index
                    }
                    if (target != index && target in TABS.indices) onSwitch(TABS[target].route)
                },
            )
        }
    }
}

/** Which tabs have a drill-down page open, by route, which a tab swipe
 * leaves alone. Set by each tab itself, through [TabDrillDownOpen]. Keyed
 * by route, not one flag: on a tab switch the incoming tab is composed
 * before the outgoing one is disposed, and one flag would let the outgoing
 * tab's clean-up clear what the incoming tab had just set. */
internal class TabDrillDown {
    private val open = mutableStateMapOf<String, Boolean>()

    fun isOpen(route: String?) = open[route] == true

    fun set(route: String, isOpen: Boolean) {
        if (isOpen) open[route] = true else open.remove(route)
    }
}

internal val LocalTabDrillDown = staticCompositionLocalOf { TabDrillDown() }

/** Tells the shell the tab at [route] shows a drill-down page while [open]:
 * no tab swipe there then. Cleared when the page closes or the tab leaves. */
@Composable
internal fun TabDrillDownOpen(route: String, open: Boolean) {
    val drillDown = LocalTabDrillDown.current
    DisposableEffect(drillDown, route, open) {
        drillDown.set(route, open)
        onDispose { drillDown.set(route, false) }
    }
}

/** How far a swipe must travel to change tab: a deliberate stroke, not a
 * drift while scrolling. */
private val TAB_SWIPE_DISTANCE = 64.dp

/** The standard tab switch: one copy of each tab on the stack, its state
 * kept while another tab is showing, and the start tab underneath so that
 * back from any tab lands there rather than walking through every tab
 * visited. */
private fun NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
