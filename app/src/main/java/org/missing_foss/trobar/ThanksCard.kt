// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
// The one-time thank-you card on Home (full and simple mode): shown from the
// 50th sync this install finished with no error (Prefs.thanksCardShown), and
// gone for good after either of its buttons.

package org.missing_foss.trobar

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** The card when Prefs says it's due, else nothing. [onOpenUrl] stands in for
 * the browser in tests, as on the About screen. */
@Composable
fun ThanksCardIfDue(modifier: Modifier = Modifier, onOpenUrl: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    val shown by Prefs.thanksCardShown(context).collectAsState(initial = false)
    if (!shown) return
    val scope = rememberCoroutineScope()
    val done: () -> Unit = { scope.launch { Prefs.setThanksCardDone(context) } }
    ThanksCard(
        modifier = modifier,
        onDone = done,
        onSupport = {
            if (onOpenUrl != null) {
                onOpenUrl(SUPPORT_URL)
            } else {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SUPPORT_URL)))
                } catch (ignored: Exception) { /* no browser */ }
            }
        },
    )
}

@Composable
fun ThanksCard(onDone: () -> Unit, onSupport: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth().testTag("thanks_card")) {
        Box {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    buildAnnotatedString {
                        append(stringResource(R.string.thanks_card_thank))
                        append(' ')
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.secondary)) {
                            append(stringResource(R.string.thanks_card_you))
                        }
                    },
                    fontFamily = FredokaFamily,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp).testTag("thanks_card_heading"),
                )
                HorizontalDivider()
                Text(
                    stringResource(R.string.thanks_card_synced, Prefs.THANKS_CARD_SYNCS) + "\n" +
                        stringResource(R.string.thanks_card_coffee),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("thanks_card_text"),
                )
                Button(
                    onClick = {
                        onSupport()
                        onDone()
                    },
                    modifier = Modifier.align(Alignment.CenterHorizontally).testTag("thanks_card_kofi"),
                ) {
                    Text(stringResource(R.string.support_button))
                }
            }
            IconButton(
                onClick = onDone,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).testTag("thanks_card_dismiss"),
            ) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.thanks_card_dismiss))
            }
        }
    }
}
