// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
// The Library screen against the in-memory App API: search and sort on
// the phone, a round picture on every artist row, an artist's albums and
// similar artists loaded on selection, list or grid per the profile, the
// albums before the similar artists and those in a strip every entry of
// which is reachable whole, and staging by double-tap: the server's
// type/target vocabulary, the header's device, the cover's flight, the
// badge moving on the server's answer, and a single tap opening the album:
// its details, its tracklist, and one track going on a playlist.

package org.missing_foss.trobar

import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
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

@RunWith(AndroidJUnit4::class)
class LibraryScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: MockWebServer
    private lateinit var api: FakeAppApi
    private lateinit var shared: AppState
    private var animatorScale: String? = null

    @Before
    fun startServer() {
        runBlocking {
            Prefs.setNetworkMode(context, Prefs.NETWORK_ANY)
            Prefs.clearHeaderDevice(context)
            Prefs.setAppApi(context, APP_API_ALBUM_TRACKS)
        }
        java.io.File(context.filesDir, "library_artists.json").delete()
        api = FakeAppApi()
        api.artist("Nebula Drift", Triple("Slow Collapse", 2021, 9), Triple("Drift", 2018, 7), Triple("Echoes", null, 5))
        api.artist("Aphelion", Triple("Parallax", 2022, 9), Triple("Event Horizon", 2019, 11))
        api.artist("Cinder & Ash", Triple("Emberwake", 2020, 10))
        api.similar["Aphelion"] = listOf("Nebula Drift")
        server = MockWebServer()
        server.dispatcher = api
        server.start()
    }

    @After
    fun stopServer() {
        api.holdStaging?.countDown()
        server.shutdown()
        animatorScale?.let(::putAnimatorScale)
    }

    private fun setScreen(
        fontScale: Float? = null, wide: Boolean = false, widthDp: Int? = null, target: LibraryTarget? = null,
    ) {
        val pairing = Prefs.Pairing(serverUrl = server.url("/").toString(), token = "test-token")
        val shared = testAppState(context, pairing)
        this.shared = shared
        target?.let(shared::openInLibrary)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale ?: density.fontScale),
                LocalWindowWidthSizeClass provides if (wide) WindowWidthSizeClass.Expanded else WindowWidthSizeClass.Compact,
            ) {
                MaterialTheme {
                    Box(if (widthDp != null) Modifier.requiredWidth(widthDp.dp) else Modifier) {
                        LibraryScreen(pairing = pairing, onOpenBasket = {}, shared = shared)
                    }
                }
            }
        }
    }

    private fun openAphelion() {
        compose.waitForText("Aphelion")
        compose.onNodeWithText("Aphelion").performClick()
        compose.waitForText("Parallax")
    }

    private fun doubleTap(album: String) = compose.onNodeWithText(album).performTouchInput { doubleClick() }

    private fun badge(count: Int) = hasContentDescription("Basket") and hasText(count.toString()) and hasAnyAncestor(hasTestTag("library_top_bar"))

    /** Gives a write that should not happen time to happen: the double-tap
     * window runs out on the test's clock -- which only then makes a tap a
     * single tap -- then a round trip to the fake in real time. */
    private fun settle() {
        compose.mainClock.advanceTimeBy(PAST_A_DOUBLE_TAP_MS)
        Thread.sleep(SETTLE_MS)
        compose.waitForIdle()
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command))
            .use { it.readBytes().decodeToString() }

    /** The system's animator scale; "null" is the value unset, which is how the emulator ships. */
    private fun putAnimatorScale(value: String) {
        shell(if (value == "null") "settings delete global animator_duration_scale" else "settings put global animator_duration_scale $value")
    }

    private fun inStrip(name: String, unmerged: Boolean = false): SemanticsNodeInteraction =
        compose.onNode(hasText(name) and hasAnyAncestor(hasTestTag(STRIP)), useUnmergedTree = unmerged)

    private fun byAlbumArtist() {
        runBlocking { Prefs.setAppApi(context, APP_API_ALBUM_ARTISTS) }
        api.albumArtistRows += JSONObject().put("artist", "Aphelion").put("track_count", 11).put("album_count", 1)
            .put("appears_on_count", 1).put("albums", org.json.JSONArray(listOf("Parallax")))
        api.albumArtistRows += JSONObject().put("artist", "Mara Ostrom").put("track_count", 0).put("album_count", 0)
            .put("appears_on_count", 2).put("albums", org.json.JSONArray())
        api.albumArtistRows += JSONObject().put("artist", "Various Artists").put("track_count", 14).put("album_count", 1)
            .put("appears_on_count", 0).put("albums", org.json.JSONArray(listOf("Night Drive")))
        api.albumArtistAlbums["Aphelion"] = listOf(Triple("Parallax", 2022, 9))
        api.albumArtistAlbums["Various Artists"] = listOf(Triple("Night Drive", 2020, 3))
        api.appearsOn["Aphelion"] = listOf(
            JSONObject().put("album", "Night Drive").put("album_artist", "Various Artists").put("year", 2020).put("track_count", 2),
        )
        api.albumTracks["Various Artists||Night Drive"] = listOf(
            track(1, "Theirs One", 1, 1), track(2, "Someone Else", 1, 2, artist = "Mara Ostrom"), track(3, "Theirs Two", 1, 3),
        )
    }

    private fun openAppearsOn() {
        byAlbumArtist()
        setScreen()
        openAphelion()
        compose.onNodeWithTag("album_list").performScrollToNode(hasTestTag("appears_on"))
        compose.onNodeWithText("Night Drive").performClick()
        settle()
        compose.waitForText("Theirs One")
    }

    @Test
    fun atLevelSixTheListCountsWhatThePageLists() {
        byAlbumArtist()
        setScreen()
        compose.waitForText("Various Artists")
        compose.onNodeWithText("1 album · appears on 1").assertExists()
        compose.onNodeWithText("appears on 2").assertExists()
    }

    @Test
    fun belowLevelSixTheLibraryIsWhatItWas() {
        byAlbumArtist()
        runBlocking { Prefs.setAppApi(context, APP_API_ALBUM_TRACKS) }
        setScreen()
        compose.waitForText("Nebula Drift")
        compose.onAllNodesWithText("Various Artists").assertCountEquals(0)
        compose.onNodeWithText("Aphelion").performClick()
        compose.waitForText("Event Horizon")
        compose.onAllNodes(hasTestTag("appears_on")).assertCountEquals(0)
    }

    private fun fromHomeOnAGuestsCard() {
        byAlbumArtist()
        api.albumTracks["Aphelion||Night Drive"] = listOf(track(1, "Theirs One", 1, 1), track(3, "Theirs Two", 1, 3))
        setScreen(target = LibraryTarget("Aphelion", "Night Drive", details = true))
        compose.waitForText("Someone Else")
    }

    @Test
    fun aGuestsCardFromHomeOpensTheWholeAlbumWithTheirTracksMarked() {
        fromHomeOnAGuestsCard()
        compose.onNodeWithTag("album_only_theirs").assertTextEquals("Add to basket adds only Aphelion's tracks: 2 of 3.")
        compose.onAllNodes(hasTestTag("album_track_theirs")).assertCountEquals(2)
        compose.onNodeWithText("Various Artists").assertExists()
    }

    @Test
    fun aGuestsCardFromHomeHasItsYear() {
        fromHomeOnAGuestsCard()
        compose.onNodeWithTag("album_facts").assertTextEquals("2020 · 3 tracks")
    }

    @Test
    fun anAlbumFromAlsoAppearsOnHasItsYear() {
        openAppearsOn()
        compose.onNodeWithTag("album_facts").assertTextEquals("2020 · 3 tracks")
    }

    @Test
    fun yourOwnAlbumStaysYoursWhenAnAppearsOnRowSharesItsName() {
        byAlbumArtist()
        api.albumArtistAlbums["Aphelion"] = listOf(Triple("Greatest Hits", 2021, 2))
        api.appearsOn["Aphelion"] = listOf(
            JSONObject().put("album", "Greatest Hits").put("album_artist", "Various Artists").put("year", 2010).put("track_count", 1),
        )
        api.albumTracks["Aphelion||Greatest Hits"] = listOf(track(1, "Mine One", 1, 1), track(2, "Mine Two", 1, 2))
        api.albumTracks["Various Artists||Greatest Hits"] = listOf(track(3, "Not Mine", 1, 1))
        setScreen()
        compose.waitForText("Aphelion")
        compose.onNodeWithText("Aphelion").performClick()
        compose.waitForText("Greatest Hits")
        compose.onAllNodesWithText("Greatest Hits")[0].performClick()
        settle()
        compose.waitForText("Mine One")
        compose.onAllNodes(hasTestTag("album_track_theirs")).assertCountEquals(0)
        compose.onAllNodes(hasTestTag("album_only_theirs")).assertCountEquals(0)
        compose.onAllNodesWithText("Not Mine").assertCountEquals(0)
    }

    @Test
    fun theCoverViewListsAlsoAppearsOnToo() {
        byAlbumArtist()
        api.coverViewMode = "grid"
        setScreen()
        openAphelion()
        compose.onNodeWithTag("album_grid").performScrollToNode(hasTestTag("appears_on"))
        compose.onNodeWithText("Night Drive").assertExists()
    }

    @Test
    fun aServerErrorOnAppearsOnLeavesTheArtistsPageWhole() {
        byAlbumArtist()
        api.failAppearsOn = true
        setScreen()
        openAphelion()
        settle()
        compose.onAllNodes(hasTestTag("appears_on")).assertCountEquals(0)
        compose.onNodeWithText("Parallax").assertExists()
    }

    @Test
    fun aDroppedConnectionOnAppearsOnLeavesTheArtistsPageWhole() {
        byAlbumArtist()
        api.dropAppearsOn = true
        setScreen()
        openAphelion()
        settle()
        compose.onAllNodes(hasTestTag("appears_on")).assertCountEquals(0)
        compose.onNodeWithText("Parallax").assertExists()
    }

    @Test
    fun theSavedListKeepsTheAppearsOnCount() {
        val cache = LibraryCache(context, serverUrl)
        cache.write(listOf(LibraryArtist("Mara", 0, 0, emptyList(), appearsOnCount = 2)), 1L)
        assertEquals(2, cache.read()!!.artists.single().appearsOnCount)
    }

    @Test
    fun anAlbumFromAlsoAppearsOnShowsWholeWithTheirTracksMarked() {
        openAppearsOn()
        compose.onNodeWithText("Various Artists").assertExists()
        compose.onNodeWithTag("album_only_theirs").assertTextEquals("Add to basket adds only Aphelion's tracks: 2 of 3.")
        compose.onAllNodes(hasTestTag("album_track_theirs")).assertCountEquals(2)
        compose.onAllNodes(hasTestTag("album_track")).assertCountEquals(1)
    }

    @Test
    fun addToBasketFromAlsoAppearsOnStagesOnlyTheirTracks() {
        openAppearsOn()
        compose.onNodeWithTag("album_add_to_basket").performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.staged.size == 1 }
        assertEquals(Triple("album", "Aphelion||Night Drive", listOf(1L)), api.staged.single())
    }

    @Test
    fun aDoubleTapOnAlsoAppearsOnStagesOnlyTheirTracks() {
        byAlbumArtist()
        setScreen()
        openAphelion()
        compose.onNodeWithTag("album_list").performScrollToNode(hasTestTag("appears_on"))
        compose.onNodeWithText("Night Drive").performTouchInput { doubleClick() }
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.staged.size == 1 }
        assertEquals(Triple("album", "Aphelion||Night Drive", listOf(1L)), api.staged.single())
    }

    @Test
    fun theAlbumArtistOnAlsoAppearsOnOpensThatArtist() {
        byAlbumArtist()
        setScreen()
        openAphelion()
        compose.onNodeWithTag("album_list").performScrollToNode(hasTestTag("appears_on"))
        compose.onNodeWithText("Various Artists").performClick()
        // Parallax is Aphelion's: gone, the page is the album artist's own.
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) {
            compose.onAllNodesWithText("Parallax").fetchSemanticsNodes().isEmpty()
        }
        compose.waitForText("Night Drive")
        compose.onNodeWithText("Various Artists").assert(isHeading())
        compose.onAllNodes(hasTestTag("appears_on")).assertCountEquals(0)
    }

    private val serverUrl get() = server.url("/").toString()

    /** What a first run read while online, from [from]. */
    private fun saveTheList(from: String = serverUrl) {
        LibraryCache(context, from).write(
            listOf(LibraryArtist("Saved Artist", 3, 1, listOf("Saved Album"))),
            System.currentTimeMillis() - 2 * 60 * 60 * 1000,
        )
    }

    @Test
    fun offlineTheSavedListShowsWithItsAgeAndRetryReadsAgain() {
        saveTheList()
        api.dropArtists = true
        setScreen()
        compose.waitForText("Saved Artist")
        compose.onNodeWithTag("library_offline").assertIsDisplayed()
        compose.waitForText("Offline · last updated 2 hours ago")
        api.dropArtists = false
        compose.onNodeWithText("Retry").performClick()
        compose.waitForText("Aphelion")
        compose.onAllNodes(hasTestTag("library_offline")).assertCountEquals(0)
    }

    @Test
    fun aListReadOnlineIsSavedForLater() {
        setScreen()
        compose.waitForText("Aphelion")
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) {
            LibraryCache(context, serverUrl).read()?.artists?.any { it.artist == "Aphelion" && it.albums != null } == true
        }
    }

    @Test
    fun withNoSavedListAFailureStillSaysSo() {
        api.dropArtists = true
        setScreen()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) {
            compose.onAllNodes(hasTestTag("artist_search")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodes(hasTestTag("library_offline")).assertCountEquals(0)
    }

    @Test
    fun anotherServersListIsNotShownAsThisOnes() {
        saveTheList(from = "https://another.example/")
        api.dropArtists = true
        setScreen()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) {
            compose.onAllNodes(hasTestTag("artist_search")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText("Saved Artist").assertCountEquals(0)
    }

    @Test
    fun aServerErrorStillShowsTheErrorNotTheSavedList() {
        saveTheList()
        api.failArtists = true
        setScreen()
        compose.waitForText("down")
        compose.onAllNodesWithText("Saved Artist").assertCountEquals(0)
    }

    @Test
    fun aRefusedTokenStillShowsTheErrorNotTheSavedList() {
        saveTheList()
        api.refuseArtists = true
        setScreen()
        compose.waitForText("refused")
        compose.onAllNodesWithText("Saved Artist").assertCountEquals(0)
    }

    @Test
    fun unpairingRemovesTheSavedList() {
        saveTheList()
        runBlocking { SyncWorker.unpair(context) }
        assertEquals(null, LibraryCache(context, serverUrl).read())
    }

    @Test
    fun theSearchHasAClearButtonOnceThereIsSomethingToClear() {
        setScreen()
        compose.waitForText("Aphelion")
        compose.onAllNodes(hasContentDescription("Clear the search")).assertCountEquals(0)
        compose.onNodeWithTag("artist_search").performTextInput("zzz")
        compose.onNodeWithContentDescription("Clear the search").performClick()
        compose.waitForText("Aphelion")
    }

    @Test
    fun searchFiltersAndSortTogglesTheArtistList() {
        setScreen()
        compose.waitForText("3 artists")
        // Sorted by name: Aphelion, Cinder & Ash, Nebula Drift. Toggle: by album count, Nebula Drift (3) first.
        compose.onNodeWithText("Name").performClick()
        compose.onNodeWithText("Albums").assertIsDisplayed()
        compose.onNodeWithTag("artist_search").performTextInput("bul")
        compose.waitForText("1 artist") // only "Nebula Drift" contains it; the search is a substring, not a prefix
        compose.onAllNodesWithText("Aphelion").assertCountEquals(0)
        compose.onNodeWithText("Nebula Drift").assertIsDisplayed()
    }

    @Test
    fun anAlbumTitleFindsItsArtistAndSaysWhichAlbumAtTheLargestFontScale() {
        setScreen(fontScale = LARGEST_FONT_SCALE)
        compose.waitForText("3 artists")
        compose.onNodeWithTag("artist_search").performTextInput("collapse")
        compose.waitForText("1 artist") // Nebula Drift, for its album Slow Collapse
        compose.onNodeWithText("Nebula Drift").assertIsDisplayed()
        val line = compose.onNodeWithTag("album_hits", useUnmergedTree = true)
        line.assertTextEquals("Slow Collapse")
        val layouts = mutableListOf<TextLayoutResult>()
        line.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        assertEquals("the album line is one line", 1, layouts.single().lineCount)
        // A name hit carries no album line.
        compose.onNodeWithTag("artist_search").performTextClearance()
        compose.onNodeWithTag("artist_search").performTextInput("aphel")
        compose.waitForText("1 artist")
        compose.onAllNodesWithTag("album_hits", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun everyArtistRowShowsARoundPictureAndRowsKeepOneHeight() {
        api.artistImages += "Aphelion" // one with a picture, two without
        setScreen()
        compose.waitForText("3 artists")
        val pictures = hasTestTag("artist_picture")
        val loading = hasTestTag("image_loading") and hasAnyAncestor(pictures)
        val fallbacks = hasTestTag("image_fallback") and hasAnyAncestor(pictures)
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) {
            compose.onAllNodes(loading, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodes(fallbacks, useUnmergedTree = true).fetchSemanticsNodes().size == 2
        }
        val shown = compose.onAllNodes(pictures, useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
        assertEquals("a picture on every row", 3, shown.size)
        shown.forEach {
            assertEquals("square", it.width, it.height, 0.5f)
            assertEquals("one size", shown.first().height, it.height, 0.5f)
        }
        // A row with a picture and a row with the fallback are the same height.
        val rows = listOf("Aphelion", "Cinder & Ash", "Nebula Drift").map { compose.onNodeWithText(it).getUnclippedBoundsInRoot().height.value }
        rows.forEach { assertEquals("row height", rows.first(), it, SIZE_TOLERANCE_DP) }
        assertEquals(
            setOf("Aphelion" to "small", "Cinder & Ash" to "small", "Nebula Drift" to "small"),
            synchronized(api.artistImageAsked) { api.artistImageAsked.toSet() },
        )
    }

    @Test
    fun aReloadDoesNotLoseWhereAnotherTabAskedTheLibraryToOpen() {
        val pairing = Prefs.Pairing(serverUrl = server.url("/").toString(), token = "test-token")
        val state = testAppState(context, pairing)
        state.openInLibrary(LibraryTarget("Aphelion"))
        runBlocking { state.reload() }
        assertEquals(LibraryTarget("Aphelion"), state.state.value.libraryTarget)
    }

    @Test
    fun anotherTabCanOpenAnArtistPage() {
        setScreen(target = LibraryTarget("Aphelion"))
        compose.waitForText("Sync entire artist")
        compose.onNodeWithText("Aphelion").assertIsDisplayed()
        // Cleared, or the Library would reopen it over wherever the person went.
        assertEquals(null, shared.state.value.libraryTarget)
    }

    @Test
    fun anotherTabCanOpenAnAlbumFarDownAnArtistsPage() {
        val many = (1..60).map { Triple("Album $it", 2000 + it, 3) }.toTypedArray()
        api.artist("Many", *many)
        setScreen(target = LibraryTarget("Many", "Album 55"))
        compose.waitForText("Album 55")
        compose.onNodeWithText("Album 55").assertIsDisplayed()
        compose.onAllNodesWithText("Album 1").assertCountEquals(0)
    }

    @Test
    fun anAlbumNotOnThePageLeavesItAtTheTop() {
        val many = (1..60).map { Triple("Album $it", 2000 + it, 3) }.toTypedArray()
        api.artist("Many", *many)
        setScreen(target = LibraryTarget("Many", "Not here"))
        compose.waitForText("Album 1")
        compose.onNodeWithText("Sync entire artist").assertIsDisplayed()
    }

    @Test
    fun selectingAnArtistLoadsItsAlbumsAndSimilarArtists() {
        setScreen()
        compose.waitForText("Aphelion")
        compose.onNodeWithText("Aphelion").performClick()
        compose.waitForText("Parallax")
        compose.onNodeWithText("2022 · 9 tracks").assertIsDisplayed()
        compose.onNodeWithText("Event Horizon").assertIsDisplayed()
        compose.waitForText("Similar artists in your library")
        compose.onNodeWithText("Nebula Drift").assertIsDisplayed()
        compose.onNodeWithTag("album_list").assertIsDisplayed()
    }

    @Test
    fun anOpenedArtistsNameIsShownOnceOnAPhoneOnThePageNotInTheTopBar() {
        setScreen()
        openAphelion()
        // Once, on the page, where it has the full width; the top bar keeps
        // back and the icons only.
        compose.onAllNodesWithText("Aphelion").assertCountEquals(1)
        compose.onAllNodes(hasText("Aphelion") and hasAnyAncestor(hasTestTag("library_top_bar"))).assertCountEquals(0)
        compose.onNode(hasText("Aphelion") and hasAnyAncestor(hasTestTag("album_list"))).assertIsDisplayed()
            .assert(isHeading()) // the screen's title now, for TalkBack
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        compose.onNodeWithText("Sync entire artist").assertIsDisplayed()
    }

    @Test
    fun onAWideLayoutTheArtistsNameIsInThePaneWhereTheTopBarShowsTheWordmark() {
        setScreen(wide = true)
        openAphelion()
        compose.onAllNodes(hasText("Aphelion") and hasAnyAncestor(hasTestTag("library_top_bar"))).assertCountEquals(0)
        compose.onAllNodes(hasText("Aphelion") and hasAnyAncestor(hasTestTag("album_list"))).assertCountEquals(1)
    }

    @Test
    fun anOpenedArtistKeepsItsNameAndBackAndTheDestinationStaysInReach() {
        setScreen()
        openAphelion()
        compose.onNode(hasText("Aphelion") and hasAnyAncestor(hasTestTag("album_list"))).assertIsDisplayed() // on the page
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        compose.onNodeWithContentDescription("Sync to Pixel").assertIsDisplayed()
        compose.onAllNodesWithText("Trobar").assertCountEquals(0)
    }

    @Test
    fun theGridFollowsTheProfileViewMode() {
        api.coverViewMode = "grid"
        setScreen()
        compose.waitForText("Aphelion")
        compose.onNodeWithText("Aphelion").performClick()
        compose.waitForText("Parallax")
        compose.onNodeWithTag("album_grid").assertIsDisplayed()
        compose.onAllNodesWithTag("album_list").assertCountEquals(0)
    }

    @Test
    fun doubleTappingAnAlbumStagesItForTheHeadersDeviceAndTheBadgeGoesUp() {
        setScreen()
        openAphelion()
        doubleTap("Parallax")
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.staged.size == 1 }
        assertEquals(Triple("album", "Aphelion||Parallax", listOf(1L)), api.staged.single()) // this phone: nothing chosen
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { compose.onAllNodes(badge(1)).fetchSemanticsNodes().isNotEmpty() }
        // Another device chosen in the header: the next album goes there.
        compose.onNodeWithContentDescription("Sync to Pixel").performClick()
        compose.onNodeWithText("Car").performClick()
        doubleTap("Event Horizon")
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.staged.size == 2 }
        assertEquals(Triple("album", "Aphelion||Event Horizon", listOf(2L)), api.staged[1])
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { compose.onAllNodes(badge(2)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("staging sends nothing", api.sends.isEmpty())
    }

    @Test
    fun onATabletTheGridsCoversStayAboutAPhonesSize() {
        // Three columns in the tablet's album pane made each cover a third of it.
        api.coverViewMode = "grid"
        setScreen(wide = true, widthDp = LANDSCAPE_TABLET_DP)
        openAphelion()
        compose.onNodeWithTag("album_grid").assertIsDisplayed()
        val cover = compose.onNode(hasText("Parallax") and hasAnyAncestor(hasTestTag("album_grid"))).getUnclippedBoundsInRoot().width.value
        assertTrue("a cover is $cover dp wide", cover in PHONE_COVER_DP)
    }

    @Test
    fun doubleTappingACoverInTheGridStagesIt() {
        api.coverViewMode = "grid"
        setScreen()
        openAphelion()
        compose.onNodeWithTag("album_grid").assertIsDisplayed()
        doubleTap("Parallax")
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.staged.size == 1 }
        assertEquals(Triple("album", "Aphelion||Parallax", listOf(1L)), api.staged.single())
    }

    @Test
    fun aSingleTapOnAnAlbumOpensItAndStagesNothing() = singleTapOpens(grid = false)

    @Test
    fun aSingleTapOnACoverOpensItAndStagesNothing() = singleTapOpens(grid = true)

    private fun singleTapOpens(grid: Boolean) {
        if (grid) api.coverViewMode = "grid"
        twoDiscs()
        setScreen()
        openAphelion()
        compose.onNodeWithText("Parallax").performTouchInput { click() }
        settle()
        compose.onNodeWithTag("album_details").assertIsDisplayed()
        compose.waitForText("Singularity")
        assertTrue("a single tap wrote ${api.staged}", api.staged.isEmpty())
        compose.onAllNodes(hasTestTag(FLIGHT), useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodes(badge(1)).assertCountEquals(0)
    }

    private fun track(id: Long, title: String, disc: Int?, no: Int?, artist: String = "Aphelion", duration: Int? = 252) =
        JSONObject().put("id", id).put("title", title).put("disc_no", disc ?: JSONObject.NULL)
            .put("track_no", no ?: JSONObject.NULL).put("artist", artist).put("duration", duration ?: JSONObject.NULL)

    private fun twoDiscs() {
        api.albumTracks["Aphelion||Parallax"] = listOf(
            track(1, "Singularity", 1, 1), track(2, "Perihelion", 1, 2, artist = "Guest Star"), track(3, "Afterglow", 2, 1),
        )
    }

    private fun openParallax() {
        setScreen()
        openAphelion()
        compose.onNodeWithText("Parallax").performClick()
        settle()
        compose.waitForText("Singularity")
    }

    @Test
    fun anAlbumShowsItsYearDiscsAndAGuestsName() {
        twoDiscs()
        openParallax()
        compose.onNodeWithTag("album_facts").assertTextEquals("2022 · 3 tracks")
        compose.onNodeWithText("Parallax").assert(isHeading())
        compose.onNodeWithText("Disc 1").assertIsDisplayed()
        compose.onNodeWithText("Disc 2").assertIsDisplayed()
        compose.onNodeWithText("Guest Star").assertIsDisplayed()
        compose.onAllNodesWithText("4:12", useUnmergedTree = true).assertCountEquals(3)
        compose.onAllNodesWithText("Aphelion").assertCountEquals(1) // the album's artist, on no track
    }

    @Test
    fun aOneDiscAlbumHasNoDiscHeading() {
        api.albumTracks["Aphelion||Parallax"] = listOf(track(1, "Singularity", 1, 1), track(2, "Perihelion", 1, 2))
        openParallax()
        compose.onAllNodesWithText("Disc", substring = true).assertCountEquals(0)
    }

    @Test
    fun addToBasketStagesTheAlbumAndThenSaysItIsIn() {
        twoDiscs()
        openParallax()
        compose.onNodeWithTag("album_add_to_basket").assertTextEquals("Add to basket").performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.staged.size == 1 }
        assertEquals(Triple("album", "Aphelion||Parallax", listOf(1L)), api.staged.single())
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) {
            compose.onAllNodesWithText("In the basket").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun aTappedTrackGoesOnAPlaylistYouCanEditAndOnlyThose() {
        twoDiscs()
        api.playlists += Triple(11L, "Morning run", "trobar")
        api.playlists += Triple(12L, "From Subsonic", "subsonic")
        openParallax()
        compose.onNodeWithText("Perihelion").performClick()
        compose.waitForText("Morning run")
        compose.onAllNodesWithText("From Subsonic").assertCountEquals(0)
        compose.onNodeWithText("Morning run").performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.appended.size == 1 }
        assertEquals(11L to listOf(2L), api.appended.single())
        compose.waitForText("Added to “Morning run”")
    }

    @Test
    fun someoneElsesPlaylistIsNotOfferedToANonAdmin() {
        twoDiscs()
        api.playlists += Triple(11L, "Mine", "trobar")
        api.playlists += Triple(13L, "Someone else's", "trobar")
        api.othersPlaylists += 13L
        openParallax()
        compose.onNodeWithText("Perihelion").performClick()
        compose.waitForText("Mine")
        compose.onAllNodesWithText("Someone else's").assertCountEquals(0)
    }

    @Test
    fun anAdminIsOfferedSomeoneElsesPlaylist() {
        twoDiscs()
        api.isAdmin = true
        api.playlists += Triple(13L, "Someone else's", "trobar")
        api.othersPlaylists += 13L
        openParallax()
        compose.onNodeWithText("Perihelion").performClick()
        compose.waitForText("Someone else's")
    }

    @Test
    fun systemBackClosesTheAlbumAndShowsItsArtist() {
        twoDiscs()
        openParallax()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForText("Event Horizon")
        compose.onAllNodes(hasTestTag("album_details")).assertCountEquals(0)
    }

    @Test
    fun aTracklistThatFailsToLoadClosesTheAlbumAndSaysWhy() {
        api.failAlbumTracks = true
        setScreen()
        openAphelion()
        compose.onNodeWithText("Parallax").performClick()
        settle()
        compose.waitForText("no tracks today", substring = true)
        compose.onAllNodes(hasTestTag("album_details")).assertCountEquals(0)
        compose.onNodeWithText("Event Horizon").assertIsDisplayed()
    }

    @Test
    fun theWholeAlbumGoesOnAPlaylist() {
        twoDiscs()
        api.playlists += Triple(11L, "Morning run", "trobar")
        openParallax()
        compose.onNodeWithTag("album_add_to_playlist").performClick()
        compose.waitForText("Morning run")
        compose.onNodeWithText("Morning run").performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.appendedAlbums.size == 1 }
        val sent = JSONObject(api.appendedAlbums.single())
        assertEquals("Aphelion" to "Parallax", sent.getString("artist") to sent.getString("album"))
        compose.waitForText("Added to “Morning run”")
    }

    @Test
    fun aNewPlaylistForAnAlbumIsNamedAfterIt() {
        twoDiscs()
        openParallax()
        compose.onNodeWithTag("album_add_to_playlist").performClick()
        compose.waitForText("New playlist…")
        compose.onNodeWithText("New playlist…").performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.appendedAlbums.size == 1 }
        assertEquals(listOf("Parallax"), api.created.toList())
    }

    @Test
    fun addToPlaylistIsNotOfferedBelowLevelFive() {
        twoDiscs()
        runBlocking { Prefs.setAppApi(context, APP_API_ALBUM_TRACKS - 1) }
        setScreen()
        openAphelion()
        compose.onNodeWithText("Parallax").performClick()
        settle()
        compose.onNodeWithTag("album_details").assertIsDisplayed()
        compose.onAllNodes(hasTestTag("album_add_to_playlist")).assertCountEquals(0)
    }

    @Test
    fun addToPlaylistFromAlsoAppearsOnSendsTheArtistsOwnKey() {
        api.playlists += Triple(11L, "Morning run", "trobar")
        openAppearsOn()
        compose.onNodeWithTag("album_add_to_playlist").performClick()
        compose.waitForText("Morning run")
        compose.onNodeWithText("Morning run").performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.appendedAlbums.size == 1 }
        val sent = JSONObject(api.appendedAlbums.single())
        assertEquals("Aphelion" to "Night Drive", sent.getString("artist") to sent.getString("album"))
    }

    @Test
    fun aNewPlaylistIsNamedAfterTheTrack() {
        twoDiscs()
        openParallax()
        compose.onNodeWithText("Afterglow").performClick()
        compose.waitForText("New playlist…")
        compose.onNodeWithText("New playlist…").performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.appended.size == 1 }
        assertEquals(listOf("Afterglow"), api.created.toList())
        assertEquals(FakeAppApi.NEW_PLAYLIST_ID to listOf(3L), api.appended.single())
    }

    @Test
    fun belowLevelFiveThereIsNoTracklistAndTheAlbumStillStages() {
        twoDiscs()
        runBlocking { Prefs.setAppApi(context, APP_API_ALBUM_TRACKS - 1) }
        setScreen()
        openAphelion()
        compose.onNodeWithText("Parallax").performClick()
        settle()
        compose.onNodeWithTag("album_update_server").assertIsDisplayed()
        compose.onAllNodesWithText("Singularity").assertCountEquals(0)
        compose.onNodeWithTag("album_add_to_basket").performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.staged.size == 1 }
    }

    @Test
    fun backFromAnAlbumIsItsArtistAndOnAWideLayoutToo() {
        twoDiscs()
        setScreen(wide = true)
        openAphelion()
        compose.onNodeWithText("Parallax").performClick()
        settle()
        compose.waitForText("Singularity")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForText("Event Horizon")
        compose.onAllNodes(hasTestTag("album_details")).assertCountEquals(0)
    }

    @Test
    fun anotherTabCanOpenAnAlbumsDetails() {
        twoDiscs()
        setScreen(target = LibraryTarget("Aphelion", "Parallax", details = true))
        compose.waitForText("Singularity")
        compose.onNodeWithTag("album_details").assertIsDisplayed()
    }

    @Test
    fun theDetailsHoldAtTheLargestFontScaleOnANarrowPhone() {
        twoDiscs()
        setScreen(fontScale = LARGEST_FONT_SCALE, widthDp = NARROW_DP)
        openAphelion()
        compose.onNodeWithText("Parallax").performClick()
        settle()
        compose.waitForText("Singularity")
        compose.onNodeWithTag("album_add_to_basket").assertIsDisplayed()
        val width = compose.onNodeWithTag("album_details").getUnclippedBoundsInRoot().width
        assertEquals(NARROW_DP.toFloat(), width.value, SIZE_TOLERANCE_DP)
    }

    @Test
    fun theCoverFliesToTheBasketAndTheBadgeWaitsForTheServer() {
        val hold = CountDownLatch(1)
        api.holdStaging = hold
        setScreen()
        openAphelion()
        compose.mainClock.autoAdvance = false
        doubleTap("Parallax")
        // A second double-tap during the first flight is taken, not queued behind it.
        compose.mainClock.advanceTimeBy(PART_OF_A_FLIGHT_MS)
        doubleTap("Event Horizon")
        compose.mainClock.advanceTimeBy(PART_OF_A_FLIGHT_MS)
        compose.onAllNodes(hasTestTag(FLIGHT), useUnmergedTree = true).assertCountEquals(2)
        compose.mainClock.advanceTimeBy(LONGER_THAN_A_FLIGHT_MS)
        compose.onAllNodes(hasTestTag(FLIGHT), useUnmergedTree = true).assertCountEquals(0)
        // Landed, but the server has not answered: the badge has not moved.
        compose.onAllNodes(badge(1)).assertCountEquals(0)
        compose.onAllNodes(badge(2)).assertCountEquals(0)
        compose.mainClock.autoAdvance = true
        // Still on its way: the same album again is refused at once, never written twice.
        doubleTap("Parallax")
        compose.waitForText("Already in the basket for Pixel")
        hold.countDown()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { compose.onAllNodes(badge(2)).fetchSemanticsNodes().isNotEmpty() }
        settle()
        assertEquals(listOf("Aphelion||Event Horizon", "Aphelion||Parallax"), api.staged.map { it.second }.sorted())
    }

    @Test
    fun withAnimationsOffNothingFliesAndTheBadgeStillMoves() {
        animatorScale = shell("settings get global animator_duration_scale").trim()
        putAnimatorScale("0")
        setScreen()
        openAphelion()
        compose.mainClock.autoAdvance = false
        doubleTap("Parallax")
        compose.mainClock.advanceTimeBy(PART_OF_A_FLIGHT_MS)
        compose.onAllNodes(hasTestTag(FLIGHT), useUnmergedTree = true).assertCountEquals(0)
        compose.mainClock.autoAdvance = true
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { compose.onAllNodes(badge(1)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, api.staged.size)
    }

    @Test
    fun anAlbumAlreadyStagedForTheDeviceIsNotAddedAgainAndSaysSo() {
        api.stage(50, "Aphelion||Parallax", 1)
        setScreen()
        openAphelion()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { compose.onAllNodes(badge(1)).fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.autoAdvance = false
        doubleTap("Parallax")
        compose.mainClock.advanceTimeBy(PART_OF_A_FLIGHT_MS)
        compose.onAllNodes(hasTestTag(FLIGHT), useUnmergedTree = true).assertCountEquals(0) // no flight for a stage that is not one
        compose.mainClock.autoAdvance = true
        compose.waitForText("Already in the basket for Pixel")
        settle()
        assertTrue("wrote ${api.staged}", api.staged.isEmpty())
    }

    @Test
    fun anAlbumStagedForAnotherDeviceOnlyIsStagedForThisOneToo() {
        api.stage(50, "Aphelion||Parallax", 2) // the Car's, not this phone's
        setScreen()
        openAphelion()
        doubleTap("Parallax")
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.staged.size == 1 }
        assertEquals(Triple("album", "Aphelion||Parallax", listOf(1L)), api.staged.single())
        // One item on the server, now for both: the badge counts items, so it stays at one.
        assertEquals(listOf(2L, 1L), api.items.single().deviceIds)
        compose.onAllNodesWithText("Already in the basket", substring = true).assertCountEquals(0)
    }

    @Test
    fun aFailedStageShowsTheFailureAndTheBadgeWhatTheServerHolds() {
        api.failStagingAfter = 0
        setScreen()
        openAphelion()
        doubleTap("Parallax")
        compose.waitForText("Storage failed")
        assertTrue(api.items.isEmpty())
        compose.onAllNodes(badge(1)).assertCountEquals(0)
    }

    @Test
    fun syncEntireArtistStagesTheNameForTheHeadersDeviceWithNoSheet() {
        setScreen()
        openAphelion()
        compose.onNodeWithText("Sync entire artist").performClick()
        compose.waitForText("In the basket")
        assertEquals(listOf(Triple("artist", "Aphelion", listOf(1L))), api.staged.toList())
        assertTrue("staging sends nothing", api.sends.isEmpty())
        // Again: already there for this phone, so said, and not written.
        compose.onNodeWithText("In the basket").performClick()
        compose.waitForText("Already in the basket for Pixel")
        settle()
        assertEquals(1, api.staged.size)
    }

    @Test
    fun anAnswerThatIsNotJsonIsAnErrorNotACrash() {
        api.malformedAlbums = true
        setScreen()
        compose.waitForText("Aphelion")
        compose.onNodeWithText("Aphelion").performClick()
        compose.waitForText("Value <html>", substring = true) // org.json's own message for the garbage, shown in the snackbar
    }

    @Test
    fun theAlbumsComeFirstAndTheSimilarArtistsAfterThem() = albumsBeforeSimilarArtists()

    @Test
    fun theAlbumsComeFirstAndTheSimilarArtistsAfterThemInTheGrid() {
        api.coverViewMode = "grid"
        albumsBeforeSimilarArtists()
    }

    private fun albumsBeforeSimilarArtists() {
        setScreen()
        openAphelion()
        compose.waitForText(SIMILAR_LABEL)
        val header = compose.onNodeWithText("Sync entire artist").getUnclippedBoundsInRoot()
        val albums = listOf("Parallax", "Event Horizon").map { compose.onNodeWithText(it).getUnclippedBoundsInRoot() }
        val label = compose.onNodeWithText(SIMILAR_LABEL).getUnclippedBoundsInRoot()
        assertTrue("albums start at ${albums.minOf { it.top }}, header ends at ${header.bottom}", header.bottom <= albums.minOf { it.top })
        assertTrue("albums end at ${albums.maxOf { it.bottom }}, similar artists start at ${label.top}", albums.maxOf { it.bottom } <= label.top)
    }

    @Test
    fun everySimilarArtistIsReachableWholeAtTheLargestFontScale() {
        api.similar["Aphelion"] = LONG_SIMILAR
        setScreen(fontScale = LARGEST_FONT_SCALE)
        openAphelion()
        compose.onNodeWithTag("album_list").performScrollToNode(hasTestTag(STRIP))
        val widths = LONG_SIMILAR.map { name ->
            compose.onNodeWithTag(STRIP).performScrollToNode(hasText(name))
            inStrip(name).assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            inStrip(name, unmerged = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
            assertEquals("$name is on one line", 1, layouts.single().lineCount)
            inStrip(name).getUnclippedBoundsInRoot().width.value
        }
        // No entry is squeezed by its neighbours: every one keeps the same width.
        widths.forEach { assertEquals(widths.first(), it, SIZE_TOLERANCE_DP) }
    }

    @Test
    fun aSimilarArtistWithoutAPictureShowsTheFallbackAndStillOpens() {
        api.similar["Aphelion"] = listOf("Nebula Drift", "Cinder & Ash")
        api.artistImages += "Nebula Drift"
        setScreen()
        openAphelion()
        val fallbacks = hasTestTag("image_fallback") and hasAnyAncestor(hasTestTag(STRIP))
        val loading = hasTestTag("image_loading") and hasAnyAncestor(hasTestTag(STRIP))
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) {
            compose.onAllNodes(fallbacks, useUnmergedTree = true).fetchSemanticsNodes().size == 1 &&
                compose.onAllNodes(loading, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
        // The one fallback is the artist without a picture.
        val fallback = compose.onNode(fallbacks, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val cinder = inStrip("Cinder & Ash").getUnclippedBoundsInRoot()
        assertTrue("fallback at $fallback, Cinder & Ash at $cinder", fallback.left >= cinder.left && fallback.right <= cinder.right)
        // The strip asks for both of its artists, small; the artist list asks too, also small.
        val asked = synchronized(api.artistImageAsked) { api.artistImageAsked.toSet() }
        assertTrue("the strip's pictures were asked for: $asked", asked.containsAll(setOf("Nebula Drift" to "small", "Cinder & Ash" to "small")))
        assertTrue("every picture asked for is the small one: $asked", asked.all { it.second == "small" })
        inStrip("Cinder & Ash").performClick()
        compose.waitForText("Emberwake")
    }

    private companion object {
        const val LANDSCAPE_TABLET_DP = 1280 // a Pixel Tablet's width in landscape
        val PHONE_COVER_DP = 120f..180f
        const val STRIP = "similar_artists"
        const val FLIGHT = "basket_flight"
        const val PART_OF_A_FLIGHT_MS = 100L
        const val LONGER_THAN_A_FLIGHT_MS = 1_000L
        const val SETTLE_MS = 1_500L
        const val PAST_A_DOUBLE_TAP_MS = 1_000L
        const val SIMILAR_LABEL = "Similar artists in your library"
        const val LARGEST_FONT_SCALE = 2f
        const val SIZE_TOLERANCE_DP = 0.5f
        const val NARROW_DP = 320
        // More than a phone's width holds, one very long, and more than the eight the row used to cap at.
        val LONG_SIMILAR = listOf(
            "Nebula Drift", "Cinder & Ash", "The Presidents of the United States of America", "The White Stripes",
            "Suicidal Tendencies", "Boards of Canada", "Godspeed You! Black Emperor", "Mogwai", "Sigur Rós",
        )
    }
}
