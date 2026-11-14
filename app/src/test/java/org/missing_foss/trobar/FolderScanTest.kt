// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
// The folder measurement behind the server's "Max music size", which is a
// limit on the sync FOLDER rather than on Trobar's own share of it.
//
// The walk is tested through walkForeignAudio's injected listing rather
// than against a real DocumentsProvider, for two reasons. It keeps the tree
// shapes that matter (nesting, an unreadable subtree, a provider reporting
// a cycle) expressible in three lines each; and it makes the QUERY COUNT
// assertable, which is the property that decides whether this is affordable
// on a large library. One query per directory is the whole design -- the
// readable alternative costs one per directory plus one per file, and the
// file count is the large number.

package org.missing_foss.trobar

import org.junit.Assert.assertEquals
import org.junit.Test

class FolderScanTest {

    /** A tree as a map of documentId to its children. `null` for an id
     * means that directory cannot be read, which is what a revoked
     * permission looks like mid-walk. */
    private class FakeTree(private val dirs: Map<String, List<ChildEntry>?>) {
        var queries = 0
            private set

        fun list(documentId: String): List<ChildEntry>? {
            queries++
            return dirs[documentId]
        }
    }

    private fun dir(id: String, name: String) = ChildEntry(id, name, isDirectory = true, size = 0)
    private fun file(name: String, size: Long) =
        ChildEntry("doc:$name", name, isDirectory = false, size = size)

    @Test
    fun countsAudioTheServerDoesNotKnowAbout() {
        val tree = FakeTree(mapOf("root" to listOf(file("stranger.mp3", 500))))
        val scan = walkForeignAudio("root", emptySet()) { tree.list(it) }
        assertEquals(500L, scan.foreignBytes)
        assertEquals(1, scan.foreignFileCount)
    }

    @Test
    fun excludesFilesTheServerAlreadyCounts() {
        // The server counts its own tracks in the budget already; counting
        // them here as well would charge the folder twice for them.
        val tree = FakeTree(mapOf("root" to listOf(file("ours.flac", 900), file("theirs.flac", 100))))
        val scan = walkForeignAudio("root", setOf("ours.flac")) { tree.list(it) }
        assertEquals(100L, scan.foreignBytes)
    }

    @Test
    fun matchesManagedPathsByFullRelativePath() {
        // "01 - Song.flac" under two different albums is two different
        // tracks. Matching on the bare name would exclude both on one hit.
        val tree = FakeTree(mapOf(
            "root" to listOf(dir("a", "Artist")),
            "a" to listOf(dir("b", "Ours"), dir("c", "Theirs")),
            "b" to listOf(file("01 - Song.flac", 700)),
            "c" to listOf(file("01 - Song.flac", 300)),
        ))
        val scan = walkForeignAudio("root", setOf("Artist/Ours/01 - Song.flac")) { tree.list(it) }
        assertEquals(300L, scan.foreignBytes)
    }

    @Test
    fun ignoresEverythingThatIsNotAudio() {
        // Cover art, Trobar's own generated playlists and the .nomedia
        // marker are not music, and the limit is on music.
        val tree = FakeTree(mapOf("root" to listOf(
            file("cover.jpg", 1000), file("Chill.m3u8", 1000), file(".nomedia", 1000),
            file("real.opus", 42),
        )))
        assertEquals(42L, walkForeignAudio("root", emptySet()) { tree.list(it) }.foreignBytes)
    }

    @Test
    fun countsAiffAsAudioButNotADotAlacFile() {
        // The server scans AIFF and copies it as is to a device not set to
        // MP3; ALAC lives in .m4a, so a ".alac" file is not audio.
        val tree = FakeTree(mapOf("root" to listOf(
            file("a.aiff", 100), file("b.AIF", 20), file("c.alac", 7))))
        assertEquals(120L, walkForeignAudio("root", emptySet()) { tree.list(it) }.foreignBytes)
    }

    @Test
    fun countsAPodcastOrAudiobookSittingInTheFolder() {
        // Deliberate, not an oversight: it takes the space the limit is a
        // limit on, whoever put it there.
        val tree = FakeTree(mapOf("root" to listOf(file("Episode 12.mp3", 5000))))
        assertEquals(5000L, walkForeignAudio("root", emptySet()) { tree.list(it) }.foreignBytes)
    }

    @Test
    fun oneQueryPerDirectoryAndNonePerFile() {
        // The measurement this whole design rests on. Four directories and
        // six files cost four queries; listFiles()+length() would cost ten.
        val tree = FakeTree(mapOf(
            "root" to listOf(dir("a", "A"), dir("b", "B")),
            "a" to listOf(dir("c", "C"), file("1.mp3", 1), file("2.mp3", 1)),
            "b" to listOf(file("3.mp3", 1), file("4.mp3", 1)),
            "c" to listOf(file("5.mp3", 1), file("6.mp3", 1)),
        ))
        val scan = walkForeignAudio("root", emptySet()) { tree.list(it) }
        assertEquals(6, scan.foreignFileCount)
        assertEquals(4, tree.queries)
        assertEquals(4, scan.directoriesQueried)
    }

    @Test
    fun anUnreadableSubtreeIsSkippedRatherThanLosingTheWholeMeasurement() {
        // A revoked permission on one folder must not turn the whole figure
        // into "unknown" -- the result is an undercount, which errs towards
        // letting Trobar sync rather than towards silently refusing to.
        val tree = FakeTree(mapOf(
            "root" to listOf(dir("ok", "Readable"), dir("no", "Denied")),
            "ok" to listOf(file("x.mp3", 300)),
            "no" to null,
        ))
        val scan = walkForeignAudio("root", emptySet()) { tree.list(it) }
        assertEquals(300L, scan.foreignBytes)
    }

    @Test
    fun aDirectoryCycleTerminates() {
        // Nothing guarantees the shape of a folder we did not create, and a
        // provider reporting a directory as its own descendant would
        // otherwise spin here forever.
        val tree = FakeTree(mapOf(
            "root" to listOf(dir("a", "A")),
            "a" to listOf(dir("root", "BackToRoot"), file("x.mp3", 10)),
        ))
        val scan = walkForeignAudio("root", emptySet()) { tree.list(it) }
        assertEquals(10L, scan.foreignBytes)
        assertEquals(2, tree.queries)
    }

    @Test
    fun anEmptyFolderMeasuresZeroRatherThanFailing() {
        // Zero is a real measurement and must be reportable as one. Only an
        // unreadable TREE yields null, and that is scanForeignAudio's job,
        // not this walk's -- the two must stay distinguishable all the way
        // to the server.
        val tree = FakeTree(mapOf("root" to emptyList()))
        val scan = walkForeignAudio("root", emptySet()) { tree.list(it) }
        assertEquals(0L, scan.foreignBytes)
        assertEquals(0, scan.foreignFileCount)
    }
}
