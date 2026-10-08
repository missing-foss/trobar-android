// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import android.net.Uri

/** Destinations. The four tabs and the screens pushed over them; in Simple
 * mode, the Sync screen and the few screens pushed over it. */
object Routes {
    /** Simple mode's one screen. */
    const val SYNC = "sync"
    const val HOME = "home"
    const val LIBRARY = "library"
    const val PLAYLISTS = "playlists"
    const val BASKET = "basket"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
    const val WIDGET_PREFS = "widget-prefs"
    /** The playlist subscriptions, pushed over the Playlists tab; [SUBSCRIPTIONS_URL]
     * carries a link shared from another app into the add field. */
    const val SUBSCRIPTIONS = "playlist-subscriptions?url={url}"
    const val SUBSCRIPTIONS_URL = "url"
    fun subscriptions(url: String? = null): String =
        "playlist-subscriptions" + if (url != null) "?url=" + Uri.encode(url) else ""
    val TABS = listOf(HOME, LIBRARY, PLAYLISTS, SETTINGS)
}
