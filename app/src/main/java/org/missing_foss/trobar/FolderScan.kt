// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

/** What a folder scan found. `directoriesQueried` is here for the tests
 * rather than for the server: the cost of this walk is one content-provider
 * query per DIRECTORY, and that count is the thing worth asserting, since
 * the obvious implementation costs one per directory plus one per FILE. */
data class FolderScan(
    val foreignBytes: Long,
    val foreignFileCount: Int,
    val directoriesQueried: Int,
)

/** Bytes of music sitting in the sync folder that Trobar did not put there.
 *
 * "Max music size" is the total the user allows in that folder, so the
 * server has to know this to make the limit mean what the setting says --
 * see its own POST /api/device/storage. Anything the server already counts
 * (`managedPaths`) is excluded here rather than subtracted there: the
 * server's own figure for a track is an estimate for anything not yet
 * acknowledged, and subtracting an estimate from a measurement can go
 * negative.
 *
 * WHY THIS IS A NEW WALK. SyncEngine's docstring says outright that it never
 * scans the tree, and that was deliberate -- a full SAF walk on every sync
 * was the cost the diff-based design avoided. Nothing else here can answer
 * the question though: the whole point is the files the server has never
 * heard of, so there is no server-side list to consult and no cheaper place
 * to hang it. The walk that already exists (SyncEngine.collectHeldPaths)
 * runs only once, at enrollment.
 *
 * So the cost is paid down where it can be. One `query()` per directory,
 * asking for name, mime type and size TOGETHER -- rather than
 * DocumentFile.listFiles() (a query per directory) followed by
 * DocumentFile.length() (another query per file), which is what the
 * readable version of this costs. On a library of N files in D directories
 * that is D queries instead of D + N.
 *
 * Returns null if the tree cannot be read at all. Null is not zero and the
 * caller must not report it as such: a folder nobody could measure and an
 * empty one are different claims, and the server keeps them apart.
 */
fun scanForeignAudio(context: Context, treeUri: Uri, managedPaths: Set<String>): FolderScan? {
    val rootId = try {
        DocumentsContract.getTreeDocumentId(treeUri)
    } catch (ignored: IllegalArgumentException) {
        return null
    }
    return walkForeignAudio(rootId, managedPaths) { documentId ->
        readChildren(context, treeUri, documentId)
    }
}

/** One entry in a directory listing, flattened out of whatever produced it. */
data class ChildEntry(
    val documentId: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
)

/** The traversal itself, with the listing injected.
 *
 * Split from [scanForeignAudio] so the walk can be tested without a
 * DocumentsProvider, and specifically so the QUERY COUNT can be asserted --
 * one per directory is the property that makes this affordable, and it is
 * not visible from the outside otherwise. A `listChildren` returning null
 * means that directory could not be read; it is skipped rather than
 * abandoning the whole walk.
 *
 * Breadth-first from an explicit queue rather than recursion: a
 * pathological tree should cost heap, not stack, and this runs on somebody
 * else's phone against a folder we did not create. */
internal fun walkForeignAudio(
    rootId: String,
    managedPaths: Set<String>,
    listChildren: (String) -> List<ChildEntry>?,
): FolderScan {
    var bytes = 0L
    var files = 0
    var queries = 0

    val pending = ArrayDeque<Pair<String, String>>()  // documentId to relative prefix
    pending.add(rootId to "")
    val seen = HashSet<String>()

    while (pending.isNotEmpty()) {
        val (documentId, prefix) = pending.removeFirst()
        // A provider that reports a directory as its own descendant would
        // otherwise spin here forever. Cheap insurance on a tree whose
        // shape is not ours to guarantee.
        if (!seen.add(documentId)) continue
        queries++
        val children = listChildren(documentId) ?: continue
        for (child in children) {
            val path = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
            if (child.isDirectory) {
                pending.add(child.documentId to path)
            } else if (isForeignAudio(child.name, path, managedPaths)) {
                bytes += child.size
                files++
            }
        }
    }
    return FolderScan(bytes, files, queries)
}

