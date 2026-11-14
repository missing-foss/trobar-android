// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException

private const val HTTP_BAD_REQUEST = 400

/** What the screen hears from an editor. */
internal interface EntryEditorHost {
    fun show(playlistId: Long, entries: List<PlaylistEntry>)
    fun changedElsewhere(playlistId: Long)
    fun failed(message: String?)
    suspend fun saved()
}

/**
 * One playlist's entries while they are edited, and the only thing that
 * writes them. It holds its own list, so what is sent can't depend on what
 * the screen shows now; that list changes only by the user's edits and the
 * server's answers, so an empty one is one the user emptied.
 *
 * Every write rewrites every entry id. One write runs at a time, and each
 * answer is matched to what was sent, position by position, then carried
 * over to the list as it is by then: an edit made while a write was on its
 * way stays, and the next write sends it with the fresh ids. There is
 * something to send when the list differs from what the server holds, so
 * undoing a removal that wasn't sent sends nothing.
 *
 * Runs on the main thread, as a ViewModel's scope does, so nothing here needs
 * a lock; only the network goes to another thread.
 */
internal class PlaylistEntryEditor(
    private val playlistId: Long,
    loaded: List<PlaylistEntry>,
    private val api: AppApiPlaylistClient,
    private val scope: CoroutineScope,
    private val host: EntryEditorHost,
) {
    private var entries = loaded
    private var onServer = loaded.ids()
    private val renamed = mutableMapOf<Long, Long>()
    private var wanted = false
    private var writer: Job? = null
    private var paused = false

    private fun List<PlaylistEntry>.ids() = mapNotNull { it.entryId }

    fun indexOf(entry: PlaylistEntry): Int = entries.indexOfFirst { it.entryId == current(entry.entryId) }

    /** The id an entry goes by now, after however many writes. */
    private fun current(id: Long?): Long? {
        var at = id
        while (at != null && at in renamed) at = renamed[at]
        return at
    }

    private fun edit(next: List<PlaylistEntry>) {
        entries = next
        host.show(playlistId, entries)
    }

    /** Off the list at once; on the server when [send] says so. */
    fun remove(entry: PlaylistEntry) = edit(entries.filter { it.entryId != current(entry.entryId) })

    fun restore(entry: PlaylistEntry, index: Int) {
        val back = entry.copy(entryId = current(entry.entryId))
        edit(entries.toMutableList().apply { add(index.coerceIn(0, size), back) })
    }

    fun move(from: Int, to: Int) {
        if (from !in entries.indices || to !in entries.indices) return
        edit(entries.toMutableList().apply { add(to, removeAt(from)) })
        send()
    }

    /** Sends the list as it stands, if it needs it. */
    fun send() {
        if (paused || entries.ids() == onServer) return
        wanted = true
        if (writer?.isActive != true) writer = scope.launch { writeWhileWanted() }
    }

    /** Waits for any write, so that a read after this sees it. */
    suspend fun idle() {
        writer?.join()
    }

    /** Until [resume], nothing is sent: the playlist may be about to go. */
    fun pause() {
        paused = true
        wanted = false
    }

    fun resume() {
        paused = false
    }

    /** Reads the list again, because something other than this editor may
     * have written it: every write rewrites the ids. Not while an edit is
     * held or on its way by the time the answer comes: those bring fresh ids of their own. */
    suspend fun reread() {
        writer?.join()
        val latest = withContext(Dispatchers.IO) { api.entries(playlistId) }
        if (paused || entries.ids() != onServer) return
        entries = latest
        onServer = latest.ids()
        host.show(playlistId, latest)
    }

    private suspend fun writeWhileWanted() {
        while (wanted) {
            wanted = false
            val sent = entries.ids()
            if (sent == onServer) continue
            try {
                val fresh = withContext(Dispatchers.IO) { api.replaceEntries(playlistId, sent) }
                carryOver(sent, fresh)
                host.saved()
            } catch (e: ServerErrorException) {
                if (e.code == HTTP_BAD_REQUEST) reload() else host.failed(e.message)
            } catch (e: IOException) {
                host.failed(e.message)
            } catch (e: JSONException) {
                host.failed(e.message)
            }
        }
    }

    /** The answer is the list as sent, in order, with new ids. */
    private fun carryOver(sent: List<Long>, fresh: List<PlaylistEntry>) {
        val byOldId = sent.zip(fresh).toMap()
        sent.zip(fresh).forEach { (old, entry) -> entry.entryId?.let { renamed[old] = it } }
        onServer = fresh.ids()
        edit(entries.map { e -> byOldId[e.entryId] ?: e })
    }

    /** Refused: the list changed elsewhere, so what is here is stale. */
    private suspend fun reload() {
        try {
            val latest = withContext(Dispatchers.IO) { api.entries(playlistId) }
            entries = latest
            onServer = latest.ids()
            wanted = false
            host.show(playlistId, latest)
            host.changedElsewhere(playlistId)
        } catch (e: IOException) {
            host.failed(e.message)
        } catch (e: JSONException) {
            host.failed(e.message)
        }
    }
}
