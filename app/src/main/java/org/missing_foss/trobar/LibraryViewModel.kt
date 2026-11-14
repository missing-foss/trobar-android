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
import kotlinx.coroutines.flow.first
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
    /** The album on the open artist page to scroll to, until it has. */
    val scrollToAlbum: String? = null,
    val albums: List<LibraryAlbum> = emptyList(),
    val albumsLoading: Boolean = false,
    val similar: List<String> = emptyList(),
    val coverGrid: Boolean = false,
    val showReissueYear: Boolean = false,
    val thisDeviceId: Long? = null,
    val basket: List<BasketItem> = emptyList(),
    val destination: AppDevice? = null,
    /** The album open on its own page, of [selectedArtist]. */
    val openAlbum: String? = null,
    val albumTracks: List<AlbumTrack> = emptyList(),
    val albumTracksLoading: Boolean = false,
    val editablePlaylists: List<AppPlaylist> = emptyList(),
    /** The title of the playlist a track just went on, until it is said. */
    val addedTo: String? = null,
    val isAdmin: Boolean = false,
    /** When the list on show was read, if it is the saved one: the server
     * didn't answer. */
    val offlineSince: Long? = null,
    /** Albums by others with [selectedArtist] on them; empty below level 6. */
    val appearsOn: List<AppearsOnAlbum> = emptyList(),
    /** The [appearsOn] row [openAlbum] was tapped on, if it was. */
    val tappedAppearsOn: AppearsOnAlbum? = null,
) {
    /** The row of [appearsOn] that [openAlbum] is. Tapped there, or sent from
     * another tab: Home's cards group by track artist, so a guest's card
     * names the guest and an album that isn't one of their own. */
    val openAppearsOn: AppearsOnAlbum?
        get() = tappedAppearsOn ?: appearsOn.firstOrNull { it.album == openAlbum }
            .takeIf { !albumsLoading && albums.none { it.album == openAlbum } }

    /** Whose album [openAlbum] is, which the appears-on case makes someone else's. */
    val openAlbumArtist: String? get() = openAppearsOn?.albumArtist ?: selectedArtist

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
class LibraryViewModel(
    private val api: AppApiClient,
    private val playlists: AppApiPlaylistClient,
    private val shared: AppState,
    private val cache: LibraryCache,
    private val appApiLevel: suspend () -> Int,
) : ViewModel() {
    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state
    private val stager = BasketStager(api, shared) { failure -> _state.update { it.copy(error = failure) } }

    init {
        // The basket and the header's device are the shared holder's,
        // mirrored one way: a stage on Home shows here too.
        viewModelScope.launch {
            shared.state.collect { sh ->
                _state.update {
                    it.copy(thisDeviceId = sh.thisDeviceId, isAdmin = sh.isAdmin, basket = sh.basket.orEmpty(), destination = sh.destination)
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
                val byAlbumArtist = byAlbumArtist()
                val (artists, view, failure) = withContext(Dispatchers.IO) {
                    coroutineScope {
                        val a = async { if (byAlbumArtist) api.albumArtists() else api.libraryArtists() }
                        val v = async { api.profileView() }
                        val f = async { if (fresh) shared.reload() else shared.ensureLoaded() }
                        Triple(a.await(), v.await(), f.await())
                    }
                }
                withContext(Dispatchers.IO) { cache.write(artists, System.currentTimeMillis()) }
                _state.update {
                    it.copy(loading = false, artists = artists, error = failure, offlineSince = null,
                            coverGrid = view.coverViewMode == "grid", showReissueYear = view.showReissueYear)
                }
            } catch (e: UnauthorizedException) {
                // Only a server that can't be reached falls back. One that answered
                // with an error, a refused token above all, says so as it always did.
                _state.update { it.copy(loading = false, error = e.message) }
            } catch (e: ServerErrorException) {
                _state.update { it.copy(loading = false, error = e.message) }
            } catch (e: IOException) {
                val saved = withContext(Dispatchers.IO) { cache.read() }
                _state.update {
                    if (saved != null) {
                        it.copy(loading = false, artists = saved.artists, offlineSince = saved.readAt)
                    } else {
                        it.copy(loading = false, error = e.message)
                    }
                }
            } catch (e: JSONException) {
                _state.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    fun setQuery(q: String) = _state.update { it.copy(query = q) }
    fun toggleSort() = _state.update { it.copy(sort = if (it.sort == ArtistSort.NAME) ArtistSort.ALBUMS else ArtistSort.NAME) }

    /** Opens [target]'s artist page, for [AppState.openInLibrary]. */
    fun open(target: LibraryTarget) {
        selectArtist(target.artist)
        _state.update { it.copy(scrollToAlbum = target.album, openAlbum = target.album.takeIf { _ -> target.details }) }
    }

    /** Opens [album] of the selected artist on its own page. */
    fun openAlbum(album: String) = _state.update {
        it.copy(openAlbum = album, tappedAppearsOn = null, albumTracks = emptyList(), albumTracksLoading = true)
    }

    /** Opens an album the selected artist appears on, whole, with theirs marked. */
    fun openAppearsOn(album: AppearsOnAlbum) = _state.update {
        it.copy(openAlbum = album.album, tappedAppearsOn = album, albumTracks = emptyList(), albumTracksLoading = true)
    }

    fun closeAlbum() = _state.update {
        it.copy(openAlbum = null, tappedAppearsOn = null, albumTracks = emptyList(), albumTracksLoading = false)
    }

    private suspend fun byAlbumArtist() = appApiLevel() >= APP_API_ALBUM_ARTISTS

    /** Read each time it opens; an answer for an album since left is dropped. */
    fun loadAlbumTracks(artist: String, album: String) {
        _state.update { it.copy(albumTracksLoading = true) }
        viewModelScope.launch {
            val failure = guarded {
                val tracks = withContext(Dispatchers.IO) { api.albumTracks(artist, album) }
                _state.update {
                    if (it.openAlbumArtist == artist && it.openAlbum == album) it.copy(albumTracks = tracks, albumTracksLoading = false) else it
                }
            }
            // Back to the artist: an empty page would read as an album with no tracks.
            if (failure != null) {
                _state.update {
                    val here = it.openAlbumArtist == artist && it.openAlbum == album
                    it.copy(
                        albumTracksLoading = false, error = failure, openAlbum = it.openAlbum.takeUnless { _ -> here },
                        tappedAppearsOn = it.tappedAppearsOn.takeUnless { _ -> here },
                    )
                }
            }
        }
    }

    /** The playlists a track can go on: made in Trobar, and the caller's or,
     * for an admin, anyone's. */
    fun loadEditablePlaylists() {
        viewModelScope.launch {
            val failure = guarded {
                val all = withContext(Dispatchers.IO) { playlists.playlists() }
                _state.update { s ->
                    s.copy(editablePlaylists = all.filter { it.sourceProvider == "trobar" && (it.isOwn || s.isAdmin) })
                }
            }
            if (failure != null) _state.update { it.copy(error = failure) }
        }
    }

    fun addTrackToPlaylist(track: AlbumTrack, playlist: AppPlaylist) =
        add(playlist.title, { playlist.id }) { playlists.append(it, listOf(track.id)) }

    /** A new playlist is named after the track, as the browser's is. */
    fun addTrackToNewPlaylist(track: AlbumTrack) =
        add(track.title, { playlists.create(track.title) }) { playlists.append(it, listOf(track.id)) }

    fun addAlbumToPlaylist(artist: String, album: String, playlist: AppPlaylist) =
        add(playlist.title, { playlist.id }) { playlists.appendAlbum(it, artist, album) }

    /** Named after the album, likewise. */
    fun addAlbumToNewPlaylist(artist: String, album: String) =
        add(album, { playlists.create(album) }) { playlists.appendAlbum(it, artist, album) }

    private fun add(title: String, playlistId: () -> Long, append: (Long) -> Unit) {
        viewModelScope.launch {
            val failure = guarded {
                withContext(Dispatchers.IO) { append(playlistId()) }
                _state.update { it.copy(addedTo = title) }
            }
            if (failure != null) _state.update { it.copy(error = failure) }
        }
    }

    fun consumeAddedTo() = _state.update { it.copy(addedTo = null) }

    /** Runs [read], answering what went wrong; the pair load() catches. */
    private suspend fun guarded(read: suspend () -> Unit): String? = try {
        read()
        null
    } catch (e: IOException) {
        e.message.orEmpty()
    } catch (e: JSONException) {
        e.message.orEmpty()
    }

    /** The page has scrolled to [LibraryUiState.scrollToAlbum]. */
    fun scrolledToAlbum() = _state.update { it.copy(scrollToAlbum = null) }

    fun selectArtist(artist: String?) {
        _state.update {
            it.copy(
                selectedArtist = artist, albums = emptyList(), similar = emptyList(), albumsLoading = artist != null,
                scrollToAlbum = null, openAlbum = null, albumTracks = emptyList(), appearsOn = emptyList(), tappedAppearsOn = null,
            )
        }
        if (artist == null) return
        viewModelScope.launch {
            try {
                val byAlbumArtist = byAlbumArtist()
                val albums = withContext(Dispatchers.IO) {
                    if (byAlbumArtist) api.albumArtistAlbums(artist) else api.libraryAlbums(artist)
                }
                _state.update { if (it.selectedArtist == artist) it.copy(albums = albums, albumsLoading = false) else it }
            } catch (e: IOException) {
                _state.update { it.copy(albumsLoading = false, error = e.message) }
            } catch (e: JSONException) {
                // The same pair load() catches: the answer is parsed here too.
                _state.update { it.copy(albumsLoading = false, error = e.message) }
            }
        }
        viewModelScope.launch {
            // Its own read: the artist's page is whole without it.
            val appearsOn = try {
                if (byAlbumArtist()) withContext(Dispatchers.IO) { api.appearsOn(artist) } else emptyList()
            } catch (ignored: IOException) {
                emptyList()
            } catch (ignored: JSONException) {
                emptyList()
            }
            _state.update { if (it.selectedArtist == artist) it.copy(appearsOn = appearsOn) else it }
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
            initializer {
                val app = context.applicationContext
                LibraryViewModel(
                    AppApiClient(app, pairing.serverUrl, pairing.token),
                    AppApiPlaylistClient(app, pairing.serverUrl, pairing.token),
                    shared,
                    LibraryCache(app, pairing.serverUrl),
                    appApiLevel = { Prefs.appApi(app).first() ?: 0 },
                )
            }
        }
    }
}
