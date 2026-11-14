// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** #239/#81: a local record of "this synced track's identity, as the
 * server itself assigned it" — track_id, the server-computed fingerprint,
 * and the on-device path it was written to. Clients never compute
 * fingerprints; this is purely a store of what
 * [ApiClient.getFingerprintsPage] returns, later replayed back via
 * [ApiClient.pushProvenance] for recovery (#85).
 *
 * Lives in app-private storage — same survival domain as the pairing
 * itself (Prefs' DataStore file). An app data wipe or uninstall loses
 * both together, which is fine: losing the pairing already forces
 * re-enrollment, and re-enrollment naturally re-populates this table
 * fresh from the server via another fingerprints walk. This is a
 * disposable cache of server-asserted identity, not a second source of
 * truth that needs its own backup story.
 *
 * Hand-rolled SQLiteOpenHelper, not Room: this app has no ORM anywhere
 * else (matches the "avoid framework deps we don't strictly need" ethos
 * already visible in TokenCrypto skipping androidx.security, and
 * build.gradle.kts's own note on org.json), and the schema here is one
 * flat table with no relations to justify one. */
class ProvenanceStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    // The walk's bookkeeping between syncs: the highest fingerprint_seq seen
    // (the next walk asks only for newer ones) and when the last full walk
    // ran (see syncProvenanceFingerprints). Kept beside the database, wiped
    // with it.
    private val meta = context.applicationContext.getSharedPreferences(META_NAME, Context.MODE_PRIVATE)

    var highWaterSeq: Long?
        get() = if (meta.contains(KEY_HIGH_WATER)) meta.getLong(KEY_HIGH_WATER, 0) else null
        set(value) {
            meta.edit().apply { if (value == null) remove(KEY_HIGH_WATER) else putLong(KEY_HIGH_WATER, value) }.apply()
        }

    var lastFullWalkAt: Long
        get() = meta.getLong(KEY_LAST_FULL_WALK, 0)
        set(value) = meta.edit().putLong(KEY_LAST_FULL_WALK, value).apply()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE provenance (" +
                "track_id INTEGER PRIMARY KEY, " +
                "path TEXT NOT NULL, " +
                "fingerprint TEXT NOT NULL, " +
                "pushed INTEGER NOT NULL DEFAULT 0)",
        )
        db.execSQL("CREATE INDEX idx_provenance_path ON provenance(path)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS provenance")
        onCreate(db)
    }

    /** Upsert keyed on track_id — the server's own stable identity for a
     * track row, and the cursor key [ApiClient.getFingerprintsPage] walks
     * on — not on path, since a re-tag/re-encode can change the
     * fingerprint for the same track_id without the path moving.
     *
     * `pushed` resets to 0 only when the stored path/fingerprint actually
     * changed, so a no-op refetch (the common case — the server has no
     * "since" filter, so every sync re-walks the whole cursor) doesn't
     * re-queue an already-acknowledged row for push loop. */
    fun upsert(trackId: Long, path: String, fingerprint: String) = upsertIn(writableDatabase, trackId, path, fingerprint)

    /** Every entry of one page in one transaction: one disk sync per page
     * rather than one per row. */
    fun upsertAll(entries: List<FingerprintEntry>) {
        if (entries.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (e in entries) upsertIn(db, e.trackId, e.path, e.fingerprint)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Deletes the rows of tracks not in [keep]: tracks no longer on the
     * device. Returns how many went. */
    fun prune(keep: Set<Long>): Int {
        val db = writableDatabase
        val gone = db.rawQuery("SELECT track_id FROM provenance", null).use { c ->
            buildList { while (c.moveToNext()) c.getLong(0).takeUnless { it in keep }?.let(::add) }
        }
        if (gone.isEmpty()) return 0
        db.beginTransaction()
        try {
            val stmt = db.compileStatement("DELETE FROM provenance WHERE track_id = ?")
            for (id in gone) {
                stmt.bindLong(1, id)
                stmt.executeUpdateDelete()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return gone.size
    }

    fun trackIds(): Set<Long> = readableDatabase.rawQuery("SELECT track_id FROM provenance", null).use { c ->
        buildSet { while (c.moveToNext()) add(c.getLong(0)) }
    }

    private fun upsertIn(db: SQLiteDatabase, trackId: Long, path: String, fingerprint: String) {
        val existing = db.rawQuery(
            "SELECT path, fingerprint, pushed FROM provenance WHERE track_id = ?",
            arrayOf(trackId.toString()),
        ).use { c ->
            if (c.moveToFirst()) Triple(c.getString(0), c.getString(1), c.getInt(2)) else null
        }
        val unchanged = existing != null && existing.first == path && existing.second == fingerprint
        val values = ContentValues().apply {
            put("track_id", trackId)
            put("path", path)
            put("fingerprint", fingerprint)
            put("pushed", if (unchanged) existing?.third ?: 0 else 0)
        }
        db.insertWithOnConflict("provenance", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Rows never yet acknowledged by #85's push, capped at [limit] — pass
     * [ApiClient.PROVENANCE_PUSH_MAX], the server's own per-request cap. */
    fun unpushed(limit: Int): List<FingerprintEntry> {
        readableDatabase.rawQuery(
            "SELECT track_id, path, fingerprint FROM provenance WHERE pushed = 0 LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c ->
            val out = mutableListOf<FingerprintEntry>()
            while (c.moveToNext()) {
                out.add(FingerprintEntry(c.getLong(0), c.getString(2), c.getString(1)))
            }
            return out
        }
    }

    fun markPushed(trackIds: List<Long>) {
        if (trackIds.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val stmt = db.compileStatement("UPDATE provenance SET pushed = 1 WHERE track_id = ?")
            for (id in trackIds) {
                stmt.bindLong(1, id)
                stmt.executeUpdateDelete()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private companion object {
        const val DB_NAME = "provenance.db"
        const val DB_VERSION = 1
        const val META_NAME = "provenance_meta"
        const val KEY_HIGH_WATER = "high_water_seq"
        const val KEY_LAST_FULL_WALK = "last_full_walk_at"
    }
}

/** How often the walk starts over from the first track instead of asking
 * only for newer fingerprints: a track's on-device path can change (a
 * re-delivery under a new layout) without its fingerprint changing, and
 * only a full walk sees that, and sees which tracks have left. */
internal const val FULL_WALK_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000

/** Brings [store] up to date with this device's server-computed
 * fingerprints, oldest track_id first, a page per transaction.
 *
 * Normally incremental: only fingerprints computed after the highest
 * fingerprint_seq the store has seen. A full walk (no high-water mark yet,
 * or [FULL_WALK_INTERVAL_MS] since the last one) re-reads every row and
 * deletes the rows of tracks the server no longer lists for this device.
 *
 * Throws (same convention as every other ApiClient-backed call in this
 * app); the caller decides what a mid-walk failure means. A walk that
 * fails partway records nothing, so the next one covers the same ground. */
suspend fun syncProvenanceFingerprints(
    api: ApiClient, store: ProvenanceStore, now: Long = System.currentTimeMillis(),
) {
    val since = store.highWaterSeq
    val full = since == null || now - store.lastFullWalkAt >= FULL_WALK_INTERVAL_MS
    val seen = HashSet<Long>()
    var highest = since
    var after = 0L
    while (true) {
        val page = api.getFingerprintsPage(after, computedAfter = if (full) null else since)
        store.upsertAll(page.entries)
        for (entry in page.entries) {
            seen.add(entry.trackId)
            entry.seq?.let { seq -> if (highest == null || seq > highest!!) highest = seq }
        }
        after = page.nextAfter ?: break
    }
    if (full) {
        store.prune(seen)
        store.lastFullWalkAt = now
    }
    store.highWaterSeq = highest
}

/** #85: replays this device's not-yet-acknowledged provenance rows back to
 * the server, paged at [ApiClient.PROVENANCE_PUSH_MAX]. Driven by the
 * store's own `pushed` flag rather than the response's `pending` count —
 * that count also includes rows still awaiting the server's own async
 * rematch job, which this loop has no reason to wait on.
 *
 * Run every sync, right after [syncProvenanceFingerprints] — deliberately
 * not gated behind an explicit "re-link this device" action or a
 * server-reported-unknown-tracks signal (the two other candidates the
 * issue raised): with the `pushed` flag already tracking what's been
 * sent, a call here is a no-op once the device is caught up, so "push
 * anything outstanding, every sync" is the cheap default the issue asked
 * for, not a wasteful whole-DB replay. */
suspend fun pushPendingProvenance(api: ApiClient, store: ProvenanceStore) {
    while (true) {
        val page = store.unpushed(ApiClient.PROVENANCE_PUSH_MAX)
        if (page.isEmpty()) break
        api.pushProvenance(page)
        store.markPushed(page.map { it.trackId })
    }
}
