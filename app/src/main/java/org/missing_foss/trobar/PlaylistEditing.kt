// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/** A name for a new playlist: nothing is created until there is one. */
@Composable
internal fun NewPlaylistDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    EditValueDialog(
        title = stringResource(R.string.playlist_new),
        label = stringResource(R.string.playlist_new_hint),
        initial = "",
        onDismiss = onDismiss,
        onConfirm = { onCreate(it.trim()) },
        isValid = { it.isNotBlank() },
    )
}

/** Rename, and a Delete that doesn't look like it: it asks first, and always
 * says mirrors go too, since the app can't see whether there are any. */
@Composable
internal fun PlaylistEditActions(p: AppPlaylist, vm: PlaylistsViewModel) {
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("playlist_edit_actions"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = { renaming = true }) { Text(stringResource(R.string.playlist_rename)) }
        TextButton(
            onClick = { deleting = true },
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text(stringResource(R.string.playlist_delete)) }
    }
    if (renaming) {
        EditValueDialog(
            title = stringResource(R.string.playlist_rename),
            label = stringResource(R.string.playlist_new_hint),
            initial = p.title,
            onDismiss = { renaming = false },
            onConfirm = {
                renaming = false
                vm.rename(it.trim())
            },
            isValid = { it.isNotBlank() },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.playlist_delete_title, p.title)) },
            text = { Text(stringResource(R.string.playlist_delete_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = false
                        vm.delete()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.playlist_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.cancel_button)) } },
        )
    }
}

/** What a playlist with nothing on it looks like, and how to fill it. */
@Composable
internal fun EmptyPlaylist(onOpenLibrary: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(24.dp).testTag("playlist_empty"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.playlist_empty), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onOpenLibrary, modifier = Modifier.padding(top = 12.dp)) {
            Text(stringResource(R.string.widget_view_library))
        }
    }
}

/** What one row of an editable playlist can do. */
internal class EntryEdit(
    val first: Boolean,
    val last: Boolean,
    val onMove: (up: Boolean) -> Unit,
    val onRemove: () -> Unit,
    val handle: Modifier,
)

/** An entry that swipes away, either way, with its handle on the right. A
 * drag from the handle only ever reorders: it takes the gesture first.
 * TalkBack can do neither, so removing is a custom action and the arrows
 * move, as on the Home widget screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditableEntry(e: PlaylistEntry, edit: EntryEdit, onTap: (() -> Unit)?, dragged: Modifier) {
    val title = e.title ?: stringResource(R.string.playlists_untitled_entry)
    val removeLabel = stringResource(R.string.playlist_remove_track, title)
    val swipe = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) edit.onRemove()
            value != SwipeToDismissBoxValue.Settled
        },
    )
    SwipeToDismissBox(
        state = swipe,
        modifier = dragged.semantics {
            customActions = listOf(CustomAccessibilityAction(removeLabel) { edit.onRemove(); true })
        }.testTag("playlist_entry_${e.position}"),
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 16.dp),
                contentAlignment = if (swipe.dismissDirection == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd,
            ) { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer) }
        },
    ) {
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
                .then(if (onTap != null) Modifier.clickable(role = Role.Button, onClick = onTap) else Modifier)
                .padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) { EntryLines(e) }
            IconButton(onClick = { edit.onMove(true) }, enabled = !edit.first) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.playlist_move_up, title))
            }
            IconButton(onClick = { edit.onMove(false) }, enabled = !edit.last) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.playlist_move_down, title))
            }
            // Drag to reorder. Left out of TalkBack, which cannot drag.
            Icon(
                Icons.Filled.DragIndicator, contentDescription = null, tint = MaterialTheme.colorScheme.outline,
                modifier = edit.handle.padding(8.dp).size(24.dp).clearAndSetSemantics { testTag = "playlist_drag_${e.position}" },
            )
        }
    }
}

/** How a row lifts while it is dragged, and steps aside for one that is. */
internal fun Modifier.followingDrag(drag: RowDrag, key: String, index: Int, order: List<String>): Modifier {
    val isDragged = drag.id == key
    return zIndex(if (isDragged) 1f else 0f)
        .graphicsLayer { translationY = if (isDragged) drag.offset else drag.shiftFor(index, order) }
}
