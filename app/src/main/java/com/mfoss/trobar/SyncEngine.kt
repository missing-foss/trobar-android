// SPDX-License-Identifier: GPL-3.0-or-later
package com.mfoss.trobar

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

data class SyncResult(
    val downloaded: Int, val deleted: Int, val failed: Int, val error: String? = null,
    // only ever populated when missingFileBehavior is "ask": the
    // caller (SyncWorker) persists these for StatusScreen to surface, since
    // this run deliberately didn't act on them itself.
    val missingTracks: List<TrackRef> = emptyList(),
)
data class SyncProgress(val done: Int, val total: Int)

/** Applies a server-computed diff (see ApiClient.getChanges) onto the SAF
 * tree the user picked. The server is the source of truth for what should
 * exist — this never scans the tree itself, it just applies adds/removes
 * (see the approved design: avoids needing a full SAF directory walk on
 * every sync, and survives reinstalls/manual deletions cleanly since the
 * server always recomputes from scratch). */
object SyncEngine {

    // Home Wi-Fi can sustain more than one transfer at once — sequential
    // downloads were leaving bandwidth unused, especially noticeable on a
    // library with lots of small tracks where per-request latency (not
    // throughput) dominates.
    private const val MAX_CONCURRENT_DOWNLOADS = 3
    private const val DOWNLOAD_RETRIES = 2

