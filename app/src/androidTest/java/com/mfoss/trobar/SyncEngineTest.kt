// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
// #82: covers SyncEngine's relativePath.split("/") tree-walk (findLocalFile),
// the Android analogue of desktop's #11 path guard — the same walk backs
// both deleteOne and the "downloaded but missing on disk" check in run().
// DocumentFile.fromFile() wraps a plain java.io.File and needs no SAF tree
// grant, so this runs against a real temp directory rather than a mocked
// DocumentFile — the walk logic (findFile-per-segment, stopping at the first
// missing segment) is exercised exactly as production code exercises it.
// (findLocalFileCached, the cached variant run() actually uses, layers a
// per-directory listing cache on top of this same walk purely for
// performance — not retested separately here since it's not a distinct
// algorithm, just a memoized lookup.)

package com.mfoss.trobar

import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SyncEngineTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var tempDir: File
    private lateinit var root: DocumentFile

    @Before
    fun setUp() {
        tempDir = File(context.cacheDir, "sync_engine_test_${System.nanoTime()}")
        tempDir.mkdirs()
        root = DocumentFile.fromFile(tempDir)
    }

    @Test
    fun findsAFileAtTheRoot() {
        File(tempDir, "top.m3u8").writeText("x")
        assertEquals("top.m3u8", SyncEngine.findLocalFile(root, "top.m3u8")?.name)
    }

    @Test
    fun findsAFileNestedTwoLevelsDeep() {
        val albumDir = File(tempDir, "Artist/Album").apply { mkdirs() }
        File(albumDir, "01 - Song.flac").writeText("audio bytes")

        val found = SyncEngine.findLocalFile(root, "Artist/Album/01 - Song.flac")

        assertEquals("01 - Song.flac", found?.name)
    }

    @Test
    fun returnsNullWhenTheLeafFileIsMissing() {
        File(tempDir, "Artist/Album").mkdirs()
        assertNull(SyncEngine.findLocalFile(root, "Artist/Album/Missing.flac"))
    }

    @Test
    fun returnsNullWhenAnIntermediateDirectoryIsMissing() {
        // "Artist" exists but "Album" never was created — the walk must stop
        // at the first missing segment rather than throwing.
        File(tempDir, "Artist").mkdirs()
        assertNull(SyncEngine.findLocalFile(root, "Artist/Album/Song.flac"))
    }

    @Test
    fun returnsNullWhenTheTopLevelDirectoryIsMissing() {
        assertNull(SyncEngine.findLocalFile(root, "NeverCreated/Song.flac"))
    }

    @Test
    fun distinguishesTracksThatShareAFileNameInDifferentAlbums() {
        val albumA = File(tempDir, "Artist/AlbumA").apply { mkdirs() }
        val albumB = File(tempDir, "Artist/AlbumB").apply { mkdirs() }
        File(albumA, "01 - Intro.flac").writeText("a")
        // AlbumB deliberately has no "01 - Intro.flac" — only the exact path
        // should resolve, not any file with a matching leaf name anywhere.
        File(albumB, "02 - Other.flac").writeText("b")

        assertEquals("01 - Intro.flac", SyncEngine.findLocalFile(root, "Artist/AlbumA/01 - Intro.flac")?.name)
        assertNull(SyncEngine.findLocalFile(root, "Artist/AlbumB/01 - Intro.flac"))
    }
}
