// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One playlist the owner may see, as the server attributes it for them.
 * [goldenOwnerUsername] is set on a row reached through a shared source
 * whose own copy belongs to someone else ("shared by X"). [canShare] says
 * whether the owner may change [shared]; the app offers the toggle only
 * then. The mirror fields are not part of the App API. */
data class AppPlaylist(
    val id: Long,
    val title: String,
    val sourceProvider: String?,
    val inferredOriginProvider: String?,
    val lastSyncedAt: String?,
    val ownerUsername: String?,
    val isOwn: Boolean,
    val shared: Boolean,
    val canShare: Boolean,
    val goldenOwnerUsername: String?,
    val trackCount: Int,
    val matchedCount: Int,
    val unresolvedCount: Int,
    val lidarr: LidarrRequests,
)

/** The per-playlist Lidarr request toggle and its last run. [configured]
 * and [connected] are the server's, for the hint beside a disabled toggle;
 * [hasAlbums] says whether anything unresolved carries an album to ask for. */
data class LidarrRequests(
    val configured: Boolean,
    val connected: Boolean,
    val hasAlbums: Boolean,
    val run: LidarrRun,
) {
    /** After a toggle: the route answers the toggle and its run only. */
    fun with(run: LidarrRun) = copy(run = run)
}

/** What the toggle route answers: whether it is on, and how the last run went. */
data class LidarrRun(val enabled: Boolean, val lastRunAt: String?, val lastCount: Int?, val lastError: String?, val lastErrorCode: String?)

/** One entry of a playlist, in playlist order; [matched] is whether it
 * resolved to a track in the library. */
data class PlaylistEntry(val position: Int, val artist: String?, val title: String?, val album: String?, val matched: Boolean)

/** An entry that did not resolve, for review; [excluded] marks it as not a gap. */
/** [inferredAlbum] is the album the server found in MusicBrainz for an entry
 * whose source gave none; null where it found none, or is too old to look. */
data class UnresolvedEntry(
    val id: Long, val artist: String?, val title: String?, val album: String?, val excluded: Boolean,
    val inferredAlbum: String? = null,
)

/** Whether a provider sync runs now. The last run's figures are the
 * server's and vary by provider, so they are kept as it sends them. */
data class PlaylistSyncStatus(val running: Boolean, val lastResult: JSONObject?)

data class PlaylistSubscription(
    val id: Long,
    val url: String,
    val provider: String,
    val title: String?,
    val lastSyncedAt: String?,
    val lastError: String?,
    val trackCount: Int?,
    val matchedCount: Int?,
)

/** A subscribe or a refresh: the import ran at once, and [status] is how
 * it went -- "ok", or not, with the reason on the subscription. */
data class SubscriptionResult(val status: String, val subscription: PlaylistSubscription)

/** The playlist half of the App API: everything the web's playlist section
 * does except mirroring. Its own class, as the dashboard's is; same
 * transport and conventions as the others. */
class AppApiPlaylistClient(context: Context, serverUrl: String, token: String) {
    private val http = AppApiHttp(context, serverUrl, token)

    fun playlists(): List<AppPlaylist> {
        val arr = JSONArray(http.get(http.url("playlists"), "playlists"))
        return List(arr.length()) { parsePlaylist(arr.getJSONObject(it)) }
    }

    fun entries(playlistId: Long): List<PlaylistEntry> {
        val arr = JSONArray(http.get(http.url("playlists", playlistId.toString(), "tracks"), "playlists/tracks"))
        return List(arr.length()) {
            val o = arr.getJSONObject(it)
            PlaylistEntry(o.getInt("position"), o.stringOrNull("artist"), o.stringOrNull("title"), o.stringOrNull("album"), o.getBoolean("matched"))
        }
    }

    /** Starts a provider sync; an IOException carries the server's own
     * message when one already runs (409). */
    fun startSync() {
        http.send("POST", http.url("playlists", "sync"), JSONObject(), "playlists/sync")
    }

    fun syncStatus(): PlaylistSyncStatus {
        val o = JSONObject(http.get(http.url("playlists", "sync", "status"), "playlists/sync/status"))
        return PlaylistSyncStatus(o.getBoolean("running"), o.optJSONObject("last_result"))
    }

    fun setShared(playlistId: Long, shared: Boolean) {
        http.send("PATCH", http.url("playlists", playlistId.toString()), JSONObject().put("shared", shared), "playlists/shared")
    }

