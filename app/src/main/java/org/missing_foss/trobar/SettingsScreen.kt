// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.AppShortcut
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.documentfile.provider.DocumentFile
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    pairing: Prefs.Pairing,
    treeUri: String,
    nav: SettingsNav,
    onServerUrlChanged: (String) -> Unit,
    onFolderChanged: (Uri) -> Unit,
    onUnpair: () -> Unit,
    syncStatus: @Composable () -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var deviceInfo by remember { mutableStateOf<DeviceInfo?>(null) }
    var storageStats by remember { mutableStateOf<StorageStats?>(null) }
    var limitField by remember { mutableStateOf("") }
    var savingLimit by remember { mutableStateOf(false) }
    var showUnpairConfirm by remember { mutableStateOf(false) }
    var showServerDialog by remember { mutableStateOf(false) }
    var showLimitDialog by remember { mutableStateOf(false) }
    var showNomediaWarning by remember { mutableStateOf(false) }
    val networkMode by Prefs.networkMode(context).collectAsState(initial = Prefs.NETWORK_WIFI_ONLY)
    val requireChargingAndIdle by Prefs.requireChargingAndIdle(context).collectAsState(initial = false)
    val useDynamicColor by Prefs.useDynamicColor(context).collectAsState(initial = false)
    val matchIconToTheme by Prefs.matchIconToTheme(context).collectAsState(initial = false)
    val nomediaEnabled by Prefs.nomediaEnabled(context).collectAsState(initial = false)
    val missingFileBehavior by Prefs.missingFileBehavior(context).collectAsState(initial = Prefs.MISSING_ASK)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    fun saveLimit() {
        scope.launch {
            savingLimit = true
            try {
                val bytes = limitField.replace(",", ".").toDoubleOrNull()?.let { (it * 1e9).toLong() }
                withContext(Dispatchers.IO) { ApiClient(context, pairing.serverUrl, pairing.token).updateLimit(bytes) }
                deviceInfo = deviceInfo?.copy(maxSizeBytes = bytes)
                snackbarHostState.showSnackbar(context.getString(R.string.limit_saved))
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(e.message ?: context.getString(R.string.save_failed_generic))
            }
            savingLimit = false
        }
    }

    val hasTelephony = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
    }
    // DAPs and other Wi-Fi-only devices have no cellular radio at all — force
    // Wi-Fi-only rather than show irrelevant mobile-data options.
    LaunchedEffect(hasTelephony) {
        if (!hasTelephony && networkMode != Prefs.NETWORK_WIFI_ONLY) {
            Prefs.setNetworkMode(context, Prefs.NETWORK_WIFI_ONLY)
        }
    }

    LaunchedEffect(pairing, treeUri) {
        val info = try {
            withContext(Dispatchers.IO) { ApiClient(context, pairing.serverUrl, pairing.token).getDeviceInfo() }
        } catch (ignored: Exception) { null }
        // Written on the composition's thread: under the Compose test
        // harness the effect dispatcher is unconfined, and this write, left
        // on the IO worker, was measured to go unobserved for a whole wait.
        withContext(Dispatchers.Main.immediate) {
            deviceInfo = info
            limitField = info?.maxSizeBytes?.let { "%.1f".format(it / BYTES_PER_GB) } ?: ""
        }
        storageStats = withContext(Dispatchers.IO) { storageStatsForTree(context, Uri.parse(treeUri)) }
    }

    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) onFolderChanged(uri)
    }

    if (showUnpairConfirm) {
        AlertDialog(
            onDismissRequest = { showUnpairConfirm = false },
            title = { Text(stringResource(R.string.unpair_dialog_title)) },
            text = { Text(stringResource(R.string.unpair_dialog_text)) },
            confirmButton = {
                TextButton(onClick = { showUnpairConfirm = false; onUnpair() }) {
                    Text(stringResource(R.string.unpair_button), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnpairConfirm = false }) { Text(stringResource(R.string.cancel_button)) }
            },
        )
    }

    if (showNomediaWarning) {
        AlertDialog(
            onDismissRequest = { showNomediaWarning = false },
            title = { Text(stringResource(R.string.nomedia_dialog_title)) },
            text = { Text(stringResource(R.string.nomedia_dialog_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showNomediaWarning = false
                    scope.launch {
                        Prefs.setNomediaEnabled(context, true)
                        DocumentFile.fromTreeUri(context, Uri.parse(treeUri))?.let {
                            SyncEngine.ensureNomediaMarker(it, true)
                        }
                    }
                }) {
                    Text(stringResource(R.string.enable_button), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNomediaWarning = false }) { Text(stringResource(R.string.cancel_button)) }
            },
        )
    }

    if (showServerDialog) {
        EditValueDialog(
            title = stringResource(R.string.server_address_dialog_title),
            label = stringResource(R.string.url_label),
            initial = pairing.serverUrl,
            onDismiss = { showServerDialog = false },
            onConfirm = { url -> onServerUrlChanged(url.trim()); showServerDialog = false },
            // The same rule the connection screen's repair dialog applies: a
            // scheme-less address is refused at Save, not stored and met by
            // the next probe as an exception.
            isValid = ::isServerUrl,
        )
    }

    if (showLimitDialog) {
        EditValueDialog(
            title = stringResource(R.string.allocated_space_label),
            label = stringResource(R.string.limit_gb_field_label),
            initial = deviceInfo?.maxSizeBytes?.let { "%.1f".format(it / BYTES_PER_GB) } ?: "",
            onDismiss = { showLimitDialog = false },
            onConfirm = { v -> limitField = v; saveLimit(); showLimitDialog = false },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                // A tab in Full mode, with nothing to go back to; pushed in Simple mode.
                navigationIcon = {
                    nav.onBack?.let { onBack ->
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back_content_description))
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .then(adaptiveContentWidth())
                .fillMaxHeight()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Device identity card with branded avatar
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(BrandGradientBrush, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        DeviceIcon(
                            deviceInfo?.icon,
                            deviceInfo?.deviceType ?: "phone",
                            contentDescription = null,
                            tint = Color.White,
                        )
                    }
                    Column {
                        Text(stringResource(R.string.this_device_label), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline)
                        Text(deviceInfo?.name ?: "…", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }

            // What the Sync screen held: the sync's state, progress and history.
            syncStatus()

            // Connexion section
            SectionLabel(stringResource(R.string.section_connection))
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                TileRow(Icons.Filled.Cloud, stringResource(R.string.server_label), displayServerUrl(pairing.serverUrl),
                    onClick = { showServerDialog = true })
                HorizontalDivider(modifier = Modifier.padding(start = 54.dp))
                TileRow(Icons.Filled.Lock, stringResource(R.string.token_label), "••••••")
            }

            // Stockage section
            SectionLabel(stringResource(R.string.section_storage))
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                TileRow(Icons.Filled.FolderOpen, stringResource(R.string.folder_label), humanReadablePath(context, Uri.parse(treeUri)),
                    onClick = { folderLauncher.launch(null) })
                HorizontalDivider(modifier = Modifier.padding(start = 54.dp))
                TileRow(
                    icon = Icons.Filled.Storage,
                    label = stringResource(R.string.allocated_space_label),
                    value = deviceInfo?.maxSizeBytes
                        ?.let { stringResource(R.string.storage_gb_value, it / BYTES_PER_GB) }
                        ?: stringResource(R.string.unlimited),
                    onClick = { showLimitDialog = true },
                )
                storageStats?.let { stats ->
                    // No divider above this block — it's a continuation of "Allocated
                    // space" (the usage of that allocation), not its own section.
                    // Padding matches the dividers' own `start = 54.dp` so the bar
                    // lines up with them instead of the wider TileRow inset.
                    StorageUsage(
                        stats, limitBytes = deviceInfo?.maxSizeBytes,
                        modifier = Modifier.padding(start = 54.dp, end = 16.dp, top = 2.dp, bottom = 12.dp),
                    )
                }
            }

            // Synchronisation section
            SectionLabel(stringResource(R.string.section_sync))
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                // artist pictures are configured per device in the
                // web UI now (off/small/full) — no local toggle. .nomedia
                // stays local: it's about this phone's gallery.
                SwitchRow(
                    icon = Icons.Filled.HideImage,
                    label = stringResource(R.string.hide_from_gallery_label),
                    description = stringResource(R.string.hide_from_gallery_description),
                    checked = nomediaEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            showNomediaWarning = true
                        } else {
                            scope.launch {
                                Prefs.setNomediaEnabled(context, false)
                                DocumentFile.fromTreeUri(context, Uri.parse(treeUri))?.let {
                                    SyncEngine.ensureNomediaMarker(it, false)
                                }
                            }
                        }
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(start = 54.dp))
                ChoiceRow(
                    icon = Icons.Filled.FolderOff,
                    label = stringResource(R.string.missing_files_label),
                    options = listOf(
                        Prefs.MISSING_ASK to stringResource(R.string.missing_files_ask),
                        Prefs.MISSING_ALWAYS_RESYNC to stringResource(R.string.missing_files_resync),
                        Prefs.MISSING_ALWAYS_EXCLUDE to stringResource(R.string.missing_files_exclude),
                    ),
                    selected = missingFileBehavior,
                    description = stringResource(R.string.missing_files_description),
                    onSelect = { value -> scope.launch { Prefs.setMissingFileBehavior(context, value) } },
                )
                HorizontalDivider(modifier = Modifier.padding(start = 54.dp))
                // #63: 'device' source-of-truth — the server stops pruning this
                // device's tracks, so it survives a server DB loss. Optimistic
                // flip, reverted on error. Reflects the single server field.
                SwitchRow(
                    icon = Icons.Filled.Lock,
                    label = stringResource(R.string.keep_device_content_label),
                    description = stringResource(R.string.keep_device_content_description),
                    checked = deviceInfo?.sourceOfTruth == "device",
                    onCheckedChange = { keep ->
                        val value = if (keep) "device" else "server"
                        val previous = deviceInfo?.sourceOfTruth ?: "server"
                        deviceInfo = deviceInfo?.copy(sourceOfTruth = value)
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    ApiClient(context, pairing.serverUrl, pairing.token)
                                        .setSourceOfTruth(value)
                                }
                            } catch (e: Exception) {
                                deviceInfo = deviceInfo?.copy(sourceOfTruth = previous)
                                snackbarHostState.showSnackbar(
                                    e.message ?: context.getString(R.string.save_failed_generic))
                            }
                        }
                    },
                )
            }

            // #93: "when to sync" section — the network-mode row is telephony-
            // gated same as before (a Wi-Fi-only DAP has no mobile-data choice
            // to make), but the charging/idle toggle applies to every device,
            // so the section itself (and its card) is no longer gated at all.
            SectionLabel(stringResource(R.string.section_sync_conditions))
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                if (hasTelephony) {
                    ChoiceRow(
                        icon = Icons.Filled.Wifi,
                        label = stringResource(R.string.auto_sync_via_label),
                        options = listOf(
                            Prefs.NETWORK_WIFI_ONLY to stringResource(R.string.network_wifi),
                            Prefs.NETWORK_WIFI_AND_MOBILE to stringResource(R.string.network_wifi_and_mobile),
                            Prefs.NETWORK_ANY to stringResource(R.string.network_roaming),
                        ),
                        selected = networkMode,
                        onSelect = { value ->
                            scope.launch { Prefs.setNetworkMode(context, value); SyncWorker.schedulePeriodic(context) }
                        },
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 54.dp))
                }
                SwitchRow(
                    icon = Icons.Filled.BatteryChargingFull,
                    label = stringResource(R.string.charging_idle_label),
                    description = stringResource(R.string.charging_idle_description),
                    checked = requireChargingAndIdle,
                    onCheckedChange = { required ->
                        scope.launch {
                            Prefs.setRequireChargingAndIdle(context, required)
                            SyncWorker.schedulePeriodic(context)
                        }
                    },
                    modifier = Modifier.testTag("charging_idle_switch"),
                )
            }

            // Apparence section — language switcher always shown; dynamic
            // color only on Android 12+ (API 31).
            SectionLabel(stringResource(R.string.section_appearance))
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                val languageNames = mapOf(
                    AppLanguage.SYSTEM to stringResource(R.string.language_system),
                    "en" to "English",
                    "fr" to "Français",
                )
                if (AppLanguage.systemManaged) {
                    // API 33+: the system's per-app language page is the one
                    // place to change it; it recreates the activity on return.
                    TileRow(
                        icon = Icons.Filled.Language,
                        label = stringResource(R.string.language_label),
                        value = AppLanguage.choice(context).let { tag ->
                            languageNames[tag] ?: java.util.Locale.forLanguageTag(tag).displayName
                        },
                        onClick = { AppLanguage.openSystemSettings(context) },
                    )
                } else {
                    ChoiceRow(
                        icon = Icons.Filled.Language,
                        label = stringResource(R.string.language_label),
                        options = languageNames.toList(),
                        selected = AppLanguage.choice(context),
                        onSelect = { tag -> AppLanguage.choose(context, tag) },
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(start = 54.dp))
                AppModeRow()
                HorizontalDivider(modifier = Modifier.padding(start = 54.dp))
                BrandThemeRow()
                HorizontalDivider(modifier = Modifier.padding(start = 54.dp))
                // The launcher icon changes once the app leaves the foreground
                // (LauncherIcon).
                SwitchRow(
                    icon = Icons.Filled.AppShortcut,
                    label = stringResource(R.string.match_icon_label),
                    description = stringResource(R.string.match_icon_description),
                    checked = matchIconToTheme,
                    onCheckedChange = { enabled ->
                        scope.launch { Prefs.setMatchIconToTheme(context, enabled) }
                    },
                    modifier = Modifier.testTag("match_icon_switch"),
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    HorizontalDivider(modifier = Modifier.padding(start = 54.dp))
                    SwitchRow(
                        icon = Icons.Filled.Palette,
                        label = stringResource(R.string.dynamic_color_label),
                        description = stringResource(R.string.dynamic_color_description),
                        checked = useDynamicColor,
                        onCheckedChange = { enabled ->
                            scope.launch { Prefs.setUseDynamicColor(context, enabled) }
                        },
                        // #61: SwitchRow's own toggleable() already merges this
                        // row into one semantics node; testTag just makes it
                        // addressable in SettingsScreenTest, since the other
                        // rows' labels aren't unique enough to disambiguate
                        // three switches on this screen otherwise.
                        modifier = Modifier.testTag("dynamic_color_switch"),
                    )
                }
            }

            // About section
            SectionLabel(stringResource(R.string.section_about))
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                TileRow(
                    Icons.Filled.Info,
                    stringResource(R.string.about_tile_label),
                    appVersionName(context),
                    onClick = nav.onOpenAbout,
                )
            }

            OutlinedButton(
                onClick = { showUnpairConfirm = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(R.string.unpair_device_button))
            }
        }
        }
    }
}

