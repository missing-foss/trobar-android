// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlbumDetailsTest {
    private fun track(id: Long, disc: Int?) = AlbumTrack(id, 1, disc, "T", "A", null)

    @Test
    fun oneDiscHasNoHeading() {
        val discs = albumDiscs(listOf(track(1, 1), track(2, 1)))
        assertEquals(1, discs.size)
        assertNull(discs.single().number)
    }

    @Test
    fun noDiscNumberIsOneDisc() {
        assertNull(albumDiscs(listOf(track(1, null), track(2, null))).single().number)
    }

    @Test
    fun severalDiscsKeepTheServersOrder() {
        val discs = albumDiscs(listOf(track(1, 1), track(2, 2), track(3, 2)))
        assertEquals(listOf(1, 2), discs.map { it.number })
        assertEquals(listOf(2L, 3L), discs[1].tracks.map { it.id })
    }

    @Test
    fun aTrackWithoutADiscAmongDiscsTakesNoHeadingOfItsOwn() {
        assertEquals(listOf(null, 1), albumDiscs(listOf(track(1, null), track(2, 1))).map { it.number })
    }

    @Test
    fun durationsRoundToTheSecond() {
        assertEquals("4:12", trackDuration(252.4))
        assertEquals("1:05", trackDuration(65.0))
        assertEquals("", trackDuration(null))
        assertEquals("", trackDuration(0.0))
    }
}
