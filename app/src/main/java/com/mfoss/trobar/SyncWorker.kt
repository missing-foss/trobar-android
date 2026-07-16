// SPDX-License-Identifier: GPL-3.0-or-later
package com.mfoss.trobar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Runs on WorkManager rather than a plain Activity-scoped coroutine —
 * deliberately, even for a manual "tap to sync" trigger. A large FLAC
 * (the kind this app handles by design) can take long enough to download
 * that a screen-off or backgrounded app gets its socket torn down by the
 * OS ("Software caused connection abort", seen directly in production:
 * the same 51MB track was re-requested 3 times in 19s, each download
 * getting cut off). WorkManager's background execution guarantees are
 * exactly what avoids that — a bare coroutine tied to Activity/Compose
 * lifecycle does not survive the screen turning off. */
class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val pairing = Prefs.pairing(applicationContext).firstOrNull() ?: return@withContext Result.failure()
        val treeUri = Prefs.treeUri(applicationContext).firstOrNull() ?: return@withContext Result.failure()
        val nomediaEnabled = Prefs.nomediaEnabled(applicationContext).firstOrNull() ?: false
        val missingFileBehavior = Prefs.missingFileBehavior(applicationContext).firstOrNull() ?: Prefs.MISSING_ASK
        val isManual = inputData.getBoolean(KEY_IS_MANUAL, false)

        // Promotes this work to a foreground service with a visible,
        // ongoing notification — the strongest signal Android has for "do
        // not kill this process". Plain WorkManager jobs are *supposed* to
        // survive the app being swiped away, but several OEM skins (and
        // likely a DAP's minimal/customized Android build) kill background
        // work anyway despite that contract — foreground services are far
        // better protected against that class of aggressive process kill.
        setForeground(buildForegroundInfo(applicationContext, 0, 0))

        val api = ApiClient(applicationContext, pairing.serverUrl, pairing.token)
        val result = SyncEngine.run(applicationContext, api, treeUri, nomediaEnabled, missingFileBehavior) { p ->
            // setProgress/setForeground are suspend; this callback isn't
            // (SyncEngine.run calls it from plain code) — already on a
            // background dispatcher here, so blocking briefly is fine.
            runBlocking {
                setProgress(workDataOf(KEY_DONE to p.done, KEY_TOTAL to p.total))
                setForeground(buildForegroundInfo(applicationContext, p.done, p.total))
            }
        }

        // only ever non-empty when missingFileBehavior is "ask";
        // overwritten every run (not merged) so a batch the user already
        // resolved via StatusScreen doesn't linger if this run found none.
        val missingJson = JSONArray().apply {
            result.missingTracks.forEach {
                put(JSONObject().put("track_id", it.trackId).put("relative_path", it.relativePath))
            }
        }
        Prefs.setPendingMissingTracks(
            applicationContext,
            if (result.missingTracks.isEmpty()) null else missingJson.toString(),
        )

        storageStatsForTree(applicationContext, Uri.parse(treeUri))?.let { stats ->
            try { api.reportStorage(stats.freeBytes, stats.totalBytes) } catch (ignored: Exception) { /* best-effort, not fatal */ }
        }

        val currentLocale = AppCompatDelegate.getApplicationLocales().get(0) ?: Locale.getDefault()
        val timestamp = SimpleDateFormat("dd/MM HH:mm", currentLocale).format(Date())
        Prefs.recordSyncResult(applicationContext, timestamp, result.error)
        NotificationManagerCompat.from(applicationContext).cancel(NOTIFICATION_ID)

        val output = workDataOf(
            KEY_DOWNLOADED to result.downloaded, KEY_DELETED to result.deleted,
            KEY_FAILED to result.failed, KEY_ERROR to result.error,
        )
        when {
            result.error == null -> Result.success(output)
            isManual -> Result.failure(output) // surface immediately, no silent background retry
            else -> Result.retry()
        }
    }

    companion object {
        private const val PERIODIC_WORK_NAME = "trobar-periodic"
        private const val PERIODIC_INTERVAL_HOURS = 6L
        const val MANUAL_WORK_NAME = "trobar-manual"
        const val KEY_IS_MANUAL = "is_manual"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_DOWNLOADED = "downloaded"
        const val KEY_DELETED = "deleted"
        const val KEY_FAILED = "failed"
        const val KEY_ERROR = "error"

        private const val NOTIFICATION_CHANNEL_ID = "sync_progress"
        private const val NOTIFICATION_ID = 1001

        private fun ensureNotificationChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        NOTIFICATION_CHANNEL_ID, context.getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW,
                    ),
                )
            }
        }

        private fun buildNotification(context: Context, done: Int, total: Int): Notification {
            ensureNotificationChannel(context)
            val builder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Trobar")
                // Status-bar icons render as pure alpha silhouettes — use the
                // mono Bard cutout, not the full-colour launcher artwork.
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
            if (total > 0) {
                builder.setContentText(context.getString(R.string.notification_sync_progress, done, total))
                    .setProgress(total, done, false)
            } else {
                builder.setContentText(context.getString(R.string.sync_in_progress))
                    .setProgress(0, 0, true)
            }
            return builder.build()
        }

        private fun buildForegroundInfo(context: Context, done: Int, total: Int): ForegroundInfo =
            ForegroundInfo(
                NOTIFICATION_ID, buildNotification(context, done, total),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )

        private fun networkTypeFor(mode: String): NetworkType = when (mode) {
            Prefs.NETWORK_ANY -> NetworkType.CONNECTED
            Prefs.NETWORK_WIFI_AND_MOBILE -> NetworkType.NOT_ROAMING
            else -> NetworkType.UNMETERED
        }

        /** Re-enqueues with UPDATE so changing the network setting in
         * Settings actually takes effect on the existing periodic work,
         * not just on a fresh pairing (KEEP would silently ignore it). */
        suspend fun schedulePeriodic(context: Context) {
            val mode = Prefs.networkMode(context).firstOrNull() ?: Prefs.NETWORK_WIFI_ONLY
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(networkTypeFor(mode))
                .build()
            val request = PeriodicWorkRequestBuilder<SyncWorker>(PERIODIC_INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request,
            )
        }

        /** User explicitly tapped "sync now" — still respects the same
         * Wi-Fi/+Mobile/+Roaming preference as auto-sync (this
         * used to hardcode NetworkType.CONNECTED, so a manual tap could
         * burn cellular data even with auto-sync restricted to Wi-Fi-only —
         * one shared setting rather than a second independent one, decided
         * when picking up the issue), but still via WorkManager so it
         * survives screen-off. Suspend because reading the preference is —
         * call from a coroutine scope. */
        suspend fun triggerManualSync(context: Context) {
            val mode = Prefs.networkMode(context).firstOrNull() ?: Prefs.NETWORK_WIFI_ONLY
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(networkTypeFor(mode)).build())
                .setInputData(workDataOf(KEY_IS_MANUAL to true))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                MANUAL_WORK_NAME, ExistingWorkPolicy.REPLACE, request,
            )
        }
    }
}

data class WorkSyncResult(val downloaded: Int, val deleted: Int, val failed: Int, val error: String?)

fun Data.toSyncResult(): WorkSyncResult = WorkSyncResult(
    downloaded = getInt(SyncWorker.KEY_DOWNLOADED, 0),
    deleted = getInt(SyncWorker.KEY_DELETED, 0),
    failed = getInt(SyncWorker.KEY_FAILED, 0),
    error = getString(SyncWorker.KEY_ERROR),
)
