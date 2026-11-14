// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.IOException
import kotlinx.coroutines.flow.first

data class PlaylistsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val playlists: List<AppPlaylist> = emptyList(),
    val syncing: Boolean = false,
    val openId: Long? = null,
    val entries: List<PlaylistEntry> = emptyList(),
    val entriesLoading: Boolean = false,
    /** The open playlist's unmatched entries under review, or null when the
     * review is closed. */
    val review: Review? = null,
    val lidarrBusy: Boolean = false,
    val sharingBusy: Boolean = false,
    /** Whether this server takes playlist writes at all. */
    val canWrite: Boolean = false,
    val isAdmin: Boolean = false,
    /** A write was refused because the playlist changed elsewhere. */
    val changedElsewhere: Boolean = false,
    val sort: PlaylistSort = PlaylistSort.AVAILABILITY,
) {
    /** Whether the open playlist can be edited here: made in Trobar, and the
     * caller's or, for an admin, anyone's. */
    val canEditOpen: Boolean
        get() = open?.let { canWrite && it.sourceProvider == "trobar" && (it.isOwn || isAdmin) } == true

    /** The playlist open, while the list still has it. */
    val open: AppPlaylist? get() = playlists.firstOrNull { it.id == openId }
}

/** A removal with its Undo still open. */
internal class HeldRemoval(val editor: PlaylistEntryEditor, val entry: PlaylistEntry, val index: Int)

/** The unmatched entries of the open playlist, as the browser's review
 * panel has them: loaded, or failed ([failed] says so in the browser's
 * words), and [busy] while an exclusion is on its way. */
data class Review(
    val rows: List<UnresolvedEntry> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false,
    val busy: Boolean = false,
)

/** Where the Playlists tab's order is kept between starts. */
class SortStore(
    val load: suspend () -> PlaylistSort? = { null },
    val save: suspend (PlaylistSort) -> Unit = {},
) {
    companion object {
        /** The phone's preferences. An unknown name, from a later version, reads as none. */
        fun prefs(context: Context) = SortStore(
            load = { Prefs.playlistSort(context).first()?.let { name -> PlaylistSort.entries.firstOrNull { it.name == name } } },
            save = { Prefs.setPlaylistSort(context, it.name) },
        )
    }
}

/** The list's orders, as the browser offers them. Ties go by name. */
enum class PlaylistSort(val label: Int) {
    AVAILABILITY(R.string.playlists_sort_availability),
    MOST_TRACKS(R.string.playlists_sort_most_tracks),
    NEWEST(R.string.playlists_sort_newest),
    NAME(R.string.playlists_sort_name),
}

private val byName: Comparator<AppPlaylist> = Comparator { a, b ->
    java.text.Collator.getInstance().apply { strength = java.text.Collator.PRIMARY }.compare(a.title, b.title)
}

internal fun sorted(playlists: List<AppPlaylist>, sort: PlaylistSort = PlaylistSort.AVAILABILITY): List<AppPlaylist> = playlists.sortedWith(
    when (sort) {
        PlaylistSort.AVAILABILITY -> compareByDescending<AppPlaylist> { it.availabilityPct() }.then(byName)
        PlaylistSort.MOST_TRACKS -> compareByDescending<AppPlaylist> { it.trackCount }.then(byName)
        PlaylistSort.NEWEST -> compareByDescending { it.id }
        PlaylistSort.NAME -> byName
    },
)

/** The share of a playlist's tracks found in the library, in whole
 * percent, as the browser rounds it; an empty playlist is 0. */
fun AppPlaylist.availabilityPct(): Int = if (trackCount == 0) 0 else Math.round(PERCENT * matchedCount / trackCount)

private const val PERCENT = 100f

/** The Playlists tab's state: the playlists the owner may see, most
 * available first as in the browser, one playlist's entries, the provider
 * sync -- started here, and followed while it runs, with the list reread
 * as playlists arrive -- and the stage of a playlist for the header's
 * device. */
