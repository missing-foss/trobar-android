// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
// The provenance store's walk: a full walk records the highest
// fingerprint_seq and prunes rows the server no longer lists; later walks
// ask only for newer fingerprints, until a week has passed; rows of tracks
// that left the device are pruned against the sync's own answer too.

package org.missing_foss.trobar

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProvenanceWalkTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: MockWebServer
    private lateinit var store: ProvenanceStore
    private lateinit var api: ApiClient

    @Before
    fun setUp() {
        context.deleteDatabase("provenance.db")
        context.getSharedPreferences("provenance_meta", 0).edit().clear().commit()
        server = MockWebServer().apply { start() }
        store = ProvenanceStore(context)
        api = ApiClient(context, server.url("/").toString().trimEnd('/'), "t0k")
    }

    @After
    fun tearDown() {
        store.close()
        server.shutdown()
    }

    private fun page(vararg rows: Triple<Long, String, Long>) = MockResponse().setBody(
        """{"entries": [${rows.joinToString(",") { (id, path, seq) ->
            """{"track_id": $id, "fingerprint": "fp$id", "path": "$path", "fingerprint_seq": $seq}"""
        }}], "next_after": null, "pending": 0}""",
    )

    @Test
    fun aFullWalkRecordsTheHighWaterMarkAndPrunesWhatTheServerNoLongerLists() = runBlocking {
        store.upsert(99, "Gone/Track.flac", "fpold") // left the device before this walk
        server.enqueue(page(Triple(1L, "A/1.flac", 40L), Triple(2L, "A/2.flac", 41L)))

        syncProvenanceFingerprints(api, store, now = 1_000)

        assertEquals("no computed_after on a first walk", null,
            server.takeRequest().requestUrl!!.queryParameter("computed_after"))
        assertEquals(setOf(1L, 2L), store.trackIds())
        assertEquals(41L, store.highWaterSeq)
        assertEquals(1_000L, store.lastFullWalkAt)
    }

    @Test
    fun aLaterWalkAsksOnlyForNewerFingerprintsAndPrunesNothing() = runBlocking {
        server.enqueue(page(Triple(1L, "A/1.flac", 40L), Triple(2L, "A/2.flac", 41L)))
        syncProvenanceFingerprints(api, store, now = 1_000)
        server.takeRequest()

        server.enqueue(page(Triple(3L, "A/3.flac", 42L)))
        syncProvenanceFingerprints(api, store, now = 2_000)

        assertEquals("41", server.takeRequest().requestUrl!!.queryParameter("computed_after"))
        assertEquals(setOf(1L, 2L, 3L), store.trackIds()) // 1 and 2 weren't listed, and stay
        assertEquals(42L, store.highWaterSeq)
        assertEquals(1_000L, store.lastFullWalkAt)
    }

    @Test
    fun aWeekLaterTheWalkIsFullAgain() = runBlocking {
        server.enqueue(page(Triple(1L, "A/1.flac", 40L), Triple(2L, "A/2.flac", 41L)))
        syncProvenanceFingerprints(api, store, now = 1_000)
        server.takeRequest()

        // Track 2 moved on the device; track 1 left it.
        server.enqueue(page(Triple(2L, "B/2.flac", 41L)))
        syncProvenanceFingerprints(api, store, now = 1_000 + FULL_WALK_INTERVAL_MS)

        assertNull(server.takeRequest().requestUrl!!.queryParameter("computed_after"))
        assertEquals(setOf(2L), store.trackIds())
        assertEquals("B/2.flac", store.unpushed(10).single().path)
    }

    @Test
    fun aFailedWalkRecordsNothing() = runBlocking {
        server.enqueue(page(Triple(1L, "A/1.flac", 40L)))
        syncProvenanceFingerprints(api, store, now = 1_000)
        server.takeRequest()

        server.enqueue(MockResponse().setResponseCode(500))
        try {
            syncProvenanceFingerprints(api, store, now = 2_000)
        } catch (expected: java.io.IOException) { /* the caller swallows it */ }

        assertEquals(40L, store.highWaterSeq)
    }

    @Test
    fun rowsOfTracksThatLeftTheDeviceArePruned() {
        store.upsertAll(listOf(
            FingerprintEntry(1, "fp1", "A/1.flac"), FingerprintEntry(2, "fp2", "A/2.flac"),
            FingerprintEntry(3, "fp3", "A/3.flac"),
        ))
        assertEquals(1, store.prune(setOf(1L, 3L)))
        assertEquals(setOf(1L, 3L), store.trackIds())
    }

    @Test
    fun heldTracksAreWhatWasThereAndWhatComesMinusWhatGoes() {
        val changes = Changes(
            toDownload = listOf(TrackRef(3, "A/3.flac", 1)),
            toDelete = listOf(TrackDeleteRef(1, "A/1.flac")),
            downloaded = listOf(TrackRef(1, "A/1.flac", 1), TrackRef(2, "A/2.flac", 1)),
        )
        assertEquals(setOf(2L, 3L), SyncEngine.heldTrackIds(changes))
    }

    @Test
    fun anUnchangedRowKeepsItsPushedFlagThroughABatch() {
        store.upsertAll(listOf(FingerprintEntry(1, "fp1", "A/1.flac")))
        store.markPushed(listOf(1L))
        store.upsertAll(listOf(FingerprintEntry(1, "fp1", "A/1.flac"), FingerprintEntry(2, "fp2", "A/2.flac")))
        assertEquals(listOf(2L), store.unpushed(10).map { it.trackId })
    }
}
