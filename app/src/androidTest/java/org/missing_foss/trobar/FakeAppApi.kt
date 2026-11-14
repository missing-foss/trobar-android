// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject

/** An in-memory stand-in for the App API's staging loop, answering by
 * route rather than by queue order, so a screen that reads three routes
 * on open and again after every write gets the right answer whatever
 * order it asks in. The shapes are the server's wire shapes; the rules
 * are the server's too: unstaging an item's last device removes it, a
 * send takes the items staged for that device and leaves the rest. */
class FakeAppApi(
    val thisDeviceId: Long = 1L,
    val devices: List<Pair<Long, String>> = listOf(1L to "Pixel", 2L to "Car"),
) : Dispatcher() {
    class Item(val id: Long, val type: String, val target: String, val title: String, val deviceIds: MutableList<Long>)

    val items = CopyOnWriteArrayList<Item>() // read by basket GETs while stages add: copy-on-write
    val ownedBy = mutableMapOf<Long, String>() // device id -> another account's name: pinned, not this account's own
    val icons = mutableMapOf<Long, String>() // device id -> the icon chosen for it; absent, none is
    var usageUsedBytes = 900L
    var usageMaxBytes: Long? = 1000L // the admin-set limit; null when none is set
    var usageReportedTotalBytes: Long? = null // what the device last reported for its whole volume
    val usageFails = mutableSetOf<Long>() // device ids whose usage route answers 500
    var skippedOnSend = 0
    /** After a fan-out, the basket read answers 500: the send went, the read-back did not. */
    var basketReadFailsAfterSend = false
    private var sent = false
    val sends = CopyOnWriteArrayList<List<Long>>()

    // The library half: artists with their albums, similar artists per
    // artist, and what the profile says about the view.
    val albums = linkedMapOf<String, List<Triple<String, Int?, Int>>>() // artist -> (album, year, tracks)
    val similar = mutableMapOf<String, List<String>>()
    val artistImages = mutableSetOf<String>() // artists with a picture; any other gets the server's 404
    val artistImageAsked = mutableListOf<Pair<String, String?>>() // (artist, size) as queried
    var coverViewMode = "list"
    var showReissueYear = false
    // Written on the server's thread, read on the test's: thread-safe, and
    // the writes counted, since the destination is written after the stages.
    val lastDestinations: MutableMap<String, List<Long>> = ConcurrentHashMap()
    @Volatile var lastDestinationWrites = 0
    /** One row of GET selections: on one device, with what to show for it. */
    data class FakeSelection(
        val id: Long, val type: String, val target: String, val title: String?, val device: Long,
        val albumTracks: Int? = null, val appearsOnTracks: Int? = null, val appearsOnOf: Int? = null,
    )
    val selections = CopyOnWriteArrayList<FakeSelection>()
    val selectionRemovals = CopyOnWriteArrayList<Pair<String, String>>() // (type, target) each remove asked
    @Volatile var removalTracks = 0
    @Volatile var removeRefusal: String? = null // the 409's message, when someone else's selection stays
    val autofitTracks = mutableMapOf<Long, Int>() // device id -> auto-fit's tracks; absent, it is off
    val staged = CopyOnWriteArrayList<Triple<String, String, List<Long>>>() // (type, target, device ids) as POSTed
    var failStagingAfter = Int.MAX_VALUE // the (n+1)th POST /basket answers 500, as a server mid-way failure would
    // When set, a POST /basket waits for it before answering: a slow server,
    // so a test can look at the screen while a stage is on its way.
    @Volatile var holdStaging: CountDownLatch? = null
    var malformedAlbums = false // the albums route answers something that is not JSON
    // "Artist||Album" -> its tracks as the server's JSON objects
    val albumTracks = mutableMapOf<String, List<JSONObject>>()
    val playlists = CopyOnWriteArrayList<Triple<Long, String, String>>() // (id, title, source provider)
    val appended = CopyOnWriteArrayList<Pair<Long, List<Long>>>() // (playlist id, track ids), as POSTed
    val created = CopyOnWriteArrayList<String>() // titles, as POSTed
    val appendedAlbums = CopyOnWriteArrayList<String>() // {artist, album}, as POSTed
    val albumArtistRows = mutableListOf<JSONObject>() // what album-artists answers
    val albumArtistAlbums = mutableMapOf<String, List<Triple<String, Int?, Int>>>()
    val appearsOn = mutableMapOf<String, List<JSONObject>>()
    @Volatile var failAppearsOn = false
    @Volatile var dropAppearsOn = false
    val othersPlaylists = mutableSetOf<Long>() // ids of playlists the caller doesn't own
    var isAdmin = false
    var failAlbumTracks = false // the album-tracks route answers 500
    @Volatile var failArtists = false // the artists route answers 500
    @Volatile var refuseArtists = false // the artists route answers 401
    @Volatile var dropArtists = false // the artists route closes the connection: no server there
    private var nextId = 100L

    fun artist(name: String, vararg albums: Triple<String, Int?, Int>) { this.albums[name] = albums.toList() }

    fun stage(id: Long, target: String, vararg deviceIds: Long, type: String = "album") {
        items += Item(id, type, target, target.substringAfter("||"), deviceIds.toMutableList())
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path ?: ""
        val method = request.method
        return when {
            path == "/api/app/whoami" -> json("""{"user_id": 1, "username": "alice", "is_admin": $isAdmin, "device_id": $thisDeviceId, "device_name": "Pixel", "app_api": 1}""")
            path == "/api/app/devices" -> json(JSONArray(devices.map { (id, name) ->
                JSONObject().put("id", id).put("name", name).put("device_type", "phone").put("max_size_bytes", JSONObject.NULL)
                    .put("owner_username", ownedBy[id] ?: "alice").put("is_own", id !in ownedBy).put("is_pinned", id in ownedBy).put("last_seen_at", JSONObject.NULL)
                    .put("sync_status", JSONObject().put("pending_count", 0).put("last_synced_at", JSONObject.NULL))
                    .put("icon", icons[id] ?: JSONObject.NULL)
                    .put("autofit", JSONObject().put("enabled", id in autofitTracks).put("tracks", autofitTracks[id] ?: 0))
            }).toString())
            path == "/api/app/basket" && method == "GET" && sent && basketReadFailsAfterSend ->
                MockResponse().setResponseCode(500).setBody("""{"error": "basket read failed"}""")
            path == "/api/app/basket" && method == "GET" -> json(JSONArray(items.map {
                JSONObject().put("id", it.id).put("type", it.type).put("target", it.target).put("added_at", "2026-09-01 10:00:00")
                    .put("device_ids", JSONArray(it.deviceIds)).put("title", it.title).put("source_provider", JSONObject.NULL).put("missing", false)
            }).toString())
            path == "/api/app/basket" && method == "POST" -> {
                if (staged.size >= failStagingAfter) return MockResponse().setResponseCode(500).setBody("""{"error": "Storage failed"}""")
                val body = JSONObject(request.body.readUtf8())
                val ids = body.getJSONArray("device_ids").let { a -> List(a.length()) { a.getLong(it) } }
                val type = body.getString("type"); val target = body.getString("target")
                holdStaging?.await(UI_WAIT_MS, TimeUnit.MILLISECONDS)
                staged += Triple(type, target, ids)
                // The server's find-or-create: a target already in the basket
                // gains the devices, never a second row.
                val existing = items.find { it.type == type && it.target == target }
                val id = existing?.id ?: nextId++
                if (existing != null) {
                    ids.filterNot(existing.deviceIds::contains).forEach { existing.deviceIds += it }
                } else {
                    items += Item(id, type, target, if (type == "album") target.substringAfter("||") else target, ids.toMutableList())
                }
                json("""{"id": $id}""")
            }
            path == "/api/app/basket/last-destination" && method == "PATCH" -> {
                val body = JSONObject(request.body.readUtf8())
                lastDestinations[body.getString("surface")] = body.getJSONArray("device_ids").let { a -> List(a.length()) { a.getLong(it) } }
                lastDestinationWrites++
                ok()
            }
            path == "/api/app/profile" -> json(
                JSONObject().put("cover_view_mode", coverViewMode).put("show_reissue_year", showReissueYear)
                    .put("basket_last_destinations", JSONObject(lastDestinations.mapValues { JSONArray(it.value) })).toString(),
            )
            path == "/api/app/library/artists" && dropArtists -> MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
            path == "/api/app/library/artists" && refuseArtists -> MockResponse().setResponseCode(401).setBody("""{"error": "refused"}""")
            path == "/api/app/library/artists" && failArtists -> MockResponse().setResponseCode(500).setBody("""{"error": "down"}""")
            path == "/api/app/library/artists" -> json(JSONArray(albums.map { (name, list) ->
                JSONObject().put("artist", name).put("track_count", list.sumOf { it.third }).put("album_count", list.size)
                    .put("albums", JSONArray(list.map { it.first }))
            }).toString())
            path.startsWith("/api/app/library/albums?") -> {
                if (malformedAlbums) return MockResponse().setResponseCode(200).setBody("<html>not json</html>")
                val artist = request.requestUrl!!.queryParameter("artist")!!
                json(JSONArray(albums[artist].orEmpty().map { (album, year, tracks) ->
                    JSONObject().put("album", album).put("year", year ?: JSONObject.NULL).put("reissue_year", JSONObject.NULL).put("track_count", tracks)
                }).toString())
            }
            path == "/api/app/library/album-artists" -> json(JSONArray(albumArtistRows).toString())
            path.startsWith("/api/app/library/album-artist-albums?") -> {
                val artist = request.requestUrl!!.queryParameter("artist")!!
                json(JSONArray(albumArtistAlbums[artist].orEmpty().map { (album, year, tracks) ->
                    JSONObject().put("album", album).put("year", year ?: JSONObject.NULL)
                        .put("reissue_year", JSONObject.NULL).put("track_count", tracks)
                }).toString())
            }
            path.startsWith("/api/app/library/appears-on?") && failAppearsOn ->
                MockResponse().setResponseCode(500).setBody("""{"error": "no"}""")
            path.startsWith("/api/app/library/appears-on?") && dropAppearsOn ->
                MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
            path.startsWith("/api/app/library/appears-on?") -> {
                val artist = request.requestUrl!!.queryParameter("artist")!!
                json(JSONArray(appearsOn[artist].orEmpty()).toString())
            }
            path.startsWith("/api/app/library/album-tracks?") -> {
                if (failAlbumTracks) return MockResponse().setResponseCode(500).setBody("""{"error": "no tracks today"}""")
                val url = request.requestUrl!!
                val key = "${url.queryParameter("artist")}||${url.queryParameter("album")}"
                json(JSONArray(albumTracks[key].orEmpty()).toString())
            }
            path == "/api/app/playlists" && method == "GET" -> json(JSONArray(playlists.map { (id, title, source) ->
                playlistJson(id, title, source)
            }).toString())
            path == "/api/app/playlists" && method == "POST" -> {
                val title = JSONObject(request.body.readUtf8()).getString("title")
                created += title
                MockResponse().setResponseCode(201).setBody(JSONObject().put("id", NEW_PLAYLIST_ID).put("title", title).toString())
            }
            path.startsWith("/api/app/playlists/") && path.endsWith("/tracks") && method == "POST" -> {
                val body = JSONObject(request.body.readUtf8())
                val ids = body.optJSONArray("track_ids") ?: org.json.JSONArray()
                appended += path.split("/")[4].toLong() to List(ids.length()) { ids.getLong(it) }
                body.optJSONArray("albums")?.let { appendedAlbums += it.getJSONObject(0).toString() }
                ok()
            }
            path.startsWith("/api/app/library/similar-artists?") -> {
                val artist = request.requestUrl!!.queryParameter("artist")!!
                json(JSONArray(similar[artist].orEmpty()).toString())
            }
            path.startsWith("/api/app/library/cover?") -> MockResponse().setResponseCode(404)
            path.startsWith("/api/app/library/artist-image?") -> {
                val artist = request.requestUrl!!.queryParameter("artist")!!
                synchronized(artistImageAsked) { artistImageAsked += artist to request.requestUrl!!.queryParameter("size") }
                if (artist in artistImages) png() else MockResponse().setResponseCode(404)
            }
            Regex("/api/app/devices/\\d+/usage").matches(path) -> {
                val id = path.removePrefix("/api/app/devices/").substringBefore('/').toLong()
                if (id in usageFails) return MockResponse().setResponseCode(500).setBody("""{"error": "usage unavailable"}""")
                json(
                    """{"used_bytes": $usageUsedBytes, "max_size_bytes": ${usageMaxBytes ?: "null"}, "track_count": 2, "over_limit": false, "folder_over_limit": false,
                    "reported_free_bytes": null, "reported_total_bytes": ${usageReportedTotalBytes ?: "null"}, "device_used_bytes": null, "reported_foreign_bytes": null,
                    "folder_used_bytes": null, "free_bytes_reported_at": null, "foreign_bytes_reported_at": null}""",
                )
            }
            path == "/api/app/selections" -> json(
                JSONArray(selections.map { s ->
                    JSONObject().put("id", s.id).put("type", s.type).put("target", s.target).put("created_at", "")
                        .put("created_by_username", "alice").put("title", s.title ?: JSONObject.NULL)
                        .put("device_ids", s.device.toString()).put("album_tracks", s.albumTracks ?: JSONObject.NULL)
                        .put(
                            "appears_on",
                            s.appearsOnTracks?.let {
                                JSONObject().put("album_artist", "Various Artists").put("tracks", it).put("of", s.appearsOnOf)
                            } ?: JSONObject.NULL,
                        )
                }).toString(),
            )
            path == "/api/app/selections/removal-count" && method == "POST" -> json("""{"tracks": $removalTracks}""")
            path == "/api/app/selections/remove" && method == "POST" -> {
                val body = JSONObject(request.body.readUtf8())
                selectionRemovals += body.getString("type") to body.getString("target")
                removeRefusal?.let { return MockResponse().setResponseCode(409).setBody("""{"error": "$it"}""") }
                selections.removeAll { it.type == body.getString("type") && it.target == body.getString("target") }
                ok()
            }
            path == "/api/app/basket" && method == "DELETE" -> { items.clear(); ok() }
            path == "/api/app/basket/fan-out" && method == "POST" -> {
                val ids = JSONObject(request.body.readUtf8()).getJSONArray("device_ids").let { a -> List(a.length()) { a.getLong(it) } }
                sends += ids
                sent = true
                val sent = items.filter { it.deviceIds.any(ids::contains) }
                sent.forEach { it.deviceIds.removeAll(ids) }
                items.removeAll { it.deviceIds.isEmpty() }
                json("""{"status": "ok", "count": ${sent.size}, "skipped": $skippedOnSend}""")
            }
            method == "DELETE" && Regex("/api/app/basket/(\\d+)/devices/(\\d+)").matches(path) -> {
                val (item, device) = Regex("/api/app/basket/(\\d+)/devices/(\\d+)").find(path)!!.destructured
                items.find { it.id == item.toLong() }?.deviceIds?.remove(device.toLong())
                items.removeAll { it.deviceIds.isEmpty() }
                ok()
            }
            method == "DELETE" && Regex("/api/app/basket/(\\d+)").matches(path) -> {
                val id = Regex("/api/app/basket/(\\d+)").find(path)!!.groupValues[1].toLong()
                items.removeAll { it.id == id }
                ok()
            }
            else -> MockResponse().setResponseCode(599).setBody("unscripted request: $method $path")
        }
    }

    private fun png(): MockResponse {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return MockResponse().setResponseCode(200).setHeader("Content-Type", "image/png").setBody(Buffer().write(out.toByteArray()))
    }

    private fun json(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)
    private fun ok() = json("""{"status": "ok"}""")
    private fun playlistJson(id: Long, title: String, source: String) = JSONObject()
        .put("id", id).put("title", title).put("source_provider", source).put("is_own", id !in othersPlaylists).put("shared", false)
        .put("can_share", true).put("track_count", 0).put("matched_count", 0).put("unresolved_count", 0)
        .put("lidarr_request_configured", false).put("lidarr_request_connected", false)
        .put("lidarr_request_has_albums", false).put("lidarr_request_enabled", false)

    companion object {
        const val NEW_PLAYLIST_ID = 77L
    }
}
