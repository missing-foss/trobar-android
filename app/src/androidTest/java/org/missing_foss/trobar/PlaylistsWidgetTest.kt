// SPDX-FileCopyrightText: 2026 missing-foss
//
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Home's Playlists widget: what it asks for, and what a row does. */
@RunWith(AndroidJUnit4::class)
class PlaylistsWidgetTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: MockWebServer
    private lateinit var app: FakeAppApi
    private lateinit var api: FakeDashboardApi
    private lateinit var shared: AppState
    private var openedList = false
    private lateinit var homeVm: HomeViewModel

    @Before
    fun startServer() {
        runBlocking { Prefs.clearHeaderDevice(context) }
        app = FakeAppApi()
        api = FakeDashboardApi(app)
        api.catalog = api.catalog + ("playlists" to false)
        api.disabled = api.catalog.map { it.first }.filter { it != "playlists" }.toMutableList()
        server = MockWebServer()
        server.dispatcher = api
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    private fun setScreen() {
        val pairing = Prefs.Pairing(serverUrl = server.url("/").toString(), token = "test-token")
        shared = testAppState(context, pairing)
        homeVm = HomeViewModel(
            AppApiDashboardClient(context, pairing.serverUrl, pairing.token),
            AppApiClient(context, pairing.serverUrl, pairing.token),
            WidgetRegistry.all,
            shared,
        )
        compose.setContent {
            MaterialTheme {
                HomeScreen(
                    pairing = pairing,
                    nav = HomeNavigation({}, {}, {}, onOpenPlaylists = { openedList = true }),
                    shared = shared, vm = homeVm,
                )
            }
        }
    }

    private fun waitForText(text: String, substring: Boolean = false) = compose.waitUntil(timeoutMillis = UI_WAIT_MS) {
        compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun row() = PlaylistToSync(7, "Road Trip", 10, 8, 3)

    @Test
    fun showsWhatIsLeftForTheHeadersDeviceAtTheDefaultCount() {
        api.playlistsToSync += row()
        setScreen()
        waitForText("Road Trip")
        waitForText("3 to sync to Pixel")
        assertEquals(1L to WidgetIds.PLAYLISTS_DEFAULT, api.playlistsAsked.last())
    }

    @Test
    fun nothingLeftSaysSo() {
        setScreen()
        waitForText("Nothing to sync to Pixel from your playlists.")
    }

    @Test
    fun aDoubleTapStagesThePlaylistAndASingleTapOpensIt() {
        api.playlistsToSync += row()
        setScreen()
        waitForText("Road Trip")
        compose.onNodeWithTag("widget_playlist_7").performTouchInput { doubleClick() }
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { app.staged.size == 1 }
        assertEquals(Triple("playlist", "7", listOf(1L)), app.staged.single())
        assertEquals(null, shared.state.value.playlistTarget)
        compose.onNodeWithTag("widget_playlist_7").performTouchInput { click() }
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { shared.state.value.playlistTarget == 7L }
        assertTrue(openedList)
    }

    @Test
    fun aDifferentDeviceInTheHeaderAsksAgainForThatDevice() {
        api.playlistsToSync += row()
        setScreen()
        waitForText("Road Trip")
        compose.runOnIdle { shared.chooseDevice(2) }
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.playlistsAsked.any { it.first == 2L } }
    }

    @Test
    fun aReloadDoesNotLoseThePlaylistAskedFor() {
        val pairing = Prefs.Pairing(serverUrl = server.url("/").toString(), token = "test-token")
        val state = testAppState(context, pairing)
        state.openPlaylist(7)
        runBlocking { state.reload() }
        assertEquals(7L, state.state.value.playlistTarget)
    }

    @Test
    fun theRowCountIsChangedInHomeWidgetsAndAskedForAgain() {
        api.playlistsLimit = 3
        api.playlistsToSync += row()
        setScreen()
        waitForText("Road Trip")
        compose.runOnIdle { homeVm.setPlaylistsLimit(4) }
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.playlistsAsked.any { it.second == 4 } }
        assertEquals(4, api.playlistsLimit)
        assertEquals(4, JSONObject(api.patches.last().toString()).getJSONObject("settings").getJSONObject("playlists").getInt("limit"))
    }

    @Test
    fun theSteppersStopAtTheServersRange() {
        api.playlistsLimit = 1
        val pairing = Prefs.Pairing(serverUrl = server.url("/").toString(), token = "test-token")
        val state = testAppState(context, pairing)
        compose.setContent { MaterialTheme { WidgetPrefsScreen(pairing = pairing, shared = state, onBack = {}) } }
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) {
            compose.onAllNodes(hasTestTag("playlists_limit")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Show fewer playlists").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Show more playlists").performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.playlistsLimit == 2 }
    }

    @Test
    fun anOlderServerHasNoSuchWidget() {
        api.catalog = api.catalog.filter { it.first != "playlists" }
        setScreen()
        compose.waitForIdle()
        compose.onAllNodes(hasTestTag("widget_playlists_nothing")).assertCountEquals(0)
    }
}