internal fun appVersionName(context: android.content.Context): String =
    try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (ignored: Exception) { "?" }

internal fun readAsset(context: android.content.Context, name: String): String =
    try {
        context.assets.open(name).bufferedReader().use { it.readText() }
    } catch (ignored: Exception) { "" }

/** Simple, Full, or Auto, which says what it decided on this device. A
 * change applies at once: the app follows the stored choice. */
@Composable
private fun AppModeRow() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val choice by Prefs.appModeChoice(context).collectAsState(initial = AppModeChoice.AUTO)
    val device = LocalDeviceProfile.current ?: remember { DeviceProfile.of(context) }
    val simple = stringResource(R.string.app_mode_simple)
    val full = stringResource(R.string.app_mode_full)
    val auto = stringResource(R.string.app_mode_auto, if (autoAppMode(device) == AppMode.SIMPLE) simple else full)
    ChoiceRow(
        icon = Icons.Filled.Dashboard,
        label = stringResource(R.string.app_mode_label),
        description = stringResource(R.string.app_mode_description),
        options = listOf(AppModeChoice.AUTO to auto, AppModeChoice.SIMPLE to simple, AppModeChoice.FULL to full),
        selected = choice,
        onSelect = { picked -> scope.launch { Prefs.setAppModeChoice(context, picked) } },
    )
}

