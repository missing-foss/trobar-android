// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import android.content.Intent

/** The first web link in text shared to the app, or null. Apps share a
 * playlist as its link alone or inside a sentence ("Listen to … on YouTube
 * Music: https://…"); either way the link is what the server wants, and
 * whether it is a playlist is the server's to say. */
fun sharedLinkOf(intent: Intent?): String? {
    if (intent?.action != Intent.ACTION_SEND || intent.type?.startsWith("text/") != true) return null
    val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
    return LINK.find(text)?.value
}

private val LINK = Regex("""https?://\S+""")
