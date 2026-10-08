// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ShoppingBasket
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/** The Library tab: every artist, searched and sorted on the phone; one
 * artist's albums as a list or a cover grid per the profile's view mode,
 * with similar artists in the library under the name; a double-tap on
 * an album stages it for the header's device, its cover flying to the
 * basket. Master and detail are two pages on a phone-width window and
 * side by side above it; the state is the ViewModel's, so rotation and
 * tab switches keep the artist and the search. */
@Composable
fun LibraryScreen(
    pairing: Prefs.Pairing,
    onOpenBasket: () -> Unit,
    shared: AppState,
    vm: LibraryViewModel = viewModel(factory = LibraryViewModel.factory(LocalContext.current, pairing, shared)),
) {
    val state by vm.state.collectAsState()
    val sharedState by shared.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val urls = remember(pairing) { AppApiClient(context, pairing.serverUrl, pairing.token) }
    val cover = Covers(
        pairing,
        url = { artist, album -> urls.coverUrl(artist, album) },
        artistImage = { urls.artistImageUrl(it, small = true) },
    )
    val compact = isCompactWidth()
    val flight = rememberBasketFlight()
    val answer = rememberStageAnswer(flight, snackbar)
    val stage: StageAt = { type, target, coverUrl, from -> answer(vm.stage(type, target), coverUrl, from) }
    val artistOpen = state.selectedArtist != null

    state.error?.let { message ->
        LaunchedEffect(message) {
            snackbar.showSnackbar(message)
            vm.consumeError()
        }
    }
    // On a phone the artist pane is a page over the list: back returns to the list, not to the previous tab.
    BackHandler(enabled = compact && artistOpen) { vm.selectArtist(null) }
    TabDrillDownOpen(Routes.LIBRARY, compact && artistOpen)

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                LibraryTopBar(
                    drillDown = compact && artistOpen,
                    onBack = { vm.selectArtist(null) },
                    basketCount = state.basket.size,
                    onOpenBasket = onOpenBasket,
                    shared = sharedState,
                    onChooseDevice = shared::chooseDevice,
                    flight = flight,
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                when {
                    state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    compact -> if (artistOpen) ArtistPane(state, vm, cover, stage) else ArtistList(state, vm, cover)
                    else -> Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(LIST_WEIGHT)) { ArtistList(state, vm, cover) }
                        Box(Modifier.weight(DETAIL_WEIGHT)) {
                            if (artistOpen) ArtistPane(state, vm, cover, stage)
                            else Text(stringResource(R.string.library_pick_an_artist), modifier = Modifier.align(Alignment.Center).padding(32.dp))
                        }
                    }
                }
            }
        }
        BasketFlightOverlay(flight, pairing)
    }
}

/** A stage from the artist pane: the type and target, and for an album
 * the cover and where it was, for the flight. */
private typealias StageAt = (type: String, target: String, coverUrl: String?, from: Rect?) -> Unit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryTopBar(
    drillDown: Boolean,
    onBack: () -> Unit,
    basketCount: Int,
    onOpenBasket: () -> Unit,
    shared: SharedState,
    onChooseDevice: (Long) -> Unit,
    flight: BasketFlight,
) {
    TopAppBar(
        modifier = Modifier.testTag("library_top_bar"),
        // The wordmark is the tab's title. An artist opened on a phone is a
        // drill-down: back, and the icons, with the name on the page below,
        // where it has the full width. The selector stays either way: the
        // artist page is where albums are staged.
        title = {
            if (!drillDown) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TrobarWordmarkTitle()
                    DeviceSelector(shared, onChooseDevice)
                }
            }
        },
        navigationIcon = {
            if (drillDown) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back_content_description))
                }
            }
        },
        actions = {
            if (drillDown) DeviceSelector(shared, onChooseDevice)
            IconButton(onClick = onOpenBasket, modifier = Modifier.basketFlightTarget(flight)) {
                BadgedBox(badge = { if (basketCount > 0) Badge { Text(basketCount.toString()) } }) {
                    Icon(Icons.Filled.ShoppingBasket, contentDescription = stringResource(R.string.basket_title))
                }
            }
        },
    )
}

