// SPDX-FileCopyrightText: 2026 missing-foss
//
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

/** What is selected for a device, grouped, and taking it off that device. */
@RunWith(AndroidJUnit4::class)
class SelectionsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: MockWebServer
    private lateinit var api: FakeAppApi
    private val removedFrom = mutableListOf<Long>()

    @Before
    fun startServer() {
        api = FakeAppApi()
        server = MockWebServer()
        server.dispatcher = api
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    private fun setScreen(deviceId: Long = 1) {
        val pairing = Prefs.Pairing(serverUrl = server.url("/").toString(), token = "test-token")
        compose.setContent { MaterialTheme { SelectionsScreen(pairing, deviceId, onBack = {}, onRemoved = { removedFrom += it }) } }
    }

    private fun waitForTag(tag: String) = compose.waitUntil(timeoutMillis = UI_WAIT_MS) {
        compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForText(text: String) = compose.waitUntil(timeoutMillis = UI_WAIT_MS) {
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun album() = FakeAppApi.FakeSelection(5, "album", "Aphelion||Parallax", "Parallax", 1)

    @Test
    fun showsEachTypeInItsGroupAndOnlyThisDevice() {
        api.selections += FakeAppApi.FakeSelection(4, "artist", "Aphelion", "Aphelion", 1)
        api.selections += album()
        api.selections += FakeAppApi.FakeSelection(6, "playlist", "7", "Road Trip", 1)
        api.selections += FakeAppApi.FakeSelection(7, "track", "9", "One", 1)
        api.selections += FakeAppApi.FakeSelection(8, "album", "B||Elsewhere", "Elsewhere", 2)
        setScreen()
        waitForText("Parallax")
        listOf("Artists", "Albums", "Playlists", "Tracks", "Road Trip", "One").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        compose.onAllNodes(hasText("Elsewhere")).assertCountEquals(0)
    }

    @Test
    fun oneArtistsTracksReadAsThatAndAWholeAlbumAsWhole() {
        api.selections += FakeAppApi.FakeSelection(8, "album", "Aphelion||Night Drive", "Night Drive", 1, appearsOnTracks = 2, appearsOnOf = 14)
        api.selections += FakeAppApi.FakeSelection(9, "album", "Aphelion||Parallax", "Parallax", 1, albumTracks = 9)
        setScreen()
        waitForText("Aphelion on Night Drive")
        compose.onNodeWithText("2 of 14 tracks").assertIsDisplayed()
        compose.onNodeWithText("Aphelion · whole album, 9 tracks").assertIsDisplayed()
    }

    @Test
    fun aSelectionWhoseTargetHasGoneSaysSoAndNeverShowsTheKey() {
        api.selections += FakeAppApi.FakeSelection(6, "playlist", "777", null, 1)
        setScreen()
        waitForText("No longer exists on the server")
        compose.onAllNodes(hasText("777")).assertCountEquals(0)
    }

    @Test
    fun removingAsksFirstAndSaysWhatTheNextSyncDeletes() {
        api.selections += album()
        api.removalTracks = 11
        setScreen()
        waitForText("Parallax")
        compose.onNodeWithTag("selection_remove_5").performClick()
        waitForTag("selection_remove_body")
        compose.onNodeWithTag("selection_remove_body").assertTextContains("deletes 11 tracks", substring = true)
        assertTrue("removed before confirming", api.selectionRemovals.isEmpty())
        compose.onNodeWithTag("selection_remove_confirm").performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { compose.onAllNodes(hasText("Parallax")).fetchSemanticsNodes().isEmpty() }
        assertEquals(listOf("album" to "Aphelion||Parallax"), api.selectionRemovals.toList())
        assertEquals("the shell is told, so Home reads its grids again", listOf(1L), removedFrom)
    }

    @Test
    fun cancellingRemovesNothing() {
        api.selections += album()
        setScreen()
        waitForText("Parallax")
        compose.onNodeWithTag("selection_remove_5").performClick()
        waitForTag("selection_remove_body")
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertTrue(api.selectionRemovals.isEmpty())
        compose.onNodeWithText("Parallax").assertIsDisplayed()
        assertTrue(removedFrom.isEmpty())
    }

    @Test
    fun someoneElsesSelectionStaysAndTheServerSaysWhose() {
        api.selections += album()
        api.removeRefusal = "Selected by bob, so it stays on this device."
        setScreen()
        waitForText("Parallax")
        compose.onNodeWithTag("selection_remove_5").performClick()
        waitForTag("selection_remove_confirm")
        compose.onNodeWithTag("selection_remove_confirm").performClick()
        waitForText("Selected by bob, so it stays on this device.")
        compose.onNodeWithText("Parallax").assertIsDisplayed()
    }

    @Test
    fun autoFitIsShownAndCannotBeRemoved() {
        api.autofitTracks[1] = 312
        setScreen()
        waitForTag("selections_autofit")
        compose.onNodeWithText("312 tracks").assertIsDisplayed()
        compose.onAllNodes(hasText("Remove")).assertCountEquals(0)
    }

    @Test
    fun nothingSelectedSaysSo() {
        setScreen()
        waitForTag("selections_empty")
    }
}
