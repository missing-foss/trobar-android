// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.columnModel
import com.patrykandpatrick.vico.compose.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import java.io.IOException

/** The four widgets that are not cover grids: the library's numbers and
 * two charts, the devices with their storage, the most-played chart, and
 * the administration counts. Charts are drawn by Vico under its Material 3
 * theme, so their colours are the app's in dark mode and under dynamic
 * colour. */

/** The library widget's data: the stats route plus the artist list's two counts. */
class LibrarySummary(val stats: LibraryStats, val artistCount: Int, val albumCount: Int)

object LibraryStatsWidget : Widget {
    override val id = WidgetIds.LIBRARY
    override suspend fun load(clients: WidgetClients, settings: WidgetSettings): Any {
        val artists = clients.app.libraryArtists()
        return LibrarySummary(clients.dashboard.libraryStats(), artists.size, artists.sumOf { it.albumCount })
    }

    @Composable
    override fun Content(data: Any, actions: WidgetActions) {
        val summary = data as LibrarySummary
        Column(Modifier.testTag("widget-library-content")) {
            Text(
                pluralStringResource(R.plurals.library_artists_count, summary.artistCount, summary.artistCount) + " · " +
                    pluralStringResource(R.plurals.library_albums_count, summary.albumCount, summary.albumCount),
                style = MaterialTheme.typography.bodyLarge,
            )
            val hours = (summary.stats.totalDurationSeconds / SECONDS_PER_HOUR).toInt()
            Text(
                pluralStringResource(R.plurals.widget_hours_of_music, hours, hours),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
            )
            val shares = CodecShares.of(summary.stats.byCodec)
            if (shares.labels.isNotEmpty()) {
                SectionLabel(stringResource(R.string.widget_by_format))
                ColumnChart(
                    labels = shares.labels,
                    series = listOf(shares.tracksPct, shares.bytesPct),
                    legend = listOf(stringResource(R.string.widget_tracks_pct), stringResource(R.string.widget_bytes_pct)),
                    tag = "chart-by-format",
                )
            }
            if (summary.stats.byDecade.isNotEmpty()) {
                SectionLabel(stringResource(R.string.widget_by_decade))
                ColumnChart(
                    labels = summary.stats.byDecade.keys.toList(),
                    series = listOf(summary.stats.byDecade.values.map { it.toDouble() }),
                    legend = emptyList(),
                    tag = "chart-by-decade",
                )
            }
            TextButton(onClick = actions.onOpenLibrary) { Text(stringResource(R.string.widget_view_library)) }
        }
    }
}

/** The format chart's two series: each codec's share of the tracks and of
 * the bytes, in percent, most tracks first. A total of zero gives shares of
 * zero, not NaN: the server can send bytes of zero for a codec whose sizes
 * are unknown, so a library of only such tracks has a zero byte total. */
class CodecShares(val labels: List<String>, val tracksPct: List<Double>, val bytesPct: List<Double>) {
    companion object {
        fun of(byCodec: Map<String, CodecStats>): CodecShares {
            val codecs = byCodec.entries.sortedByDescending { it.value.tracks }
            val totalTracks = codecs.sumOf { it.value.tracks }.coerceAtLeast(1)
            val totalBytes = codecs.sumOf { it.value.bytes }.coerceAtLeast(1)
            return CodecShares(
                labels = codecs.map { it.key.uppercase() },
                tracksPct = codecs.map { PERCENT * it.value.tracks / totalTracks },
                bytesPct = codecs.map { PERCENT * it.value.bytes / totalBytes },
            )
        }
    }
}

/** A column chart of one or two series over labelled categories, in the
 * theme's colours. Empty series draw an empty frame rather than fail. */
@Composable
private fun ColumnChart(labels: List<String>, series: List<List<Double>>, legend: List<String>, tag: String) {
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(series) {
        producer.runTransaction {
            if (series.any { it.isNotEmpty() }) columnModel { series.forEach { s -> series(s) } }
        }
    }
    val colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
    ProvideVicoTheme(rememberM3VicoTheme()) {
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberColumnCartesianLayer(
                    ColumnCartesianLayer.ColumnProvider.series(colors.map { rememberLineComponent(fill = Fill(it), thickness = COLUMN_WIDTH) }),
                ),
                startAxis = VerticalAxis.rememberStart(),
                bottomAxis = HorizontalAxis.rememberBottom(
                    valueFormatter = CartesianValueFormatter { _, x, _ -> labels.getOrNull(x.toInt()) ?: "" },
                ),
            ),
            modelProducer = producer,
            modifier = Modifier.fillMaxWidth().height(CHART_HEIGHT).testTag(tag),
        )
    }
    if (legend.isNotEmpty()) {
        // Each entry stays whole; the next one takes a new line when both do
        // not fit, instead of the last being squeezed until it breaks mid-word.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            legend.forEachIndexed { i, label ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(10.dp).height(10.dp).clip(RoundedCornerShape(2.dp)).background(colors.getOrElse(i) { colors.first() }))
                    Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
    }
}

