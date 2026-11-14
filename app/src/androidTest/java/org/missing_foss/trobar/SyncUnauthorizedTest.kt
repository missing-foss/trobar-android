// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
// A sync the server refuses for the token (401) says so in the
// connection indicator's own words, not "Could not reach the server", and
// isn't retried; unpairing cancels the scheduled sync.

package org.missing_foss.trobar

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SyncUnauthorizedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: MockWebServer
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        tempDir = File(context.cacheDir, "sync_401_${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        server.shutdown()
        tempDir.deleteRecursively()
    }

    private fun run(recoveryPending: Boolean = false): SyncResult = runBlocking {
        val api = ApiClient(context, server.url("/").toString().trimEnd('/'), "t0k")
        SyncEngine.runIn(context, api, Uri.fromFile(tempDir), recoveryPending = recoveryPending)
    }

    @Test
    fun aRefusedTokenOnChangesIsWordedAsSuchAndFlagged() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error": "Invalid or revoked token"}"""))
        val result = run()
        assertTrue(result.unauthorized)
        assertEquals(context.getString(R.string.conn_indicator_unauthorized), result.error)
    }

    @Test
    fun aRefusedTokenDuringRecoveryIsTheSame() {
        File(tempDir, "A/B").mkdirs()
        File(tempDir, "A/B/01 - One.flac").writeText("x") // something to report
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error": "Invalid or revoked token"}"""))
        val result = run(recoveryPending = true)
        assertTrue(result.unauthorized)
        assertEquals(context.getString(R.string.conn_indicator_unauthorized), result.error)
    }

    @Test
    fun aServerErrorIsStillCouldNotReachAndNotFlagged() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error": "boom"}"""))
        val result = run()
        assertFalse(result.unauthorized)
        assertEquals(context.getString(R.string.cannot_reach_server, "boom"), result.error)
    }

    @Test
    fun theWorkerRetriesAnErrorButNotARefusal() {
        val refused = SyncResult(0, 0, 0, "x", unauthorized = true)
        val failed = SyncResult(0, 0, 0, "x")
        assertEquals(SyncWorker.Companion.Outcome.FAILURE, SyncWorker.outcome(refused, isManual = false))
        assertEquals(SyncWorker.Companion.Outcome.RETRY, SyncWorker.outcome(failed, isManual = false))
        assertEquals(SyncWorker.Companion.Outcome.FAILURE, SyncWorker.outcome(failed, isManual = true))
        assertEquals(SyncWorker.Companion.Outcome.SUCCESS, SyncWorker.outcome(SyncResult(1, 0, 0), isManual = false))
    }

    @Test
    fun unpairingCancelsTheScheduledSyncAndForgetsThePairing(): Unit = runBlocking {
        Prefs.setPairing(context, "http://srv", "t0k")
        val work = WorkManager.getInstance(context)
        SyncWorker.schedulePeriodic(context)
        // A manual sync still pending (delayed, so it can't start and finish
        // before the check), under the name the app enqueues it with.
        work.enqueueUniqueWork(SyncWorker.MANUAL_WORK_NAME, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>().setInitialDelay(1, TimeUnit.HOURS).build()).result.get()
        for (name in listOf(SyncWorker.PERIODIC_WORK_NAME, SyncWorker.MANUAL_WORK_NAME)) {
            val before = work.getWorkInfosForUniqueWork(name).get()
            assertTrue(name, before.any { !it.state.isFinished }) // the control
        }

        SyncWorker.unpair(context)

        for (name in listOf(SyncWorker.PERIODIC_WORK_NAME, SyncWorker.MANUAL_WORK_NAME)) {
            val after = work.getWorkInfosForUniqueWork(name).get()
            assertTrue(name, after.all { it.state == WorkInfo.State.CANCELLED })
        }
        assertEquals(Prefs.PairingState.NotPaired, Prefs.pairing(context).first())
    }
}
