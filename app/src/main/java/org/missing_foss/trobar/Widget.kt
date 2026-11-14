// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect

/** The ids the server's catalog uses. The catalog governs order and the
 * admin-only flag; these are the labels and the settings the phone
 * knows for each id. An id the catalog names and this list does not is
 * rendered as "not available in the app" and kept in the order. */
object WidgetIds {
    const val LIBRARY = "library"
    const val DEVICES = "devices"
    const val SUGGESTIONS = "suggestions"
    const val RECENTLY_ADDED = "recently_added"
    const val RECENTLY_RELEASED = "recently_released"
    const val MOST_PLAYED = "most_played"
    const val ADMINISTRATION = "administration"
    const val PLAYLISTS = "playlists"

    /** The Playlists widget's row count, as the server holds it. */
    val PLAYLISTS_RANGE = 1..10
    const val PLAYLISTS_DEFAULT = 3

    /** The two widgets with a per-widget months setting, and its bounds, as the server holds them. */
    val MONTHS_WIDGETS = setOf(RECENTLY_ADDED, RECENTLY_RELEASED)

    /** The three widgets that draw a cover grid, each with its own cover count. */
    val COVER_WIDGETS = setOf(SUGGESTIONS, RECENTLY_ADDED, RECENTLY_RELEASED)
    val MONTHS_RANGE = 1..24
    val COVER_LIMITS = listOf(15, 30, 45, 60)

    @StringRes
    fun label(id: String): Int? = when (id) {
        LIBRARY -> R.string.widget_library
        DEVICES -> R.string.widget_devices
        SUGGESTIONS -> R.string.widget_suggestions
        RECENTLY_ADDED -> R.string.widget_recently_added
        RECENTLY_RELEASED -> R.string.widget_recently_released
        MOST_PLAYED -> R.string.widget_most_played
        ADMINISTRATION -> R.string.widget_administration
        PLAYLISTS -> R.string.widget_playlists
        else -> null
    }
}

/** Where a widget's rows lead, beyond the library: a device's selections, the
 * Playlists tab, an album's page, and staging a playlist from where its row is. */
class WidgetLinks(
    val onOpenDevice: (Long) -> Unit = {},
    val onOpenPlaylists: () -> Unit = {},
    val onOpenPlaylist: (Long) -> Unit = {},
    val onStagePlaylist: (id: Long, from: Rect) -> Unit = { _, _ -> },
    val onOpenAlbum: (artist: String, album: String) -> Unit = { _, _ -> },
)

/** What the host hands a widget's content: the preferences in force, the
 * ids currently visible and the loaded data of every widget (a grid
 * deduplicates against its siblings' shown lists), the staging context,
 * and what it may ask the host to do -- open the library, stage an album
 * (`Artist||Album`) for the header's device from where its cover is,
 * change its own months. */
class WidgetActions(
    val settings: WidgetSettings,
    val host: WidgetHost,
    val onOpenLibrary: () -> Unit,
    val links: WidgetLinks = WidgetLinks(),
    val onStage: (target: String, coverUrl: String, from: Rect) -> Unit,
    val onSetMonths: (id: String, months: Int) -> Unit,
)

/** The host's state a widget may read: which ids are visible, what every
 * widget has loaded, the staging context and how to show a cover. */
class WidgetHost(
    val visible: Set<String>,
    val loaded: Map<String, Any>,
    val staging: StagingContext,
    val covers: Covers,
    /** True from App API level 7: the album grids leave out what is selected
     * for this phone, so an empty one says that is all there is. */
    val leavesOutThisPhone: Boolean = false,
    /** This phone's name as the server has it enrolled; null until the
     * devices are read, or if it isn't among them. */
    val thisDeviceName: String? = null,
)

/** One widget the phone can render: a loader that fetches its data with
 * the settings in force, and the content that shows it. The host owns
 * when to load (visible widgets only, on open and on refresh) and what
 * to show while loading or after a failure. */
/** The two App API clients a loader may read: the dashboard's routes and
 * the rest (library, devices, basket). */
class WidgetClients(
    val dashboard: AppApiDashboardClient,
    val app: AppApiClient,
    /** The header's device, for a widget about what is on it. */
    val destination: () -> AppDevice? = { null },
)

interface Widget {
    val id: String

    /** Built from a Last.fm or ListenBrainz history: shown on Home only
     * once its feed says one is set up (see [Feed.configured]). */
    val needsListeningHistory: Boolean get() = false

    /** An explanation of the card, behind an info icon beside its title; none by default. */
    val info: Int? get() = null

    /** A control the card puts in its title row, after the title and its
     * info icon; none by default. The card owns that row, so the control
     * shows while the content loads or after it fails too. */
    fun titleControls(actions: WidgetActions): (@Composable () -> Unit)? = null

    suspend fun load(clients: WidgetClients, settings: WidgetSettings): Any

    @Composable
    fun Content(data: Any, actions: WidgetActions)
}

/** The widgets this build renders, by id. Filled in as they land; the
 * host treats a catalog id with no entry here as unavailable. */
object WidgetRegistry {
    val all: Map<String, Widget> = listOf(
        LibraryStatsWidget, DevicesWidget, SuggestionsWidget, RecentlyAddedWidget, RecentlyReleasedWidget, MostPlayedWidget, AdministrationWidget,
        PlaylistsWidget,
    ).associateBy { it.id }
}
