// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
// Below API 33, where the app keeps the language choice itself: the stored
// choice must reach the places that show text, not just AppLanguage. The real
// MainActivity opens in it (attachBaseContext applies it, the step that was
// missing when the picker did nothing), and the sync notification is written
// in it.

package org.missing_foss.trobar

import android.app.Notification
import android.os.Build
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLanguageCallSiteTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun storeFrench() {
        assumeTrue(Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
        runBlocking { Prefs.clearPairing(context) } // the enrolment screen, whose text is known
        AppLanguage.choose(context, "fr")
    }

    @After
    fun backToSystem() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) AppLanguage.choose(context, AppLanguage.SYSTEM)
    }

    @Test
    fun theRealActivityOpensInTheStoredLanguage() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForText("Scanner le QR code")
        }
    }

    @Test
    fun theSyncNotificationIsInTheStoredLanguage() {
        val n = SyncWorker.buildNotification(context, done = 0, total = 0, scanning = false)
        assertEquals("Synchronisation en cours…", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }
}
