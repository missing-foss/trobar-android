// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package com.mfoss.trobar

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Internal (not private) so PrefsTest can seed/inspect raw preference state
// (e.g. a legacy plaintext token) to exercise migrateTokenIfNeeded() directly.
internal val Context.dataStore by preferencesDataStore(name = "trobar_prefs")

/** Pairing + sync state — server URL/token from the QR code, the SAF tree
 * the user picked once, and the last sync outcome for the status screen. */
// Thin DataStore facade: one small getter (Flow) + setter per preference, so
// the function count scales with the number of prefs, not with complexity.
@Suppress("TooManyFunctions")
object Prefs {
    private val SERVER_URL = stringPreferencesKey("server_url")
    private val TOKEN = stringPreferencesKey("token")
    private val TREE_URI = stringPreferencesKey("tree_uri")
    private val RECOVERY_PENDING = booleanPreferencesKey("recovery_pending")
    private val LAST_SYNC_AT = stringPreferencesKey("last_sync_at")
    private val LAST_SYNC_ERROR = stringPreferencesKey("last_sync_error")
    private val NETWORK_MODE = stringPreferencesKey("network_mode")
    private val REQUIRE_CHARGING_AND_IDLE = booleanPreferencesKey("require_charging_and_idle")
    private val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
    private val NOMEDIA = booleanPreferencesKey("nomedia")
    private val MISSING_FILE_BEHAVIOR = stringPreferencesKey("missing_file_behavior")
    private val PENDING_MISSING_TRACKS = stringPreferencesKey("pending_missing_tracks")

    // "wifi_only" | "wifi_and_mobile" | "any" (any = incl. roaming)
    const val NETWORK_WIFI_ONLY = "wifi_only"
    const val NETWORK_WIFI_AND_MOBILE = "wifi_and_mobile"
    const val NETWORK_ANY = "any"

    // what to do when a sync finds a track it believed was
    // already downloaded missing on disk (deleted directly on the device).
    const val MISSING_ASK = "ask"
    const val MISSING_ALWAYS_RESYNC = "always_resync"
    const val MISSING_ALWAYS_EXCLUDE = "always_exclude"

    data class Pairing(val serverUrl: String, val token: String)

    /** #101: a stored pairing whose token can't be decrypted is a materially
     * different situation from never having paired at all — both used to
     * collapse into a bare `null`, which is exactly why the bug looked like
     * silent data loss with no way to tell the two apart from the outside. */
    sealed class PairingState {
        object NotPaired : PairingState()

        /** The URL is still readable (plaintext, never encrypted) even
         * though the token isn't — kept so re-pairing can offer it back to
         * the user instead of a blank form. */
        data class TokenUnreadable(val serverUrl: String) : PairingState()
        data class Paired(val pairing: Pairing) : PairingState()
    }

    fun pairing(context: Context): Flow<PairingState> =
        context.dataStore.data.map { prefs ->
            val url = prefs[SERVER_URL]
            val stored = prefs[TOKEN]
            if (url == null || stored == null) {
                PairingState.NotPaired
            } else {
                // Token is Keystore-encrypted at rest; decrypt transparently.
                // A legacy plaintext token (no PREFIX) is returned as-is so
                // existing pairings keep working until migrateTokenIfNeeded()
                // upgrades them.
                val token = if (TokenCrypto.isEncrypted(stored)) TokenCrypto.decrypt(stored) else stored
                if (token != null) PairingState.Paired(Pairing(url, token)) else PairingState.TokenUnreadable(url)
            }
        }

    suspend fun setPairing(context: Context, serverUrl: String, token: String) {
        val encrypted = TokenCrypto.encrypt(token)
        context.dataStore.edit {
            it[SERVER_URL] = serverUrl
            it[TOKEN] = encrypted
        }
    }

    /** One-time upgrade for installs paired by an older version: re-store a
     *  legacy plaintext token encrypted, so existing devices gain Keystore
     *  protection without a re-pair. No-op once the token is encrypted. */
    suspend fun migrateTokenIfNeeded(context: Context) {
        // Best-effort: a Keystore hiccup here must never crash startup — if it
        // fails the token just stays plaintext (still functional) until next time.
        try {
            context.dataStore.edit { prefs ->
                val stored = prefs[TOKEN] ?: return@edit
                if (!TokenCrypto.isEncrypted(stored)) {
                    prefs[TOKEN] = TokenCrypto.encrypt(stored)
                }
            }
        } catch (ignored: Exception) {
            // leave as-is
        }
    }

    suspend fun clearPairing(context: Context) {
        context.dataStore.edit {
            it.remove(SERVER_URL)
            it.remove(TOKEN)
        }
    }

    /** Changing the server address alone (token stays — same device
     * identity, just reachable at a different URL, e.g. LAN IP changed). */
    suspend fun setServerUrl(context: Context, serverUrl: String) {
        context.dataStore.edit { it[SERVER_URL] = serverUrl }
    }

