// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import android.content.Context
import android.icu.text.RelativeDateTimeFormatter
import android.icu.util.ULocale
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException

data class MissingTrackInfo(val trackId: Long, val relativePath: String)

internal fun parsePendingMissing(json: String?): List<MissingTrackInfo> {
    if (json == null) return emptyList()
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            MissingTrackInfo(o.getLong("track_id"), o.getString("relative_path"))
        }
    } catch (ignored: Exception) {
        emptyList()
    }
}

/** #34: which server-connectivity problem the app hit, if any. */
enum class ConnState { OK, UNREACHABLE, UNAUTHORIZED }

/** Where a sync stands, as every tab shows it. Read from the two unique
 * works (the manual one and the scheduled one) and the stored last error.
 * The scheduled work sits ENQUEUED between runs, so only its RUNNING counts;
 * a manual work that is ENQUEUED is one held back by its constraints. */
enum class SyncPhase { IDLE, WAITING, RUNNING, SUCCEEDED, FAILED }

data class SyncStatus(
    val phase: SyncPhase,
    val done: Int = 0,
    val total: Int = 0,
    val scanning: Boolean = false,
    val result: WorkSyncResult? = null,
    val error: String? = null,
    val waitingReason: Int? = null,
    val plan: SyncPlan? = null,
)

/** "142 tracks, 4.2 GB to download, 18.6 GB free". */
internal fun syncPlanText(context: Context, plan: SyncPlan): String {
    val tracks = context.resources.getQuantityString(R.plurals.sync_plan_tracks, plan.tracks, plan.tracks)
    val bytes = SyncEngine.gigabytes(plan.bytes, up = true)
    val free = plan.freeBytes ?: return context.getString(R.string.sync_plan_no_free, tracks, bytes)
    return context.getString(R.string.sync_plan, tracks, bytes, SyncEngine.gigabytes(free, up = false))
}

/** "2 hours ago", in [locale]; "now" under a minute. */
internal fun relativeSyncTime(atMillis: Long, nowMillis: Long, locale: Locale): String {
    val format = RelativeDateTimeFormatter.getInstance(ULocale.forLocale(locale))
    val minutes = (nowMillis - atMillis) / MILLIS_PER_MINUTE
    val hours = minutes / MINUTES_PER_HOUR
    val days = hours / HOURS_PER_DAY
    return when {
        minutes < 1 -> format.format(RelativeDateTimeFormatter.Direction.PLAIN, RelativeDateTimeFormatter.AbsoluteUnit.NOW)
        hours < 1 -> format.format(minutes.toDouble(), RelativeDateTimeFormatter.Direction.LAST, RelativeDateTimeFormatter.RelativeUnit.MINUTES)
        days < 1 -> format.format(hours.toDouble(), RelativeDateTimeFormatter.Direction.LAST, RelativeDateTimeFormatter.RelativeUnit.HOURS)
        else -> format.format(days.toDouble(), RelativeDateTimeFormatter.Direction.LAST, RelativeDateTimeFormatter.RelativeUnit.DAYS)
    }
}

private fun planFrom(progress: Data): SyncPlan? {
    val tracks = progress.getInt(SyncWorker.KEY_PLAN_TRACKS, 0)
    if (tracks <= 0) return null
    val free = progress.getLong(SyncWorker.KEY_PLAN_FREE, -1L)
    return SyncPlan(tracks, progress.getLong(SyncWorker.KEY_PLAN_BYTES, 0L), free.takeIf { it >= 0 })
}

