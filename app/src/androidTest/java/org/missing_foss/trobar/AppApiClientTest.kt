// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
// The App API client against a local HTTP server standing in for
// trobar-server, same pattern as ApiClientTest: each DTO's happy path and
// its optional fields, the server's {"error"} text surfaced, 401 typed, and
// the image URLs it hands TrobarImage.

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
class AppApiClientTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: MockWebServer
    private lateinit var api: AppApiClient

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
        api = AppApiClient(context, server.url("/").toString(), "test-token")
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    @Test
    fun whoAmIParsesTheAccountAndSendsTheToken() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"user_id": 7, "username": "alice", "is_admin": true, "device_id": 3, "device_name": "Phone", "app_api": 1}""",
            ),
        )
        val me = api.whoAmI()
        assertEquals(WhoAmI(7L, "alice", true, 3L, "Phone", 1), me)
        val req = server.takeRequest()
        assertEquals("/api/app/whoami", req.path)
        assertEquals("Bearer test-token", req.getHeader("Authorization"))
    }

    @Test
    fun libraryArtistsParsesCounts() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[{"artist": "Alpha", "track_count": 12, "album_count": 2}, {"artist": "Beta", "track_count": 1, "album_count": 1}]""",
            ),
        )
        val artists = api.libraryArtists()
        assertEquals(listOf(LibraryArtist("Alpha", 12, 2), LibraryArtist("Beta", 1, 1)), artists)
        assertEquals("/api/app/library/artists", server.takeRequest().path)
    }

    @Test
    fun libraryArtistsParsesTheAlbumTitlesWhenTheServerSendsThem() {
        // A server without the field is libraryArtistsParsesCounts: null.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[{"artist": "Alpha", "track_count": 12, "album_count": 2, "albums": ["One", "Two"]},
                    {"artist": "Beta", "track_count": 1, "album_count": 0, "albums": []}]""",
            ),
        )
        assertEquals(
            listOf(LibraryArtist("Alpha", 12, 2, listOf("One", "Two")), LibraryArtist("Beta", 1, 0, emptyList())),
            api.libraryArtists(),
        )
    }

    @Test
    fun libraryAlbumsKeepsNullYearsAndEncodesTheArtist() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[{"album": "X", "year": 1999, "reissue_year": 2019, "track_count": 10},
                    {"album": "Y", "year": null, "reissue_year": null, "track_count": 3}]""",
            ),
        )
        val albums = api.libraryAlbums("Ålpha & Co")
        assertEquals(listOf(LibraryAlbum("X", 1999, 2019, 10), LibraryAlbum("Y", null, null, 3)), albums)
        assertNull(albums[1].year)
        assertEquals("/api/app/library/albums?artist=%C3%85lpha%20%26%20Co", server.takeRequest().path)
    }

    @Test
    fun similarArtistsIsAListOfNames() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""["Beta", "Gamma"]"""))
        assertEquals(listOf("Beta", "Gamma"), api.similarArtists("Alpha"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        assertTrue(api.similarArtists("Nobody").isEmpty())
    }

    @Test
    fun theServersErrorTextIsSurfaced() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error": "Library unavailable"}"""))
        try {
            api.libraryArtists()
            fail("expected IOException")
        } catch (e: IOException) {
            assertFalse(e is UnauthorizedException)
            assertEquals("Library unavailable", e.message)
        }
    }

    @Test
    fun aRejectedTokenIsTyped() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error": "Invalid or revoked token"}"""))
        try {
            api.whoAmI()
            fail("expected UnauthorizedException")
        } catch (e: UnauthorizedException) {
            assertEquals("Invalid or revoked token", e.message)
        }
    }

    @Test
    fun imageUrlsPointAtTheAppApiAndCarryNoToken() {
        val base = server.url("/").toString().trimEnd('/')
        assertEquals("$base/api/app/library/cover?artist=A%2FB&album=C%3FD", api.coverUrl("A/B", "C?D"))
        assertEquals("$base/api/app/library/artist-image?artist=Alpha", api.artistImageUrl("Alpha"))
        assertEquals("$base/api/app/library/artist-image?artist=Alpha&size=small", api.artistImageUrl("Alpha", small = true))
        assertFalse(api.coverUrl("A", "B").contains("test-token"))
    }

    // --- the staging loop

    @Test
    fun basketParsesItemsAndTheirDevicesAndAMissingTarget() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[{"id": 5, "type": "album", "target": "Alpha||X", "added_at": "2026-09-01T10:00:00", "device_ids": [1, 2],
                     "title": "X", "source_provider": null, "missing": false},
                    {"id": 6, "type": "playlist", "target": "9", "added_at": "2026-09-01T10:01:00", "device_ids": [2],
                     "title": null, "source_provider": null, "missing": true}]""",
            ),
        )
        val items = api.basket()
        assertEquals(
            listOf(
                BasketItem(5L, "album", "Alpha||X", "2026-09-01T10:00:00", listOf(1L, 2L), "X", null, false),
                BasketItem(6L, "playlist", "9", "2026-09-01T10:01:00", listOf(2L), null, null, true),
            ),
            items,
        )
        assertEquals("/api/app/basket", server.takeRequest().path)
    }

    @Test
    fun addToBasketSendsTheServersVocabularyAndReturnsTheId() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id": 42}"""))
        assertEquals(42L, api.addToBasket("album", "Alpha||X", listOf(1L, 3L)))
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/app/basket", req.path)
        val body = JSONObject(req.body.readUtf8())
        assertEquals("album", body.getString("type"))
        assertEquals("Alpha||X", body.getString("target"))
        assertEquals(listOf(1L, 3L), List(body.getJSONArray("device_ids").length()) { body.getJSONArray("device_ids").getLong(it) })
    }

    @Test
    fun removalsHitTheItemAndTheDeviceLinkRoutes() {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status": "ok"}""")) }
        api.removeBasketItem(5L)
        api.unstageDevice(5L, 2L)
        api.clearBasket()
        val paths = List(3) { server.takeRequest().let { "${it.method} ${it.path}" } }
        assertEquals(listOf("DELETE /api/app/basket/5", "DELETE /api/app/basket/5/devices/2", "DELETE /api/app/basket"), paths)
    }

    @Test
    fun fanOutSendsTheDevicesAndReportsCountAndSkipped() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status": "ok", "count": 2, "skipped": 1}"""))
        assertEquals(FanOutResult(2, 1), api.fanOut(listOf(7L)))
        val req = server.takeRequest()
        assertEquals("POST /api/app/basket/fan-out", "${req.method} ${req.path}")
        assertEquals("[7]", JSONObject(req.body.readUtf8()).getJSONArray("device_ids").toString())
    }

    @Test
    fun lastDestinationIsPatchedPerSurface() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status": "ok"}"""))
        api.setLastDestination("app-library", listOf(1L, 2L))
        val req = server.takeRequest()
        assertEquals("PATCH /api/app/basket/last-destination", "${req.method} ${req.path}")
        val body = JSONObject(req.body.readUtf8())
        assertEquals("app-library", body.getString("surface"))
        assertEquals("[1,2]", body.getJSONArray("device_ids").toString())
    }

    @Test
    fun devicesParseOwnershipFlagsAndSyncStatus() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[{"id": 1, "name": "Phone", "device_type": "phone", "max_size_bytes": 32000000000, "owner_username": "alice",
                     "is_own": true, "is_pinned": false, "last_seen_at": "2026-09-01T10:00:00",
                     "sync_status": {"pending_count": 3, "last_synced_at": null}, "autofit": {"enabled": false, "percent": 80}, "icon": "clickwheel"},
                    {"id": 2, "name": "Car", "device_type": "dap", "max_size_bytes": null, "owner_username": "bob",
                     "is_own": false, "is_pinned": true, "last_seen_at": null,
                     "sync_status": {"pending_count": 0, "last_synced_at": "2026-08-30T08:00:00"}, "icon": null}]""",
            ),
        )
        val devices = api.devices()
        assertEquals(
            listOf(
                AppDevice(1L, "Phone", "phone", 32_000_000_000L, "alice", true, false, "2026-09-01T10:00:00", DeviceSyncStatus(3, null), autofit = DeviceAutofit(false, 0), icon = "clickwheel"),
                AppDevice(2L, "Car", "dap", null, "bob", false, true, null, DeviceSyncStatus(0, "2026-08-30T08:00:00")),
            ),
            devices,
        )
    }

    @Test
    fun deviceUsageKeepsNeverReportedAsNull() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"used_bytes": 1500, "max_size_bytes": null, "track_count": 3, "over_limit": false, "folder_over_limit": false,
                    "reported_free_bytes": null, "reported_total_bytes": null, "device_used_bytes": null,
                    "reported_foreign_bytes": null, "folder_used_bytes": null,
                    "free_bytes_reported_at": null, "foreign_bytes_reported_at": null}""",
            ),
        )
        assertEquals(DeviceUsage(1500L, null, 3, false, false, null, null, null, null, null, null, null), api.deviceUsage(1L))
        assertEquals("/api/app/devices/1/usage", server.takeRequest().path)
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"used_bytes": 900, "max_size_bytes": 1000, "track_count": 2, "over_limit": false, "folder_over_limit": true,
                    "reported_free_bytes": 100, "reported_total_bytes": 2000, "device_used_bytes": 1900,
                    "reported_foreign_bytes": 300, "folder_used_bytes": 1200,
                    "free_bytes_reported_at": "2026-09-01T10:00:00", "foreign_bytes_reported_at": "2026-09-01T09:00:00"}""",
            ),
        )
        assertEquals(
            DeviceUsage(900L, 1000L, 2, false, true, 100L, 2000L, 1900L, 300L, 1200L, "2026-09-01T10:00:00", "2026-09-01T09:00:00"),
            api.deviceUsage(1L),
        )
    }

    @Test
    fun selectionsParseWhoMadeThemAndForWhichDevices() {
        // The server joins a selection's device ids into one string
        // ("1,2"; null when it has none) -- not a list as on the basket.
        // The fixture is that wire shape, so a client that only reads
        // lists comes back empty here rather than passing against a fiction.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[{"id": 11, "type": "artist", "target": "Alpha", "created_at": "2026-09-01T10:00:00", "created_by_user_id": 1,
                     "created_by_username": "alice", "device_ids": "1,2"},
                    {"id": 12, "type": "album", "target": "Alpha||X", "created_at": "2026-09-01T10:01:00", "created_by_user_id": 1,
                     "created_by_username": "alice", "device_ids": null}]""",
            ),
        )
        assertEquals(
            listOf(
                Selection(11L, "artist", "Alpha", "2026-09-01T10:00:00", "alice", listOf(1L, 2L)),
                Selection(12L, "album", "Alpha||X", "2026-09-01T10:01:00", "alice", emptyList()),
            ),
            api.selections(),
        )
    }

    @Test
    fun aRefusedStagingCarriesTheServersReason() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error": "Choose at least one device."}"""))
        try {
            api.addToBasket("album", "Alpha||X", emptyList())
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals("Choose at least one device.", e.message)
        }
    }

    @Test
    fun profileViewParsesTheThreeThingsThePhoneFollows() {
        server.enqueue(ok("""{"cover_view_mode": "grid", "show_reissue_year": true, "basket_last_destinations": {"app-library": [1, 2], "library": [3]}}"""))
        assertEquals(ProfileView("grid", true, mapOf("app-library" to listOf(1L, 2L), "library" to listOf(3L))), api.profileView())
        assertEquals("/api/app/profile", server.takeRequest().path)
        server.enqueue(ok("""{"cover_view_mode": "list", "show_reissue_year": false, "basket_last_destinations": {}}"""))
        assertEquals(ProfileView("list", false, emptyMap()), api.profileView())
    }
}
