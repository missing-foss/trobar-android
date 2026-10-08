// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
// The navigation shell: Home, Library and Settings as tabs, Home as the
// start and the place system back returns to, the basket pushed over a tab
// with the tabs hidden, the sync's detail at the top of Settings, and the
// server probe's outcome said on every tab, with the repair in Settings.
package org.missing_foss.trobar

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
class AppShellTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var server: MockWebServer
    private lateinit var appApi: FakeDashboardApi
    private lateinit var library: FakeAppApi
    private lateinit var playlists: FakePlaylistApi
    @Volatile private var deviceInfoCode = 200
    @Volatile private var appApiLevel = 1

    @Before
    fun startServer() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            Prefs.clearAppApi(context)
            Prefs.clearHeaderDevice(context)
        }
        server = MockWebServer()
        // The shell probes /api/device/info at start and whenever Settings
        // opens; answer every time rather than once. The code is settable,
        // so the connection tests can have the server refuse this phone.
        // Everything else is the App API, which the Home and Library tabs
        // now read: the dashboard fake answers its routes and hands the
        // rest to the staging-loop fake.
        library = FakeAppApi()
        appApi = FakeDashboardApi(library)
        playlists = FakePlaylistApi(library)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path == "/api/device/info" && deviceInfoCode != 200) {
                    MockResponse().setResponseCode(deviceInfoCode).setBody("""{"error": "invalid token"}""")
                } else if (request.path == "/api/device/info") {
                    MockResponse().setResponseCode(200).setBody(
                        """{"name": "Test Phone", "max_size_bytes": null, "device_type": "phone", "source_of_truth": "server", "app_api": $appApiLevel}""",
                    )
                } else if ((request.path ?: "").startsWith("/api/app/playlists")) {
                    playlists.dispatch(request)
                } else {
                    appApi.dispatch(request)
                }
        }
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    private fun setShell(serverUrl: String = server.url("/").toString(), widthDp: Int? = null, fontScale: Float? = null, sharedLink: String? = null) {
        val pairing = Prefs.Pairing(serverUrl = serverUrl, token = "test-token")
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale ?: density.fontScale)) {
                MaterialTheme {
                    Box(if (widthDp != null) Modifier.requiredWidth(widthDp.dp) else Modifier) {
                        AppShell(
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


    private fun pressBack() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    // Tabs are icons alone; each is found by its name, as a screen reader reads it.
    private fun tab(name: String) = compose.onNode(hasContentDescription(name) and isSelectable())

    @Test
    fun theFourTabsFitTheNarrowestPhoneAtTheLargestFontScale() {
        setShell(widthDp = NARROWEST_PHONE_DP, fontScale = LARGEST_FONT_SCALE)
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { compose.onAllNodes(hasContentDescription("Home") and isSelectable()).fetchSemanticsNodes().isNotEmpty() }
        val names = listOf("Home", "Library", "Playlists", "Settings")
        val items = names.map { tab(it).assertIsDisplayed().getUnclippedBoundsInRoot() }
        // Side by side, in order, none over another.
        items.zipWithNext().forEach { (a, b) -> assertTrue("$a then $b", a.right <= b.left) }
        // No labels: the words that wrapped mid-word at this width are gone
        // from the bar, whatever the text size.
        names.forEach { compose.onAllNodes(hasText(it) and hasAnyAncestor(isSelectable())).assertCountEquals(0) }
    }

    @Test
    fun playlistsAskForTheLevelThatBroughtThemAndOpenOnAServerThatHasIt() {
        setShell() // the server reports level 1: no playlist routes
        compose.waitForText("1 hour of music")
        tab("Playlists").performClick()
        compose.waitForText("Update the server to use this")
        compose.onNodeWithText("level 2", substring = true).assertIsDisplayed()
        // Home and Library need only level 1: still open.
        tab("Library").performClick()
        compose.waitForText("Search artists and albums")
    }

    @Test
    fun aLinkSharedFromAnotherAppOpensTheSubscriptionsWithItInTheField() {
        appApiLevel = 2
        setShell(sharedLink = "https://music.youtube.com/playlist?list=PLshared")
        compose.waitForText("Playlist subscriptions")
        compose.onNodeWithTag("subscription_url").assertTextContains("https://music.youtube.com/playlist?list=PLshared")
        // Pushed over the tabs, and back returns to them.
        compose.onAllNodes(hasContentDescription("Home") and isSelectable()).assertCountEquals(0)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { compose.onAllNodes(hasContentDescription("Home") and isSelectable()).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun thePlaylistsTabOpensTheSubscriptions() {
        appApiLevel = 2
        setShell()
        compose.waitForText("1 hour of music")
        tab("Playlists").performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) {
            compose.onAllNodes(hasContentDescription("Playlist subscriptions")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Playlist subscriptions").performClick()
        compose.waitForText("Add a public playlist by link")
    }

    @Test
    fun playlistsOpenOnALevelTwoServer() {
        appApiLevel = 2
        setShell()
        compose.waitForText("1 hour of music")
        tab("Playlists").performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) {
            compose.onAllNodes(hasTestTag("playlists_top_bar")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText("Update the server to use this").assertCountEquals(0)
    }

    @Test
    fun opensOnHomeWithHomeLibraryPlaylistsAndSettings() {
        setShell()
        compose.waitForText("1 hour of music") // the dashboard: its library widget has rendered from the fake's stats
        tab("Home").assertIsSelected()
        tab("Library").assertIsDisplayed()
        tab("Playlists").assertIsDisplayed()
        tab("Settings").assertIsDisplayed()
        compose.onAllNodes(hasText("Sync") and isSelectable()).assertCountEquals(0)
        // The start-up probe records the server's App API level, which the
        // gate in front of Home and Library reads.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { runBlocking { Prefs.appApi(context).first() } == 1 }
    }

    @Test
    fun aNewTabShowsItsScreenAndBackReturnsToHome() {
        setShell()
        compose.waitForText("1 hour of music")
        tab("Library").performClick()
        compose.waitForText("Search artists and albums") // the library, empty: its search field is the first thing there
        tab("Settings").performClick()
        compose.waitForText("Sync now")

        // Two tabs visited, one back press: Home underneath, not Library.
        pressBack()
        compose.waitForText("1 hour of music")
        tab("Home").assertIsSelected()
    }

    /**
     * A swipe across the screen, away from the edges, where gesture
     * navigation's back swipe starts: through the middle of the tab's content,
     * or with [onTopBar] through the top bar. Home's charts take a horizontal
     * drag themselves, so on Home the middle can land on one, depending on
     * how far Home has loaded; its swipes go through the top bar instead.
     */
    private fun swipeTabs(towardsTheStart: Boolean, onTopBar: Boolean = false) {
        compose.onRoot().performTouchInput {
            val from = width * EDGE_CLEAR
            val to = width * (1 - EDGE_CLEAR)
            val y = if (onTopBar) TOP_BAR_MIDDLE.toPx() else centerY
            if (towardsTheStart) swipe(Offset(to, y), Offset(from, y)) else swipe(Offset(from, y), Offset(to, y))
        }
        compose.waitForIdle()
    }

    /** Waits for [name] to be the selected tab; on a timeout, says which one is. */
    private fun awaitSelected(name: String) {
        val ok = runCatching {
            compose.waitUntil(timeoutMillis = UI_WAIT_MS) { runCatching { tab(name).assertIsSelected() }.isSuccess }
        }.isSuccess
        if (!ok) {
            val showing = listOf("Home", "Library", "Playlists", "Settings").filter { runCatching { tab(it).assertIsSelected() }.isSuccess }
            throw AssertionError("expected $name selected; selected: $showing")
        }
    }

    @Test
    fun aSwipeMovesToTheNextOrPreviousTabInTheBarsOrderAndNotPastTheEnds() {
        setShell()
        compose.waitForText("1 hour of music")
        swipeTabs(towardsTheStart = false, onTopBar = true) // before the first tab: nothing
        tab("Home").assertIsSelected()
        swipeTabs(towardsTheStart = true, onTopBar = true)
        awaitSelected("Library")
        for (next in listOf("Playlists", "Settings")) {
            swipeTabs(towardsTheStart = true)
            awaitSelected(next)
        }
        swipeTabs(towardsTheStart = true) // past the last tab: nothing
        tab("Settings").assertIsSelected()
        swipeTabs(towardsTheStart = false)
        awaitSelected("Playlists")
        // Back still lands on Home, as after a tap.
        pressBack()
        awaitSelected("Home")
    }

    @Test
    fun aSwipeOnTheSimilarArtistsStripScrollsItAndStaysOnLibrary() {
        library.artist("Aphelion", Triple("Parallax", 2022, 9))
        library.similar["Aphelion"] = listOf(
            "Nebula Drift", "Cinder & Ash", "The White Stripes", "Suicidal Tendencies", "Boards of Canada", "Mogwai", "Sigur Rós",
        )
        setShell()
        compose.waitForText("1 hour of music")
        tab("Library").performClick()
        compose.waitForText("Aphelion")
        compose.onNodeWithText("Aphelion").performClick()
        compose.waitForText("Parallax")
        compose.onNodeWithTag("album_list").performScrollToNode(hasTestTag("similar_artists"))
        val strip = compose.onNodeWithTag("similar_artists")
        val firstBefore = compose.onNode(hasText("Nebula Drift") and hasAnyAncestor(hasTestTag("similar_artists"))).getUnclippedBoundsInRoot().left
        strip.performTouchInput { swipeLeft() }
        compose.waitForIdle()
        tab("Library").assertIsSelected() // the strip had the swipe, not the tabs
        val firstAfter = compose.onAllNodes(hasText("Nebula Drift") and hasAnyAncestor(hasTestTag("similar_artists")))
            .fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.left
        assertTrue("the strip scrolled: first entry from $firstBefore to $firstAfter", firstAfter == null || firstAfter < firstBefore.value)
    }

    @Test
    fun aTabKeepsItsStateThroughASwipeAwayAndBack() {
        library.artist("Aphelion", Triple("Parallax", 2022, 9))
        library.artist("Borealis", Triple("Dawn", 2020, 5))
        setShell()
        compose.waitForText("1 hour of music")
        tab("Library").performClick()
        compose.waitForText("Borealis")
        // A search typed in: that is the Library tab's state.
        compose.onNode(hasSetTextAction()).performTextInput("Aph")
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { compose.onAllNodesWithText("Borealis").fetchSemanticsNodes().isEmpty() }
        swipeTabs(towardsTheStart = true)
        awaitSelected("Playlists")
        swipeTabs(towardsTheStart = false)
        awaitSelected("Library")
        compose.onNode(hasSetTextAction()).assertTextContains("Aph") // still searched, the list still filtered
        compose.onAllNodesWithText("Borealis").assertCountEquals(0)
    }

    @Test
    fun anOpenedArtistKeepsItsPageUnderASwipe() {
        library.artist("Aphelion", Triple("Parallax", 2022, 9))
        setShell()
        compose.waitForText("1 hour of music")
        tab("Library").performClick()
        compose.waitForText("Aphelion")
        compose.onNodeWithText("Aphelion").performClick()
        compose.waitForText("Parallax")
        // A drill-down page keeps its behaviour, as a pushed screen does:
        // no tab change from here. One swipe, then look: a swipe there and
        // back would land on Library either way, the artist still open.
        swipeTabs(towardsTheStart = true)
        tab("Library").assertIsSelected()
        tab("Playlists").assertIsNotSelected()
        compose.waitForText("Parallax")
    }

    @Test
    fun aDrillDownPageKeepsItsPageAfterComingBackFromAnotherTabsOpenPage() {
        // Both tabs with a page open: coming back to Library, the Playlists
        // tab is disposed after Library is composed, and its clean-up must
        // not clear Library's page. Found in review.
        appApiLevel = 2
        library.artist("Aphelion", Triple("Parallax", 2022, 9))
        playlists.playlists += FakePlaylistApi.Playlist(1, "Road Trip", trackCount = 2, matchedCount = 1)
        setShell()
        compose.waitForText("1 hour of music")
        tab("Library").performClick()
        compose.waitForText("Aphelion")
        compose.onNodeWithText("Aphelion").performClick()
        compose.waitForText("Parallax")
        tab("Playlists").performClick()
        compose.waitForText("Road Trip")
        compose.onNodeWithText("Road Trip").performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { compose.onAllNodes(hasTestTag("playlist_entries")).fetchSemanticsNodes().isNotEmpty() }
        tab("Library").performClick()
        compose.waitForText("Parallax")
        compose.mainClock.advanceTimeBy(TRANSITION_SETTLE_MS) // the outgoing tab's exit transition, and its disposal
        compose.waitForIdle()
        swipeTabs(towardsTheStart = true)
        tab("Library").assertIsSelected()
        compose.waitForText("Parallax")
    }

    @Test
    fun anOpenedPlaylistKeepsItsPageUnderASwipe() {
        appApiLevel = 2
        playlists.playlists += FakePlaylistApi.Playlist(1, "Road Trip", trackCount = 2, matchedCount = 1)
        setShell()
        compose.waitForText("1 hour of music")
        tab("Playlists").performClick()
        compose.waitForText("Road Trip")
        compose.onNodeWithText("Road Trip").performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { compose.onAllNodes(hasTestTag("playlist_entries")).fetchSemanticsNodes().isNotEmpty() }
        swipeTabs(towardsTheStart = true) // one swipe, then look, as above
        tab("Playlists").assertIsSelected()
        tab("Settings").assertIsNotSelected()
        compose.onAllNodes(hasTestTag("playlist_entries")).assertCountEquals(1)
    }

    @Test
    fun theBasketActionPushesOverATabAndHidesTheTabs() {
        setShell()
        compose.waitForText("1 hour of music")
        compose.onNodeWithContentDescription("Basket").performClick()
        compose.waitForText("Basket")
        compose.onAllNodes(hasContentDescription("Library") and isSelectable()).assertCountEquals(0)

        pressBack()
        tab("Library").assertIsDisplayed()
    }

    @Test
    fun settingsIsATabWithTheSyncAtTheTopAndNoWayBack() {
        setShell()
        compose.waitForText("1 hour of music")
        tab("Settings").performClick()
        compose.waitForText("Sync now")
        compose.onNodeWithTag("sync_status_card").assertIsDisplayed()
        compose.onNodeWithText("Last sync", substring = true).assertIsDisplayed()
        tab("Library").assertIsDisplayed() // still a tab: the bar stays
        compose.onAllNodesWithContentDescription("Back").assertCountEquals(0)
    }

    @Test
    fun aRejectedPairingIsSaidOnEveryTabAndSettingsOffersReEnroll() {
        deviceInfoCode = 401
        setShell()
        compose.waitForText("The server no longer accepts this phone", substring = true)
        compose.onNodeWithTag("sync_indicator").performClick() // leads to the detail
        compose.waitForText("Device not recognized")
        compose.onNodeWithText("Re-enroll").assertIsDisplayed()
    }

    @Test
    fun anUnreachableServerIsSaidOnEveryTabAndSettingsOffersRetry() {
        server.shutdown() // the probe's request is refused, not answered
        setShell()
        compose.waitForText("The server cannot be reached", substring = true)
        tab("Settings").performClick()
        compose.waitForText("Can't reach the server")
        compose.onNodeWithText("Retry").assertIsDisplayed()
    }

    @Test
    fun aStoredAddressThatIsNotAUrlShowsTheRetryScreenNotACrash() {
        setShell(serverUrl = "myserver:8080")
        compose.waitForText("The server cannot be reached", substring = true)
        tab("Settings").performClick()
        compose.waitForText("Can't reach the server")
        compose.onNodeWithText("Retry").assertIsDisplayed()
    }

    private fun hasTestTagStartingWith(prefix: String) = SemanticsMatcher("test tag starts with $prefix") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

    private fun moveDownButton() = SemanticsMatcher("a Move … down button") { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.startsWith("Move ") && it.endsWith(" down") }
    }

    private fun basketBadge(count: Int) = hasContentDescription("Basket") and hasText(count.toString())

    private fun widgetTops(): List<Pair<String, Float>> =
        compose.onAllNodes(hasTestTagStartingWith("widget-"))
            .fetchSemanticsNodes()
            .mapNotNull { n -> n.config.getOrNull(SemanticsProperties.TestTag)?.takeIf { !it.endsWith("-content") }?.let { it to n.boundsInRoot.top } }
            .sortedBy { it.second }

    @Test
    fun aStageOnHomeShowsOnTheBadgeAtOnceAndOnTheOtherTabWithoutAnotherRead() {
        appApi.recentlyReleased += Triple("A", "One", "recent")
        // Only the library widget and the grid, so the grid is on screen, not below the fold.
        appApi.disabled = mutableListOf("devices", "suggestions", "recently_added", "most_played")
        setShell()
        compose.waitForText("One")
        compose.onNode(hasText("One") and hasAnyAncestor(hasTestTag("grid-recently_released"))).performTouchInput { doubleClick() }
        // No navigation: the badge on the tab that staged.
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { compose.onAllNodes(basketBadge(1)).fetchSemanticsNodes().isNotEmpty() }
        // The other tab shows the same count from the same holder, with no read of its own.
        val basketReads = appApi.hits("/api/app/basket")
        tab("Library").performClick()
        compose.waitForText("Search artists and albums")
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { compose.onAllNodes(basketBadge(1)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("a tab switch reads no basket", basketReads, appApi.hits("/api/app/basket"))
    }

    @Test
    fun aRemovalInTheBasketShowsOnTheBadgeOnReturn() {
        appApi.recentlyReleased += Triple("A", "One", "recent")
        // Only the library widget and the grid, so the grid is on screen, not below the fold.
        appApi.disabled = mutableListOf("devices", "suggestions", "recently_added", "most_played")
        setShell()
        compose.waitForText("One")
        compose.onNode(hasText("One") and hasAnyAncestor(hasTestTag("grid-recently_released"))).performTouchInput { doubleClick() }
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { compose.onAllNodes(basketBadge(1)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Basket").performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) {
            compose.onAllNodesWithContentDescription("Actions for One").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Actions for One").performClick()
        compose.onNodeWithText("Remove from basket").performClick()
        compose.waitForText("Nothing staged", substring = true)
        pressBack()
        compose.waitForText("1 hour of music")
        compose.onAllNodes(basketBadge(1)).assertCountEquals(0)
    }

    @Test
    fun anOrderChangedInThePreferencesIsHomesOrderOnReturn() {
        // The reply to the order's PATCH comes late, so Home is back on
        // screen with the old order when the new one lands (#67). Without
        // the wait this was a race, and it failed whenever the reply lost.
        appApi.patchReplyDelayMs = PATCH_REPLY_DELAY_MS
        setShell()
        compose.waitForText("1 hour of music")
        val before = widgetTops().map { it.first }
        assertTrue("the control: at least two widgets to reorder, got $before", before.size >= 2)
        compose.onNodeWithContentDescription("Home widgets").performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) {
            compose.onAllNodes(moveDownButton()).fetchSemanticsNodes().isNotEmpty()
        }
        val patches = appApi.patches.size
        compose.onAllNodes(moveDownButton())[0].performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { appApi.patches.size == patches + 1 }
        pressBack()
        compose.waitForText("1 hour of music")
        // No restart, no refresh: Home draws the order the preferences wrote.
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { widgetTops().map { it.first }.take(2) == listOf(before[1], before[0]) }
    }

    private fun selector(device: String) = compose.onNodeWithContentDescription("Sync to $device")

    private fun waitForSelector(device: String) = compose.waitUntil(timeoutMillis = UI_WAIT_MS) {
        compose.onAllNodesWithContentDescription("Sync to $device").fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun theHeaderIsTheWordmarkNotTheScreensName() {
        setShell()
        compose.waitForText("1 hour of music")
        compose.onNodeWithText("Trobar").assertIsDisplayed()
        compose.onAllNodesWithText("Home").assertCountEquals(0) // no title -- and the tabs are icons, with no labels
        tab("Library").performClick()
        compose.waitForText("Search artists and albums")
        compose.onNodeWithText("Trobar").assertIsDisplayed()
        compose.onAllNodesWithText("Library").assertCountEquals(0)
    }

    @Test
    fun theSelectorStartsOnThisPhoneAndAChoiceHoldsOnBothTabsAndIsKept() {
        setShell()
        waitForSelector("Pixel") // this phone: the fake's whoami device
        selector("Pixel").performClick()
        // Every device, the current one marked; no name in the bar itself.
        compose.onNode(hasText("Car") and hasClickAction()).assertIsDisplayed()
        compose.onNodeWithContentDescription("Current destination").assertIsDisplayed()
        compose.onNode(hasText("Car") and hasClickAction()).performClick()
        waitForSelector("Car")
        tab("Library").performClick()
        compose.waitForText("Search artists and albums")
        waitForSelector("Car") // the same holder, the same choice
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(2L, runBlocking { Prefs.headerDevice(context).first() })
    }

    @Test
    fun aChoiceKeptFromBeforeIsTheNextStartsDestination() {
        runBlocking { Prefs.setHeaderDevice(InstrumentationRegistry.getInstrumentation().targetContext, 2L) }
        setShell()
        waitForSelector("Car")
    }

    @Test
    fun aKeptChoiceThatIsNoLongerADeviceFallsBackToThisPhone() {
        runBlocking { Prefs.setHeaderDevice(InstrumentationRegistry.getInstrumentation().targetContext, 999L) }
        setShell()
        waitForSelector("Pixel")
    }

    private companion object {
        const val PATCH_REPLY_DELAY_MS = 1_500L
        const val NARROWEST_PHONE_DP = 320 // the smallest width Android's compatibility definition allows a phone
        const val LARGEST_FONT_SCALE = 2f
        const val EDGE_CLEAR = 0.2f // the share of the width a test swipe keeps off each edge
        val TOP_BAR_MIDDLE = 32.dp // half a 64 dp top app bar
        const val TRANSITION_SETTLE_MS = 2_000L // well past the NavHost's tab transition
    }
}
