// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import org.junit.Assert.assertEquals
import org.junit.Test

/** The artist list as the web computes it: a case-insensitive substring
 * search, then by name, or by album count descending with name breaking ties. */
class LibraryUiStateTest {
    private val artists = listOf(
        LibraryArtist("Nebula Drift", 20, 3),
        LibraryArtist("aphelion", 15, 2),
        LibraryArtist("Cinder & Ash", 9, 1),
        LibraryArtist("Glass Meridian", 12, 2),
    )

    @Test
    fun byNameIsCaseInsensitive() {
        val names = LibraryUiState(artists = artists).visibleArtists.map { it.artist }
        assertEquals(listOf("aphelion", "Cinder & Ash", "Glass Meridian", "Nebula Drift"), names)
    }

    @Test
    fun byAlbumsIsDescendingWithNameBreakingTies() {
        val names = LibraryUiState(artists = artists, sort = ArtistSort.ALBUMS).visibleArtists.map { it.artist }
        assertEquals(listOf("Nebula Drift", "aphelion", "Glass Meridian", "Cinder & Ash"), names)
    }

    @Test
    fun searchIsASubstringNotAPrefix() {
        assertEquals(listOf("Nebula Drift"), LibraryUiState(artists = artists, query = "BUL").visibleArtists.map { it.artist })
        val shown = LibraryUiState(artists = artists, query = "i").visibleArtists.map { it.artist }
        assertEquals(listOf("Glass Meridian", "Nebula Drift"), shown.filter { it != "aphelion" && it != "Cinder & Ash" })
        assertEquals(4, LibraryUiState(artists = artists, query = "  ").visibleArtists.size)
    }

    private val tagged = listOf(
        LibraryArtist("\u2010M\u2010", 60, 5), // ‐M‐, U+2010 on both sides, as MusicBrainz writes it
        LibraryArtist("Beyoncé", 20, 2),
        LibraryArtist("Björk", 30, 3),
        LibraryArtist("Beyonce Tribute Band", 5, 1),
        LibraryArtist("Jean\u2013Michel Jarre", 12, 1), // en dash
        LibraryArtist("Muse", 40, 4),
    )

    private fun search(q: String) = LibraryUiState(artists = tagged, query = q).visibleArtists.map { it.artist }

    @Test
    fun aTypedHyphenFindsANameWrittenWithUnicodeHyphens() {
        assertEquals(listOf("\u2010M\u2010"), search("-m-"))
        assertEquals(listOf("\u2010M\u2010"), search("-M-"))
        assertEquals(listOf("Jean\u2013Michel Jarre"), search("jean-michel"))
    }

    @Test
    fun anUnaccentedQueryFindsTheAccentedNameAndTheOtherWayRound() {
        assertEquals(listOf("Beyonce Tribute Band", "Beyoncé"), search("beyonce"))
        assertEquals(listOf("Beyonce Tribute Band", "Beyoncé"), search("beyoncé"))
        assertEquals(listOf("Björk"), search("bjork"))
    }

    @Test
    fun theFoldDoesNotWidenAQueryAndNamesStayAsStored() {
        assertEquals(emptyList<String>(), search("m-x"))
        assertEquals(listOf("Muse"), search("mus"))
        assertEquals(tagged.map { it.artist }.toSet(), search("").toSet())
    }

    // The server's `albums` field: every album title, searched with the name.
    private val withAlbums = listOf(
        LibraryArtist("Aphelion", 20, 2, listOf("Parallax", "Event Horizon")),
        LibraryArtist("Nebula Drift", 15, 2, listOf("Drift", "Slow Collapse")),
        LibraryArtist("Horizon Lines", 9, 1, listOf("First Light")),
        LibraryArtist("Björk", 30, 1, listOf("Medúlla")),
    )

    @Test
    fun aNameHitIsListedWithNoAlbumLine() {
        val state = LibraryUiState(artists = withAlbums, query = "nebula")
        assertEquals(listOf("Nebula Drift"), state.visibleArtists.map { it.artist })
        assertEquals(emptyList<String>(), state.albumOnlyHits(state.visibleArtists.single()))
    }

    @Test
    fun whenTheNameMatchesAMatchingAlbumAddsNoLine() {
        // Nebula Drift's own album is called Drift: the name already
        // explains the hit.
        val state = LibraryUiState(artists = withAlbums, query = "drift")
        assertEquals(listOf("Nebula Drift"), state.visibleArtists.map { it.artist })
        assertEquals(emptyList<String>(), state.albumOnlyHits(state.visibleArtists.single()))
    }

    @Test
    fun anAlbumOnlyHitListsTheArtistWithTheMatchingTitles() {
        val state = LibraryUiState(artists = withAlbums, query = "collapse")
        assertEquals(listOf("Nebula Drift"), state.visibleArtists.map { it.artist })
        assertEquals(listOf("Slow Collapse"), state.albumOnlyHits(state.visibleArtists.single()))
    }

    @Test
    fun aNameAndAnAlbumHitBothListAndOnlyTheAlbumHitCarriesALine() {
        // "horizon" is Horizon Lines' name and Aphelion's album.
        val state = LibraryUiState(artists = withAlbums, query = "horizon")
        assertEquals(listOf("Aphelion", "Horizon Lines"), state.visibleArtists.map { it.artist })
        val lines = state.visibleArtists.associate { it.artist to state.albumOnlyHits(it) }
        assertEquals(mapOf("Aphelion" to listOf("Event Horizon"), "Horizon Lines" to emptyList<String>()), lines)
    }

    @Test
    fun albumTitlesAreFoldedLikeNames() {
        val state = LibraryUiState(artists = withAlbums, query = "medulla")
        assertEquals(listOf("Björk"), state.visibleArtists.map { it.artist })
        assertEquals(listOf("Medúlla"), state.albumOnlyHits(state.visibleArtists.single()))
    }

    @Test
    fun withoutTheFieldOnlyTheNameIsSearched() {
        // An older server sends no albums: the title search finds nothing,
        // the name search is unchanged.
        val older = withAlbums.map { it.copy(albums = null) }
        assertEquals(emptyList<String>(), LibraryUiState(artists = older, query = "collapse").visibleArtists.map { it.artist })
        assertEquals(listOf("Nebula Drift"), LibraryUiState(artists = older, query = "nebula").visibleArtists.map { it.artist })
    }

    @Test
    fun noQueryShowsEveryoneAndNoAlbumLines() {
        val state = LibraryUiState(artists = withAlbums)
        assertEquals(4, state.visibleArtists.size)
        assertEquals(true, state.visibleArtists.all { state.albumOnlyHits(it).isEmpty() })
    }
}
