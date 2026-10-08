// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

/** The three album grids of the Home dashboard, deduplicated against each
 * other the way the browser does it, since the server does not: an album
 * both recently released and recently added spends the dashboard's space
 * once, in the widget of highest precedence -- recently released, then
 * recently added, then suggestions.
 *
 * The rules that make this exact rather than roughly right:
 * - a widget that is hidden contributes nothing to what the others
 *   exclude, so hiding recently released makes its albums reappear in
 *   recently added rather than vanish from the dashboard;
 * - the exclude set is built from what a widget SHOWS (its deduplicated,
 *   limited slice), not from everything it holds, so an album past a
 *   widget's display cutoff cannot suppress a visible copy of itself --
 *   and each widget is cut at its OWN cover count, now that each has one;
 * - dedupe first, limit after, so a widget losing albums to a sibling
 *   pulls more from its own tail and is never short;
 * - suggestions are matched on their library names, the ones the server
 *   resolved, not on the spelling the listening service reported. */
object DashboardDedup {
    class Grids(val recentlyReleased: List<AlbumCard>, val recentlyAdded: List<AlbumCard>, val suggestions: List<AlbumCard>)

    fun recentlyReleased(all: Grids, visible: Set<String>, limitOf: (String) -> Int): List<AlbumCard> =
        if (WidgetIds.RECENTLY_RELEASED in visible) all.recentlyReleased.take(limitOf(WidgetIds.RECENTLY_RELEASED)) else emptyList()

    fun recentlyAdded(all: Grids, visible: Set<String>, limitOf: (String) -> Int): List<AlbumCard> {
        if (WidgetIds.RECENTLY_ADDED !in visible) return emptyList()
        val exclude = keys(recentlyReleased(all, visible, limitOf))
        return all.recentlyAdded.filter { key(it) !in exclude }.take(limitOf(WidgetIds.RECENTLY_ADDED))
    }

    fun suggestions(all: Grids, visible: Set<String>, limitOf: (String) -> Int): List<AlbumCard> {
        if (WidgetIds.SUGGESTIONS !in visible) return emptyList()
        val exclude = keys(recentlyReleased(all, visible, limitOf)) + keys(recentlyAdded(all, visible, limitOf))
        return mix(all.suggestions.filter { libraryKey(it) !in exclude }, limitOf(WidgetIds.SUGGESTIONS))
    }

    /** The same with one count for all three. */
    fun recentlyReleased(all: Grids, visible: Set<String>, limit: Int) = recentlyReleased(all, visible) { limit }
    fun recentlyAdded(all: Grids, visible: Set<String>, limit: Int) = recentlyAdded(all, visible) { limit }
    fun suggestions(all: Grids, visible: Set<String>, limit: Int) = suggestions(all, visible) { limit }

    /** Round-robins across the sources present, in order of first
     * appearance, so a blend shows every source rather than whichever
     * came first in the merged list crowding the others out. */
    fun mix(list: List<AlbumCard>, limit: Int): List<AlbumCard> {
        val buckets = LinkedHashMap<String?, ArrayDeque<AlbumCard>>()
        list.forEach { buckets.getOrPut(it.source) { ArrayDeque() }.addLast(it) }
        val order = buckets.keys.toList()
        val out = ArrayList<AlbumCard>()
        var i = 0
        while (out.size < limit && buckets.values.any { it.isNotEmpty() }) {
            buckets.getValue(order[i % order.size]).removeFirstOrNull()?.let(out::add)
            i++
        }
        return out
    }

    private fun key(card: AlbumCard) = "${card.artist}||${card.album}"
    private fun libraryKey(card: AlbumCard) = "${card.libraryArtist}||${card.libraryAlbum}"
    private fun keys(cards: List<AlbumCard>) = cards.map(::key).toSet()
}
