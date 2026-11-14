// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

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
    // Older versions stored the last sync preformatted ("dd/MM HH:mm", no
    // year); it's shown as is until the next sync replaces it.
    private val LAST_SYNC_AT_LEGACY = stringPreferencesKey("last_sync_at")
    private val LAST_SYNC_AT_MS = longPreferencesKey("last_sync_at_ms")
    private val LAST_SYNC_ERROR = stringPreferencesKey("last_sync_error")
    private val LAST_SYNC_FAILED = intPreferencesKey("last_sync_failed")
    private val LAST_SYNC_FAILURES = stringPreferencesKey("last_sync_failures")
    // The one-time thank-you card on Home: syncs this install finished with
    // no error, and whether either of the card's buttons was pressed.
    private val SUCCESSFUL_SYNCS = intPreferencesKey("successful_syncs")
    private val THANKS_CARD_DONE = booleanPreferencesKey("thanks_card_done")
    // Whether the app has asked for the notification permission. It asks
    // once, after the folder picker; Settings is the way back after that.
    private val NOTIFICATION_PROMPT_DONE = booleanPreferencesKey("notification_prompt_done")
    private val NETWORK_MODE = stringPreferencesKey("network_mode")
    private val PLAYLIST_SORT = stringPreferencesKey("playlist_sort")
    private val REQUIRE_CHARGING_AND_IDLE = booleanPreferencesKey("require_charging_and_idle")
    private val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
    private val APP_MODE = stringPreferencesKey("app_mode")
    private val BRAND_THEME = stringPreferencesKey("brand_theme")
    private val MATCH_ICON_TO_THEME = booleanPreferencesKey("match_icon_to_theme")
    private val NOMEDIA = booleanPreferencesKey("nomedia")
    private val MISSING_FILE_BEHAVIOR = stringPreferencesKey("missing_file_behavior")
    private val PENDING_MISSING_TRACKS = stringPreferencesKey("pending_missing_tracks")
    // The paired server's App API level, refreshed on every probe of
    // /api/device/info; absent until the first probe after pairing.
    private val APP_API = intPreferencesKey("app_api")
    // The device the header's selector names (#50): this phone's own choice,
    // kept here and not on the server, whose remembered destinations belong
    // to the account -- a choice made on one phone must not become another's.
    private val HEADER_DEVICE = longPreferencesKey("header_device")
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
            it.remove(APP_API) // a different server until its first probe says otherwise
            it.remove(HEADER_DEVICE) // its ids are another server's
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
            it.remove(APP_API)
            it.remove(HEADER_DEVICE)
        }
    }

    /** null: no probe has answered since this pairing was stored. */
    fun appApi(context: Context): Flow<Int?> =
        context.dataStore.data.map { it[APP_API] }

    suspend fun setAppApi(context: Context, level: Int) {
        context.dataStore.edit { it[APP_API] = level }
    }

    suspend fun clearAppApi(context: Context) {
        context.dataStore.edit { it.remove(APP_API) }
    }

    /** null: nothing chosen, so the header names this phone. */
    fun headerDevice(context: Context): Flow<Long?> =
        context.dataStore.data.map { it[HEADER_DEVICE] }

    suspend fun setHeaderDevice(context: Context, deviceId: Long) {
        context.dataStore.edit { it[HEADER_DEVICE] = deviceId }
    }

    suspend fun clearHeaderDevice(context: Context) {
        context.dataStore.edit { it.remove(HEADER_DEVICE) }
    }

    /** Changing the server address alone (token stays — same device
     * identity, just reachable at a different URL, e.g. LAN IP changed).
     * The App API level is forgotten with it: the new address can be a
     * different machine on an older build, and the next probe re-reads it. */
    suspend fun setServerUrl(context: Context, serverUrl: String) {
        context.dataStore.edit {
            it[SERVER_URL] = serverUrl
            it.remove(APP_API)
        }
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

    /** When the last sync finished, in the app's language, or null if none
     * has. Stored as epoch millis and formatted here, when it's read. */
    fun lastSyncAt(context: Context): Flow<String?> =
        context.dataStore.data.map { prefs ->
            prefs[LAST_SYNC_AT_MS]?.let { formatSyncTime(it, AppLanguage.locale(context)) } ?: prefs[LAST_SYNC_AT_LEGACY]
        }

    fun formatSyncTime(epochMillis: Long, locale: Locale): String =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale).format(Date(epochMillis))

    fun lastSyncError(context: Context): Flow<String?> =
        context.dataStore.data.map { it[LAST_SYNC_ERROR] }

    suspend fun recordSyncResult(
        context: Context, atEpochMillis: Long, error: String?, failed: Int = 0, failures: List<SyncFailure> = emptyList(),
    ) {
        context.dataStore.edit {
            it[LAST_SYNC_AT_MS] = atEpochMillis
            it.remove(LAST_SYNC_AT_LEGACY)
            if (error != null) it[LAST_SYNC_ERROR] = error else it.remove(LAST_SYNC_ERROR)
            it[LAST_SYNC_FAILED] = failed
            if (failures.isEmpty()) it.remove(LAST_SYNC_FAILURES) else it[LAST_SYNC_FAILURES] = failuresJson(failures)
        }
    }

    /** When the last sync ended, for showing how long ago. */
    fun lastSyncAtMillis(context: Context): Flow<Long?> = context.dataStore.data.map { it[LAST_SYNC_AT_MS] }

    /** How many tracks the last sync failed on, all of them. */
    fun lastSyncFailed(context: Context): Flow<Int> = context.dataStore.data.map { it[LAST_SYNC_FAILED] ?: 0 }

    /** The last sync's failures, as far as they were kept. */
    fun lastSyncFailures(context: Context): Flow<List<SyncFailure>> =
        context.dataStore.data.map { parseFailures(it[LAST_SYNC_FAILURES]) }

    private fun failuresJson(failures: List<SyncFailure>) = JSONArray(
        failures.map { JSONObject().put("path", it.path).put("reason", it.reason) },
    ).toString()

    private fun parseFailures(json: String?): List<SyncFailure> {
        if (json == null) return emptyList()
        return try {
            val arr = JSONArray(json)
            List(arr.length()) { arr.getJSONObject(it).let { o -> SyncFailure(o.getString("path"), o.getString("reason")) } }
        } catch (ignored: JSONException) {
            emptyList()
        }
    }

    /** Manual dismiss for the persistent "last error" line — a
     * clean sync already clears it, but a recurring failure would otherwise
     * pin the message to the status screen with no way to acknowledge it. */
    suspend fun clearSyncError(context: Context) {
        context.dataStore.edit {
            it.remove(LAST_SYNC_ERROR)
            it.remove(LAST_SYNC_FAILURES)
            it.remove(LAST_SYNC_FAILED)
        }
    }

    /** Successful syncs on this install before Home shows the thank-you card. */
    const val THANKS_CARD_SYNCS = 50

    fun successfulSyncs(context: Context): Flow<Int> =
        context.dataStore.data.map { it[SUCCESSFUL_SYNCS] ?: 0 }

    suspend fun countSuccessfulSync(context: Context) {
        context.dataStore.edit { it[SUCCESSFUL_SYNCS] = (it[SUCCESSFUL_SYNCS] ?: 0) + 1 }
    }

    /** Whether Home shows the thank-you card: from the 50th successful sync
     * on, until either of its buttons is pressed. */
    fun thanksCardShown(context: Context): Flow<Boolean> = context.dataStore.data.map {
        it[THANKS_CARD_DONE] != true && (it[SUCCESSFUL_SYNCS] ?: 0) >= THANKS_CARD_SYNCS
    }

    suspend fun setThanksCardDone(context: Context) {
        context.dataStore.edit { it[THANKS_CARD_DONE] = true }
    }

    suspend fun notificationPromptDone(context: Context): Boolean =
        context.dataStore.data.first()[NOTIFICATION_PROMPT_DONE] == true

    suspend fun setNotificationPromptDone(context: Context) {
        context.dataStore.edit { it[NOTIFICATION_PROMPT_DONE] = true }
    }

    /** Both thank-you card keys gone, as on a fresh install: for tests. */
    suspend fun resetThanksCard(context: Context) {
        context.dataStore.edit {
            it.remove(SUCCESSFUL_SYNCS)
            it.remove(THANKS_CARD_DONE)
        }
    }

    fun networkMode(context: Context): Flow<String> =
        context.dataStore.data.map { it[NETWORK_MODE] ?: NETWORK_WIFI_ONLY }

    suspend fun setNetworkMode(context: Context, mode: String) {
        context.dataStore.edit { it[NETWORK_MODE] = mode }
    }

    /** The Playlists tab's order, as a PlaylistSort name; null until one is chosen. */
    fun playlistSort(context: Context): Flow<String?> = context.dataStore.data.map { it[PLAYLIST_SORT] }

    suspend fun setPlaylistSort(context: Context, sort: String) {
        context.dataStore.edit { it[PLAYLIST_SORT] = sort }
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

    /** Simple or Full pinned on this device, or Auto (the default); see
     * [resolveAppMode]. */
    fun appModeChoice(context: Context): Flow<String> =
        context.dataStore.data.map { it[APP_MODE] ?: AppModeChoice.AUTO }

    suspend fun setAppModeChoice(context: Context, choice: String) {
        context.dataStore.edit { it[APP_MODE] = choice }
    }

    /** The brand theme's key (BrandTheme.key); troubadour when never set. */
    fun brandTheme(context: Context): Flow<String> =
        context.dataStore.data.map { it[BRAND_THEME] ?: BrandTheme.DEFAULT.key }

    suspend fun setBrandTheme(context: Context, key: String) {
        context.dataStore.edit { it[BRAND_THEME] = key }
    }

    /** Whether the launcher icon follows the brand theme (LauncherIcon);
     * off by default, so the icon stays Troubadour's. */
    fun matchIconToTheme(context: Context): Flow<Boolean> =
        context.dataStore.data.map { it[MATCH_ICON_TO_THEME] ?: false }

    suspend fun setMatchIconToTheme(context: Context, enabled: Boolean) {
        context.dataStore.edit { it[MATCH_ICON_TO_THEME] = enabled }
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
    // still awaiting the user's decision. Surfaced in the sync card in Settings; cleared
    // once resolved (or the next sync overwrites it with the current set).
    fun pendingMissingTracks(context: Context): Flow<String?> =
        context.dataStore.data.map { it[PENDING_MISSING_TRACKS] }

    suspend fun setPendingMissingTracks(context: Context, json: String?) {
        context.dataStore.edit {
            if (json != null) it[PENDING_MISSING_TRACKS] = json else it.remove(PENDING_MISSING_TRACKS)
        }
    }
}
