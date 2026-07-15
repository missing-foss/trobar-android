// SPDX-License-Identifier: GPL-3.0-or-later
package com.mfoss.trobar

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tablet
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.documentfile.provider.DocumentFile
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Upgrade any legacy plaintext token to Keystore-encrypted at rest.
        lifecycleScope.launch { Prefs.migrateTokenIfNeeded(this@MainActivity) }
        setContent {
            val dynamicColor by Prefs.useDynamicColor(this).collectAsState(initial = false)
            TrobarTheme(dynamicColor = dynamicColor) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot()
                }
            }
        }
    }

    @Composable
    fun AppRoot() {
        val pairing by Prefs.pairing(this).collectAsState(initial = NOT_LOADED)
        val treeUri by Prefs.treeUri(this).collectAsState(initial = null)
        var showSettings by remember { mutableStateOf(false) }
        var showAbout by remember { mutableStateOf(false) }

        when {
            showAbout -> AboutScreen(onBack = { showAbout = false })
            pairing === NOT_LOADED -> Unit // still loading prefs, render nothing yet
            pairing == null -> PairingScreen(onPaired = { url, token ->
                lifecycleScope.launch { Prefs.setPairing(this@MainActivity, url, token) }
            })
            treeUri == null -> FolderPickerScreen(onPicked = { uri ->
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                lifecycleScope.launch {
                    Prefs.setTreeUri(this@MainActivity, uri.toString())
                    SyncWorker.schedulePeriodic(this@MainActivity)
                }
            })
            showSettings -> SettingsScreen(
                pairing = pairing!!,
                treeUri = treeUri!!,
                onBack = { showSettings = false },
                onOpenAbout = { showAbout = true },
                onServerUrlChanged = { url -> lifecycleScope.launch { Prefs.setServerUrl(this@MainActivity, url) } },
                onFolderChanged = { newUri ->
                    try {
                        contentResolver.releasePersistableUriPermission(
                            Uri.parse(treeUri),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                        )
                    } catch (e: Exception) { /* already gone — fine */ }
                    contentResolver.takePersistableUriPermission(
                        newUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                    lifecycleScope.launch { Prefs.setTreeUri(this@MainActivity, newUri.toString()) }
                },
                onUnpair = {
                    lifecycleScope.launch {
                        Prefs.clearPairing(this@MainActivity)
                        showSettings = false
                    }
                },
            )
            else -> StatusScreen(
                pairing = pairing!!,
                onOpenSettings = { showSettings = true },
            )
        }
    }

    companion object {
        // Distinguishes "DataStore hasn't emitted yet" from "no pairing saved" —
        // both surface as null/initial otherwise, which would flash the
        // pairing screen for a frame even when already paired.
        private val NOT_LOADED = Prefs.Pairing("", "")
    }
}

/** "The Bard" mark: an original troubadour mid-strum on a lute
 * whose rosette carries the Side A burgundy label. A single flat artwork —
 * no more plinth/disc/tonearm layering, and nothing rotates. While syncing,
 * musical notes rise from the lute's soundhole (three staggered copies,
 * fading in then out), mirroring the web header's CSS loop in logo.css. */
@Composable
fun AppLogo(size: Dp, modifier: Modifier = Modifier, syncing: Boolean = false) {
    Box(modifier = modifier.size(size)) {
        Image(
            painter = painterResource(id = R.drawable.logo_bard),
            contentDescription = "Trobar",
            modifier = Modifier.fillMaxSize(),
        )
        if (syncing) {
            val transition = rememberInfiniteTransition(label = "logo-notes")
            val t by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(2500, easing = LinearEasing)),
                label = "note-progress",
            )
            RisingNote(t, phase = 0f, drift = 0.16f, glyph = "♪", markSize = size)
            RisingNote(t, phase = 0.34f, drift = 0.05f, glyph = "♫", markSize = size)
            RisingNote(t, phase = 0.68f, drift = -0.14f, glyph = "♪", markSize = size)
        }
    }
}

/** One looping sync note: spawns at the soundhole (right-of-center, lower
 * half of the artwork), rises ~58% of the mark height with a slight
 * horizontal drift, fading in then out. `phase` staggers the copies so the
 * 2.5s loop reads as continuous rather than pulsed. */
