// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
// The dashboard half of the App API client against a local HTTP server.
// Every fixture is the server's own wire shape -- copied from what its
// routes emit (its tests and shared functions), never written from these
// DTOs -- so a parser that reads a key the server does not send fails here.

package org.missing_foss.trobar

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class AppApiDashboardClientTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: MockWebServer
    private lateinit var api: AppApiDashboardClient

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
        api = AppApiDashboardClient(context, server.url("/").toString(), "test-token")
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    @Test
    fun catalogKeepsTheServersOrderAndFlag() {
        server.enqueue(
            ok(
                """[{"id": "library", "admin_only": false}, {"id": "devices", "admin_only": false},
                    {"id": "administration", "admin_only": true}]""",
            ),
        )
        assertEquals(
            listOf(WidgetCatalogEntry("library", false), WidgetCatalogEntry("devices", false), WidgetCatalogEntry("administration", true)),
            api.catalog(),
        )
        val req = server.takeRequest()
        assertEquals("/api/app/dashboard/catalog", req.path)
        assertEquals("Bearer test-token", req.getHeader("Authorization"))
    }

    @Test
    fun widgetPrefsParseTheStoredShapeIncludingAFreshAccount() {
        server.enqueue(ok("""{"disabled": [], "order": [], "settings": {"cover_limit": 15}}"""))
        assertEquals(WidgetPrefs(emptyList(), emptyList(), WidgetSettings(15, emptyMap())), api.widgetPrefs())
        server.enqueue(
            ok("""{"disabled": ["administration", "devices"], "order": ["devices", "library"],
                   "settings": {"cover_limit": 30, "recently_added": {"months": 6}}}"""),
        )
        assertEquals(
            WidgetPrefs(listOf("administration", "devices"), listOf("devices", "library"), WidgetSettings(30, mapOf("recently_added" to 6))),
            api.widgetPrefs(),
        )
    }

    @Test
    fun patchSendsOnlyWhatWasGivenWithMonthsNested() {
        server.enqueue(ok("""{"disabled": ["administration"], "order": [], "settings": {"cover_limit": 15, "recently_released": {"months": 12}}}"""))
        val prefs = api.patchWidgetPrefs(months = mapOf("recently_released" to 12))
        assertEquals(WidgetSettings(15, mapOf("recently_released" to 12)), prefs.settings)
        val req = server.takeRequest()
        assertEquals("PATCH /api/app/dashboard/widgets", "${req.method} ${req.path}")
        val body = JSONObject(req.body.readUtf8())
        assertEquals(setOf("settings"), body.keys().asSequence().toSet()) // no disabled/order: they would replace
        assertEquals("""{"recently_released":{"months":12}}""", body.getJSONObject("settings").toString())

        server.enqueue(ok("""{"disabled": ["administration", "most_played"], "order": ["devices"], "settings": {"cover_limit": 45}}"""))
        api.patchWidgetPrefs(disabled = listOf("most_played"), order = listOf("devices"), coverLimit = 45)
        val second = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(setOf("disabled", "order", "settings"), second.keys().asSequence().toSet())
        assertEquals("""["most_played"]""", second.getJSONArray("disabled").toString())
        assertEquals(45, second.getJSONObject("settings").getInt("cover_limit"))
    }

    @Test
    fun libraryStatsParseCodecsAndDecades() {
        server.enqueue(
            ok("""{"total_tracks": 2, "total_duration_seconds": 300,
                   "by_codec": {"flac": {"tracks": 1, "bytes": 100}, "mp3": {"tracks": 1, "bytes": 50}},
                   "by_decade": {"2000s": 1, "1990s": 1}}"""),  // out of order on purpose: the client sorts
        )
        val stats = api.libraryStats()
        assertEquals(2, stats.totalTracks)
        assertEquals(300L, stats.totalDurationSeconds)
        assertEquals(mapOf("flac" to CodecStats(1, 100L), "mp3" to CodecStats(1, 50L)), stats.byCodec)
        assertEquals(listOf("1990s", "2000s"), stats.byDecade.keys.toList())
        assertEquals("/api/app/library/stats", server.takeRequest().path)
    }

    @Test
    fun recentFeedsCarryTheLibraryNamesAndTheirDate() {
        server.enqueue(
            ok("""[{"artist": "Alpha", "album": "X", "added_at": "2026-09-01 10:00:00", "library_artist": "Alpha",
                    "library_album": "X", "image_url": null, "source": "recent"}]"""),
        )
        val added = api.recentlyAdded(3)
        assertEquals(listOf(AlbumCard("Alpha", "X", "Alpha", "X", null, "recent", "2026-09-01 10:00:00", null, null)), added)
        assertEquals("/api/app/library/recently-added?months=3", server.takeRequest().path)

        server.enqueue(
            ok("""[{"artist": "Alpha", "album": "Y", "released_at": "2026-08-15", "library_artist": "Alpha",
                    "library_album": "Y", "image_url": null, "source": "recent"}]"""),
        )
        val released = api.recentlyReleased(6)
        assertEquals("2026-08-15", released[0].releasedAt)
        assertNull(released[0].addedAt)
        assertEquals("/api/app/library/recently-released?months=6", server.takeRequest().path)
    }

    @Test
    fun suggestionsSayWhetherAHistoryIsConfiguredAndMixSources() {
        server.enqueue(
            ok("""{"configured": false, "items": [
                    {"artist": "Alpha", "album": "X", "added_at": "2026-09-01 10:00:00", "library_artist": "Alpha",
                     "library_album": "X", "image_url": null, "source": "recent"}]}"""),
        )
        val feed = api.suggestions("6month")
        assertFalse(feed.configured)
        assertEquals("recent", feed.items[0].source)
        assertNull(feed.items[0].playcount)
        assertEquals("/api/app/suggestions?period=6month", server.takeRequest().path)

        server.enqueue(
            ok("""{"configured": true, "items": [
                    {"artist": "Beta", "album": "Z", "playcount": 9, "library_artist": "Beta", "library_album": "Z",
                     "image_url": "https://images.invalid/z.jpg", "source": "lastfm"}]}"""),
        )
        val withHistory = api.suggestions("overall")
        assertTrue(withHistory.configured)
        assertEquals(AlbumCard("Beta", "Z", "Beta", "Z", "https://images.invalid/z.jpg", "lastfm", null, null, 9), withHistory.items[0])
    }

    @Test
    fun mostPlayedItemsHaveNoLibraryNamesOrSource() {
        // The chart is the listening history as reported, not filtered to
        // the library: the server sends only these four keys per item.
        server.enqueue(
            ok("""{"configured": true, "items": [{"artist": "Beta", "album": "Z", "playcount": 9, "image_url": null}]}"""),
        )
        val feed = api.mostPlayed("3month", 8)
        assertEquals(listOf(AlbumCard("Beta", "Z", null, null, null, null, null, null, 9)), feed.items)
        assertEquals("/api/app/suggestions/most-played?period=3month&limit=8", server.takeRequest().path)
    }

    @Test
    fun adminCountsParseAndARefusalCarriesTheServersMessage() {
        server.enqueue(ok("""{"users": 3, "delegations": 1}"""))
        assertEquals(AdminCounts(3, 1), api.adminCounts())
        assertEquals("/api/app/admin/counts", server.takeRequest().path)
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error": "Admin access required"}"""))
        try {
            api.adminCounts()
            fail("expected IOException")
        } catch (e: IOException) {
            assertFalse(e is UnauthorizedException)
            assertEquals("Admin access required", e.message)
        }
    }
}
