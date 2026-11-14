// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One catalog entry: the widget id and whether only an admin sees it.
 * The catalog governs order and the admin-only flag; a client renders per
 * id and skips an id it does not know. */
data class WidgetCatalogEntry(val id: String, val adminOnly: Boolean)

/**
 * The per-widget settings the server holds: each cover widget's cover count,
 * and how many months back each of the two recently-* widgets looks (absent
 * when the user never set it).
 *
 * [coverLimits] is empty from a server older than per-widget counts, which
 * holds the one [coverLimit] for all three; [coverLimitOf] reads either, and
 * [perWidgetCovers] says which a change must be written as.
 */
data class WidgetSettings(
    val coverLimit: Int,
    val months: Map<String, Int>,
    val coverLimits: Map<String, Int> = emptyMap(),
    val playlistsLimit: Int = WidgetIds.PLAYLISTS_DEFAULT,
) {
    val perWidgetCovers: Boolean get() = coverLimits.isNotEmpty()

    fun coverLimitOf(id: String): Int = coverLimits[id] ?: coverLimit
}

/** A playlist with tracks left to sync to a device, for the Playlists widget. */
data class PlaylistToSync(val id: Long, val title: String, val trackCount: Int, val matchedCount: Int, val toSync: Int) {
    /** The share of its tracks in the library, as the Playlists tab's bar. */
    val availability: Float get() = if (trackCount > 0) matchedCount.toFloat() / trackCount else 0f
}

data class WidgetPrefs(val disabled: List<String>, val order: List<String>, val settings: WidgetSettings)

data class CodecStats(val tracks: Int, val bytes: Long)

/** `byDecade` is sorted by key ("1990s" before "2000s"), as the server sends it. */
data class LibraryStats(val totalTracks: Int, val totalDurationSeconds: Long,
                        val byCodec: Map<String, CodecStats>, val byDecade: Map<String, Int>)

/** One album on a dashboard feed. The two recently-* feeds and the
 * suggestion feed carry the library's own names in `libraryArtist` /
 * `libraryAlbum` and a `source`; the most-played chart carries neither
 * (it is the listening history as reported, not filtered to the library),
 * so those are null there. `imageUrl` is the provider's art or null, in
 * which case the library cover is the fallback. */
data class AlbumCard(val artist: String, val album: String, val libraryArtist: String?, val libraryAlbum: String?,
                     val imageUrl: String?, val source: String?, val addedAt: String?, val releasedAt: String?,
                     val playcount: Int?)

/** A feed that needs a listening history: `configured` false means no
 * Last.fm or ListenBrainz username on the profile -- a different state
 * from "configured, nothing to show": Home leaves such a widget out. */
data class Feed(val configured: Boolean, val items: List<AlbumCard>)

data class AdminCounts(val users: Int, val delegations: Int)

/** The Home dashboard's half of the App API: the widget catalog, the
 * owner's widget preferences (read, and the narrow PATCH), and the data
 * behind each widget. The devices and library-artists widgets read
 * [AppApiClient]. Its own class so each stays within the size detekt holds
 * a class to; same transport and conventions as the other. */
class AppApiDashboardClient(context: Context, serverUrl: String, token: String) {
    private val http = AppApiHttp(context, serverUrl, token)

