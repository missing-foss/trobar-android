// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.testTag
import kotlin.random.Random
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var updateAvailable by remember { mutableStateOf(false) }
    var shownDoc by remember { mutableStateOf<String?>(null) } // asset name, or null
    // Easter egg: 5 taps on the mark wakes the bard up for a duel. Resets on its
    // own after either a trigger or leaving the screen — no need to reset
    // the counter explicitly on a stray tap since it's just "taps so far",
    // never decremented.
    var bardTapCount by remember { mutableStateOf(0) }
    var showTicTacToe by remember { mutableStateOf(false) }

    fun openUrl(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (ignored: Exception) { /* no browser — nothing sensible to do */ }
    }

    // User-initiated only: one GitHub API call per button press,
    // never automatic — consistent with the no-telemetry stance.
    fun checkForUpdate() {
        scope.launch {
            checking = true
            updateStatus = context.getString(R.string.update_checking)
            updateAvailable = false
            updateStatus = try {
                val latest = withContext(Dispatchers.IO) {
                    val req = okhttp3.Request.Builder()
                        .url("https://api.github.com/repos/missing-foss/trobar-android/releases/latest")
                        .build()
                    OkHttpClient().newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
                        org.json.JSONObject(resp.body!!.string()).optString("tag_name")
                    }
                }
                // Drop any leading prefix rather than one literal string: a parser
                // that only survives its own repository's convention breaks the
                // first time that convention changes, and it breaks silently by
                // reporting an update that is not there.
                val latestVersion = latest.dropWhile { !it.isDigit() }
                if (latestVersion.isNotEmpty() && latestVersion != appVersionName(context)) {
                    updateAvailable = true
                    context.getString(R.string.update_available, latest)
                } else {
                    context.getString(R.string.update_up_to_date, appVersionName(context))
                }
            } catch (e: Exception) {
                context.getString(R.string.update_check_failed, e.message ?: "?")
            }
            checking = false
        }
    }

    shownDoc?.let { asset ->
        AlertDialog(
            onDismissRequest = { shownDoc = null },
            title = {
                Text(stringResource(
                    if (asset == "LICENSE") R.string.license_dialog_title else R.string.notices_dialog_title))
            },
            text = {
                val docText = remember(asset) { readAsset(context, asset) }
                // heightIn first (bounds the viewport), THEN verticalScroll —
                // the reverse order just truncates the text with nothing to
                // scroll. fillMaxWidth so long license lines wrap.
                Text(
                    docText,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { shownDoc = null }) { Text(stringResource(R.string.close_button)) }
            },
        )
    }

    if (showTicTacToe) {
        TicTacToeDialog(onDismiss = { showTicTacToe = false })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back_content_description))
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .then(adaptiveContentWidth())
                .fillMaxHeight()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppLogo(
                size = 84.dp,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null, // a growing tap streak shouldn't visibly ripple/hint before it's earned
                ) {
                    bardTapCount++
                    if (bardTapCount >= EASTER_EGG_TAP_THRESHOLD) {
                        bardTapCount = 0
                        showTicTacToe = true
                    }
                },
            )
            Text(trobarWordmark(), fontFamily = FredokaFamily, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.about_version, appVersionName(context)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )

            // Stacked, not side by side: beside the update button, which takes
            // the width it needs first, Donate was crushed in French. Both as
            // wide as the wider label, so they read as a pair.
            Column(
                Modifier.width(IntrinsicSize.Max),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { checkForUpdate() }, enabled = !checking,
                    modifier = Modifier.fillMaxWidth().testTag("check_updates"),
                ) {
                    Text(stringResource(R.string.check_updates_button), textAlign = TextAlign.Center)
                }
                // Liberapay's brand yellow — a vendored "button", no external
                // widget script (a deliberate no-CDN choice).
                val liberapayYellow = Color(0xFFF6C915)
                val liberapayInk = Color(0xFF1A171B)
                Button(
                    onClick = { openUrl("https://liberapay.com/Trobar/donate") },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = liberapayYellow, contentColor = liberapayInk),
                    modifier = Modifier.fillMaxWidth().testTag("donate"),
                ) {
                    Text(stringResource(R.string.donate_button), textAlign = TextAlign.Center)
                }
            }
            updateStatus?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (updateAvailable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                )
            }

            SectionLabel(stringResource(R.string.about_links_section))
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                TileRow(Icons.Filled.Info, stringResource(R.string.about_documentation), "",
                    onClick = { openUrl("https://missing-foss.github.io/trobar-server/") })
                HorizontalDivider(modifier = Modifier.padding(start = 54.dp))
                TileRow(Icons.Filled.Cloud, stringResource(R.string.about_source_code), "",
                    onClick = { openUrl("https://github.com/missing-foss/trobar-android") })
                HorizontalDivider(modifier = Modifier.padding(start = 54.dp))
                TileRow(Icons.Filled.Settings, stringResource(R.string.about_report_issue), "",
                    onClick = { openUrl("https://github.com/missing-foss/trobar-android/issues/new/choose") })
                HorizontalDivider(modifier = Modifier.padding(start = 54.dp))
                TileRow(Icons.Filled.Storage, stringResource(R.string.about_releases), "",
                    onClick = { openUrl("https://github.com/missing-foss/trobar-android/releases") })
            }

            SectionLabel(stringResource(R.string.about_licenses_section))
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.about_license_summary), style = MaterialTheme.typography.bodySmall)
                    // The second button takes the next line when both don't
                    // fit, instead of being squeezed until it breaks mid-word.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { shownDoc = "LICENSE" }) {
                            Text(stringResource(R.string.show_license_button))
                        }
                        OutlinedButton(onClick = { shownDoc = "THIRD_PARTY_NOTICES.md" }) {
                            Text(stringResource(R.string.show_notices_button))
                        }
                    }
                }
            }
        }
        }
    }
}

