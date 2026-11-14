// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
// The four widgets that are not cover grids, on the real host with the
// real registry: the library's counts, hours and two charts (and an empty
// frame, not a crash, on zero data); the devices with the owner badge only
// on a pinned device and one failed usage read not taking the others
// down; the most-played bars, the two empty states and the query as sent;
// the administration counts for an admin, and no request at all for a
// non-admin.

package org.missing_foss.trobar

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemainingWidgetsTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: MockWebServer
    private lateinit var api: FakeDashboardApi
    private lateinit var app: FakeAppApi

    private val everyWidget = listOf("library", "devices", "suggestions", "recently_added", "recently_released", "most_played", "administration")

    @Before
    fun startServer() {
        app = FakeAppApi()
        app.artist("Aphelion", Triple("Parallax", 2001, 10), Triple("Event Horizon", 2004, 8))
        app.artist("Borealis", Triple("Dawn", null, 5))
        api = FakeDashboardApi(app)
        server = MockWebServer()
        server.dispatcher = api
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    /** Shows only the named widgets, so each test reads one card. */
    private fun only(vararg ids: String) {
        api.disabled = (everyWidget - ids.toSet()).toMutableList()
    }

    private fun setScreen(widthDp: Int? = null, fontScale: Float? = null, french: Boolean = false) {
        val pairing = Prefs.Pairing(serverUrl = server.url("/").toString(), token = "test-token")
        val shared = testAppState(context, pairing)
        val vm = HomeViewModel(
            AppApiDashboardClient(context, pairing.serverUrl, pairing.token),
            AppApiClient(context, pairing.serverUrl, pairing.token),
            WidgetRegistry.all,
            shared,
        )
        compose.setContent {
            val base = LocalContext.current
            val config = Configuration(base.resources.configuration).apply { if (french) setLocale(Locale.FRENCH) }
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides base.createConfigurationContext(config),
                LocalConfiguration provides config,
                LocalDensity provides Density(density.density, fontScale ?: density.fontScale),
            ) {
                MaterialTheme {
                    Box(if (widthDp != null) Modifier.requiredWidth(widthDp.dp) else Modifier) {
                        HomeScreen(pairing = pairing, nav = HomeNavigation({}, {}, {}, { openedDevice = it }), shared = shared, vm = vm)
                    }
                }
            }
        }
    }

    /** True if [text] wraps between two letters, not between words. */
    private fun breaksMidWord(text: String): Boolean {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val layout = layouts.single()
        return (0 until layout.lineCount - 1).any { line ->
            val end = layout.getLineEnd(line)
            text[end - 1].isLetterOrDigit() && text[end].isLetterOrDigit()
        }
    }

    private fun inDevice(id: Long, text: String, substring: Boolean = false) =
        compose.onAllNodes(hasText(text, substring = substring) and hasAnyAncestor(hasTestTag("device-$id")), useUnmergedTree = true)

    @Test
    fun theLibraryWidgetShowsTheCountsTheHoursAndBothCharts() {
        only("library")
        api.statsJson = """{"total_tracks": 23, "total_duration_seconds": 7300,
            "by_codec": {"flac": {"tracks": 20, "bytes": 8000}, "mp3": {"tracks": 3, "bytes": 200}}, "by_decade": {"2000s": 18, "2010s": 5}}"""
        setScreen()
        compose.waitForText("2 artists · 3 albums") // the artist list's two counts, not the stats route's
        compose.onNodeWithText("2 hours of music").assertIsDisplayed()
        compose.onNodeWithText("BY FORMAT").assertIsDisplayed()
        compose.onNodeWithTag("chart-by-format").assertIsDisplayed()
        compose.onNodeWithText("tracks %").assertIsDisplayed()
        compose.onNodeWithText("storage %").assertIsDisplayed()
        compose.onNodeWithText("BY DECADE").assertIsDisplayed()
        compose.onNodeWithTag("chart-by-decade").assertIsDisplayed()
        assertEquals(1, api.hits("/api/app/library/stats"))
        assertEquals(1, api.hits("/api/app/library/artists"))
    }

    @Test
    fun theChartLegendKeepsEachEntryWholeOnTheNarrowestPhone() {
        only("library")
        api.statsJson = """{"total_tracks": 23, "total_duration_seconds": 7300,
            "by_codec": {"flac": {"tracks": 20, "bytes": 8000}, "mp3": {"tracks": 3, "bytes": 200}}, "by_decade": {"2000s": 18, "2010s": 5}}"""
        setScreen(widthDp = NARROWEST_PHONE_DP, fontScale = LARGEST_FONT_SCALE, french = true)
        compose.waitForText("stockage %")
        // Beside "morceaux %", the second entry was left too little and broke
        // as "stockag/e %".
        assertEquals(emptyList<String>(), listOf("morceaux %", "stockage %").filter { breaksMidWord(it) })
    }

    @Test
    fun zeroDataDrawsAnEmptyFrameNotACrashAndNoSectionForAnEmptyMap() {
        only("library")
        api.statsJson = """{"total_tracks": 0, "total_duration_seconds": 0, "by_codec": {"flac": {"tracks": 0, "bytes": 0}}, "by_decade": {}}"""
        setScreen()
        compose.waitForText("2 artists · 3 albums")
        compose.onNodeWithText("0 hours of music").assertIsDisplayed()
        compose.onNodeWithTag("chart-by-format").assertIsDisplayed() // a zero column, drawn
        compose.onAllNodesWithText("BY DECADE").assertCountEquals(0) // nothing to chart: no section
        compose.onAllNodesWithTag("chart-by-decade").assertCountEquals(0)
    }

    private var openedDevice: Long? = null

    @Test
    fun tappingADeviceOpensWhatIsSelectedForIt() {
        only("devices")
        setScreen()
        compose.waitForText("Car")
        compose.onNodeWithTag("device-2").performClick()
        compose.waitForIdle()
        assertEquals(2L, openedDevice)
    }

    @Test
    fun theDevicesWidgetBadgesOnlyThePinnedDeviceAndShowsEachStorage() {
        only("devices")
        app.ownedBy[2L] = "bob" // the car is bob's, pinned to this account
        app.usageUsedBytes = 600_000_000L
        app.usageMaxBytes = 1_000_000_000L
        setScreen()
        compose.waitForText("Car")
        compose.onAllNodesWithText("bob's").assertCountEquals(1)
        inDevice(2, "bob's").assertCountEquals(1)
        inDevice(1, "this phone").assertCountEquals(1)
        inDevice(2, "this phone").assertCountEquals(0)
        inDevice(1, "0.6 GB of 1.0 GB").assertCountEquals(1)
        inDevice(2, "0.6 GB of 1.0 GB").assertCountEquals(1)
        compose.onAllNodesWithText("never seen · up to date").assertCountEquals(2)
    }

    @Test
    fun eachDeviceRowDrawsItsOwnIconElseItsTypes() {
        only("devices")
        app.icons[2L] = "clickwheel"
        setScreen()
        compose.waitForText("Car")
        fun drawnIn(id: Long) = compose.onAllNodes(SemanticsMatcher.keyIsDefined(DeviceIconRes) and hasAnyAncestor(hasTestTag("device-$id")), useUnmergedTree = true)
            .fetchSemanticsNodes().map { context.resources.getResourceEntryName(it.config[DeviceIconRes]) }
        assertEquals(listOf("ic_device_phone"), drawnIn(1L)) // no icon: its type, a phone
        assertEquals(listOf("ic_device_clickwheel"), drawnIn(2L))
    }

    @Test
    fun oneDeviceWhoseUsageReadFailsStillShowsWithoutItsStorage() {
        only("devices")
        app.usageFails += 2L
        setScreen()
        compose.waitForText("Car")
        inDevice(1, "GB", substring = true).assertCountEquals(1)
        inDevice(2, "GB", substring = true).assertCountEquals(0)
    }

    @Test
    fun aDeviceWithNoLimitShowsItsBarAgainstTheVolumeItReported() {
        only("devices")
        app.usageUsedBytes = 600_000_000L
        app.usageMaxBytes = null // no admin-set limit
        app.usageReportedTotalBytes = 2_000_000_000L // the device has reported its volume
        setScreen()
        compose.waitForText("Car")
        compose.onAllNodesWithText("0.6 GB of 2.0 GB").assertCountEquals(2)
        compose.onAllNodesWithText("capacity not reported", substring = true).assertCountEquals(0)
    }

    @Test
    fun aDeviceWithNoLimitAndNoReportSaysCapacityNotReported() {
        only("devices")
        app.usageUsedBytes = 600_000_000L
        app.usageMaxBytes = null
        setScreen()
        compose.waitForText("Car")
        compose.onAllNodesWithText("0.6 GB used · capacity not reported").assertCountEquals(2)
    }

    @Test
    fun codecSharesAreZeroNotNaNWhenTheByteTotalIsZero() {
        // Reachable: the server coalesces an unknown size to zero bytes, so a
        // library whose sizes are all unknown has tracks but no bytes.
        val shares = CodecShares.of(mapOf("flac" to CodecStats(tracks = 3, bytes = 0), "mp3" to CodecStats(tracks = 1, bytes = 0)))
        assertEquals(listOf("FLAC", "MP3"), shares.labels)
        assertEquals(listOf(75.0, 25.0), shares.tracksPct)
        assertEquals(listOf(0.0, 0.0), shares.bytesPct)
        val empty = CodecShares.of(emptyMap())
        assertEquals(emptyList<String>(), empty.labels)
    }

    @Test
    fun theMostPlayedWidgetDrawsABarPerAlbumWithItsPlaysAndAsksForSixMonths() {
        only("most_played")
        api.mostPlayed += Triple("A", "One", 10)
        api.mostPlayed += Triple("B", "Two", 1)
        setScreen()
        compose.waitForText("One · A")
        compose.onNodeWithText("10 plays").assertIsDisplayed()
        compose.onNodeWithText("Two · B").assertIsDisplayed()
        compose.onNodeWithText("1 play").assertIsDisplayed()
        compose.onNodeWithTag("most-played-bars").assertIsDisplayed()
        assertEquals(listOf("6month" to 8), api.mostPlayedAsked)
    }

    @Test
    fun withoutAListeningHistoryTheMostPlayedWidgetIsLeftOutAndNothingIsSaved() {
        only("library", "most_played")
        api.mostPlayedConfigured = false
        setScreen()
        compose.waitForText("1 hour of music") // the library widget beside it has rendered
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.mostPlayedAsked.isNotEmpty() }
        compose.waitForIdle()
        compose.onAllNodesWithTag("widget-most_played").assertCountEquals(0)
        assertTrue("left out on the phone only: nothing written to the shared preferences", api.patches.isEmpty())
    }

    @Test
    fun theMostPlayedWidgetSaysWhenNothingWasPlayed() {
        only("most_played")
        setScreen()
        compose.waitForText("Nothing played in this period.")
        compose.onAllNodesWithTag("most-played-bars").assertCountEquals(0)
    }

    @Test
    fun theAdministrationWidgetShowsTheCountsForAnAdmin() {
        api.isAdmin = true
        api.order = mutableListOf("administration", "library")
        only("administration")
        api.users = 3
        api.delegations = 1
        setScreen()
        compose.waitForText("3 users · 1 active delegation")
        // Said behind the card title's info icon.
        compose.onAllNodesWithText("Managing users and delegations is done in the browser.").assertCountEquals(0)
        compose.onNodeWithContentDescription("About Administration").performClick()
        compose.onNodeWithText("Managing users and delegations is done in the browser.").assertIsDisplayed()
    }

    @Test
    fun aNonAdminNeverAsksForTheCountsTheServerWouldRefuse() {
        api.order = mutableListOf("administration", "library")
        only("administration", "library")
        setScreen()
        compose.waitForText("2 artists · 3 albums") // the dashboard has loaded
        compose.onAllNodesWithTag("widget-administration").assertCountEquals(0)
        assertEquals(0, api.hits("/api/app/admin/counts"))
    }

    private companion object {
        const val NARROWEST_PHONE_DP = 320 // the smallest width Android's compatibility definition allows a phone
        const val LARGEST_FONT_SCALE = 2f
    }
}
