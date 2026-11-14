// SPDX-FileCopyrightText: 2026 missing-foss
//
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.IOException

/** A selection waiting for the owner to confirm its removal, with what the
 * next sync would delete. */
data class PendingRemoval(val selection: Selection, val tracks: Int)

data class SelectionsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val device: AppDevice? = null,
    val selections: List<Selection> = emptyList(),
    val confirm: PendingRemoval? = null,
) {
    /** Grouped by type, in the order the mock shows them. */
    val groups: List<Pair<String, List<Selection>>>
        get() = TYPES.mapNotNull { type -> selections.filter { it.type == type }.takeIf { it.isNotEmpty() }?.let { type to it } }

    private companion object {
        val TYPES = listOf("artist", "album", "playlist", "track")
    }
}

/** What is selected for one device, and taking it off that device. */
class SelectionsViewModel(
    private val api: AppApiClient,
    private val deviceId: Long,
    /** Told after a selection is removed, with the device it was on. */
    private val onRemoved: (Long) -> Unit = {},
) : ViewModel() {
    private val _state = MutableStateFlow(SelectionsUiState())
    val state: StateFlow<SelectionsUiState> = _state

    init {
        load()
    }

    private suspend fun <T> attempt(block: () -> T, onDone: (T) -> Unit) {
        try {
            onDone(withContext(Dispatchers.IO) { block() })
        } catch (e: IOException) {
            _state.update { it.copy(loading = false, error = e.message) }
        } catch (e: JSONException) {
            _state.update { it.copy(loading = false, error = e.message) }
        }
    }

    fun load() {
        viewModelScope.launch {
            attempt({ api.devices() to api.selections() }) { (devices, selections) ->
                _state.update { s ->
                    s.copy(
                        loading = false,
                        device = devices.firstOrNull { it.id == deviceId },
                        selections = selections.filter { deviceId in it.deviceIds },
                    )
                }
            }
        }
    }

    /** Asks what removing it would delete, then asks the owner. */
    fun askToRemove(selection: Selection) {
        viewModelScope.launch {
            attempt({ api.removalCount(deviceId, selection.type, selection.target) }) { tracks ->
                _state.update { it.copy(confirm = PendingRemoval(selection, tracks)) }
            }
        }
    }

    fun cancelRemoval() = _state.update { it.copy(confirm = null) }

    fun confirmRemoval() {
        val selection = _state.value.confirm?.selection ?: return
        _state.update { it.copy(confirm = null) }
        viewModelScope.launch {
            attempt({ api.removeSelection(deviceId, selection.type, selection.target) }) { onRemoved(deviceId) }
            // Reloaded after a refusal too: the owner's part may have gone
            // while someone else's keeps it on the device.
            load()
        }
    }

    fun consumeError() = _state.update { it.copy(error = null) }

    companion object {
        fun factory(
            context: Context,
            pairing: Prefs.Pairing,
            deviceId: Long,
            onRemoved: (Long) -> Unit = {},
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    SelectionsViewModel(
                        AppApiClient(context.applicationContext, pairing.serverUrl, pairing.token), deviceId, onRemoved,
                    )
                }
            }
    }
}

/** What a selection is called here; one whose playlist or track has gone
 * says so rather than showing the server's key. */
@Composable
private fun selectionTitle(s: Selection): String {
    val title = s.title ?: stringResource(R.string.basket_item_missing)
    return if (s.appearsOn != null) stringResource(R.string.selection_artist_on_album, s.target.substringBefore("||"), title) else title
}

/** The line under the title: an album's artist and how much of it this is,
 * so one artist's tracks aren't taken for the whole album. */
@Composable
private fun selectionSubtitle(s: Selection): String? {
    if (s.type != "album") return null
    val artist = s.target.substringBefore("||")
    val on = s.appearsOn
    val whole = s.wholeAlbumTracks
    return when {
        on != null -> pluralStringResource(R.plurals.selection_tracks_of, on.tracks, on.tracks, on.of)
        whole != null -> "$artist · " + pluralStringResource(R.plurals.selection_whole_album, whole, whole)
        else -> artist
    }
}

@Composable
private fun groupLabel(type: String): String = stringResource(
    when (type) {
        "artist" -> R.string.selections_artists
        "album" -> R.string.selections_albums
        "playlist" -> R.string.selections_playlists
        else -> R.string.selections_tracks
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionsScreen(
    pairing: Prefs.Pairing,
    deviceId: Long,
    onBack: () -> Unit,
    onRemoved: (Long) -> Unit = {},
    vm: SelectionsViewModel = viewModel(factory = SelectionsViewModel.factory(LocalContext.current, pairing, deviceId, onRemoved)),
) {
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    state.error?.let { message ->
        LaunchedEffect(message) {
            snackbar.showSnackbar(message)
            vm.consumeError()
        }
    }
    val name = state.device?.name.orEmpty()
    state.confirm?.let { pending ->
        AlertDialog(
            onDismissRequest = vm::cancelRemoval,
            title = { Text(stringResource(R.string.selections_remove_title, selectionTitle(pending.selection), name)) },
            text = {
                Text(
                    pluralStringResource(R.plurals.selections_remove_body, pending.tracks, pending.tracks),
                    modifier = Modifier.testTag("selection_remove_body"),
                )
            },
            confirmButton = {
                TextButton(onClick = vm::confirmRemoval, modifier = Modifier.testTag("selection_remove_confirm")) {
                    Text(stringResource(R.string.selections_remove))
                }
            },
            dismissButton = { TextButton(onClick = vm::cancelRemoval) { Text(stringResource(R.string.cancel_button)) } },
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.selections_title, name)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back_content_description))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> SelectionsList(state, vm)
            }
        }
    }
}

@Composable
private fun SelectionsList(state: SelectionsUiState, vm: SelectionsViewModel) {
    val autofit = state.device?.autofit?.takeIf { it.enabled }
    if (state.groups.isEmpty() && autofit == null) {
        Text(
            stringResource(R.string.selections_empty), style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp).testTag("selections_empty"),
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize().testTag("selections_list")) {
        state.groups.forEach { (type, selections) ->
            item(key = "group-$type") { SectionLabel(groupLabel(type)) }
            items(selections, key = { it.id }) { s ->
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(selectionTitle(s), style = MaterialTheme.typography.bodyLarge)
                        selectionSubtitle(s)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    TextButton(onClick = { vm.askToRemove(s) }, modifier = Modifier.testTag("selection_remove_${s.id}")) {
                        Text(stringResource(R.string.selections_remove))
                    }
                }
            }
        }
        autofit?.let {
            item(key = "autofit") {
                SectionLabel(stringResource(R.string.selections_autofit))
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("selections_autofit")) {
                    Text(pluralStringResource(R.plurals.library_tracks_count, it.tracks, it.tracks), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.selections_autofit_note), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
    )
}
