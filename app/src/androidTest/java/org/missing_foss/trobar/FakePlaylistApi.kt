// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject

/** The playlist half of the App API, in memory, in the server's wire shapes
 * (the client's fixtures were captured from the server itself). A provider
 * sync runs for [syncPolls] status reads, then adds [syncAdds] to the list.
 * Everything else goes to [base], for the header and the badge. */
class FakePlaylistApi(val base: FakeAppApi = FakeAppApi()) : Dispatcher() {
    class Playlist(
        val id: Long,
        val title: String,
        val trackCount: Int,
        val matchedCount: Int,
        val provider: String? = "roon",
        val ownerUsername: String? = "alice",
        val isOwn: Boolean = true,
        @Volatile var shared: Boolean = !isOwn,
        val canShare: Boolean = isOwn,
        val goldenOwnerUsername: String? = null,
        val inferredOrigin: String? = null,
        val lastSyncedAt: String? = "2026-09-20 10:00:00",
        val lidarrConfigured: Boolean = true,
        val lidarrConnected: Boolean = true,
        val lidarrHasAlbums: Boolean = true,
        @Volatile var lidarrEnabled: Boolean = false,
    )

    /** An unmatched entry under review, as the server keeps it. */
    class Unresolved(
        val id: Long, val artist: String?, val title: String?, val album: String?, @Volatile var excluded: Boolean = false,
        val inferredAlbum: String? = null,
    )
    val unresolved: MutableMap<Long, List<Unresolved>> = java.util.concurrent.ConcurrentHashMap() // playlist id -> its unmatched entries
    val excludeBodies = CopyOnWriteArrayList<JSONObject>()
    @Volatile var unresolvedFails = false // the review's routes answer 500
    /** What the Lidarr toggle's run answers when turned on: (count, error, error code). */
    @Volatile var lidarrRun: Triple<Int, String?, String?> = Triple(3, null, null)
    val lidarrBodies = CopyOnWriteArrayList<JSONObject>()
    val shareBodies = CopyOnWriteArrayList<JSONObject>()

    // MockWebServer answers each connection on its own thread: thread-safe.
    val playlists = CopyOnWriteArrayList<Playlist>()
    val entries = mutableMapOf<Long, List<PlaylistEntry>>()
    @Volatile var running = false
    @Volatile var syncPolls = 2 // status reads a started sync stays running for
    val syncAdds = CopyOnWriteArrayList<Playlist>()
    @Volatile var startAnswer: MockResponse? = null // a refusal to answer the start with, instead of 202
    val starts = AtomicInteger()
    private val pollsLeft = AtomicInteger()

    /** A subscription as the server keeps it; [playlistId] is the playlist
     * its import made, removed with it. */
    class Subscription(
        val id: Long, val url: String, var title: String?, var trackCount: Int?, var matchedCount: Int?,
        var lastError: String? = null, var lastSyncedAt: String? = "2026-09-24 04:45:12", val playlistId: Long? = null,
    )
    val subscriptions = CopyOnWriteArrayList<Subscription>()
    val subscribeBodies = CopyOnWriteArrayList<String>() // the url of each POST, as sent
    val refreshes = AtomicInteger()
    val deletes = CopyOnWriteArrayList<Long>()
    private val nextSub = AtomicInteger(100)

