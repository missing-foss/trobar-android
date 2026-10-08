// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import android.util.Log
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog

/** The one budget for a wait that covers a MockWebServer round trip, a
 * DataStore write, or both. Five seconds was enough on an idle host and
 * not on a loaded one: three tests timed out intermittently on a commit
 * with none of the work they were blamed on, and the forge gate never
 * runs this suite, so the flake was visible only to whoever ran it. A
 * generous budget costs nothing on a pass (the wait returns the moment
 * the condition holds) and makes a red run mean the code, not the host.
 * A real defect now takes this long to fail, once, which is the price. */
const val UI_WAIT_MS = 30_000L

/** Waits until a node with exactly [text] exists, within [UI_WAIT_MS].
 * On a timeout, every thread's stack goes to logcat first: a wait that
 * runs out is a state that never arrived, and the threads that were
 * supposed to deliver it are frozen at that moment in exactly the place
 * that explains why. */
fun ComposeTestRule.waitForText(text: String, substring: Boolean = false) {
    try {
        waitUntil(timeoutMillis = UI_WAIT_MS) {
            // A state written on a background thread (a ViewModel's flow
            // emitting on IO, an effect resumed on a worker under the
            // harness's unconfined dispatcher) reaches the composition
            // only once the global snapshot's apply notifications are
            // sent; the harness does not do that on every poll, and one
            // such write was measured to stay invisible for a whole
            // budget. Send them ourselves before each look.
            runOnUiThread { Snapshot.sendApplyNotifications() }
            onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    } catch (e: ComposeTimeoutException) {
        dumpThreads("waiting for \"$text\"")
        // What the screen actually shows at this moment: a state that was
        // never applied looks different from a node that is there under
        // another text.
        runCatching { onRoot().printToLog(DUMP_TAG) }
        throw e
    }
}

private fun dumpThreads(why: String) {
    Log.e(DUMP_TAG, "timed out $why; ${Thread.getAllStackTraces().size} threads follow")
    for ((thread, stack) in Thread.getAllStackTraces()) {
        val sb = StringBuilder("thread \"${thread.name}\" ${thread.state}")
        stack.take(STACK_DEPTH).forEach { sb.append("\n    at ").append(it) }
        Log.e(DUMP_TAG, sb.toString())
    }
}

private const val DUMP_TAG = "WaitDump"
private const val STACK_DEPTH = 14
