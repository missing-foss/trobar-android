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
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
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
    ) {
        val pairing = Prefs.Pairing(serverUrl = server.url("/").toString(), token = "test-token")
        val shared = testAppState(context, pairing)
        val vm = PlaylistsViewModel(
            AppApiPlaylistClient(context, pairing.serverUrl, pairing.token),
            AppApiClient(context, pairing.serverUrl, pairing.token),
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
    }
}
