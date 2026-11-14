// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The notification permission: asked once after the folder picker, and the
 * Settings row that says whether the sync's progress notification can show.
 * The permission itself is faked through LocalNotificationAccess: revoking
 * the real one kills the app's process, and this test with it.
 */
@RunWith(AndroidJUnit4::class)
class SyncNotificationTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"name": "Test Phone", "max_size_bytes": null, "device_type": "phone", "source_of_truth": "server"}"""))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // The server-owned settings have an "Off" of their own.
    private fun offRow() = compose.onNode(hasText("Off") and !hasAnyAncestor(hasTestTag("server_owned_settings")))

    private fun setScreen(allowed: () -> Boolean, opened: MutableList<Unit> = mutableListOf()) {
        compose.setContent {
            CompositionLocalProvider(LocalNotificationAccess provides NotificationAccess(
                allowedNow = { allowed() },
                openSettings = { opened.add(Unit) },
            )) { MaterialTheme {
                SettingsScreen(
                    pairing = Prefs.Pairing(serverUrl = server.url("/").toString(), token = "test-token"),
                    treeUri = "content://com.android.externalstorage.documents/tree/primary%3ATrobarTest",
                    nav = SettingsNav(onOpenAbout = {}),
                    onServerUrlChanged = {},
                    onFolderChanged = {},
                    onUnpair = {},
                )
            } }
        }
    }

    @Test
    fun askedOnlyOnAndroid13AndLaterWhenNotGrantedAndNotAskedBefore() {
        assertTrue(shouldAskForNotifications(sdkInt = 33, granted = false, alreadyAsked = false))
        assertTrue(shouldAskForNotifications(sdkInt = 34, granted = false, alreadyAsked = false))
        assertFalse(shouldAskForNotifications(sdkInt = 32, granted = false, alreadyAsked = false))
        assertFalse(shouldAskForNotifications(sdkInt = 34, granted = true, alreadyAsked = false))
        assertFalse(shouldAskForNotifications(sdkInt = 34, granted = false, alreadyAsked = true))
    }

    @Test
    fun theAskIsRemembered() = runBlocking {
        Prefs.setNotificationPromptDone(context)
        assertTrue(Prefs.notificationPromptDone(context))
    }

    @Test
    fun allowedSaysWhatTheNotificationShows() {
        setScreen(allowed = { true })
        compose.onNodeWithText("Sync progress notification").performScrollTo()
        compose.onNodeWithText("Allowed").assertExists()
        compose.onNodeWithText("Shows how far a sync has got while it runs.").assertExists()
    }

    @Test
    fun offSaysTheSyncStillRunsAndTheRowOpensAndroidSettings() {
        val opened = mutableListOf<Unit>()
        setScreen(allowed = { false }, opened = opened)
        compose.onNodeWithText("Sync progress notification").performScrollTo()
        offRow().assertExists()
        compose.onNodeWithText(
            "Syncs still run, just without a progress notification. Tap to allow it in Android settings.")
            .assertExists()
        compose.onNodeWithText("Sync progress notification").performClick()
        compose.waitForIdle()
        assertEquals(1, opened.size)
    }

    @Test
    fun comingBackToSettingsReadsTheStateAgain() {
        var allowed = false
        setScreen(allowed = { allowed })
        compose.onNodeWithText("Sync progress notification").performScrollTo()
        offRow().assertExists()
        // What changing it in Android's settings and coming back looks like.
        allowed = true
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        compose.onNodeWithText("Allowed").assertExists()
    }
}
