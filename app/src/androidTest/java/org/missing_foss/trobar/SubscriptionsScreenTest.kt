// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
// The playlist subscriptions against the in-memory App API: the list with
// the browser's words for each state, a link added and the field cleared,
// a link the server refuses kept in the field with its reason, a link
// shared from another app waiting for Add, a refresh, and a removal that
// asks first and takes its playlist with it.

package org.missing_foss.trobar

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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

@RunWith(AndroidJUnit4::class)
class SubscriptionsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: MockWebServer
    private lateinit var api: FakePlaylistApi

    @Before
    fun startServer() {
        api = FakePlaylistApi()
        server = MockWebServer()
        server.dispatcher = api
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    private fun setScreen(sharedUrl: String? = null) {
        val pairing = Prefs.Pairing(serverUrl = server.url("/").toString(), token = "test-token")
        compose.setContent {
            MaterialTheme { SubscriptionsScreen(pairing = pairing, sharedUrl = sharedUrl, onBack = {}) }
        }
    }

    private fun field() = compose.onNodeWithTag("subscription_url")

    @Test
    fun eachSubscriptionSaysHowItsLastFetchWentInTheBrowsersWords() {
        api.subscriptions += FakePlaylistApi.Subscription(1, "https://music.youtube.com/playlist?list=A", "Matched", 4, 3)
        api.subscriptions += FakePlaylistApi.Subscription(2, "https://music.youtube.com/playlist?list=B", "Nothing here", 5, 0)
        api.subscriptions += FakePlaylistApi.Subscription(3, "https://music.youtube.com/playlist?list=C", "Gone", null, null, lastError = "unavailable")
        api.subscriptions += FakePlaylistApi.Subscription(4, "https://music.youtube.com/playlist?list=D", null, null, null, lastError = "timeout")
        setScreen()
        compose.waitForText("Matched")
        compose.onNodeWithText("3 of 4 tracks in your library").assertIsDisplayed()
        compose.onNodeWithText("Fetched, but none of its tracks are in your library.").assertIsDisplayed()
        compose.onNodeWithText("Unavailable — deleted, made private, or the link is wrong.").assertIsDisplayed()
        compose.onNodeWithText("Couldn't reach YouTube Music. Nothing already imported was changed.").assertIsDisplayed()
        // No title yet: the link stands in for it, as in the browser.
        compose.onNodeWithText("https://music.youtube.com/playlist?list=D").assertIsDisplayed()
    }

    @Test
    fun thePrivacyPromiseStaysInViewAndTheRestIsBehindTheInfoIcon() {
        setScreen()
        compose.onNodeWithText("Nothing is sent to YouTube and nothing is downloaded from it.").assertIsDisplayed()
        compose.onAllNodesWithText("Public YouTube Music playlists only", substring = true).assertCountEquals(0)
        compose.onNodeWithContentDescription("About Playlist subscriptions").performClick()
        compose.onNodeWithText("Public YouTube Music playlists only", substring = true).assertIsDisplayed()
    }

    @Test
    fun aLinkAddedIsSubscribedListedAndTheFieldCleared() {
        setScreen()
        compose.waitForText("No subscriptions yet.")
        compose.onNodeWithText("Add").assertIsNotEnabled() // nothing typed, nothing to add
        field().performTextInput("https://music.youtube.com/playlist?list=PLabc")
        compose.onNodeWithText("Add").performClick()
        compose.waitForText("Mix 100")
        assertEquals(listOf("https://music.youtube.com/playlist?list=PLabc"), api.subscribeBodies.toList())
        compose.onNodeWithText("1 of 2 tracks in your library").assertIsDisplayed()
        field().assertTextContains("", substring = false)
    }

    @Test
    fun aLinkTheServerRefusesStaysInTheFieldWithItsReason() {
        setScreen()
        compose.waitForText("No subscriptions yet.")
        field().performTextInput("https://example.org/not-a-playlist")
        compose.onNodeWithText("Add").performClick()
        compose.waitForText("That doesn't look like a YouTube Music playlist link", substring = true)
        field().assertTextContains("https://example.org/not-a-playlist")
        compose.onAllNodesWithText("No subscriptions yet.").assertCountEquals(1)
    }

    @Test
    fun aSharedLinkWaitsInTheFieldUntilAdd() {
        setScreen(sharedUrl = "https://music.youtube.com/playlist?list=PLshared")
        compose.waitForText("No subscriptions yet.")
        field().assertTextContains("https://music.youtube.com/playlist?list=PLshared")
        Thread.sleep(SETTLE_MS)
        assertTrue("subscribed without Add: ${api.subscribeBodies}", api.subscribeBodies.isEmpty())
        compose.onNodeWithText("Add").performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { api.subscribeBodies.size == 1 }
    }

    @Test
    fun refreshFetchesThatSubscriptionAgain() {
        api.subscriptions += FakePlaylistApi.Subscription(1, "https://music.youtube.com/playlist?list=A", "Mix", null, null, lastError = "timeout")
        setScreen()
        compose.waitForText("Couldn't reach YouTube Music. Nothing already imported was changed.")
        compose.onNodeWithText("Refresh").performClick()
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) {
            compose.onAllNodesWithText("Couldn't reach YouTube Music", substring = true).fetchSemanticsNodes().isEmpty()
        }
        assertEquals(1, api.refreshes.get())
    }

    @Test
    fun removingAsksFirstAndTakesItsPlaylistWithIt() {
        api.playlists += FakePlaylistApi.Playlist(900, "Mix", trackCount = 2, matchedCount = 1, provider = "ytmusic")
        api.subscriptions += FakePlaylistApi.Subscription(1, "https://music.youtube.com/playlist?list=A", "Mix", 2, 1, playlistId = 900)
        setScreen()
        compose.waitForText("Mix")
        compose.onNodeWithText("Remove").performClick()
        compose.waitForText("Remove “Mix” and the playlist it created?")
        compose.onNodeWithText("Cancel").performClick()
        Thread.sleep(SETTLE_MS)
        assertTrue("removed on Cancel", api.deletes.isEmpty())
        compose.onNodeWithText("Remove").performClick()
        compose.onNode(hasText("Remove") and hasAnyAncestor(isDialog())).performClick()
        compose.waitForText("No subscriptions yet.")
        assertEquals(listOf(1L), api.deletes.toList())
        assertTrue(api.playlists.none { it.id == 900L })
    }

    private companion object {
        const val SETTLE_MS = 1_500L
    }
}