@Composable
private fun RisingNote(t: Float, phase: Float, drift: Float, glyph: String, markSize: Dp) {
    val p = (t + phase) % 1f
    val noteAlpha = when {
        p < 0.18f -> p / 0.18f
        p < 0.72f -> 1f
        else -> (1f - p) / 0.28f
    }
    val noteScale = 0.7f + 0.3f * p
    Text(
        glyph,
        color = MaterialTheme.colorScheme.secondary,
        fontSize = (markSize.value * 0.26f).sp,
        modifier = Modifier
            .offset {
                IntOffset(
                    (markSize.toPx() * (0.63f + drift * p)).roundToInt(),
                    (markSize.toPx() * (0.64f - 0.58f * p)).roundToInt(),
                )
            }
            .graphicsLayer { alpha = noteAlpha; scaleX = noteScale; scaleY = noteScale },
    )
}

/** "Trob" in the ambient text color, "ar" in the brand rose accent —
 * matches the web app's wordmark treatment (`Tro<b>bar</b>`). */
@Composable
fun trobarWordmark(): AnnotatedString = buildAnnotatedString {
    append("Trob")
    withStyle(SpanStyle(color = MaterialTheme.colorScheme.secondary)) { append("ar") }
}

/** Best-effort human-readable rendering of a SAF tree URI — not a real
 * filesystem path (SAF doesn't expose one), but the decoded document ID
 * ("primary:Music/Trobar") is recognizable enough to confirm the right
 * folder is selected. */
fun humanReadablePath(context: Context, uri: Uri): String = try {
    DocumentsContract.getTreeDocumentId(uri).replace("primary:", context.getString(R.string.internal_storage_prefix))
} catch (e: Exception) {
    uri.toString()
}

@Composable
fun PairingScreen(onPaired: (String, String) -> Unit) {
    var serverUrl by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val raw = result.contents ?: return@rememberLauncherForActivityResult
        try {
            val json = JSONObject(raw)
            onPaired(json.getString("server_url"), json.getString("token"))
        } catch (e: Exception) {
            // Not JSON — ignore, user can still fill the fields manually below.
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .background(BrandGradientBrush),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppLogo(72.dp)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.pairing_title), style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Text(
                    stringResource(R.string.pairing_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(
                onClick = { scanLauncher.launch(ScanOptions().setOrientationLocked(false)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.scan_qr_button)) }
            OutlinedTextField(
                value = serverUrl, onValueChange = { serverUrl = it },
                label = { Text(stringResource(R.string.server_url_label)) }, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token, onValueChange = { token = it },
                label = { Text(stringResource(R.string.token_label)) }, modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onPaired(serverUrl, token) },
                enabled = serverUrl.isNotBlank() && token.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.validate_button)) }
        }
    }
}

/** Error text + a "Copier" button — added because plain on-screen error text
 * isn't useful for reporting a bug (no way to get the exact message off the
 * phone without retyping it by hand). Kept as persistent inline text rather
 * than a Snackbar: lastSyncError survives app restarts and should still be
 * visible (and copyable) whenever this screen is open, not just briefly
 * after the failure happens. */
@Composable
fun CopyableError(text: String, onDismiss: (() -> Unit)? = null) {
    val clipboard = LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text, color = MaterialTheme.colorScheme.error)
        Row {
            TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) {
                Text(stringResource(R.string.copy_error_button))
            }
            if (onDismiss != null) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.clear_error_button))
                }
            }
        }
    }
}

@Composable
fun FolderPickerScreen(onPicked: (android.net.Uri) -> Unit) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) onPicked(uri)
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .background(BrandGradientBrush),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppLogo(72.dp)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.folder_picker_title), style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Text(
                    stringResource(R.string.folder_picker_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                )
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { launcher.launch(null) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.choose_folder_button)) }
        }
    }
}

// one entry of the "found missing locally, awaiting a decision"
// batch persisted by SyncWorker (see Prefs.pendingMissingTracks).
data class MissingTrackInfo(val trackId: Long, val relativePath: String)

