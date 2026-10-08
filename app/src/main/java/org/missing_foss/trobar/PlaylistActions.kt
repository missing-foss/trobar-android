// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// What an open playlist lets its owner do beyond staging it: share it with
// the household or not, review the entries the library has no match for,
// and ask Lidarr for the missing albums. As the browser has them, kept to a
// plain list, a confirm and two switches.

/** Shared with the household, or private, as a switch with the browser's
 * words. Making it private asks first, which the browser's toggle does not:
 * the server deletes every other member's selection of it -- an admin's
 * excepted -- and their devices remove its tracks at their next sync. */
@Composable
internal fun Sharing(p: AppPlaylist, busy: Boolean, onToggle: () -> Unit) {
    var asking by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("playlist_sharing")) {
        Row(
            Modifier.fillMaxWidth().toggleable(value = p.shared, enabled = !busy, role = Role.Switch) {
                if (p.shared) asking = true else onToggle()
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val label = stringResource(if (p.shared) R.string.sharing_shared else R.string.sharing_private)
            LabelWithInfo(label, stringResource(if (p.shared) R.string.sharing_shared_hint else R.string.sharing_private_hint), Modifier.weight(1f))
            Switch(checked = p.shared, onCheckedChange = null, enabled = !busy)
        }
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            text = { Text(stringResource(R.string.sharing_unshare_confirm, p.title)) },
            confirmButton = { TextButton(onClick = { onToggle(); asking = false }) { Text(stringResource(R.string.sharing_make_private)) } },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(stringResource(R.string.cancel_button)) } },
        )
    }
}

/** The Lidarr requests switch with what the browser says beside it: why it
 * cannot be turned on when it cannot, what it does when off or on, and how
 * the last run went. On stays switchable off while Lidarr is set up. */
@Composable
internal fun LidarrRequests(p: AppPlaylist, busy: Boolean, onToggle: () -> Unit) {
    val l = p.lidarr
    val on = l.run.enabled
    // Shown only where Lidarr is set up (see PlaylistDetail): what can still
    // stop it is nothing to ask for -- no gaps at all, or gaps whose source
    // gives no album.
    val blocked = when {
        on || l.hasAlbums -> null
        p.unresolvedCount == 0 -> R.string.lidarr_nothing_missing
        else -> R.string.lidarr_no_album_data
    }
    val enabled = !busy && blocked == null
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("lidarr_requests")) {
        Row(
            Modifier.fillMaxWidth().toggleable(value = on, enabled = enabled, role = Role.Switch) { onToggle() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val label = stringResource(if (on) R.string.lidarr_requesting else R.string.lidarr_request_to)
            LabelWithInfo(label, stringResource(if (on) R.string.lidarr_on_hint else R.string.lidarr_off_hint), Modifier.weight(1f))
            Switch(checked = on, onCheckedChange = null, enabled = enabled)
        }
        // Why it cannot be switched on reports a state, so it stays in view.
        if (blocked != null) {
            Text(stringResource(blocked), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        if (on) {
            val error = lidarrErrorText(l.run)
            when {
                error != null -> Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                l.run.lastRunAt != null -> Text(
                    stringResource(R.string.lidarr_last_run, l.run.lastCount ?: 0, relativeTime(l.run.lastRunAt)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/** The last run's failure as the browser words it: a target that is not
 * set says so as the setup hint does; a partial or failed run, its prefix
 * and the server's detail; anything else, the detail alone. Null when the
 * run went through. */
@Composable
private fun lidarrErrorText(run: LidarrRun): String? {
    if (run.lastErrorCode == null && run.lastError == null) return null
    if (run.lastErrorCode == "unset_target") return stringResource(R.string.lidarr_not_configured)
    val prefix = when (run.lastErrorCode) {
        "partial" -> stringResource(R.string.lidarr_error_partial)
        "failed" -> stringResource(R.string.lidarr_error_failed)
        else -> null
    }
    val detail = run.lastError.orEmpty()
    return if (prefix == null) detail else listOf(prefix, detail).filter { it.isNotEmpty() }.joinToString(" ")
}

/** The review of the open playlist's unmatched entries, in place of its
 * entries: the browser's heading and help, then each one with its toggle,
 * and "Exclude all", which asks first. */
internal fun LazyListScope.reviewItems(review: Review, onClose: () -> Unit, onExclude: (ids: List<Long>, excluded: Boolean) -> Unit) {
    item {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("unresolved_review")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LabelWithInfo(
                    stringResource(R.string.review_title), stringResource(R.string.review_help), Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                )
                TextButton(onClick = onClose) { Text(stringResource(R.string.review_close)) }
            }
            if (review.failed) {
                Text(
                    stringResource(R.string.review_error), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
    when {
        review.loading -> item { Box(Modifier.fillMaxWidth().padding(32.dp)) { CircularProgressIndicator(Modifier.align(Alignment.Center)) } }
        review.rows.isEmpty() && !review.failed -> item {
            Text(
                stringResource(R.string.review_empty), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(16.dp),
            )
        }
        else -> {
            items(review.rows, key = { it.id }) { row -> ReviewRow(row, busy = review.busy, onExclude = onExclude) }
            val open = review.rows.filterNot { it.excluded }.map { it.id }
            if (open.isNotEmpty()) item { ExcludeAll(open, busy = review.busy, onExclude = onExclude) }
        }
    }
}

@Composable
private fun ReviewRow(row: UnresolvedEntry, busy: Boolean, onExclude: (List<Long>, Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        // The browser's label: what is known of it, most general first. An
        // album the source didn't give says where it came from; the server
        // keeps a missing album as "", which is as good as none.
        val album = row.album.orBlankNull()
            ?: row.inferredAlbum.orBlankNull()?.let { stringResource(R.string.review_album_inferred, it) }
        Text(
            listOfNotNull(row.artist.orBlankNull(), album, row.title.orBlankNull()).joinToString(" — "),
            style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = if (row.excluded) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
            textDecoration = if (row.excluded) TextDecoration.LineThrough else null,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = { onExclude(listOf(row.id), !row.excluded) }, enabled = !busy, modifier = Modifier.padding(start = 8.dp)) {
            Text(stringResource(if (row.excluded) R.string.review_excluded else R.string.review_exclude))
        }
    }
}

private fun String?.orBlankNull(): String? = this?.takeIf { it.isNotBlank() }

@Composable
private fun ExcludeAll(ids: List<Long>, busy: Boolean, onExclude: (List<Long>, Boolean) -> Unit) {
    var asking by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
        Button(onClick = { asking = true }, enabled = !busy) { Text(stringResource(R.string.review_exclude_all)) }
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            text = { Text(pluralStringResource(R.plurals.review_exclude_all_confirm, ids.size, ids.size)) },
            confirmButton = {
                TextButton(onClick = { onExclude(ids, true); asking = false }) { Text(stringResource(R.string.review_exclude_all)) }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(stringResource(R.string.cancel_button)) } },
        )
    }
}
