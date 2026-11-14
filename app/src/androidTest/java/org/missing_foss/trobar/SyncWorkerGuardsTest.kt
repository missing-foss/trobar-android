// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
// A refused foreground promotion doesn't fail the run, progress
// updates are spaced out, and the last sync is stored as a time (shown with
// its year, in the app's language), not a preformatted "dd/MM HH:mm".

package org.missing_foss.trobar

import android.app.LocaleManager
import android.app.Notification
import android.os.LocaleList
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ForegroundInfo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Calendar
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class SyncWorkerGuardsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val info = ForegroundInfo(1, Notification())

    @Test
    fun aRefusedPromotionIsSwallowedAndNotAskedAgain(): Unit = runBlocking {
        var calls = 0
        val promoter = ForegroundPromoter {
            calls++
            throw IllegalStateException("ForegroundServiceStartNotAllowedException")
        }
        promoter.promote(info) // must not throw
        promoter.promote(info)
        assertTrue(promoter.refused)
        assertEquals(1, calls)
    }

    @Test
    fun anAcceptedPromotionIsPostedEachTime(): Unit = runBlocking {
        var calls = 0
        val promoter = ForegroundPromoter { calls++ }
        promoter.promote(info)
        promoter.promote(info)
        assertFalse(promoter.refused)
        assertEquals(2, calls)
    }

    @Test
    fun progressIsSpacedOutButNeverLosesTheFirstSwitchOrLast() {
        val t = ProgressThrottle(1_000)
        assertTrue(t.due(1, 10, scanning = false, nowMs = 0)) // the first
        assertFalse(t.due(2, 10, scanning = false, nowMs = 500)) // too soon
        assertTrue(t.due(3, 10, scanning = false, nowMs = 1_000)) // a second later
        assertFalse(t.due(4, 10, scanning = false, nowMs = 1_200))
        assertTrue(t.due(10, 10, scanning = false, nowMs = 1_300)) // the last track
        assertTrue(t.due(0, 0, scanning = true, nowMs = 1_400)) // a switch of phase
    }

    @Test
    fun theLastSyncIsStoredAsATimeAndShownWithItsYear(): Unit = runBlocking {
        val at = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 16, 14, 5) }.timeInMillis
        Prefs.recordSyncResult(context, at, null)
        val shown = Prefs.lastSyncAt(context).first()!!
        assertEquals(Prefs.formatSyncTime(at, AppLanguage.locale(context)), shown)
        assertTrue(shown, shown.contains("26")) // the year, in any short form
    }

    @Test
    fun theLastSyncFollowsTheAppsLanguageNotTheDevices(): Unit = runBlocking {
        // The app set to French on a device that may be in English: the
        // stored time is shown the French way, day first.
        assumeTrue(AppLanguage.systemManaged)
        val localeManager = context.getSystemService(LocaleManager::class.java)
        try {
            localeManager.applicationLocales = LocaleList.forLanguageTags("fr")
            val at = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 16, 14, 5) }.timeInMillis
            Prefs.recordSyncResult(context, at, null)
            assertTrue(Prefs.lastSyncAt(context).first()!!.startsWith("16/10/2026"))
        } finally {
            localeManager.applicationLocales = LocaleList.getEmptyLocaleList()
        }
    }

    @Test
    fun theFormatFollowsTheLanguage() {
        val at = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 16, 14, 5) }.timeInMillis
        assertTrue(Prefs.formatSyncTime(at, Locale.FRENCH).startsWith("16/10/2026"))
        assertTrue(Prefs.formatSyncTime(at, Locale.US).startsWith("10/16/26"))
    }

    @Test
    fun aLegacyStringIsShownUntilTheNextSyncReplacesIt(): Unit = runBlocking {
        context.dataStore.edit {
            it.clear()
            it[stringPreferencesKey("last_sync_at")] = "16/10 14:05"
        }
        assertEquals("16/10 14:05", Prefs.lastSyncAt(context).first())
        Prefs.recordSyncResult(context, System.currentTimeMillis(), null)
        assertFalse(Prefs.lastSyncAt(context).first() == "16/10 14:05")
        // and the old string is gone, not just outranked
        assertFalse(context.dataStore.data.first().contains(stringPreferencesKey("last_sync_at")))
    }
}
