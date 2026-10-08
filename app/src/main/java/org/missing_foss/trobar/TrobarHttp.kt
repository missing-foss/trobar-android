// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import android.content.Context
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/** One OkHttpClient for the whole app. ApiClient used to build a fresh one
 * per instance, and an instance was built per call site, so every request
 * paid for its own connection pool and thread pool. The image loader shares
 * this one too (through [OkHttpClient.newBuilder], which keeps the pool and
 * dispatcher), so covers and API calls reuse the same connections to the
 * server. */
object TrobarHttp {
    private const val CONNECT_TIMEOUT_SECONDS = 15L
    private const val READ_TIMEOUT_SECONDS = 60L

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }
}

/** Adds the device credential to requests bound for the paired server and to
 * nothing else. Matched on scheme, host and port, not a string prefix: the
 * image loader takes arbitrary URLs, and a redirect or a misconfigured cover
 * URL pointing off the server must never carry the token with it. */
class ServerAuthInterceptor(
    context: Context,
    serverUrl: String,
    private val token: String,
) : Interceptor {
    private val appContext = context.applicationContext
    private val server: HttpUrl? = serverUrl.toHttpUrlOrNull()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val target = server
        val sameServer = target != null &&
            request.url.scheme == target.scheme &&
            request.url.host == target.host &&
            request.url.port == target.port
        if (!sameServer) return chain.proceed(request)
        return chain.proceed(
            request.newBuilder()
                .header("Authorization", "Bearer $token")
                .header("Accept-Language", appLanguageTag(appContext))
                .build(),
        )
    }
}