    /** A sync started from somewhere else -- the browser -- that runs for
     * [polls] status reads. */
    fun runningElsewhere(polls: Int) {
        pollsLeft.set(polls)
        running = true
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path ?: ""
        val method = request.method
        return when {
            path == "/api/app/playlists" && method == "GET" -> json(JSONArray(playlists.map(::wire)).toString())
            path == "/api/app/playlists/sync" && method == "POST" -> {
                starts.incrementAndGet()
                startAnswer?.let { return it }
                running = true
                pollsLeft.set(syncPolls)
                MockResponse().setResponseCode(202).setBody("""{"status": "started"}""")
            }
            path == "/api/app/playlists/sync/status" -> {
                if (running && pollsLeft.getAndDecrement() <= 0) {
                    running = false
                    playlists += syncAdds
                    syncAdds.clear()
                }
                json("""{"last_result": null, "running": $running}""")
            }
            method == "PATCH" && Regex("/api/app/playlists/(\\d+)").matches(path) -> {
                val id = path.split('/')[4].toLong()
                val body = JSONObject(request.body.readUtf8()).also { shareBodies += it }
                playlists.first { it.id == id }.shared = body.getBoolean("shared")
                json("""{"status": "ok"}""")
            }
            Regex("/api/app/playlists/(\\d+)/unresolved-tracks").matches(path) -> {
                if (unresolvedFails) return MockResponse().setResponseCode(500).setBody("""{"error": "boom"}""")
                val id = path.split('/')[4].toLong()
                json(JSONArray(unresolved[id].orEmpty().map { u ->
                    JSONObject().put("id", u.id).put("artist", u.artist ?: JSONObject.NULL).put("title", u.title ?: JSONObject.NULL)
                        .put("album", u.album ?: JSONObject.NULL).put("excluded", u.excluded).put("isrc", JSONObject.NULL)
                        .put("inferred_album", u.inferredAlbum ?: JSONObject.NULL)
                        .put("first_seen_at", "2026-09-24 04:45:12")
                }).toString())
            }
            Regex("/api/app/playlists/(\\d+)/unresolved-tracks/exclude").matches(path) -> {
                if (unresolvedFails) return MockResponse().setResponseCode(500).setBody("""{"error": "boom"}""")
                val id = path.split('/')[4].toLong()
                val body = JSONObject(request.body.readUtf8()).also { excludeBodies += it }
                val ids = body.getJSONArray("ids").let { a -> List(a.length()) { a.getLong(it) } }
                unresolved[id].orEmpty().filter { it.id in ids }.forEach { it.excluded = body.getBoolean("excluded") }
                json(JSONObject().put("unresolved_count", unresolved[id].orEmpty().count { !it.excluded }).toString())
            }
            Regex("/api/app/playlists/(\\d+)/lidarr-requests").matches(path) -> {
                val id = path.split('/')[4].toLong()
                val body = JSONObject(request.body.readUtf8()).also { lidarrBodies += it }
                val p = playlists.first { it.id == id }
                p.lidarrEnabled = body.getBoolean("enabled")
                val (count, error, code) = lidarrRun
                json(JSONObject().put("lidarr_request_enabled", p.lidarrEnabled)
                    .put("lidarr_request_last_count", if (p.lidarrEnabled) count else 0)
                    .put("lidarr_request_last_run_at", if (p.lidarrEnabled) "2026-09-25 08:00:00" else JSONObject.NULL)
                    .put("lidarr_request_last_error", if (p.lidarrEnabled) error ?: JSONObject.NULL else JSONObject.NULL)
                    .put("lidarr_request_last_error_code", if (p.lidarrEnabled) code ?: JSONObject.NULL else JSONObject.NULL).toString())
            }
            Regex("/api/app/playlists/(\\d+)/tracks").matches(path) -> {
                val id = Regex("/api/app/playlists/(\\d+)/tracks").find(path)!!.groupValues[1].toLong()
                json(JSONArray(entries[id].orEmpty().map { e ->
                    JSONObject().put("position", e.position).put("artist", e.artist ?: JSONObject.NULL).put("title", e.title ?: JSONObject.NULL)
                        .put("album", e.album ?: JSONObject.NULL).put("matched", e.matched)
                }).toString())
            }
            path == "/api/app/playlist-subscriptions" && method == "GET" ->
                json(JSONObject().put("subscriptions", JSONArray(subscriptions.map(::wireSub))).toString())
            path == "/api/app/playlist-subscriptions" && method == "POST" -> {
                val url = JSONObject(request.body.readUtf8()).getString("url")
                subscribeBodies += url
                // The server's own refusal, word for word, for a link it cannot read.
                if ("list=" !in url) {
                    return MockResponse().setResponseCode(400).setBody(
                        JSONObject().put("error", "That doesn't look like a YouTube Music playlist link. " +
                            "Copy the playlist's Share link, or the address bar while it's open.").toString(),
                    )
                }
                // Subscribing imports at once: a playlist appears with it.
                val id = nextSub.getAndIncrement().toLong()
                val playlist = Playlist(id + 1000, "Mix $id", trackCount = 2, matchedCount = 1, provider = "ytmusic")
                playlists += playlist
                val sub = Subscription(id, url, "Mix $id", 2, 1, playlistId = playlist.id)
                subscriptions += sub
                json(JSONObject().put("status", "ok").put("subscription", wireSub(sub)).toString())
            }
            method == "POST" && Regex("/api/app/playlist-subscriptions/(\\d+)/refresh").matches(path) -> {
                val id = Regex("/api/app/playlist-subscriptions/(\\d+)/refresh").find(path)!!.groupValues[1].toLong()
                refreshes.incrementAndGet()
                val sub = subscriptions.first { it.id == id }
                sub.lastError = null
                json(JSONObject().put("status", "ok").put("subscription", wireSub(sub)).toString())
            }
            method == "DELETE" && Regex("/api/app/playlist-subscriptions/(\\d+)").matches(path) -> {
                val id = Regex("/api/app/playlist-subscriptions/(\\d+)").find(path)!!.groupValues[1].toLong()
                deletes += id
                val sub = subscriptions.first { it.id == id }
                subscriptions.remove(sub)
                playlists.removeAll { it.id == sub.playlistId }
                json("""{"status": "ok"}""")
            }
            else -> base.dispatch(request)
        }
    }

    private fun wireSub(s: Subscription) = JSONObject()
        .put("id", s.id).put("url", s.url).put("provider", "ytmusic").put("title", s.title ?: JSONObject.NULL)
        .put("last_synced_at", s.lastSyncedAt ?: JSONObject.NULL).put("last_error", s.lastError ?: JSONObject.NULL)
        .put("last_error_at", JSONObject.NULL).put("track_count", s.trackCount ?: JSONObject.NULL)
        .put("matched_count", s.matchedCount ?: JSONObject.NULL)

    private fun wire(p: Playlist) = JSONObject()
        .put("id", p.id).put("title", p.title).put("source_provider", p.provider ?: JSONObject.NULL)
        .put("inferred_origin_provider", p.inferredOrigin ?: JSONObject.NULL).put("last_synced_at", p.lastSyncedAt ?: JSONObject.NULL)
        .put("owner_user_id", if (p.ownerUsername == null) JSONObject.NULL else 2).put("owner_username", p.ownerUsername ?: JSONObject.NULL)
        .put("is_own", p.isOwn).put("shared", p.shared).put("can_share", p.canShare)
        .put("golden_owner_username", p.goldenOwnerUsername ?: JSONObject.NULL)
        .put("track_count", p.trackCount).put("matched_count", p.matchedCount)
        .put("unresolved_count", unresolved[p.id]?.count { !it.excluded } ?: (p.trackCount - p.matchedCount))
        .put("lidarr_request_configured", p.lidarrConfigured).put("lidarr_request_connected", p.lidarrConnected)
        .put("lidarr_request_enabled", p.lidarrEnabled).put("lidarr_request_has_albums", p.lidarrHasAlbums).put("lidarr_request_last_count", 0).put("lidarr_request_last_error", JSONObject.NULL)
        .put("lidarr_request_last_error_code", JSONObject.NULL).put("lidarr_request_last_run_at", JSONObject.NULL)

    private fun json(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)
}
