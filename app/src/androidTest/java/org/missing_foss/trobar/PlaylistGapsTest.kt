// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
// A playlist's gaps against the in-memory App API: the unmatched entries
// reviewed, one excluded and the count following the server's answer, a
// failure changing nothing and saying so, "Exclude all" asking first; and
// the Lidarr switch -- why it cannot be turned on, turned on with its last
// run, and a run that failed in the browser's words.

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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaylistGapsTest {
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

    private fun open(playlist: FakePlaylistApi.Playlist) {
        api.playlists += playlist
        val pairing = Prefs.Pairing(serverUrl = server.url("/").toString(), token = "test-token")
        val shared = testAppState(context, pairing)
        compose.setContent { MaterialTheme { PlaylistsScreen(pairing = pairing, nav = PlaylistsNav({}, {}), shared = shared) } }
        compose.waitForText(playlist.title)
        compose.onNodeWithText(playlist.title).performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { compose.onAllNodes(hasTestTag("playlist_entries")).fetchSemanticsNodes().isNotEmpty() }
    }

    /** [open], at the narrowest phone width, in French, at 2.0x: the
     * NarrowLayoutSweepTest overrides. */
    private fun openNarrowFrench(playlist: FakePlaylistApi.Playlist) {
        api.playlists += playlist
        val pairing = Prefs.Pairing(serverUrl = server.url("/").toString(), token = "test-token")
        val shared = testAppState(context, pairing)
        compose.setContent {
            val ctx = LocalContext.current
            val config = Configuration(ctx.resources.configuration).apply {
                setLocale(Locale.FRENCH); fontScale = 2.0f; screenWidthDp = NARROW_DP
            }
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides ctx.createConfigurationContext(config),
                LocalConfiguration provides config,
                LocalDensity provides Density(density.density, 2.0f),
            ) {
                MaterialTheme {
                    Box(Modifier.requiredWidth(NARROW_DP.dp)) { PlaylistsScreen(pairing = pairing, nav = PlaylistsNav({}, {}), shared = shared) }
                }
            }
        }
        compose.waitForText(playlist.title)
        compose.onNodeWithText(playlist.title).performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { compose.onAllNodes(hasTestTag("playlist_entries")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun widthOf(description: String) = compose.onNodeWithContentDescription(description, useUnmergedTree = true)
        .fetchSemanticsNode().boundsInRoot.width

    @Test
    fun atTheNarrowestWidthInFrenchAt2xEveryInfoIconAndSwitchKeepsItsWidth() {
        // The longest labels: Lidarr on, the playlist shared.
        val playlist = roadTripWithTwoGaps().also { it.lidarrEnabled = true; it.shared = true }
        openNarrowFrench(playlist)
        compose.waitForText("Demande des albums manquants active")
        assertTrue("the Lidarr info icon is laid out", widthOf("À propos : Demande des albums manquants active") > 0f)
        assertTrue("the sharing info icon is laid out", widthOf("À propos : Partagée") > 0f)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).fetchSemanticsNodes().forEach {
            assertTrue("a switch row keeps its width", it.boundsInRoot.width > 0f)
        }
        compose.onNodeWithTag("playlist_entries").performScrollToNode(hasText("· Examiner", substring = true))
        // The review button ("… · Examiner"), not the playlist row's own "non associé(s)" summary.
        compose.onNodeWithText("· Examiner", substring = true).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForText("Non associés à votre bibliothèque")
        assertTrue("the review's info icon is laid out", widthOf("À propos : Non associés à votre bibliothèque") > 0f)
    }

    private fun roadTripWithTwoGaps(): FakePlaylistApi.Playlist {
        api.unresolved[1] = listOf(
            FakePlaylistApi.Unresolved(11, "C", "Two", null),
            FakePlaylistApi.Unresolved(12, "E", "Four", "F"),
        )
        return FakePlaylistApi.Playlist(1, "Road Trip", trackCount = 4, matchedCount = 2)
    }

    private fun openReview() {
        compose.onNodeWithText("2 not matched locally · Review").performClick()
        compose.waitForText("Not matched to your library")
    }

    @Test
    fun oneExcludedChangesItsRowAndTheCountOnlyOnTheServersAnswer() {
        open(roadTripWithTwoGaps())
        openReview()
        compose.waitForText("C — Two")
        compose.onNodeWithText("E — F — Four").assertExists() // artist, album, title: the browser's order
        compose.onAllNodes(hasText("Exclude")).fetchSemanticsNodes().size.let { assertEquals("one per row", 2, it) }
        compose.onAllNodes(hasText("Exclude"))[0].performClick()
        compose.waitForText("Excluded")
        assertEquals(listOf(11L), api.excludeBodies.single().getJSONArray("ids").let { a -> List(a.length()) { a.getLong(it) } })
        assertEquals(true, api.excludeBodies.single().getBoolean("excluded"))
        compose.waitForText("1 not matched locally", substring = true) // the summary follows the server's count
    }

    @Test
    fun anAlbumFoundInMusicBrainzSaysSoAndTheSourcesOwnDoesNot() {
        api.unresolved[1] = listOf(
            // As the server keeps it: a missing album is "", not null.
            FakePlaylistApi.Unresolved(11, "Band", "Song", "", inferredAlbum = "Found Album"),
            FakePlaylistApi.Unresolved(12, "Band", "Other", "Source Album"),
            FakePlaylistApi.Unresolved(13, "Band", "Third", ""),
        )
        open(FakePlaylistApi.Playlist(1, "Road Trip", trackCount = 5, matchedCount = 2))
        compose.onNodeWithText("3 not matched locally · Review").performClick()
        compose.waitForText("Band — Found Album (from MusicBrainz) — Song")
        compose.onNodeWithText("Band — Source Album — Other").assertExists()
        compose.onNodeWithText("Band — Third").assertExists()
    }

    @Test
    fun aFailedExclusionChangesNothingAndSaysSo() {
        open(roadTripWithTwoGaps())
        openReview()
        compose.waitForText("C — Two")
        api.unresolvedFails = true
        compose.onAllNodes(hasText("Exclude"))[0].performClick()
        compose.waitForText("Couldn't load or save — try again.")
        compose.onAllNodes(hasText("Excluded")).fetchSemanticsNodes().let { assertTrue("a row changed on a failure", it.isEmpty()) }
        compose.onNodeWithText("2 not matched locally", substring = true).assertExists()
    }

    @Test
    fun aReviewThatCannotLoadSaysSo() {
        api.unresolvedFails = true
        open(roadTripWithTwoGaps())
        openReview()
        compose.waitForText("Couldn't load or save — try again.")
    }

    @Test
    fun excludeAllAsksFirst() {
        open(roadTripWithTwoGaps())
        openReview()
        compose.waitForText("C — Two")
        compose.onNodeWithText("Exclude all").performClick()
        compose.waitForText("Exclude all 2 tracks from the review?", substring = true)
        compose.onNodeWithText("Cancel").performClick()
        Thread.sleep(SETTLE_MS)
        assertTrue("excluded on Cancel", api.excludeBodies.isEmpty())
        compose.onNodeWithText("Exclude all").performClick()
        compose.onNode(hasText("Exclude all") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.excludeBodies.size == 1 }
        assertEquals(listOf(11L, 12L), api.excludeBodies.single().getJSONArray("ids").let { a -> List(a.length()) { a.getLong(it) } })
    }

    @Test
    fun lidarrNotSetUpIsNotOffered() {
        open(FakePlaylistApi.Playlist(1, "Road Trip", trackCount = 4, matchedCount = 2, lidarrConfigured = false, lidarrConnected = false))
        compose.onAllNodes(hasTestTag("lidarr_requests")).assertCountEquals(0)
    }

    @Test
    fun lidarrConnectedButNotFinishedIsNotOfferedEither() {
        open(FakePlaylistApi.Playlist(1, "Road Trip", trackCount = 4, matchedCount = 2, lidarrConfigured = false, lidarrConnected = true))
        compose.onAllNodes(hasTestTag("lidarr_requests")).assertCountEquals(0)
    }

    @Test
    fun requestsSwitchedOnBeforeLidarrWentAwayAreNotShownEither() {
        open(FakePlaylistApi.Playlist(1, "Road Trip", trackCount = 4, matchedCount = 2, lidarrConfigured = false, lidarrEnabled = true))
        compose.onAllNodes(hasTestTag("lidarr_requests")).assertCountEquals(0)
    }

    @Test
    fun requestsOnCanBeTurnedOff() {
        open(FakePlaylistApi.Playlist(1, "Road Trip", trackCount = 4, matchedCount = 2, lidarrHasAlbums = false, lidarrEnabled = true))
        lidarrSwitch().assertIsOn().assertIsEnabled().performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.lidarrBodies.isNotEmpty() }
        assertEquals(false, api.lidarrBodies.single().getBoolean("enabled"))
    }

    @Test
    fun aPlaylistWithNothingMissingSaysSoRatherThanBlameItsSource() {
        open(FakePlaylistApi.Playlist(1, "Road Trip", trackCount = 4, matchedCount = 4, lidarrHasAlbums = false))
        compose.onNodeWithText("Nothing is missing from this playlist", substring = true).assertExists()
        compose.onAllNodesWithText("doesn't provide album information", substring = true).assertCountEquals(0)
        lidarrSwitch().assertIsNotEnabled()
    }

    @Test
    fun aSourceWithNoAlbumDataCannotRequest() {
        open(FakePlaylistApi.Playlist(1, "Road Trip", trackCount = 4, matchedCount = 2, lidarrHasAlbums = false))
        compose.onNodeWithText("doesn't provide album information", substring = true).assertExists()
        compose.onAllNodesWithText("Nothing is missing", substring = true).assertCountEquals(0)
        lidarrSwitch().assertIsNotEnabled()
    }

    @Test
    fun lidarrTurnedOnShowsItsLastRun() {
        open(FakePlaylistApi.Playlist(1, "Road Trip", trackCount = 4, matchedCount = 2))
        // What it does is behind the label's info icon.
        compose.onAllNodesWithText("Request this playlist's missing albums", substring = true).assertCountEquals(0)
        compose.onNodeWithContentDescription("About Request missing albums…").performClick()
        compose.onNodeWithText("Request this playlist's missing albums", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        lidarrSwitch().assertIsEnabled().performClick()
        compose.waitForText("Requesting missing albums")
        compose.waitForText("Requested 3 albums, last run", substring = true)
        assertEquals(true, api.lidarrBodies.single().getBoolean("enabled"))
    }

    @Test
    fun aRunThatFailedSaysSoInTheBrowsersWords() {
        api.lidarrRun = Triple(0, "Lidarr answered 500", "failed")
        open(FakePlaylistApi.Playlist(1, "Road Trip", trackCount = 4, matchedCount = 2))
        lidarrSwitch().performClick()
        compose.waitForText("Failed to request an album from Lidarr: Lidarr answered 500")
    }

    private fun lidarrSwitch() = compose.onNode(isToggleable() and hasAnyAncestor(hasTestTag("lidarr_requests")))

    private companion object {
        const val NARROW_DP = 320
        const val SETTLE_MS = 1_500L
    }
}