internal fun syncStatusOf(manual: WorkInfo?, scheduled: WorkInfo?, lastError: String?, blockedReason: Int?): SyncStatus {
    val running = listOfNotNull(manual, scheduled).firstOrNull { it.state == WorkInfo.State.RUNNING }
    return when {
        running != null -> SyncStatus(
            SyncPhase.RUNNING,
            done = running.progress.getInt(SyncWorker.KEY_DONE, 0),
            total = running.progress.getInt(SyncWorker.KEY_TOTAL, 0),
            scanning = running.progress.getBoolean(SyncWorker.KEY_SCANNING, false),
            plan = planFrom(running.progress),
        )
        manual?.state == WorkInfo.State.ENQUEUED || manual?.state == WorkInfo.State.BLOCKED ->
            SyncStatus(SyncPhase.WAITING, waitingReason = blockedReason)
        manual?.state == WorkInfo.State.FAILED -> SyncStatus(SyncPhase.FAILED, error = manual.outputData.toSyncResult().error)
        lastError != null -> SyncStatus(SyncPhase.FAILED, error = lastError)
        manual?.state == WorkInfo.State.SUCCEEDED -> SyncStatus(SyncPhase.SUCCEEDED, result = manual.outputData.toSyncResult())
        else -> SyncStatus(SyncPhase.IDLE)
    }
}

@Composable
fun rememberSyncStatus(): SyncStatus {
    val context = LocalContext.current
    val manual by remember { WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(SyncWorker.MANUAL_WORK_NAME) }
        .collectAsState(initial = emptyList())
    val scheduled by remember { WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(SyncWorker.PERIODIC_WORK_NAME) }
        .collectAsState(initial = emptyList())
    val lastError by remember { Prefs.lastSyncError(context) }.collectAsState(initial = null)
    val networkMode by remember { Prefs.networkMode(context) }.collectAsState(initial = Prefs.NETWORK_WIFI_ONLY)
    val waiting = manual.firstOrNull()?.state == WorkInfo.State.ENQUEUED
    val blocked = if (waiting) SyncConditions.blockingReason(context, networkMode) else null
    return syncStatusOf(manual.firstOrNull(), scheduled.firstOrNull(), lastError, blocked)
}

/** The server probe the Sync tab used to run on open: a 401 means the
 * pairing was rejected, anything else unreachable; an answer records the
 * App API level the gate in front of Home and Library reads. Run when the
 * app starts and whenever the Settings tab opens. */
class ServerProbe {
    var state by mutableStateOf(ConnState.OK)
        internal set
    internal var key by mutableIntStateOf(0)
    fun retry() { key++ }
}

@Composable
fun rememberServerProbe(pairing: Prefs.Pairing): ServerProbe {
    val context = LocalContext.current
    val probe = remember { ServerProbe() }
    LaunchedEffect(pairing, probe.key) {
        Log.d(PROBE_TAG, "status probe start (retry ${probe.key})")
        val info = try {
            withContext(Dispatchers.IO) { ApiClient(context, pairing.serverUrl, pairing.token).getDeviceInfo() }
        } catch (e: UnauthorizedException) {
            Log.w(PROBE_TAG, "status probe: token rejected", e)
            probe.state = ConnState.UNAUTHORIZED
            return@LaunchedEffect
        } catch (e: IOException) {
            Log.w(PROBE_TAG, "status probe: unreachable", e)
            probe.state = ConnState.UNREACHABLE
            return@LaunchedEffect
        } catch (e: JSONException) {
            Log.w(PROBE_TAG, "status probe: not device info", e)
            probe.state = ConnState.UNREACHABLE
            return@LaunchedEffect
        } catch (e: IllegalArgumentException) {
            // A stored address OkHttp cannot build a request from: expected
            // input (it is the user's), shown as unreachable so the repair
            // dialog is one tap away, never a crash.
            Log.w(PROBE_TAG, "status probe: the stored address is not a URL", e)
            probe.state = ConnState.UNREACHABLE
            return@LaunchedEffect
        }
        Log.d(PROBE_TAG, "status probe ok: ${info.name} app_api=${info.appApi}")
        // Not caught: a CancellationException, which is this effect being
        // restarted (a retry, a new pairing). Catching it as "unreachable"
        // let a cancelled probe overwrite the state a newer one had set.
        // The write goes to the composition's thread explicitly: under the
        // Compose test harness the effect dispatcher is unconfined, and a
        // state write left on the IO worker was measured to go unobserved.
        withContext(Dispatchers.Main.immediate) { probe.state = ConnState.OK }
        withContext(NonCancellable) { Prefs.setAppApi(context, info.appApi) }
    }
    return probe
}

