// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** The transport the App API clients share: URLs under the prefix, the
 * device token on every request, and the error mapping of [requireSuccess]
 * (401 typed, everything else an IOException carrying the server's own
 * message). Blocking, over the app's one HTTP client. */
internal class AppApiHttp(context: Context, private val serverUrl: String, private val token: String) {
    private val appContext = context.applicationContext
    private val client = TrobarHttp.client

    fun url(vararg segments: String, query: Map<String, String> = emptyMap()): String {
        val b = serverUrl.toHttpUrl().newBuilder().addPathSegments("api/app")
        segments.forEach { b.addPathSegment(it) }
        query.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build().toString()
    }

    private fun authed(url: String): Request.Builder =
        Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept-Language", appLanguageTag(appContext))

    fun get(url: String, action: String): String {
        client.newCall(authed(url).build()).execute().use { resp ->
            requireSuccess(appContext, resp, action)
            return resp.body?.string() ?: ""
        }
    }

    /** A JSON-bodied write (POST/PATCH/DELETE); the server answers JSON too. */
    fun send(method: String, url: String, body: JSONObject?, action: String): String {
        val req = authed(url).method(method, body?.toString()?.toRequestBody("application/json".toMediaType())).build()
        client.newCall(req).execute().use { resp ->
            requireSuccess(appContext, resp, action)
            return resp.body?.string() ?: ""
        }
    }
}

/** `device_ids` is a JSON list on the basket routes and a comma-joined
 * string on selections (the server's GROUP_CONCAT goes out untouched; the
 * browser splits it). Same name, two shapes -- both parsed here so neither
 * route silently yields no devices. */
internal fun ids(value: Any?): List<Long> = when (value) {
    is JSONArray -> List(value.length()) { value.getLong(it) }
    is String -> value.split(',').filter { it.isNotBlank() }.map { it.trim().toLong() }
    else -> emptyList()
}

internal fun JSONObject.longOrNull(key: String): Long? = if (isNull(key)) null else optLong(key)
internal fun JSONObject.intOrNull(key: String): Int? = if (isNull(key)) null else optInt(key)
internal fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else optString(key)
internal fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else List(length()) { getString(it) }