class PlaylistsViewModel(
    private val api: AppApiPlaylistClient,
    app: AppApiClient,
    private val shared: AppState,
    private val appApiLevel: suspend () -> Int = { 0 },
    /** The order chosen last time, and where a new choice is kept: the
     * phone's preferences in the app, nothing in a test that passes none. */
    private val sortStore: SortStore = SortStore(),
) : ViewModel() {
    private val _state = MutableStateFlow(PlaylistsUiState())
    val state: StateFlow<PlaylistsUiState> = _state
    private val stager = BasketStager(app, shared) { failure -> _state.update { it.copy(error = failure) } }
    private var follow: Job? = null
    private var editor: PlaylistEntryEditor? = null
    private val editorHost = object : EntryEditorHost {
        override fun show(playlistId: Long, entries: List<PlaylistEntry>) =
            _state.update { if (it.openId == playlistId) it.copy(entries = entries) else it }

        override fun changedElsewhere(playlistId: Long) =
            _state.update { if (it.openId == playlistId) it.copy(changedElsewhere = true) else it }

        override fun failed(message: String?) = _state.update { it.copy(error = message) }

        override suspend fun saved() = refreshList()
    }

    // Before init: load() goes through it.
    val sorting = Sorting()

    init {
        viewModelScope.launch {
            val canWrite = appApiLevel() >= APP_API_PLAYLIST_WRITES
            _state.update { it.copy(canWrite = canWrite) }
            shared.state.collect { sh -> _state.update { it.copy(isAdmin = sh.isAdmin) } }
        }
        load()
    }

    /** The list's order: the one chosen last time at the start, and a new
     * choice applied at once and kept for the next start. */
    inner class Sorting {
        init {
            viewModelScope.launch {
                sortStore.load()?.let { sort -> _state.update { it.copy(sort = sort, playlists = sorted(it.playlists, sort)) } }
            }
        }

        /** A list just read, in the current order: every replacement of the
         * list goes through here, so none can jump back to another order. */
        fun order(playlists: List<AppPlaylist>): List<AppPlaylist> = sorted(playlists, _state.value.sort)

        fun by(sort: PlaylistSort) {
            _state.update { it.copy(sort = sort, playlists = sorted(it.playlists, sort)) }
            viewModelScope.launch { sortStore.save(sort) }
        }
    }


    /** Reads the list and whether a sync runs; one already running (started
     * from the browser, say) is followed as if started here. */
    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val (playlists, status) = withContext(Dispatchers.IO) {
                    coroutineScope {
                        val p = async { api.playlists() }
                        val s = async { api.syncStatus() }
                        p.await() to s.await()
                    }
                }
                _state.update { it.copy(loading = false, playlists = sorting.order(playlists)) }
                if (status.running) followSync()
            } catch (e: IOException) {
                _state.update { it.copy(loading = false, error = e.message) }
            } catch (e: JSONException) {
                _state.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    /** The tab shown again: the list is read again without the spinner, so a
     * playlist a subscription made or took away shows. Nothing while the
     * first load is still on its way. */
    fun reenter() {
        if (_state.value.loading) return
        viewModelScope.launch {
            try {
                val playlists = withContext(Dispatchers.IO) { api.playlists() }
                _state.update { it.copy(playlists = sorting.order(playlists)) }
                // The Library adds to the open playlist, and any write rewrites its ids.
                editor?.reread()
            } catch (e: IOException) {
                _state.update { it.copy(error = e.message) }
            } catch (e: JSONException) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    /** Starts a provider sync. A refusal because one already runs is not a
     * failure to show: the one running is followed instead. */
    fun startSync() {
        if (_state.value.syncing) return
        _state.update { it.copy(syncing = true) }
        viewModelScope.launch {
            val refused = try {
                withContext(Dispatchers.IO) { api.startSync() }
                null
            } catch (e: IOException) {
                e.message ?: e.toString()
            }
            followSync(refused)
        }
    }

    /** Polls the sync until it stops, rereading the list each time. [refused]
     * is the start's failure: shown only if no sync turns out to be running. */
    private fun followSync(refused: String? = null) {
        if (follow?.isActive == true) return
        _state.update { it.copy(syncing = true) }
        follow = viewModelScope.launch {
            var first = true
            while (true) {
                val running = try {
                    val (status, playlists) = withContext(Dispatchers.IO) { api.syncStatus() to api.playlists() }
                    _state.update { it.copy(playlists = sorting.order(playlists)) }
                    status.running
                } catch (e: IOException) {
                    _state.update { it.copy(error = e.message) }
                    false
                } catch (e: JSONException) {
                    _state.update { it.copy(error = e.message) }
                    false
                }
                if (!running) {
                    if (first && refused != null) _state.update { it.copy(error = refused) }
                    break
                }
                first = false
                delay(POLL_MILLIS)
            }
            _state.update { it.copy(syncing = false) }
        }
    }

    /** The last one left, whose write a read may still have to wait for: going
     * through the list between two playlists leaves no [editor] to wait on. */
    private var departing: PlaylistEntryEditor? = null

    fun open(id: Long?) {
        // Leaving sends what the Undo was holding: it can't be lost by moving on.
        val leaving = editor ?: departing
        leaving?.send()
        departing = leaving
        editor = null
        _state.update { it.copy(openId = id, entries = emptyList(), entriesLoading = id != null, review = null) }
        if (id == null) return
        viewModelScope.launch {
            try {
                // After any write of the one left, which may be this same playlist.
                leaving?.idle()
                val entries = withContext(Dispatchers.IO) { api.entries(id) }
                if (_state.value.openId == id) editor = PlaylistEntryEditor(id, entries, api, viewModelScope, editorHost)
                _state.update { if (it.openId == id) it.copy(entries = entries, entriesLoading = false) else it }
            } catch (e: IOException) {
                _state.update { if (it.openId == id) it.copy(entriesLoading = false, error = e.message) else it }
            } catch (e: JSONException) {
                _state.update { if (it.openId == id) it.copy(entriesLoading = false, error = e.message) else it }
            }
        }
    }

    fun consumeError() = _state.update { it.copy(error = null, changedElsewhere = false) }

    /** Makes a playlist and opens it. */
    fun create(title: String) = attemptWrite {
        val id = withContext(Dispatchers.IO) { api.create(title) }
        val playlists = withContext(Dispatchers.IO) { api.playlists() }
        _state.update { it.copy(playlists = sorting.order(playlists)) }
        open(id)
    }

    fun rename(title: String) {
        val id = _state.value.openId ?: return
        attemptWrite {
            withContext(Dispatchers.IO) { api.rename(id, title) }
            refreshList()
        }
    }

    fun delete() {
        val id = _state.value.openId ?: return
        val going = editor
        attemptWrite {
            // Nothing goes out meanwhile; if the server says no, nothing is lost.
            going?.pause()
            going?.idle()
            try {
                withContext(Dispatchers.IO) { api.delete(id) }
            } finally {
                going?.resume()
            }
            if (editor === going) editor = null
            open(null)
            refreshList()
        }
    }

    private suspend fun refreshList() {
        val playlists = withContext(Dispatchers.IO) { api.playlists() }
        _state.update { it.copy(playlists = sorting.order(playlists)) }
    }

    private fun attemptWrite(write: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                write()
            } catch (e: IOException) {
                _state.update { it.copy(error = e.message) }
            } catch (e: JSONException) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    /** Moves an entry and writes the new order at once, and with it any
     * removal still held: there is one list on the server. */
    fun moveEntry(from: Int, to: Int) {
        editor?.move(from, to)
    }

    /** Takes [entry] off the list on screen; [commitEdits] sends it, once
     * its Undo has run out. Answers what the Undo needs. */
    internal fun removeEntry(entry: PlaylistEntry): HeldRemoval? {
        val held = editor ?: return null
        val index = held.indexOf(entry)
        held.remove(entry)
        return HeldRemoval(held, entry, index)
    }

    /** Puts a held removal back, in the playlist it came from and only while
     * that one is open: it has already gone out otherwise. */
    internal fun undoRemove(held: HeldRemoval) {
        if (held.editor === editor) held.editor.restore(held.entry, held.index)
    }

    fun commitEdits() {
        editor?.send()
    }

    /** Opens the review of the open playlist's unmatched entries. */
    fun openReview() {
        val id = _state.value.openId ?: return
        _state.update { it.copy(review = Review()) }
        viewModelScope.launch {
            val rows = try {
                withContext(Dispatchers.IO) { api.unresolved(id) }
            } catch (e: IOException) {
                Log.w(TAG, "unresolved entries failed to load", e)
                null
            } catch (e: JSONException) {
                Log.w(TAG, "unresolved entries answered with something unexpected", e)
                null
            }
            _state.update { s ->
                if (s.openId != id || s.review == null) s
                else s.copy(review = if (rows != null) Review(rows, loading = false) else Review(loading = false, failed = true))
            }
        }
    }

    fun closeReview() = _state.update { it.copy(review = null) }

    /** Marks entries as expected gaps, or not any more. The rows and the
     * playlist's count change only once the server has the write -- never
     * ahead of it, as in the browser. */
    fun setExcluded(ids: List<Long>, excluded: Boolean) {
        val id = _state.value.openId ?: return
        val review = _state.value.review ?: return
        if (review.busy || ids.isEmpty()) return
        _state.update { it.copy(review = review.copy(busy = true, failed = false)) }
        viewModelScope.launch {
            val count = try {
                withContext(Dispatchers.IO) { api.setExcluded(id, ids, excluded) }
            } catch (e: IOException) {
                Log.w(TAG, "exclusion failed", e)
                null
            } catch (e: JSONException) {
                Log.w(TAG, "exclusion answered with something unexpected", e)
                null
            }
            _state.update { s ->
                val r = s.review ?: return@update s
                if (count == null) {
                    s.copy(review = r.copy(busy = false, failed = true))
                } else {
                    s.copy(
                        review = r.copy(busy = false, rows = r.rows.map { if (it.id in ids) it.copy(excluded = excluded) else it }),
                        playlists = s.playlists.map { if (it.id == id) it.copy(unresolvedCount = count) else it },
                    )
                }
            }
        }
    }

    /** Turns the open playlist's Lidarr requests on or off. On, the server
     * requests the missing albums before it answers; the toggle and its run
     * are what it answers with. */
    /** Shares the open playlist with the household, or makes it private
     * again -- the one field of a playlist the owner may change, and only
     * where the server says they may. The list is read again after, so the
     * mark and every other screen's view follow the server. */
    fun toggleShared() {
        val p = _state.value.open ?: return
        if (!p.canShare || _state.value.sharingBusy) return
        _state.update { it.copy(sharingBusy = true) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { api.setShared(p.id, !p.shared) }
                val playlists = withContext(Dispatchers.IO) { api.playlists() }
                _state.update { it.copy(playlists = sorting.order(playlists)) }
            } catch (e: IOException) {
                _state.update { it.copy(error = e.message) }
            } catch (e: JSONException) {
                _state.update { it.copy(error = e.message) }
            }
            _state.update { it.copy(sharingBusy = false) }
        }
    }

    fun toggleLidarr() {
        val p = _state.value.open ?: return
        if (_state.value.lidarrBusy) return
        _state.update { it.copy(lidarrBusy = true) }
        viewModelScope.launch {
            try {
                val run = withContext(Dispatchers.IO) { api.setLidarrRequests(p.id, !p.lidarr.run.enabled) }
                _state.update { s ->
                    s.copy(playlists = s.playlists.map { if (it.id == p.id) it.copy(lidarr = it.lidarr.with(run)) else it })
                }
            } catch (e: IOException) {
                _state.update { it.copy(error = e.message) }
            } catch (e: JSONException) {
                _state.update { it.copy(error = e.message) }
            }
            _state.update { it.copy(lidarrBusy = false) }
        }
    }

    /** Stages a playlist for the header's device -- the server's type
     * "playlist", with its id as the target; answers at once, the write
     * goes on behind. */
    fun stage(id: Long): StageStart = stager.stage("playlist", id.toString())

    /** Stages one library track for the header's device, the same way. */
    fun stageTrack(trackId: Long): StageStart = stager.stage("track", trackId.toString())

    /** Whether this target is already staged for the header's device. */
    fun stagedHere(type: String, target: String): Boolean = shared.state.value.staging.stagedHere(type, target)

    /** Opens an entry's artist page in the Library, at [album] if set. */
    fun openInLibrary(artist: String, album: String? = null) = shared.openInLibrary(LibraryTarget(artist, album))

    /** The browser's order: most available first, ties in the server's
     * order (the sort is stable, as the browser's is). */
    companion object {
        /** As often as the browser asks. */
        private const val POLL_MILLIS = 2_000L
        private const val TAG = "Playlists"

        fun factory(context: Context, pairing: Prefs.Pairing, shared: AppState): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = context.applicationContext
                PlaylistsViewModel(
                    AppApiPlaylistClient(app, pairing.serverUrl, pairing.token),
                    AppApiClient(app, pairing.serverUrl, pairing.token),
                    shared,
                    appApiLevel = { Prefs.appApi(app).first() ?: 0 },
                    sortStore = SortStore.prefs(app),
                )
            }
        }
    }
}
