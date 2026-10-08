// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
// The sharing switch against the in-memory App API: absent where the server
// says the owner may not change it, one tap to share, and a question before
// making a playlist private, since that takes it out of other members'
// selections.

package org.missing_foss.trobar

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
class PlaylistSharingTest {
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
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { compose.onAllNodes(hasTestTag("lidarr_requests")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun sharingSwitch() = compose.onNode(isToggleable() and hasAnyAncestor(hasTestTag("playlist_sharing")))

    @Test
    fun aPlaylistTheOwnerCannotChangeHasNoSwitch() {
        open(FakePlaylistApi.Playlist(1, "Household", trackCount = 2, matchedCount = 2, ownerUsername = "carol", isOwn = false))
        compose.onAllNodes(hasTestTag("playlist_sharing")).assertCountEquals(0)
    }

    @Test
    fun sharingIsOneTapAndTheMarkFollowsTheServer() {
        open(FakePlaylistApi.Playlist(1, "Road Trip", trackCount = 2, matchedCount = 2))
        compose.onNodeWithText("Private").assertExists()
        // What it means is behind the label's info icon, and follows the state.
        compose.onNodeWithContentDescription("About Private").performClick()
        compose.onNodeWithText("Only visible to you (and the admin).").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        sharingSwitch().assertIsOff().performClick()
        compose.waitForText("Shared")
        sharingSwitch().assertIsOn()
        compose.onNodeWithContentDescription("About Shared").performClick()
        compose.onNodeWithText("Visible to everyone in the household.").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        assertEquals(true, api.shareBodies.single().getBoolean("shared"))
    }

    @Test
    fun makingItPrivateAsksFirst() {
        open(FakePlaylistApi.Playlist(1, "Road Trip", trackCount = 2, matchedCount = 2).also { it.shared = true })
        sharingSwitch().assertIsOn().performClick()
        compose.waitForText(
            "Make “Road Trip” private? Anyone else who chose it, other than an admin, loses it: " +
                "it leaves their selections, and its tracks are removed from their devices at their next sync.",
        )
        compose.onNodeWithText("Cancel").performClick()
        Thread.sleep(SETTLE_MS)
        assertTrue("written on Cancel", api.shareBodies.isEmpty())
        sharingSwitch().assertIsOn()
        sharingSwitch().performClick()
        compose.onNode(hasText("Make private") and hasAnyAncestor(isDialog())).performClick()
        compose.waitForText("Private")
        assertEquals(false, api.shareBodies.single().getBoolean("shared"))
    }

    private companion object {
        const val SETTLE_MS = 1_500L
    }
}
