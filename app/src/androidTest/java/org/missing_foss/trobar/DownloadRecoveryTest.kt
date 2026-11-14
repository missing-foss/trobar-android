// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
// A download goes to a part and is renamed over the track once whole. A
// part resumes only within the sync that wrote it: one left by an earlier
// sync is deleted and the file fetched whole, since the file may have
// changed on the server since. A download that can't resume is started over
// rather than wedged: a 416 restarts from zero, and a 200 with nothing in it
// is a failed attempt, not a downloaded track. A swap that fails leaves the
// old file at the track's path.

package org.missing_foss.trobar

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

@RunWith(AndroidJUnit4::class)
class DownloadRecoveryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val content = ByteArray(10) { it.toByte() }
    private lateinit var server: MockWebServer
    private lateinit var tempDir: File
    private val fileAnswers = ConcurrentLinkedQueue<(RecordedRequest) -> MockResponse>()
    private val fileRanges = ConcurrentLinkedQueue<String>()
    private val acks = ConcurrentLinkedQueue<String>()
    private val defaultRename = SyncEngine.renameDocument

    @Before
    fun setUp() {
        tempDir = File(context.cacheDir, "download_recovery_${System.nanoTime()}").apply { mkdirs() }
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path ?: ""
                    return when {
                        path.startsWith("/api/device/changes") -> MockResponse().setBody(
                            """{"to_download": [{"track_id": 1, "relative_path": "A/B/01 - T.flac", "size": 10}],
                               "to_delete": [], "downloaded": [], "playlists": []}""")
                        path.startsWith("/api/device/file/1") -> {
                            fileRanges.add(request.getHeader("Range") ?: "none")
                            fileAnswers.poll()?.invoke(request) ?: MockResponse().setResponseCode(500)
                        }
                        path.startsWith("/api/device/ack") -> {
                            acks.add(request.body.readUtf8())
                            MockResponse().setBody("{}")
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
        SyncEngine.renameDocument = defaultRename
        server.shutdown()
        tempDir.deleteRecursively()
    }

    private fun whole(): (RecordedRequest) -> MockResponse = { MockResponse().setBody(Buffer().write(content)) }

    /** The whole file, cut off halfway through its body. */
    private fun cutOff(): (RecordedRequest) -> MockResponse = {
        MockResponse().setBody(Buffer().write(content)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
    }

    /** What was asked for: the rest of the file from the Range's start, as a 206. */
    private fun rest(): (RecordedRequest) -> MockResponse = { req ->
        val from = req.getHeader("Range")!!.removePrefix("bytes=").removeSuffix("-").toInt()
        MockResponse().setResponseCode(206).setBody(Buffer().write(content.copyOfRange(from, content.size)))
    }

    private fun status(code: Int): (RecordedRequest) -> MockResponse = {
        MockResponse().setResponseCode(code).setBody("""{"error": "range"}""")
    }

    private fun album() = File(tempDir, "A/B").apply { mkdirs() }

    private fun track() = File(tempDir, "A/B/01 - T.flac")

    /** Every name in the album folder. */
    private fun names(): List<String> = album().list()!!.sorted()

    private fun run(): SyncResult = runBlocking {
        val api = ApiClient(context, server.url("/").toString().trimEnd('/'), "t0k")
        SyncEngine.runIn(context, api, Uri.fromFile(tempDir))
    }

    /** The second request resumes from what the first landed, which must
     * be part of the file and not all of it. */
    private fun resumedFrom(): Int {
        val from = fileRanges.toList()[1].removePrefix("bytes=").removeSuffix("-").toInt()
        assertTrue("resumed from $from", from in 1 until content.size)
        return from
    }

    @Test
    fun aPartFromAnEarlierSyncIsNotResumed() {
        // An earlier sync stopped 4 bytes into an older version of the file;
        // resuming would join that head to the new file's tail.
        File(album(), "01 - T.flac.part").writeBytes(ByteArray(4) { 9 })
        fileAnswers.add(whole())
        val result = run()
        assertEquals(1, result.downloaded)
        assertEquals(listOf("none"), fileRanges.toList())
        assertArrayEquals(content, track().readBytes())
        assertEquals(listOf("01 - T.flac"), names())
    }

    @Test
    fun aFileOfAnotherSizeAtTheTracksPathIsReplacedWhole() {
        // Left at the track's own path by an older app, or an older version.
        for (stale in listOf(ByteArray(4) { 9 }, ByteArray(15) { 9 })) {
            fileRanges.clear()
            track().apply { album(); writeBytes(stale) }
            fileAnswers.add(whole())
            assertEquals(1, run().downloaded)
            assertEquals(listOf("none"), fileRanges.toList())
            assertArrayEquals(content, track().readBytes())
            assertEquals(listOf("01 - T.flac"), names())
        }
    }

    @Test
    fun aDropMidFileResumesWithinTheSync() {
        fileAnswers.add(cutOff())
        fileAnswers.add(rest())
        val result = run()
        assertEquals(1, result.downloaded)
        assertEquals("none", fileRanges.first())
        resumedFrom()
        assertArrayEquals(content, track().readBytes())
        assertEquals(listOf("01 - T.flac"), names())
    }

    @Test
    fun a416RestartsFromZero() {
        fileAnswers.add(cutOff())
        fileAnswers.add(status(416))
        fileAnswers.add(whole())
        val result = run()
        assertEquals(1, result.downloaded)
        resumedFrom()
        assertEquals(listOf("none", "none"), fileRanges.toList().let { listOf(it[0], it[2]) })
        assertArrayEquals(content, track().readBytes())
    }

    @Test
    fun aWhole200AnsweringARangeIsWrittenOverThePartNotAppended() {
        // Asked for the rest, the server sends the whole file with a plain
        // 200 (a proxy that drops Range, say). Appending it would give the
        // head twice.
        fileAnswers.add(cutOff())
        fileAnswers.add(whole())
        val result = run()
        assertEquals(1, result.downloaded)
        resumedFrom()
        assertArrayEquals(content, track().readBytes())
    }

    @Test
    fun aTrackThatFailsLeavesNoPartAndKeepsWhatWasThere() {
        val old = ByteArray(4) { 9 }
        track().apply { album(); writeBytes(old) }
        repeat(3) { fileAnswers.add(cutOff()) }
        val result = run()
        assertEquals(0, result.downloaded)
        assertEquals(1, result.failed)
        assertEquals(listOf("01 - T.flac"), names())
        assertArrayEquals(old, track().readBytes())
    }

    /** An old file at the track's path, a whole download for it, and the
     * swap failing as [rename] says: the old bytes stay, and nothing else. */
    private fun assertAFailedSwapKeepsTheOldFile(rename: (DocumentFile, String) -> Boolean) {
        val old = ByteArray(4) { 9 }
        track().apply { album(); writeBytes(old) }
        fileAnswers.add(whole())
        SyncEngine.renameDocument = rename
        val result = run()
        assertEquals(0, result.downloaded)
        assertEquals(1, result.failed)
        assertEquals(listOf("01 - T.flac"), names())
        assertArrayEquals(old, track().readBytes())
    }

    @Test
    fun aPartThatCantBeRenamedLeavesTheOldFileInPlace() = assertAFailedSwapKeepsTheOldFile { f, name ->
        if (f.name!!.contains(".part")) false else f.renameTo(name)
    }

    @Test
    fun aPartLandingUnderAnotherNameLeavesTheOldFileInPlace() = assertAFailedSwapKeepsTheOldFile { f, name ->
        // A provider, finding the name taken, picks a unique one.
        f.renameTo(if (f.name!!.contains(".part")) "01 - T (1).flac" else name)
    }

    @Test
    fun anOldFileThatCantBeMovedAsideStays() = assertAFailedSwapKeepsTheOldFile { f, name ->
        if (name.endsWith(".old")) false else f.renameTo(name)
    }

    @Test
    fun anOldFileSetAsideByAnEarlierSyncIsDeleted() {
        File(album(), "01 - T.flac.old").writeBytes(ByteArray(4) { 9 })
        fileAnswers.add(whole())
        assertEquals(1, run().downloaded)
        assertEquals(listOf("01 - T.flac"), names())
        assertArrayEquals(content, track().readBytes())
    }

    @Test
    fun anEmptySuccessIsAFailedAttemptNotADownload() {
        fileAnswers.add { MockResponse().setBody("") }
        fileAnswers.add(whole())
        val result = run()
        assertEquals(1, result.downloaded)
        assertEquals(2, fileRanges.size)
        assertArrayEquals(content, track().readBytes())
    }

    @Test
    fun onlyEmptySuccessesFailTheTrackUnacked() {
        repeat(3) { fileAnswers.add { MockResponse().setBody("") } }
        val result = run()
        assertEquals(0, result.downloaded)
        assertEquals(1, result.failed)
        assertEquals(0, acks.count { it.contains("\"downloaded\"") })
        assertFalse(File(tempDir, "A/B").list()!!.any { it.contains(".part") })
    }
}
