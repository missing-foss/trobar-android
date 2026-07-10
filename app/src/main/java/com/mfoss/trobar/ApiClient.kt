// SPDX-License-Identifier: GPL-3.0-or-later
package com.mfoss.trobar

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class TrackRef(val trackId: Long, val relativePath: String, val size: Long,
                    val transcode: Boolean = false)
data class TrackDeleteRef(val trackId: Long, val relativePath: String)
data class Changes(val toDownload: List<TrackRef>, val toDelete: List<TrackDeleteRef>, val downloaded: List<TrackRef>,
                   val playlists: List<PlaylistRef> = emptyList())
/** gitea#118 — a server-generated .m3u8 for one playlist assigned to this device. */
data class PlaylistRef(val name: String, val filename: String, val content: String)
data class DeviceInfo(val name: String, val maxSizeBytes: Long?, val deviceType: String,
                      val artistImages: String? = null)

/** Talks to the device-facing API ("/api/device" routes) — Bearer-token
 * auth, no ForwardAuth involved (that router is deliberately excluded from
 * Authentik in docker-compose.yaml on the server, since a native client
 * can't follow an HTML login redirect). */
class ApiClient(context: Context, private val serverUrl: String, private val token: String) {
    private val appContext = context.applicationContext
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun baseUrl() = serverUrl.trimEnd('/')

    // Only the device-API's 5 error-message strings (main.py's
    // _authenticated_device/api_device_file/api_device_artist_image) depend
    // on this — the server has no cookie/session for this Bearer-token API,
    // so Accept-Language is the only signal it has for which language to
    // reply in. Reflects the same locale the app's own UI is currently
    // showing (explicit override, or the system locale under "System").
    private fun currentLanguageTag(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        return if (!locales.isEmpty) (locales[0]?.language ?: "en") else java.util.Locale.getDefault().language
    }

    private fun authed(url: String): Request.Builder =
        Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept-Language", currentLanguageTag())

    /** The server now returns {"error": "..."} on device-API failures
     * (see device_api_error_json in main.py) — surface that text directly
     * (e.g. in a Snackbar) instead of a bare "info HTTP 401". */
    private fun errorMessage(resp: Response, default: String): String {
        val parsed = try {
            resp.body?.string()?.let { JSONObject(it).optString("error") }
        } catch (e: Exception) { null }
        return parsed?.takeIf { it.isNotBlank() } ?: default
    }

    private fun requireSuccess(resp: Response, action: String) {
        if (!resp.isSuccessful) throw IOException(errorMessage(resp, appContext.getString(R.string.api_error_generic, action, resp.code)))
    }

    /** This device's own registered identity (name + configured storage
     * limit), as set in the web UI's Profil > Appareils list — the app
     * never stores this itself, only the server URL + token from pairing. */
    fun getDeviceInfo(): DeviceInfo {
        val req = authed("${baseUrl()}/api/device/info").build()
        client.newCall(req).execute().use { resp ->
            requireSuccess(resp, "info")
            val json = JSONObject(resp.body?.string() ?: "{}")
            val max = if (json.isNull("max_size_bytes")) null else json.optLong("max_size_bytes")
            val artistImages = if (json.isNull("artist_images")) null else json.optString("artist_images")
            return DeviceInfo(json.optString("name", "?"), max, json.optString("device_type", "phone"),
                              artistImages)
        }
    }

    /** Reports this device's actual free + total space (see
     * StorageUtils.kt) so the web UI can flag a configured limit that
     * doesn't physically fit, and show overall device storage usage. */
    fun reportStorage(freeBytes: Long, totalBytes: Long) {
        val json = JSONObject().put("free_bytes", freeBytes).put("total_bytes", totalBytes)
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val req = authed("${baseUrl()}/api/device/storage").post(body).build()
        client.newCall(req).execute().use { resp -> requireSuccess(resp, "storage") }
    }

