// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** The account this device's token acts as on the App API, and with what rights. */
data class WhoAmI(val userId: Long, val username: String, val isAdmin: Boolean,
                  val deviceId: Long, val deviceName: String, val appApi: Int)

/** [albums] is each album title, for the search; null from a server that
 * predates the field, and then only the name is searched. */
data class LibraryArtist(
    val artist: String, val trackCount: Int, val albumCount: Int, val albums: List<String>? = null,
    val appearsOnCount: Int = 0,
)

/** `year` and `reissueYear` are null when no track of the album carries one. */
data class LibraryAlbum(val album: String, val year: Int?, val reissueYear: Int?, val trackCount: Int)

/** One disc of an album's tracklist; [number] is null when the album has
 * only the one, which then takes no heading. */
data class AlbumDisc(val number: Int?, val tracks: List<AlbumTrack>)

/** An album by [albumArtist] with the artist on it: [trackCount] are theirs. */
data class AppearsOnAlbum(val album: String, val albumArtist: String, val year: Int?, val trackCount: Int)

/** One track of an album; [id] is what a playlist's append takes. */
data class AlbumTrack(val id: Long, val trackNo: Int?, val discNo: Int?, val title: String, val artist: String, val duration: Double?)

/** One staged item, as the server enriches it: `title` is resolved for
 * display (null, with `missing` true, when a playlist or track target no
 * longer exists); `deviceIds` are the devices it is staged for. The type
 * and target are the server's own vocabulary and go back to it unchanged. */
data class BasketItem(val id: Long, val type: String, val target: String, val addedAt: String,
                      val deviceIds: List<Long>, val title: String?, val sourceProvider: String?,
                      val missing: Boolean)

/** `count` selections created, `skipped` items the server could not act on. */
data class FanOutResult(val count: Int, val skipped: Int)

data class DeviceSyncStatus(val pendingCount: Int, val lastSyncedAt: String?)

/** A device the acting user may stage to: their own, one pinned to them,
 * or every device when they are an admin. */
data class AppDevice(val id: Long, val name: String, val deviceType: String, val maxSizeBytes: Long?,
                     val ownerUsername: String, val isOwn: Boolean, val isPinned: Boolean,
                     val lastSeenAt: String?, val syncStatus: DeviceSyncStatus, val icon: String? = null,
                     val autofit: DeviceAutofit? = null)

/** Trobar's own share of a device (`usedBytes`) against its limit, and what
 * the device last reported about the whole volume and the sync folder; a
 * null is "never reported", never zero, and the two `...ReportedAt`
 * timestamps say when a reported figure was true. */
data class DeviceUsage(val usedBytes: Long, val maxSizeBytes: Long?, val trackCount: Int, val overLimit: Boolean,
                       val folderOverLimit: Boolean, val reportedFreeBytes: Long?, val reportedTotalBytes: Long?,
                       val deviceUsedBytes: Long?, val reportedForeignBytes: Long?, val folderUsedBytes: Long?,
                       val freeBytesReportedAt: String?, val foreignBytesReportedAt: String?)

data class Selection(val id: Long, val type: String, val target: String, val createdAt: String,
                     val createdByUsername: String, val deviceIds: List<Long>, val title: String? = null,
                     val wholeAlbumTracks: Int? = null, val appearsOn: SelectionAppearsOn? = null)

/** An album selection of one artist's tracks on someone else's album. */
data class SelectionAppearsOn(val tracks: Int, val of: Int)

/** A device's auto-fit, set in the browser. */
data class DeviceAutofit(val enabled: Boolean, val tracks: Int)

/** The three things the phone follows from the profile: how albums show
 * (list or cover grid), whether reissue years show, and the devices last
 * chosen from each surface, keyed by surface name. */
data class ProfileView(val coverViewMode: String, val showReissueYear: Boolean, val lastDestinations: Map<String, List<Long>>)

/** The App API level this build was written against. A server reporting
 * less does not offer these routes; the tabs that need them show the
 * "update the server" state instead (see AppApiGate). */
const val APP_API_MIN = 1

