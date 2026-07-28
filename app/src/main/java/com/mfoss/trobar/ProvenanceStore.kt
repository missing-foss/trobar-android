// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package com.mfoss.trobar

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
     * re-queue an already-acknowledged row for #85's push loop. */
    fun upsert(trackId: Long, path: String, fingerprint: String) {
        val db = writableDatabase
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
    }
}

/** #239/#81: walks every page of this device's server-computed
 * fingerprints (oldest track_id first) and upserts them into [store].
 * Not incremental across calls — the endpoint has no "since" filter, only
 * a cursor position, so every sync re-walks from after=0 — but each page
 * is capped and cheap (no audio decode on either end), same order of cost
 * as one getChanges() call per few hundred tracks.
 *
 * Throws (same convention as every other ApiClient-backed call in this
 * app) — the caller decides what a mid-walk failure means for the sync
 * result; see SyncWorker, which treats this as best-effort and swallows
 * it rather than failing the overall sync. */
suspend fun syncProvenanceFingerprints(api: ApiClient, store: ProvenanceStore) {
    var after = 0L
    while (true) {
        val page = api.getFingerprintsPage(after)
        for (entry in page.entries) {
            store.upsert(entry.trackId, entry.path, entry.fingerprint)
        }
        after = page.nextAfter ?: break
    }
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
