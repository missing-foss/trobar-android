// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
// Where a sync stands, read from the manual and the scheduled work and the
// stored last error; and the line every tab shows for it, including the
// "just finished" that follows a run and then goes; and the brand mark beside
// that line and on the status card, in the state the sync is in. The
// indicator is one clickable node, so its mark is found in the unmerged tree;
// the absence checks look there too, or they could not fail.

package org.missing_foss.trobar

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.workDataOf
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncStatusTest {
    @get:Rule
    val compose = createComposeRule()

    private fun work(state: WorkInfo.State, progress: Pair<Int, Int>? = null, error: String? = null) = WorkInfo(
        UUID.randomUUID(), state, emptySet(),
        workDataOf(SyncWorker.KEY_DOWNLOADED to 3, SyncWorker.KEY_DELETED to 1, SyncWorker.KEY_FAILED to 0, SyncWorker.KEY_ERROR to error),
        progress?.let { (d, t) -> workDataOf(SyncWorker.KEY_DONE to d, SyncWorker.KEY_TOTAL to t) } ?: workDataOf(),
    )

    @Test
    fun theScheduledWorkBetweenRunsIsNotASync() {
        // ENQUEUED is where a periodic work sits between runs: not waiting, idle.
        assertEquals(SyncPhase.IDLE, syncStatusOf(null, work(WorkInfo.State.ENQUEUED), null, null).phase)
    }

    @Test
    fun aRunningScheduledSyncIsShownWithItsProgress() {
        val s = syncStatusOf(null, work(WorkInfo.State.RUNNING, progress = 4 to 10), null, null)
        assertEquals(SyncPhase.RUNNING, s.phase)
        assertEquals(4, s.done)
        assertEquals(10, s.total)
    }

    @Test
    fun aManualSyncHeldByItsConstraintsIsWaitingWithTheReason() {
        val s = syncStatusOf(work(WorkInfo.State.ENQUEUED), work(WorkInfo.State.ENQUEUED), null, R.string.sync_blocked_needs_unmetered)
        assertEquals(SyncPhase.WAITING, s.phase)
        assertEquals(R.string.sync_blocked_needs_unmetered, s.waitingReason)
    }

    @Test
    fun aFailureFromEitherSourceIsAFailure() {
        assertEquals("boom", syncStatusOf(work(WorkInfo.State.FAILED, error = "boom"), null, null, null).error)
        // The scheduled work leaves no FAILED record; its error is the stored one.
        val stored = syncStatusOf(null, work(WorkInfo.State.ENQUEUED), "stored", null)
        assertEquals(SyncPhase.FAILED, stored.phase)
        assertEquals("stored", stored.error)
    }

    @Test
    fun aSucceededManualSyncCarriesItsCounts() {
        val s = syncStatusOf(work(WorkInfo.State.SUCCEEDED), null, null, null)
        assertEquals(SyncPhase.SUCCEEDED, s.phase)
        assertEquals(3, s.result?.downloaded)
        assertNull(s.error)
    }

    @Test
    fun theIndicatorSaysNothingWhenThereIsNothingToSay() {
        var status by mutableStateOf(SyncStatus(SyncPhase.IDLE))
        compose.setContent { MaterialTheme { SyncIndicator(status, ConnState.OK) {} } }
        compose.onAllNodesWithTag("sync_indicator").assertCountEquals(0)
        // An old SUCCEEDED record is not news either: nothing ran while this was on screen.
        status = SyncStatus(SyncPhase.SUCCEEDED, result = WorkSyncResult(3, 1, 0, null))
        compose.onAllNodesWithTag("sync_indicator").assertCountEquals(0)
    }

    @Test
    fun theIndicatorShowsProgressThenTheFinishThenGoes() {
        var status by mutableStateOf(SyncStatus(SyncPhase.RUNNING, done = 4, total = 10))
        var opened = 0
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme { SyncIndicator(status, ConnState.OK) { opened++ } } }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Sync in progress… 4 / 10").assertExists()
        compose.onNodeWithTag(syncMarkTag(MarkState.SYNCING), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("sync_indicator").performClick()
        assertEquals(1, opened)

        status = SyncStatus(SyncPhase.SUCCEEDED, result = WorkSyncResult(3, 1, 0, null))
        compose.mainClock.advanceTimeBy(FRAME_MS)
        compose.onNodeWithText("Sync finished", substring = true).assertExists()
        compose.onNodeWithTag(syncMarkTag(MarkState.SUCCESS), useUnmergedTree = true).assertExists()
        compose.mainClock.advanceTimeBy(FINISH_GONE_MS)
        compose.onAllNodesWithTag("sync_indicator").assertCountEquals(0)
    }

    @Test
    fun theIndicatorSaysWhyASyncIsWaitingAndWhenTheServerRefuses() {
        var conn by mutableStateOf(ConnState.OK)
        compose.setContent {
            MaterialTheme { SyncIndicator(SyncStatus(SyncPhase.WAITING, waitingReason = R.string.sync_blocked_offline), conn) {} }
        }
        compose.onNodeWithText(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.sync_blocked_offline)).assertExists()
        compose.onNodeWithTag(syncMarkTag(MarkState.IDLE), useUnmergedTree = true).assertExists()
        conn = ConnState.UNAUTHORIZED
        compose.onNodeWithText("The server no longer accepts this phone", substring = true).assertExists()
        compose.onNodeWithTag(syncMarkTag(MarkState.ERROR), useUnmergedTree = true).assertExists()
    }

    @Test
    fun aFailedSyncShowsTheErrorMarkInTheIndicator() {
        compose.setContent { MaterialTheme { SyncIndicator(SyncStatus(SyncPhase.FAILED, error = "boom"), ConnState.OK) {} } }
        compose.onNodeWithText("The last sync failed", substring = true).assertExists()
        compose.onNodeWithTag(syncMarkTag(MarkState.ERROR), useUnmergedTree = true).assertExists()
        compose.onAllNodesWithTag(syncMarkTag(MarkState.SUCCESS), useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun theStatusCardShowsTheMarkForEachPhase() {
        val pairing = Prefs.Pairing(serverUrl = "http://127.0.0.1:9", token = "test-token")
        var status by mutableStateOf(SyncStatus(SyncPhase.IDLE))
        // A running sync turns the star for ever: drive the clock by hand.
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme { SyncStatusCard(pairing, status) } }
        val expected = listOf(
            SyncStatus(SyncPhase.IDLE) to MarkState.IDLE,
            SyncStatus(SyncPhase.WAITING) to MarkState.IDLE,
            SyncStatus(SyncPhase.RUNNING, done = 1, total = 2) to MarkState.SYNCING,
            SyncStatus(SyncPhase.SUCCEEDED, result = WorkSyncResult(3, 1, 0, null)) to MarkState.SUCCESS,
            SyncStatus(SyncPhase.FAILED, error = "boom") to MarkState.ERROR,
        )
        for ((s, mark) in expected) {
            status = s
            compose.mainClock.advanceTimeBy(FRAME_MS)
            compose.onNodeWithTag(syncMarkTag(mark), useUnmergedTree = true).assertExists()
            MarkState.entries.filter { it != mark }.forEach { compose.onAllNodesWithTag(syncMarkTag(it), useUnmergedTree = true).assertCountEquals(0) }
        }
    }

    private companion object {
        const val FRAME_MS = 32L
        const val FINISH_GONE_MS = 6_000L
    }
}