private const val COL_ID = 0
private const val COL_NAME = 1
private const val COL_MIME = 2
private const val COL_SIZE = 3

private val SCAN_PROJECTION = arrayOf(
    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
    DocumentsContract.Document.COLUMN_MIME_TYPE,
    DocumentsContract.Document.COLUMN_SIZE,
)

/** One directory, in ONE content-provider query.
 *
 * name, mime type and size come back in the same cursor. The readable
 * alternative -- DocumentFile.listFiles() followed by length() on each
 * result -- is a query per directory PLUS a query per file, and the file
 * count is the large number here. */
internal fun readChildren(context: Context, treeUri: Uri, documentId: String): List<ChildEntry>? {
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
    val cursor: Cursor? = try {
        context.contentResolver.query(childrenUri, SCAN_PROJECTION, null, null, null)
    } catch (ignored: SecurityException) {
        // Permission for the tree was revoked mid-walk. One unreadable
        // subtree must not lose the whole measurement, so skip it -- the
        // figure is then an undercount, which errs towards letting Trobar
        // sync rather than towards silently refusing to.
        null
    } catch (ignored: IllegalArgumentException) {
        null
    }
    return cursor?.use { c ->
        buildList {
            while (c.moveToNext()) {
                val name = c.getString(COL_NAME) ?: continue
                add(ChildEntry(
                    documentId = c.getString(COL_ID) ?: continue,
                    name = name,
                    isDirectory = c.getString(COL_MIME) == DocumentsContract.Document.MIME_TYPE_DIR,
                    size = if (c.isNull(COL_SIZE)) 0L else c.getLong(COL_SIZE),
                ))
            }
        }
    }
}

/** One entry of a directory, with the DocumentFile to act on it. */
internal data class ListedFile(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val file: DocumentFile,
)

/** A directory's children with name, kind and size, for the sync's walks.
 *
 * On a SAF tree this is ONE content-provider query, the one [readChildren]
 * makes. `listFiles()` followed by `name`, `isDirectory` or `length()` on each
 * child is a query per directory plus a query per child per property, and a
 * sync touching thousands of tracks paid it for every one. A `file:`
 * directory, which the tests use, is listed directly, where those calls cost
 * nothing. Null if the directory could not be read. */
internal fun listDirectory(context: Context, dir: DocumentFile): List<ListedFile>? {
    val uri = dir.uri
    if (uri.scheme != "content") {
        return dir.listFiles().mapNotNull { f -> f.name?.let { ListedFile(it, f.isDirectory, f.length(), f) } }
    }
    val documentId = try {
        DocumentsContract.getDocumentId(uri)
    } catch (ignored: IllegalArgumentException) {
        return null
    }
    val children = readChildren(context, uri, documentId) ?: return null
    return children.mapNotNull { c ->
        DocumentFile.fromTreeUri(context, DocumentsContract.buildDocumentUriUsingTree(uri, c.documentId))
            ?.let { ListedFile(c.name, c.isDirectory, c.size, it) }
    }
}

/** Audio, and not something the server already counts.
 *
 * Audio only, matching what the server's own budget counts and what
 * SyncEngine.collectHeldPaths already treats as a track: cover art and
 * Trobar's own .m3u8 playlists are not music and are not what the limit is
 * about. A podcast or an audiobook in the folder IS counted, deliberately
 * -- it takes the space the limit is a limit on. */
private fun isForeignAudio(name: String, path: String, managedPaths: Set<String>): Boolean =
    name.substringAfterLast('.', "").lowercase() in AUDIO_FILE_EXTENSIONS &&
        path !in managedPaths

/** Shared with SyncEngine, which uses the same set to decide what counts as
 * a track when it reports held paths at enrollment. One definition, because
 * two would drift and the disagreement would show up as a budget that is
 * quietly wrong rather than as an error. The formats the server scans; ALAC
 * lives in .m4a (there is no .alac extension). Kept aligned with the desktop
 * client's audioExtensions. */
val AUDIO_FILE_EXTENSIONS = setOf("flac", "wav", "aiff", "aif", "mp3", "m4a", "ogg", "opus", "aac")