private fun parsePendingMissing(json: String?): List<MissingTrackInfo> {
    if (json == null) return emptyList()
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            MissingTrackInfo(o.getLong("track_id"), o.getString("relative_path"))
        }
    } catch (e: Exception) {
        emptyList()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(pairing: Prefs.Pairing, onOpenSettings: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var deviceName by remember { mutableStateOf<String?>(null) }
    val lastSyncAt by Prefs.lastSyncAt(context).collectAsState(initial = null)
    val lastSyncError by Prefs.lastSyncError(context).collectAsState(initial = null)
    val pendingMissingJson by Prefs.pendingMissingTracks(context).collectAsState(initial = null)
    val pendingMissing = remember(pendingMissingJson) { parsePendingMissing(pendingMissingJson) }
    var resolvingMissing by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Manual sync runs as a WorkManager job (not a plain Activity-scoped
    // coroutine) — observed here via its unique-work Flow — for the same
    // reason the periodic job already does: a long download of a large
    // FLAC needs to survive the screen turning off, which a bare coroutine
    // tied to this Composable's lifecycle would not (this is exactly what
    // produced "Software caused connection abort" in production).
    val workInfos by remember {
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(SyncWorker.MANUAL_WORK_NAME)
    }.collectAsState(initial = emptyList())
    val workInfo = workInfos.firstOrNull()
    val syncing = workInfo?.state == WorkInfo.State.RUNNING || workInfo?.state == WorkInfo.State.ENQUEUED
    val done = workInfo?.progress?.getInt(SyncWorker.KEY_DONE, 0) ?: 0
    val total = workInfo?.progress?.getInt(SyncWorker.KEY_TOTAL, 0) ?: 0
    val failureText = if (workInfo?.state == WorkInfo.State.FAILED) {
        context.getString(R.string.sync_failure, workInfo.outputData.toSyncResult().error)
    } else null

    // Successful-sync confirmation is transient (Snackbar) — it's a "yes,
    // that worked" toast, not something the user needs to come back to.
    // Failures stay as persistent on-screen text below (via CopyableError)
    // since the user may want to copy the message to report a bug.
    LaunchedEffect(workInfo?.id, workInfo?.state) {
        if (workInfo?.state == WorkInfo.State.SUCCEEDED) {
            val r = workInfo.outputData.toSyncResult()
            val msg = context.resources.getQuantityString(R.plurals.sync_downloaded, r.downloaded, r.downloaded) +
                ", " + context.resources.getQuantityString(R.plurals.sync_deleted, r.deleted, r.deleted) +
                if (r.failed > 0) ", " + context.resources.getQuantityString(R.plurals.sync_failed, r.failed, r.failed) else ""
            snackbarHostState.showSnackbar(msg)
        }
    }

    LaunchedEffect(pairing) {
        deviceName = try {
            withContext(Dispatchers.IO) { ApiClient(context, pairing.serverUrl, pairing.token).getDeviceInfo().name }
        } catch (e: Exception) { null }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(trobarWordmark(), fontFamily = FredokaFamily, fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Hero area — logo + app name + device name
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                AppLogo(
                    size = 160.dp,
                    modifier = Modifier.clickable(enabled = !syncing) { scope.launch { SyncWorker.triggerManualSync(context) } },
                    syncing = syncing,
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    trobarWordmark(),
                    style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FredokaFamily, fontWeight = FontWeight.SemiBold),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    deviceName ?: pairing.serverUrl,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            // Status card — sync state, progress, last sync
            Surface(
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        if (syncing) stringResource(R.string.sync_in_progress) else stringResource(R.string.tap_logo_to_sync),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (syncing) {
                        if (total > 0) {
                            LinearProgressIndicator(
                                progress = { done.toFloat() / total.toFloat() },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                stringResource(R.string.sync_progress_count, done, total),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                    HorizontalDivider()
                    Text(
                        stringResource(R.string.last_sync, lastSyncAt ?: stringResource(R.string.never)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    // A failed run leaves the same error on two surfaces: the
                    // persisted last-error pref AND WorkManager's FAILED record.
                    // Show one line (current run's failure wins over the stored
                    // one) and have dismiss clear both — with two lines only the
                    // first was dismissable (follow-up).
                    (failureText ?: lastSyncError?.let { stringResource(R.string.last_error, it) })?.let { msg ->
                        CopyableError(
                            msg,
                            onDismiss = {
                                scope.launch {
                                    Prefs.clearSyncError(context)
                                    WorkManager.getInstance(context).pruneWork()
                                }
                            },
                        )
                    }
                    if (pendingMissing.isNotEmpty()) {
                        HorizontalDivider()
                        Text(
                            context.resources.getQuantityString(
                                R.plurals.missing_tracks_notice, pendingMissing.size, pendingMissing.size,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                enabled = !resolvingMissing,
                                onClick = {
                                    resolvingMissing = true
                                    scope.launch {
                                        try {
                                            withContext(Dispatchers.IO) {
                                                ApiClient(context, pairing.serverUrl, pairing.token)
                                                    .resolveMissingTracks(
                                                        redownload = pendingMissing.map { it.trackId },
                                                        exclude = emptyList(),
                                                    )
                                            }
                                            Prefs.setPendingMissingTracks(context, null)
                                            SyncWorker.triggerManualSync(context)
                                        } catch (e: Exception) {
                                            snackbarHostState.showSnackbar(e.message ?: context.getString(R.string.save_failed_generic))
                                        }
                                        resolvingMissing = false
                                    }
                                },
                            ) { Text(stringResource(R.string.missing_tracks_resync_button)) }
                            TextButton(
                                enabled = !resolvingMissing,
                                onClick = {
                                    resolvingMissing = true
                                    scope.launch {
                                        try {
                                            withContext(Dispatchers.IO) {
                                                ApiClient(context, pairing.serverUrl, pairing.token)
                                                    .resolveMissingTracks(
                                                        redownload = emptyList(),
                                                        exclude = pendingMissing.map { it.trackId },
                                                    )
                                            }
                                            Prefs.setPendingMissingTracks(context, null)
                                        } catch (e: Exception) {
                                            snackbarHostState.showSnackbar(e.message ?: context.getString(R.string.save_failed_generic))
                                        }
                                        resolvingMissing = false
                                    }
                                },
                            ) { Text(stringResource(R.string.missing_tracks_exclude_button)) }
                        }
                    }
                }
            }
        }
    }
}

private fun formatGB(bytes: Long?): String = if (bytes == null) "?" else "%.1f".format(bytes / 1e9)

/** Mirrors the web UI's deviceIconSvg() mapping — phone,
 * tablet, watch, dap (dedicated audio player), sdcard (bare card). 'android'
 * kept as a phone-equivalent alias for the same pre-migration-row reason the
 * web side keeps it: harmless fallback, not the current default. */
private fun deviceTypeIcon(deviceType: String) = when (deviceType) {
    "tablet" -> Icons.Filled.Tablet
    "watch" -> Icons.Filled.Watch
    "dap" -> Icons.Filled.Headphones
    "sdcard" -> Icons.Filled.SdCard
    else -> Icons.Filled.Smartphone // "phone", "android", and any unknown value
}

/** Display-only: drops the scheme and trailing slash so the settings tile
 * shows just the domain. The stored/edited value (used for requests) always
 * keeps the full URL — only this rendering is trimmed. */
private fun displayServerUrl(url: String): String =
    url.removePrefix("https://").removePrefix("http://").removeSuffix("/")

/* Every settings row shares one geometry and type hierarchy: 22dp primary
 * icon, title in bodyLarge, supporting line (current value or description)
 * in labelSmall/outline, trailing widget (chevron or switch). Dividers
 * between rows inset by 54dp to clear the icon column. */

@Composable
fun TileRow(icon: ImageVector, label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline)
        }
        if (onClick != null)
            Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = null,
                modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline)
    }
}

@Composable
fun SwitchRow(
    icon: ImageVector,
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** A TileRow whose value is picked from a radio list in a popup — replaces
 * the segmented button rows, whose three cells clipped the longer French
 * labels. The row shows the current choice; the dialog shows the full-length
 * option labels plus the setting's description when there is one. */
@Composable
fun ChoiceRow(
    icon: ImageVector,
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    description: String? = null,
    onSelect: (String) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    TileRow(icon, label, options.firstOrNull { it.first == selected }?.second ?: "",
        onClick = { showDialog = true })
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(label) },
            text = {
                Column {
                    if (description != null) {
                        Text(description, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(bottom = 8.dp))
                    }
                    options.forEach { (value, optionLabel) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showDialog = false; onSelect(value) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            RadioButton(selected = selected == value, onClick = null)
                            Text(optionLabel, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text(stringResource(R.string.cancel_button)) }
            },
        )
    }
}

@Composable
fun EditValueDialog(
    title: String,
    label: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var field by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = field, onValueChange = { field = it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(field) }) { Text(stringResource(R.string.save_button)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_button)) } },
    )
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(start = 4.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    pairing: Prefs.Pairing,
    treeUri: String,
    onBack: () -> Unit,
    onOpenAbout: () -> Unit,
    onServerUrlChanged: (String) -> Unit,
    onFolderChanged: (Uri) -> Unit,
    onUnpair: () -> Unit,
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
    val useDynamicColor by Prefs.useDynamicColor(context).collectAsState(initial = false)
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
        } catch (e: Exception) { null }
        deviceInfo = info
        limitField = info?.maxSizeBytes?.let { "%.1f".format(it / 1e9) } ?: ""
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
            onConfirm = { url -> onServerUrlChanged(url); showServerDialog = false },
        )
    }

    if (showLimitDialog) {
        EditValueDialog(
            title = stringResource(R.string.allocated_space_label),
            label = stringResource(R.string.limit_gb_field_label),
            initial = deviceInfo?.maxSizeBytes?.let { "%.1f".format(it / 1e9) } ?: "",
            onDismiss = { showLimitDialog = false },
            onConfirm = { v -> limitField = v; saveLimit(); showLimitDialog = false },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back_content_description))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
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
                        Icon(
                            deviceTypeIcon(deviceInfo?.deviceType ?: "phone"),
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
                    value = deviceInfo?.maxSizeBytes?.let { stringResource(R.string.storage_gb_value, it / 1e9) } ?: stringResource(R.string.unlimited),
                    onClick = { showLimitDialog = true },
                )
                storageStats?.let { stats ->
                    // No divider above this block — it's a continuation of "Allocated
                    // space" (the usage of that allocation), not its own section.
                    // Padding matches the dividers' own `start = 54.dp` so the bar
                    // lines up with them instead of the wider TileRow inset.
                    Column(
                        modifier = Modifier.padding(start = 54.dp, end = 16.dp, top = 2.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        LinearProgressIndicator(
                            progress = { (stats.totalBytes - stats.freeBytes).toFloat() / stats.totalBytes.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            stringResource(R.string.storage_free_of_total, formatGB(stats.freeBytes), formatGB(stats.totalBytes)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        val limit = deviceInfo?.maxSizeBytes
                        if (limit != null && stats.freeBytes != null && limit > stats.freeBytes) {
                            Text(stringResource(R.string.limit_exceeds_free_space),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
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
            }

            // Network section — only shown on devices with a cellular radio
            if (hasTelephony) {
                SectionLabel(stringResource(R.string.section_network))
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
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
                }
            }

            // Apparence section — language switcher always shown; dynamic
            // color only on Android 12+ (API 31).
            SectionLabel(stringResource(R.string.section_appearance))
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                val currentLocales = AppCompatDelegate.getApplicationLocales()
                val currentTag = if (currentLocales.isEmpty) "system" else currentLocales[0]?.language
                ChoiceRow(
                    icon = Icons.Filled.Language,
                    label = stringResource(R.string.language_label),
                    options = listOf(
                        "system" to stringResource(R.string.language_system),
                        "en" to "English",
                        "fr" to "Français",
                    ),
                    selected = currentTag ?: "system",
                    onSelect = { tag ->
                        AppCompatDelegate.setApplicationLocales(
                            if (tag == "system") LocaleListCompat.getEmptyLocaleList()
                            else LocaleListCompat.forLanguageTags(tag),
                        )
                    },
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
                    onClick = onOpenAbout,
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

private fun appVersionName(context: android.content.Context): String =
    try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (e: Exception) { "?" }

private fun readAsset(context: android.content.Context, name: String): String =
    try {
        context.assets.open(name).bufferedReader().use { it.readText() }
    } catch (e: Exception) { "" }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var updateAvailable by remember { mutableStateOf(false) }
    var shownDoc by remember { mutableStateOf<String?>(null) } // asset name, or null
    // Easter egg: 5 taps on the bard wakes it up for a duel. Resets on its
    // own after either a trigger or leaving the screen — no need to reset
    // the counter explicitly on a stray tap since it's just "taps so far",
    // never decremented.
    var bardTapCount by remember { mutableStateOf(0) }
    var showTicTacToe by remember { mutableStateOf(false) }

    fun openUrl(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) { /* no browser — nothing sensible to do */ }
    }

    // User-initiated only: one GitHub API call per button press,
    // never automatic — consistent with the no-telemetry stance.
    fun checkForUpdate() {
        scope.launch {
            checking = true
            updateStatus = context.getString(R.string.update_checking)
            updateAvailable = false
            updateStatus = try {
                val latest = withContext(Dispatchers.IO) {
                    val req = okhttp3.Request.Builder()
                        .url("https://api.github.com/repos/missing-foss/trobar-android/releases/latest")
                        .build()
                    OkHttpClient().newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                        org.json.JSONObject(resp.body!!.string()).optString("tag_name")
                    }
                }
                val latestVersion = latest.removePrefix("android-v")
                if (latestVersion.isNotEmpty() && latestVersion != appVersionName(context)) {
                    updateAvailable = true
                    context.getString(R.string.update_available, latest)
                } else {
                    context.getString(R.string.update_up_to_date, appVersionName(context))
                }
            } catch (e: Exception) {
                context.getString(R.string.update_check_failed, e.message ?: "?")
            }
            checking = false
        }
    }

    shownDoc?.let { asset ->
        AlertDialog(
            onDismissRequest = { shownDoc = null },
            title = {
                Text(stringResource(
                    if (asset == "LICENSE") R.string.license_dialog_title else R.string.notices_dialog_title))
            },
            text = {
                val docText = remember(asset) { readAsset(context, asset) }
                // heightIn first (bounds the viewport), THEN verticalScroll —
                // the reverse order just truncates the text with nothing to
                // scroll. fillMaxWidth so long license lines wrap.
                Text(
                    docText,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { shownDoc = null }) { Text(stringResource(R.string.close_button)) }
            },
        )
    }

    if (showTicTacToe) {
        TicTacToeDialog(onDismiss = { showTicTacToe = false })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back_content_description))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppLogo(
                size = 84.dp,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null, // a growing tap streak shouldn't visibly ripple/hint before it's earned
                ) {
                    bardTapCount++
                    if (bardTapCount >= 5) {
                        bardTapCount = 0
                        showTicTacToe = true
                    }
                },
            )
            Text(trobarWordmark(), fontFamily = FredokaFamily, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.about_version, appVersionName(context)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { checkForUpdate() }, enabled = !checking) {
                    Text(stringResource(R.string.check_updates_button))
                }
                // Liberapay's brand yellow — a vendored "button", no external
                // widget script (a deliberate no-CDN choice).
                Button(
                    onClick = { openUrl("https://liberapay.com/Trobar/donate") },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFF6C915), contentColor = Color(0xFF1A171B)),
                ) {
                    Text(stringResource(R.string.donate_button))
                }
            }
            updateStatus?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (updateAvailable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                )
            }

            SectionLabel(stringResource(R.string.about_links_section))
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                TileRow(Icons.Filled.Info, stringResource(R.string.about_documentation), "",
                    onClick = { openUrl("https://github.com/missing-foss/trobar-server/blob/main/docs/clients.md") })
                HorizontalDivider(modifier = Modifier.padding(start = 54.dp))
                TileRow(Icons.Filled.Cloud, stringResource(R.string.about_source_code), "",
                    onClick = { openUrl("https://github.com/missing-foss/trobar-android") })
                HorizontalDivider(modifier = Modifier.padding(start = 54.dp))
                TileRow(Icons.Filled.Settings, stringResource(R.string.about_report_issue), "",
                    onClick = { openUrl("https://github.com/missing-foss/trobar-android/issues/new/choose") })
                HorizontalDivider(modifier = Modifier.padding(start = 54.dp))
                TileRow(Icons.Filled.Storage, stringResource(R.string.about_releases), "",
                    onClick = { openUrl("https://github.com/missing-foss/trobar-android/releases") })
            }

            SectionLabel(stringResource(R.string.about_licenses_section))
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.about_license_summary), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { shownDoc = "LICENSE" }) {
                            Text(stringResource(R.string.show_license_button))
                        }
                        OutlinedButton(onClick = { shownDoc = "THIRD_PARTY_NOTICES.md" }) {
                            Text(stringResource(R.string.show_notices_button))
                        }
                    }
                }
            }
        }
    }
}

