// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

data class SyncResult(
    val downloaded: Int, val deleted: Int, val failed: Int, val error: String? = null,
    // only ever populated when missingFileBehavior is "ask": the
    // caller (SyncWorker) persists these for the sync card in Settings to surface, since
    // this run deliberately didn't act on them itself.
    val missingTracks: List<TrackRef> = emptyList(),
    // Bytes of music in the sync folder that Trobar did not put there --
    // what the server's size limit is a limit on, alongside our own files.
    // NULL MEANS NOT MEASURED, never zero: a folder nobody could read and
    // an empty one are different claims, and the caller must not flatten
    // them by defaulting this to 0 on the way out.
    val foreignBytes: Long? = null,
    // The server refused this device's token (401): only re-enrolling
    // fixes it, so the caller must not retry. `error` says so in the same
    // words as the connection indicator.
    val unauthorized: Boolean = false,
    // The tracks this device holds, or is about to, by the server's last
    // answer: what it believed was here plus what it sent, minus what it
    // removed. Null when the sync ended before that answer. The local
    // provenance rows of any other track are stale.
    val heldTrackIds: Set<Long>? = null,
    // The files this run wrote or removed, relative to the sync folder:
    // what the media index is told about afterwards (MediaIndex).
    val changedPaths: List<String> = emptyList(),
    // What went wrong, track by track, up to MAX_REPORTED_FAILURES; `failed`
    // is the whole count.
    val failures: List<SyncFailure> = emptyList(),
)

/** One track this run could not download or remove, and why. */
data class SyncFailure(val path: String, val reason: String)

/** What a run is about to do: tracks to download, the bytes they need, and
 * the free space, null when it can't be read. */
data class SyncPlan(val tracks: Int, val bytes: Long, val freeBytes: Long?)

/** The most failures a run keeps. A failed sync can fail every track, and
 * this list lives in the preferences store. */
const val MAX_REPORTED_FAILURES = 50
// #40: `scanning` marks the recovery SAF tree-walk phase (before the first
// pull), where `done` is a running count of audio files seen and `total` is 0
// (indeterminate) — so the UI can say "Scanning your library…" instead of
// showing a 0/0 sync.
data class SyncProgress(val done: Int, val total: Int, val scanning: Boolean = false, val plan: SyncPlan? = null)

/** What the downloads need; a file already whole on disk needs nothing. */
private fun neededBytes(changes: Changes, onDisk: (String) -> Long): Long = changes.toDownload.sumOf {
    when {
        it.transcode -> it.estimatedSize ?: 0L
        onDisk(it.relativePath) == it.size -> 0L
        else -> it.size
    }
}

/** The plan for [queue]; null when there is nothing to download. */
private fun syncPlan(queue: List<TrackRef>, changes: Changes, free: Long?, onDisk: (String) -> ListedFile?): SyncPlan? =
    if (queue.isEmpty()) null else SyncPlan(queue.size, neededBytes(changes) { onDisk(it)?.size ?: 0L }, free)

