// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
// The widget host against the in-memory dashboard API, with two test
// widgets standing in for the real ones: the account's order applied
// with hidden ids skipped and unknown ids kept, a hidden widget costing
// no request until it is switched on, the admin-only widget absent for
// a non-admin, months clamped in the PATCH, and the empty state.

package org.missing_foss.trobar

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onChildAt
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: MockWebServer
    private lateinit var api: FakeDashboardApi
    private lateinit var vm: HomeViewModel
    private val widgets = mapOf<String, Widget>(
        "library" to StatsTestWidget("library"), "most_played" to StatsTestWidget("most_played"),
        "recently_added" to StatsTestWidget("recently_added"),
    )

    @Before
    fun startServer() {
        api = FakeDashboardApi()
        server = MockWebServer()
        server.dispatcher = api
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    private lateinit var shared: AppState
    private lateinit var pairing: Prefs.Pairing

    private fun setScreen() {
        pairing = Prefs.Pairing(serverUrl = server.url("/").toString(), token = "test-token")
        shared = testAppState(context, pairing)
        vm = HomeViewModel(
            AppApiDashboardClient(context, pairing.serverUrl, pairing.token),
            AppApiClient(context, pairing.serverUrl, pairing.token),
            widgets,
            shared,
        )
        compose.setContent {
            MaterialTheme {
                HomeScreen(pairing = pairing, nav = HomeNavigation({}, {}, {}), shared = shared, widgets = widgets, vm = vm)
            }
        }
    }

    private fun waitForText(text: String) {
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun cardsFollowTheAccountsOrderSkipHiddenIdsAndDrawOnlyCatalogIds() {
        api.order = mutableListOf("devices", "zzz", "library", "most_played")
        api.disabled = mutableListOf("most_played", "suggestions", "recently_added", "recently_released")
        setScreen()
        waitForText("library shows 42 tracks")
        val list = compose.onNodeWithTag("dashboard")
        list.onChildAt(0).assert(hasTestTag("widget-devices")) // in the catalog, no renderer here: unavailable, in place
        list.onChildAt(1).assert(hasTestTag("widget-library")) // "zzz" is not in the catalog: never drawn, as on the web
        compose.onAllNodesWithTag("widget-zzz").assertCountEquals(0)
        compose.onAllNodesWithTag("widget-most_played").assertCountEquals(0)
        compose.onAllNodesWithText("Not available in the app yet", substring = true).assertCountEquals(1)
        assertEquals(1, api.hits("/api/app/library/stats")) // one visible renderable widget, one fetch
    }

    @Test
    fun anUnknownIdInTheStoredOrderSurvivesAMoveUnchanged() {
        // What keeps a browser-side order intact is what the phone writes,
        // not what it draws: a move writes the saved order back, "zzz"
        // included, rather than rebuilding it from the catalog.
        api.order = mutableListOf("devices", "zzz", "library")
        api.disabled = mutableListOf("most_played", "suggestions", "recently_added", "recently_released")
        setScreen()
        waitForText("library shows 42 tracks")
        vm.move("library", up = true)
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.patches.isNotEmpty() }
        val written = api.patches.last().getJSONArray("order").let { a -> List(a.length()) { a.getString(it) } }
        assertEquals("zzz", written[1]) // still there, still second
        assertEquals(listOf("devices", "zzz", "library", "suggestions", "recently_added", "recently_released", "most_played").sorted(), written.sorted())
    }

    @Test
    fun aHiddenWidgetMakesNoRequestUntilItIsPutBack() {
        api.disabled = mutableListOf("library", "most_played", "suggestions", "recently_added", "recently_released", "devices")
        setScreen()
        waitForText("Your dashboard is empty")
        assertEquals(0, api.hits("/api/app/library/stats"))
        vm.restore("library")
        waitForText("library shows 42 tracks")
        assertEquals(1, api.hits("/api/app/library/stats"))
        assertEquals(listOf("disabled"), api.patches.last().keys().asSequence().toList()) // the narrow write carries only what changed
    }

    @Test
    fun theAdminOnlyWidgetIsAbsentForANonAdminEvenWhenOrderedAndEnabled() {
        api.order = mutableListOf("administration", "library")
        api.disabled = mutableListOf("devices", "suggestions", "recently_added", "recently_released", "most_played")
        setScreen()
        waitForText("library shows 42 tracks")
        compose.onAllNodesWithTag("widget-administration").assertCountEquals(0)
        api.isAdmin = true
        vm.load()
        compose.waitUntil(timeoutMillis = 30_000) { compose.onAllNodesWithTag("widget-administration").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aWidgetAddedFromThePreferencesScreenShowsOnHomeWithItsData() {
        // #49: the preferences screen holds its own view model; the two meet
        // in the shared holder. Added there, the widget is drawn here and
        // its data fetched, with no reload of Home.
        api.disabled = mutableListOf("devices", "suggestions", "recently_added", "recently_released", "most_played")
        setScreen()
        waitForText("library shows 42 tracks")
        val prefsScreen = HomeViewModel(
            AppApiDashboardClient(context, pairing.serverUrl, pairing.token),
            AppApiClient(context, pairing.serverUrl, pairing.token),
            emptyMap(),
            shared,
        )
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { !prefsScreen.state.value.loading }
        prefsScreen.add("most_played")
        waitForText("most_played shows 42 tracks")
    }

    @Test
    fun monthsAreHeldToTheServersRangeInThePatch() {
        api.disabled = mutableListOf("devices", "suggestions", "most_played", "recently_released")
        setScreen()
        waitForText("library shows 42 tracks")
        vm.setMonths("recently_added", 30)
        compose.waitUntil(timeoutMillis = 30_000) { api.patches.isNotEmpty() }
        assertEquals(24, api.patches.last().getJSONObject("settings").getJSONObject("recently_added").getInt("months"))
        vm.setMonths("recently_added", 0)
        compose.waitUntil(timeoutMillis = 30_000) { api.patches.size == 2 }
        assertEquals(1, api.patches.last().getJSONObject("settings").getJSONObject("recently_added").getInt("months"))
    }

    @Test
    fun aCoverWidgetsOwnCountReachesItAndRefetchesItAlone() {
        api.disabled = mutableListOf("devices", "suggestions", "recently_released", "most_played")
        setScreen()
        waitForText("recently_added shows 42 tracks, covers 15")
        waitForText("library shows 42 tracks, covers 15")
        val before = api.hits("/api/app/library/stats")
        vm.setCoverLimit("recently_added", 45)
        waitForText("recently_added shows 42 tracks, covers 45")
        // Sent as that widget's own count, not the one value for all three.
        val settings = api.patches.last().getJSONObject("settings")
        assertEquals(45, settings.getJSONObject("recently_added").getInt("cover_limit"))
        assertTrue("no single value sent", !settings.has("cover_limit"))
        // Only the widget whose count changed is fetched again.
        assertEquals(before + 1, api.hits("/api/app/library/stats"))
        compose.onNodeWithText("library shows 42 tracks, covers 15").assertIsDisplayed()
    }

    @Test
    fun onAServerWithOneCountForAllThreeTheChangeIsSentAsThatOneValue() {
        api.perWidgetCovers = false // a server from before per-widget counts
        api.disabled = mutableListOf("devices", "suggestions", "recently_released", "most_played")
        setScreen()
        waitForText("recently_added shows 42 tracks, covers 15")
        vm.setCoverLimit("recently_added", 45)
        waitForText("recently_added shows 42 tracks, covers 45")
        // A per-widget count would be refused there; the one value is what it takes.
        assertEquals(45, api.patches.last().getJSONObject("settings").getInt("cover_limit"))
        waitForText("library shows 42 tracks, covers 45")
    }
}
