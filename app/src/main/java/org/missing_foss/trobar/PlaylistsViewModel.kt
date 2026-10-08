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
) {
    /** The playlist open, while the list still has it. */
    val open: AppPlaylist? get() = playlists.firstOrNull { it.id == openId }
}

/** The unmatched entries of the open playlist, as the browser's review
 * panel has them: loaded, or failed ([failed] says so in the browser's
 * words), and [busy] while an exclusion is on its way. */
data class Review(
    val rows: List<UnresolvedEntry> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false,
    val busy: Boolean = false,
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
class PlaylistsViewModel(private val api: AppApiPlaylistClient, app: AppApiClient, shared: AppState) : ViewModel() {
    private val _state = MutableStateFlow(PlaylistsUiState())
    val state: StateFlow<PlaylistsUiState> = _state
    private val stager = BasketStager(app, shared) { failure -> _state.update { it.copy(error = failure) } }
    private var follow: Job? = null

    init {
        load()
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
                _state.update { it.copy(loading = false, playlists = sorted(playlists)) }
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
                _state.update { it.copy(playlists = sorted(playlists)) }
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
                    _state.update { it.copy(playlists = sorted(playlists)) }
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

    fun open(id: Long?) {
        _state.update { it.copy(openId = id, entries = emptyList(), entriesLoading = id != null, review = null) }
        if (id == null) return
        viewModelScope.launch {
            try {
                val entries = withContext(Dispatchers.IO) { api.entries(id) }
                _state.update { if (it.openId == id) it.copy(entries = entries, entriesLoading = false) else it }
            } catch (e: IOException) {
                _state.update { if (it.openId == id) it.copy(entriesLoading = false, error = e.message) else it }
            } catch (e: JSONException) {
                _state.update { if (it.openId == id) it.copy(entriesLoading = false, error = e.message) else it }
            }
        }
    }

    fun consumeError() = _state.update { it.copy(error = null) }

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
                _state.update { it.copy(playlists = sorted(playlists)) }
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

    /** The browser's order: most available first, ties in the server's
     * order (the sort is stable, as the browser's is). */
    private fun sorted(playlists: List<AppPlaylist>) = playlists.sortedByDescending { it.availabilityPct() }

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
                )
            }
        }
    }
}
