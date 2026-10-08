// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import android.content.res.Configuration
import android.view.ViewConfiguration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
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

/** The preferences screen: the admin-only row absent for a non-admin, a
 * step in the order stored as the whole order, and the months stepper
 * stopping at the server's range. */
@RunWith(AndroidJUnit4::class)
class WidgetPrefsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: MockWebServer
    private lateinit var api: FakeDashboardApi

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

    private fun setScreen(
        widthDp: Int? = null,
        fontScale: Float? = null,
        french: Boolean = false,
        widgets: Map<String, Widget> = emptyMap(),
    ) {
        val pairing = Prefs.Pairing(serverUrl = server.url("/").toString(), token = "test-token")
        val shared = testAppState(context, pairing)
        val vm = HomeViewModel(
            AppApiDashboardClient(context, pairing.serverUrl, pairing.token), AppApiClient(context, pairing.serverUrl, pairing.token), widgets, shared,
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
                        WidgetPrefsScreen(pairing = pairing, shared = shared, onBack = {}, vm = vm)
                    }
                }
            }
        }
    }

    private fun waitForText(text: String) {
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun theWidgetNamesBreakOnlyBetweenWordsOnTheNarrowestPhone() {
        setScreen(widthDp = NARROWEST_PHONE_DP, fontScale = LARGEST_FONT_SCALE, french = true)
        val french = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocale(Locale.FRENCH) },
        ).resources
        waitForText(french.getString(R.string.widget_recently_released))
        // The arrows and the switch were measured first and left the name a
        // few characters: "Bibliothèq/ue", "Suggestio/ns".
        val names = PREFS_ORDER.map { french.getString(WidgetIds.label(it)!!) }
        val broken = names.filter { breaksMidWord(it) }
        assertEquals("names broken mid-word", emptyList<String>(), broken)
    }

    @Test
    fun atTheDefaultSizeTheControlsStayBesideTheName() {
        setScreen()
        waitForText("Recently released")
        val name = compose.onNodeWithText("Library", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val down = compose.onNodeWithContentDescription("Move Library down").getUnclippedBoundsInRoot()
        assertTrue("the arrow at $down is not beside the name at $name", down.top < name.bottom && down.left > name.right)
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

    @Test
    fun theScreenSaysTheSetIsThisDevicesOwnNotSharedWithTheBrowser() {
        setScreen()
        waitForText("Recently released")
        // The server keeps a set per paired device; the browser keeps its own.
        // Behind the section's info icon, not on the screen until asked for.
        compose.onAllNodesWithText("This device's own widgets, order and settings", substring = true).assertCountEquals(0)
        compose.onNodeWithContentDescription("About WIDGETS").performClick()
        compose.onNodeWithText("This device's own widgets, order and settings", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onAllNodesWithText("This device's own widgets, order and settings", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Changes apply to both", substring = true).assertCountEquals(0)
    }

    @Test
    fun theWidgetsAreOneSectionAndEachCoverWidgetCarriesItsOwnCount() {
        setScreen()
        waitForText("Recently released")
        compose.onNodeWithText("WIDGETS").assertIsDisplayed()
        for (name in listOf("Library", "Devices", "Suggestions", "Recently added", "Recently released", "Most played")) {
            compose.onNode(hasText(name) and hasAnyAncestor(hasTestTag("widget_prefs_widgets")), useUnmergedTree = true).assertExists()
        }
        // The chips on each cover widget's row, and no section shared by all three.
        for (id in listOf("suggestions", "recently_added", "recently_released")) {
            compose.onNodeWithTag("covers_$id", useUnmergedTree = true).assertExists()
        }
        for (id in listOf("library", "devices", "most_played")) {
            compose.onAllNodes(hasTestTag("covers_$id"), useUnmergedTree = true).assertCountEquals(0)
        }
        compose.onAllNodesWithText("COVERS PER WIDGET").assertCountEquals(0)
        compose.onAllNodesWithText("COVER GRIDS").assertCountEquals(0)
    }

    @Test
    fun aChipOnOneCoverWidgetsRowSetsThatWidgetsCountOnly() {
        setScreen()
        waitForText("Recently released")
        chip("recently_added", 45).performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 30_000) { api.patches.isNotEmpty() }
        val settings = api.patches.last().getJSONObject("settings")
        assertEquals(45, settings.getJSONObject("recently_added").getInt("cover_limit"))
        assertTrue("no single value sent", !settings.has("cover_limit"))
        compose.waitUntil(timeoutMillis = 30_000) { runCatching { chip("recently_added", 45).assertIsSelected() }.isSuccess }
        chip("suggestions", 15).assertIsSelected()
        chip("recently_released", 15).assertIsSelected()
    }

    @Test
    fun everyCoverChipsNumberKeepsOneLineOnTheNarrowestPhone() {
        setScreen(widthDp = NARROWEST_PHONE_DP, fontScale = LARGEST_FONT_SCALE, french = true)
        waitForText("Sortis récemment")
        // A Row squeezed the last chip until "60" broke over two lines.
        val broken = listOf("suggestions", "recently_added", "recently_released").flatMap { id ->
            WidgetIds.COVER_LIMITS.filter { n ->
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNode(hasText(n.toString()) and hasAnyAncestor(hasTestTag("covers_$id")), useUnmergedTree = true)
                    .fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
                layouts.single().lineCount > 1
            }.map { "$id:$it" }
        }
        assertEquals("chips whose number wraps", emptyList<String>(), broken)
    }

    /** The chip with [n] on the row of the cover widget [id]. */
    private fun chip(id: String, n: Int) =
        compose.onNode(hasClickAction() and hasText(n.toString()) and hasAnyAncestor(hasTestTag("covers_$id")))

    /** Drags [id]'s row by its handle until its middle is at [toId]'s row's
     * middle -- past half that row, where a drag list moves past it -- in
     * small steps, as a finger does, plus the touch slop the gesture
     * swallows before it starts. */
    private fun dragHandle(id: String, toId: String) {
        val from = compose.onNodeWithTag("widget_row_$id", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val to = compose.onNodeWithTag("widget_row_$toId", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val slop = ViewConfiguration.get(InstrumentationRegistry.getInstrumentation().targetContext).scaledTouchSlop
        val between = with(compose.density) { ((to.top + to.bottom) / 2 - (from.top + from.bottom) / 2).toPx() }
        val dy = if (between == 0f) 0f else between + Math.signum(between) * slop
        compose.onNodeWithTag("drag_$id", useUnmergedTree = true).performTouchInput {
            down(center)
            repeat(DRAG_STEPS) { moveBy(Offset(0f, dy / DRAG_STEPS)) }
            up()
        }
        compose.waitForIdle()
    }

    private fun lastOrder() = api.patches.last().getJSONArray("order").let { a -> List(a.length()) { a.getString(it) } }

    @Test
    fun aDragFromTheFirstRowToTheFourthStoresThatOrderInOneWrite() {
        setScreen()
        waitForText("Recently released")
        dragHandle("library", "recently_added")
        compose.waitUntil(timeoutMillis = 30_000) { api.patches.isNotEmpty() }
        compose.waitForIdle()
        assertEquals("one write for the whole drag", 1, api.patches.size)
        assertEquals(listOf("devices", "suggestions", "recently_added", "library", "recently_released", "most_played"), lastOrder())
    }

    private fun lastDisabled() = api.patches.last().getJSONArray("disabled").let { a -> List(a.length()) { a.getString(it) } }

    @Test
    fun aSwipeRemovesTheWidgetAndItsRowLeavesTheList() {
        setScreen()
        waitForText("Recently released")
        compose.onNodeWithTag("widget_row_library").performTouchInput { swipeLeft() }
        compose.waitUntil(timeoutMillis = 30_000) { api.patches.isNotEmpty() }
        assertEquals(listOf("library"), lastDisabled())
        assertEquals("a removal writes only the hidden list", listOf("disabled"), api.patches.last().keys().asSequence().toList())
        compose.waitUntil(timeoutMillis = 30_000) { compose.onAllNodesWithTag("widget_row_library").fetchSemanticsNodes().isEmpty() }
        waitForText("Removed from Home: Library")
    }

    @Test
    fun undoPutsTheWidgetBackInThePlaceItHad() {
        setScreen()
        waitForText("Recently released")
        compose.onNodeWithTag("widget_row_suggestions").performTouchInput { swipeRight() }
        compose.waitUntil(timeoutMillis = 30_000) { api.patches.size == 1 }
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(timeoutMillis = 30_000) { api.patches.size == 2 }
        // Only Suggestions leaves the hidden list; the rest is what the server
        // holds (Administration, for a non-admin).
        assertTrue("Suggestions still hidden: ${lastDisabled()}", "suggestions" !in lastDisabled())
        compose.waitUntil(timeoutMillis = 30_000) { compose.onAllNodesWithTag("widget_row_suggestions").fetchSemanticsNodes().isNotEmpty() }
        // Back between Devices and Recently added, as before.
        val tops = PREFS_ORDER.map { compose.onNodeWithTag("widget_row_$it").getUnclippedBoundsInRoot().top }
        assertEquals(tops.sorted(), tops)
    }

    @Test
    fun anUndoRightAfterTheSwipeIsNotOvertakenByTheSwipesOwnWrite() {
        // The swipe's write is held at the server; the Undo is tapped while
        // it is out. Sent at once, the Undo would be applied first and the
        // swipe's write after it, leaving the widget removed.
        api.holdFirstPatch = true
        setScreen()
        waitForText("Recently released")
        compose.onNodeWithTag("widget_row_suggestions").performTouchInput { swipeLeft() }
        compose.waitUntil(timeoutMillis = 30_000) { api.patches.size == 1 }
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(timeoutMillis = 30_000) { api.patches.size == 2 }
        compose.waitUntil(timeoutMillis = 30_000) { compose.onAllNodesWithTag("widget_row_suggestions").fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(500)
        assertTrue("the server ends with Suggestions hidden: ${api.disabled}", "suggestions" !in api.disabled)
    }

    @Test
    fun talkBackRemovesThroughTheRowsOwnAction() {
        setScreen()
        waitForText("Recently released")
        val node = compose.onNode(
            hasAnyAncestor(hasTestTag("widget_row_devices")) and SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions),
        ).fetchSemanticsNode()
        compose.runOnIdle { node.config[SemanticsActions.CustomActions].single { it.label == "Remove Devices" }.action() }
        compose.waitUntil(timeoutMillis = 30_000) { api.patches.isNotEmpty() }
        assertEquals(listOf("devices"), lastDisabled())
    }

    @Test
    fun addPutsAHiddenWidgetBackAtTheEndInOneWrite() {
        api.disabled = mutableListOf("devices")
        setScreen()
        waitForText("Recently released")
        compose.onNodeWithTag("widget_add").performClick()
        compose.onNodeWithTag("widget_add_devices").performClick()
        compose.waitUntil(timeoutMillis = 30_000) { api.patches.isNotEmpty() }
        assertEquals(emptyList<String>(), lastDisabled())
        assertEquals(listOf("library", "suggestions", "recently_added", "recently_released", "most_played", "devices"), lastOrder())
        assertEquals("one write for both", 1, api.patches.size)
    }

    @Test
    fun theAddSheetOffersOnlyHiddenWidgetsWithTheirCaptions() {
        api.disabled = mutableListOf("recently_added", "most_played")
        setScreen()
        waitForText("Recently released")
        compose.onNodeWithTag("widget_add").performClick()
        compose.onNodeWithTag("widget_add_sheet").assertExists()
        compose.onAllNodesWithTag("widget_add_library").assertCountEquals(0)
        compose.onAllNodesWithTag("widget_add_administration").assertCountEquals(0) // a non-admin is not offered it
        compose.onNodeWithTag("widget_add_recently_added").assertExists()
        // Most played needs a listening history: the sheet says so, as the rows do.
        compose.onNode(hasTestTag("widget_add_most_played") and hasAnyDescendant(hasText("Last.fm", substring = true)), useUnmergedTree = true)
            .assertExists()
    }

    private fun openAddSheet() {
        compose.onNodeWithTag("widget_add").performScrollTo().performClick()
        compose.onNodeWithTag("widget_add_sheet").assertExists()
    }

    @Test
    fun withNoListeningHistoryTheAddSheetLeavesOutTheWidgetsThatNeedOne() {
        api.mostPlayedConfigured = false
        api.disabled = mutableListOf("recently_added", "most_played")
        setScreen(widgets = WidgetRegistry.all)
        waitForText("Recently released")
        compose.waitUntil(timeoutMillis = 30_000) { api.hits("/api/app/suggestions/most-played") > 0 }
        compose.waitForIdle()
        openAddSheet()
        compose.onNodeWithTag("widget_add_recently_added").assertExists()
        compose.onAllNodesWithTag("widget_add_most_played").assertCountEquals(0)
    }

    @Test
    fun withAListeningHistoryTheWidgetIsOfferedLikeAnyOther() {
        api.mostPlayedConfigured = true
        api.disabled = mutableListOf("most_played")
        setScreen(widgets = WidgetRegistry.all)
        waitForText("Recently released")
        compose.waitUntil(timeoutMillis = 30_000) { api.hits("/api/app/suggestions/most-played") > 0 }
        compose.waitForIdle()
        openAddSheet()
        compose.onNodeWithTag("widget_add_most_played").assertExists()
        compose.onAllNodes(hasTestTag("widget_add_most_played") and hasAnyDescendant(hasText("Last.fm", substring = true)), useUnmergedTree = true)
            .assertCountEquals(0) // no caption once it is known to be set up
    }

    @Test
    fun whenTheFeedCannotBeReadTheWidgetIsOfferedWithItsCaption() {
        api.suggestionsFail = true
        api.disabled = mutableListOf("suggestions")
        setScreen(widgets = WidgetRegistry.all)
        waitForText("Recently released")
        compose.waitUntil(timeoutMillis = 30_000) { api.hits("/api/app/suggestions") > 0 }
        compose.waitForIdle()
        openAddSheet()
        compose.onNode(hasTestTag("widget_add_suggestions") and hasAnyDescendant(hasText("Last.fm", substring = true)), useUnmergedTree = true)
            .assertExists()
    }

    @Test
    fun whenTheOnlyHiddenWidgetNeedsAHistoryThereIsNoneAddIsOff() {
        api.mostPlayedConfigured = false
        api.disabled = mutableListOf("most_played")
        setScreen(widgets = WidgetRegistry.all)
        waitForText("Recently released")
        compose.waitUntil(timeoutMillis = 30_000) { api.hits("/api/app/suggestions/most-played") > 0 }
        compose.waitUntil(timeoutMillis = 30_000) {
            runCatching { compose.onNodeWithTag("widget_add").assertIsNotEnabled() }.isSuccess
        }
    }

    @Test
    fun withEveryWidgetRemovedTheScreenSaysSoAndOffersAdd() {
        api.disabled = PREFS_ORDER.toMutableList()
        setScreen()
        waitForText("No widgets on Home.")
        compose.onAllNodesWithTag("widget_prefs_widgets").assertCountEquals(0)
        compose.onNodeWithTag("widget_add").assertIsEnabled()
    }

    @Test
    fun withNothingHiddenAddIsOff() {
        setScreen()
        waitForText("Recently released")
        compose.onNodeWithTag("widget_add").assertIsNotEnabled()
    }

    @Test
    fun aHiddenWidgetIsNotListedAndAMoveStepsOverItWithoutMovingIt() {
        api.disabled = mutableListOf("devices")
        setScreen()
        waitForText("Recently released")
        compose.onAllNodesWithTag("widget_row_devices").assertCountEquals(0)
        // Library, then (hidden) Devices, then Suggestions: one step down is
        // past Suggestions, and Devices keeps its place in the stored order.
        compose.onNodeWithContentDescription("Move Library down").performClick()
        compose.waitUntil(timeoutMillis = 30_000) { api.patches.isNotEmpty() }
        assertEquals(listOf("suggestions", "devices", "library", "recently_added", "recently_released", "most_played"), lastOrder())
    }

    @Test
    fun aDragThatLandsWhereItStartedWritesNothing() {
        setScreen()
        waitForText("Recently released")
        dragHandle("suggestions", "suggestions") // down and up in place, past no row
        compose.onNodeWithTag("drag_suggestions", useUnmergedTree = true).performTouchInput {
            down(center); repeat(DRAG_STEPS) { moveBy(Offset(0f, 2f)) }; up() // a small wobble, under half a row
        }
        compose.waitForIdle()
        Thread.sleep(500)
        assertEquals(0, api.patches.size)
    }

    /** The list's scroll position and its end, from the scroll semantics. */
    private fun scrolled(): Pair<Float, Float> {
        val range = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        return range.value() to range.maxValue()
    }

    @Test
    fun aRowHeldAtTheBottomEdgeScrollsTheListAndLandsPastRowsThatWereOffScreen() {
        setScreen(widthDp = NARROWEST_PHONE_DP, fontScale = LARGEST_FONT_SCALE)
        waitForText("Recently released")
        val bottom = compose.onRoot().getUnclippedBoundsInRoot().bottom
        val offScreen = PREFS_ORDER.indexOfFirst {
            compose.onNodeWithTag("widget_row_$it", useUnmergedTree = true).getUnclippedBoundsInRoot().top >= bottom
        }
        assertTrue("some rows start below the screen", offScreen > 0)
        val handle = compose.onNodeWithTag("drag_library", useUnmergedTree = true)
        val start = handle.getUnclippedBoundsInRoot()
        // To inside the bottom edge band, and held there without moving.
        val dy = with(compose.density) { (bottom - 24.dp - (start.top + start.bottom) / 2).toPx() }
        handle.performTouchInput { down(center); repeat(DRAG_STEPS) { moveBy(Offset(0f, dy / DRAG_STEPS)) } }
        compose.waitUntil(timeoutMillis = 30_000) { scrolled().let { (at, end) -> at > 0f && at >= end } }
        handle.performTouchInput { up() }
        compose.waitUntil(timeoutMillis = 30_000) { api.patches.isNotEmpty() }
        val landed = lastOrder().indexOf("library")
        assertTrue("landed at $landed, not past row $offScreen, which started off screen", landed >= offScreen)
    }

    @Test
    fun aNonAdminDoesNotSeeTheAdminWidgetAndAStepStoresTheWholeOrder() {
        setScreen()
        waitForText("Recently released")
        compose.onAllNodesWithText("Administration").assertCountEquals(0)
        compose.onNodeWithContentDescription("Move Devices up").performClick()
        compose.waitUntil(timeoutMillis = 30_000) { api.patches.isNotEmpty() }
        val order = api.patches.last().getJSONArray("order").let { a -> List(a.length()) { a.getString(it) } }
        assertEquals(listOf("devices", "library", "suggestions", "recently_added", "recently_released", "most_played"), order)
    }

    @Test
    fun theWidgetsThatNeedAListeningHistoryStayListedAndSaySo() {
        setScreen()
        waitForText("Recently released")
        // Listed, so Home leaving them out has an explanation here.
        compose.onAllNodesWithText("Shown on Home once a Last.fm or ListenBrainz username is set up.").assertCountEquals(2)
    }

    @Test
    fun theMonthsStepperStopsAtTheRange() {
        api.months["recently_added"] = 24
        setScreen()
        waitForText("24 months")
        // The stepper for Recently added is the first "+"; at 24 it is disabled.
        compose.onAllNodesWithText("+")[0].assertIsNotEnabled()
        compose.onAllNodesWithText("−")[0].performClick()
        waitForText("23 months")
        assertEquals(23, api.patches.last().getJSONObject("settings").getJSONObject("recently_added").getInt("months"))
    }

    private companion object {
        const val NARROWEST_PHONE_DP = 320 // the smallest width Android's compatibility definition allows a phone
        const val LARGEST_FONT_SCALE = 2f
        const val DRAG_STEPS = 12
        /** The rows a non-admin sees, in the fake's default order. */
        val PREFS_ORDER = listOf(
            WidgetIds.LIBRARY, WidgetIds.DEVICES, WidgetIds.SUGGESTIONS,
            WidgetIds.RECENTLY_ADDED, WidgetIds.RECENTLY_RELEASED, WidgetIds.MOST_PLAYED,
        )
    }
}
