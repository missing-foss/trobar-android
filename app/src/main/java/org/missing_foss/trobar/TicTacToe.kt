// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
// The file is the game; TttMark is only the board's cell type.
@file:Suppress("MatchingDeclarationName")

package org.missing_foss.trobar

import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoundEffect
import androidx.compose.ui.platform.SoundEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Easter egg (5 taps on the mark, About screen). Human plays one note
 * glyph, the bard answers with the other one, rather than plain X/O. The
 * bard plays to win-or-block first, otherwise centre/corner/edge — beatable
 * on purpose ("doesn't need to be unbeatable"), not a minimax grind. */
internal enum class TttMark { NONE, PLAYER, BARD }

internal val TTT_LINES = listOf(
    listOf(0, 1, 2), listOf(3, 4, 5), listOf(6, 7, 8), // rows
    listOf(0, 3, 6), listOf(1, 4, 7), listOf(2, 5, 8), // columns
    listOf(0, 4, 8), listOf(2, 4, 6),                  // diagonals
)

/** The three cells of the line that won, or null while no line is full. */
internal fun tttWinningLine(board: List<TttMark>): List<Int>? = TTT_LINES.firstOrNull { (a, b, c) ->
    board[a] != TttMark.NONE && board[a] == board[b] && board[a] == board[c]
}

internal fun tttWinner(board: List<TttMark>): TttMark? = tttWinningLine(board)?.let { board[it[0]] }

/** Never misses an outright win (that would just feel broken) — but
 * blocking and positioning are deliberately imperfect, a musician playing
 * by ear rather than a calculator. A strict win/block/centre-corner-edge
 * heuristic (the first version of this) is *technically* beatable, but
 * only via a specific forcing sequence (opposite corners, then a fork) a
 * casual player has no reason to know — in ordinary play it draws or wins
 * essentially every time, which isn't "doesn't need to be unbeatable".
 * So: only a 70% chance to block a real threat, and a genuinely random cell
 * (not the strong centre-first preference) when there's no win or block to
 * make — real, frequent openings to win without needing to already know
 * the trick. */
internal fun bardMove(board: List<TttMark>): List<TttMark> {
    val empty = board.indices.filter { board[it] == TttMark.NONE }
    fun wouldWin(i: Int, mark: TttMark): Boolean {
        val trial = board.toMutableList()
        trial[i] = mark
        return tttWinner(trial) == mark
    }
    val move = empty.firstOrNull { wouldWin(it, TttMark.BARD) }
        ?: (if (Random.nextInt(10) < 7) empty.firstOrNull { wouldWin(it, TttMark.PLAYER) } else null)
        ?: empty.random()
    val result = board.toMutableList()
    result[move] = TttMark.BARD
    return result
}

private const val ENDING_MS = 1300
// The endings' motion, as fractions of the ending and as sizes.
private const val PULSE_END = 0.3f
private const val PULSE_SCALE = 0.18f
private const val WILT_DEGREES = 25f
private const val WILT_DROP_DP = 6
private const val BOUNCE_START = 0.5f
private const val BOUNCE_DP = 6
private const val DRAW_FADE = 0.35f
private const val BURST_NOTES = 12
private const val SECOND_TAP_DELAY_MS = 120L

/** Whether the phone's "Remove animations" is off: with it on, an ending
 * shows only the static highlight. Read at each ending, so a change takes
 * effect without reopening the game. */
