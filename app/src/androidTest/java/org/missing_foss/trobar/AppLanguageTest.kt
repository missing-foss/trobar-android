// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
// The app's language has one source per platform level: the system's per-app
// language on API 33+, the app's own stored choice below. What the screen
// shows, Accept-Language and the sync timestamp all read it through
// AppLanguage.

package org.missing_foss.trobar

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLanguageTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun followsTheSystemPerAppLanguageOnApi33() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        val localeManager = context.getSystemService(LocaleManager::class.java)
        try {
            localeManager.applicationLocales = LocaleList.forLanguageTags("fr")
            assertEquals("fr", AppLanguage.choice(context))
            assertEquals("fr", appLanguageTag(context))

            localeManager.applicationLocales = LocaleList.getEmptyLocaleList()
            assertEquals(AppLanguage.SYSTEM, AppLanguage.choice(context))
        } finally {
            localeManager.applicationLocales = LocaleList.getEmptyLocaleList()
        }
    }

    @Test
    fun leavesTheActivityContextAloneOnApi33() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        assertSame(context, AppLanguage.wrap(context))
    }

    @Test
    fun appliesTheStoredChoiceBelowApi33() {
        assumeTrue(Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
        try {
            AppLanguage.choose(context, "fr")
            assertEquals("fr", AppLanguage.choice(context))
            assertEquals("fr", appLanguageTag(context))
            val wrapped = AppLanguage.wrap(context)
            assertEquals("fr", wrapped.resources.configuration.locales[0].language)
            assertEquals("Système", wrapped.getString(R.string.language_system))

            AppLanguage.choose(context, AppLanguage.SYSTEM)
            assertSame(context, AppLanguage.wrap(context))
        } finally {
            AppLanguage.choose(context, AppLanguage.SYSTEM)
        }
    }
}
