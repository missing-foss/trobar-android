// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** The three album grids of the Home dashboard. Each loads its own list
 * and shows it deduplicated against its siblings' shown lists (the
 * browser's rule, in DashboardDedup), limited to its own cover count;
 * a double-tap on an album stages it; the two recently-* grids
 * carry their months setting in the card's title row. Suggestions need a listening
 * history: without one, Home leaves the widget out (see shownOnHome). */
private const val DEFAULT_MONTHS = 3
private val GRID_SPACING = 8.dp

object RecentlyReleasedWidget : Widget {
    override val id = WidgetIds.RECENTLY_RELEASED
    override suspend fun load(clients: WidgetClients, settings: WidgetSettings): Any =
        clients.dashboard.recentlyReleased(settings.months[id] ?: DEFAULT_MONTHS)

    @Composable
    override fun Content(data: Any, actions: WidgetActions) {
        val cards = DashboardDedup.recentlyReleased(grids(actions, id, data), actions.host.visible, actions.settings::coverLimitOf)
        AlbumGridWidget(id, cards, actions, emptyText = R.string.widget_nothing_recently_released)
    }

    override fun titleControls(actions: WidgetActions) = monthsControl(id, actions)
}

object RecentlyAddedWidget : Widget {
    override val id = WidgetIds.RECENTLY_ADDED
    override suspend fun load(clients: WidgetClients, settings: WidgetSettings): Any =
        clients.dashboard.recentlyAdded(settings.months[id] ?: DEFAULT_MONTHS)

    @Composable
    override fun Content(data: Any, actions: WidgetActions) {
        val cards = DashboardDedup.recentlyAdded(grids(actions, id, data), actions.host.visible, actions.settings::coverLimitOf)
        AlbumGridWidget(id, cards, actions, emptyText = R.string.widget_nothing_recently_added)
    }

    override fun titleControls(actions: WidgetActions) = monthsControl(id, actions)
}

object SuggestionsWidget : Widget {
    override val id = WidgetIds.SUGGESTIONS
    override val needsListeningHistory = true
    override suspend fun load(clients: WidgetClients, settings: WidgetSettings): Any = clients.dashboard.suggestions(SUGGESTIONS_PERIOD)

    @Composable
    override fun Content(data: Any, actions: WidgetActions) {
        val feed = data as Feed
        val cards = DashboardDedup.suggestions(grids(actions, id, feed.items), actions.host.visible, actions.settings::coverLimitOf)
        AlbumGridWidget(id, cards, actions, emptyText = R.string.widget_no_suggestions)
    }

    private const val SUGGESTIONS_PERIOD = "6month"
}

/** The three lists as loaded: this widget's own [data], and the siblings'
 * from what the host has loaded -- a sibling not loaded (hidden, or still
 * on its way) contributes nothing, which is the rule for a hidden one. */
@Suppress("UNCHECKED_CAST")
private fun grids(actions: WidgetActions, id: String, data: Any): DashboardDedup.Grids {
    fun cards(widgetId: String): List<AlbumCard> {
        val d = if (widgetId == id) data else actions.host.loaded[widgetId] ?: return emptyList()
        return if (d is Feed) d.items else d as List<AlbumCard>
    }
    return DashboardDedup.Grids(cards(WidgetIds.RECENTLY_RELEASED), cards(WidgetIds.RECENTLY_ADDED), cards(WidgetIds.SUGGESTIONS))
}

/** The months stepper of a recently-* grid, for its card's title row. */
private fun monthsControl(id: String, actions: WidgetActions): @Composable () -> Unit = {
    MonthsInline(actions.settings.months[id] ?: DEFAULT_MONTHS, onChange = { actions.onSetMonths(id, it) })
}

@Composable
private fun AlbumGridWidget(id: String, cards: List<AlbumCard>, actions: WidgetActions, emptyText: Int) {
    Column {
        // A row to itself, so it never shares the width with anything that
        // could push it onto two lines.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f))
            TextButton(onClick = actions.onOpenLibrary) { Text(stringResource(R.string.widget_view_library)) }
        }
        if (cards.isEmpty()) {
            Text(stringResource(emptyText), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        } else {
            // Plain rows, not a lazy grid: the list is short and capped, sits
            // in a scrolling column, and must take the height its cards need.
            // A cell is a square cover plus two caption lines, so its height
            // follows the screen width and the font scale; a box sized ahead
            // of time clipped the last row.
            // As many columns as the width holds (coverColumns): three on a
            // phone, more on a tablet, where three would make each cover a
            // third of the screen.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val columns = coverColumns(maxWidth, GRID_SPACING)
                Column(Modifier.fillMaxWidth().testTag("grid-$id"), verticalArrangement = Arrangement.spacedBy(GRID_SPACING)) {
                    cards.chunked(columns).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GRID_SPACING)) {
                            row.forEach { card ->
                                key("${card.artist}||${card.album}") {
                                    // Staged on the library's names, which the server resolved; a card
                                    // without them (a suggestion the library does not hold) is not stageable.
                                    val target = card.libraryArtist?.let { a -> card.libraryAlbum?.let { b -> "$a||$b" } }
                                    AlbumGridCard(
                                        card, actions.host.covers, Modifier.weight(1f),
                                        onStage = target?.let { t -> { url, from -> actions.onStage(t, url, from) } },
                                    )
                                }
                            }
                            // A short last row keeps its cards the width of the others.
                            repeat(columns - row.size) { Box(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

/** One cover with its two caption lines; [onStage] null = not stageable,
 * and then a double-tap does nothing either. */
@Composable
private fun AlbumGridCard(card: AlbumCard, covers: Covers, modifier: Modifier, onStage: ((coverUrl: String, from: Rect) -> Unit)?) {
    val url = card.imageUrl ?: covers.url(card.libraryArtist ?: card.artist, card.libraryAlbum ?: card.album)
    val label = stringResource(R.string.stage_action)
    Column(if (onStage != null) modifier.stageOnDoubleTap(label) { from -> onStage(url, from) } else modifier) {
        TrobarImage(
            url = url, contentDescription = null, pairing = covers.pairing,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        )
        Text(card.album, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        Text(card.artist, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1)
    }
}

/** "within [−] 3 months [+]", held to the server's range by the buttons. */
@Composable
private fun MonthsInline(months: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.widget_within), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        IconButton(onClick = { onChange(months - 1) }, enabled = months > WidgetIds.MONTHS_RANGE.first) { Text("−") }
        Text(pluralStringResource(R.plurals.widget_months, months, months), style = MaterialTheme.typography.bodySmall)
        IconButton(onClick = { onChange(months + 1) }, enabled = months < WidgetIds.MONTHS_RANGE.last) { Text("+") }
    }
}