/** The one line every tab carries above the bottom bar: a sync running (and
 * how far), held back (and why), just finished, or failed; or the server
 * refusing or out of reach. Nothing at all when there is nothing to say.
 * A tap leads to the detail in Settings. */
@Composable
fun SyncIndicator(status: SyncStatus, conn: ConnState, onOpen: () -> Unit) {
    var justFinished by remember { mutableStateOf(false) }
    var wasRunning by remember { mutableStateOf(false) }
    LaunchedEffect(status.phase) {
        if (status.phase == SyncPhase.RUNNING) {
            wasRunning = true
        } else if (wasRunning) {
            wasRunning = false
            if (status.phase != SyncPhase.FAILED) {
                justFinished = true
                delay(FINISHED_SHOWN_MS)
                justFinished = false
            }
        }
    }
    val text: String? = when {
        conn == ConnState.UNAUTHORIZED -> stringResource(R.string.conn_indicator_unauthorized)
        conn == ConnState.UNREACHABLE -> stringResource(R.string.conn_indicator_unreachable)
        status.phase == SyncPhase.RUNNING && status.scanning -> stringResource(R.string.recovery_scanning)
        status.phase == SyncPhase.RUNNING && status.total > 0 ->
            stringResource(R.string.sync_in_progress) + " " + stringResource(R.string.sync_progress_count, status.done, status.total)
        status.phase == SyncPhase.RUNNING -> stringResource(R.string.sync_in_progress)
        status.phase == SyncPhase.WAITING ->
            status.waitingReason?.let { stringResource(it) } ?: stringResource(R.string.sync_indicator_waiting)
        status.phase == SyncPhase.FAILED -> stringResource(R.string.sync_indicator_failed)
        justFinished -> status.result?.let { finishedSummary(it) } ?: stringResource(R.string.sync_indicator_finished)
        else -> null
    }
    if (text == null) return
    val problem = conn != ConnState.OK || status.phase == SyncPhase.FAILED
    val mark = indicatorMark(status.phase, problem)
    Surface(
        tonalElevation = 2.dp,
        color = if (problem) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).testTag("sync_indicator"),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                // The text says the state; the mark has nothing to add for a screen reader.
                AppLogo(INDICATOR_MARK, Modifier.testTag(syncMarkTag(mark)), mark, contentDescription = null)
                Text(
                    text, style = MaterialTheme.typography.labelMedium,
                    color = if (problem) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
                )
            }
            if (status.phase == SyncPhase.RUNNING && conn == ConnState.OK) {
                if (status.total > 0 && !status.scanning) {
                    LinearProgressIndicator(progress = { status.done.toFloat() / status.total }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** The indicator's mark: a problem shows the error badge, a run the turning
 * star, a held-back sync the idle mark, and anything else shown at all is
 * the "just finished" line. */
private fun indicatorMark(phase: SyncPhase, problem: Boolean): MarkState = when {
    problem -> MarkState.ERROR
    phase == SyncPhase.RUNNING -> MarkState.SYNCING
    phase == SyncPhase.WAITING -> MarkState.IDLE
    else -> MarkState.SUCCESS
}

@Composable
private fun finishedSummary(r: WorkSyncResult): String {
    val res = LocalContext.current.resources
    return stringResource(R.string.sync_indicator_finished) + ": " +
        res.getQuantityString(R.plurals.sync_downloaded, r.downloaded, r.downloaded) + ", " +
        res.getQuantityString(R.plurals.sync_deleted, r.deleted, r.deleted) +
        if (r.failed > 0) ", " + res.getQuantityString(R.plurals.sync_failed, r.failed, r.failed) else ""
}

/** What the Sync screen held: the state and progress, the last sync, the
 * last error (copyable, dismissable), the tracks deleted by hand awaiting a
 * decision, and Sync now -- the manual start the tap on the logo used to be.
 * In Settings, [live] is false while the server answers: the indicator at
 * the bottom of every tab then shows a run's state and progress, so the card
 * keeps to the last sync, its plan, what failed and what waits on a decision.
 * When the server doesn't answer, the indicator shows that instead, and
 * [live] is true so the run is still shown somewhere. */
@Composable
fun SyncStatusCard(pairing: Prefs.Pairing, status: SyncStatus, live: Boolean = true) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lastSyncAt by remember { Prefs.lastSyncAt(context) }.collectAsState(initial = null)
    val lastSyncMillis by remember { Prefs.lastSyncAtMillis(context) }.collectAsState(initial = null)
    val failed by remember { Prefs.lastSyncFailed(context) }.collectAsState(initial = 0)
    val failures by remember { Prefs.lastSyncFailures(context) }.collectAsState(initial = emptyList())
    val locale = remember(context) { AppLanguage.locale(context) }
    val lastSync = lastSyncMillis?.let { relativeSyncTime(it, System.currentTimeMillis(), locale) } ?: lastSyncAt
    val lastLine = lastSyncLine(lastSync, failed)
    val pendingMissingJson by remember { Prefs.pendingMissingTracks(context) }.collectAsState(initial = null)
    val pendingMissing = remember(pendingMissingJson) { parsePendingMissing(pendingMissingJson) }
    var resolvingMissing by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    val busy = status.phase == SyncPhase.RUNNING || status.phase == SyncPhase.WAITING

    fun resolve(redownload: Boolean) {
        resolvingMissing = true
        scope.launch {
            try {
                val ids = pendingMissing.map { it.trackId }
                withContext(Dispatchers.IO) {
                    ApiClient(context, pairing.serverUrl, pairing.token).resolveMissingTracks(
                        redownload = if (redownload) ids else emptyList(),
                        exclude = if (redownload) emptyList() else ids,
                    )
                }
                Prefs.setPendingMissingTracks(context, null)
                if (redownload) SyncWorker.triggerManualSync(context)
            } catch (e: Exception) {
                actionError = e.message ?: context.getString(R.string.save_failed_generic)
            }
            resolvingMissing = false
        }
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth().testTag("sync_status_card")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusCardHeadline(status, lastLine, live)
            status.plan?.takeIf { status.phase == SyncPhase.RUNNING }?.let { plan ->
                Text(
                    syncPlanText(context, plan), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline, modifier = Modifier.testTag("sync_plan"),
                )
            }
            if (live && status.phase == SyncPhase.RUNNING) {
                if (status.scanning) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    if (status.done > 0) {
                        Text(
                            pluralStringResource(R.plurals.recovery_files_scanned, status.done, status.done),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline,
                        )
                    }
                } else if (status.total > 0) {
                    LinearProgressIndicator(progress = { status.done.toFloat() / status.total }, modifier = Modifier.fillMaxWidth())
                    Text(
                        stringResource(R.string.sync_progress_count, status.done, status.total),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline,
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(
                    lastLine,
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline,
                )
            }
            // A failed run leaves the same error on two surfaces: the stored
            // last error and WorkManager's FAILED record. One line, and the
            // dismiss clears both.
            val failureText = if (failures.isEmpty()) status.error?.let { stringResource(R.string.sync_failure, it) }
            else failureList(failures, failed)
            failureText?.let { text ->
                CopyableError(
                    text,
                    onDismiss = {
                        scope.launch {
                            Prefs.clearSyncError(context)
                            WorkManager.getInstance(context).pruneWork()
                        }
                    },
                )
            }
            actionError?.let { CopyableError(it, onDismiss = { actionError = null }) }
            if (pendingMissing.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    pluralStringResource(R.plurals.missing_tracks_notice, pendingMissing.size, pendingMissing.size),
                    style = MaterialTheme.typography.labelMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = !resolvingMissing, onClick = { resolve(redownload = true) }) {
                        Text(stringResource(R.string.missing_tracks_resync_button))
                    }
                    TextButton(enabled = !resolvingMissing, onClick = { resolve(redownload = false) }) {
                        Text(stringResource(R.string.missing_tracks_exclude_button))
                    }
                }
            }
            Button(
                onClick = { scope.launch { SyncWorker.triggerManualSync(context) } },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.sync_now_button)) }
        }
    }
}

