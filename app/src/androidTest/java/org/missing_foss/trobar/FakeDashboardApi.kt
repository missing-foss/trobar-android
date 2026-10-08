// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject

/** The dashboard half of the App API, in memory, with the server's rules
 * for the widget-preferences PATCH: `disabled` and `order` replace,
 * `settings` merges, and a non-admin's disabled list always carries the
 * admin-only widget. Counts the widget data routes so a test can say
 * which widget fetched. Everything else goes to [base]. */
class FakeDashboardApi(private val base: FakeAppApi = FakeAppApi()) : Dispatcher() {
    var isAdmin = false
    val catalog = listOf(
        "library" to false, "devices" to false, "suggestions" to false, "recently_added" to false,
        "recently_released" to false, "most_played" to false, "administration" to true,
    )
    var disabled = mutableListOf<String>()
    var order = mutableListOf<String>()
    var coverLimit = 15
    // Each cover widget's own count, as the server holds it; unset = the one
    // value above. With perWidgetCovers off the fake is a server older than
    // per-widget counts: it answers only the one value, and refuses a
    // per-widget one, as that server's PATCH validation did.
    val coverLimits: MutableMap<String, Int> = ConcurrentHashMap()
    @Volatile var perWidgetCovers = true
    // MockWebServer answers each connection on its own thread, and the widgets
    // fetch at once: what the handlers record is kept in thread-safe collections.
    val months: MutableMap<String, Int> = ConcurrentHashMap()
    @Volatile var holdFirstPatch = false
    private val secondPatchArrived = java.util.concurrent.CountDownLatch(1)
    val patches = CopyOnWriteArrayList<JSONObject>()
    val hits = ConcurrentHashMap<String, Int>()
    // The three discovery feeds: (artist, album, source) triples; suggestions
    // carry the same names as library names unless a pair is given.
    val recentlyReleased = mutableListOf<Triple<String, String, String>>()
    val recentlyAdded = mutableListOf<Triple<String, String, String>>()
    val suggestions = mutableListOf<Triple<String, String, String>>()
    var suggestionsConfigured = true
    // When set, the suggestions feed waits for it before answering: a slow
    // feed, so a test can look at Home while it is still on its way.
    @Volatile var holdSuggestions: CountDownLatch? = null
    @Volatile var suggestionsFail = false // the feed answers 500, as a Last.fm or ListenBrainz outage would
    val mostPlayed = mutableListOf<Triple<String, String, Int>>() // (artist, album, playcount)
    var mostPlayedConfigured = true
    var users = 3
    var delegations = 1
    var statsJson = """{"total_tracks": 42, "total_duration_seconds": 3600, "by_codec": {"flac": {"tracks": 42, "bytes": 1000}}, "by_decade": {"1990s": 42}}"""
    @Volatile var patchReplyDelayMs = 0L
    val monthsAsked = ConcurrentHashMap<String, MutableList<Int>>()
    val mostPlayedAsked = CopyOnWriteArrayList<Pair<String, Int>>() // (period, limit) as queried

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path ?: ""
        val route = path.substringBefore('?')
        hits.merge(route, 1, Int::plus)
        return when {
            path == "/api/app/whoami" -> json(
                """{"user_id": 1, "username": "alice", "is_admin": $isAdmin, "device_id": 1, "device_name": "Pixel", "app_api": 1}""",
            )
            path == "/api/app/dashboard/catalog" -> json(JSONArray(catalog.map { (id, admin) -> JSONObject().put("id", id).put("admin_only", admin) }).toString())
            path == "/api/app/dashboard/widgets" && request.method == "GET" -> json(prefsJson())
            path == "/api/app/dashboard/widgets" && request.method == "PATCH" -> {
                val body = JSONObject(request.body.readUtf8())
                patches += body
                // With holdFirstPatch, the first write is applied only once a
                // second has arrived (or after a while): a client that sends
                // the second before the first is answered gets them applied
                // out of order, as a real server might.
                if (patches.size == 1 && holdFirstPatch) secondPatchArrived.await(3_000, TimeUnit.MILLISECONDS)
                if (patches.size > 1) secondPatchArrived.countDown()
                if (body.has("disabled")) disabled = body.getJSONArray("disabled").let { a -> MutableList(a.length()) { a.getString(it) } }
                if (body.has("order")) order = body.getJSONArray("order").let { a -> MutableList(a.length()) { a.getString(it) } }
                val settings = body.optJSONObject("settings")
                if (!perWidgetCovers && settings != null &&
                    settings.keys().asSequence().any { settings.optJSONObject(it)?.has("cover_limit") == true }
                ) {
                    return MockResponse().setResponseCode(400).setBody("""{"error": "Unknown widget setting"}""")
                }
                settings?.let { s ->
                    if (s.has("cover_limit")) {
                        coverLimit = s.getInt("cover_limit")
                        coverLimits.clear() // the one value sets all three
                    }
                    s.keys().asSequence().filter { it != "cover_limit" }.forEach { w ->
                        val entry = s.getJSONObject(w)
                        if (entry.has("months")) months[w] = entry.getInt("months")
                        if (entry.has("cover_limit")) coverLimits[w] = entry.getInt("cover_limit")
                    }
                }
                if (!isAdmin && "administration" !in disabled) disabled = (disabled + "administration").sorted().toMutableList()
                // Answered late when asked: the write is stored at once, the reply held back.
                json(prefsJson()).setHeadersDelay(patchReplyDelayMs, TimeUnit.MILLISECONDS)
            }
            route == "/api/app/library/stats" -> json(statsJson)
            route == "/api/app/suggestions/most-played" -> {
                // The query is asserted, not just answered: a client that stopped sending it fails here.
                mostPlayedAsked += request.requestUrl!!.queryParameter("period")!! to request.requestUrl!!.queryParameter("limit")!!.toInt()
                json(JSONObject().put("configured", mostPlayedConfigured).put("items", JSONArray(mostPlayed.map { (a, b, n) ->
                    JSONObject().put("artist", a).put("album", b).put("playcount", n).put("image_url", JSONObject.NULL).put("source", "lastfm")
                })).toString())
            }
            route == "/api/app/admin/counts" -> json("""{"users": $users, "delegations": $delegations}""")
            route == "/api/app/library/recently-added" -> {
                monthsAsked.computeIfAbsent("recently_added") { CopyOnWriteArrayList() } += request.requestUrl!!.queryParameter("months")!!.toInt()
                json(cards(recentlyAdded, "added_at"))
            }
            route == "/api/app/library/recently-released" -> {
                monthsAsked.computeIfAbsent("recently_released") { CopyOnWriteArrayList() } += request.requestUrl!!.queryParameter("months")!!.toInt()
                json(cards(recentlyReleased, "released_at"))
            }
            route == "/api/app/suggestions" && suggestionsFail ->
                MockResponse().setResponseCode(500).setBody(JSONObject().put("error", "Last.fm did not answer").toString())
            route == "/api/app/suggestions" -> holdSuggestions?.await(UI_WAIT_MS, TimeUnit.MILLISECONDS).let { json(JSONObject().put("configured", suggestionsConfigured).put("items", JSONArray(cards(suggestions, null))).toString()) }
            else -> base.dispatch(request)
        }
    }

    fun hits(route: String): Int = hits[route] ?: 0

    private fun cards(list: List<Triple<String, String, String>>, dateKey: String?): String = JSONArray(list.map { (artist, album, source) ->
        JSONObject().put("artist", artist).put("album", album).put("library_artist", artist).put("library_album", album)
            .put("image_url", JSONObject.NULL).put("source", source).also { if (dateKey != null) it.put(dateKey, "2026-09-01") }
    }).toString()

    private fun prefsJson(): String {
        val settings = JSONObject()
        months.forEach { (w, m) -> settings.put(w, JSONObject().put("months", m)) }
        if (perWidgetCovers) {
            listOf("suggestions", "recently_added", "recently_released").forEach { w ->
                (settings.optJSONObject(w) ?: JSONObject().also { settings.put(w, it) }).put("cover_limit", coverLimits[w] ?: coverLimit)
            }
        }
        // The one value, as the server answers it: Suggestions' count.
        settings.put("cover_limit", if (perWidgetCovers) coverLimits["suggestions"] ?: coverLimit else coverLimit)
        return JSONObject().put("disabled", JSONArray(disabled)).put("order", JSONArray(order)).put("settings", settings).toString()
    }

    private fun json(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)
}

/** A widget for the host's tests: fetches library stats and shows the
 * track count, so a test can tell a fetch from a render. */
class StatsTestWidget(override val id: String) : Widget {
    override suspend fun load(clients: WidgetClients, settings: WidgetSettings): Any = clients.dashboard.libraryStats()

    @androidx.compose.runtime.Composable
    override fun Content(data: Any, actions: WidgetActions) {
        androidx.compose.material3.Text("$id shows ${(data as LibraryStats).totalTracks} tracks, covers ${actions.settings.coverLimitOf(id)}")
    }
}
