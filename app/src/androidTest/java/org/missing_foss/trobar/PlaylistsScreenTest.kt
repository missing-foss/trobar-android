// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
// The Playlists tab against the in-memory App API: the list most available
// first with the browser's owner marks and figures, a playlist opened on
// its entries with the unmatched ones marked, the provider sync started and
// followed until it stops -- one already running followed rather than
// reported, a start that fails said -- the header every tab has, and a
// playlist staged by double-tap for the header's device, a single tap
// opening it instead.

package org.missing_foss.trobar

import androidx.compose.material3.MaterialTheme
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.util.Locale
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import androidx.compose.ui.test.onAllNodesWithContentDescription
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaylistsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: MockWebServer
    private lateinit var api: FakePlaylistApi
    private lateinit var shared: AppState

    @Before
    fun startServer() {
        runBlocking { Prefs.clearHeaderDevice(context) }
        api = FakePlaylistApi()
        server = MockWebServer()
        server.dispatcher = api
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    private fun setScreen(
        shown: androidx.compose.runtime.State<Boolean>? = null,
        wide: Boolean = false,
        widthDp: Int? = null,
        fontScale: Float? = null,
        french: Boolean = false,
        target: Long? = null,
        level: Int = 0,
        sortStore: SortStore = SortStore(),
    ) {
        val pairing = Prefs.Pairing(serverUrl = server.url("/").toString(), token = "test-token")
        val shared = testAppState(context, pairing)
        this.shared = shared
        target?.let(shared::openPlaylist)
        vm = PlaylistsViewModel(
            AppApiPlaylistClient(context, pairing.serverUrl, pairing.token),
            AppApiClient(context, pairing.serverUrl, pairing.token),
            shared,
            appApiLevel = { level },
            sortStore = sortStore,
        )
        compose.setContent {
            val base = LocalContext.current
            val config = Configuration(base.resources.configuration).apply { if (french) setLocale(Locale.FRENCH) }
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides base.createConfigurationContext(config),
                LocalConfiguration provides config,
                LocalDensity provides Density(density.density, fontScale ?: density.fontScale),
                LocalWindowWidthSizeClass provides if (wide) WindowWidthSizeClass.Expanded else WindowWidthSizeClass.Compact,
            ) {
                MaterialTheme {
                    Box(if (widthDp != null) Modifier.requiredWidth(widthDp.dp) else Modifier) {
                        if (shown?.value != false) PlaylistsScreen(pairing = pairing, nav = PlaylistsNav({}, {}), shared = shared, vm = vm)
                    }
                }
            }
        }
    }

    /** The unmatched count sits on its own line below the availability figure,
     * not squeezed beside it -- measured where it broke: 320 dp, French, 2x. */
    private fun assertCountBelowAvailability() {
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) {
            compose.onAllNodes(hasText("non associé(s) localement", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        val availability = compose.onAllNodes(hasText("pistes disponibles localement", substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes().maxBy { it.boundsInRoot.top }.boundsInRoot
        val count = compose.onAllNodes(hasText("non associé(s) localement", substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes().maxBy { it.boundsInRoot.top }.boundsInRoot
        // Measured where it broke, the old row squeezed the count to nothing at all.
        assertTrue("count has no width: $count", count.width > 0f)
        assertTrue("count at $count, availability at $availability", count.top >= availability.bottom)
    }

    @Test
    fun theUnmatchedCountIsOnItsOwnLineInTheListAndTheDetail() {
        api.playlists += FakePlaylistApi.Playlist(1, "Road Trip", trackCount = 142, matchedCount = 62)
        setScreen(widthDp = NARROWEST_PHONE_DP, fontScale = LARGEST_FONT_SCALE, french = true)
        compose.waitForText("Road Trip")
        assertCountBelowAvailability() // the list
        compose.onNodeWithText("Road Trip").performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { compose.onAllNodes(hasTestTag("playlist_entries")).fetchSemanticsNodes().isNotEmpty() }
        assertCountBelowAvailability() // the detail
    }

    @Test
    fun theHeadersCountAndRefreshEachKeepOneLineOnTheNarrowestPhone() {
        api.playlists += FakePlaylistApi.Playlist(1, "Road Trip", trackCount = 142, matchedCount = 62)
        api.playlists += FakePlaylistApi.Playlist(2, "Household", trackCount = 3, matchedCount = 3)
        setScreen(widthDp = NARROWEST_PHONE_DP, fontScale = LARGEST_FONT_SCALE, french = true)
        compose.waitForText("Road Trip")
        // The count used to be measured after the button, and was left one
        // character wide beside it; now the button takes the next line.
        assertEquals(1, lineCount("2 playlists"))
        assertEquals(1, lineCount("Rafraîchir les playlists"))
        compose.onNodeWithText("Road Trip").assertIsDisplayed()
    }

    @Test
    fun anOpenPlaylistsWholeNameShowsOnTheNarrowestPhoneAtALargerFont() {
        val name = "Road trip across the Pyrenees, summer edition"
        api.playlists += FakePlaylistApi.Playlist(1, name, trackCount = 4, matchedCount = 2)
        setScreen(widthDp = NARROWEST_PHONE_DP, fontScale = LARGER_FONT_SCALE, french = true)
        compose.waitForText(name)
        compose.onNodeWithText(name).performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { compose.onAllNodes(hasTestTag("playlist_entries")).fetchSemanticsNodes().isNotEmpty() }
        // Beside three icons in the top bar it was "Road tri…"; on the page it
        // wraps, all of it shown.
        val layout = layoutOf(name)
        assertTrue("the name is cut off", !layout.hasVisualOverflow)
        assertTrue("the name wraps rather than being cut: ${layout.lineCount} line(s)", layout.lineCount > 1)
    }

    private fun layoutOf(text: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag("playlist_summary")), useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        return layouts.single()
    }

    private fun lineCount(text: String): Int {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        return layouts.single().lineCount
    }

    @Test
    fun aLineSeparatesPlaylistsButNotTheFirstOrTheLast() {
        listOf("A", "B", "C").forEachIndexed { i, t -> api.playlists += FakePlaylistApi.Playlist(i + 1L, t, trackCount = 1, matchedCount = 1) }
        setScreen()
        compose.waitForText("3 playlists")
        compose.onAllNodes(hasTestTag("playlist_divider"), useUnmergedTree = true).assertCountEquals(2)
    }

    @Test
    fun anOpenPlaylistsTitleIsShownOnceOnAPhoneOnThePageAndItsOwnerMarkStays() {
        api.playlists += FakePlaylistApi.Playlist(2, "Household", trackCount = 3, matchedCount = 3, ownerUsername = "carol", isOwn = false)
        setScreen()
        compose.waitForText("Household")
        compose.onNodeWithText("Household").performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { compose.onAllNodes(hasTestTag("playlist_entries")).fetchSemanticsNodes().isNotEmpty() }
        // Once, on the page; the top bar keeps back and the icons only.
        compose.onAllNodesWithText("Household").assertCountEquals(1)
        compose.onAllNodes(hasText("Household") and hasAnyAncestor(hasTestTag("playlists_top_bar"))).assertCountEquals(0)
        compose.onNode(hasText("Household") and hasAnyAncestor(hasTestTag("playlist_summary")), useUnmergedTree = true).assertIsDisplayed()
        // Unmerged: the summary merges its children into one node for the
        // double-tap, so the mark's own node is only in the unmerged tree.
        compose.onNode(hasText("carol") and hasAnyAncestor(hasTestTag("playlist_summary")), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun onAWideLayoutTheOpenPlaylistsTitleIsInTheDetail() {
        api.playlists += FakePlaylistApi.Playlist(1, "Road Trip", trackCount = 2, matchedCount = 1)
        setScreen(wide = true)
        compose.waitForText("Road Trip")
        compose.onNodeWithText("Road Trip").performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { compose.onAllNodes(hasTestTag("playlist_entries")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("Road Trip") and hasAnyAncestor(hasTestTag("playlists_top_bar"))).assertCountEquals(0)
        compose.onAllNodes(hasText("Road Trip") and hasAnyAncestor(hasTestTag("playlist_entries"))).assertCountEquals(1)
    }

    @Test
    fun backOnTheTabTheListIsReadAgain() {
        api.playlists += FakePlaylistApi.Playlist(1, "Road Trip", trackCount = 4, matchedCount = 2)
        val shown = androidx.compose.runtime.mutableStateOf(true)
        setScreen(shown)
        compose.waitForText("1 playlist")
        // Away -- on the subscriptions, say, where a new one made a playlist --
        // and back, with the same view model, as navigation keeps it.
        shown.value = false
        compose.waitForIdle()
        api.playlists += FakePlaylistApi.Playlist(2, "Mix 100", trackCount = 2, matchedCount = 1, provider = "ytmusic")
        shown.value = true
        compose.waitForText("Mix 100")
        compose.onNodeWithText("2 playlists").assertIsDisplayed()
    }

    private fun top(text: String) = compose.onNodeWithText(text).getUnclippedBoundsInRoot().top

    private fun row(title: String) = compose.onNode(hasText(title) and hasAnyAncestor(hasTestTag("playlist_list")))

    /** Past the double-tap window on the test's clock -- only then is a tap
     * a single tap -- then a round trip to the fake in real time. */
    private fun settle() {
        compose.mainClock.advanceTimeBy(PAST_A_DOUBLE_TAP_MS)
        Thread.sleep(SETTLE_MS)
        compose.waitForIdle()
    }

    @Test
    fun aDoubleTapOnAPlaylistStagesItForTheHeadersDeviceAndItFlies() {
        api.playlists += FakePlaylistApi.Playlist(1, "Road Trip", trackCount = 4, matchedCount = 2)
        setScreen()
        compose.waitForText("Road Trip")
        compose.mainClock.autoAdvance = false
        row("Road Trip").performTouchInput { doubleClick() }
        compose.mainClock.advanceTimeBy(PART_OF_A_FLIGHT_MS)
        compose.onAllNodes(hasTestTag("basket_flight"), useUnmergedTree = true).assertCountEquals(1)
        compose.mainClock.autoAdvance = true
        // The server's vocabulary: type "playlist", the id as the target.
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.base.staged.size == 1 }
        assertEquals(Triple("playlist", "1", listOf(1L)), api.base.staged.single())
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) {
            compose.onAllNodes(hasContentDescription("Basket") and hasText("1")).fetchSemanticsNodes().isNotEmpty()
        }
        // A double-tap stages; it does not open the playlist as well.
        settle()
        compose.onNodeWithText("Refresh playlists").assertIsDisplayed()
        compose.onAllNodes(hasTestTag("playlist_entries")).assertCountEquals(0)
    }

    @Test
    fun anotherTabCanOpenAPlaylist() {
        api.playlists += FakePlaylistApi.Playlist(1, "Road Trip", trackCount = 1, matchedCount = 1)
        api.entries[1] = listOf(PlaylistEntry(1, "A", "One", "B", matched = true))
        setScreen(target = 1)
        compose.waitForText("One")
        // Cleared, or the tab would reopen it over wherever the person went.
        assertEquals(null, shared.state.value.playlistTarget)
    }

    @Test
    fun aSingleTapOpensThePlaylistAndStagesNothing() {
        api.playlists += FakePlaylistApi.Playlist(1, "Road Trip", trackCount = 1, matchedCount = 1)
        api.entries[1] = listOf(PlaylistEntry(1, "A", "One", "B", matched = true))
        setScreen()
        compose.waitForText("Road Trip")
        row("Road Trip").performTouchInput { click() }
        settle()
        compose.waitForText("One")
        assertTrue("a single tap wrote ${api.base.staged}", api.base.staged.isEmpty())
    }

    private fun entry(id: Long, title: String, position: Int) =
        PlaylistEntry(position, "A", title, "B", matched = true, trackId = id, entryId = id)

    /** A playlist made in Trobar, three tracks, open and editable. */
    private fun openEditable(isOwn: Boolean = true, provider: String = "trobar", level: Int = APP_API_PLAYLIST_WRITES) {
        api.playlists += FakePlaylistApi.Playlist(1, "Road Trip", trackCount = 3, matchedCount = 3, provider = provider, isOwn = isOwn)
        api.entries[1] = listOf(entry(11, "One", 1), entry(12, "Two", 2), entry(13, "Three", 3))
        setScreen(level = level)
        compose.waitForText("Road Trip")
        compose.onNodeWithText("Road Trip").performClick()
        compose.waitForText("Three")
    }

    // From the left: swipeLeft starts at the right edge, on the handle, which wins.
    private fun swipeAway(position: Int) =
        compose.onNodeWithTag("playlist_entry_$position").performTouchInput { swipeRight() }

    @Test
    fun aNewPlaylistAsksForANameAndOpens() {
        setScreen(level = APP_API_PLAYLIST_WRITES)
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) {
            compose.onAllNodes(hasTestTag("playlist_new")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("playlist_new").performClick()
        compose.waitForText("Name")
        compose.onNode(hasSetTextAction()).performTextInput("Morning run")
        compose.onNodeWithText("Save").performClick()
        compose.waitForText("Morning run")
        compose.onNodeWithTag("playlist_empty").assertIsDisplayed()
    }

    @Test
    fun belowLevelThreeThereIsNoNewPlaylist() {
        setScreen(level = 2)
        compose.waitForText("Refresh", substring = true)
        compose.onAllNodes(hasTestTag("playlist_new")).assertCountEquals(0)
    }

    @Test
    fun onlyAPlaylistMadeInTrobarAndYoursCanBeEdited() {
        openEditable(provider = "roon")
        compose.onAllNodes(hasTestTag("playlist_edit_actions")).assertCountEquals(0)
    }

    @Test
    fun someoneElsesPlaylistCannotBeEdited() {
        openEditable(isOwn = false)
        compose.onAllNodes(hasTestTag("playlist_edit_actions")).assertCountEquals(0)
    }

    @Test
    fun aRemovalWaitsForItsUndoAndThenSends() {
        openEditable()
        swipeAway(2)
        compose.waitForText("Removed “Two”")
        assertTrue("sent before the Undo ran out: ${api.puts}", api.puts.isEmpty())
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.puts.size == 1 }
        assertEquals(1L to listOf(11L, 13L), api.puts.single())
    }

    @Test
    fun undoPutsItBackAndSendsNothing() {
        openEditable()
        swipeAway(2)
        compose.waitForText("Undo")
        compose.onNodeWithText("Undo").performClick()
        compose.waitForText("Two")
        Thread.sleep(SETTLE_MS)
        compose.waitForIdle()
        assertEquals(emptyList<Pair<Long, List<Long>>>(), api.puts.toList())
    }

    @Test
    fun leavingSendsARemovalStillWaiting() {
        openEditable()
        swipeAway(1)
        compose.waitForText("Undo")
        compose.runOnUiThread { vm.open(null) }
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.puts.size == 1 }
        assertEquals(1L to listOf(12L, 13L), api.puts.single())
    }

    private fun slowFirstPut(): java.util.concurrent.CountDownLatch {
        val hold = java.util.concurrent.CountDownLatch(1)
        api.holdPuts = hold
        compose.onNodeWithContentDescription("Move Three up").performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.puts.size == 1 }
        return hold
    }

    @Test
    fun leavingWhileAWriteIsOnItsWayNeverEmptiesThePlaylist() {
        openEditable()
        val hold = slowFirstPut()
        compose.waitForIdle()
        swipeAway(1)
        compose.waitForText("Undo")
        compose.runOnUiThread { vm.open(null) }
        api.holdPuts = null
        hold.countDown()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.puts.size == 2 }
        // The fake counts a PUT before it applies it.
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.entries[1]!!.size == 2 }
        assertTrue("sent an empty list: ${api.puts}", api.puts.none { it.second.isEmpty() })
        assertEquals(listOf("Three", "Two"), api.entries[1]!!.map { it.title })
    }

    @Test
    fun aSwipeDuringAWriteIsNotUndoneByItsAnswer() {
        openEditable()
        val hold = slowFirstPut()
        compose.waitForIdle()
        swipeAway(1)
        compose.waitForText("Undo")
        api.holdPuts = null
        hold.countDown()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.puts.size == 2 }
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.entries[1]!!.map { it.title } == listOf("Three", "Two") }
        assertEquals(listOf("Three", "Two"), api.entries[1]!!.map { it.title })
        compose.onAllNodesWithText("One").assertCountEquals(0)
    }

    @Test
    fun aSecondMoveDuringAWriteIsKept() {
        openEditable()
        val hold = slowFirstPut()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Move One down").performClick()
        api.holdPuts = null
        hold.countDown()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.puts.size == 2 }
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.entries[1]!!.map { it.title } == listOf("Three", "One", "Two") }
        assertEquals(listOf("Three", "One", "Two"), api.entries[1]!!.map { it.title })
    }

    @Test
    fun undoAfterAWriteLandedGoesBackUnderItsNewId() {
        openEditable()
        val hold = slowFirstPut()
        compose.waitForIdle()
        swipeAway(1)
        compose.waitForText("Undo")
        // The move lands while the removal waits: every id, One's too, is new.
        api.holdPuts = null
        hold.countDown()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.entries[1]!!.first().entryId != 11L }
        compose.onNodeWithText("Undo").performClick()
        compose.waitForText("One")
        Thread.sleep(SETTLE_MS)
        compose.waitForIdle()
        assertEquals("one write only: undoing changes nothing on the server", 1, api.puts.size)
        compose.onAllNodesWithText("changed elsewhere", substring = true).assertCountEquals(0)
        // And the next edit is accepted, so the restored row's id is good.
        compose.onNodeWithContentDescription("Move Two up").performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.puts.size == 2 }
        compose.waitForIdle()
        assertEquals(listOf("One", "Two", "Three"), api.entries[1]!!.map { it.title })
    }

    @Test
    fun aFailedReloadAfterARefusalSaysSoAndKeepsTheApp() {
        openEditable()
        api.entries[1] = listOf(entry(21, "Elsewhere", 1))
        api.getStatus = 502
        swipeAway(2)
        compose.waitForText("Undo")
        compose.waitForText("gone")
    }

    @Test
    fun aServerErrorIsNotCalledChangedElsewhere() {
        openEditable()
        api.putStatus = 500
        swipeAway(2)
        compose.waitForText("put failed")
        compose.onAllNodesWithText("changed elsewhere", substring = true).assertCountEquals(0)
    }

    @Test
    fun comingBackReadsTheOpenPlaylistAgain() {
        openEditable()
        // The Library appended a track: every id is new.
        api.entries[1] = listOf(entry(41, "One", 1), entry(42, "Two", 2), entry(43, "Three", 3), entry(44, "From The Library", 4))
        compose.runOnUiThread { vm.reenter() }
        compose.waitForText("From The Library")
        compose.onNodeWithContentDescription("Move Three up").performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.puts.size == 1 }
        assertEquals(listOf(41L, 43L, 42L, 44L), api.puts.single().second)
    }

    @Test
    fun comingBackLeavesAHeldRemovalAlone() {
        openEditable()
        swipeAway(2)
        compose.waitForText("Undo")
        compose.runOnUiThread { vm.reenter() }
        Thread.sleep(SETTLE_MS / 4)
        compose.waitForIdle()
        compose.onAllNodesWithText("Two").assertCountEquals(0)
    }

    @Test
    fun undoBelongsToThePlaylistItCameFrom() {
        openEditable()
        api.playlists += FakePlaylistApi.Playlist(2, "Other", trackCount = 1, matchedCount = 1, provider = "trobar")
        api.entries[2] = listOf(entry(31, "Thirty", 1))
        swipeAway(2)
        compose.waitForText("Undo")
        compose.runOnUiThread { vm.open(2) }
        compose.waitForText("Thirty")
        undoGoesAtOnce()
        compose.onAllNodesWithText("Two").assertCountEquals(0)
        assertTrue("a write crossed playlists: ${api.puts}", api.puts.none { it.first == 2L })
    }

    /** Sooner than the snackbar's own timeout, which would pass this anyway. */
    private fun undoGoesAtOnce() =
        compose.waitUntil(timeoutMillis = UNDO_GONE_MS) { compose.onAllNodesWithText("Undo").fetchSemanticsNodes().isEmpty() }

    @Test
    fun anUndoHeldOverAnotherPlaylistChangesNeither() {
        openEditable()
        api.playlists += FakePlaylistApi.Playlist(2, "Other", trackCount = 1, matchedCount = 1, provider = "trobar")
        api.entries[2] = listOf(entry(31, "Thirty", 1))
        lateinit var held: HeldRemoval
        compose.runOnUiThread { held = vm.removeEntry(vm.state.value.entries[1])!! }
        compose.runOnUiThread { vm.open(2) }
        compose.waitForText("Thirty")
        compose.runOnUiThread { vm.undoRemove(held) }
        compose.waitForIdle()
        compose.onAllNodesWithText("Two").assertCountEquals(0)
    }

    @Test
    fun nothingIsSentWhileADeleteIsOnItsWay() {
        openEditable()
        val hold = java.util.concurrent.CountDownLatch(1)
        api.holdDeletes = hold
        api.deleteStatus = 500
        swipeAway(2)
        compose.waitForText("Undo")
        compose.onNodeWithText("Delete").performClick()
        compose.waitForText("any mirror of it is deleted too", substring = true)
        compose.onAllNodesWithText("Delete")[1].performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.deletesAsked.get() == 1 }
        compose.runOnUiThread { vm.commitEdits() }
        Thread.sleep(SETTLE_MS)
        val during = api.puts.toList()
        api.holdDeletes = null
        hold.countDown()
        assertEquals(emptyList<Pair<Long, List<Long>>>(), during)
    }

    @Test
    fun aFailedDeleteLeavesThePlaylistEditable() {
        openEditable()
        api.deleteStatus = 500
        compose.onNodeWithText("Delete").performClick()
        compose.waitForText("any mirror of it is deleted too", substring = true)
        compose.onAllNodesWithText("Delete")[1].performClick()
        compose.waitForText("delete failed")
        compose.onNodeWithContentDescription("Move Three up").performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.puts.size == 1 }
        assertEquals(listOf(11L, 13L, 12L), api.puts.single().second)
    }

    @Test
    fun aFailedDeleteThenARemovalCantWipeThePlaylist() {
        openEditable()
        api.deleteStatus = 500
        compose.onNodeWithText("Delete").performClick()
        compose.waitForText("any mirror of it is deleted too", substring = true)
        compose.onAllNodesWithText("Delete")[1].performClick()
        compose.waitForText("delete failed")
        swipeAway(2)
        compose.waitForText("Undo")
        compose.onNodeWithText("Undo").performClick()
        compose.waitForText("Two")
        Thread.sleep(SETTLE_MS)
        compose.waitForIdle()
        assertEquals(emptyList<Pair<Long, List<Long>>>(), api.puts.toList())
    }

    @Test
    fun aDeleteSendsNothingElse() {
        openEditable()
        swipeAway(2)
        compose.waitForText("Undo")
        compose.onNodeWithText("Delete").performClick()
        compose.waitForText("any mirror of it is deleted too", substring = true)
        compose.onAllNodesWithText("Delete")[1].performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.deletedPlaylists.toList() == listOf(1L) }
        compose.waitForIdle()
        assertEquals(emptyList<Pair<Long, List<Long>>>(), api.puts.toList())
    }

    @Test
    fun anAdminCanEditSomeoneElsesPlaylistMadeInTrobar() {
        api.isAdmin = true
        openEditable(isOwn = false)
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) {
            compose.onAllNodes(hasTestTag("playlist_edit_actions")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun leavingTheTabSendsARemovalStillWaiting() {
        val shown = androidx.compose.runtime.mutableStateOf(true)
        api.playlists += FakePlaylistApi.Playlist(1, "Road Trip", trackCount = 3, matchedCount = 3, provider = "trobar")
        api.entries[1] = listOf(entry(11, "One", 1), entry(12, "Two", 2), entry(13, "Three", 3))
        setScreen(shown = shown, level = APP_API_PLAYLIST_WRITES)
        compose.waitForText("Road Trip")
        compose.onNodeWithText("Road Trip").performClick()
        compose.waitForText("Three")
        swipeAway(2)
        compose.waitForText("Undo")
        shown.value = false
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.puts.size == 1 }
        assertEquals(listOf(11L, 13L), api.puts.single().second)
    }

    @Test
    fun aMoveClosesAHeldRemovalsUndo() {
        openEditable()
        swipeAway(2)
        compose.waitForText("Undo")
        compose.onNodeWithContentDescription("Move Three up").performClick()
        undoGoesAtOnce()
    }

    @Test
    fun aNewNameIsTrimmedAndABlankOneRefused() {
        setScreen(level = APP_API_PLAYLIST_WRITES)
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) {
            compose.onAllNodes(hasTestTag("playlist_new")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("playlist_new").performClick()
        compose.waitForText("Name")
        compose.onNode(hasSetTextAction()).performTextInput("   ")
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextReplacement("  Morning run  ")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.playlists.any { it.title == "Morning run" } }
    }

    @Test
    fun aPlaylistReopenedAtOnceShowsTheWriteJustSent() {
        openEditable()
        val hold = slowFirstPut()
        compose.runOnUiThread {
            vm.open(null)
            vm.open(1)
        }
        Thread.sleep(SETTLE_MS) // long enough for a read that didn't wait to come back
        api.holdPuts = null
        hold.countDown()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { !vm.state.value.entriesLoading }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Move Two up").performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.puts.size == 2 }
        assertTrue("old ids sent: ${api.puts}", api.puts[1].second.none { it in 11L..13L })
    }

    @Test
    fun aDragFromTheHandleNeverRemoves() {
        openEditable()
        compose.onNode(hasTestTag("playlist_drag_2"), useUnmergedTree = true).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onAllNodesWithText("Undo").assertCountEquals(0)
        compose.onNodeWithText("Two").assertExists()
    }

    @Test
    fun theArrowsMoveAndEachWriteUsesTheIdsTheLastOneReturned() {
        openEditable()
        compose.onNodeWithContentDescription("Move Three up").performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.puts.size == 1 }
        assertEquals(listOf(11L, 13L, 12L), api.puts[0].second)
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.entries[1]!!.first().entryId != 11L }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Move One down").performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.puts.size == 2 }
        assertTrue("old ids sent: ${api.puts[1]}", api.puts[1].second.none { it in 11L..13L })
        assertEquals("the server refused nothing", listOf("Three", "One", "Two"), api.entries[1]!!.map { it.title })
    }

    @Test
    fun talkBackCanRemoveARow() {
        openEditable()
        val action = compose.onNodeWithTag("playlist_entry_2").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions].single { it.label == "Remove Two from the playlist" }
        compose.runOnUiThread { action.action() }
        compose.waitForText("Removed “Two”")
    }

    @Test
    fun aRefusedWriteReloadsThePlaylistAndSaysSo() {
        openEditable()
        // The browser rewrites the list: every id this screen holds is stale.
        api.entries[1] = listOf(entry(21, "Elsewhere", 1))
        swipeAway(2)
        compose.waitForText("Undo")
        compose.waitForText("This playlist changed elsewhere", substring = true)
        compose.waitForText("Elsewhere")
    }

    @Test
    fun deleteAsksSaysMirrorsGoAndClosesIt() {
        openEditable()
        compose.onNodeWithText("Delete").performClick()
        compose.waitForText("any mirror of it is deleted too", substring = true)
        compose.onAllNodesWithText("Delete")[1].performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.deletedPlaylists.toList() == listOf(1L) }
    }

    @Test
    fun renameSends() {
        openEditable()
        compose.onNodeWithText("Rename").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Long Road")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.shareBodies.any { it.optString("title") == "Long Road" } }
    }

    private lateinit var vm: PlaylistsViewModel

    private fun openWith(entry: PlaylistEntry) {
        api.playlists += FakePlaylistApi.Playlist(1, "Road Trip", trackCount = 1, matchedCount = 1)
        api.entries[1] = listOf(entry)
        setScreen()
        compose.waitForText("Road Trip")
        compose.onNodeWithText("Road Trip").performClick()
        compose.waitForText(entry.title!!)
    }

    /** Taps the only entry and waits out the sheet's slide up. */
    private fun openSheet() {
        compose.onNodeWithTag("playlist_entry_1").performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) {
            runCatching { compose.onNodeWithTag("entry_stage").assertIsDisplayed() }.isSuccess
        }
    }

    @Test
    fun theSyncButtonStagesTheOpenPlaylistAndSaysSo() {
        openWith(PlaylistEntry(1, "A", "One", "B", matched = true, trackId = 9))
        compose.onNodeWithText("Sync entire playlist").performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.base.staged.size == 1 }
        assertEquals(Triple("playlist", "1", listOf(1L)), api.base.staged.single())
        compose.waitForText("In the basket")
    }

    @Test
    fun tappingATrackOffersToStageItAndStagesThatTrack() {
        openWith(PlaylistEntry(1, "A", "One", "B", matched = true, trackId = 9))
        openSheet()
        compose.onNodeWithTag("entry_stage").performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.base.staged.size == 1 }
        assertEquals(Triple("track", "9", listOf(1L)), api.base.staged.single())
    }

    @Test
    fun aStagedTrackSaysSoAndIsNotAddedAgain() {
        api.base.stage(50, "9", 1, type = "track")
        openWith(PlaylistEntry(1, "A", "One", "B", matched = true, trackId = 9))
        settle()
        openSheet()
        compose.onNodeWithTag("entry_stage").assertTextContains("Already in the basket", substring = true)
        compose.onNodeWithTag("entry_stage").assertIsNotEnabled()
        assertTrue("wrote ${api.base.staged}", api.base.staged.isEmpty())
    }

    @Test
    fun theSheetOpensWhereTheLibraryFilesTheTrackNotThePlaylistsNames() {
        openWith(
            PlaylistEntry(1, "Guest", "One", "Single", matched = true, trackId = 9,
                libraryArtist = "Various Artists", libraryAlbum = "Compilation"),
        )
        openSheet()
        compose.onNodeWithTag("entry_album").performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { shared.state.value.libraryTarget != null }
        assertEquals(LibraryTarget("Various Artists", "Compilation"), shared.state.value.libraryTarget)
    }

    @Test
    fun theSheetOpensTheArtistPageWithNoAlbumToScrollTo() {
        openWith(PlaylistEntry(1, "A", "One", "B", matched = true, trackId = 9, libraryArtist = "A", libraryAlbum = "B"))
        openSheet()
        compose.onNodeWithTag("entry_artist").performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { shared.state.value.libraryTarget != null }
        assertEquals(LibraryTarget("A", null), shared.state.value.libraryTarget)
    }

    @Test
    fun anEntryWithNoAlbumOffersNoAlbum() {
        openWith(PlaylistEntry(1, "A", "One", null, matched = true, trackId = 9))
        openSheet()
        compose.onNodeWithTag("entry_artist").assertIsDisplayed()
        compose.onAllNodes(hasTestTag("entry_album")).assertCountEquals(0)
    }

    @Test
    fun anUnmatchedEntryOpensTheReviewAndNoSheet() {
        openWith(PlaylistEntry(1, "A", "One", "B", matched = false))
        compose.onNodeWithTag("playlist_entry_1").performClick()
        settle()
        compose.onAllNodes(hasTestTag("entry_sheet")).assertCountEquals(0)
        compose.waitForText("Not matched to your library")
    }

    @Test
    fun aMatchedEntryWhoseTrackHasLeftTheLibraryDoesNothing() {
        openWith(PlaylistEntry(1, "A", "One", "B", matched = true, trackId = null))
        compose.onNodeWithTag("playlist_entry_1").performClick()
        settle()
        compose.onAllNodes(hasTestTag("entry_sheet")).assertCountEquals(0)
    }

    @Test
    fun anOpenPlaylistsSummaryStagesOnADoubleTap() {
        api.playlists += FakePlaylistApi.Playlist(1, "Road Trip", trackCount = 1, matchedCount = 1)
        api.entries[1] = listOf(PlaylistEntry(1, "A", "One", "B", matched = true))
        setScreen()
        compose.waitForText("Road Trip")
        compose.onNodeWithText("Road Trip").performClick()
        compose.waitForText("One")
        compose.onNodeWithTag("playlist_summary").performTouchInput { doubleClick() }
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.base.staged.size == 1 }
        assertEquals(Triple("playlist", "1", listOf(1L)), api.base.staged.single())
    }

    @Test
    fun aPlaylistAlreadyStagedForTheDeviceIsNotAddedAgainAndSaysSo() {
        api.playlists += FakePlaylistApi.Playlist(1, "Road Trip", trackCount = 1, matchedCount = 1)
        api.base.stage(50, "1", 1, type = "playlist")
        setScreen()
        compose.waitForText("Road Trip")
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) {
            compose.onAllNodes(hasContentDescription("Basket") and hasText("1")).fetchSemanticsNodes().isNotEmpty()
        }
        row("Road Trip").performTouchInput { doubleClick() }
        compose.waitForText("Already in the basket for Pixel")
        settle()
        assertTrue("wrote ${api.base.staged}", api.base.staged.isEmpty())
    }

    @OptIn(ExperimentalTestApi::class) // the test API for a custom action by its label
    @Test
    fun aScreenReaderOpensWithItsActivationAndStagesWithANamedAction() {
        api.playlists += FakePlaylistApi.Playlist(1, "Road Trip", trackCount = 1, matchedCount = 1)
        api.entries[1] = listOf(PlaylistEntry(1, "A", "One", "B", matched = true))
        setScreen()
        compose.waitForText("Road Trip")
        row("Road Trip").performCustomAccessibilityActionWithLabel("Add to the basket")
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.base.staged.size == 1 }
        row("Road Trip").performClick()
        compose.waitForText("One")
        assertEquals(1, api.base.staged.size) // opening stages nothing more
    }

    @Test
    fun theListShowsTheMostAvailableFirstWithTheBrowsersMarksAndFigures() {
        api.playlists += FakePlaylistApi.Playlist(1, "Road Trip", trackCount = 4, matchedCount = 2)
        api.playlists += FakePlaylistApi.Playlist(2, "Household", trackCount = 3, matchedCount = 3, ownerUsername = "carol", isOwn = false)
        api.playlists += FakePlaylistApi.Playlist(3, "Borrowed", trackCount = 10, matchedCount = 1, goldenOwnerUsername = "bob", provider = "tidal")
        setScreen()
        compose.waitForText("3 playlists")
        // Most available first; the server's order would put Road Trip first.
        assertTrue("Household above Road Trip", top("Household") < top("Road Trip"))
        assertTrue("Road Trip above Borrowed", top("Road Trip") < top("Borrowed"))
        compose.onNodeWithText("2/4 tracks available locally (50%)").assertIsDisplayed()
        compose.onNodeWithText("2 not matched locally").assertIsDisplayed()
        compose.onNodeWithText("carol").assertIsDisplayed() // someone else's, shared into the house
        compose.onNodeWithText("shared by bob").assertIsDisplayed()
        compose.onAllNodesWithText("alice").assertCountEquals(0) // one's own playlists carry no owner mark
        compose.onNodeWithContentDescription("Tidal").assertIsDisplayed() // the provider, as its glyph
        compose.onAllNodesWithText("Synced", substring = true).assertCountEquals(3)
    }

    @Test
    fun theProviderIsItsGlyphNamedForTalkBackAndNeverABlank() {
        api.playlists += FakePlaylistApi.Playlist(1, "Road Trip", trackCount = 4, matchedCount = 2, provider = "tidal")
        api.playlists += FakePlaylistApi.Playlist(2, "Mystery", trackCount = 4, matchedCount = 1, provider = "qobuz")
        api.playlists += FakePlaylistApi.Playlist(3, "Nowhere", trackCount = 4, matchedCount = 0, provider = null)
        setScreen()
        compose.waitForText("3 playlists")
        // Tidal's glyph, its name for TalkBack, and no longer written out.
        glyph("Road Trip", "provider_glyph_tidal").assertContentDescriptionEquals("Tidal")
        compose.onAllNodesWithText("Tidal", substring = true).assertCountEquals(0)
        // A provider the app has no mark for, and none at all: the neutral glyph.
        glyph("Mystery", "provider_glyph_unknown").assertContentDescriptionEquals("qobuz")
        glyph("Nowhere", "provider_glyph_unknown").assertContentDescriptionEquals("Unknown source")
    }

    @Test
    fun aPlaylistFromAPublicLinkCarriesTheLinkBadgeAndNoOtherDoes() {
        api.playlists += FakePlaylistApi.Playlist(1, "From YouTube", trackCount = 4, matchedCount = 2, provider = "ytmusic")
        api.playlists += FakePlaylistApi.Playlist(2, "From a Spotify link", trackCount = 4, matchedCount = 2, provider = "spotify_public")
        api.playlists += FakePlaylistApi.Playlist(3, "From Tidal", trackCount = 4, matchedCount = 2, provider = "tidal")
        api.playlists += FakePlaylistApi.Playlist(4, "From Spotify", trackCount = 4, matchedCount = 2, provider = "spotify")
        api.playlists += FakePlaylistApi.Playlist(5, "From a Tidal link", trackCount = 4, matchedCount = 2, provider = "tidal_public")
        setScreen()
        compose.waitForText("5 playlists")
        glyph("From YouTube", "public_link_badge").assertContentDescriptionEquals("Added by public link")
        glyph("From a Spotify link", "public_link_badge").assertContentDescriptionEquals("Added by public link")
        glyph("From a Tidal link", "public_link_badge").assertContentDescriptionEquals("Added by public link")
        compose.onAllNodes(hasTestTag("public_link_badge"), useUnmergedTree = true).assertCountEquals(3)
        // Each link's own service glyph and name, not the neutral one.
        glyph("From a Spotify link", "provider_glyph_spotify_public").assertContentDescriptionEquals("Spotify")
        glyph("From a Tidal link", "provider_glyph_tidal_public").assertContentDescriptionEquals("Tidal")
    }

    @Test
    fun aMusicAssistantPlaylistCarriesItsOwnGlyphAndName() {
        api.playlists += FakePlaylistApi.Playlist(1, "Probe mix", trackCount = 5, matchedCount = 5, provider = "music_assistant")
        setScreen()
        compose.waitForText("1 playlist")
        glyph("Probe mix", "provider_glyph_music_assistant").assertContentDescriptionEquals("Music Assistant")
    }

    @Test
    fun aLongPressOnTheGlyphShowsTheNameAndNeitherOpensNorStages() {
        api.playlists += FakePlaylistApi.Playlist(1, "Road Trip", trackCount = 4, matchedCount = 2, provider = "tidal")
        setScreen()
        compose.waitForText("1 playlist")
        glyph("Road Trip", "provider_glyph_tidal").performTouchInput { longClick() }
        compose.waitForText("Tidal") // the tooltip
        assertTrue("staged ${api.base.staged}", api.base.staged.isEmpty())
        compose.onAllNodes(hasTestTag("playlist_entries")).assertCountEquals(0)
    }

    @Test
    fun whereItWasImportedFromShowsUnlessTheSharedByMarkAlreadySaysSo() {
        api.playlists += FakePlaylistApi.Playlist(1, "Imported", trackCount = 4, matchedCount = 2, inferredOrigin = "tidal")
        api.playlists += FakePlaylistApi.Playlist(2, "Attributed", trackCount = 4, matchedCount = 1, inferredOrigin = "spotify", goldenOwnerUsername = "bob")
        setScreen()
        compose.waitForText("2 playlists")
        glyph("Imported", "provider_glyph_tidal")
            .assertContentDescriptionEquals("Its tracks closely match a Tidal playlist — likely imported into Roon from there.")
        compose.onAllNodes(hasTestTag("provider_glyph_spotify"), useUnmergedTree = true).assertCountEquals(0) // "shared by bob" says it
        glyph("Attributed", "provider_glyph_roon").assertIsDisplayed()
    }

    @Test
    fun theDetailShowsTheGlyphBeforeItsNameOnItsFirstLine() {
        // A name that wraps at 320 dp, 1.3x, French: the glyph must sit on its
        // first line, not centred against the whole block.
        val name = "Road trip across the Pyrenees, summer edition"
        api.playlists += FakePlaylistApi.Playlist(1, name, trackCount = 4, matchedCount = 2, provider = "tidal")
        setScreen(widthDp = NARROWEST_PHONE_DP, fontScale = LARGER_FONT_SCALE, french = true)
        compose.waitForText(name)
        compose.onNodeWithText(name).performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { compose.onAllNodes(hasTestTag("playlist_entries")).fetchSemanticsNodes().isNotEmpty() }
        val glyph = compose.onNode(hasTestTag("provider_glyph_tidal"), useUnmergedTree = true)
            .assertContentDescriptionEquals("Tidal").assertIsDisplayed().getUnclippedBoundsInRoot()
        val title = compose.onNode(hasText(name) and hasAnyAncestor(hasTestTag("playlist_summary")), useUnmergedTree = true)
        val box = title.getUnclippedBoundsInRoot()
        val layout = layoutOf(name)
        assertTrue("the name should wrap for this test to mean anything", layout.lineCount > 1)
        val firstLineBottom = box.top + with(compose.density) { layout.getLineBottom(0).toDp() }
        assertTrue("glyph at $glyph, name at $box", glyph.right <= box.left)
        assertTrue("glyph at ${glyph.top}..${glyph.bottom}, first line ${box.top}..$firstLineBottom",
            glyph.top >= box.top && glyph.bottom <= firstLineBottom)
        title.assert(isHeading()) // the screen's title now, for TalkBack
    }

    /** The glyph with [tag] in the list row -- the clickable one -- of the
     * playlist titled [title]. The row merges its children, so this reads the
     * unmerged tree. */
    private fun glyph(title: String, tag: String) =
        compose.onNode(hasTestTag(tag) and hasAnyAncestor(hasClickAction() and hasAnyDescendant(hasText(title))), useUnmergedTree = true)

    /** Ties on purpose: two at 100 %, two with 120 tracks, two with 18. */
    private fun stageSortPlaylists() {
        api.playlists += FakePlaylistApi.Playlist(12, "Road trip mix", trackCount = 41, matchedCount = 38)
        api.playlists += FakePlaylistApi.Playlist(30, "Sunday morning", trackCount = 120, matchedCount = 60)
        api.playlists += FakePlaylistApi.Playlist(7, "Focus", trackCount = 18, matchedCount = 18)
        api.playlists += FakePlaylistApi.Playlist(41, "kids' songs", trackCount = 18, matchedCount = 3)
        api.playlists += FakePlaylistApi.Playlist(25, "Night drive", trackCount = 120, matchedCount = 40)
        api.playlists += FakePlaylistApi.Playlist(3, "Workout", trackCount = 75, matchedCount = 75)
        api.playlists += FakePlaylistApi.Playlist(50, "Empty", trackCount = 0, matchedCount = 0)
    }

    private val sortOrders = mapOf(
        // 100, 100 (by name), 93, 50, 33, 17, then the empty one at 0.
        PlaylistSort.AVAILABILITY to listOf("Focus", "Workout", "Road trip mix", "Sunday morning", "Night drive", "kids' songs", "Empty"),
        // 120, 120 (by name), 75, 41, 18, 18 (by name, ignoring case), 0.
        PlaylistSort.MOST_TRACKS to listOf("Night drive", "Sunday morning", "Workout", "Road trip mix", "Focus", "kids' songs", "Empty"),
        PlaylistSort.NEWEST to listOf("Empty", "kids' songs", "Sunday morning", "Night drive", "Road trip mix", "Focus", "Workout"),
        // Case doesn't count: kids' songs sits between Focus and Night drive.
        PlaylistSort.NAME to listOf("Empty", "Focus", "kids' songs", "Night drive", "Road trip mix", "Sunday morning", "Workout"),
    )

    private fun shownTitles() = vm.state.value.playlists.map { it.title }

    private fun waitForList() = compose.waitUntil(timeoutMillis = UI_WAIT_MS) { vm.state.value.playlists.size == 7 }

    @Test
    fun eachSortOrdersTheListTiesByName() {
        stageSortPlaylists()
        setScreen()
        waitForList()
        assertEquals("availability is the default", sortOrders.getValue(PlaylistSort.AVAILABILITY), shownTitles())
        for ((sort, titles) in sortOrders) {
            compose.runOnIdle { vm.sorting.by(sort) }
            assertEquals(sort.name, titles, shownTitles())
        }
    }

    @Test
    fun tiesGoByNameWhateverOrderTheServerSends() {
        // The server lists by title, which would hide a missing tie-break: so
        // the same playlists, read through the real client, are sorted reversed.
        stageSortPlaylists()
        val reversed = runBlocking {
            AppApiPlaylistClient(context, server.url("/").toString(), "test-token").playlists()
        }.reversed()
        for ((sort, titles) in sortOrders) {
            assertEquals(sort.name, titles, sorted(reversed, sort).map { it.title })
        }
    }

    @Test
    fun theMenuSortsTheListOnScreen() {
        stageSortPlaylists()
        setScreen()
        waitForList()
        compose.onNodeWithContentDescription("Sort").performClick()
        compose.onNodeWithText("Most tracks").performClick()
        compose.waitForIdle()
        val tops = listOf("Night drive", "Sunday morning", "Workout").map {
            compose.onNode(hasText(it), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
        }
        assertEquals("the rows on screen follow the sort", tops.sorted(), tops)
    }

    @Test
    fun theChoiceSurvivesARestartAndARefresh() {
        runBlocking { Prefs.setPlaylistSort(context, "") }
        stageSortPlaylists()
        setScreen(sortStore = SortStore.prefs(context))
        waitForList()
        compose.runOnIdle { vm.sorting.by(PlaylistSort.NAME) }
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { runBlocking { Prefs.playlistSort(context).first() } == "NAME" }
        // A refresh with a new playlist keeps the order rather than jumping back.
        api.playlists += FakePlaylistApi.Playlist(60, "Alpha", trackCount = 5, matchedCount = 5)
        compose.runOnIdle { vm.reenter() }
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { vm.state.value.playlists.size == 8 }
        assertEquals(listOf("Alpha") + sortOrders.getValue(PlaylistSort.NAME), shownTitles())
        // The next start reads it back.
        val next = PlaylistsViewModel(
            AppApiPlaylistClient(context, server.url("/").toString(), "test-token"),
            AppApiClient(context, server.url("/").toString(), "test-token"),
            shared, sortStore = SortStore.prefs(context),
        )
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { next.state.value.playlists.size == 8 && next.state.value.sort == PlaylistSort.NAME }
        assertEquals(listOf("Alpha") + sortOrders.getValue(PlaylistSort.NAME), next.state.value.playlists.map { it.title })
        runBlocking { Prefs.setPlaylistSort(context, "") }
    }

    @Test
    fun anEditKeepsTheChosenOrder() {
        stageSortPlaylists()
        setScreen(wide = true, target = 12)
        waitForList()
        compose.runOnIdle { vm.sorting.by(PlaylistSort.NAME) }
        compose.runOnIdle { vm.toggleShared() }
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) {
            vm.state.value.playlists.firstOrNull { it.id == 12L }?.shared == true && !vm.state.value.sharingBusy
        }
        assertEquals(sortOrders.getValue(PlaylistSort.NAME), shownTitles())
    }

    @Test
    fun aNewPlaylistKeepsTheChosenOrder() {
        stageSortPlaylists()
        setScreen(wide = true, level = APP_API_PLAYLIST_WRITES)
        waitForList()
        compose.runOnIdle { vm.sorting.by(PlaylistSort.NAME) }
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { vm.state.value.canWrite }
        compose.runOnIdle { vm.create("Alpha") }
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { vm.state.value.playlists.size == 8 }
        assertEquals(listOf("Alpha") + sortOrders.getValue(PlaylistSort.NAME), shownTitles())
    }

    @Test
    fun theSortIsHiddenWhileAPlaylistIsOpenOnAPhone() {
        api.playlists += FakePlaylistApi.Playlist(1, "Road Trip", trackCount = 2, matchedCount = 1)
        setScreen(target = 1)
        compose.waitForText("Road Trip")
        compose.onAllNodesWithContentDescription("Sort").assertCountEquals(0)
    }

    @Test
    fun theTopBarHoldsItsIconsAtTheNarrowestWidthAndLargestFont() {
        setScreen(widthDp = NARROWEST_PHONE_DP, fontScale = LARGEST_FONT_SCALE)
        compose.waitForText("0 playlists")
        val bar = compose.onNodeWithTag("playlists_top_bar").getUnclippedBoundsInRoot()
        for (icon in listOf("Sort", "Basket")) {
            val b = compose.onNodeWithContentDescription(icon).getUnclippedBoundsInRoot()
            assertTrue("$icon ends inside the bar", b.right <= bar.right)
            compose.onNodeWithContentDescription(icon).assertIsDisplayed()
        }
        compose.onNode(hasText("Trobar") and hasAnyAncestor(hasTestTag("playlists_top_bar"))).assertIsDisplayed()
    }

    @Test
    fun theHeaderIsTheOneEveryTabHas() {
        setScreen()
        compose.waitForText("0 playlists")
        compose.onNode(hasText("Trobar") and hasAnyAncestor(hasTestTag("playlists_top_bar"))).assertIsDisplayed()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) {
            compose.onAllNodes(hasContentDescription("Sync to Pixel")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Basket").assertIsDisplayed()
    }

    @Test
    fun aPlaylistOpensOnItsEntriesWithTheUnmatchedOnesMarked() {
        api.playlists += FakePlaylistApi.Playlist(1, "Road Trip", trackCount = 3, matchedCount = 2)
        api.entries[1] = listOf(
            PlaylistEntry(1, "A", "One", "B", matched = true),
            PlaylistEntry(2, "C", "Two", null, matched = false),
            PlaylistEntry(3, "D", "Three", "E", matched = true),
        )
        setScreen()
        compose.waitForText("Road Trip")
        compose.onNodeWithText("Road Trip").performClick()
        compose.waitForText("Two")
        compose.onNodeWithText("A · B").assertIsDisplayed()
        compose.onNodeWithText("C").assertIsDisplayed() // no album: the artist alone
        compose.onAllNodesWithText("Not in the library").assertCountEquals(1)
        // A drill-down on a phone: the playlist's name, on the page, and a way back.
        compose.onNode(hasText("Road Trip") and hasAnyAncestor(hasTestTag("playlist_summary")), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForText("1 playlist")
    }

    @Test
    fun refreshStartsASyncAndFollowsItUntilItStops() {
        api.playlists += FakePlaylistApi.Playlist(1, "Road Trip", trackCount = 4, matchedCount = 2)
        api.syncAdds += FakePlaylistApi.Playlist(2, "New Arrivals", trackCount = 2, matchedCount = 2)
        setScreen()
        compose.waitForText("Refresh playlists")
        compose.onNodeWithText("Refresh playlists").performClick()
        compose.waitForText("Syncing…")
        compose.waitForText("New Arrivals") // read while following, with no refresh of its own
        compose.waitForText("Refresh playlists") // stopped: the button is back
        assertEquals(1, api.starts.get())
    }

    @Test
    fun aSyncAlreadyRunningIsFollowedNotReportedAsAFailure() {
        api.runningElsewhere(polls = 2)
        api.startAnswer = MockResponse().setResponseCode(409).setBody("""{"error": "A playlist sync is already running."}""")
        setScreen()
        compose.waitForText("Syncing…") // found running at open: followed
        compose.waitForText("Refresh playlists")
        // Started elsewhere just before the tap: refused, and followed instead.
        api.runningElsewhere(polls = 2)
        compose.onNodeWithText("Refresh playlists").performClick()
        compose.waitForText("Refresh playlists")
        compose.onAllNodesWithText("already running", substring = true).assertCountEquals(0)
    }

    @Test
    fun aSyncThatCannotStartSaysWhy() {
        api.startAnswer = MockResponse().setResponseCode(400).setBody("""{"error": "No playlist provider is configured."}""")
        setScreen()
        compose.waitForText("Refresh playlists")
        compose.onNodeWithText("Refresh playlists").performClick()
        compose.waitForText("No playlist provider is configured.", substring = true)
    }

    @Test
    fun noPlaylistsSaysHowTheyArrive() {
        setScreen()
        compose.waitForText("No playlists yet", substring = true)
    }

    private companion object {
        const val NARROWEST_PHONE_DP = 320
        const val LARGEST_FONT_SCALE = 2f
        const val LARGER_FONT_SCALE = 1.3f // where the top-bar title was first cut off
        const val PART_OF_A_FLIGHT_MS = 100L
        const val PAST_A_DOUBLE_TAP_MS = 1_000L
        const val SETTLE_MS = 1_500L
        const val UNDO_GONE_MS = 2_000L
    }
}