/** A device with its usage, or null usage when that read failed for it. */
class DeviceCard(val device: AppDevice, val usage: DeviceUsage?)

object DevicesWidget : Widget {
    override val id = WidgetIds.DEVICES
    override suspend fun load(clients: WidgetClients, settings: WidgetSettings): Any =
        clients.app.devices().map { device ->
            // One device's usage failing must not take the whole widget down.
            val usage = try {
                clients.app.deviceUsage(device.id)
            } catch (ignored: IOException) {
                null
            }
            DeviceCard(device, usage)
        }

    @Composable
    override fun Content(data: Any, actions: WidgetActions) {
        @Suppress("UNCHECKED_CAST")
        val cards = data as List<DeviceCard>
        if (cards.isEmpty()) {
            Text(stringResource(R.string.widget_no_devices), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            return
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            cards.forEach { card ->
                DeviceRow(card, isThisPhone = card.device.id == actions.host.staging.thisDeviceId) { actions.links.onOpenDevice(card.device.id) }
            }
        }
    }
}

@Composable
private fun DeviceRow(card: DeviceCard, isThisPhone: Boolean, onOpen: () -> Unit) {
    val device = card.device
    Column(Modifier.clickable(role = Role.Button, onClick = onOpen).testTag("device-${device.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DeviceIcon(device.icon, device.deviceType, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            // A badge takes the next line when it doesn't fit beside the name,
            // instead of being left what the name leaves and breaking mid-word.
            FlowRow(
                Modifier.padding(start = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(device.name, style = MaterialTheme.typography.bodyLarge)
                if (isThisPhone) {
                    Text(stringResource(R.string.basket_this_phone), style = MaterialTheme.typography.labelMedium,
                         color = MaterialTheme.colorScheme.primary)
                }
                if (!device.isOwn) {
                    // The owner's name, as the web's badge: this is someone else's device, pinned to this account.
                    Text(stringResource(R.string.widget_device_owner, device.ownerUsername), style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.outline)
                }
            }
        }
        val pending = device.syncStatus.pendingCount
        Text(
            listOfNotNull(
                device.lastSeenAt?.let { stringResource(R.string.widget_device_seen, it) } ?: stringResource(R.string.widget_device_seen_never),
                if (pending > 0) pluralStringResource(R.plurals.widget_device_pending, pending, pending)
                else stringResource(R.string.widget_device_up_to_date),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
        )
        val usage = card.usage
        if (usage != null) {
            val capacity = usage.maxSizeBytes ?: usage.reportedTotalBytes
            if (capacity != null && capacity > 0) {
                val fraction = (usage.usedBytes.toFloat() / capacity).coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    val barColor = if (usage.overLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(barColor))
                }
                Text(
                    stringResource(R.string.widget_device_storage, formatGB(usage.usedBytes), formatGB(capacity)),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                )
            } else {
                Text(stringResource(R.string.widget_device_storage_unknown, formatGB(usage.usedBytes)),
                     style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

object MostPlayedWidget : Widget {
    override val needsListeningHistory = true
    override val id = WidgetIds.MOST_PLAYED
    override suspend fun load(clients: WidgetClients, settings: WidgetSettings): Any = clients.dashboard.mostPlayed(PERIOD, LIMIT)

    @Composable
    override fun Content(data: Any, actions: WidgetActions) {
        val feed = data as Feed // configured: Home shows this widget only then
        if (feed.items.isEmpty()) {
            Text(stringResource(R.string.widget_no_plays), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            return
        }
        // The chart is horizontal bars: a row per album, the bar's width the
        // play count against the top one. Plain layout, no library needed.
        val top = feed.items.maxOf { it.playcount ?: 0 }.coerceAtLeast(1)
        Column(Modifier.testTag("most-played-bars"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            feed.items.forEach { card ->
                val plays = card.playcount ?: 0
                Column {
                    Text("${card.album} · ${card.artist}", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(plays.toFloat() / top).background(MaterialTheme.colorScheme.primary))
                        }
                        Text(pluralStringResource(R.plurals.widget_plays, plays, plays), style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }

    private const val PERIOD = "6month"
    private const val LIMIT = 8
}

object AdministrationWidget : Widget {
    override val id = WidgetIds.ADMINISTRATION
    // "Manage" is a web action; the card says so, behind its info icon.
    override val info = R.string.widget_admin_manage_in_browser
    override suspend fun load(clients: WidgetClients, settings: WidgetSettings): Any = clients.dashboard.adminCounts()

    @Composable
    override fun Content(data: Any, actions: WidgetActions) {
        val counts = data as AdminCounts
        Column(Modifier.testTag("widget-administration-content")) {
            Text(
                pluralStringResource(R.plurals.widget_users, counts.users, counts.users) + " · " +
                    pluralStringResource(R.plurals.widget_delegations, counts.delegations, counts.delegations),
                style = MaterialTheme.typography.bodyLarge,
            )

        }
    }
}

private const val SECONDS_PER_HOUR = 3600L
private const val PERCENT = 100.0
private val CHART_HEIGHT = 160.dp
private val COLUMN_WIDTH = 14.dp
