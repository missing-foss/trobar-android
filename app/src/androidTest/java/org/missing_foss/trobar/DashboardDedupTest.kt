// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The browser's cross-widget rule, reimplemented: precedence, hidden
 * widgets excluding nothing, the exclude set built from what is shown,
 * dedupe before the limit, suggestions matched on library names, and the
 * round-robin mix by source. */
@RunWith(AndroidJUnit4::class)
class DashboardDedupTest {
    private fun card(artist: String, album: String, source: String? = "recent", libraryArtist: String? = artist, libraryAlbum: String? = album) =
        AlbumCard(artist, album, libraryArtist, libraryAlbum, null, source, null, null, null)

    private val released = listOf(card("A", "One"), card("B", "Two"), card("C", "Three"))
    private val added = listOf(card("B", "Two"), card("D", "Four"), card("A", "One"), card("E", "Five"))
    private val suggestions = listOf(
        card("D", "Four", source = "recent"),
        card("F", "Six", source = "lastfm"),
        card("H", "Eight", source = "lastfm"), // two lastfm entries adjacent: only the round-robin puts Seven between them
        card("G", "Seven", source = "recent"),
        card("A - remaster", "One", source = "lastfm", libraryArtist = "A", libraryAlbum = "One"), // the service's spelling; the library's key
    )
    private val all = DashboardDedup.Grids(released, added, suggestions)
    private val everything = setOf(WidgetIds.RECENTLY_RELEASED, WidgetIds.RECENTLY_ADDED, WidgetIds.SUGGESTIONS)

    private fun titles(cards: List<AlbumCard>) = cards.map { it.album }

    @Test
    fun precedenceRunsReleasedThenAddedThenSuggestions() {
        assertEquals(listOf("One", "Two", "Three"), titles(DashboardDedup.recentlyReleased(all, everything, 10)))
        assertEquals(listOf("Four", "Five"), titles(DashboardDedup.recentlyAdded(all, everything, 10))) // One and Two are in released
        // Four is in added; One is in released, matched on the library key though the service spells the artist differently.
        assertEquals(listOf("Six", "Seven", "Eight"), titles(DashboardDedup.suggestions(all, everything, 10)))
    }

    @Test
    fun aHiddenWidgetExcludesNothingSoItsAlbumsReappearBelow() {
        val withoutReleased = setOf(WidgetIds.RECENTLY_ADDED, WidgetIds.SUGGESTIONS)
        assertEquals(emptyList<String>(), titles(DashboardDedup.recentlyReleased(all, withoutReleased, 10)))
        assertEquals(listOf("Two", "Four", "One", "Five"), titles(DashboardDedup.recentlyAdded(all, withoutReleased, 10)))
        val onlySuggestions = setOf(WidgetIds.SUGGESTIONS)
        assertEquals(listOf("Four", "Six", "Seven", "Eight", "One"), titles(DashboardDedup.suggestions(all, onlySuggestions, 10)))
    }

    @Test
    fun theExcludeSetIsWhatIsShownNotWhatIsHeld() {
        // With a limit of 1, released shows only One; Two is past its cutoff and must not suppress the visible Two in added.
        assertEquals(listOf("One"), titles(DashboardDedup.recentlyReleased(all, everything, 1)))
        assertEquals(listOf("Two"), titles(DashboardDedup.recentlyAdded(all, everything, 1)))
    }

    @Test
    fun dedupeBeforeTheLimitSoAWidgetIsNeverShort() {
        // added holds four; two are taken by released; with a limit of 2 it still shows two.
        assertEquals(listOf("Four", "Five"), titles(DashboardDedup.recentlyAdded(all, everything, 2)))
    }

    @Test
    fun theMixRoundRobinsSourcesInOrderOfFirstAppearance() {
        val list = listOf(card("a", "r1", "recent"), card("b", "r2", "recent"), card("c", "l1", "lastfm"), card("d", "r3", "recent"), card("e", "l2", "lastfm"))
        assertEquals(listOf("r1", "l1", "r2", "l2", "r3"), titles(DashboardDedup.mix(list, 10)))
        assertEquals(listOf("r1", "l1", "r2"), titles(DashboardDedup.mix(list, 3)))
        assertEquals(emptyList<String>(), titles(DashboardDedup.mix(emptyList(), 3)))
    }

    @Test
    fun eachWidgetIsCutAtItsOwnCountWhenTheOthersDedupAgainstIt() {
        // Released shows 3, Added 10: "Three" is shown in Released, so it is
        // not repeated in Added. Cutting Released at another widget's count
        // would get that wrong whenever the two differ.
        val limits = mapOf(WidgetIds.RECENTLY_RELEASED to 3, WidgetIds.RECENTLY_ADDED to 10, WidgetIds.SUGGESTIONS to 10)
        val shared = DashboardDedup.Grids(released, released.drop(2) + added, suggestions)
        assertEquals(listOf("One", "Two", "Three"), titles(DashboardDedup.recentlyReleased(shared, everything, limits::getValue)))
        assertTrue("Three" !in titles(DashboardDedup.recentlyAdded(shared, everything, limits::getValue)))
        // With Released at 2, Three is past its cut and shows in Added instead.
        val fewer = limits + (WidgetIds.RECENTLY_RELEASED to 2)
        assertTrue("Three" in titles(DashboardDedup.recentlyAdded(shared, everything, fewer::getValue)))
    }

    @Test
    fun suggestionsDedupAgainstEachSiblingCutAtThatSiblingsOwnCount() {
        // Suggestions excludes what BOTH siblings show, each at its own count.
        // "One" is Released's first and "Four" Added's first (after dedup):
        // with Suggestions at 1 but the siblings at 10, both are shown there
        // and so left out of Suggestions. Cutting the siblings at Suggestions'
        // count, 1, would keep only "One" out, and show "Four" twice.
        val limits = mapOf(WidgetIds.RECENTLY_RELEASED to 10, WidgetIds.RECENTLY_ADDED to 10, WidgetIds.SUGGESTIONS to 1)
        val shown = titles(DashboardDedup.suggestions(all, everything, limits::getValue))
        assertTrue("One" !in shown && "Four" !in shown)
        assertEquals(1, shown.size)
    }
}