    fun treeUri(context: Context): Flow<String?> =
        context.dataStore.data.map { it[TREE_URI] }

    suspend fun setTreeUri(context: Context, uri: String) {
        context.dataStore.edit { it[TREE_URI] = uri }
    }

    /** #34: set when a device (re-)enrolls; the next sync checks whether the
     * chosen folder already holds a library and, if so, runs the recovery
     * handshake (source_of_truth=device + manifest) before its first pull, so a
     * reinstalled/migrated device is neither wiped nor made to re-download. */
    fun recoveryPending(context: Context): Flow<Boolean> =
        context.dataStore.data.map { it[RECOVERY_PENDING] ?: false }

    suspend fun setRecoveryPending(context: Context, pending: Boolean) {
        context.dataStore.edit { it[RECOVERY_PENDING] = pending }
    }

    fun lastSyncAt(context: Context): Flow<String?> =
        context.dataStore.data.map { it[LAST_SYNC_AT] }

    fun lastSyncError(context: Context): Flow<String?> =
        context.dataStore.data.map { it[LAST_SYNC_ERROR] }

    suspend fun recordSyncResult(context: Context, at: String, error: String?) {
        context.dataStore.edit {
            it[LAST_SYNC_AT] = at
            if (error != null) it[LAST_SYNC_ERROR] = error else it.remove(LAST_SYNC_ERROR)
        }
    }

    /** Manual dismiss for the persistent "last error" line — a
     * clean sync already clears it, but a recurring failure would otherwise
     * pin the message to the status screen with no way to acknowledge it. */
    suspend fun clearSyncError(context: Context) {
        context.dataStore.edit { it.remove(LAST_SYNC_ERROR) }
    }

    fun networkMode(context: Context): Flow<String> =
        context.dataStore.data.map { it[NETWORK_MODE] ?: NETWORK_WIFI_ONLY }

    suspend fun setNetworkMode(context: Context, mode: String) {
        context.dataStore.edit { it[NETWORK_MODE] = mode }
    }

    /** #93: opt-in WorkManager constraints on the PERIODIC sync only —
     * SyncWorker.triggerManualSync() deliberately never reads this, since an
     * explicit "sync now" tap means now, not "whenever the phone happens to
     * be idle on the charger". Off by default: requiring both can push the
     * periodic sync well past its usual 6-hour interval, which is the
     * user's opt-in tradeoff to make, not a surprise default. */
    fun requireChargingAndIdle(context: Context): Flow<Boolean> =
        context.dataStore.data.map { it[REQUIRE_CHARGING_AND_IDLE] ?: false }

    suspend fun setRequireChargingAndIdle(context: Context, required: Boolean) {
        context.dataStore.edit { it[REQUIRE_CHARGING_AND_IDLE] = required }
    }

    fun useDynamicColor(context: Context): Flow<Boolean> =
        context.dataStore.data.map { it[DYNAMIC_COLOR] ?: false }

    suspend fun setUseDynamicColor(context: Context, enabled: Boolean) {
        context.dataStore.edit { it[DYNAMIC_COLOR] = enabled }
    }

    // the artist-pictures setting moved to the device (web UI,
    // /api/device/info) — the old local pref is retired; its stored value
    // is simply ignored by this version.

    // Off by default; enabling it shows a warning first since a .nomedia
    // file hides the WHOLE sync folder (audio included) from any app that
    // discovers media via MediaStore, not just galleries.
    fun nomediaEnabled(context: Context): Flow<Boolean> =
        context.dataStore.data.map { it[NOMEDIA] ?: false }

    suspend fun setNomediaEnabled(context: Context, enabled: Boolean) {
        context.dataStore.edit { it[NOMEDIA] = enabled }
    }

    // "ask" (default), "always_resync", or "always_exclude" — see the
    // MISSING_* constants above.
    fun missingFileBehavior(context: Context): Flow<String> =
        context.dataStore.data.map { it[MISSING_FILE_BEHAVIOR] ?: MISSING_ASK }

    suspend fun setMissingFileBehavior(context: Context, mode: String) {
        context.dataStore.edit { it[MISSING_FILE_BEHAVIOR] = mode }
    }

    // Raw JSON array of {"track_id":Long,"relative_path":String} — tracks
    // found missing locally during the last sync while behavior was "ask",
    // still awaiting the user's decision. Surfaced on StatusScreen; cleared
    // once resolved (or the next sync overwrites it with the current set).
    fun pendingMissingTracks(context: Context): Flow<String?> =
        context.dataStore.data.map { it[PENDING_MISSING_TRACKS] }

    suspend fun setPendingMissingTracks(context: Context, json: String?) {
        context.dataStore.edit {
            if (json != null) it[PENDING_MISSING_TRACKS] = json else it.remove(PENDING_MISSING_TRACKS)
        }
    }
}
