// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/** The owner's playlist subscriptions, as the browser's section has them: a
 * public playlist added by its link -- pasted, or shared from another app
 * and waiting in the field for the owner to confirm -- then each one with
 * how its last fetch went, a refresh, and a removal that asks first,
 * since it takes the playlist it made with it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsScreen(
    pairing: Prefs.Pairing,
    sharedUrl: String?,
    onBack: () -> Unit,
    vm: SubscriptionsViewModel = viewModel(factory = SubscriptionsViewModel.factory(LocalContext.current, pairing, sharedUrl)),
) {
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var removing by remember { mutableStateOf<PlaylistSubscription?>(null) }
    state.error?.let { message ->
        LaunchedEffect(message) {
            snackbar.showSnackbar(message)
            vm.consumeError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.subs_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back_content_description))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        LazyColumn(Modifier.fillMaxSize().padding(innerPadding).testTag("subscriptions")) {
            item { AddByLink(state, vm) }
            if (!state.loading && state.subscriptions.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.subs_empty), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(state.subscriptions, key = { it.id }) { sub ->
                SubscriptionRow(sub, busy = state.busy, onRefresh = { vm.refresh(sub.id) }, onRemove = { removing = sub })
            }
        }
    }

    removing?.let { sub ->
        AlertDialog(
            onDismissRequest = { removing = null },
            text = { Text(stringResource(R.string.subs_remove_confirm, sub.title ?: sub.url)) },
            confirmButton = {
                TextButton(onClick = { vm.remove(sub.id); removing = null }) { Text(stringResource(R.string.subs_remove)) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel_button)) } },
        )
    }
}

/** The field, its button, the browser's note on what a subscription is,
 * and why the last add did not go through. */
@Composable
private fun AddByLink(state: SubscriptionsUiState, vm: SubscriptionsViewModel) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.url,
            onValueChange = vm::setUrl,
            label = { Text(stringResource(R.string.subs_add_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { vm.add() }),
            modifier = Modifier.fillMaxWidth().testTag("subscription_url"),
        )
        Button(onClick = vm::add, enabled = !state.busy && state.url.isNotBlank(), modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(if (state.busy) R.string.playlists_syncing else R.string.subs_add))
        }
        // The privacy promise stays in view where a link is pasted; the rest is
        // behind the info icon at its end. Not in the top bar: at 320 dp and
        // 2.0x the icon left the title too little room, and it broke mid-word.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.subs_privacy), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline, modifier = Modifier.weight(1f),
            )
            InfoButton(about = stringResource(R.string.subs_title), text = stringResource(R.string.subs_about))
        }
        state.addError?.let { e ->
            Text(
                e.message ?: stringResource(R.string.subs_add_failed),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun SubscriptionRow(sub: PlaylistSubscription, busy: Boolean, onRefresh: () -> Unit, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Glyph(sub.provider, providerName(sub.provider), PROVIDER_GLYPH_SIZE, MaterialTheme.colorScheme.outline)
                Text(sub.title ?: sub.url, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val (text, color) = subscriptionState(sub)
            if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.bodySmall, color = color)
        }
        OutlinedButton(onClick = onRefresh, enabled = !busy, modifier = Modifier.padding(start = 8.dp)) {
            Text(stringResource(R.string.subs_refresh))
        }
        TextButton(onClick = onRemove) {
            Text(stringResource(R.string.subs_remove), color = MaterialTheme.colorScheme.error)
        }
    }
}

/** How the last fetch went, in the browser's words and its three tones:
 * failed, fetched with nothing matched, or the matched count. Nothing
 * fetched yet says nothing. */
@Composable
private fun subscriptionState(sub: PlaylistSubscription): Pair<String, Color> = when {
    sub.lastError == "unavailable" -> stringResource(R.string.subs_unavailable) to MaterialTheme.colorScheme.error
    sub.lastError != null -> stringResource(
        when (sub.provider) {
            "spotify_public" -> R.string.subs_fetch_failed_spotify
            "tidal_public" -> R.string.subs_fetch_failed_tidal
            else -> R.string.subs_fetch_failed
        },
    ) to MaterialTheme.colorScheme.error
    sub.trackCount == null -> "" to MaterialTheme.colorScheme.outline
    sub.trackCount > 0 && sub.matchedCount == 0 -> stringResource(R.string.subs_no_matches) to MaterialTheme.colorScheme.tertiary
    else -> stringResource(R.string.subs_match_count, sub.matchedCount ?: 0, sub.trackCount) to MaterialTheme.colorScheme.outline
}
