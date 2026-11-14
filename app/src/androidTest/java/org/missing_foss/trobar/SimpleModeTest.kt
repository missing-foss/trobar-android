// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
// The two modes on the real host: a DAP opens in Simple mode and a phone in
// Full; pinning either in Settings switches at once, with no restart, and is
// stored; Simple mode keeps Settings reachable and back returns to the Sync
// screen; a link shared to the app still opens the subscriptions there; and
// the Simple screens break text between words only at 320 dp in French at
// 2.0x. The device is set per test, through LocalDeviceProfile.

package org.missing_foss.trobar

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SimpleModeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: MockWebServer

    @Before
    fun startServer() {
        runBlocking {
            Prefs.setAppModeChoice(context, AppModeChoice.AUTO)
            Prefs.clearAppApi(context)
            Prefs.clearHeaderDevice(context)
        }
        val library = FakeAppApi()
        val dashboard = FakeDashboardApi(library)
        val playlists = FakePlaylistApi(library)
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/api/device/info" -> MockResponse().setResponseCode(200).setBody(
                    """{"name": "Test DAP", "max_size_bytes": null, "device_type": "dap", "source_of_truth": "server", "app_api": 2}""",
                )
                (request.path ?: "").startsWith("/api/app/playlists") -> playlists.dispatch(request)
                else -> dashboard.dispatch(request)
            }
        }
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
        runBlocking { Prefs.setAppModeChoice(context, AppModeChoice.AUTO) }
    }

    private fun setApp(device: DeviceProfile, sharedLink: String? = null, widthDp: Int? = null, fontScale: Float? = null, french: Boolean = false) {
        val pairing = Prefs.Pairing(serverUrl = server.url("/").toString(), token = "test-token")
        compose.setContent {
            val base = LocalContext.current
            // Found through the activity, which the configuration context below hides.
            val owner = LocalActivityResultRegistryOwner.current!!
            val config = Configuration(base.resources.configuration).apply { if (french) setLocale(Locale.FRENCH) }
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides base.createConfigurationContext(config),
                LocalConfiguration provides config,
                LocalDensity provides Density(density.density, fontScale ?: density.fontScale),
                LocalDeviceProfile provides device,
                LocalActivityResultRegistryOwner provides owner,
            ) {
                MaterialTheme {
                    Box(if (widthDp != null) Modifier.requiredWidth(widthDp.dp) else Modifier) {
                        PairedApp(
                            pairing = pairing,
                            treeUri = "content://test/tree",
                            onServerUrlChanged = {},
                            onFolderChanged = {},
                            onUnpair = {},
                            onReEnroll = {},
                            sharedLink = SharedLinkIn(sharedLink) {},
                        )
                    }
                }
            }
        }
    }

    private fun shows(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(timeoutMillis = UI_WAIT_MS) { shows(matcher) }
    private val simpleScreen = hasTestTag("simple_sync_screen")
    private val homeTab = hasContentDescription("Home") and isSelectable()

    private fun pressBack() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /** Settings → App mode → [option], on whichever Settings is showing. */
    private fun pickAppMode(option: String) {
        compose.waitForText("App mode")
        compose.onNodeWithText("App mode").performScrollTo().performClick()
        compose.onNodeWithText(option).performClick()
    }

    /** Settings → Auto-sync, on the Simple Settings screen. */
    private fun openNetworkChoice() {
        compose.waitForText("Auto-sync")
        compose.onNodeWithText("Auto-sync").performScrollTo().performClick()
    }

    @Test
    fun aDapCanChooseToSyncOverAPhonesHotspot() {
        runBlocking { Prefs.setNetworkMode(context, Prefs.NETWORK_WIFI_ONLY) }
        setApp(DAP)
        waitFor(simpleScreen)
        compose.onNodeWithTag("open_settings").performClick()
        openNetworkChoice()
        // No mobile-data choices on a device without mobile data.
        // (in the row, as the current choice, and in the list)
        assertTrue("unmetered option", shows(hasText("Unmetered networks only")))
        assertTrue("no mobile data option", !shows(hasText("Wi-Fi + mobile data")))
        compose.onNodeWithText("Any network, phone hotspots too").performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) {
            runBlocking { Prefs.networkMode(context).first() } == Prefs.NETWORK_ANY
        }
    }

    @Test
    fun aDapsHotspotChoiceIsNotResetToWifiOnly() {
        // The old Settings forced Wi-Fi only on every open without telephony.
        runBlocking { Prefs.setNetworkMode(context, Prefs.NETWORK_ANY) }
        setApp(DAP)
        waitFor(simpleScreen)
        compose.onNodeWithTag("open_settings").performClick()
        compose.waitForText("Auto-sync")
        compose.waitForIdle()
        assertEquals(Prefs.NETWORK_ANY, runBlocking { Prefs.networkMode(context).first() })
    }

    @Test
    fun aDapStoredOnWifiAndMobileIsShownAsUnmeteredOnly() {
        // A mode a DAP can't offer (left from a backup, say) becomes the one
        // it can, so the row always shows a choice.
        runBlocking { Prefs.setNetworkMode(context, Prefs.NETWORK_WIFI_AND_MOBILE) }
        setApp(DAP)
        waitFor(simpleScreen)
        compose.onNodeWithTag("open_settings").performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) {
            runBlocking { Prefs.networkMode(context).first() } == Prefs.NETWORK_WIFI_ONLY
        }
    }

    @Test
    fun aPhoneKeepsItsMobileDataChoices() {
        setApp(PHONE)
        waitFor(homeTab)
        compose.onNode(hasContentDescription("Settings") and isSelectable()).performClick()
        openNetworkChoice()
        compose.onNodeWithText("Wi-Fi + mobile data").assertExists()
        assertTrue("no DAP wording", !shows(hasText("Unmetered networks only")))
        pressBack()
    }

    @Test
    fun aDapOpensInSimpleModeWithTheSyncCardAndNoTabs() {
        setApp(DAP)
        waitFor(simpleScreen)
        compose.onNodeWithTag("sync_status_card").assertExists()
        assertTrue("no tab bar", !shows(homeTab))
    }

    @Test
    fun aPhoneOpensInFullMode() {
        setApp(PHONE)
        waitFor(homeTab)
        assertTrue("no Simple screen", !shows(simpleScreen))
    }

    @Test
    fun aTabletWithoutAMobileNetworkOpensInFullMode() {
        setApp(DeviceProfile(hasTelephony = false, smallestWidthDp = 800))
        waitFor(homeTab)
    }

    @Test
    fun onADapPinningFullSwitchesAtOnceAndAutoBringsSimpleBack() {
        setApp(DAP)
        waitFor(simpleScreen)
        compose.onNodeWithTag("open_settings").performClick()
        pickAppMode("Full")
        waitFor(homeTab)
        assertEquals(AppModeChoice.FULL, runBlocking { Prefs.appModeChoice(context).first() })

        // Back the other way, from Full mode's own Settings tab.
        compose.onNode(hasContentDescription("Settings") and isSelectable()).performClick()
        pickAppMode("Auto: Simple on this device")
        waitFor(simpleScreen)
        assertEquals(AppModeChoice.AUTO, runBlocking { Prefs.appModeChoice(context).first() })
    }

    @Test
    fun onAPhonePinningSimpleSwitchesAtOnceAndIsStored() {
        setApp(PHONE)
        waitFor(homeTab)
        compose.onNode(hasContentDescription("Settings") and isSelectable()).performClick()
        compose.waitForText("Auto: Full on this device")
        pickAppMode("Simple")
        waitFor(simpleScreen)
        assertEquals(AppModeChoice.SIMPLE, runBlocking { Prefs.appModeChoice(context).first() })
    }

    @Test
    fun inSimpleModeSettingsIsPushedAndBackReturnsToTheSyncScreen() {
        setApp(DAP)
        waitFor(simpleScreen)
        compose.onNodeWithTag("open_settings").performClick()
        compose.waitForText("App mode")
        assertTrue("Settings covers the Sync screen", !shows(simpleScreen))
        compose.onNodeWithContentDescription("Back").performClick()
        waitFor(simpleScreen)
        compose.onNodeWithTag("open_settings").performClick()
        compose.waitForText("App mode")
        pressBack()
        waitFor(simpleScreen)
    }

    @Test
    fun aLinkSharedInSimpleModeOpensTheSubscriptionsAndBackReturnsToTheSyncScreen() {
        setApp(DAP, sharedLink = "https://music.youtube.com/playlist?list=PLshared")
        compose.waitForText("Playlist subscriptions")
        pressBack()
        waitFor(simpleScreen)
        assertTrue("still no tab bar", !shows(homeTab))
    }

    @Test
    fun theSimpleScreensBreakBetweenWordsOnlyAtTheNarrowestWidthInFrenchAtTheLargestScale() {
        setApp(DAP, widthDp = NARROWEST_PHONE_DP, fontScale = LARGEST_FONT_SCALE, french = true)
        waitFor(simpleScreen)
        assertNoMidWordBreaks("Sync screen")
        compose.onNodeWithTag("open_settings").performClick()
        val fr = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.FRENCH) })
        compose.waitForText(fr.getString(R.string.app_mode_label))
        assertNoMidWordBreaks("Settings")
    }

    /** Every text on screen ends each line but its last on a space or just
     * before one. */
    private fun assertNoMidWordBreaks(screen: String) {
        compose.waitForIdle()
        val nodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertTrue("$screen has text", nodes.isNotEmpty())
        for (node in nodes) {
            val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } ?: continue
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
            val layout = layouts.singleOrNull() ?: continue
            for (line in 0 until layout.lineCount - 1) {
                val end = layout.getLineEnd(line)
                if (end <= 0 || end >= text.length) continue
                assertTrue(
                    "$screen: \"$text\" breaks mid-word after \"${text.substring(0, end)}\"",
                    text[end - 1].isWhitespace() || text[end].isWhitespace() || !text[end - 1].isLetter() || !text[end].isLetter(),
                )
            }
        }
    }

    private companion object {
        val DAP = DeviceProfile(hasTelephony = false, smallestWidthDp = 360)
        val PHONE = DeviceProfile(hasTelephony = true, smallestWidthDp = 411)
        const val NARROWEST_PHONE_DP = 320 // the smallest width Android's compatibility definition allows a phone
        const val LARGEST_FONT_SCALE = 2f
    }
}
