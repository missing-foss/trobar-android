// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
// The playlist half of the App API client against a local HTTP server.
// Every fixture is a response the server's own routes produced -- captured
// from the merged server with its test client and seed data, pasted as it
// came -- never written from these DTOs.

package org.missing_foss.trobar

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
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

@RunWith(AndroidJUnit4::class)
class AppApiPlaylistClientTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: MockWebServer
    private lateinit var api: AppApiPlaylistClient

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
        api = AppApiPlaylistClient(context, server.url("/").toString(), "test-token")
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    private fun answer(code: Int, body: String) = MockResponse().setResponseCode(code).setBody(body)

    @Test
    fun playlistsCarryTheViewersAttributionAndWhatTheyMayChange() {
        server.enqueue(answer(200, PLAYLISTS))
        val rows = api.playlists().associateBy { it.title }
        val own = rows.getValue("Road Trip")
        assertTrue(own.isOwn)
        assertFalse(own.shared)
        assertTrue(own.canShare)
        assertEquals("owner", own.ownerUsername)
        assertEquals(2, own.trackCount)
        assertEquals(1, own.matchedCount)
        assertEquals(1, own.unresolvedCount)
        assertFalse(own.lidarr.run.enabled)
        assertFalse(own.lidarr.configured)
        val household = rows.getValue("Household")
        assertFalse(household.isOwn)
        assertFalse(household.canShare)
        assertNull(household.ownerUsername)
        assertNull(household.goldenOwnerUsername)
        val req = server.takeRequest()
        assertEquals("/api/app/playlists", req.path)
        assertEquals("Bearer test-token", req.getHeader("Authorization"))
    }

    @Test
    fun canShareIsItsOwnFieldNotWhetherThePlaylistIsYours() {
        // An admin viewing another user's playlist: not theirs, and theirs to share.
        server.enqueue(answer(200, PLAYLISTS_AS_ADMIN))
        val roadTrip = api.playlists().single { it.title == "Road Trip" }
        assertFalse(roadTrip.isOwn)
        assertTrue(roadTrip.canShare)
    }

    @Test
    fun entriesKeepThePlaylistsOrderAndWhetherEachResolved() {
        server.enqueue(answer(200, TRACKS))
        assertEquals(
            listOf(
                PlaylistEntry(1, "A", "One", "B", true, trackId = 9, libraryArtist = "Various", libraryAlbum = "Mix"),
                PlaylistEntry(2, "C", "Two", null, false),
            ),
            api.entries(7),
        )
        assertEquals("/api/app/playlists/7/tracks", server.takeRequest().path)
    }

    @Test
    fun anEntryFromAServerWithoutLibraryNamesOpensItsOwn() {
        val e = PlaylistEntry(1, "A", "One", "B", true, trackId = 9)
        assertEquals("A" to "B", e.openArtist to e.openAlbum)
        val filed = e.copy(libraryArtist = "Various", libraryAlbum = "Mix")
        assertEquals("Various" to "Mix", filed.openArtist to filed.openAlbum)
    }

    @Test
    fun aSyncStartsOrSaysWhyNot() {
        server.enqueue(answer(202, SYNC_START))
        api.startSync()
        assertEquals("POST", server.takeRequest().method)
        server.enqueue(answer(409, SYNC_BUSY))
        try {
            api.startSync()
            fail("a running sync is not a second start")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("already running"))
        }
        server.enqueue(answer(200, SYNC_STATUS))
        val status = api.syncStatus()
        assertFalse(status.running)
        assertNull(status.lastResult)
    }

    @Test
    fun theSharedFlagIsTheOneFieldWritten() {
        server.enqueue(answer(200, SHARE))
        api.setShared(7, true)
        val req = server.takeRequest()
        assertEquals("PATCH", req.method)
        assertEquals("/api/app/playlists/7", req.path)
        assertEquals("{\"shared\":true}", req.body.readUtf8())
    }

    @Test
    fun anAlbumLookedUpInMusicBrainzIsReadApartFromTheSourcesOwn() {
        server.enqueue(answer(200, UNRESOLVED_INFERRED))
        assertEquals(listOf(UnresolvedEntry(1, "C", "Two", "", false, inferredAlbum = "Found")), api.unresolved(7))
    }

    @Test
    fun unresolvedEntriesAreReadAndExcluded() {
        server.enqueue(answer(200, UNRESOLVED))
        val rows = api.unresolved(7)
        // A server too old to look albums up sends no inferred_album at all.
        assertEquals(listOf(UnresolvedEntry(1, "C", "Two", null, false, inferredAlbum = null)), rows)
        server.takeRequest()
        server.enqueue(answer(200, EXCLUDE))
        assertEquals(0, api.setExcluded(7, listOf(rows[0].id), excluded = true))
        val req = server.takeRequest()
        assertEquals("/api/app/playlists/7/unresolved-tracks/exclude", req.path)
        val body = JSONObject(req.body.readUtf8())
        assertEquals(rows[0].id, body.getJSONArray("ids").getLong(0))
        assertTrue(body.getBoolean("excluded"))
    }

    @Test
    fun theLidarrToggleAnswersItsRunOnly() {
        server.enqueue(answer(200, LIDARR))
        val run = api.setLidarrRequests(7, enabled = true)
        assertTrue(run.enabled)
        assertEquals("unset_target", run.lastErrorCode)
        assertNull(run.lastRunAt)
        assertTrue(JSONObject(server.takeRequest().body.readUtf8()).getBoolean("enabled"))
    }

    @Test
    fun subscriptionsListAddAndSayWhenAnImportFailed() {
        server.enqueue(answer(200, SUBSCRIPTIONS))
        val subs = api.subscriptions()
        assertEquals(1, subs.size)
        assertEquals("Mix", subs[0].title)
        assertEquals("ytmusic", subs[0].provider)
        server.takeRequest()
        server.enqueue(answer(200, SUBSCRIBE))
        val ok = api.subscribe("https://music.youtube.com/playlist?list=PLabc123")
        assertEquals("ok", ok.status)
        assertEquals(1, ok.subscription.trackCount)
        assertEquals("https://music.youtube.com/playlist?list=PLabc123", JSONObject(server.takeRequest().body.readUtf8()).getString("url"))
        // Accepted as an id but not importable: a 200 whose status says so, with nothing counted.
        server.enqueue(answer(200, SUBSCRIBE_FAILED))
        val failed = api.subscribe("nope")
        assertEquals("error", failed.status)
        assertEquals("error", failed.subscription.lastError)
        assertNull(failed.subscription.trackCount)
        assertNull(failed.subscription.title)
        server.takeRequest()
        server.enqueue(answer(200, SHARE))
        api.unsubscribe(1)
        val del = server.takeRequest()
        assertEquals("DELETE", del.method)
        assertEquals("/api/app/playlist-subscriptions/1", del.path)
    }

    private companion object {
        const val PLAYLISTS = """[{"can_share": false, "golden_owner_username": null, "id": 2, "inferred_origin_provider": null, "is_own": false, "last_synced_at": "2026-09-20 10:00:00", "lidarr_request_configured": false, "lidarr_request_connected": false, "lidarr_request_enabled": false, "lidarr_request_has_albums": false, "lidarr_request_last_count": 0, "lidarr_request_last_error": null, "lidarr_request_last_error_code": null, "lidarr_request_last_run_at": null, "matched_count": 0, "owner_user_id": null, "owner_username": null, "shared": true, "source_provider": "roon", "title": "Household", "track_count": 0, "unresolved_count": 0}, {"can_share": true, "golden_owner_username": null, "id": 1, "inferred_origin_provider": null, "is_own": true, "last_synced_at": "2026-09-20 10:00:00", "lidarr_request_configured": false, "lidarr_request_connected": false, "lidarr_request_enabled": false, "lidarr_request_has_albums": false, "lidarr_request_last_count": 0, "lidarr_request_last_error": null, "lidarr_request_last_error_code": null, "lidarr_request_last_run_at": null, "matched_count": 1, "owner_user_id": 2, "owner_username": "owner", "shared": false, "source_provider": "roon", "title": "Road Trip", "track_count": 2, "unresolved_count": 1}]"""
        const val PLAYLISTS_AS_ADMIN = """[{"can_share": false, "golden_owner_username": null, "id": 2, "inferred_origin_provider": null, "is_own": false, "last_synced_at": "2026-09-20 10:00:00", "lidarr_request_configured": false, "lidarr_request_connected": false, "lidarr_request_enabled": false, "lidarr_request_has_albums": false, "lidarr_request_last_count": 0, "lidarr_request_last_error": null, "lidarr_request_last_error_code": null, "lidarr_request_last_run_at": null, "matched_count": 0, "owner_user_id": null, "owner_username": null, "shared": true, "source_provider": "roon", "title": "Household", "track_count": 0, "unresolved_count": 0}, {"can_share": true, "golden_owner_username": null, "id": 1, "inferred_origin_provider": null, "is_own": false, "last_synced_at": "2026-09-20 10:00:00", "lidarr_request_configured": false, "lidarr_request_connected": false, "lidarr_request_enabled": false, "lidarr_request_has_albums": false, "lidarr_request_last_count": 0, "lidarr_request_last_error": null, "lidarr_request_last_error_code": null, "lidarr_request_last_run_at": null, "matched_count": 1, "owner_user_id": 2, "owner_username": "owner", "shared": false, "source_provider": "roon", "title": "Road Trip", "track_count": 2, "unresolved_count": 1}]"""
        const val TRACKS = """[{"album": "B", "artist": "A", "library_album": "Mix", "library_artist": "Various", "matched": true, "position": 1, "title": "One", "track_id": 9}, {"album": null, "artist": "C", "matched": false, "position": 2, "title": "Two"}]"""
        const val SYNC_START = """{"status": "started"}"""
        const val SYNC_BUSY = """{"error": "A playlist sync is already running."}"""
        const val SYNC_STATUS = """{"last_result": null, "running": false}"""
        const val SHARE = """{"status": "ok"}"""
        const val UNRESOLVED = """[{"album": null, "artist": "C", "excluded": false, "first_seen_at": "2026-09-24 04:45:12", "id": 1, "isrc": null, "title": "Two"}]"""
        const val UNRESOLVED_INFERRED = """[{"album": "", "artist": "C", "excluded": false, "first_seen_at": "2026-09-24 04:45:12", "id": 1, "inferred_album": "Found", "isrc": null, "title": "Two"}]"""
        const val EXCLUDE = """{"unresolved_count": 0}"""
        const val LIDARR = """{"lidarr_request_enabled": true, "lidarr_request_last_count": 0, "lidarr_request_last_error": null, "lidarr_request_last_error_code": "unset_target", "lidarr_request_last_run_at": null}"""
        const val SUBSCRIPTIONS = """{"subscriptions": [{"id": 1, "last_error": null, "last_error_at": null, "last_synced_at": "2026-09-24 04:45:12", "matched_count": 0, "provider": "ytmusic", "title": "Mix", "track_count": 1, "url": "https://music.youtube.com/playlist?list=PLabc123"}]}"""
        const val SUBSCRIBE = """{"status": "ok", "subscription": {"id": 1, "last_error": null, "last_error_at": null, "last_synced_at": "2026-09-24 04:45:12", "matched_count": 0, "provider": "ytmusic", "title": "Mix", "track_count": 1, "url": "https://music.youtube.com/playlist?list=PLabc123"}}"""
        const val SUBSCRIBE_FAILED = """{"status": "error", "subscription": {"id": 2, "last_error": "error", "last_error_at": "2026-09-24 04:45:13", "last_synced_at": null, "matched_count": null, "provider": "ytmusic", "title": null, "track_count": null, "url": "nope"}}"""
    }
}