    fun unresolved(playlistId: Long): List<UnresolvedEntry> {
        val arr = JSONArray(http.get(http.url("playlists", playlistId.toString(), "unresolved-tracks"), "playlists/unresolved"))
        return List(arr.length()) {
            val o = arr.getJSONObject(it)
            UnresolvedEntry(
                o.getLong("id"), o.stringOrNull("artist"), o.stringOrNull("title"), o.stringOrNull("album"), o.getBoolean("excluded"),
                inferredAlbum = o.stringOrNull("inferred_album"),
            )
        }
    }

    /** Returns the count of entries still flagged. */
    fun setExcluded(playlistId: Long, ids: List<Long>, excluded: Boolean): Int {
        val body = JSONObject().put("ids", JSONArray(ids)).put("excluded", excluded)
        val o = JSONObject(http.send("POST", http.url("playlists", playlistId.toString(), "unresolved-tracks", "exclude"), body, "playlists/exclude"))
        return o.getInt("unresolved_count")
    }

    /** The toggle and its last run after the write -- only those, as the
     * route answers; on, the server has already run the requests once. */
    fun setLidarrRequests(playlistId: Long, enabled: Boolean): LidarrRun =
        parseRun(JSONObject(http.send("POST", http.url("playlists", playlistId.toString(), "lidarr-requests"),
                                      JSONObject().put("enabled", enabled), "playlists/lidarr")))

    private fun parseRun(o: JSONObject) = LidarrRun(
        enabled = o.getBoolean("lidarr_request_enabled"), lastRunAt = o.stringOrNull("lidarr_request_last_run_at"),
        lastCount = o.intOrNull("lidarr_request_last_count"), lastError = o.stringOrNull("lidarr_request_last_error"),
        lastErrorCode = o.stringOrNull("lidarr_request_last_error_code"),
    )

    fun subscriptions(): List<PlaylistSubscription> {
        val arr = JSONObject(http.get(http.url("playlist-subscriptions"), "playlist-subscriptions")).getJSONArray("subscriptions")
        return List(arr.length()) { parseSubscription(arr.getJSONObject(it)) }
    }

    fun subscribe(url: String): SubscriptionResult =
        parseResult(http.send("POST", http.url("playlist-subscriptions"), JSONObject().put("url", url), "playlist-subscriptions/add"))

    fun refreshSubscription(id: Long): SubscriptionResult =
        parseResult(http.send("POST", http.url("playlist-subscriptions", id.toString(), "refresh"), JSONObject(), "playlist-subscriptions/refresh"))

    fun unsubscribe(id: Long) {
        http.send("DELETE", http.url("playlist-subscriptions", id.toString()), null, "playlist-subscriptions/remove")
    }

    private fun parseResult(body: String): SubscriptionResult {
        val o = JSONObject(body)
        return SubscriptionResult(o.getString("status"), parseSubscription(o.getJSONObject("subscription")))
    }

    private fun parseSubscription(o: JSONObject) = PlaylistSubscription(
        id = o.getLong("id"), url = o.getString("url"), provider = o.getString("provider"), title = o.stringOrNull("title"),
        lastSyncedAt = o.stringOrNull("last_synced_at"), lastError = o.stringOrNull("last_error"),
        trackCount = o.intOrNull("track_count"), matchedCount = o.intOrNull("matched_count"),
    )

    private fun parsePlaylist(o: JSONObject) = AppPlaylist(
        id = o.getLong("id"), title = o.getString("title"), sourceProvider = o.stringOrNull("source_provider"),
        inferredOriginProvider = o.stringOrNull("inferred_origin_provider"), lastSyncedAt = o.stringOrNull("last_synced_at"),
        ownerUsername = o.stringOrNull("owner_username"), isOwn = o.getBoolean("is_own"), shared = o.getBoolean("shared"),
        canShare = o.getBoolean("can_share"), goldenOwnerUsername = o.stringOrNull("golden_owner_username"),
        trackCount = o.getInt("track_count"), matchedCount = o.getInt("matched_count"), unresolvedCount = o.getInt("unresolved_count"),
        lidarr = LidarrRequests(
            configured = o.getBoolean("lidarr_request_configured"), connected = o.getBoolean("lidarr_request_connected"),
            hasAlbums = o.getBoolean("lidarr_request_has_albums"), run = parseRun(o),
        ),
    )
}