/** Easter egg (5 taps on the bard, About screen). Human plays the note
 * glyph, the bard answers with the other one — matching the rising-note
 * motif in AppLogo/RisingNote rather than plain X/O. The bard plays to
 * win-or-block first, otherwise centre/corner/edge — beatable on purpose
 * (see the issue: "doesn't need to be unbeatable"), not a minimax grind. */
private enum class TttMark { NONE, PLAYER, BARD }

private val TTT_LINES = listOf(
    listOf(0, 1, 2), listOf(3, 4, 5), listOf(6, 7, 8), // rows
    listOf(0, 3, 6), listOf(1, 4, 7), listOf(2, 5, 8), // columns
    listOf(0, 4, 8), listOf(2, 4, 6),                  // diagonals
)

private fun tttWinner(board: List<TttMark>): TttMark? {
    for ((a, b, c) in TTT_LINES) {
        if (board[a] != TttMark.NONE && board[a] == board[b] && board[a] == board[c]) return board[a]
    }
    return null
}

/** Win if it can, block if it must, otherwise centre > corner > edge. No
 * lookahead beyond one ply each way — a player who plays well can beat it,
 * which is the point. */
private fun bardMove(board: List<TttMark>): List<TttMark> {
    val empty = board.indices.filter { board[it] == TttMark.NONE }
    fun wouldWin(i: Int, mark: TttMark): Boolean {
        val trial = board.toMutableList()
        trial[i] = mark
        return tttWinner(trial) == mark
    }
    val move = empty.firstOrNull { wouldWin(it, TttMark.BARD) }
        ?: empty.firstOrNull { wouldWin(it, TttMark.PLAYER) }
        ?: listOf(4, 0, 2, 6, 8, 1, 3, 5, 7).firstOrNull { it in empty }
        ?: return board
    val result = board.toMutableList()
    result[move] = TttMark.BARD
    return result
}

