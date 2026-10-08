// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.IOException

enum class ArtistSort { NAME, ALBUMS }

data class LibraryUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val artists: List<LibraryArtist> = emptyList(),
    val query: String = "",
    val sort: ArtistSort = ArtistSort.NAME,
    val selectedArtist: String? = null,
    val albums: List<LibraryAlbum> = emptyList(),
    val albumsLoading: Boolean = false,
    val similar: List<String> = emptyList(),
    val coverGrid: Boolean = false,
    val showReissueYear: Boolean = false,
    val thisDeviceId: Long? = null,
    val basket: List<BasketItem> = emptyList(),
    val destination: AppDevice? = null,
) {
    /** The artist list as shown: the substring search, folded as the web
     * folds it ([LibrarySearch]), on the name or any album title, then the
     * sort -- by name, or by album count with name as the tie-break, as the
     * web. */
    val visibleArtists: List<LibraryArtist>
        get() {
            val q = LibrarySearch.fold(query.trim())
            val hits = if (q.isEmpty()) {
                artists
            } else {
                artists.filter { a -> LibrarySearch.matches(q, a.artist) || a.albums.orEmpty().any { LibrarySearch.matches(q, it) } }
            }
            return when (sort) {
                ArtistSort.NAME -> hits.sortedBy { it.artist.lowercase() }
                ArtistSort.ALBUMS -> hits.sortedWith(compareByDescending<LibraryArtist> { it.albumCount }.thenBy { it.artist.lowercase() })
            }
        }

    /** The album titles that put [artist] in the list when its name alone
     * did not, shown under the name so the hit makes sense; empty otherwise,
     * as the web's albumOnlyHits. */
    fun albumOnlyHits(artist: LibraryArtist): List<String> {
        val q = LibrarySearch.fold(query.trim())
        if (q.isEmpty() || LibrarySearch.matches(q, artist.artist)) return emptyList()
        return artist.albums.orEmpty().filter { LibrarySearch.matches(q, it) }
    }

    val staging: StagingContext get() = StagingContext(thisDeviceId, basket, destination)

    fun stagedHere(type: String, target: String): Boolean = staging.stagedHere(type, target)
}

/** The library screen's state: every artist in one response (the web's
 * contract too), one artist's albums and similar artists fetched
 * separately so neither blocks the other, and the stage of an album or a
 * whole artist to the header's device. */
class LibraryViewModel(private val api: AppApiClient, private val shared: AppState) : ViewModel() {
    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state
    private val stager = BasketStager(api, shared) { failure -> _state.update { it.copy(error = failure) } }

    init {
        // The basket and the header's device are the shared holder's,
        // mirrored one way: a stage on Home shows here too.
        viewModelScope.launch {
            shared.state.collect { sh ->
                _state.update {
                    it.copy(thisDeviceId = sh.thisDeviceId, basket = sh.basket.orEmpty(), destination = sh.destination)
                }
            }
        }
        load(fresh = false)
    }

    /** As Home's: the first load reuses the shared read, a retry reads again. */
    fun load(fresh: Boolean = true) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val (artists, view, failure) = withContext(Dispatchers.IO) {
                    coroutineScope {
                        val a = async { api.libraryArtists() }
                        val v = async { api.profileView() }
                        val f = async { if (fresh) shared.reload() else shared.ensureLoaded() }
                        Triple(a.await(), v.await(), f.await())
                    }
                }
                _state.update {
                    it.copy(loading = false, artists = artists, error = failure,
                            coverGrid = view.coverViewMode == "grid", showReissueYear = view.showReissueYear)
                }
            } catch (e: IOException) {
                _state.update { it.copy(loading = false, error = e.message) }
            } catch (e: JSONException) {
                _state.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    fun setQuery(q: String) = _state.update { it.copy(query = q) }
    fun toggleSort() = _state.update { it.copy(sort = if (it.sort == ArtistSort.NAME) ArtistSort.ALBUMS else ArtistSort.NAME) }

    fun selectArtist(artist: String?) {
        _state.update {
            it.copy(selectedArtist = artist, albums = emptyList(), similar = emptyList(), albumsLoading = artist != null)
        }
        if (artist == null) return
        viewModelScope.launch {
            try {
                val albums = withContext(Dispatchers.IO) { api.libraryAlbums(artist) }
                _state.update { if (it.selectedArtist == artist) it.copy(albums = albums, albumsLoading = false) else it }
            } catch (e: IOException) {
                _state.update { it.copy(albumsLoading = false, error = e.message) }
            } catch (e: JSONException) {
                // The same pair load() catches: the answer is parsed here too.
                _state.update { it.copy(albumsLoading = false, error = e.message) }
            }
        }
        viewModelScope.launch {
            // Separate on purpose: a slow or unconfigured similarity source
            // must never hold the album pane.
            val similar = try {
                withContext(Dispatchers.IO) { api.similarArtists(artist) }
            } catch (ignored: IOException) {
                emptyList()
            } catch (ignored: JSONException) {
                emptyList()
            }
            _state.update { if (it.selectedArtist == artist) it.copy(similar = similar) else it }
        }
    }

    /** Stages an album (`Artist||Album`) or a whole artist for the header's
     * device; answers at once, the write goes on behind. */
    fun stage(type: String, target: String): StageStart = stager.stage(type, target)

    fun consumeError() = _state.update { it.copy(error = null) }

    companion object {
        fun factory(context: Context, pairing: Prefs.Pairing, shared: AppState): ViewModelProvider.Factory = viewModelFactory {
            initializer { LibraryViewModel(AppApiClient(context.applicationContext, pairing.serverUrl, pairing.token), shared) }
        }
    }
}