    suspend fun run(
        context: Context,
        api: ApiClient,
        treeUriString: String,
        nomediaEnabled: Boolean = false,
        missingFileBehavior: String = Prefs.MISSING_ASK,
        onProgress: (SyncProgress) -> Unit = {},
    ): SyncResult = coroutineScope {
        val treeUri = Uri.parse(treeUriString)
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: return@coroutineScope SyncResult(0, 0, 0, context.getString(R.string.sync_folder_not_found))

        // Enforced on every sync (not just when toggled in Settings) so it
        // stays correct even if the sync folder itself changes later.
        try { ensureNomediaMarker(root, nomediaEnabled) } catch (e: Exception) { /* best-effort */ }

        var changes = try {
            api.getChanges()
        } catch (e: IOException) {
            return@coroutineScope SyncResult(0, 0, 0, context.getString(R.string.cannot_reach_server, e.message))
        }

        // device_track_state's "downloaded" bookkeeping is never
        // otherwise verified against what's actually still on disk (a track
        // deleted by hand on the device would silently stay "synced"
        // forever server-side). This is the one bounded check for that —
        // scoped to exactly the tracks the server already believes are
        // here, same order of cost as the download/delete loops below, not
        // a full library scan.
        val reportedMissing = mutableListOf<TrackRef>()
        // Cached lookups: SAF's findFile() lists the whole directory on every
        // call, and this check touches every downloaded track (thousands) —
        // uncached, the pre-download phase took minutes of silent local I/O
        // (user-reported as "sync started but nothing happening"). One
        // listFiles() per distinct directory instead.
        val listingCache = HashMap<android.net.Uri, Map<String, DocumentFile>>()
        fun cachedChild(dir: DocumentFile, name: String): DocumentFile? =
            listingCache.getOrPut(dir.uri) { dir.listFiles().associateBy { f -> f.name ?: "" } }[name]
        fun findLocalFileCached(relativePath: String): DocumentFile? {
            val parts = relativePath.split("/")
            var dir: DocumentFile? = root
            for (segment in parts.dropLast(1)) {
                dir = dir?.let { d -> cachedChild(d, segment) } ?: return null
            }
            return dir?.let { d -> cachedChild(d, parts.last()) }
        }
        val missingTracks = changes.downloaded.filter { findLocalFileCached(it.relativePath) == null }
        if (missingTracks.isNotEmpty()) {
            try {
                when (missingFileBehavior) {
                    Prefs.MISSING_ALWAYS_RESYNC -> {
                        api.resolveMissingTracks(redownload = missingTracks.map { it.trackId }, exclude = emptyList())
                        changes = api.getChanges() // pick up the newly-pending tracks in this same run
                    }
                    Prefs.MISSING_ALWAYS_EXCLUDE -> {
                        api.resolveMissingTracks(redownload = emptyList(), exclude = missingTracks.map { it.trackId })
                    }
                    else -> reportedMissing.addAll(missingTracks) // "ask" — surfaced via SyncResult, not acted on here
                }
            } catch (e: IOException) { /* best-effort — try again next sync */ }
        }

        // this client can't transcode — a device set to an MP3
        // format must be synced by the desktop app. Downloading the
        // original under the .mp3 name the server expects would write FLAC
        // bytes into an .mp3 file, so flagged tracks are skipped (they stay
        // pending server-side) with one clear message.
        val transcodeFlagged = changes.toDownload.count { it.transcode }
        val downloadable = changes.toDownload.filter { !it.transcode }

        val total = downloadable.size + changes.toDelete.size
        val done = AtomicInteger(0)
        val downloaded = AtomicInteger(0)
        val deleted = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val firstError = AtomicReference<String?>(null)
        onProgress(SyncProgress(0, total))

        // Two (or more) tracks from the same album would otherwise race to
        // create the same parent directory concurrently — SAF has no atomic
        // "create if not exists". Only the directory-creation step is
        // serialized; the actual network transfer still runs in parallel.
        val dirMutex = Mutex()
        val semaphore = Semaphore(MAX_CONCURRENT_DOWNLOADS)

        if (transcodeFlagged > 0) {
            firstError.compareAndSet(null, context.getString(R.string.transcode_needs_desktop, transcodeFlagged))
        }

        val downloadJobs = downloadable.map { track ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    try {
                        downloadOne(context, api, root, track, dirMutex)
                        api.ack(track.trackId, "downloaded")
                        downloaded.incrementAndGet()
                    } catch (e: Exception) {
                        failed.incrementAndGet()
                        firstError.compareAndSet(null, context.getString(R.string.track_error, track.relativePath, e.message))
                    }
                    onProgress(SyncProgress(done.incrementAndGet(), total))
                }
            }
        }
        downloadJobs.awaitAll()

        // Deletes are local + cheap, no need to parallelize or bound them.
        for (track in changes.toDelete) {
            try {
                deleteOne(root, track.relativePath)
                api.ack(track.trackId, "removed")
                deleted.incrementAndGet()
            } catch (e: Exception) {
                failed.incrementAndGet()
                firstError.compareAndSet(null, context.getString(R.string.track_error, track.relativePath, e.message))
            }
            onProgress(SyncProgress(done.incrementAndGet(), total))
        }

        // playlist files at the sync-folder root: write/refresh
        // the server-generated .m3u8s, remove Trobar-marked ones that no
        // longer correspond to an assigned playlist. User-made playlist
        // files (no marker) are never touched.
        try {
            writePlaylists(context, root, changes.playlists)
        } catch (e: Exception) {
            firstError.compareAndSet(null, context.getString(R.string.track_error, "playlists", e.message))
        }

        // Best-effort, decorative — walks every artist folder currently on
        // device (not just ones touched this run) so a picture backfills
        // for artists synced before this feature existed too. A missing or
        // failed picture is never treated as a sync failure. the
        // setting is device-level now (web UI, /api/device/info) — off /
        // small (~512px, server-side downscale) / full.
        val artistImages = try { api.getDeviceInfo().artistImages } catch (e: Exception) { null }
        if (artistImages == "small" || artistImages == "full") {
            for (dir in root.listFiles()) {
                if (!dir.isDirectory) continue
                try {
                    downloadArtistImageIfMissing(context, api, dir, small = artistImages == "small")
                } catch (e: Exception) { /* decorative only — ignore */ }
            }
        }

        SyncResult(downloaded.get(), deleted.get(), failed.get(), firstError.get(), reportedMissing)
    }

    /** Creates or removes an empty ".nomedia" marker at the sync folder's
     * root — tells Android's MediaScanner to skip the whole tree (recursive:
     * this hides the synced audio too, from any app that discovers media via
     * MediaStore rather than its own folder scan, not just galleries — the
     * Settings screen warns about this before letting the user turn it on).
     * Public (not private) so SettingsScreen can call it directly for
     * instant feedback when the toggle changes, instead of waiting for the
     * next sync to pick it up. */
    fun ensureNomediaMarker(root: DocumentFile, shouldExist: Boolean) {
        val existing = root.findFile(".nomedia")
        if (shouldExist && existing == null) {
            root.createFile("application/octet-stream", ".nomedia")
        } else if (!shouldExist && existing != null) {
            existing.delete()
        }
    }

    private fun downloadArtistImageIfMissing(context: Context, api: ApiClient, dir: DocumentFile,
                                             small: Boolean = false) {
        if (dir.findFile("artist.jpg") != null) return // never overwrite — respects a manually-added picture too
        val artistName = dir.name ?: return
        val bytes = api.downloadArtistImage(artistName, small = small) ?: return
        val file = dir.createFile("image/jpeg", "artist.jpg") ?: return
        context.contentResolver.openOutputStream(file.uri)?.use { it.write(bytes) }
    }

    private suspend fun downloadOne(
        context: Context,
        api: ApiClient,
        root: DocumentFile,
        track: TrackRef,
        dirMutex: Mutex,
    ) {
        val parts = track.relativePath.split("/")
        val fileName = parts.last()
        val dir = dirMutex.withLock { mkdirs(context, root, parts.dropLast(1)) }

        var file = dir.findFile(fileName)
        if (file != null && file.length() == track.size) {
            return // already downloaded (e.g. a previous run finished the write but the ack failed)
        }
        if (file == null) {
            file = dir.createFile(guessMimeType(fileName), fileName)
                ?: throw IOException(context.getString(R.string.file_creation_failed))
        }

        // Large FLACs can have their socket torn down mid-transfer by a
        // transient Wi-Fi blip ("Software caused connection abort", seen
        // directly in production on a 51MB file). Resuming via Range from
        // however many bytes already landed on disk — rather than
        // restarting from zero — avoids re-transferring tens of MB that
        // were already received just because the last few KB didn't land.
        var lastError: Exception? = null
        for (attempt in 0..DOWNLOAD_RETRIES) {
            val resumeFrom = file.length()
            if (resumeFrom >= track.size) break // a previous attempt actually finished
            try {
                api.downloadFile(track.trackId, resumeFrom).use { resp ->
                    val input = resp.body?.byteStream() ?: throw IOException(context.getString(R.string.empty_response))
                    val mode = if (resumeFrom > 0) "wa" else "w"
                    context.contentResolver.openOutputStream(file.uri, mode)?.use { output ->
                        input.copyTo(output)
                    } ?: throw IOException(context.getString(R.string.write_stream_unavailable))
                }
                return
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException(context.getString(R.string.download_failed))
    }

    private fun deleteOne(root: DocumentFile, relativePath: String) {
        findLocalFile(root, relativePath)?.delete()
    }

    /** Walks the SAF tree for one relative path, or null if any segment
     * (folder or file) along the way is missing — shared by deleteOne and
     * the missing-file check, which only cares whether this
     * returns null or not. */
    private fun findLocalFile(root: DocumentFile, relativePath: String): DocumentFile? {
        val parts = relativePath.split("/")
        var dir: DocumentFile? = root
        for (segment in parts.dropLast(1)) {
            dir = dir?.findFile(segment) ?: return null
        }
        return dir?.findFile(parts.last())
    }

    private fun mkdirs(context: Context, root: DocumentFile, segments: List<String>): DocumentFile {
        var dir = root
        for (segment in segments) {
            val existing = dir.findFile(segment)?.takeIf { it.isDirectory }
            dir = existing ?: (dir.createDirectory(segment) ?: throw IOException(context.getString(R.string.folder_creation_failed, segment)))
        }
        return dir
    }

    /** marker the server writes into every generated playlist;
     * only files carrying it are ever deleted here. */
    private const val M3U_MARKER = "# Generated by Trobar"

    private fun readHead(context: Context, f: DocumentFile, bytes: Int = 256): String? = try {
        context.contentResolver.openInputStream(f.uri)?.use { s ->
            val buf = ByteArray(bytes)
            val n = s.read(buf)
            if (n <= 0) "" else String(buf, 0, n, Charsets.UTF_8)
        }
    } catch (e: Exception) { null }

    private fun writePlaylists(context: Context, root: DocumentFile, playlists: List<PlaylistRef>) {
        val expected = playlists.map { it.filename }.toSet()
        val rootFiles = root.listFiles().associateBy { it.name ?: "" }
        for (pl in playlists) {
            val existing = rootFiles[pl.filename]
            if (existing != null) {
                val current = try {
                    context.contentResolver.openInputStream(existing.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                } catch (e: Exception) { null }
                if (current == pl.content) continue
                existing.delete()
            }
            // application/octet-stream: ExternalStorageProvider keeps the
            // display name untouched for it — an audio/x-mpegurl mime would
            // make SAF append a second ".m3u" extension.
            val f = root.createFile("application/octet-stream", pl.filename)
                ?: throw IOException(context.getString(R.string.file_creation_failed))
            context.contentResolver.openOutputStream(f.uri)?.use { it.write(pl.content.toByteArray(Charsets.UTF_8)) }
        }
        for (f in root.listFiles()) {
            val name = f.name ?: continue
            if (!name.endsWith(".m3u8", ignoreCase = true) || name in expected) continue
            if (readHead(context, f)?.contains(M3U_MARKER) == true) f.delete()
        }
    }

    private fun guessMimeType(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "flac" -> "audio/flac"
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        else -> "application/octet-stream"
    }
}