/** The level that brought the playlist routes; a level-1 server has none. */
const val APP_API_PLAYLISTS = 2

/** The level that brought removing a selection from a device. */
const val APP_API_SELECTIONS = 4

/** The level that brought making playlists from the phone. */
const val APP_API_PLAYLIST_WRITES = 3

/** The level that brought an album's tracks. */
const val APP_API_ALBUM_TRACKS = 5

/** The level that brought the library by album artist. Below it the Library
 * shows what it always did, by track artist. */
const val APP_API_ALBUM_ARTISTS = 6

/** The level from which Recently added, Recently released and Suggestions
 * leave out the albums already selected for the phone asking. Below it they
 * include them, and a change of selections gives no reason to read them again. */
const val APP_API_HOME_LEAVES_OUT_THIS_PHONE = 7

private fun parseAlbums(json: String): List<LibraryAlbum> {
    val arr = JSONArray(json)
    return List(arr.length()) { i ->
        val o = arr.getJSONObject(i)
        LibraryAlbum(
            album = o.optString("album", ""),
            year = if (o.isNull("year")) null else o.optInt("year"),
            reissueYear = if (o.isNull("reissue_year")) null else o.optInt("reissue_year"),
            trackCount = o.optInt("track_count", 0),
        )
    }
}

/** Talks to the App API ("/api/app" routes): the same device token as the
 * sync client, acting as the device's owner, over the app's one HTTP
 * client. Same conventions as [ApiClient] -- blocking calls, org.json,
 * one method per route -- and the same error mapping (401 typed,
 * everything else an IOException carrying the server's message). */
class AppApiClient(context: Context, private val serverUrl: String, private val token: String) {
    private val http = AppApiHttp(context, serverUrl, token)

    fun whoAmI(): WhoAmI {
        val json = JSONObject(http.get(http.url("whoami"), "whoami"))
        return WhoAmI(
            userId = json.getLong("user_id"),
            username = json.optString("username", "?"),
            isAdmin = json.optBoolean("is_admin", false),
            deviceId = json.getLong("device_id"),
            deviceName = json.optString("device_name", "?"),
            appApi = (json.opt("app_api") as? Number)?.toInt() ?: 0,
        )
    }

