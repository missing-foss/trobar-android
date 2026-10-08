// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
// The three discovery grids on the real host: deduplicated against each
// other in precedence order, a hidden widget giving its albums back to
// the ones below, a months change refetching only its own widget, the
// suggestions hint from the server's signal, a double-tap staging an
// album for the header's device with its cover flying (a single tap doing
// nothing), a full grid showing its last row whole at a phone width,
// a large font scale and a tablet width, and the months stepper in the
// card's title row leaving "View library" a line of its own.

package org.missing_foss.trobar

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import java.util.Locale
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiscoveryWidgetsTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: MockWebServer
    private lateinit var api: FakeDashboardApi
    private lateinit var basket: FakeAppApi
    private lateinit var vm: HomeViewModel

    @Before
    fun startServer() {
        runBlocking { Prefs.clearHeaderDevice(context) }
        basket = FakeAppApi()
        api = FakeDashboardApi(basket)
        api.disabled = mutableListOf("library", "devices", "most_played")
        api.recentlyReleased += Triple("A", "One", "recent"); api.recentlyReleased += Triple("B", "Two", "recent")
        api.recentlyAdded += Triple("B", "Two", "recent"); api.recentlyAdded += Triple("C", "Three", "recent")
        api.suggestions += Triple("C", "Three", "recent"); api.suggestions += Triple("D", "Four", "lastfm")
        server = MockWebServer()
        server.dispatcher = api
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    private fun setScreen(fontScale: Float? = null, tablet: Boolean = false, widthDp: Int? = null, french: Boolean = false) {
        val pairing = Prefs.Pairing(serverUrl = server.url("/").toString(), token = "test-token")
        val shared = testAppState(context, pairing)
        vm = HomeViewModel(
            AppApiDashboardClient(context, pairing.serverUrl, pairing.token),
            AppApiClient(context, pairing.serverUrl, pairing.token),
            WidgetRegistry.all,
            shared,
        )
        compose.setContent {
            val density = LocalDensity.current
            val base = LocalContext.current
            val config = Configuration(base.resources.configuration).apply { if (french) setLocale(Locale.FRENCH) }
            CompositionLocalProvider(
                LocalContext provides base.createConfigurationContext(config),
                LocalConfiguration provides config,
                LocalDensity provides Density(density.density, fontScale ?: density.fontScale),
                LocalWindowWidthSizeClass provides if (tablet) WindowWidthSizeClass.Expanded else WindowWidthSizeClass.Compact,
            ) {
                MaterialTheme {
                    // A tablet's window, wider than this device's screen; Home caps its content as it does there.
                    val width = widthDp ?: if (tablet) TABLET_WIDTH_DP else null
                    Box(if (width != null) Modifier.requiredWidth(width.dp) else Modifier) {
                        HomeScreen(pairing = pairing, nav = HomeNavigation({}, {}, {}), shared = shared, vm = vm)
                    }
                }
            }
        }
    }

    private fun inGrid(id: String, album: String) = compose.onAllNodes(hasText(album) and hasAnyAncestor(hasTestTag("grid-$id")))

    @Test
    fun theThreeGridsDeduplicateInPrecedenceOrder() {
        setScreen()
        compose.waitForText("Four")
        inGrid("recently_released", "One").assertCountEquals(1)
        inGrid("recently_released", "Two").assertCountEquals(1)
        inGrid("recently_added", "Two").assertCountEquals(0) // released has it
        inGrid("recently_added", "Three").assertCountEquals(1)
        inGrid("suggestions", "Three").assertCountEquals(0) // added has it
        inGrid("suggestions", "Four").assertCountEquals(1)
    }

    @Test
    fun hidingAWidgetGivesItsAlbumsBackToTheOneBelow() {
        setScreen()
        compose.waitForText("Four")
        vm.remove("recently_released")
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { inGrid("recently_added", "Two").fetchSemanticsNodes().size == 1 }
        inGrid("recently_released", "One").assertCountEquals(0)
        inGrid("suggestions", "Three").assertCountEquals(0) // still in added
    }

    @Test
    fun aMonthsChangeRefetchesOnlyThatWidget() {
        setScreen()
        compose.waitForText("Four")
        val addedBefore = api.hits("/api/app/library/recently-added")
        val releasedBefore = api.hits("/api/app/library/recently-released")
        val suggestionsBefore = api.hits("/api/app/suggestions")
        // The "+" of Recently added: the widgets render in catalog order, suggestions (no stepper), added, released.
        compose.onAllNodesWithText("+")[0].performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.hits("/api/app/library/recently-added") == addedBefore + 1 }
        assertEquals(listOf(3, 4), api.monthsAsked["recently_added"])
        assertEquals(releasedBefore, api.hits("/api/app/library/recently-released"))
        assertEquals(suggestionsBefore, api.hits("/api/app/suggestions"))
        assertEquals(4, api.patches.last().getJSONObject("settings").getJSONObject("recently_added").getInt("months"))
    }

    @Test
    fun withoutAListeningHistoryTheSuggestionsWidgetIsLeftOutAndNothingIsSaved() {
        api.suggestionsConfigured = false
        setScreen()
        compose.waitForText("One") // the recently-released grid, loaded alongside
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.hits("/api/app/suggestions") >= 1 }
        compose.waitForIdle()
        compose.onAllNodesWithTag("widget-suggestions").assertCountEquals(0)
        assertTrue("left out on the phone only: nothing written to the shared preferences", api.patches.isEmpty())
    }

    @Test
    fun aSuggestionsFeedThatFailsIsShownWithItsErrorNotLeftOut() {
        // An outage is not "not set up": leaving the widget out would hide
        // the error and look exactly like having no history.
        api.suggestionsFail = true
        setScreen()
        compose.waitForText("One")
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { compose.onAllNodesWithTag("widget-suggestions").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Last.fm did not answer", substring = true) and hasAnyAncestor(hasTestTag("widget-suggestions"))).assertExists()
    }

    @Test
    fun whileItsFeedLoadsTheSuggestionsWidgetIsNotShownThenAppears() {
        // Never shown and then taken away: not drawn until its feed is in.
        val hold = CountDownLatch(1)
        api.holdSuggestions = hold
        setScreen()
        compose.waitForText("One")
        compose.onAllNodesWithTag("widget-suggestions").assertCountEquals(0)
        hold.countDown()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { compose.onAllNodesWithTag("widget-suggestions").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aDoubleTapStagesTheAlbumForTheHeadersDeviceAndItsCoverFlies() {
        setScreen()
        compose.waitForText("Four")
        compose.onNodeWithContentDescription("Sync to Pixel").performClick()
        compose.onNodeWithText("Car").performClick()
        compose.mainClock.autoAdvance = false
        inGrid("recently_released", "One")[0].performTouchInput { doubleClick() }
        compose.mainClock.advanceTimeBy(PART_OF_A_FLIGHT_MS)
        compose.onAllNodes(hasTestTag("basket_flight"), useUnmergedTree = true).assertCountEquals(1)
        compose.mainClock.autoAdvance = true
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { basket.staged.size == 1 }
        assertEquals(Triple("album", "A||One", listOf(2L)), basket.staged.single())
        // The shared basket has it: every tab's badge reads from there.
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { vm.state.value.basket.size == 1 }
        inGrid("recently_released", "One").assertCountEquals(1) // the album stays where it was
    }

    @Test
    fun aSingleTapOnACoverDoesNothing() {
        setScreen()
        compose.waitForText("Four")
        inGrid("recently_released", "One")[0].performTouchInput { click() }
        // The double-tap window runs out on the test's clock -- only then is a
        // tap a single tap -- then a round trip to the fake in real time.
        compose.mainClock.advanceTimeBy(PAST_A_DOUBLE_TAP_MS)
        Thread.sleep(SETTLE_MS)
        compose.waitForIdle()
        assertTrue("a single tap wrote ${basket.staged}", basket.staged.isEmpty())
        compose.onAllNodes(hasTestTag("basket_flight"), useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
        compose.onAllNodesWithText("to the basket", substring = true).assertCountEquals(0)
    }

    @Test
    fun aFailedStageSaysSoAndTheBasketStaysWhatTheServerHolds() {
        basket.failStagingAfter = 0
        setScreen()
        compose.waitForText("Four")
        inGrid("recently_released", "One")[0].performTouchInput { doubleClick() }
        compose.waitForText("Storage failed", substring = true)
        assertTrue(basket.items.isEmpty())
        assertTrue(vm.state.value.basket.isEmpty())
    }

    @Test
    fun aFullGridShowsItsLastRowWhole() = lastRowFitsInTheGrid()

    @Test
    fun aFullGridShowsItsLastRowWholeAtTheLargestFontScale() = lastRowFitsInTheGrid(fontScale = LARGEST_FONT_SCALE)

    @Test
    fun aFullGridShowsItsLastRowWholeAtATabletWidth() = lastRowFitsInTheGrid(tablet = true)

    private fun frenchContext() =
        context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.FRENCH) })

    @Test
    fun onTheNarrowestPhoneInFrenchViewLibraryKeepsOneLineAndTheStepperIsInTheTitleRow() =
        titleRowAndViewLibrary(french = true, atDefaultScale = true)

    @Test
    fun onTheNarrowestPhoneInEnglishViewLibraryKeepsOneLineAndTheStepperIsInTheTitleRow() =
        titleRowAndViewLibrary(french = false, atDefaultScale = true)

    // At 2.0x on 320 dp nothing promises one line; nothing may clip or break mid-word.
    @Test
    fun onTheNarrowestPhoneInFrenchAtTheLargestFontScaleNothingClipsOrBreaksMidWord() =
        titleRowAndViewLibrary(french = true, atDefaultScale = false, fontScale = LARGEST_FONT_SCALE)

    /** The three grids at 320 dp. "View library" is on a line of its own
     * below the stepper. Each recently-* card has its stepper inside its
     * title row, both buttons whole, and its title breaks between words
     * only. [atDefaultScale]: "View library" on one line and the stepper
     * beside the title; at a larger scale the stepper may go under it. */
    private fun titleRowAndViewLibrary(french: Boolean, atDefaultScale: Boolean, fontScale: Float? = null) {
        setScreen(fontScale = fontScale, widthDp = NARROWEST_PHONE_DP, french = french)
        compose.waitForText("Four")
        val res = if (french) frenchContext() else context
        val viewLibrary = res.getString(R.string.widget_view_library)
        for (id in listOf("suggestions", "recently_added", "recently_released")) {
            val button = compose.onNode(hasText(viewLibrary) and hasAnyAncestor(hasTestTag("widget-$id")), useUnmergedTree = true)
            val lines = layoutOf(button).lineCount
            if (atDefaultScale) assertEquals("lines of \"$viewLibrary\" in $id", 1, lines)
            assertBreaksBetweenWords(viewLibrary, layoutOf(button))
        }
        for (id in listOf("recently_added", "recently_released")) {
            // The stepper is above "View library", never on its row.
            val plus = compose.onNode(hasText("+") and hasAnyAncestor(hasTestTag("widget-$id"))).getUnclippedBoundsInRoot()
            val button = compose.onNode(hasText(viewLibrary) and hasAnyAncestor(hasTestTag("widget-$id")), useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
            assertTrue("in $id, \"+\" at $plus and \"$viewLibrary\" at $button", plus.bottom <= button.top)
            val title = res.getString(WidgetIds.label(id)!!)
            val titleNode = compose.onNode(hasText(title) and hasAnyAncestor(hasTestTag("widget-$id")), useUnmergedTree = true)
            if (atDefaultScale) {
                val t = titleNode.getUnclippedBoundsInRoot()
                assertTrue("in $id, \"+\" at $plus is not beside the title at $t", plus.left >= t.right && plus.top < t.bottom)
            }
            val row = compose.onNode(hasTestTag("widget-title-$id")).getUnclippedBoundsInRoot()
            for (sign in listOf("−", "+")) {
                val nodes = compose.onAllNodes(hasText(sign) and hasAnyAncestor(hasTestTag("widget-title-$id")))
                assertEquals("\"$sign\" in the title row of $id", 1, nodes.fetchSemanticsNodes().size)
                val b = nodes[0].getUnclippedBoundsInRoot()
                assertTrue("\"$sign\" of $id at $b, row $row", b.left >= row.left && b.right <= row.right)
                assertTrue("\"$sign\" of $id is ${b.width} wide", b.width.value >= MIN_BUTTON_DP)
            }
            assertBreaksBetweenWords(title, layoutOf(titleNode))
        }
    }

    private fun layoutOf(node: androidx.compose.ui.test.SemanticsNodeInteraction): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        return layouts.single()
    }

    /** Every line of [text] but the last ends on a space or just before one. */
    private fun assertBreaksBetweenWords(text: String, layout: TextLayoutResult) {
        for (line in 0 until layout.lineCount - 1) {
            val end = layout.getLineEnd(line)
            assertTrue(
                "\"$text\" breaks mid-word after \"${text.substring(0, end)}\"",
                text[end - 1] == ' ' || text.getOrNull(end) == ' ',
            )
        }
    }

    /** Eleven albums, four rows, the last one short: the last card's artist
     * line (the bottom of the card) must end inside the grid, and the last
     * card keeps the first one's width and height. Displayed alone would not
     * do: a row clipped part-way still has a visible part. */
    @Test
    fun theNarrowestPhoneShowsThreeCoversARow() {
        val (perRow, _) = coversInTheFirstRow(widthDp = NARROWEST_PHONE_DP, tablet = false)
        assertEquals(3, perRow)
    }

    @Test
    fun aTabletShowsMoreCoversARowAtAboutAPhonesCoverSize() {
        // A landscape tablet's window: three a row made each cover a third of it.
        val (perRow, cover) = coversInTheFirstRow(widthDp = LANDSCAPE_TABLET_DP, tablet = true)
        assertTrue("$perRow covers a row", perRow >= MIN_TABLET_COVERS_A_ROW)
        assertTrue("each cover $cover dp wide", cover in PHONE_COVER_DP)
    }

    /** How many of [GRID_ALBUMS] covers share the first row, and how wide one is. */
    private fun coversInTheFirstRow(widthDp: Int, tablet: Boolean): Pair<Int, Float> {
        api.disabled = mutableListOf("library", "devices", "most_played", "suggestions", "recently_added")
        api.recentlyReleased.clear()
        (1..GRID_ALBUMS).forEach { api.recentlyReleased += Triple("Artist $it", "Album $it", "recent") }
        setScreen(tablet = tablet, widthDp = widthDp)
        compose.waitForText("Album 1")
        val captions = (1..GRID_ALBUMS).map {
            compose.onNode(hasText("Album $it") and hasAnyAncestor(hasTestTag("grid-recently_released"))).getUnclippedBoundsInRoot()
        }
        val top = captions.first().top.value
        return captions.count { kotlin.math.abs(it.top.value - top) < SIZE_TOLERANCE_DP } to captions.first().width.value
    }

    private fun lastRowFitsInTheGrid(fontScale: Float? = null, tablet: Boolean = false) {
        api.disabled = mutableListOf("library", "devices", "most_played", "suggestions", "recently_added")
        api.recentlyReleased.clear()
        (1..GRID_ALBUMS).forEach { api.recentlyReleased += Triple("Artist $it", "Album $it", "recent") }
        setScreen(fontScale, tablet)
        compose.waitForText("Album 1")
        val grid = compose.onNode(hasTestTag("grid-recently_released")).getUnclippedBoundsInRoot()
        val last = compose.onAllNodes(hasText("Artist $GRID_ALBUMS") and hasAnyAncestor(hasTestTag("grid-recently_released")))
        assertEquals("the last card is composed at all", 1, last.fetchSemanticsNodes().size)
        val bottom = last[0].getUnclippedBoundsInRoot().bottom
        assertTrue("last card ends at $bottom, grid at ${grid.bottom}", bottom <= grid.bottom)
        val first = compose.onNode(hasText("Album 1") and hasAnyAncestor(hasTestTag("grid-recently_released"))).getUnclippedBoundsInRoot()
        val lastAlbum = compose.onNode(hasText("Album $GRID_ALBUMS") and hasAnyAncestor(hasTestTag("grid-recently_released"))).getUnclippedBoundsInRoot()
        assertEquals("a short row's card is as wide as a full row's", first.width.value, lastAlbum.width.value, SIZE_TOLERANCE_DP)
        // A grid that fits by squeezing its last row is clipping it too, only inside the card.
        assertEquals("the last card is as tall as the first", first.height.value, lastAlbum.height.value, SIZE_TOLERANCE_DP)
    }

    private companion object {
        const val GRID_ALBUMS = 11
        const val LARGEST_FONT_SCALE = 2f
        const val TABLET_WIDTH_DP = 840
        const val NARROWEST_PHONE_DP = 320 // the smallest width Android's compatibility definition allows a phone
        const val MIN_BUTTON_DP = 40f
        const val PART_OF_A_FLIGHT_MS = 100L
        const val SETTLE_MS = 1_500L
        const val PAST_A_DOUBLE_TAP_MS = 1_000L
        const val SIZE_TOLERANCE_DP = 0.5f
        const val LANDSCAPE_TABLET_DP = 1280 // a Pixel Tablet's width in landscape
        const val MIN_TABLET_COVERS_A_ROW = 6
        val PHONE_COVER_DP = 120f..180f
    }
}