private fun failureReason(context: Context, e: Exception): String =
    e.message ?: context.getString(R.string.download_failed)

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
    // #29: HTTP 206 Partial Content — the only response we may safely append to.
    private const val HTTP_PARTIAL_CONTENT = 206
    // A download is written beside its track, to the track's name plus
    // this, and renamed over it once whole. Typed as plain bytes so a
    // provider doesn't add an audio extension to the part's name.
    private const val PART_SUFFIX = ".part"
    // The file a download replaces is moved to its name plus this until the
    // new one is in place, so a swap that fails can put it back.
    private const val ASIDE_SUFFIX = ".old"
    private const val PART_MIME = "application/octet-stream"
    // #40: emit a recovery-scan progress tick every this-many audio files.
    private const val SCAN_PROGRESS_STRIDE = 25

    // Only these (audio) files map to library tracks the manifest can match —
    // playlists (.m3u8), artist/cover art (.jpg) and .nomedia are skipped.
    // One definition, shared with the folder scan that measures what the
    // server's size limit is a limit on -- two copies would drift, and the
    // disagreement would surface as a budget that is quietly wrong rather
    // than as an error.
    private val AUDIO_EXTENSIONS = AUDIO_FILE_EXTENSIONS
    private const val TAG = "SyncEngine"

    suspend fun run(
        context: Context,
        api: ApiClient,
        treeUriString: String,
        nomediaEnabled: Boolean = false,
        missingFileBehavior: String = Prefs.MISSING_ASK,
        recoveryPending: Boolean = false,
        onProgress: (SyncProgress) -> Unit = {},
    ): SyncResult = runIn(context, api, Uri.parse(treeUriString), nomediaEnabled, missingFileBehavior,
        recoveryPending, onProgress)

    /** [run] on a parsed URI: the SAF tree the user picked, or in tests a
     * plain `file:` directory. */
    internal suspend fun runIn(
        context: Context,
        api: ApiClient,
        treeUri: Uri,
        nomediaEnabled: Boolean = false,
        missingFileBehavior: String = Prefs.MISSING_ASK,
        recoveryPending: Boolean = false,
        onProgress: (SyncProgress) -> Unit = {},
    ): SyncResult = coroutineScope {
        val root = rootFor(context, treeUri)
            ?: return@coroutineScope SyncResult(0, 0, 0, context.getString(R.string.sync_folder_not_found))

        // Enforced on every sync (not just when toggled in Settings) so it
        // stays correct even if the sync folder itself changes later.
        try { ensureNomediaMarker(root, nomediaEnabled) } catch (ignored: Exception) { /* best-effort */ }

        val (pulled, stop) = firstPull(context, api, root, recoveryPending, onProgress)
        if (stop != null) return@coroutineScope stop
        var changes = pulled!!

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
        // listing per distinct directory instead, and one query for it, names
        // and sizes included (listDirectory): a name or size read per file
        // is a query per file too (SyncQueryCountTest).
        val listingCache = HashMap<android.net.Uri, Map<String, ListedFile>>()
        fun cachedEntries(dir: DocumentFile): Map<String, ListedFile> =
            listingCache.getOrPut(dir.uri) { (listDirectory(context, dir) ?: emptyList()).associateBy { it.name } }
        fun cachedListing(dir: DocumentFile): Map<String, DocumentFile> = cachedEntries(dir).mapValues { it.value.file }
        fun findLocalEntryCached(relativePath: String): ListedFile? {
            val parts = relativePath.split("/")
            var dir: DocumentFile = root
            for (segment in parts.dropLast(1)) {
                dir = cachedEntries(dir)[segment]?.takeIf { it.isDirectory }?.file ?: return null
            }
            return cachedEntries(dir)[parts.last()]
        }
        fun findLocalFileCached(relativePath: String): DocumentFile? = findLocalEntryCached(relativePath)?.file
        val missingTracks = changes.downloaded.filter { findLocalEntryCached(it.relativePath) == null }
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
            } catch (ignored: IOException) { /* best-effort — try again next sync */ }
        }

        // Before the space check, so what they held counts as free.
        deleteStaleParts(root, changes.toDownload, ::findLocalFileCached, ::cachedListing)

        // Before any download: a folder that can't hold them is said so, once,
        // instead of every download failing on it in turn.
        val shortfall = spaceShortfall(context, treeUri, changes) { findLocalEntryCached(it)?.size ?: 0L }
        val queue = if (shortfall == null) changes.toDownload else emptyList()
        val diskFull = java.util.concurrent.atomic.AtomicBoolean(false)

        val total = queue.size + changes.toDelete.size
        val done = AtomicInteger(0)
        val downloaded = AtomicInteger(0)
        val deleted = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val changed = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val firstError = AtomicReference<String?>(shortfall)
        val failures = java.util.concurrent.ConcurrentLinkedQueue<SyncFailure>()
        onProgress(SyncProgress(0, total, plan = syncPlan(queue, changes, freeSpaceOf(context, treeUri), ::findLocalEntryCached)))

        // Two (or more) tracks from the same album would otherwise race to
        // create the same parent directory concurrently — SAF has no atomic
        // "create if not exists". Only the directory-creation step is
        // serialized; the actual network transfer still runs in parallel.
        val dirMutex = Mutex()
        val semaphore = Semaphore(MAX_CONCURRENT_DOWNLOADS)

        val downloadJobs = queue.map { track ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    downloadAndAck(context, api, root, track, dirMutex,
                        DownloadTally(downloaded, failed, firstError, diskFull, changed, failures))
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
                changed.add(track.relativePath)
            } catch (e: Exception) {
                failed.incrementAndGet()
                firstError.compareAndSet(null, context.getString(R.string.track_error, track.relativePath, e.message))
                failures.add(SyncFailure(track.relativePath, failureReason(context, e)))
            }
            onProgress(SyncProgress(done.incrementAndGet(), total))
        }

        // playlist files at the sync-folder root: write/refresh
        // the server-generated .m3u8s, remove Trobar-marked ones that no
        // longer correspond to an assigned playlist. User-made playlist
        // files (no marker) are never touched.
        try {
            changed.addAll(writePlaylists(context, root, changes.playlists))
        } catch (e: Exception) {
            firstError.compareAndSet(null, context.getString(R.string.track_error, "playlists", e.message))
        }

        // After the write above, so this reads the folder as it now stands.
        uploadDevicePlaylists(context, api, treeUri, firstError)

        // Best-effort, decorative — walks every artist folder currently on
        // device (not just ones touched this run) so a picture backfills
        // for artists synced before this feature existed too. A missing or
        // failed picture is never treated as a sync failure. the
        // setting is device-level now (web UI, /api/device/info) — off /
        // small (~512px, server-side downscale) / full.
        val artistImages = try { api.getDeviceInfo().artistImages } catch (ignored: Exception) { null }
        if (artistImages == "small" || artistImages == "full") {
            for (entry in listDirectory(context, root).orEmpty()) {
                if (!entry.isDirectory) continue
                try {
                    downloadArtistImageIfMissing(context, api, entry, small = artistImages == "small")
                } catch (ignored: Exception) { /* decorative only — ignore */ }
            }
        }

        SyncResult(downloaded.get(), deleted.get(), failed.get(), firstError.get(), reportedMissing,
            measureForeignBytes(context, treeUri, changes),
            heldTrackIds = heldTrackIds(changes), changedPaths = changed.toList(), failures = failures.take(MAX_REPORTED_FAILURES))
    }

    internal fun heldTrackIds(changes: Changes): Set<Long> =
        (changes.downloaded.map { it.trackId } + changes.toDownload.map { it.trackId }).toSet() -
            changes.toDelete.map { it.trackId }.toSet()

    /** Playlists a person made in the folder go to the server, every sync,
     * the whole set: one missing from a later upload is removed there. A
     * failure is reported through [firstError] but never fails the sync. */
    private fun uploadDevicePlaylists(
        context: Context, api: ApiClient, treeUri: Uri, firstError: AtomicReference<String?>,
    ) {
        try {
            val found = collectDevicePlaylists(context, treeUri)
            if (found == null) {
                firstError.compareAndSet(null, context.getString(R.string.device_playlists_unreadable))
                return
            }
            api.postDevicePlaylists(found)
        } catch (e: Exception) {
            firstError.compareAndSet(null, context.getString(R.string.track_error, "device playlists", e.message))
        }
    }

    /** Bytes in the sync folder that are not ours, or null if the folder
     * could not be measured.
     *
     * Called LAST, after this run's own downloads and deletions have
     * landed, so the figure describes the folder as it now is rather than
     * as it was when the run started. `changes` names everything the server
     * wants here -- already downloaded plus queued -- so anything else in
     * the tree is by definition not ours.
     *
     * Null on any failure, never 0. An unmeasurable folder must not be
     * reported as an empty one, and a throw here would fail a sync that has
     * otherwise already succeeded -- never the right trade for a number
     * that only improves a budget. */
    private fun measureForeignBytes(context: Context, treeUri: Uri, changes: Changes): Long? = try {
        val managed = (changes.downloaded + changes.toDownload)
            .mapTo(HashSet()) { it.relativePath }
        scanForeignAudio(context, treeUri, managed)?.foreignBytes
    } catch (ignored: Exception) {
        null
    }

    /** Creates or removes an empty ".nomedia" marker at the sync folder's
     * root — tells Android's MediaScanner to skip the whole tree (recursive:
     * this hides the synced audio too, from any app that discovers media via
     * MediaStore rather than its own folder scan, not just galleries — the
     * Settings screen warns about this before letting the user turn it on).
     * Public (not private) so SettingsScreen can call it directly for
     * instant feedback when the toggle changes, instead of waiting for the
     * next sync to pick it up. */
    /** The sync folder: the SAF tree the user picked, or in tests a plain
     * `file:` directory. */
    private fun rootFor(context: Context, treeUri: Uri): DocumentFile? =
        if (treeUri.scheme == "file") DocumentFile.fromFile(File(treeUri.path!!))
        else DocumentFile.fromTreeUri(context, treeUri)

    /** Recovery, then the first pull: the server's changes, or the
     * result that ends this sync. Recovery runs (and clears its flag) before
     * the pull; an error from it means it couldn't finish (network), so the
     * sync stops and retries rather than going on to a prune/re-download.
     * A refused token, from either, is caught before the IOException it
     * subclasses: it isn't "could not reach the server", and no retry can
     * fix it. */
    private suspend fun firstPull(
        context: Context, api: ApiClient, root: DocumentFile, recoveryPending: Boolean,
        onProgress: (SyncProgress) -> Unit,
    ): Pair<Changes?, SyncResult?> = try {
        val recoveryError = recoverIfPending(context, api, root, recoveryPending, onProgress)
        if (recoveryError != null) null to SyncResult(0, 0, 0, recoveryError)
        else api.getChanges() to null
    } catch (e: UnauthorizedException) {
        Log.w(TAG, "the server refused this device's token", e)
        null to SyncResult(0, 0, 0, context.getString(R.string.conn_indicator_unauthorized), unauthorized = true)
    } catch (e: IOException) {
        null to SyncResult(0, 0, 0, context.getString(R.string.cannot_reach_server, e.message))
    }

    /** #34 recovery: on the first sync after a (re-)enrollment, if the chosen
     * folder already holds a library, tell the server we have it BEFORE the
     * first pull — source_of_truth='device' (so it won't prune what we hold),
     * then the manifest (so it marks those tracks downloaded instead of
     * re-queuing them). Both are idempotent; the flag is cleared only once the
     * handshake completes, so a network failure mid-recovery retries next sync.
     * Returns an error message if it couldn't complete, else null. */
    private suspend fun recoverIfPending(
        context: Context, api: ApiClient, root: DocumentFile, recoveryPending: Boolean,
        onProgress: (SyncProgress) -> Unit,
    ): String? {
        if (!recoveryPending) return null
        // #40: the tree-walk is the slow part of recovery; report a running
        // count so the UI shows "Scanning your library…" rather than a silent
        // pause on a large library.
        val held = collectHeldPaths(context, root) { seen -> onProgress(SyncProgress(seen, 0, scanning = true)) }
        if (held.isNotEmpty()) {
            try {
                api.setSourceOfTruth("device")
                api.postManifest(held)
            } catch (e: UnauthorizedException) {
                throw e // the caller words a refused token
            } catch (e: IOException) {
                return context.getString(R.string.cannot_reach_server, e.message)
            }
        }
        Prefs.setRecoveryPending(context, false)
        return null
    }

    /** #34 recovery: the server-relative device paths of every audio file
     * currently in the sync folder — a full SAF tree walk (used only at
     * recovery, a rare one-shot). These are exactly the wire paths getChanges
     * emits (Artiste/Album/NN - Titre.ext), so the server's manifest matcher
     * recognizes them. Non-track files (playlists, artwork, .nomedia) are
     * skipped so `unmatched` reflects real unknown tracks. */
    private fun collectHeldPaths(context: Context, root: DocumentFile, onScan: (Int) -> Unit = {}): List<String> {
        val out = mutableListOf<String>()
        fun walk(dir: DocumentFile, prefix: String) {
            // One query per directory, names and kinds included (listDirectory).
            for (child in listDirectory(context, dir).orEmpty()) {
                val name = child.name
                val path = if (prefix.isEmpty()) name else "$prefix/$name"
                if (child.isDirectory) {
                    walk(child.file, path)
                } else if (name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS) {
                    out.add(path)
                    // Throttled so a big library doesn't fire a foreground/
                    // WorkManager update per file.
                    if (out.size % SCAN_PROGRESS_STRIDE == 0) onScan(out.size)
                }
            }
        }
        walk(root, "")
        onScan(out.size)
        return out
    }

    fun ensureNomediaMarker(root: DocumentFile, shouldExist: Boolean) {
        val existing = root.findFile(".nomedia")
        if (shouldExist && existing == null) {
            root.createFile("application/octet-stream", ".nomedia")
        } else if (!shouldExist && existing != null) {
            existing.delete()
        }
    }

    private fun downloadArtistImageIfMissing(context: Context, api: ApiClient, entry: ListedFile,
                                             small: Boolean = false) {
        val dir = entry.file
        // never overwrite — respects a manually-added picture too
        if (listDirectory(context, dir)?.any { it.name == "artist.jpg" } != false) return
        val artistName = entry.name
        val bytes = api.downloadArtistImage(artistName, small = small) ?: return
        val file = dir.createFile("image/jpeg", "artist.jpg") ?: return
        context.contentResolver.openOutputStream(file.uri)?.use { it.write(bytes) }
    }

    /** Deletes the parts (and set-aside old files) that a sync which didn't
     * finish left for these tracks. A part is never resumed across syncs:
     * the file may have changed on the server since, and a bare Range would
     * join the old file's head to the new one's tail, at the right length,
     * with nothing to show for it. Matched by prefix, since a provider may
     * still add to a new file's name. */
    private fun deleteStaleParts(
        root: DocumentFile, tracks: List<TrackRef>,
        find: (String) -> DocumentFile?, listing: (DocumentFile) -> Map<String, DocumentFile>,
    ) {
        for (track in tracks) {
            val parent = track.relativePath.substringBeforeLast('/', "")
            val dir = (if (parent.isEmpty()) root else find(parent)) ?: continue
            val name = track.relativePath.substringAfterLast('/')
            val prefixes = listOf(name + PART_SUFFIX, name + ASIDE_SUFFIX)
            listing(dir).filterKeys { n -> prefixes.any { n.startsWith(it) } }.values.forEach {
                try { it.delete() } catch (ignored: Exception) { /* best-effort: a new part is made anyway */ }
            }
        }
    }

    /** Returns the actual number of bytes written, for the caller to ack
     * back to the server (see ApiClient.ack's bytesOnDevice). The download
     * goes to a part beside the track and is renamed over the track's path
     * once whole, so a file at a track's own path is always a complete one.
     * A part that doesn't make it is deleted, and the file it was to replace
     * stays at the track's path. */
    private suspend fun downloadOne(
        context: Context,
        api: ApiClient,
        root: DocumentFile,
        track: TrackRef,
        dirMutex: Mutex,
    ): Long {
        val parts = track.relativePath.split("/")
        val fileName = parts.last()
        val dir = dirMutex.withLock { mkdirs(context, root, parts.dropLast(1)) }

        val existing = dir.findFile(fileName)
        // track.size is always the *original*'s size (see TrackRef/the
        // server's get_changes) — for a transcode-flagged track the file
        // that actually lands is the server's MP3, a different size, so
        // this equality can never confirm a transcoded file is already
        // complete. Always re-fetch those rather than trust a guess.
        if (!track.transcode && existing != null && existing.length() == track.size) {
            return existing.length() // already downloaded (e.g. a previous run finished the write but the ack failed)
        }

        /** Downloads [track] into [part], retrying; returns the bytes written. */
        fun fetchInto(part: DocumentFile): Long {
            // Large FLACs can have their socket torn down mid-transfer by a
            // transient Wi-Fi blip ("Software caused connection abort", seen
            // directly in production on a 51MB file). A retry resumes via Range
            // from however many bytes this sync's earlier attempts landed,
            // rather than restarting from zero.
            //
            // Transcoded tracks can't do this: the server generates the MP3
            // live and always restarts it from scratch on a fresh request
            // (ignores Range), so trusting local partial bytes and opening in
            // append mode would silently glue a full re-encode onto stale
            // data. Always start those clean instead.
            var lastError: Exception? = null
            for (@Suppress("unused") attempt in 0..DOWNLOAD_RETRIES) {
                // A part longer than the server's file can only be wrong:
                // resuming it would never finish, so start over.
                if (!track.transcode && part.length() > track.size) truncate(context, part)
                val resumeFrom = if (track.transcode) 0L else part.length()
                if (!track.transcode && resumeFrom == track.size) return resumeFrom // a previous attempt finished
                try {
                    api.downloadFile(track.trackId, resumeFrom).use { resp -> writeBody(context, part, resp, resumeFrom) }
                    return part.length()
                } catch (e: RangeNotSatisfiableException) {
                    // What's in the part no longer matches the server's file:
                    // the next attempt starts from zero.
                    truncate(context, part)
                    lastError = e
                } catch (e: IOException) {
                    if (isNoSpace(e)) throw e // no attempt can fit it
                    lastError = e
                }
            }
            throw lastError ?: IOException(context.getString(R.string.download_failed))
        }

        val part = dir.createFile(PART_MIME, fileName + PART_SUFFIX)
            ?: throw IOException(context.getString(R.string.file_creation_failed))
        var aside: DocumentFile? = null
        try {
            val bytes = fetchInto(part)
            // A provider won't rename over a file (it picks a unique name
            // instead), so the old one moves aside, and goes only once the
            // new one is at the track's exact name.
            if (existing != null) {
                if (!renameDocument(existing, fileName + ASIDE_SUFFIX)) {
                    throw IOException(context.getString(R.string.file_rename_failed))
                }
                aside = existing
            }
            if (!renameDocument(part, fileName) || part.name != fileName) {
                throw IOException(context.getString(R.string.file_rename_failed))
            }
            try { aside?.delete() } catch (ignored: Exception) { /* the next sync deletes it */ }
            return bytes
        } catch (e: Exception) {
            try { part.delete() } catch (ignored: Exception) { /* the next sync deletes it */ }
            try { aside?.let { renameDocument(it, fileName) } } catch (ignored: Exception) { /* left aside */ }
            throw e
        }
    }

    /** Writes one download's body into [file]: appended when the server
     * resumed it (206), from the start otherwise. */
    private fun writeBody(context: Context, file: DocumentFile, resp: okhttp3.Response, resumeFrom: Long) {
        val input = resp.body?.byteStream() ?: throw IOException(context.getString(R.string.empty_response))
        // #29: only append when the server actually returned 206
        // Partial Content. We ask for a range only on a resume
        // (resumeFrom > 0), but if a full 200 comes back anyway — a
        // transcoded stream that ignores Range, or a proxy that
        // dropped the header — appending its complete body onto the
        // partial bytes already on disk would corrupt the file, so
        // truncate instead. Belt-and-suspenders beyond the
        // transcode-flag gate in downloadOne.
        val append = resumeFrom > 0 && resp.code == HTTP_PARTIAL_CONTENT
        val mode = if (append) "wa" else "w"
        val copied = openOutput(context, file.uri, mode)?.use { output ->
            input.copyTo(output)
        } ?: throw IOException(context.getString(R.string.write_stream_unavailable))
        // A success with nothing in it isn't a file: try again,
        // rather than ack an empty track as downloaded.
        if (copied == 0L) throw IOException(context.getString(R.string.empty_response))
    }

    /** The counters the parallel downloads share. */
    private class DownloadTally(
        val downloaded: AtomicInteger, val failed: AtomicInteger,
        val firstError: AtomicReference<String?>, val diskFull: java.util.concurrent.atomic.AtomicBoolean,
        val changed: java.util.concurrent.ConcurrentLinkedQueue<String>,
        val failures: java.util.concurrent.ConcurrentLinkedQueue<SyncFailure>,
    )

    /** One track: downloaded and acked, or counted as failed. Once the
     * folder is full, every later download would fail the same way, so
     * they aren't tried, and the sync says it filled up rather than naming
     * the first track that didn't fit. */
    private suspend fun downloadAndAck(
        context: Context, api: ApiClient, root: DocumentFile, track: TrackRef, dirMutex: Mutex,
        tally: DownloadTally,
    ) {
        if (tally.diskFull.get()) return
        try {
            val bytesOnDevice = downloadOne(context, api, root, track, dirMutex)
            api.ack(track.trackId, "downloaded", bytesOnDevice)
            tally.downloaded.incrementAndGet()
            tally.changed.add(track.relativePath)
        } catch (e: Exception) {
            if (isNoSpace(e)) {
                if (tally.diskFull.compareAndSet(false, true)) {
                    tally.firstError.set(context.getString(R.string.sync_folder_full))
                }
            } else {
                tally.failed.incrementAndGet()
                tally.firstError.compareAndSet(null, context.getString(R.string.track_error, track.relativePath, e.message))
                tally.failures.add(SyncFailure(track.relativePath, failureReason(context, e)))
            }
        }
    }

    /** The folder's free space; a test sets its own. Null: not known. */
    internal var freeSpaceOf: (Context, Uri) -> Long? = { c, u -> storageStatsForTree(c, u)?.freeBytes }

    /** The space a sync must leave free in the folder's volume (see
     * spaceReserve); a test sets its own. 0 when the volume isn't known. */
    internal var reserveOf: (Context, Uri) -> Long = { c, u ->
        storageStatsForTree(c, u)?.let { spaceReserve(it.totalBytes) } ?: 0L
    }

    /** How a download's files are renamed; a test can make it fail, or land
     * under another name as a provider does when the name is taken. */
    internal var renameDocument: (DocumentFile, String) -> Boolean = { f, name -> f.renameTo(name) }

    /** How a download opens its file for writing; a test can make it fail. */
    internal var openOutput: (Context, Uri, String) -> java.io.OutputStream? =
        { c, u, mode -> c.contentResolver.openOutputStream(u, mode) }

    /** The sentence for a folder that can't hold this sync's downloads, or
     * null when it can (or its free space isn't known). What's needed is the
     * untranscoded tracks' sizes, less those already whole on disk; a file
     * of another size at a track's path stays until its replacement is
     * whole, so it frees nothing in time. What
     * this sync's deletions free doesn't count: they run after the downloads,
     * so that space isn't there yet when the downloads need it (and, refused
     * or not, the deletions still run, so the next sync sees it). A
     * transcoded track counts at the server's estimate, a little high by
     * design: its final size isn't known until written, and a server too
     * old to send an estimate leaves it at nothing, as before. The
     * downloads must also leave the volume's reserve free. */
    private fun spaceShortfall(
        context: Context, treeUri: Uri, changes: Changes, onDisk: (String) -> Long,
    ): String? {
        val needed = neededBytes(changes, onDisk)
        if (needed <= 0) return null
        val free = freeSpaceOf(context, treeUri) ?: return null
        val reserve = reserveOf(context, treeUri)
        if (needed + reserve <= free) return null
        return context.getString(R.string.sync_not_enough_space, gigabytes(needed, up = true),
            gigabytes(free, up = false), gigabytes(reserve, up = true))
    }

    /** [bytes] in gigabytes to one decimal, in the app's language; rounded
     * up for what's needed and down for what's free, so a near miss never
     * reads as if it fit. */
    internal fun gigabytes(bytes: Long, up: Boolean): String {
        val tenths = bytes / 1e8
        val rounded = (if (up) kotlin.math.ceil(tenths) else kotlin.math.floor(tenths)) / 10
        return String.format(java.util.Locale.getDefault(), "%.1f", rounded)
    }

    /** "No space left on device" anywhere in [e]'s causes. */
    internal fun isNoSpace(e: Throwable): Boolean = generateSequence(e) { it.cause }.any {
        (it is android.system.ErrnoException && it.errno == android.system.OsConstants.ENOSPC) ||
            it.message?.contains("ENOSPC") == true || it.message?.contains("No space left on device") == true
    }

    /** Empties [file] in place, so the next attempt downloads it whole. */
    private fun truncate(context: Context, file: DocumentFile) {
        context.contentResolver.openOutputStream(file.uri, "wt")?.close()
    }

    private fun deleteOne(root: DocumentFile, relativePath: String) {
        findLocalFile(root, relativePath)?.delete()
    }

    /** Walks the SAF tree for one relative path, or null if any segment
     * (folder or file) along the way is missing — shared by deleteOne and
     * the missing-file check, which only cares whether this
     * returns null or not. Internal (not private) so SyncEngineTest can
     * exercise this walk directly against a DocumentFile.fromFile() root,
     * without needing a real SAF tree grant. */
    internal fun findLocalFile(root: DocumentFile, relativePath: String): DocumentFile? {
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
    } catch (ignored: Exception) { null }

    /** Returns the names of the playlist files it wrote or removed. */
    internal fun writePlaylists(context: Context, root: DocumentFile, playlists: List<PlaylistRef>): List<String> {
        val changed = mutableListOf<String>()
        val expected = playlists.map { it.filename }.toSet()
        val rootFiles = root.listFiles().associateBy { it.name ?: "" }
        for (pl in playlists) {
            val existing = rootFiles[pl.filename]
            if (existing != null) {
                val current = try {
                    context.contentResolver.openInputStream(existing.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                } catch (ignored: Exception) { null }
                if (current == pl.content) continue
                // A playlist the person made, under the same name: theirs
                // stays, and the Trobar one isn't written this sync.
                if (current != null && parseDevicePlaylist(current.toByteArray(Charsets.UTF_8)) != null) continue
                existing.delete()
            }
            // application/octet-stream: ExternalStorageProvider keeps the
            // display name untouched for it — an audio/x-mpegurl mime would
            // make SAF append a second ".m3u" extension.
            val f = root.createFile("application/octet-stream", pl.filename)
                ?: throw IOException(context.getString(R.string.file_creation_failed))
            context.contentResolver.openOutputStream(f.uri)?.use { it.write(pl.content.toByteArray(Charsets.UTF_8)) }
            changed.add(pl.filename)
        }
        for (f in root.listFiles()) {
            val name = f.name ?: continue
            if (!name.endsWith(".m3u8", ignoreCase = true) || name in expected) continue
            if (readHead(context, f)?.contains(M3U_MARKER) == true && f.delete()) changed.add(name)
        }
        return changed
    }
}