    /** Every artist in the library with a track that is not deleted, in the
     * server's order (by name). */
    fun libraryArtists(): List<LibraryArtist> {
        val arr = JSONArray(http.get(http.url("library", "artists"), "library/artists"))
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val titles = o.optJSONArray("albums")
            LibraryArtist(
                o.optString("artist", ""), o.optInt("track_count", 0), o.optInt("album_count", 0),
                titles?.let { a -> List(a.length()) { a.optString(it, "") } },
            )
        }
    }

    /** The library by album artist, level 6: a compilation once, and an
     * artist only on others' albums with no album count. */
    fun albumArtists(): List<LibraryArtist> {
        val arr = JSONArray(http.get(http.url("library", "album-artists"), "library/album-artists"))
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val titles = o.optJSONArray("albums")
            LibraryArtist(
                o.optString("artist", ""), o.optInt("track_count", 0), o.optInt("album_count", 0),
                titles?.let { a -> List(a.length()) { a.optString(it, "") } }, o.optInt("appears_on_count", 0),
            )
        }
    }

    /** One artist's albums, newest first as the server orders them. */
    fun libraryAlbums(artist: String): List<LibraryAlbum> =
        parseAlbums(http.get(http.url("library", "albums", query = mapOf("artist" to artist)), "library/albums"))

    /** The albums [artist] is the album artist of, level 6. */
    fun albumArtistAlbums(artist: String): List<LibraryAlbum> =
        parseAlbums(http.get(http.url("library", "album-artist-albums", query = mapOf("artist" to artist)), "library/album-artist-albums"))

    /** Albums by others with [artist] on them, level 6. */
    fun appearsOn(artist: String): List<AppearsOnAlbum> {
        val arr = JSONArray(http.get(http.url("library", "appears-on", query = mapOf("artist" to artist)), "library/appears-on"))
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            AppearsOnAlbum(o.optString("album", ""), o.optString("album_artist", ""), o.intOrNull("year"), o.optInt("track_count", 0))
        }
    }

    /** An album's tracks in disc and track order: what selecting it syncs. */
    fun albumTracks(artist: String, album: String): List<AlbumTrack> {
        val query = mapOf("artist" to artist, "album" to album)
        val arr = JSONArray(http.get(http.url("library", "album-tracks", query = query), "library/album-tracks"))
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            AlbumTrack(
                id = o.getLong("id"), trackNo = o.intOrNull("track_no"), discNo = o.intOrNull("disc_no"),
                title = o.optString("title", ""), artist = o.optString("artist", ""),
                duration = if (o.isNull("duration")) null else o.optDouble("duration"),
            )
        }
    }

    /** Artists the server's similarity source names that are also in this
     * library -- an empty list when there are none or the source is unset. */
    fun similarArtists(artist: String): List<String> {
        val arr = JSONArray(http.get(http.url("library", "similar-artists", query = mapOf("artist" to artist)), "library/similar-artists"))
        return List(arr.length()) { i -> arr.getString(i) }
    }

    /** Image URLs for [TrobarImage]: the shared client's interceptor adds the
     * token to requests for the paired server, so these carry none. A 404 is
     * the normal outcome for an album or artist without a picture. */
    fun coverUrl(artist: String, album: String): String =
        http.url("library", "cover", query = mapOf("artist" to artist, "album" to album))

    fun artistImageUrl(artist: String, small: Boolean = false): String =
        http.url("library", "artist-image", query = if (small) mapOf("artist" to artist, "size" to "small") else mapOf("artist" to artist))

    // The staging loop. The basket is server-side and per account, so the
    // phone and the browser share one.

    fun basket(): List<BasketItem> {
        val arr = JSONArray(http.get(http.url("basket"), "basket"))
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            BasketItem(
                id = o.getLong("id"),
                type = o.optString("type", ""),
                target = o.optString("target", ""),
                addedAt = o.optString("added_at", ""),
                deviceIds = ids(o.opt("device_ids")),
                title = o.stringOrNull("title"),
                sourceProvider = o.stringOrNull("source_provider"),
                missing = o.optBoolean("missing", false),
            )
        }
    }

    /** Stages one target for the given devices (at least one: staging is
     * choosing a destination) and returns the new item's id. */
    fun addToBasket(type: String, target: String, deviceIds: List<Long>): Long {
        val body = JSONObject().put("type", type).put("target", target).put("device_ids", JSONArray(deviceIds))
        return JSONObject(http.send("POST", http.url("basket"), body, "basket")).getLong("id")
    }

    fun clearBasket() {
        http.send("DELETE", http.url("basket"), null, "basket")
    }

    fun removeBasketItem(itemId: Long) {
        http.send("DELETE", http.url("basket", itemId.toString()), null, "basket/$itemId")
    }

    /** Takes one device off an item; the item goes with its last device. */
    fun unstageDevice(itemId: Long, deviceId: Long) {
        http.send("DELETE", http.url("basket", itemId.toString(), "devices", deviceId.toString()), null, "basket/$itemId/devices/$deviceId")
    }

    /** Turns the basket's sections for these devices into selections; items
     * staged only for other devices stay in the basket. */
    fun fanOut(deviceIds: List<Long>): FanOutResult {
        val body = JSONObject().put("device_ids", JSONArray(deviceIds))
        val o = JSONObject(http.send("POST", http.url("basket", "fan-out"), body, "basket/fan-out"))
        return FanOutResult(o.optInt("count", 0), o.optInt("skipped", 0))
    }

    /** Remembers the devices last chosen from one surface of the app, so the
     * picker can preselect them next time (the browser keeps its own). */
    fun setLastDestination(surface: String, deviceIds: List<Long>) {
        val body = JSONObject().put("surface", surface).put("device_ids", JSONArray(deviceIds))
        http.send("PATCH", http.url("basket", "last-destination"), body, "basket/last-destination")
    }

    fun selections(): List<Selection> {
        val arr = JSONArray(http.get(http.url("selections"), "selections"))
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Selection(
                id = o.getLong("id"),
                type = o.optString("type", ""),
                target = o.optString("target", ""),
                createdAt = o.optString("created_at", ""),
                createdByUsername = o.optString("created_by_username", ""),
                deviceIds = ids(o.opt("device_ids")),
                title = o.stringOrNull("title"),
                wholeAlbumTracks = o.intOrNull("album_tracks"),
                appearsOn = o.optJSONObject("appears_on")?.let { SelectionAppearsOn(it.optInt("tracks"), it.optInt("of")) },
            )
        }
    }

    /** What removing this selection would delete from the device at its next sync. */
    fun removalCount(deviceId: Long, type: String, target: String): Int {
        val body = selectionBody(deviceId, type, target)
        val o = JSONObject(http.send("POST", http.url("selections", "removal-count"), body, "selections/removal-count"))
        return o.optInt("tracks", 0)
    }

    /** Takes the owner's selection off this device, leaving any others it
     * covers. An IOException carries the server's own message when someone
     * else's selection keeps it there (409). */
    fun removeSelection(deviceId: Long, type: String, target: String) {
        http.send("POST", http.url("selections", "remove"), selectionBody(deviceId, type, target), "selections/remove")
    }

    private fun selectionBody(deviceId: Long, type: String, target: String) =
        JSONObject().put("device_id", deviceId).put("type", type).put("target", target)

    fun devices(): List<AppDevice> {
        val arr = JSONArray(http.get(http.url("devices"), "devices"))
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val status = o.optJSONObject("sync_status")
            AppDevice(
                id = o.getLong("id"),
                name = o.optString("name", "?"),
                deviceType = o.optString("device_type", "phone"),
                maxSizeBytes = o.longOrNull("max_size_bytes"),
                ownerUsername = o.optString("owner_username", ""),
                isOwn = o.optBoolean("is_own", false),
                isPinned = o.optBoolean("is_pinned", false),
                lastSeenAt = o.stringOrNull("last_seen_at"),
                syncStatus = DeviceSyncStatus(status?.optInt("pending_count", 0) ?: 0, status?.stringOrNull("last_synced_at")),
                icon = o.stringOrNull("icon"),
                autofit = o.optJSONObject("autofit")?.let { DeviceAutofit(it.optBoolean("enabled", false), it.optInt("tracks", 0)) },
            )
        }
    }

    fun profileView(): ProfileView {
        val o = JSONObject(http.get(http.url("profile"), "profile"))
        val dests = o.optJSONObject("basket_last_destinations") ?: JSONObject()
        return ProfileView(
            coverViewMode = o.optString("cover_view_mode", "list"),
            showReissueYear = o.optBoolean("show_reissue_year", false),
            lastDestinations = dests.keys().asSequence().associateWith { ids(dests.opt(it)) },
        )
    }

    fun deviceUsage(deviceId: Long): DeviceUsage {
        val o = JSONObject(http.get(http.url("devices", deviceId.toString(), "usage"), "devices/$deviceId/usage"))
        return DeviceUsage(
            usedBytes = o.optLong("used_bytes", 0),
            maxSizeBytes = o.longOrNull("max_size_bytes"),
            trackCount = o.optInt("track_count", 0),
            overLimit = o.optBoolean("over_limit", false),
            folderOverLimit = o.optBoolean("folder_over_limit", false),
            reportedFreeBytes = o.longOrNull("reported_free_bytes"),
            reportedTotalBytes = o.longOrNull("reported_total_bytes"),
            deviceUsedBytes = o.longOrNull("device_used_bytes"),
            reportedForeignBytes = o.longOrNull("reported_foreign_bytes"),
            folderUsedBytes = o.longOrNull("folder_used_bytes"),
            freeBytesReportedAt = o.stringOrNull("free_bytes_reported_at"),
            foreignBytesReportedAt = o.stringOrNull("foreign_bytes_reported_at"),
        )
    }
}
