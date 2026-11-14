// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
// What a sync says about itself: what it is about to do, before the first
// download and in the notification, and what went wrong, track by track, kept
// across restarts and capped; the last-sync line says how long ago and how
// many failed.

package org.missing_foss.trobar

import android.app.Notification
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncLegibilityTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var tempDir: File
    private lateinit var server: MockWebServer
    private var changesJson = ""
    private val defaultFree = SyncEngine.freeSpaceOf
    private val defaultOpen = SyncEngine.openOutput

    @Before
    fun setUp() {
        tempDir = File(context.cacheDir, "sync_legible_${System.nanoTime()}").apply { mkdirs() }
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path ?: ""
                    return when {
                        path.startsWith("/api/device/changes") -> MockResponse().setBody(changesJson)
                        path.startsWith("/api/device/file/") -> MockResponse().setBody(Buffer().write(ByteArray(SIZE) { 1 }))
                        else -> MockResponse().setBody("{}")
                    }
                }
            }
            start()
        }
        runBlocking { Prefs.recordSyncResult(context, 0L, null) }
    }

    @After
    fun tearDown() {
        SyncEngine.freeSpaceOf = defaultFree
        SyncEngine.openOutput = defaultOpen
        server.shutdown()
        tempDir.deleteRecursively()
    }

    private fun tracks(count: Int) = (1..count).joinToString(",") {
        """{"track_id": $it, "relative_path": "A/B/$it.flac", "size": $SIZE, "transcode": false}"""
    }

    private fun changes(download: String) {
        changesJson = """{"to_download": [$download], "to_delete": [], "downloaded": [], "playlists": []}"""
    }

    private fun run(onProgress: (SyncProgress) -> Unit = {}): SyncResult = runBlocking {
        val api = ApiClient(context, server.url("/").toString().trimEnd('/'), "t0k")
        SyncEngine.runIn(context, api, Uri.fromFile(tempDir), onProgress = onProgress)
    }

    private fun failDownloads() {
        SyncEngine.openOutput = { _, _, _ -> throw IOException("Connection reset") }
    }

    @Test
    fun theFirstProgressCarriesThePlan() {
        SyncEngine.freeSpaceOf = { _, _ -> FREE }
        changes(tracks(3))
        val seen = CopyOnWriteArrayList<SyncProgress>()
        run { seen.add(it) }
        assertEquals(SyncPlan(3, 3L * SIZE, FREE), seen.first().plan)
        assertEquals(0, seen.first().done)
        assertEquals(listOf(seen.first()), seen.filter { it.plan != null })
    }

    @Test
    fun nothingToDownloadIsNoPlan() {
        changes("")
        val seen = CopyOnWriteArrayList<SyncProgress>()
        run { seen.add(it) }
        assertTrue(seen.all { it.plan == null })
    }

    @Test
    fun aRunKeepsEachFailureWithItsPathAndReason() {
        failDownloads()
        changes(tracks(2))
        val result = run()
        assertEquals(2, result.failed)
        assertEquals(setOf("A/B/1.flac", "A/B/2.flac"), result.failures.map { it.path }.toSet())
        assertTrue(result.failures.all { "Connection reset" in it.reason })
    }

    @Test
    fun aRunKeepsFiftyFailuresAndCountsThemAll() {
        failDownloads()
        changes(tracks(MAX_REPORTED_FAILURES + 5))
        val result = run()
        assertEquals(MAX_REPORTED_FAILURES + 5, result.failed)
        assertEquals(MAX_REPORTED_FAILURES, result.failures.size)
    }

    @Test
    fun theFailuresSurviveInThePreferencesAndAGoodRunClearsThem() = runBlocking {
        val failures = listOf(SyncFailure("A/1.flac", "reset, \"quoted\""))
        Prefs.recordSyncResult(context, 1L, "x", failed = 1, failures = failures)
        assertEquals(failures, Prefs.lastSyncFailures(context).first())
        assertEquals(1, Prefs.lastSyncFailed(context).first())
        Prefs.recordSyncResult(context, 2L, null)
        assertEquals(emptyList<SyncFailure>(), Prefs.lastSyncFailures(context).first())
        assertEquals(0, Prefs.lastSyncFailed(context).first())
    }

    @Test
    fun theNotificationSaysThePlanThenTheProgress() {
        val plan = SyncPlan(142, 4_200_000_000L, 18_600_000_000L)
        fun text(done: Int) = SyncWorker.buildNotification(context, done, 142, false, plan)
            .extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertEquals("142 tracks, 4.2 GB to download, 18.6 GB free", text(0))
        assertTrue(text(9), "9" in text(9) && "GB" !in text(9))
    }

    @Test
    fun aPlanWithoutFreeSpaceLeavesItOut() {
        assertEquals("1 track, 0.1 GB to download", syncPlanText(context, SyncPlan(1, 100_000_000L, null)))
    }

    @Test
    fun relativeTimesFollowTheLocale() {
        val now = 10L * DAY
        fun at(ago: Long, locale: Locale = Locale.ENGLISH) = relativeSyncTime(now - ago, now, locale)
        assertEquals("now", at(SECOND * 30))
        assertEquals("5 minutes ago", at(MINUTE * 5))
        assertEquals("2 hours ago", at(HOUR * 2))
        assertEquals("3 days ago", at(DAY * 3))
        assertEquals("il y a 2 heures", at(HOUR * 2, Locale.FRENCH))
    }

    private fun setCard(status: SyncStatus = SyncStatus(SyncPhase.IDLE)) {
        val pairing = Prefs.Pairing(serverUrl = server.url("/").toString(), token = "t")
        compose.setContent { MaterialTheme { SyncStatusCard(pairing, status) } }
    }

    private fun waitForText(text: String) = compose.waitUntil(timeoutMillis = WAIT_MS) {
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun theCardGivesTheCountAndEachFailureAndClearTakesThemAway() {
        val failures = listOf(SyncFailure("Aphelion/02 Parallax.flac", "Connection reset"))
        runBlocking {
            Prefs.recordSyncResult(context, System.currentTimeMillis() - 2 * HOUR, "x", failed = 3, failures = failures)
        }
        setCard()
        waitForText("Last sync: 2 hours ago · 3 failed")
        waitForText("Aphelion/02 Parallax.flac\nConnection reset")
        waitForText("and 2 more")
        compose.onNodeWithText("Clear").performClick()
        compose.waitUntil(timeoutMillis = WAIT_MS) {
            compose.onAllNodesWithText("Connection reset", substring = true).fetchSemanticsNodes().isEmpty()
        }
        assertNull(runBlocking { Prefs.lastSyncError(context).first() })
    }

    @Test
    fun theCardShowsThePlanWhileRunning() {
        setCard(SyncStatus(SyncPhase.RUNNING, done = 9, total = 142, plan = SyncPlan(142, 4_200_000_000L, null)))
        compose.onNodeWithTag("sync_plan").assertIsDisplayed()
    }

    private companion object {
        const val SIZE = 10
        const val FREE = 5_000_000_000L
        const val SECOND = 1_000L
        const val MINUTE = 60 * SECOND
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
        const val WAIT_MS = 5_000L
    }
}
