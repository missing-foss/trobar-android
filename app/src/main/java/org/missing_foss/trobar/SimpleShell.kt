// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The device Auto decides for. Provided by [PairedApp]; tests set their own. */
internal val LocalDeviceProfile = staticCompositionLocalOf<DeviceProfile?> { null }

/** The paired-and-configured app in the mode in force: [SimpleShell] or
 * [AppShell]. Follows the stored choice as it changes, so a switch in
 * Settings takes effect at once; nothing shows until the choice is read,
 * rather than one mode flashing before the other. */
@Composable
fun PairedApp(
    pairing: Prefs.Pairing,
    treeUri: String,
    onServerUrlChanged: (String) -> Unit,
    onFolderChanged: (Uri) -> Unit,
    onUnpair: () -> Unit,
    onReEnroll: () -> Unit,
    sharedLink: SharedLinkIn = SharedLinkIn(null) {},
) {
    val context = LocalContext.current
    val device = LocalDeviceProfile.current ?: remember { DeviceProfile.of(context) }
    val choice by remember { Prefs.appModeChoice(context) }.collectAsState(initial = null)
    val current = choice ?: return
    CompositionLocalProvider(LocalDeviceProfile provides device) {
        when (resolveAppMode(current, device)) {
            AppMode.SIMPLE -> SimpleShell(
                pairing, treeUri, HostActions(onServerUrlChanged, onFolderChanged, onUnpair, onReEnroll), sharedLink,
            )
            AppMode.FULL -> AppShell(pairing, treeUri, onServerUrlChanged, onFolderChanged, onUnpair, onReEnroll, sharedLink)
        }
    }
}

/** Simple mode: the app as a sync client and nothing else, for a device that
 * only receives music -- a DAP. One Sync screen; Settings, About and the
 * playlist subscriptions (reached only by a link shared to the app) are
 * pushed over it, and back returns to it. No tabs, no Home, Library or
 * basket: what goes on the device is chosen elsewhere. Needs no App API, so
 * it also works against a server too old for the tabs. */
@Composable
internal fun SimpleShell(
    pairing: Prefs.Pairing,
    treeUri: String,
    host: HostActions,
    sharedLink: SharedLinkIn = SharedLinkIn(null) {},
) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    TakeSharedLink(nav, ready = backStack != null, sharedLink)
    val probe = rememberServerProbe(pairing)
    val syncStatus = rememberSyncStatus()
    NavHost(navController = nav, startDestination = Routes.SYNC) {
        composable(Routes.SYNC) {
            SimpleSyncScreen(pairing, treeUri, probe, syncStatus, onOpenSettings = { nav.navigate(Routes.SETTINGS) })
        }
        composable(Routes.SETTINGS) {
            SettingsTab(pairing, treeUri, probe, syncStatus = null, nav = nav, host = host, onBack = { nav.popBackStack() })
        }
        composable(Routes.ABOUT) {
            AboutScreen(onBack = { nav.popBackStack() })
        }
        subscriptionsRoute(pairing, nav)
    }
}

/** The Sync screen: the server's state when it is out of reach or refusing,
 * the sync card (Sync now, progress, the last sync, why it is held back),
 * and this device's storage. Settings is the icon in the bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleSyncScreen(
    pairing: Prefs.Pairing,
    treeUri: String,
    probe: ServerProbe,
    syncStatus: SyncStatus,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    var storage by remember { mutableStateOf<StorageStats?>(null) }
    LaunchedEffect(treeUri) {
        storage = withContext(Dispatchers.IO) { storageStatsForTree(context, Uri.parse(treeUri)) }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { TrobarWordmarkTitle() },
                actions = {
                    IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("open_settings")) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .then(adaptiveContentWidth())
                    .fillMaxHeight()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag("simple_sync_screen"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // The server's state, when there is one to tell; a tap leads to
                // Settings, which shows the repair screen.
                if (probe.state != ConnState.OK) SyncIndicator(syncStatus, probe.state, onOpen = onOpenSettings)
                SyncStatusCard(pairing, syncStatus)
                storage?.let { stats ->
                    SectionLabel(stringResource(R.string.section_storage))
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        StorageUsage(stats, limitBytes = null, modifier = Modifier.padding(16.dp))
                    }
                }
            }
        }
    }
}
