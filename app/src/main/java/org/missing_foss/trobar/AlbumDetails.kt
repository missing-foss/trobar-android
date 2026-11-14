// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** [tracks] by disc, in the server's order. More than one disc number is
 * what makes headings worth showing. */
fun albumDiscs(tracks: List<AlbumTrack>): List<AlbumDisc> {
    val discs = tracks.groupBy { it.discNo ?: 0 }
    if (discs.size <= 1) return listOf(AlbumDisc(null, tracks))
    return discs.map { (n, onDisc) -> AlbumDisc(n.takeIf { it > 0 }, onDisc) }
}

/** "4:12"; empty when the track carries no duration. */
fun trackDuration(seconds: Double?): String {
    if (seconds == null || seconds <= 0) return ""
    val whole = Math.round(seconds)
    return "${whole / SECONDS_PER_MINUTE}:${(whole % SECONDS_PER_MINUTE).toString().padStart(2, '0')}"
}

/** An album on its own page: the cover, what it is, Add to basket for the
 * header's device, and the tracklist, a track's tap offering the playlists
 * it can go on. A server without the tracks route shows no list, says why,
 * and still stages the album. */
@Composable
internal fun AlbumDetailsPane(
    state: LibraryUiState,
    vm: LibraryViewModel,
    cover: Covers,
    stage: (type: String, target: String, coverUrl: String?, from: Rect?) -> Unit,
) {
    val artist = state.selectedArtist ?: return
    val album = state.openAlbum ?: return
    val albumArtist = state.openAlbumArtist ?: return
    val context = LocalContext.current
    val appApi = remember(context) { Prefs.appApi(context) }
    val level by appApi.collectAsState(initial = null)
    val hasTracks = level.let { it == null || it >= APP_API_ALBUM_TRACKS }
    var picking by remember { mutableStateOf<AlbumTrack?>(null) }
    var pickingAlbum by remember { mutableStateOf(false) }
    var coverAt by remember { mutableStateOf<Rect?>(null) }

    LaunchedEffect(albumArtist, album, hasTracks) { if (hasTracks) vm.loadAlbumTracks(albumArtist, album) }

    LazyColumn(Modifier.fillMaxSize().testTag("album_details")) {
        item {
            val addToPlaylist = { pickingAlbum = true }.takeIf { hasTracks }
            AlbumHeader(state, artist, album, cover, AlbumHeaderActions(stage, coverAt, addToPlaylist) { coverAt = it })
        }
        when {
            !hasTracks -> item {
                Text(
                    stringResource(R.string.album_update_server), style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp).testTag("album_update_server"),
                )
            }
            state.albumTracksLoading -> item {
                CircularProgressIndicator(Modifier.padding(16.dp))
            }
            else -> albumDiscs(state.albumTracks).forEach { disc ->
                disc.number?.let { n ->
                    item {
                        Text(
                            stringResource(R.string.album_disc, n), style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                        )
                    }
                }
                items(disc.tracks, key = { it.id }) { track ->
                    // From "Also appears on", the whole album with this artist's marked.
                    val theirs = state.openAppearsOn != null && track.artist == artist
                    TrackRow(track, albumArtist, theirs) { picking = track }
                }
            }
        }
    }
    picking?.let { track ->
        PlaylistPicker(
            title = stringResource(R.string.album_add_track_to, track.title), state, vm,
            onPick = { vm.addTrackToPlaylist(track, it) }, onNew = { vm.addTrackToNewPlaylist(track) },
        ) { picking = null }
    }
    if (pickingAlbum) {
        PlaylistPicker(
            title = stringResource(R.string.album_add_track_to, album), state, vm,
            onPick = { vm.addAlbumToPlaylist(artist, album, it) }, onNew = { vm.addAlbumToNewPlaylist(artist, album) },
        ) { pickingAlbum = false }
    }
}

/** What the header's buttons do, and where the cover is for the flight. */
private class AlbumHeaderActions(
    val stage: (type: String, target: String, coverUrl: String?, from: Rect?) -> Unit,
    val coverAt: Rect?,
    val onAddToPlaylist: (() -> Unit)?,
    val onCoverAt: (Rect) -> Unit,
)

