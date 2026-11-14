// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
// What a sync costs in content-provider queries on a picked SAF folder, the
// case the file:-folder sync tests never reach. On a large card every query
// is a binder round trip, and a cost per FILE rather than per DIRECTORY is
// what made a sync look stuck before it started downloading.

package org.missing_foss.trobar

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import android.os.Bundle

@RunWith(AndroidJUnit4::class)
class SyncQueryCountTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: MockWebServer
    private var changesJson = ""
    private val defaultFree = SyncEngine.freeSpaceOf

    @Before
    fun setUp() {
        tree(CountingTreeProvider.TEST_RESET)
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if ((request.path ?: "").startsWith("/api/device/changes")) MockResponse().setBody(changesJson)
                    else MockResponse().setBody("{}")
            }
            start()
        }
        // StatFs can't read a provider URI; the space check is not what is measured here.
        SyncEngine.freeSpaceOf = { _, _ -> Long.MAX_VALUE }
    }

    @After
    fun tearDown() {
        SyncEngine.freeSpaceOf = defaultFree
        server.shutdown()
        tree(CountingTreeProvider.TEST_RESET)
    }

    /** The provider runs in another process: everything goes through call(). */
    private fun tree(method: String, arg: String? = null, extras: Bundle? = null): Bundle? =
        context.contentResolver.call(CountingTreeProvider.treeUri(), method, arg, extras)

    /** `artists` x `albums` directories of `tracks` files each, all of them
     * already on the card and reported downloaded by the server. */
    private fun library(artists: Int, albums: Int, tracks: Int, missing: Int = 0): Int {
        val entries = mutableListOf<String>()
        var id = 1
        for (a in 1..artists) for (b in 1..albums) for (t in 1..tracks) {
            val path = "Artist $a/Album $b/0$t - Track.flac"
            tree(CountingTreeProvider.TEST_ADD, path, Bundle().apply { putInt("size", 10) })
            entries += """{"track_id": ${id++}, "relative_path": "$path", "size": 10}"""
        }
        // Tracks the server believes are here but are not: the check must see them.
        for (m in 1..missing) entries += """{"track_id": ${id++}, "relative_path": "Artist 1/Album 1/9$m - Gone.flac", "size": 10}"""
        changesJson = """{"to_download": [], "to_delete": [], "downloaded": [${entries.joinToString(",")}], "playlists": []}"""
        return entries.size
    }

    private var lastMissing = -1

    private var lastResult: SyncResult? = null

    private fun syncQueries(recovery: Boolean = false, mustSucceed: Boolean = true): Int = runBlocking {
        val api = ApiClient(context, server.url("/").toString().trimEnd('/'), "t0k")
        tree(CountingTreeProvider.TEST_ZERO)
        val result = SyncEngine.runIn(context, api, CountingTreeProvider.treeUri(), recoveryPending = recovery)
        if (mustSucceed) assertNull("the sync itself must succeed: ${result.error}", result.error)
        lastMissing = result.missingTracks.size
        lastResult = result
        tree(CountingTreeProvider.TEST_COUNT)!!.getInt("queries")
    }

    /** The same album folders as [library], with `tracks` files each that
     * the server wants downloaded: on the card already but cut short (5 of
     * their 10 bytes), so the space check has to read every size. */
    private fun partialDownloads(tracks: Int): Int {
        val entries = mutableListOf<String>()
        var id = 1
        for (a in 1..2) for (b in 1..2) for (t in 1..tracks) {
            val path = "Artist $a/Album $b/0$t - Track.flac"
            tree(CountingTreeProvider.TEST_ADD, path, Bundle().apply { putInt("size", 5) })
            entries += """{"track_id": ${id++}, "relative_path": "$path", "size": 10}"""
        }
        changesJson = """{"to_download": [${entries.joinToString(",")}], "to_delete": [], "downloaded": [], "playlists": []}"""
        return entries.size
    }

    @Test
    fun theSpaceCheckReadsSizesPerDirectoryNotPerFile() {
        // No room at all: the check refuses every download before one starts,
        // so what it costs is all that is counted.
        SyncEngine.freeSpaceOf = { _, _ -> 0L }
        val files = partialDownloads(tracks = 3)
        val few = syncQueries(mustSucceed = false)
        // The refusal is the space check's own, not some earlier failure that
        // would leave a flat count too: needed rounds up to 0.1 GB, free is 0.
        val gb = { v: Double -> String.format(java.util.Locale.getDefault(), "%.1f", v) }
        org.junit.Assert.assertEquals("the space check refused the downloads",
            context.getString(R.string.sync_not_enough_space, gb(0.1), gb(0.0), gb(0.0)), lastResult?.error)
        org.junit.Assert.assertEquals("nothing was downloaded", 0, lastResult?.downloaded)
        tree(CountingTreeProvider.TEST_RESET)
        val moreFiles = partialDownloads(tracks = 15)
        val many = syncQueries(mustSucceed = false)
        android.util.Log.i("SyncQueryCountTest", "space check: $few for $files files, $many for $moreFiles files")
        assertTrue("queries grew with the file count: $few for $files files, $many for $moreFiles", many == few)
    }

    @Test
    fun theRecoveryWalkListsEachDirectoryOnce() {
        // The first sync after an enrollment walks the whole folder to tell
        // the server what is already there.
        library(artists = 2, albums = 2, tracks = 3)
        changesJson = """{"to_download": [], "to_delete": [], "downloaded": [], "playlists": []}"""
        val few = syncQueries(recovery = true)
        tree(CountingTreeProvider.TEST_RESET)
        library(artists = 2, albums = 2, tracks = 15)
        changesJson = """{"to_download": [], "to_delete": [], "downloaded": [], "playlists": []}"""
        val many = syncQueries(recovery = true)
        android.util.Log.i("SyncQueryCountTest", "recovery: $few for 12 files, $many for 60 files")
        assertTrue("queries grew with the file count: $few for 12 files, $many for 60", many == few)
    }

    @Test
    fun aSyncWithNothingToDoCostsQueriesPerDirectoryNotPerFile() {
        // 2 artists x 2 albums: 1 root + 2 artist + 4 album directories, 12 files.
        val files = library(artists = 2, albums = 2, tracks = 3, missing = 1)
        val few = syncQueries()
        // The check under measurement ran: the one track not on the card was seen.
        org.junit.Assert.assertEquals(1, lastMissing)
        // The same tree with five times the files in the same directories.
        tree(CountingTreeProvider.TEST_RESET)
        val moreFiles = library(artists = 2, albums = 2, tracks = 15, missing = 1)
        val many = syncQueries()
        android.util.Log.i("SyncQueryCountTest", "queries: $few for $files files, $many for $moreFiles files")
        // Per directory, the count cannot grow with the files in them.
        assertTrue("queries grew with the file count: $few for $files files, $many for $moreFiles", many == few)
    }
}
