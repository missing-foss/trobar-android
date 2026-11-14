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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject

// Every Trobar project's donation link.
const val SUPPORT_URL = "https://ko-fi.com/missingfoss"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    // How a link opens: the browser by default. A test passes its own to see
    // where a button goes without leaving the app.
    onOpenUrl: ((String) -> Unit)? = null,
) {
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
        if (onOpenUrl != null) return onOpenUrl(url)
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
            // the width it needs first, the support button was crushed in French. Both as
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
                // In the app's own theme: no Ko-fi logo, image or colour.
                Button(
                    onClick = { openUrl(SUPPORT_URL) },
                    modifier = Modifier.fillMaxWidth().testTag("donate"),
                ) {
                    Text(stringResource(R.string.support_button), textAlign = TextAlign.Center)
                }
            }
            Text(
                stringResource(R.string.support_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("support_note"),
            )
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
