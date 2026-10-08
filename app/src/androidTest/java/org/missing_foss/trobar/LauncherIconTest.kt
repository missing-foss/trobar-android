// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
// The launcher icon per theme: exactly one launcher entry whatever the
// theme, each one opens the app, the setting moves it only once the app is in
// the background (and why), the share target is untouched, and a start that
// can't resolve a just-enabled alias yet is retried.

package org.missing_foss.trobar

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherIconTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val pm = context.packageManager

    @Before
    fun clean() = reset()

    @After
    fun reset() {
        runBlocking {
            Prefs.setMatchIconToTheme(context, false)
            Prefs.setBrandTheme(context, BrandTheme.DEFAULT.key)
        }
        LauncherIcon.show(context, BrandTheme.DEFAULT)
    }

    /** The package's launcher entries, as the launcher sees them. */
    private fun launcherEntries(): List<String> =
        pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName),
            0,
        ).map { it.activityInfo.name }

    private fun aliasName(theme: BrandTheme) = LauncherIcon.component(context, theme).className

    @Test
    fun byDefaultTheOnlyLauncherEntryIsTroubadour() {
        assertEquals(listOf(aliasName(BrandTheme.TROUBADOUR)), launcherEntries())
        // And that is the manifest's own state: nothing was written.
        for (theme in BrandTheme.entries) {
            assertEquals(
                "$theme alias",
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                pm.getComponentEnabledSetting(LauncherIcon.component(context, theme)),
            )
        }
    }

    @Test
    fun eachThemeLeavesOneLauncherEntryAndItOpensTheApp() {
        for (theme in BrandTheme.entries) {
            LauncherIcon.show(context, theme)
            assertEquals("$theme", listOf(aliasName(theme)), launcherEntries())
            launchWhenResolvable(viaAlias(theme)).use { scenario ->
                assertEquals("$theme opens", Lifecycle.State.RESUMED, scenario.state)
                scenario.onActivity { assertTrue(it is MainActivity) }
            }
        }
    }

    /** Why the change waits for the background: disabling the alias the app
     * was opened through destroys the open activity, DONT_KILL_APP or not.
     * Measured on API 34 and 35; on API 26 the activity survives, and the
     * versions between were not measured. */
    @Test
    fun disablingTheAliasInUseClosesTheOpenApp() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        launchWhenResolvable(viaAlias(BrandTheme.TROUBADOUR)).use { scenario ->
            LauncherIcon.show(context, BrandTheme.GARRIGA)
            waitFor { scenario.state == Lifecycle.State.DESTROYED }
        }
    }

    @Test
    fun theIconWaitsWhileTheAppIsOpenAndFollowsTheSettingOnceItIsNot() {
        launchWhenResolvable(viaAlias(BrandTheme.TROUBADOUR)).use { scenario ->
            runBlocking {
                Prefs.setBrandTheme(context, BrandTheme.PEIRA.key)
                Prefs.setMatchIconToTheme(context, true)
            }
            Thread.sleep(SETTLE_MS)
            assertEquals(listOf(aliasName(BrandTheme.TROUBADOUR)), launcherEntries())
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            pressHome()
            waitFor { launcherEntries() == listOf(aliasName(BrandTheme.PEIRA)) }
        }
        launchWhenResolvable(viaAlias(BrandTheme.PEIRA)).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            runBlocking { Prefs.setMatchIconToTheme(context, false) }
            pressHome()
            waitFor { launcherEntries() == listOf(aliasName(BrandTheme.TROUBADOUR)) }
        }
    }

    /** A start that can't resolve the alias yet is retried until it can.
     * The alias is enabled a second after the first attempt, so the retry
     * path runs whatever the device's timing. A disabled alias was measured
     * to fail bare on API 35, so this one passes with the old check too;
     * the wrapped form is pinned by the next test. */
    @Test
    fun aStartThatCannotResolveYetIsRetriedUntilItCan() {
        assertEquals(listOf(aliasName(BrandTheme.TROUBADOUR)), launcherEntries())
        val enabler = Thread {
            Thread.sleep(ENABLE_LATER_MS)
            LauncherIcon.show(context, BrandTheme.PEIRA)
        }.apply { start() }
        try {
            launchWhenResolvable(viaAlias(BrandTheme.PEIRA)).use { scenario ->
                assertEquals(Lifecycle.State.RESUMED, scenario.state)
            }
        } finally {
            enabler.join()
        }
    }

    /** The failure on record arrived wrapped: a RuntimeException whose
     * message is its cause's toString(), so it starts with the class name. */
    @Test
    fun anUnresolvableStartIsRecognisedWrappedOrNot() {
        val bare = RuntimeException("Unable to resolve activity for: Intent { cmp=x/.LauncherGarriga }")
        assertTrue("bare", isUnresolvable(bare))
        assertTrue("wrapped", isUnresolvable(RuntimeException(bare)))
        assertFalse("anything else", isUnresolvable(RuntimeException(IllegalStateException("boom"))))
    }

    /** On API 35 an activity start can fail to resolve a just-enabled alias
     * for a moment after the package manager already lists it. */
    private fun launchWhenResolvable(intent: Intent): ActivityScenario<MainActivity> {
        val deadline = System.currentTimeMillis() + UI_WAIT_MS
        while (true) {
            try {
                return ActivityScenario.launch(intent)
            } catch (e: RuntimeException) {
                if (!isUnresolvable(e) || System.currentTimeMillis() > deadline) throw e
                Thread.sleep(POLL_MS)
            }
        }
    }

    /** "Unable to resolve activity" anywhere in [e]'s cause chain. */
    private fun isUnresolvable(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.any { it.message?.contains(UNRESOLVABLE) == true }

    private fun viaAlias(theme: BrandTheme) =
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(LauncherIcon.component(context, theme))

    /** Every activity of the app stops, as when the user leaves it. */
    private fun pressHome() {
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
    }

    @Test
    fun theShareTargetStillResolves() {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(context.packageName)
        val targets = pm.queryIntentActivities(send, 0).map { it.activityInfo.name }
        assertEquals(listOf("${MainActivity::class.java.`package`!!.name}.AddPlaylistLink"), targets)
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + UI_WAIT_MS
        while (!condition()) {
            assertTrue("condition not met within $UI_WAIT_MS ms", System.currentTimeMillis() < deadline)
            Thread.sleep(POLL_MS)
        }
    }

    private companion object {
        const val SETTLE_MS = 3_000L
        const val POLL_MS = 200L
        const val ENABLE_LATER_MS = 1_000L
        const val UNRESOLVABLE = "Unable to resolve activity"
    }
}
