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
    val staged = CopyOnWriteArrayList<Triple<String, String, List<Long>>>() // (type, target, device ids) as POSTed
    var failStagingAfter = Int.MAX_VALUE // the (n+1)th POST /basket answers 500, as a server mid-way failure would
    // When set, a POST /basket waits for it before answering: a slow server,
    // so a test can look at the screen while a stage is on its way.
    @Volatile var holdStaging: CountDownLatch? = null
    var malformedAlbums = false // the albums route answers something that is not JSON
    private var nextId = 100L

    fun artist(name: String, vararg albums: Triple<String, Int?, Int>) { this.albums[name] = albums.toList() }

    fun stage(id: Long, target: String, vararg deviceIds: Long, type: String = "album") {
        items += Item(id, type, target, target.substringAfter("||"), deviceIds.toMutableList())
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path ?: ""
        val method = request.method
        return when {
            path == "/api/app/whoami" -> json("""{"user_id": 1, "username": "alice", "is_admin": false, "device_id": $thisDeviceId, "device_name": "Pixel", "app_api": 1}""")
            path == "/api/app/devices" -> json(JSONArray(devices.map { (id, name) ->
                JSONObject().put("id", id).put("name", name).put("device_type", "phone").put("max_size_bytes", JSONObject.NULL)
                    .put("owner_username", ownedBy[id] ?: "alice").put("is_own", id !in ownedBy).put("is_pinned", id in ownedBy).put("last_seen_at", JSONObject.NULL)
                    .put("sync_status", JSONObject().put("pending_count", 0).put("last_synced_at", JSONObject.NULL))
                    .put("icon", icons[id] ?: JSONObject.NULL)
            }).toString())
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
            path == "/api/app/basket" && method == "DELETE" -> { items.clear(); ok() }
            path == "/api/app/basket/fan-out" && method == "POST" -> {
                val ids = JSONObject(request.body.readUtf8()).getJSONArray("device_ids").let { a -> List(a.length()) { a.getLong(it) } }
                sends += ids
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
}