    /** Lets the device set its own allocation from the Settings screen —
     * mirrors what the web UI's Profil > Appareils can already do, but
     * through the Bearer-token device API instead of an Authentik session. */
    fun updateLimit(maxSizeBytes: Long?) {
        val json = JSONObject().put("max_size_bytes", maxSizeBytes ?: JSONObject.NULL)
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val req = authed("${baseUrl()}/api/device/limit").method("PATCH", body).build()
        client.newCall(req).execute().use { resp -> requireSuccess(resp, "limit") }
    }

    fun getChanges(): Changes {
        val req = authed("${baseUrl()}/api/device/changes").build()
        client.newCall(req).execute().use { resp ->
            requireSuccess(resp, "changes")
            val body = JSONObject(resp.body?.string() ?: "{}")
            val playlists = mutableListOf<PlaylistRef>()
            body.optJSONArray("playlists")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    playlists.add(PlaylistRef(o.getString("name"), o.getString("filename"), o.getString("content")))
                }
            }
            return Changes(
                toDownload = parseTrackRefs(body.optJSONArray("to_download")),
                toDelete = parseDeleteRefs(body.optJSONArray("to_delete")),
                downloaded = parseTrackRefs(body.optJSONArray("downloaded")),
                playlists = playlists,
            )
        }
    }

    /** gitea#49 — reports the user's (or a standing preference's) decision
     * about tracks the server believed were already downloaded but were
     * found missing on disk: `redownload` flips them back to pending,
     * `exclude` stops them being silently re-queued without touching
     * whatever selection still nominally requires them. */
    fun resolveMissingTracks(redownload: List<Long>, exclude: List<Long>) {
        val json = JSONObject()
            .put("redownload", JSONArray(redownload))
            .put("exclude", JSONArray(exclude))
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val req = authed("${baseUrl()}/api/device/missing-tracks").post(body).build()
        client.newCall(req).execute().use { resp -> requireSuccess(resp, "missing-tracks") }
    }

    private fun parseTrackRefs(arr: JSONArray?): List<TrackRef> {
        val out = mutableListOf<TrackRef>()
        if (arr == null) return out
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(TrackRef(o.getLong("track_id"), o.getString("relative_path"), o.getLong("size"),
                             o.optBoolean("transcode", false)))
        }
        return out
    }

    private fun parseDeleteRefs(arr: JSONArray?): List<TrackDeleteRef> {
        val out = mutableListOf<TrackDeleteRef>()
        if (arr == null) return out
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(TrackDeleteRef(o.getLong("track_id"), o.getString("relative_path")))
        }
        return out
    }

    /** Streams a track's bytes. Caller is responsible for closing the
     * returned response (use `.use {}`). */
    fun downloadFile(trackId: Long, resumeFromByte: Long = 0): Response {
        val builder = authed("${baseUrl()}/api/device/file/$trackId")
        if (resumeFromByte > 0) builder.header("Range", "bytes=$resumeFromByte-")
        val resp = client.newCall(builder.build()).execute()
        if (!resp.isSuccessful) {
            val message = errorMessage(resp, appContext.getString(R.string.api_error_file, trackId, resp.code))
            resp.close()
            throw IOException(message)
        }
        return resp
    }

    fun ack(trackId: Long, status: String) {
        val json = JSONObject().put("track_id", trackId).put("status", status)
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val req = authed("${baseUrl()}/api/device/ack").post(body).build()
        client.newCall(req).execute().use { resp -> requireSuccess(resp, "ack") }
    }

    /** Bytes of an artist's picture (for the artist.jpg-in-folder feature),
     * or null if Roon has none for this artist — a 404 here is a normal,
     * expected outcome (most artists won't resolve), not a sync failure. */
    fun downloadArtistImage(artist: String, small: Boolean = false): ByteArray? {
        val url = "${baseUrl()}/api/device/artist-image?artist=" +
            java.net.URLEncoder.encode(artist, "UTF-8") +
            if (small) "&size=small" else ""  // gitea#127
        client.newCall(authed(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return resp.body?.bytes()
        }
    }
}
