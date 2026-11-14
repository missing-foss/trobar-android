// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import android.content.Context
import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** The artist list as last read, and when. */
data class CachedArtists(val readAt: Long, val artists: List<LibraryArtist>)

/** The last artist list of [serverUrl], on disk, for when that server can't
 * be reached: the music is on the device whether it answers or not. Another
 * server's list is never shown as this one's, and unpairing removes it.
 * Best-effort both ways: a cache that can't be written or read is no cache. */
class LibraryCache(context: Context, private val serverUrl: String) {
    private val file = fileIn(context)

    fun write(artists: List<LibraryArtist>, readAt: Long) {
        val json = JSONObject().put("server", serverUrl).put("read_at", readAt).put(
            "artists",
            JSONArray(
                artists.map {
                    JSONObject().put("artist", it.artist).put("track_count", it.trackCount)
                        .put("album_count", it.albumCount).put("albums", it.albums?.let(::JSONArray))
                        .put("appears_on_count", it.appearsOnCount)
                },
            ),
        )
        try {
            file.writeText(json.toString())
        } catch (ignored: IOException) {
            // The list on screen is fine; only the offline copy is older.
        }
    }

    fun read(): CachedArtists? = try {
        val json = JSONObject(file.readText())
        if (json.optString("server") == serverUrl) parse(json) else null
    } catch (ignored: IOException) {
        null
    } catch (ignored: JSONException) {
        null
    }

    private fun parse(json: JSONObject): CachedArtists {
        val arr = json.getJSONArray("artists")
        return CachedArtists(
            json.getLong("read_at"),
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val albums = o.optJSONArray("albums")
                LibraryArtist(
                    o.getString("artist"), o.getInt("track_count"), o.getInt("album_count"),
                    albums?.let { a -> List(a.length()) { a.getString(it) } }, o.optInt("appears_on_count"),
                )
            },
        )
    }

    companion object {
        private const val FILE = "library_artists.json"

        private fun fileIn(context: Context) = File(context.applicationContext.filesDir, FILE)

        /** Unpairing: the list belongs to the server this device has left. */
        fun clear(context: Context) {
            fileIn(context).delete()
        }
    }
}
