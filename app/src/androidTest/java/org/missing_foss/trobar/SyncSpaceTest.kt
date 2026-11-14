// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
// A folder that can't hold the downloads is said so up front, counting
// tracks already whole on disk as not needed, a part left by an earlier
// sync as free (it's deleted first), but not what the deletions will free
// (they run after the downloads), a transcoded track at the server's
// estimate, and keeping the volume's reserve free; a folder that fills up
// mid-sync stops the downloads that are left instead of failing each in turn.

package org.missing_foss.trobar

import android.net.Uri
import android.system.ErrnoException
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue

@RunWith(AndroidJUnit4::class)
class SyncSpaceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: MockWebServer
    private lateinit var tempDir: File
    private var changesJson = ""
    private val fileRequests = ConcurrentLinkedQueue<String>()
    private val defaultFree = SyncEngine.freeSpaceOf
    private val defaultReserve = SyncEngine.reserveOf
    private val defaultOpen = SyncEngine.openOutput

    @Before
    fun setUp() {
        tempDir = File(context.cacheDir, "sync_space_${System.nanoTime()}").apply { mkdirs() }
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path ?: ""
                    return when {
                        path.startsWith("/api/device/changes") -> MockResponse().setBody(changesJson)
                        path.startsWith("/api/device/file/") -> {
                            fileRequests.add(path)
                            MockResponse().setBody(Buffer().write(ByteArray(10) { 1 }))
                        }
                        else -> MockResponse().setBody("{}")
                    }
                }
            }
            start()
        }
    }

    @After
    fun tearDown() {
        SyncEngine.freeSpaceOf = defaultFree
        SyncEngine.reserveOf = defaultReserve
        SyncEngine.openOutput = defaultOpen
        server.shutdown()
        tempDir.deleteRecursively()
    }

    private fun tracks(vararg ids: Int, transcode: Boolean = false) = ids.joinToString(",") {
        """{"track_id": $it, "relative_path": "A/B/0$it - T.flac", "size": 10, "transcode": $transcode}"""
    }

    private fun changes(download: String, delete: String = "") {
        changesJson = """{"to_download": [$download], "to_delete": [$delete], "downloaded": [], "playlists": []}"""
    }

    private fun run(): SyncResult = runBlocking {
        val api = ApiClient(context, server.url("/").toString().trimEnd('/'), "t0k")
        SyncEngine.runIn(context, api, Uri.fromFile(tempDir))
    }

    private fun free(bytes: Long?) { SyncEngine.freeSpaceOf = { _, _ -> bytes } }
    private fun reserve(bytes: Long) { SyncEngine.reserveOf = { _, _ -> bytes } }

    private fun transcoded(vararg ids: Int, estimate: Long) = ids.joinToString(",") {
        """{"track_id": $it, "relative_path": "A/B/0$it - T.mp3", "size": 10, "transcode": true, "estimated_size": $estimate}"""
    }

    @Test
    fun downloadsThatCantFitAreNotStartedAndSaySo() {
        free(15)
        changes(tracks(1, 2))
        val result = run()
        assertEquals(0, result.downloaded)
        assertTrue(fileRequests.isEmpty())
        assertEquals(context.getString(R.string.sync_not_enough_space, "0.1", "0.0", "0.0"), result.error)
    }

    @Test
    fun whatTheDeletionsWillFreeDoesNotCountButTheyStillRun() {
        // The deletions run after the downloads, so their space isn't there
        // when the downloads need it: refused, the deletions still run, and
        // the next sync, with that space free, fits.
        File(tempDir, "Old").mkdirs()
        File(tempDir, "Old/Gone.flac").writeBytes(ByteArray(20))
        free(5)
        changes(tracks(1), delete = """{"track_id": 9, "relative_path": "Old/Gone.flac"}""")
        val refused = run()
        assertEquals(0, refused.downloaded)
        assertTrue(fileRequests.isEmpty())
        assertEquals(1, refused.deleted)
        assertFalse(File(tempDir, "Old/Gone.flac").exists())
        assertEquals(context.getString(R.string.sync_not_enough_space, "0.1", "0.0", "0.0"), refused.error)

        free(25)
        changes(tracks(1))
        assertEquals(1, run().downloaded)
    }

    @Test
    fun aTrackAlreadyWholeOnDiskIsNotNeededAgain() {
        File(tempDir, "A/B").mkdirs()
        File(tempDir, "A/B/01 - T.flac").writeBytes(ByteArray(10) { 1 })
        free(5)
        changes(tracks(1))
        assertEquals(1, run().downloaded)
        assertTrue(fileRequests.isEmpty())
    }

    @Test
    fun aFileOfAnotherSizeIsNeededWhole() {
        // It stays until its replacement is whole, so it frees nothing in time.
        File(tempDir, "A/B").mkdirs()
        File(tempDir, "A/B/01 - T.flac").writeBytes(ByteArray(8) { 1 })
        free(5)
        changes(tracks(1))
        assertEquals(0, run().downloaded)
        assertTrue(fileRequests.isEmpty())
    }

    @Test
    fun aPartFromAnEarlierSyncIsDeletedEvenWhenTheSyncIsRefused() {
        // Otherwise the space it holds could refuse every sync after.
        File(tempDir, "A/B").mkdirs()
        File(tempDir, "A/B/01 - T.flac.part").writeBytes(ByteArray(8) { 1 })
        free(5)
        changes(tracks(1))
        assertEquals(0, run().downloaded)
        assertFalse(File(tempDir, "A/B/01 - T.flac.part").exists())
    }

    @Test
    fun aTranscodedTrackWithoutAnEstimateDoesNotCount() {
        // A server too old to send one: as before, never refused for it.
        free(1)
        changes(tracks(1, 2, transcode = true))
        assertEquals(2, run().downloaded)
    }

    @Test
    fun aTranscodingSyncWhoseEstimateExceedsTheFreeSpaceIsRefused() {
        free(2_500_000_000)
        changes(transcoded(1, 2, estimate = 1_500_000_000))
        val result = run()
        assertEquals(0, result.downloaded)
        assertTrue(fileRequests.isEmpty())
        assertEquals(context.getString(R.string.sync_not_enough_space, "3.0", "2.5", "0.0"), result.error)
    }

    @Test
    fun aTranscodingSyncWhoseEstimateFitsGoesAhead() {
        free(2_500_000_000)
        changes(transcoded(1, 2, estimate = 1_000_000_000))
        assertEquals(2, run().downloaded)
    }

    @Test
    fun aSyncThatFitsOnlyByEatingTheReserveIsRefused() {
        // 20 bytes needed and 25 free, but 10 must stay free.
        free(25)
        reserve(10)
        changes(tracks(1, 2))
        val result = run()
        assertEquals(0, result.downloaded)
        assertTrue(fileRequests.isEmpty())
        assertEquals(context.getString(R.string.sync_not_enough_space, "0.1", "0.0", "0.1"), result.error)
        // With the reserve left whole it goes ahead.
        free(30)
        assertEquals(2, run().downloaded)
    }

    @Test
    fun theReserveIsFivePercentOfTheVolumeAndNeverUnderOneGigabyte() {
        assertEquals(1_000_000_000L, spaceReserve(16_000_000_000))
        assertEquals(1_000_000_000L, spaceReserve(20_000_000_000))
        assertEquals(6_400_000_000L, spaceReserve(128_000_000_000))
        assertEquals(25_600_000_000L, spaceReserve(512_000_000_000))
    }

    @Test
    fun noFreeSpaceFigureMeansNoCheck() {
        free(null)
        changes(tracks(1))
        assertEquals(1, run().downloaded)
    }

    @Test
    fun aFolderThatFillsUpStopsTheDownloadsLeft() {
        free(null)
        SyncEngine.openOutput = { _, _, _ ->
            throw IOException("write failed", ErrnoException("write", OsConstants.ENOSPC))
        }
        changes(tracks(1, 2, 3, 4, 5, 6, 7, 8))
        val result = run()
        assertEquals(0, result.downloaded)
        assertEquals(context.getString(R.string.sync_folder_full), result.error)
        assertEquals(0, result.failed) // said once, as full, not as eight failures
        // Each track tried at most once (no retries on a full folder), and the
        // ones after the first that hit it never at all.
        assertTrue(fileRequests.size < 8)
        assertEquals(fileRequests.size, fileRequests.toSet().size)
    }

    @Test
    fun noSpaceIsRecognisedThroughItsCauses() {
        assertTrue(SyncEngine.isNoSpace(IOException("x", ErrnoException("write", OsConstants.ENOSPC))))
        assertTrue(SyncEngine.isNoSpace(IOException("write failed: ENOSPC (No space left on device)")))
        assertFalse(SyncEngine.isNoSpace(IOException("write failed", ErrnoException("write", OsConstants.EACCES))))
    }
}
