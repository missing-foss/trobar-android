// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.annotation.StringRes
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.first

/** Why a manual sync started now would wait rather than run, under the
 * user's own network setting -- or null when it would run. The answer
 * takes the SAME properties the worker's WorkManager constraints take
 * (SyncWorker.networkTypeFor): Wi-Fi only is UNMETERED, which is the
 * network's metering flag and not its transport -- a phone hotspot is
 * Wi-Fi and metered, and would hold the sync; a few operators' mobile
 * networks are unmetered, and would not. Charging/idle only gates the
 * periodic sync and is not consulted here, matching triggerManualSync. */
object SyncConditions {
    @StringRes
    fun blockingReason(context: Context, mode: String): Int? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        return blockingReason(
            mode = mode,
            online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            unmetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true,
            roaming = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING) == false,
        )
    }

    /** The rule alone, for tests: the constraint each setting maps to. */
    @StringRes
    fun blockingReason(mode: String, online: Boolean, unmetered: Boolean, roaming: Boolean): Int? = when {
        !online -> R.string.sync_blocked_offline
        mode == Prefs.NETWORK_WIFI_ONLY && !unmetered -> R.string.sync_blocked_needs_unmetered
        mode == Prefs.NETWORK_WIFI_AND_MOBILE && roaming -> R.string.sync_blocked_roaming
        else -> null
    }
}

/** How the app starts a manual sync of this phone after a send, and asks
 * what would hold it back: the real ones, which a test replaces through
 * [LocalManualSync] so that a send starts no worker and the held-back case
 * needs no network of its own. */
class ManualSync(
    val start: suspend (Context) -> Unit = { SyncWorker.triggerManualSync(it) },
    val blockedBy: suspend (Context) -> Int? = { SyncConditions.blockingReason(it, Prefs.networkMode(it).first()) },
)

internal val LocalManualSync = staticCompositionLocalOf { ManualSync() }
