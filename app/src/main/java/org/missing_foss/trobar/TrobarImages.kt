// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okio.Path.Companion.toOkioPath

/** The image loader for covers and artist pictures, one per pairing. Built
 * on the shared OkHttp client with the credential interceptor in front, so
 * the token reaches the paired server and no other host. The disk cache
 * honours the server's Cache-Control (covers say max-age of a day), and
 * artist images carry a version query, so a changed picture is a new URL
 * and never a stale hit. */
object TrobarImages {
    private const val DISK_CACHE_BYTES = 64L * 1024 * 1024
    private var cached: Pair<Prefs.Pairing, ImageLoader>? = null

    @Synchronized
    fun loader(context: Context, pairing: Prefs.Pairing): ImageLoader {
        cached?.let { (forPairing, loader) -> if (forPairing == pairing) return loader }
        val app = context.applicationContext
        val http = TrobarHttp.client.newBuilder()
            .addInterceptor(ServerAuthInterceptor(app, pairing.serverUrl, pairing.token))
            .build()
        val loader = ImageLoader.Builder(app)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = { http },
                        cacheStrategy = { CacheControlCacheStrategy() },
                    ),
                )
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(app.cacheDir.resolve("images").toOkioPath())
                    .maxSizeBytes(DISK_CACHE_BYTES)
                    .build()
            }
            .build()
        cached = pairing to loader
        return loader
    }
}
