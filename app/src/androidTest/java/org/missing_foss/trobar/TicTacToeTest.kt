// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
// Duel the bard: the winning line for a row, a column and a diagonal, the
// bard taking an outright win, and the dialog keeping its size and place
// from the first move through each ending and Play again, with only the
// winning cells marked.

package org.missing_foss.trobar

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoundEffect
import androidx.compose.ui.platform.SoundEffect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpRect
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TicTacToeTest {
    @get:Rule
    val compose = createComposeRule()

    private val n = TttMark.NONE
    private val p = TttMark.PLAYER
    private val b = TttMark.BARD

    @Test
    fun theWinningLineIsFoundForARowAColumnAndADiagonal() {
        assertEquals(listOf(3, 4, 5), tttWinningLine(listOf(n, b, n, p, p, p, b, n, b)))
        assertEquals(listOf(1, 4, 7), tttWinningLine(listOf(p, b, n, n, b, p, n, b, n)))
        assertEquals(listOf(2, 4, 6), tttWinningLine(listOf(p, n, b, p, b, n, b, n, p)))
        assertEquals(TttMark.BARD, tttWinner(listOf(p, n, b, p, b, n, b, n, p)))
    }

    @Test
    fun noLineWinsAnEmptyBoardOrADraw() {
        assertNull(tttWinningLine(List(9) { n }))
        val draw = listOf(p, b, p, p, b, b, b, p, p)
        assertNull(tttWinningLine(draw))
        assertNull(tttWinner(draw))
    }

    @Test
    fun theBardAlwaysTakesAnOutrightWin() {
        repeat(50) {
            val next = bardMove(listOf(b, b, n, p, p, n, p, n, n))
            assertEquals(TttMark.BARD, next[2])
            assertEquals(listOf(0, 1, 2), tttWinningLine(next))
        }
    }

    /** The bard plays the first free cell of [order]: a game the test chooses. */
    private fun scripted(vararg order: Int): (List<TttMark>) -> List<TttMark> = { board ->
        board.toMutableList().also { it[order.first { i -> board[i] == TttMark.NONE }] = TttMark.BARD }
    }

    private fun setGame(bard: (List<TttMark>) -> List<TttMark>, fontScale: Float? = null) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale ?: density.fontScale)) {
                MaterialTheme { TicTacToeDialog(onDismiss = {}, bard = bard) }
            }
        }
    }

    /** The dialog, the board and the Close button: none may move or resize. */
    private fun bounds(): List<DpRect> = listOf(
        compose.onNodeWithTag("ttt_dialog").getBoundsInRoot(),
        compose.onNodeWithTag("ttt_board").getBoundsInRoot(),
        compose.onNodeWithText("Close").getBoundsInRoot(),
    )

    private fun tap(cell: Int) {
        compose.onNodeWithTag("ttt_cell_$cell").performClick()
        compose.waitForIdle()
    }

    private fun winningCells(): List<Int> = (0 until 9).filter { i ->
        compose.onNodeWithTag("ttt_cell_$i").fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.StateDescription) == "In the winning line"
    }

    private fun playThrough(moves: List<Int>, outcome: String) {
        val start = bounds()
        compose.onAllNodesWithText("Play again").assertCountEquals(0) // laid out, but not reachable yet
        moves.forEachIndexed { k, cell ->
            tap(cell)
            if (k == 0) assertEquals("the first move moves nothing", start, bounds())
        }
        compose.onNodeWithText(outcome).fetchSemanticsNode()
        compose.mainClock.advanceTimeBy(2_000) // past the ending's animation
        compose.waitForIdle()
        assertEquals("the ending moves nothing", start, bounds())
        compose.onNodeWithText("Play again").performClick()
        compose.waitForIdle()
        assertEquals("Play again moves nothing", start, bounds())
        assertEquals(emptyList<Int>(), winningCells())
    }

    @Test
    fun aPlayerWinHighlightsItsLineAndMovesNothing() {
        setGame(scripted(4, 5, 8, 6, 7))
        val start = bounds()
        listOf(0, 1, 2).forEach(::tap)
        assertEquals(listOf(0, 1, 2), winningCells())
        compose.onNodeWithText("You've bested the bard's tune!").fetchSemanticsNode()
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        assertEquals(start, bounds())
    }

    @Test
    fun aBardWinHighlightsItsLineAndMovesNothing() {
        setGame(scripted(3, 4, 5, 2, 6, 7))
        listOf(0, 1, 8).forEach(::tap)
        assertEquals(listOf(3, 4, 5), winningCells())
    }

    @Test
    fun aDrawHighlightsNothing() {
        // Player 0, 2, 3, 7, 5 against the bard's 1, 4, 6, 8: no line.
        setGame(scripted(1, 4, 6, 8))
        listOf(0, 2, 3, 7, 5).forEach(::tap)
        compose.onNodeWithText("A well-matched duet — call it a draw.").fetchSemanticsNode()
        assertEquals(emptyList<Int>(), winningCells())
    }

    @Test
    fun theDialogKeepsItsSizeThroughEveryEndingAndPlayAgain() {
        setGame(scripted(4, 5, 8, 6, 7))
        playThrough(listOf(0, 1, 2), "You've bested the bard's tune!")
    }

    @Test
    fun theDialogKeepsItsSizeThroughABardWin() {
        setGame(scripted(3, 4, 5, 2, 6, 7))
        playThrough(listOf(0, 1, 8), "The bard's melody prevails.")
    }

    @Test
    fun theDialogKeepsItsSizeThroughADraw() {
        setGame(scripted(1, 4, 6, 8))
        playThrough(listOf(0, 2, 3, 7, 5), "A well-matched duet — call it a draw.")
    }

    @Test
    fun theDialogKeepsItsSizeAtTheLargestFontScale() {
        // At 2x the buttons are wider than the board: Play again appearing
        // late would widen the dialog here.
        setGame(scripted(4, 5, 8, 6, 7), fontScale = 2f)
        playThrough(listOf(0, 1, 2), "You've bested the bard's tune!")
    }

    @Test
    fun noTapInTheGameMakesASound() {
        // The click sound Compose's clickables play goes through
        // LocalSoundEffect; one counting around the game must hear nothing.
        // Around the game, not the dialog: a dialog's window supplies its own.
        var clicks = 0
        val counting = object : SoundEffect {
            override fun playClickSound() { clicks++ }
        }
        compose.setContent {
            CompositionLocalProvider(LocalSoundEffect provides counting) {
                MaterialTheme { TicTacToeGame(onDismiss = {}, bard = scripted(4, 5, 8, 6, 7)) }
            }
        }
        listOf(0, 1, 2).forEach(::tap)
        compose.onNodeWithText("Play again").performClick()
        compose.onNodeWithText("Close").performClick()
        compose.waitForIdle()
        assertEquals(0, clicks)
    }

    /** A player win with the clock held, stopped about 600 ms into the ending. */
    private fun midEndingOfAPlayerWin(animations: Boolean) {
        compose.setContent {
            MaterialTheme {
                TicTacToeDialog(onDismiss = {}, bard = scripted(4, 5, 8, 6, 7), animationsOn = { animations })
            }
        }
        compose.mainClock.autoAdvance = false
        listOf(0, 1, 2).forEach { compose.onNodeWithTag("ttt_cell_$it").performClick(); compose.mainClock.advanceTimeByFrame() }
        compose.mainClock.advanceTimeBy(600)
    }

    @Test
    fun withAnimationsOnAnEndingMoves() {
        midEndingOfAPlayerWin(animations = true)
        compose.onAllNodesWithTag("ttt_ending_motion").assertCountEquals(1)
        compose.onAllNodesWithTag("ttt_burst").assertCountEquals(1)
        assertEquals(listOf(0, 1, 2), winningCells())
    }

    @Test
    fun withRemoveAnimationsOnlyTheHighlightShows() {
        midEndingOfAPlayerWin(animations = false)
        compose.onAllNodesWithTag("ttt_ending_motion").assertCountEquals(0)
        compose.onAllNodesWithTag("ttt_burst").assertCountEquals(0)
        assertEquals("the static highlight still shows", listOf(0, 1, 2), winningCells())
        // Nor later: there is no ending to run.
        compose.mainClock.advanceTimeBy(1_000)
        compose.onAllNodesWithTag("ttt_ending_motion").assertCountEquals(0)
    }

    @Test
    fun playAgainMidAnimationResetsTheBoard() {
        setGame(scripted(4, 5, 8, 6, 7))
        compose.mainClock.autoAdvance = false
        listOf(0, 1, 2).forEach { compose.onNodeWithTag("ttt_cell_$it").performClick(); compose.mainClock.advanceTimeByFrame() }
        compose.mainClock.advanceTimeBy(300) // mid-burst
        compose.onNodeWithText("Play again").performClick()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(emptyList<Int>(), winningCells())
        compose.onAllNodesWithText("Play again").assertCountEquals(0)
        // The board is empty again and playable.
        tap(0)
        compose.onAllNodesWithText("♪").assertCountEquals(1)
    }
}