/** Whether Settings' card shows a run's state and progress: only when the
 * indicator, which then shows the connection instead, doesn't. */
internal fun settingsCardIsLive(conn: ConnState): Boolean = conn != ConnState.OK

/** The card's first line, with the mark in the state the sync is in;
 * without [live], a run or a wait shows the last sync, as when idle. */
@Composable
private fun StatusCardHeadline(status: SyncStatus, lastLine: String, live: Boolean) {
    val phase = if (live) status.phase else status.phase.takeUnless { it == SyncPhase.RUNNING || it == SyncPhase.WAITING } ?: SyncPhase.IDLE
    val mark = when (phase) {
        SyncPhase.RUNNING -> MarkState.SYNCING
        SyncPhase.FAILED -> MarkState.ERROR
        SyncPhase.SUCCEEDED -> MarkState.SUCCESS
        SyncPhase.WAITING, SyncPhase.IDLE -> MarkState.IDLE
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        AppLogo(CARD_MARK, Modifier.testTag(syncMarkTag(mark)), mark, contentDescription = null)
        Text(
            when {
                phase == SyncPhase.RUNNING && status.scanning -> stringResource(R.string.recovery_scanning)
                phase == SyncPhase.RUNNING -> stringResource(R.string.sync_in_progress)
                phase == SyncPhase.WAITING ->
                    status.waitingReason?.let { stringResource(it) } ?: stringResource(R.string.sync_indicator_waiting)
                else -> lastLine
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** "Last sync: 2 hours ago · 3 failed". */
@Composable
private fun lastSyncLine(lastSync: String?, failed: Int): String {
    val line = stringResource(R.string.last_sync, lastSync ?: stringResource(R.string.never))
    return if (failed > 0) line + " · " + pluralStringResource(R.plurals.sync_failed, failed, failed) else line
}

/** Each failure, its path over its reason, and how many were left out. */
@Composable
private fun failureList(failures: List<SyncFailure>, failed: Int): String {
    val lines = failures.joinToString("\n\n") { "${it.path}\n${it.reason}" }
    val more = failed - failures.size
    return if (more > 0) lines + "\n\n" + pluralStringResource(R.plurals.sync_failures_more, more, more) else lines
}

private const val FINISHED_SHOWN_MS = 5_000L
private const val MILLIS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60L
private const val HOURS_PER_DAY = 24L

/** The sync marks: one line of label text in the indicator, so under 24 dp
 * and the kit's small mark; larger beside the card's first line. */
private val INDICATOR_MARK = 20.dp
private val CARD_MARK = 40.dp

/** Tests find a sync mark, and the state it shows, by this tag. */
internal fun syncMarkTag(state: MarkState) = "sync_mark_" + state.name.lowercase()

// #45: cap the Status error block's height; a longer message scrolls in place.
internal val ERROR_MAX_HEIGHT = 160.dp
internal const val EASTER_EGG_TAP_THRESHOLD = 5
internal const val TTT_BOARD_SIZE = 9

internal fun formatGB(bytes: Long?): String = if (bytes == null) "?" else "%.1f".format(bytes / BYTES_PER_GB)

/** Display-only: drops the scheme and trailing slash so the settings tile
 * shows just the domain. The stored/edited value (used for requests) always
 * keeps the full URL — only this rendering is trimmed. */
internal fun displayServerUrl(url: String): String =
    url.removePrefix("https://").removePrefix("http://").removeSuffix("/")

/* Every settings row shares one geometry and type hierarchy: 22dp primary
 * icon, title in bodyLarge, supporting line (current value or description)
 * in labelSmall/outline, trailing widget (chevron or switch). Dividers
 * between rows inset by 54dp to clear the icon column. */

private const val PROBE_TAG = "StatusProbe"
