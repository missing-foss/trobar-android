// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
// What the app takes from text shared to it: the first web link, alone or
// inside a sentence; nothing from anything else.

package org.missing_foss.trobar

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedLinkTest {
    private fun send(text: String?, type: String = "text/plain") =
        Intent(Intent.ACTION_SEND).setType(type).apply { if (text != null) putExtra(Intent.EXTRA_TEXT, text) }

    @Test
    fun aLinkAloneIsTaken() {
        assertEquals("https://music.youtube.com/playlist?list=PLabc", sharedLinkOf(send("https://music.youtube.com/playlist?list=PLabc")))
    }

    @Test
    fun aLinkInsideASentenceIsTakenAlone() {
        assertEquals(
            "https://music.youtube.com/playlist?list=PLabc&si=xyz",
            sharedLinkOf(send("Listen to Road Trip on YouTube Music: https://music.youtube.com/playlist?list=PLabc&si=xyz and more")),
        )
    }

    @Test
    fun textWithoutALinkGivesNothing() {
        assertNull(sharedLinkOf(send("just some words")))
        assertNull(sharedLinkOf(send(null)))
    }

    @Test
    fun anythingButSharedTextGivesNothing() {
        assertNull(sharedLinkOf(send("https://example.org/a.png", type = "image/png")))
        assertNull(sharedLinkOf(Intent(Intent.ACTION_VIEW).putExtra(Intent.EXTRA_TEXT, "https://example.org")))
        assertNull(sharedLinkOf(null))
    }
}
