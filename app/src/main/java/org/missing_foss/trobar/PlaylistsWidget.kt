// SPDX-FileCopyrightText: 2026 missing-foss
//
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** The playlists with the most left to sync to the header's device. A tap
 * opens one on the Playlists tab; a double-tap stages it, as the cover grids
 * do. The server filters to what this account may see. */
object PlaylistsWidget : Widget {
    override val id = WidgetIds.PLAYLISTS

    override suspend fun load(clients: WidgetClients, settings: WidgetSettings): Any {
        val device = clients.destination() ?: return emptyList<PlaylistToSync>()
        return clients.dashboard.playlistsToSync(device.id, settings.playlistsLimit)
    }

    @Composable
    override fun Content(data: Any, actions: WidgetActions) {
        @Suppress("UNCHECKED_CAST")
        val rows = data as List<PlaylistToSync>
        val device = actions.host.staging.destination?.name.orEmpty()
        if (rows.isEmpty()) {
            Text(
                stringResource(R.string.widget_playlists_nothing, device), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("widget_playlists_nothing"),
            )
            return
        }
        val stageLabel = stringResource(R.string.stage_action)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            rows.forEach { p ->
                Column(
                    Modifier.fillMaxWidth().testTag("widget_playlist_${p.id}").openOnTapStageOnDoubleTap(
                        stageLabel, onOpen = { actions.links.onOpenPlaylist(p.id) }, onStage = { from -> actions.links.onStagePlaylist(p.id, from) },
                    ),
                ) {
                    Text(p.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    LinearProgressIndicator(
                        progress = { p.availability },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                    Text(
                        pluralStringResource(R.plurals.widget_playlists_to_sync, p.toSync, p.toSync, device),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = actions.links.onOpenPlaylists) { Text(stringResource(R.string.widget_playlists_all)) }
        }
    }
}
