// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.IOException

/** Sentinel for "DataStore has not emitted yet" -- distinct from null, which
 * is DataStore saying no probe has answered since pairing. */
private const val UNREAD = -1
private const val TAG = "AppApiGate"

/** Shows [content] only on a server whose App API level is at least
 * [minLevel] -- [APP_API_MIN] for the tabs the first level carries, more for
 * a screen whose routes came later. The level comes from the last probe of
 * /api/device/info (the shell makes one at start and whenever Settings
 * opens, and records it); when none has answered yet this makes its own,
 * so a tab opened before that probe has answered is never a crash or a
 * silent empty screen. A server below the level gets a native "update the
 * server" state; an unreachable one gets a retry. */
@Composable
fun AppApiGate(pairing: Prefs.Pairing, minLevel: Int = APP_API_MIN, content: @Composable () -> Unit) {
    val context = LocalContext.current
    // One flow instance for the life of this composable: Prefs.appApi()
    // builds a new Flow on every call, and collectAsState restarts its
    // collection whenever the instance it is given changes -- once per
    // recomposition, here, with a gap each time in which an emission can
    // land on no collector at all.
    val appApi = remember(context) { Prefs.appApi(context) }
    val level by appApi.collectAsState(initial = UNREAD)
    var probeFailed by remember { mutableStateOf(false) }
    // The level this gate's own probe got back, as well as the store's: see
    // where it is written.
    var probedLevel by remember(pairing) { mutableStateOf<Int?>(null) }
    var retryKey by remember { mutableIntStateOf(0) }

    // The decision is taken on the value this composition saw, not on a
    // live read inside the effect. The effect body runs after the store's
    // collector has had a chance to emit, so a body that read `level`
    // itself could see null while composed with UNREAD, probe, and then be
    // restarted -- and its probe cancelled -- by the very recomposition
    // that brought null. Measured: that is how a test with one scripted
    // response saw two probes, the first's write cancelled and the second
    // left waiting on nothing.
    val current = level ?: probedLevel // local val: `by` delegates don't smart-cast
    LaunchedEffect(pairing, current, retryKey) {
        if (current != null) return@LaunchedEffect
        probeFailed = false
        Log.d(TAG, "app api probe start (retry $retryKey)")
        try {
            val info = withContext(Dispatchers.IO) {
                ApiClient(context, pairing.serverUrl, pairing.token).getDeviceInfo()
            }
            Log.d(TAG, "app api probe ok: app_api=${info.appApi}")
            // The answer is recorded whatever happens to this effect next:
            // DataStore runs the write in the caller's context, so a plain
            // call here would be dropped if the effect were restarted or
            // the screen left between the answer and the write.
            withContext(NonCancellable) {
                Prefs.setAppApi(context, info.appApi)
                // The store's flow alone was measured to leave this gate on
                // its spinner: under the test harness the collector's update
                // landed off the composition's thread and the gate never
                // recomposed, with 2 in the store for the whole budget. So
                // the answer is also handed to the composition directly, on
                // its thread, as probeFailed below and rememberServerProbe do.
                withContext(Dispatchers.Main.immediate) { probedLevel = info.appApi }
            }
        } catch (e: IOException) {
            // Unreachable, refused, or a store that would not write; the
            // Settings has the details and the re-enroll path. Only these
            // two: a CancellationException is the effect being restarted or
            // the composition leaving, and catching it here recorded the
            // cancellation as a failed probe.
            Log.w(TAG, "app api probe failed", e)
            withContext(Dispatchers.Main.immediate) { probeFailed = true } // see rememberServerProbe: written on the composition's thread
        } catch (e: JSONException) {
            Log.w(TAG, "app api probe answered with something that is not device info", e)
            withContext(Dispatchers.Main.immediate) { probeFailed = true }
        } catch (e: IllegalArgumentException) {
            // OkHttp's answer to a stored address it cannot build a request
            // from. The address is user-supplied state, so this is an
            // expected input, not a bug to surface as a crash; it reads as
            // unreachable and Settings leads to where it is fixed.
            Log.w(TAG, "app api probe could not build a request from the stored address", e)
            withContext(Dispatchers.Main.immediate) { probeFailed = true }
        }
    }

    when {
        current == null && probeFailed -> GateMessage(stringResource(R.string.app_api_unreachable)) {
            Button(onClick = { retryKey++ }) { Text(stringResource(R.string.conn_retry)) }
        }
        current == null || current == UNREAD -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        current >= minLevel -> content()
        else -> GateMessage(
            stringResource(R.string.app_api_too_old_body, minLevel),
            title = stringResource(R.string.app_api_too_old_title),
        )
    }
}

@Composable
private fun GateMessage(body: String, title: String? = null, action: @Composable () -> Unit = {}) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (title != null) Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        action()
    }
}