/** Easter egg (5 taps on the mark, About screen). Human plays one note
 * glyph, the bard answers with the other one, rather than plain X/O. The
 * bard plays to win-or-block first, otherwise centre/corner/edge — beatable on purpose
 * (see the issue: "doesn't need to be unbeatable"), not a minimax grind. */
internal enum class TttMark { NONE, PLAYER, BARD }

internal val TTT_LINES = listOf(
    listOf(0, 1, 2), listOf(3, 4, 5), listOf(6, 7, 8), // rows
    listOf(0, 3, 6), listOf(1, 4, 7), listOf(2, 5, 8), // columns
    listOf(0, 4, 8), listOf(2, 4, 6),                  // diagonals
)

internal fun tttWinner(board: List<TttMark>): TttMark? {
    for ((a, b, c) in TTT_LINES) {
        if (board[a] != TttMark.NONE && board[a] == board[b] && board[a] == board[c]) return board[a]
    }
    return null
}

/** Never misses an outright win (that would just feel broken) — but
 * blocking and positioning are deliberately imperfect, a musician playing
 * by ear rather than a calculator. A strict win/block/centre-corner-edge
 * heuristic (the first version of this) is *technically* beatable, but
 * only via a specific forcing sequence (opposite corners, then a fork) a
 * casual player has no reason to know — in ordinary play it draws or wins
 * essentially every time, which isn't the "doesn't need to be unbeatable"
 * the issue asked for. So: only a 70% chance to block a real threat, and
 * a genuinely random cell (not the strong centre-first preference) when
 * there's no win or block to make — real, frequent openings to win
 * without needing to already know the trick. */
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

@Composable
internal fun TicTacToeDialog(onDismiss: () -> Unit) {
    var board by remember { mutableStateOf(List(TTT_BOARD_SIZE) { TttMark.NONE }) }
    var result by remember { mutableStateOf<TttMark?>(null) } // null = still playing; NONE = draw

    fun reset() {
        board = List(TTT_BOARD_SIZE) { TttMark.NONE }
        result = null
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
        next = bardMove(next).toMutableList()
        board = next
        result = tttWinner(next) ?: if (next.none { it == TttMark.NONE }) TttMark.NONE else null
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.tictactoe_title),
                    fontFamily = FredokaFamily,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleLarge,
                )
                // #38: a roomier board on tablets / unfolded foldables.
                val cell = if (isCompactWidth()) 64.dp else 88.dp
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (row in 0..2) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (col in 0..2) {
                                val i = row * 3 + col
                                Surface(
                                    modifier = Modifier
                                        .width(cell)
                                        .aspectRatio(1f)
                                        .clickable { playerTap(i) },
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                ) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                        Text(
                                            when (board[i]) {
                                                TttMark.PLAYER -> "♪" // ♪
                                                TttMark.BARD -> "♫"   // ♫
                                                TttMark.NONE -> ""
                                            },
                                            style = MaterialTheme.typography.headlineMedium,
                                            color = MaterialTheme.colorScheme.secondary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                result?.let {
                    Text(
                        stringResource(
                            when (it) {
                                TttMark.PLAYER -> R.string.tictactoe_player_win
                                TttMark.BARD -> R.string.tictactoe_bard_win
                                TttMark.NONE -> R.string.tictactoe_draw
                            }
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (result != null) {
                        OutlinedButton(onClick = { reset() }) { Text(stringResource(R.string.tictactoe_play_again)) }
                    }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.close_button)) }
                }
            }
        }
    }
}