@Composable
private fun TicTacToeDialog(onDismiss: () -> Unit) {
    var board by remember { mutableStateOf(List(9) { TttMark.NONE }) }
    var result by remember { mutableStateOf<TttMark?>(null) } // null = still playing; NONE = draw

    fun reset() {
        board = List(9) { TttMark.NONE }
        result = null
    }

    fun playerTap(i: Int) {
        if (result != null || board[i] != TttMark.NONE) return
        var next = board.toMutableList().also { it[i] = TttMark.PLAYER }
        val playerWin = tttWinner(next)
        if (playerWin != null) {
            board = next; result = playerWin
            return
        }
        if (next.none { it == TttMark.NONE }) {
            board = next; result = TttMark.NONE
            return
        }
        next = bardMove(next).toMutableList()
        board = next
        result = tttWinner(next) ?: if (next.none { it == TttMark.NONE }) TttMark.NONE else null
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.tictactoe_title),
                    fontFamily = FredokaFamily,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleLarge,
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (row in 0..2) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (col in 0..2) {
                                val i = row * 3 + col
                                Surface(
                                    modifier = Modifier
                                        .width(64.dp)
                                        .aspectRatio(1f)
                                        .clickable { playerTap(i) },
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                ) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                        Text(
                                            when (board[i]) {
                                                TttMark.PLAYER -> "♪" // ♪ — matches RisingNote's glyphs
                                                TttMark.BARD -> "♫"   // ♫
                                                TttMark.NONE -> ""
                                            },
                                            style = MaterialTheme.typography.headlineMedium,
                                            color = MaterialTheme.colorScheme.secondary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                result?.let {
                    Text(
                        stringResource(
                            when (it) {
                                TttMark.PLAYER -> R.string.tictactoe_player_win
                                TttMark.BARD -> R.string.tictactoe_bard_win
                                TttMark.NONE -> R.string.tictactoe_draw
                            }
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (result != null) {
                        OutlinedButton(onClick = { reset() }) { Text(stringResource(R.string.tictactoe_play_again)) }
                    }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.close_button)) }
                }
            }
        }
    }
}
