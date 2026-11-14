// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
// #61: the three settings named in the issue — server URL edit, missing-file
// policy, and dynamic color — each verified against the real Prefs DataStore,
// not just that the row renders. getDeviceInfo() on load hits a real local
// HTTP server (see EnrollmentWizardTest's header for why).

package org.missing_foss.trobar

import android.app.Instrumentation
import android.content.IntentFilter
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: MockWebServer
    // A dummy SAF tree URI — storageStatsForTree/humanReadablePath both
    // degrade gracefully on one that doesn't resolve to a real picked
    // folder (see StorageStats.kt/MainActivity.kt), so no real SAF pick is
    // needed just to host this screen.
    private val dummyTreeUri = "content://com.android.externalstorage.documents/tree/primary%3ATrobarTest"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"name": "Test Phone", "max_size_bytes": null, "device_type": "phone", "source_of_truth": "server", "icon": "clickwheel"}""",
            ),
        )
        // Reset the DataStore prefs this test touches, so a prior run's
        // leftover state can't make this one flaky either way.
        runBlocking {
            Prefs.setMissingFileBehavior(context, Prefs.MISSING_ASK)
            Prefs.setUseDynamicColor(context, false)
            Prefs.setRequireChargingAndIdle(context, false)
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun setScreen(onServerUrlChanged: (String) -> Unit = {}) {
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    pairing = Prefs.Pairing(serverUrl = server.url("/").toString(), token = "test-token"),
                    treeUri = dummyTreeUri,
                    nav = SettingsNav(onOpenAbout = {}),
                    onServerUrlChanged = onServerUrlChanged,
                    onFolderChanged = {},
                    onUnpair = {},
                )
            }
        }
    }

    @Test
    fun theDevicesContentSettingsAreOneGroup() {
        setScreen()
        val group = hasAnyAncestor(hasTestTag("on_device_settings"))
        for (label in listOf("Keep device content", "Locally deleted files", "Allocated space")) {
            compose.onNode(hasText(label) and group).assertExists()
        }
    }

    @Test
    fun aProblemSitsOnTopAndTheRestStaysReachable() {
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    pairing = Prefs.Pairing(serverUrl = server.url("/").toString(), token = "test-token"),
                    treeUri = "content://com.android.externalstorage.documents/tree/primary%3ATrobarTest",
                    nav = SettingsNav(
                        onOpenAbout = {},
                        problem = {
                            ConnectionProblemCard(
                                unauthorized = false, serverUrl = "https://music.example",
                                onRetry = {}, onReEnroll = {}, onServerUrlChanged = {},
                            )
                        },
                    ),
                    onServerUrlChanged = {}, onFolderChanged = {}, onUnpair = {},
                )
            }
        }
        compose.onNodeWithTag("connection_problem").assertIsDisplayed()
        compose.onNodeWithText("Keep device content").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun hideFromGallerySaysWhatElseItHides() {
        assertTrue(context.getString(R.string.hide_from_gallery_description).contains("MediaStore"))
    }

    @Test
    fun theWaitingLinesNameTheSettingByItsLabel() {
        val label = context.getString(R.string.auto_sync_via_label)
        assertTrue(context.getString(R.string.sync_blocked_roaming).contains(label))
        assertTrue(context.getString(R.string.sync_blocked_needs_unmetered).contains(label))
    }

    @Test
    fun aDevicesFormatAndArtistImagesReadAsWords() {
        val info = DeviceInfo("Phone", null, "phone", artistImages = "small", transcodeFormat = "mp3_320")
        compose.setContent { MaterialTheme { Column { ServerOwnedSettings(info) } } }
        compose.onNodeWithText(context.getString(R.string.transcode_mp3_320)).assertExists()
        compose.onNodeWithText("Small").assertExists()
        compose.onAllNodesWithText("MP3_320").assertCountEquals(0)
    }

    @Test
    fun everyFormatTheServerSendsHasWordsAndAnUnknownOneShowsAsSent() {
        for (format in listOf("mp3_320", "mp3_256", "mp3_192", "mp3_128")) {
            assertTrue(format, transcodeLabel(format) != null)
        }
        assertEquals(R.string.transcode_originals, transcodeLabel(null))
        assertEquals(null, transcodeLabel("opus_96"))
    }

    private fun waitForServerCard() = compose.waitUntil(timeoutMillis = 5_000) {
        compose.onAllNodes(hasText("Artist images") and hasAnyAncestor(hasTestTag("server_owned_settings")))
            .fetchSemanticsNodes().isNotEmpty()
    }

    private fun chooseArtistImages(option: String) {
        compose.onNode(hasText("Artist images") and hasAnyAncestor(hasTestTag("server_owned_settings")))
            .performScrollTo().performClick()
        compose.waitForText(option)
        compose.onNode(hasText(option) and hasAnyAncestor(isDialog())).performClick()
    }

    private fun artistImagesShows(value: String) = compose.onNode(
        hasText(value) and hasAnyAncestor(hasTestTag("server_owned_settings")), useUnmergedTree = true)

    /** The first device read answers with these artist images instead of none. */
    private fun startWithArtistImages(value: String) {
        server.shutdown()
        server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(200).setBody(deviceInfoJson("\"$value\"")))
    }

    private fun deviceInfoJson(artistImages: String) =
        """{"name": "Test Phone", "max_size_bytes": null, "device_type": "phone", "source_of_truth": "server", "artist_images": $artistImages}"""

    private fun waitForArtistImages(value: String) = compose.waitUntil(timeoutMillis = 5_000) {
        compose.onAllNodes(hasText(value) and hasAnyAncestor(hasTestTag("server_owned_settings")), useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun artistImagesChosenHereAreSavedAndReadBackFromTheServer() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status": "ok", "artist_images": "small"}"""))
        // The read-back answers otherwise than the tap: what is shown is the server's.
        server.enqueue(MockResponse().setResponseCode(200).setBody(deviceInfoJson("\"full\"")))
        setScreen()
        waitForServerCard()
        chooseArtistImages("Small")
        compose.waitUntil(timeoutMillis = 5_000) { server.requestCount >= 3 }
        server.takeRequest() // the first read
        val patch = server.takeRequest()
        assertEquals("PATCH", patch.method)
        assertEquals("/api/device/artist-images", patch.path)
        assertEquals("""{"artist_images":"small"}""", patch.body.readUtf8())
        assertEquals("/api/device/info", server.takeRequest().path) // read back: the server decides
        waitForArtistImages("Full")
        artistImagesShows("Small").assertDoesNotExist()
    }

    @Test
    fun offIsSentAsNoValue() {
        startWithArtistImages("full")
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status": "ok", "artist_images": null}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody(deviceInfoJson("null")))
        setScreen()
        waitForServerCard()
        waitForArtistImages("Full")
        chooseArtistImages("Off")
        compose.waitUntil(timeoutMillis = 5_000) { server.requestCount >= 3 }
        server.takeRequest() // the first read
        val patch = server.takeRequest()
        assertEquals("PATCH", patch.method)
        // The server takes null, "small" or "full": "off" would be refused.
        assertEquals("""{"artist_images":null}""", patch.body.readUtf8())
        waitForArtistImages("Off")
    }

    @Test
    fun aReadBackThatIsNotJsonIsAFailedSaveNotACrash() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status": "ok", "artist_images": "small"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>a proxy's page</html>"))
        setScreen()
        waitForServerCard()
        chooseArtistImages("Small")
        compose.waitUntil(timeoutMillis = 5_000) { server.requestCount >= 3 }
        waitForArtistImages("Off")
        artistImagesShows("Small").assertDoesNotExist()
    }

    @Test
    fun aNetworkFailureKeepsTheOldValueAndTheChoice() {
        setScreen()
        waitForServerCard()
        server.shutdown() // the save's connection is refused
        chooseArtistImages("Small")
        // A refused connection answers fast, so the Small shown at the tap may
        // already be gone: the wait is for the revert to Off.
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodes(hasText("Off") and hasAnyAncestor(hasTestTag("server_owned_settings")), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        artistImagesShows("Small").assertDoesNotExist()
        compose.onNodeWithText("Change the name and the format on the server, under Devices.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun anOlderServerKeepsArtistImagesReadOnly() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error": "not found"}"""))
        setScreen()
        waitForServerCard()
        chooseArtistImages("Full")
        compose.waitForText("This server can't take that from the phone yet", substring = true)
        artistImagesShows("Off").assertExists()
        compose.onNodeWithText("Change these on the server, under Devices.").performScrollTo().assertIsDisplayed()
        // Read-only now: a tap opens no choice.
        compose.onNode(hasText("Artist images") and hasAnyAncestor(hasTestTag("server_owned_settings")))
            .performScrollTo().performClick()
        compose.waitForIdle()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
    }

    @Test
    fun aFailedSaveKeepsTheOldValueAndTheChoice() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error": "boom"}"""))
        setScreen()
        waitForServerCard()
        chooseArtistImages("Small")
        compose.waitForText("boom", substring = true)
        artistImagesShows("Off").assertExists()
        compose.onNodeWithText("Change the name and the format on the server, under Devices.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun whatOnlyTheServerCanChangeIsShownAndSaysWhere() {
        setScreen()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("server_owned_settings").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Change the name and the format on the server, under Devices.").performScrollTo().assertIsDisplayed()
        val card = hasAnyAncestor(hasTestTag("server_owned_settings"))
        compose.onNode(hasText(context.getString(R.string.transcode_originals)) and card).assertExists()
        compose.onNode(hasText("Off") and card).assertExists()
    }

    @Test
    fun theChargingHintNamesNoInterval() {
        val hint = context.getString(R.string.charging_idle_description)
        assertFalse(hint, hint.contains("hour") || hint.contains("6"))
        val fr = context.createConfigurationContext(
            android.content.res.Configuration().apply { setLocale(java.util.Locale.FRENCH) },
        ).getString(R.string.charging_idle_description)
        assertFalse(fr, fr.contains("heure") || fr.contains("6"))
    }

    @Test
    fun theDeviceTileDrawsThisDevicesOwnIcon() {
        setScreen()
        // Until the info arrives the tile draws a phone; then this device's own icon.
        compose.waitUntil(timeoutMillis = 30_000) { drawn() == listOf("ic_device_clickwheel") }
    }

    private fun drawn(): List<String> = compose.onAllNodes(SemanticsMatcher.keyIsDefined(DeviceIconRes), useUnmergedTree = true)
        .fetchSemanticsNodes().map { context.resources.getResourceEntryName(it.config[DeviceIconRes]) }

    @Test
    fun serverUrlEditFiresCallbackWithNewValue() {
        var changedTo: String? = null
        setScreen(onServerUrlChanged = { changedTo = it })

        compose.onNodeWithText("Server").performClick()
        compose.onNodeWithText("URL").performTextClearance()
        compose.onNodeWithText("URL").performTextInput("https://new.example.com")
        compose.onNodeWithText("Save").performClick()

        assertEquals("https://new.example.com", changedTo)
    }

    @Test
    fun serverUrlWithoutASchemeCannotBeSaved() {
        var changedTo: String? = null
        setScreen(onServerUrlChanged = { changedTo = it })

        compose.onNodeWithText("Server").performClick()
        compose.onNodeWithText("URL").performTextClearance()
        compose.onNodeWithText("URL").performTextInput("myserver:8080")
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("URL").performTextClearance()
        compose.onNodeWithText("URL").performTextInput("https://myserver:8080")
        compose.onNodeWithText("Save").assertIsEnabled()
        assertNull(changedTo) // nothing stored until a valid address is saved
    }

    @Test
    fun missingFilePolicyPersistsToRealPrefs() {
        setScreen()
        compose.onNodeWithText("Locally deleted files").performScrollTo().performClick()
        compose.onNodeWithText("Resync").performClick()

        compose.waitUntil(timeoutMillis = UI_WAIT_MS) {
            runBlocking { Prefs.missingFileBehavior(context).first() } == Prefs.MISSING_ALWAYS_RESYNC
        }
        assertEquals(Prefs.MISSING_ALWAYS_RESYNC, runBlocking { Prefs.missingFileBehavior(context).first() })
    }

    @Test
    fun dynamicColorTogglePersistsToRealPrefs() {
        // Only shown on API 31+ (Build.VERSION_CODES.S) — see SettingsScreen.
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        setScreen()
        assertEquals(false, runBlocking { Prefs.useDynamicColor(context).first() })

        // SettingsScreen is a verticalScroll Column, not a LazyColumn — every
        // row exists in the semantics tree regardless of scroll position, but
        // performClick() dispatches a real touch at the node's actual
        // coordinates. This row sits below Server/Storage/Language, off the
        // initial viewport, so the click needs to scroll it into view first
        // or it silently lands nowhere.
        compose.onNodeWithTag("dynamic_color_switch").performScrollTo().performClick()

        compose.waitUntil(timeoutMillis = UI_WAIT_MS) {
            runBlocking { Prefs.useDynamicColor(context).first() }
        }
        assertTrue(runBlocking { Prefs.useDynamicColor(context).first() })
    }

    @Test
    fun matchIconToThemeTogglePersistsToRealPrefs() {
        runBlocking { Prefs.setMatchIconToTheme(context, false) }
        setScreen()
        compose.onNodeWithTag("match_icon_switch").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) {
            runBlocking { Prefs.matchIconToTheme(context).first() }
        }
        runBlocking { Prefs.setMatchIconToTheme(context, false) }
    }

    @Test
    fun chargingAndIdleTogglePersistsToRealPrefs() {
        // #93: unlike the network-mode row above it, this one isn't
        // telephony-gated -- always present regardless of device type.
        setScreen()
        assertEquals(false, runBlocking { Prefs.requireChargingAndIdle(context).first() })

        compose.onNodeWithTag("charging_idle_switch").performScrollTo().performClick()

        compose.waitUntil(timeoutMillis = UI_WAIT_MS) {
            runBlocking { Prefs.requireChargingAndIdle(context).first() }
        }
        assertTrue(runBlocking { Prefs.requireChargingAndIdle(context).first() })
    }

    @Test
    fun languageRowOpensTheSystemLanguagePageOnApi33() {
        // API 33+: the per-app language is the system's. The row must hand
        // over to the system page, not offer an in-app choice that the
        // system never receives.
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Blocks the launch and counts it, so the test never leaves the app.
        // The intent carries a package: URI, and a filter without a data
        // scheme never matches an intent that has data.
        val filter = IntentFilter(Settings.ACTION_APP_LOCALE_SETTINGS).apply { addDataScheme("package") }
        val monitor = instrumentation.addMonitor(
            filter,
            Instrumentation.ActivityResult(0, null),
            true,
        )
        try {
            setScreen()
            compose.onNodeWithText("Language").performScrollTo().performClick()
            compose.waitUntil(timeoutMillis = UI_WAIT_MS) { monitor.hits == 1 }
            assertEquals(0, compose.onAllNodesWithText("Français").fetchSemanticsNodes().size)
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun languagePickerStoresTheChoiceBelowApi33() {
        assumeTrue(Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
        try {
            setScreen()
            compose.onNodeWithText("Language").performScrollTo().performClick()
            compose.onNodeWithText("Français").performClick()
            compose.waitUntil(timeoutMillis = UI_WAIT_MS) { AppLanguage.choice(context) == "fr" }
        } finally {
            AppLanguage.choose(context, AppLanguage.SYSTEM)
        }
    }
}