@Composable
private fun AlbumHeader(
    state: LibraryUiState,
    artist: String,
    album: String,
    cover: Covers,
    actions: AlbumHeaderActions,
) {
    val albumArtist = state.openAlbumArtist ?: artist
    val url = cover.url(albumArtist, album)
    // The artist's own key: from "Also appears on", that is only their tracks.
    val target = "$artist||$album"
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        TrobarImage(
            url = url, contentDescription = null, pairing = cover.pairing,
            modifier = Modifier.size(COVER_SIZE).onGloballyPositioned { actions.onCoverAt(it.boundsInRoot()) },
        )
        Text(
            album, style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).semantics { heading() },
        )
        Text(albumArtist, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth())
        AlbumFacts(state, album)
        // Once the tracks are in: until then there is no "of" to say.
        state.openAppearsOn?.takeIf { state.albumTracks.isNotEmpty() }?.let { on ->
            Text(
                stringResource(R.string.album_only_theirs, artist, on.trackCount, state.albumTracks.size),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("album_only_theirs"),
            )
        }
        val staged = state.stagedHere("album", target)
        Button(
            onClick = { actions.stage("album", target, url, actions.coverAt) },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("album_add_to_basket"),
        ) {
            Text(stringResource(if (staged) R.string.album_in_basket else R.string.album_add_to_basket))
        }
        actions.onAddToPlaylist?.let {
            OutlinedButton(onClick = it, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("album_add_to_playlist")) {
                Text(stringResource(R.string.album_add_to_playlist))
            }
        }
    }
}

/** The year as the artist page shows it, and the track count. A Home album
 * under an album artist has no row on that artist's page, so no year; the
 * count then comes from the tracklist. */
@Composable
private fun AlbumFacts(state: LibraryUiState, album: String) {
    val row = state.albums.firstOrNull { it.album == album }.takeIf { state.openAppearsOn == null }
    val tracks = state.albumTracks.size.takeIf { it > 0 } ?: row?.trackCount ?: return
    val count = pluralStringResource(R.plurals.library_tracks_count, tracks, tracks)
    val year = state.openAppearsOn?.year?.toString() ?: row?.let { albumYear(state, it) }.orEmpty()
    Text(
        if (year.isEmpty()) count else "$year · $count",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.fillMaxWidth().testTag("album_facts"),
    )
}

@Composable
private fun TrackRow(track: AlbumTrack, albumArtist: String, theirs: Boolean, onTap: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onTap)
            .padding(horizontal = 16.dp, vertical = 10.dp).testTag(if (theirs) "album_track_theirs" else "album_track"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(track.trackNo?.toString().orEmpty(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(TRACK_NO_WIDTH))
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(track.title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (theirs) FontWeight.SemiBold else null)
            if (track.artist != albumArtist) {
                Text(track.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }
        Text(trackDuration(track.duration), style = MaterialTheme.typography.bodySmall)
    }
}

/** The playlists a track or album can go on, and a new one named after
 * it, as the browser's menu has them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistPicker(
    title: String,
    state: LibraryUiState,
    vm: LibraryViewModel,
    onPick: (AppPlaylist) -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(Unit) { vm.loadEditablePlaylists() }
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("album_playlist_sheet")) {
        Text(
            title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
        )
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxWidth()) {
            items(state.editablePlaylists, key = { it.id }) { playlist ->
                PickerRow(playlist.title) {
                    onPick(playlist)
                    onDismiss()
                }
            }
            item {
                PickerRow(stringResource(R.string.album_new_playlist)) {
                    onNew()
                    onDismiss()
                }
            }
        }
    }
}

@Composable
private fun PickerRow(text: String, onClick: () -> Unit) {
    Text(
        text, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    )
}

private const val SECONDS_PER_MINUTE = 60L
private val COVER_SIZE = 200.dp
private val TRACK_NO_WIDTH = 28.dp