private fun animationsOn(context: android.content.Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

/** The game. Its size never changes: the result line and Play again are
 * laid out from the first move, invisible until the game ends, so the
 * dialog is never re-centred and the board stays put. An ending is drawn
 * over the board, never by moving or resizing it: notes burst from the
 * player's winning line, the board droops for the bard's, and a draw fades.
 * The winning line is highlighted in either case, and is all that shows
 * with "Remove animations" on. Haptics go through the view, so the phone's
 * touch-feedback setting decides; there is no sound anywhere.
 *
 * [bard] makes the bard's move and [animationsOn] reads the phone's setting;
 * tests give each a fixed one. */
@Composable
internal fun TicTacToeDialog(
    onDismiss: () -> Unit,
    bard: (List<TttMark>) -> List<TttMark> = ::bardMove,
    animationsOn: (android.content.Context) -> Boolean = ::animationsOn,
) {
    Dialog(onDismissRequest = onDismiss) { TicTacToeGame(onDismiss, bard, animationsOn) }
}

/** The dialog's content: the game itself, testable without a window of its own. */
@Composable
internal fun TicTacToeGame(
    onDismiss: () -> Unit,
    bard: (List<TttMark>) -> List<TttMark> = ::bardMove,
    animationsOn: (android.content.Context) -> Boolean = ::animationsOn,
) {
    var board by remember { mutableStateOf(List(TTT_BOARD_SIZE) { TttMark.NONE }) }
    var result by remember { mutableStateOf<TttMark?>(null) } // null = still playing; NONE = draw
    // A new game restarts (and so cancels) the ending's animation.
    var game by remember { mutableIntStateOf(0) }
    var animate by remember { mutableStateOf(false) }
    val progress = remember(game) { Animatable(0f) }
    val context = LocalContext.current
    val view = LocalView.current

    fun reset() {
        board = List(TTT_BOARD_SIZE) { TttMark.NONE }
        result = null
        game++
    }

    fun playerTap(i: Int) {
        if (result != null || board[i] != TttMark.NONE) return
        var next = board.toMutableList().also { it[i] = TttMark.PLAYER }
        val playerWin = tttWinner(next)
        if (playerWin != null) {
            board = next; result = playerWin
            return
        }
        if (next.none { it == TttMark.NONE }) {
            board = next; result = TttMark.NONE
            return
        }
        next = bard(next).toMutableList()
        board = next
        result = tttWinner(next) ?: if (next.none { it == TttMark.NONE }) TttMark.NONE else null
    }

    LaunchedEffect(game, result) {
        val ended = result ?: return@LaunchedEffect
        animate = animationsOn(context)
        when (ended) {
            TttMark.PLAYER -> view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            TttMark.BARD -> {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                delay(SECOND_TAP_DELAY_MS)
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
            TttMark.NONE -> Unit
        }
        if (animate) progress.animateTo(1f, tween(ENDING_MS, easing = LinearEasing))
    }

    val line = if (result == TttMark.PLAYER || result == TttMark.BARD) tttWinningLine(board).orEmpty() else emptyList()
    val p = if (animate && result != null) progress.value else 0f

    // No sound at all in the game: Compose's clickables play the system
    // click sound through LocalSoundEffect (when the phone's touch sounds
    // are on), so the game's own taps get a silent one.
    CompositionLocalProvider(LocalSoundEffect provides Silent) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier.padding(20.dp).testTag("ttt_dialog"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.tictactoe_title),
                    fontFamily = FredokaFamily,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleLarge,
                )
                Box(Modifier.testTag("ttt_board")) {
                    TttBoard(board, result, line, p, onTap = ::playerTap)
                    if (result == TttMark.PLAYER && p > 0f && p < 1f) {
                        NoteBurst(line, p, Modifier.matchParentSize().testTag("ttt_burst"))
                    }
                    // Nothing drawn: marks, for tests, the time an ending is in motion.
                    if (p > 0f && p < 1f) Box(Modifier.testTag("ttt_ending_motion"))
                }
                TttResultLine(result)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val ended = result != null
                    OutlinedButton(
                        onClick = { reset() },
                        enabled = ended,
                        // Laid out from the start, so the row never widens; hidden, and
                        // out of reach of TalkBack, until the game ends.
                        modifier = Modifier.alpha(if (ended) 1f else 0f)
                            .then(if (ended) Modifier else Modifier.clearAndSetSemantics {}),
                    ) { Text(stringResource(R.string.tictactoe_play_again)) }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.close_button)) }
                }
            }
        }
    }
}

/** The game's taps play nothing. */
private object Silent : SoundEffect {
    override fun playClickSound() = Unit
}

/** The three outcomes stacked in one slot, only the current one visible:
 * the slot is as tall as the tallest, whichever the game ends on. */
