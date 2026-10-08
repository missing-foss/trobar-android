// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.first

/** The basket: what is staged, one section per destination device, and
 * the per-device Send that turns a section into selections. Deliberately
 * no "send everything": a send is a decision about one device.
 *
 * The part the browser cannot do: after a send whose target was this
 * phone, start the sync, and say when the sync setting would hold it back
 * rather than let it wait silently. [onSyncNow] starts the sync where the
 * user is; [onShowSync] shows it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BasketScreen(
    pairing: Prefs.Pairing,
    onBack: () -> Unit,
    onSyncNow: () -> Unit,
    onShowSync: () -> Unit,
    shared: AppState,
    vm: BasketViewModel = viewModel(factory = BasketViewModel.factory(LocalContext.current, pairing, shared)),
) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var confirmClear by remember { mutableStateOf(false) }
    val art = remember(pairing) { BasketArt(pairing, AppApiClient(context, pairing.serverUrl, pairing.token)) }

    state.error?.let { message ->
        LaunchedEffect(message) {
            snackbar.showSnackbar(message)
            vm.consumeError()
        }
    }

    state.lastSend?.let { sent -> SendOutcomeEffect(sent, snackbar, onSyncNow, onShowSync, vm::consumeSend) }

    Scaffold(
        topBar = { BasketTopBar(state.itemCount, onBack, onClear = { confirmClear = true }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when {
                state.loading && state.sections.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.sections.isEmpty() -> Text(
                    stringResource(R.string.basket_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    state.sections.forEach { section ->
                        item(key = "device-${section.device.id}") {
                            SectionHeader(section, isThisPhone = section.device.id == state.thisDeviceId, onSend = { vm.send(section) })
                        }
                        items(section.items, key = { "item-${section.device.id}-${it.id}" }) { item ->
                            BasketRow(
                                item = item,
                                deviceName = section.device.name,
                                art = art,
                                onUnstage = { vm.unstage(item.id, section.device.id) },
                                onRemove = { vm.removeItem(item.id) },
                            )
                        }
                        item { HorizontalDivider() }
                    }
                    // How sending works, behind an info icon where the footer was. Not in
                    // the top bar: at 320 dp and 2.0x it squeezed the title mid-word.
                    item {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp), contentAlignment = Alignment.CenterEnd) {
                            InfoButton(about = stringResource(R.string.basket_title), text = stringResource(R.string.basket_footer))
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.basket_clear)) },
            text = { Text(stringResource(R.string.basket_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; vm.clear() }) { Text(stringResource(R.string.basket_clear_confirm_button)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel_button)) } },
        )
    }
}

/** What follows a send: the result in a snackbar, always -- it says what
 * could not be sent and stays in the basket. When the target was this
 * phone, the sync too: started at once, said so, and shown on request; or,
 * when the Sync via setting would only hold it, the reason instead of a
 * start that would not happen. */
@Composable
internal fun SendOutcomeEffect(
    sent: SendOutcome,
    snackbar: SnackbarHostState,
    onSyncNow: () -> Unit,
    onShowSync: () -> Unit,
    onConsumed: () -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(sent) {
        val devices = sent.deviceNames.joinToString(", ")
        var text = context.resources.getQuantityString(R.plurals.basket_sent, sent.result.count, sent.result.count, devices)
        if (sent.result.skipped > 0) {
            text += " " + context.resources.getQuantityString(R.plurals.basket_sent_skipped, sent.result.skipped, sent.result.skipped)
        }
        if (!sent.toThisPhone) {
            snackbar.showSnackbar(text)
        } else {
            val blocked = SyncConditions.blockingReason(context, Prefs.networkMode(context).first())
            if (blocked != null) {
                snackbar.showSnackbar(text + " " + context.getString(blocked))
            } else {
                onSyncNow()
                val choice = snackbar.showSnackbar(
                    text + " " + context.getString(R.string.basket_sync_started),
                    actionLabel = context.getString(R.string.basket_show_sync),
                )
                if (choice == SnackbarResult.ActionPerformed) onShowSync()
            }
        }
        onConsumed()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BasketTopBar(itemCount: Int, onBack: () -> Unit, onClear: () -> Unit) {
    TopAppBar(
        title = {
            Text(
                if (itemCount == 0) stringResource(R.string.basket_title)
                else pluralStringResource(R.plurals.basket_title_count, itemCount, itemCount),
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back_content_description))
            }
        },
        actions = {
            if (itemCount > 0) TextButton(onClick = onClear) { Text(stringResource(R.string.basket_clear)) }
        },
    )
}

/** The device, the "this phone" marker and the count, then the send
 * button: on a phone on its own line, since a device name is the user's
 * own and can be long; beside them on a wider window. The name gives way
 * (ellipsised) before the marker does. The button's label is short, its
 * accessibility label names the device. */
@Composable
private fun SectionHeader(section: BasketSection, isThisPhone: Boolean, onSend: () -> Unit) {
    val compact = isCompactWidth()
    val sendDescription = stringResource(R.string.basket_send_device, section.device.name)
    val send = @Composable {
        Button(onClick = onSend, modifier = Modifier.semantics { contentDescription = sendDescription }) {
            Text(stringResource(R.string.basket_send), modifier = Modifier.clearAndSetSemantics {})
        }
    }
    Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        section.device.name, style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isThisPhone) {
                        Text(
                            stringResource(R.string.basket_this_phone),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                Text(
                    pluralStringResource(R.plurals.basket_section_count, section.items.size, section.items.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            if (!compact) send()
        }
        if (compact) {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.CenterEnd) { send() }
        }
    }
}

@Composable
private fun BasketRow(item: BasketItem, deviceName: String, art: BasketArt, onUnstage: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The same square for every row, art or not, so a mixed basket keeps one row height.
        Box(Modifier.size(ROW_ART_SIZE).clip(RoundedCornerShape(4.dp)).testTag("basket_art")) {
            TrobarImage(url = art.url(item), contentDescription = null, pairing = art.pairing, modifier = Modifier.fillMaxSize())
        }
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                item.title ?: item.target,
                style = MaterialTheme.typography.bodyLarge,
                color = if (item.missing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                if (item.missing) stringResource(R.string.basket_item_missing) else typeLabel(item),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.basket_item_actions, item.title ?: item.target))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.basket_unstage_from, deviceName)) },
                    onClick = { menu = false; onUnstage() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.basket_remove_item)) },
                    onClick = { menu = false; onRemove() },
                )
            }
        }
    }
}

/** Where a row's picture comes from: an album's cover, an artist's small
 * picture; a playlist, a track or a missing item has none and shows the
 * image fallback in the same square. */
class BasketArt(val pairing: Prefs.Pairing, private val urls: AppApiClient) {
    fun url(item: BasketItem): String? = when {
        item.missing -> null
        item.type == "album" && "||" in item.target -> urls.coverUrl(item.target.substringBefore("||"), item.target.substringAfter("||"))
        item.type == "artist" -> urls.artistImageUrl(item.target, small = true)
        else -> null
    }
}

private val ROW_ART_SIZE = 48.dp

/** "Artist · album" for an album target, else the type alone; the target
 * string is the server's key and stays out of sight unless there is no title. */
@Composable
private fun typeLabel(item: BasketItem): String = when (item.type) {
    "album" -> item.target.substringBefore("||") + " · " + stringResource(R.string.basket_type_album)
    "artist" -> stringResource(R.string.basket_type_artist)
    "playlist" -> stringResource(R.string.basket_type_playlist)
    "track" -> stringResource(R.string.basket_type_track)
    else -> item.type
}