/** The five brand themes, each previewed as its own lockup on its own
 * ground, so the choice shows the theme rather than the current one. Applies
 * at once: MainActivity follows the stored key. */
@Composable
private fun BrandThemeRow() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val current = LocalBrand.current
    var open by remember { mutableStateOf(false) }
    TileRow(
        icon = Icons.Filled.Palette,
        label = stringResource(R.string.brand_theme_label),
        value = current.displayName,
        onClick = { open = true },
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.brand_theme_label)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()).testTag("brand_theme_options"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BrandTheme.entries.forEach { theme ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(selected = theme == current, role = Role.RadioButton) {
                                    open = false
                                    scope.launch { Prefs.setBrandTheme(context, theme.key) }
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            RadioButton(selected = theme == current, onClick = null)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Box(
                                    Modifier
                                        .background(theme.ground, RoundedCornerShape(8.dp))
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                ) {
                                    BrandImage(
                                        R.drawable.ic_trobar_lockup, contentDescription = null, brand = theme,
                                        modifier = Modifier.height(28.dp),
                                    )
                                }
                                Text(theme.displayName, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    stringResource(brandThemeDescription(theme)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel_button)) } },
        )
    }
}

private fun brandThemeDescription(theme: BrandTheme): Int = when (theme) {
    BrandTheme.TROUBADOUR -> R.string.brand_theme_troubadour
    BrandTheme.NUECH -> R.string.brand_theme_nuech
    BrandTheme.GARRIGA -> R.string.brand_theme_garriga
    BrandTheme.PEIRA -> R.string.brand_theme_peira
    BrandTheme.CONTRASTE -> R.string.brand_theme_contraste
}