@Composable
private fun TttResultLine(result: TttMark?) {
    Box(contentAlignment = Alignment.Center) {
        for (outcome in listOf(TttMark.PLAYER, TttMark.BARD, TttMark.NONE)) {
            val shown = result == outcome
            Text(
                stringResource(
                    when (outcome) {
                        TttMark.PLAYER -> R.string.tictactoe_player_win
                        TttMark.BARD -> R.string.tictactoe_bard_win
                        TttMark.NONE -> R.string.tictactoe_draw
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.alpha(if (shown) 1f else 0f)
                    .then(if (shown) Modifier else Modifier.clearAndSetSemantics {}),
            )
        }
    }
}

@Composable
private fun TttBoard(board: List<TttMark>, result: TttMark?, line: List<Int>, p: Float, onTap: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val winningCell = stringResource(R.string.tictactoe_winning_cell)
    val density = LocalDensity.current
    // #38: a roomier board on tablets / unfolded foldables.
    val cell = if (isCompactWidth()) 64.dp else 88.dp
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (row in 0..2) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (col in 0..2) {
                    val i = row * 3 + col
                    val onLine = i in line
                    // Roles every brand theme sets (tertiary containers aren't): the
                    // player's line solid in the primary colour; the bard's in the
                    // secondary container, with a secondary border so it stands out
                    // where that container is close to the board's own colour.
                    val (fill, mark) = when {
                        onLine && result == TttMark.PLAYER -> colors.primary to colors.onPrimary
                        onLine && result == TttMark.BARD -> colors.secondaryContainer to colors.onSecondaryContainer
                        else -> colors.surfaceVariant to colors.secondary
                    }
                    val border = if (onLine && result == TttMark.BARD) BorderStroke(2.dp, colors.secondary) else null
                    Surface(
                        modifier = Modifier
                            .width(cell)
                            .aspectRatio(1f)
                            .testTag("ttt_cell_$i")
                            .semantics { if (onLine) stateDescription = winningCell }
                            .clickable { onTap(i) },
                        shape = RoundedCornerShape(8.dp),
                        color = fill,
                        border = border,
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            val glyph = board[i]
                            Text(
                                when (glyph) {
                                    TttMark.PLAYER -> "♪"
                                    TttMark.BARD -> "♫"
                                    TttMark.NONE -> ""
                                },
                                style = MaterialTheme.typography.headlineMedium,
                                color = when {
                                    // The bard's win: the player's marks turn grey as they wilt.
                                    result == TttMark.BARD && glyph == TttMark.PLAYER -> lerp(mark, colors.outline, p)
                                    else -> mark
                                },
                                modifier = Modifier.graphicsLayer {
                                    with(density) { endingMotion(result, glyph, onLine, p) }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Each mark's part in the ending, drawn by the layer alone: the cell, and
 * so the board, keeps its place and size. */
private fun androidx.compose.ui.graphics.GraphicsLayerScope.endingMotion(
    result: TttMark?, glyph: TttMark, onLine: Boolean, p: Float,
) {
    if (p == 0f) return
    when (result) {
        TttMark.PLAYER -> if (onLine) {
            // One pulse, in the first third.
            val pulse = 1f + PULSE_SCALE * sin(PI.toFloat() * (p / PULSE_END).coerceAtMost(1f))
            scaleX = pulse; scaleY = pulse
        }
        TttMark.BARD -> when {
            glyph == TttMark.PLAYER -> {
                rotationZ = WILT_DEGREES * p
                translationY = WILT_DROP_DP.dp.toPx() * p
            }
            onLine && p > BOUNCE_START ->
                translationY = -BOUNCE_DP.dp.toPx() * sin(PI.toFloat() * (p - BOUNCE_START) / (1f - BOUNCE_START))
        }
        TttMark.NONE -> alpha = 1f - DRAW_FADE * p
        null -> Unit
    }
}

/** About a dozen notes flying up and out from the winning line, fading as
 * they go: confetti made of notes, over the board. */
@Composable
private fun NoteBurst(line: List<Int>, p: Float, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val color = MaterialTheme.colorScheme.primary
    // Fixed per burst, so the notes don't jitter from frame to frame.
    val notes = remember(line) {
        val random = Random(line.sum())
        List(BURST_NOTES) { n ->
            Triple(line[n % line.size], -PI / 2 + (random.nextDouble() - 0.5) * 2.2, 0.6f + random.nextFloat() * 0.8f)
        }
    }
    Canvas(modifier) {
        val cellW = (size.width - 8.dp.toPx()) / 3
        val gap = 4.dp.toPx()
        notes.forEachIndexed { n, (cellIndex, angle, reach) ->
            val cx = (cellIndex % 3) * (cellW + gap) + cellW / 2
            val cy = (cellIndex / 3) * (cellW + gap) + cellW / 2
            val distance = reach * 90.dp.toPx() * (1f - (1f - p) * (1f - p))
            val glyph = if (n % 2 == 0) "♪" else "♫"
            drawText(
                measurer, glyph,
                topLeft = Offset(cx + (cos(angle) * distance).toFloat(), cy + (sin(angle) * distance).toFloat()),
                style = TextStyle(color = color.copy(alpha = (1f - p).coerceIn(0f, 1f)), fontSize = 22.sp),
            )
        }
    }
}