    fun catalog(): List<WidgetCatalogEntry> {
        val arr = JSONArray(http.get(http.url("dashboard", "catalog"), "dashboard/catalog"))
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            WidgetCatalogEntry(o.getString("id"), o.optBoolean("admin_only", false))
        }
    }

    fun widgetPrefs(): WidgetPrefs = parsePrefs(JSONObject(http.get(http.url("dashboard", "widgets"), "dashboard/widgets")))

    /** The narrow write: only what is given is sent. `disabled` and `order`
     * replace the stored lists; `coverLimit` (the one value, which sets all
     * three cover widgets), each of `coverLimits` and each `months` entry
     * merge into the stored settings, leaving the others alone. A widget's
     * months and cover count go in one object. Returns the stored,
     * normalized preferences. */
    fun patchWidgetPrefs(
        disabled: List<String>? = null,
        order: List<String>? = null,
        coverLimit: Int? = null,
        months: Map<String, Int> = emptyMap(),
        coverLimits: Map<String, Int> = emptyMap(),
        playlistsLimit: Int? = null,
    ): WidgetPrefs {
        val body = JSONObject()
        if (disabled != null) body.put("disabled", JSONArray(disabled))
        if (order != null) body.put("order", JSONArray(order))
        val settings = JSONObject()
        if (coverLimit != null) settings.put("cover_limit", coverLimit)
        fun entry(widget: String): JSONObject =
            settings.optJSONObject(widget) ?: JSONObject().also { settings.put(widget, it) }
        months.forEach { (widget, n) -> entry(widget).put("months", n) }
        coverLimits.forEach { (widget, n) -> entry(widget).put("cover_limit", n) }
        if (playlistsLimit != null) entry(WidgetIds.PLAYLISTS).put("limit", playlistsLimit)
        if (settings.length() > 0) body.put("settings", settings)
        return parsePrefs(JSONObject(http.send("PATCH", http.url("dashboard", "widgets"), body, "dashboard/widgets")))
    }

    /** What has most left to sync to [deviceId], most first. */
    fun playlistsToSync(deviceId: Long, limit: Int): List<PlaylistToSync> {
        val url = http.url("dashboard", "playlists", query = mapOf("device_id" to deviceId.toString(), "limit" to limit.toString()))
        val arr = JSONArray(http.get(url, "dashboard/playlists"))
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            PlaylistToSync(o.getLong("id"), o.getString("title"), o.optInt("track_count", 0), o.optInt("matched_count", 0), o.optInt("to_sync", 0))
        }
    }

    fun libraryStats(): LibraryStats {
        val o = JSONObject(http.get(http.url("library", "stats"), "library/stats"))
        val codecs = o.optJSONObject("by_codec") ?: JSONObject()
        val decades = o.optJSONObject("by_decade") ?: JSONObject()
        return LibraryStats(
            totalTracks = o.optInt("total_tracks", 0),
            totalDurationSeconds = o.optLong("total_duration_seconds", 0),
            byCodec = codecs.keys().asSequence().associateWith { k ->
                val c = codecs.getJSONObject(k)
                CodecStats(c.optInt("tracks", 0), c.optLong("bytes", 0))
            },
            byDecade = decades.keys().asSequence().associateWith { decades.optInt(it, 0) }.toSortedMap(),
        )
    }

    fun recentlyAdded(months: Int): List<AlbumCard> {
        val url = http.url("library", "recently-added", query = mapOf("months" to months.toString()))
        return cards(JSONArray(http.get(url, "library/recently-added")))
    }

    fun recentlyReleased(months: Int): List<AlbumCard> {
        val url = http.url("library", "recently-released", query = mapOf("months" to months.toString()))
        return cards(JSONArray(http.get(url, "library/recently-released")))
    }

    /** `period` is one of the server's Last.fm periods (overall, 7day,
     * 1month, 3month, 6month, 12month); anything else reads as 6month. */
    fun suggestions(period: String): Feed =
        feed(JSONObject(http.get(http.url("suggestions", query = mapOf("period" to period)), "suggestions")))

    fun mostPlayed(period: String, limit: Int): Feed {
        val url = http.url("suggestions", "most-played", query = mapOf("period" to period, "limit" to limit.toString()))
        return feed(JSONObject(http.get(url, "suggestions/most-played")))
    }

    /** Admin only: the server answers 403 for any other owner, which
     * reaches the caller as an IOException with the server's message. */
    fun adminCounts(): AdminCounts {
        val o = JSONObject(http.get(http.url("admin", "counts"), "admin/counts"))
        return AdminCounts(o.optInt("users", 0), o.optInt("delegations", 0))
    }

    private fun feed(o: JSONObject): Feed =
        Feed(o.optBoolean("configured", false), cards(o.optJSONArray("items") ?: JSONArray()))

    private fun cards(arr: JSONArray): List<AlbumCard> = List(arr.length()) { i ->
        val o = arr.getJSONObject(i)
        AlbumCard(
            artist = o.optString("artist", ""),
            album = o.optString("album", ""),
            libraryArtist = o.stringOrNull("library_artist"),
            libraryAlbum = o.stringOrNull("library_album"),
            imageUrl = o.stringOrNull("image_url"),
            source = o.stringOrNull("source"),
            addedAt = o.stringOrNull("added_at"),
            releasedAt = o.stringOrNull("released_at"),
            playcount = o.intOrNull("playcount"),
        )
    }

    private fun parsePrefs(o: JSONObject): WidgetPrefs {
        val settings = o.optJSONObject("settings") ?: JSONObject()
        val months = settings.keys().asSequence()
            .filter { it != "cover_limit" }
            .mapNotNull { key -> settings.optJSONObject(key)?.let { entry -> key to entry.optInt("months", 0) } }
            .filter { it.second > 0 }
            .toMap()
        // Absent from a server older than per-widget counts: then empty.
        val coverLimits = WidgetIds.COVER_WIDGETS
            .mapNotNull { id -> settings.optJSONObject(id)?.intOrNull("cover_limit")?.let { id to it } }
            .toMap()
        return WidgetPrefs(
            disabled = o.optJSONArray("disabled").strings(),
            order = o.optJSONArray("order").strings(),
            settings = WidgetSettings(
                settings.optInt("cover_limit", DEFAULT_COVER_LIMIT), months, coverLimits,
                settings.optJSONObject(WidgetIds.PLAYLISTS)?.intOrNull("limit") ?: WidgetIds.PLAYLISTS_DEFAULT,
            ),
        )
    }

    companion object {
        /** The server's default when a user never chose one. */
        const val DEFAULT_COVER_LIMIT = 15
    }
}