@Composable
private fun ArtistList(state: LibraryUiState, vm: LibraryViewModel, cover: Covers) {
    val artists = state.visibleArtists
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = vm::setQuery,
            singleLine = true,
            placeholder = { Text(stringResource(R.string.library_search_hint)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("artist_search"),
        )
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                pluralStringResource(R.plurals.library_artists_count, artists.size, artists.size),
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = vm::toggleSort) {
                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null)
                Text(stringResource(if (state.sort == ArtistSort.NAME) R.string.library_sort_name else R.string.library_sort_albums))
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(artists, key = { it.artist }) { artist ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { vm.selectArtist(artist.artist) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Round like the similar-artists strip; decorative beside the name. A
                    // fixed size, so a row is one height whether its picture has arrived,
                    // is loading or has none -- the list does not shift under a fast scroll.
                    TrobarImage(
                        url = cover.artistImage(artist.artist), contentDescription = null, pairing = cover.pairing,
                        modifier = Modifier.size(ARTIST_ROW_PICTURE_SIZE).clip(CircleShape).testTag("artist_picture"),
                    )
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(artist.artist, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        // The albums that matched when the name did not, so the hit makes sense.
                        val hits = state.albumOnlyHits(artist)
                        if (hits.isNotEmpty()) {
                            Text(
                                hits.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("album_hits"),
                            )
                        }
                    }
                    Text(
                        pluralStringResource(R.plurals.library_albums_count, artist.albumCount, artist.albumCount),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

/** The albums first, under the artist's name; similar artists after the
 * last album, in the same scrolling surface. */
@Composable
private fun ArtistPane(state: LibraryUiState, vm: LibraryViewModel, cover: Covers, stage: StageAt) {
    val artist = state.selectedArtist ?: return
    when {
        state.albumsLoading -> Column(Modifier.fillMaxSize()) {
            ArtistHeader(state, artist, LIST_INSET, stage)
            Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
        }
        state.coverGrid -> AlbumGrid(state, artist, vm, cover, stage)
        else -> AlbumList(state, artist, vm, cover, stage)
    }
}

/** The name on its own line, its sync button -- the whole artist, for the
 * header's device -- under it, then the album count. [inset] is the
 * horizontal padding left to add inside the scrolling surface. */
@Composable
private fun ArtistHeader(state: LibraryUiState, artist: String, inset: Dp, stage: StageAt) {
    Column(Modifier.fillMaxWidth()) {
        // Stacked on every width: beside the button, a name of several words
        // wraps word by word into a narrow column without breaking any word.
        Column(Modifier.fillMaxWidth().padding(horizontal = inset, vertical = 16.dp)) {
            // The screen's title now the top bar has none, so TalkBack can jump to it.
            Text(artist, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            val staged = state.stagedHere("artist", artist)
            OutlinedButton(onClick = { stage("artist", artist, null, null) }, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(if (staged) R.string.library_artist_in_basket else R.string.library_sync_entire_artist))
            }
        }
        Text(
            pluralStringResource(R.plurals.library_albums_count, state.albums.size, state.albums.size),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.fillMaxWidth().padding(horizontal = inset, vertical = 8.dp),
        )
    }
}

/** Similar artists in the library, as the browser lists them: each a round
 * picture over its name, in a strip that scrolls sideways, so a long name
 * never squeezes the others. No picture shows the image fallback, and the
 * entry still opens the artist. */
@Composable
private fun SimilarArtists(state: LibraryUiState, vm: LibraryViewModel, cover: Covers, inset: Dp) {
    if (state.similar.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 16.dp)) {
        Text(
            stringResource(R.string.library_similar), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = inset),
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("similar_artists"),
            contentPadding = PaddingValues(horizontal = inset),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.similar, key = { it }) { name ->
                Column(
                    Modifier.width(SIMILAR_ENTRY_WIDTH).clickable(role = Role.Button) { vm.selectArtist(name) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    TrobarImage(
                        url = cover.artistImage(name), contentDescription = null, pairing = cover.pairing,
                        modifier = Modifier.size(SIMILAR_PICTURE_SIZE).clip(CircleShape),
                    )
                    Text(
                        name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun albumSubtitle(state: LibraryUiState, album: LibraryAlbum): String {
    val year = listOfNotNull(album.year?.toString(), album.reissueYear?.takeIf { state.showReissueYear }?.let { "($it)" }).joinToString(" ")
    val tracks = pluralStringResource(R.plurals.library_tracks_count, album.trackCount, album.trackCount)
    return if (year.isEmpty()) tracks else "$year · $tracks"
}

@Composable
private fun AlbumList(state: LibraryUiState, artist: String, vm: LibraryViewModel, cover: Covers, stage: StageAt) {
    val label = stringResource(R.string.stage_action)
    LazyColumn(Modifier.fillMaxSize().testTag("album_list")) {
        item { ArtistHeader(state, artist, LIST_INSET, stage) }
        items(state.albums, key = { it.album }) { album ->
            val target = "$artist||${album.album}"
            Column(
                Modifier.fillMaxWidth()
                    .stageOnDoubleTap(label) { from -> stage("album", target, cover.url(artist, album.album), from) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(album.album, style = MaterialTheme.typography.bodyLarge)
                Text(albumSubtitle(state, album), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }
        item { SimilarArtists(state, vm, cover, LIST_INSET) }
    }
}

/** What a cover needs: the pairing the image loader authenticates with,
 * the App API URL for an album's cover, and for an artist's picture. */
class Covers(
    val pairing: Prefs.Pairing,
    val url: (artist: String, album: String) -> String,
    val artistImage: (artist: String) -> String,
)

@Composable
private fun AlbumGrid(state: LibraryUiState, artist: String, vm: LibraryViewModel, cover: Covers, stage: StageAt) {
    val label = stringResource(R.string.stage_action)
    // As many columns as the width holds, as on Home (coverColumns).
    BoxWithConstraints(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(coverColumns(maxWidth - GRID_PADDING * 2, GRID_SPACING)),
            modifier = Modifier.fillMaxSize().testTag("album_grid"),
            contentPadding = PaddingValues(GRID_PADDING),
            horizontalArrangement = Arrangement.spacedBy(GRID_SPACING),
            verticalArrangement = Arrangement.spacedBy(GRID_SPACING),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { ArtistHeader(state, artist, GRID_INSET, stage) }
            items(state.albums, key = { it.album }) { album ->
                val target = "$artist||${album.album}"
                val url = cover.url(artist, album.album)
                Column(Modifier.stageOnDoubleTap(label) { from -> stage("album", target, url, from) }) {
                    TrobarImage(
                        url = url, contentDescription = null,
                        pairing = cover.pairing, modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    )
                    Text(album.album, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    Text(
                        albumSubtitle(state, album), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline, maxLines = 1,
                    )
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { SimilarArtists(state, vm, cover, GRID_INSET) }
        }
    }
}

private val GRID_PADDING = 12.dp
private val GRID_SPACING = 8.dp
private const val LIST_WEIGHT = 2f
private const val DETAIL_WEIGHT = 3f
// The grid's content padding is 12dp; header and strip add the rest of the list's 16dp.
private val LIST_INSET = 16.dp
private val GRID_INSET = 4.dp
private val SIMILAR_ENTRY_WIDTH = 88.dp
private val SIMILAR_PICTURE_SIZE = 72.dp
private val ARTIST_ROW_PICTURE_SIZE = 40.dp
