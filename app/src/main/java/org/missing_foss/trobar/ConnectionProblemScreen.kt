// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.ui.platform.testTag

/** #34: shown when the app can't operate against its server. A 401
 * (unauthorized — the server no longer knows this device, e.g. after a
 * reinstall) offers Re-enroll; any other failure (unreachable / down) offers
 * Retry plus two escape hatches (#72) — editing the server address (same
 * server, new location: DDNS/port/proxy change, pairing preserved) or
 * re-pairing outright (genuinely different server) — without dropping the
 * pairing on Retry's own account. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionProblemCard(
    unauthorized: Boolean,
    serverUrl: String,
    onRetry: () -> Unit,
    onReEnroll: () -> Unit,
    onServerUrlChanged: (String) -> Unit,
) {
    var showServerDialog by remember { mutableStateOf(false) }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth().testTag("connection_problem"),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                if (unauthorized) Icons.Filled.Lock else Icons.Filled.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(if (unauthorized) R.string.conn_unauthorized_title else R.string.conn_unreachable_title),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(if (unauthorized) R.string.conn_unauthorized_body else R.string.conn_unreachable_body),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(12.dp))
            if (unauthorized) {
                Button(onClick = onReEnroll) { Text(stringResource(R.string.conn_re_enroll)) }
            } else {
                Button(onClick = onRetry) { Text(stringResource(R.string.conn_retry)) }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { showServerDialog = true }) {
                    Text(stringResource(R.string.conn_change_server))
                }
                TextButton(onClick = onReEnroll) { Text(stringResource(R.string.conn_re_pair)) }
            }
        }
    }

    if (showServerDialog) {
        EditValueDialog(
            title = stringResource(R.string.server_address_dialog_title),
            label = stringResource(R.string.url_label),
            initial = serverUrl,
            onDismiss = { showServerDialog = false },
            // The retry itself is NOT triggered from here — onServerUrlChanged
            // is responsible for retrying only once its own write actually
            // completes (see the Settings tab's wiring in AppShell). Firing onRetry() here as
            // well would race it: Prefs.setServerUrl is a suspend write, so an
            // immediate onRetry() could re-probe against the still-old
            // pairing.serverUrl, fail, and read as "the change didn't take"
            // even though it's about to.
            onConfirm = { url ->
                showServerDialog = false
                onServerUrlChanged(url.trim())
            },
            isValid = ::isServerUrl,
        )
    }
}
