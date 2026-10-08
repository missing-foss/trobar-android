// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
// The capability gate in front of the App API tabs: a server at the level
// shows the content, one below it shows the "update the server" state, an
// unreachable one offers a retry, and a level already recorded by an
// earlier probe is used without asking the server again.

package org.missing_foss.trobar

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppApiGateTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: MockWebServer

    @Before
    fun startServer() {
        // DataStore is the device's; a level left by an earlier test would
        // let the gate skip the probe these tests drive through the server.
        runBlocking { Prefs.clearAppApi(context) }
        server = MockWebServer().scripted()
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    private fun info(appApi: String?) = MockResponse().setResponseCode(200).setBody(
        """{"name": "Test Phone", "max_size_bytes": null, "device_type": "phone", "source_of_truth": "server"""" +
            (if (appApi != null) """, "app_api": $appApi}""" else "}"),
    )

    private fun setGate() {
        val pairing = Prefs.Pairing(serverUrl = server.url("/").toString(), token = "test-token")
        compose.setContent {
            MaterialTheme {
                AppApiGate(pairing) { Text("GATED CONTENT") }
            }
        }
    }


    @Test
    fun aServerAtTheLevelShowsTheContent() {
        server.enqueue(info("1"))
        setGate()
        compose.waitForText("GATED CONTENT")
        compose.onNodeWithText("GATED CONTENT").assertIsDisplayed()
    }

    @Test
    fun aServerWithoutTheCapabilityShowsTheUpdateState() {
        server.enqueue(info(null))
        setGate()
        compose.waitForText("Update the server to use this")
        compose.onNodeWithText("Update the server to use this").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("GATED CONTENT").fetchSemanticsNodes().size)
    }

    @Test
    fun aMalformedLevelCountsAsNone() {
        server.enqueue(info("\"1\""))
        setGate()
        compose.waitForText("Update the server to use this")
    }

    @Test
    fun anUnreachableServerOffersRetryAndRetryReprobes() {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(info("1"))
        setGate()
        compose.waitForText("Retry")
        // Where the details are: Settings, which took over the Sync tab's detail.
        compose.onNodeWithText("Couldn't reach the server. Details in Settings.").assertIsDisplayed()
        assertEquals(1, server.requestCount) // one probe failed; nothing re-probed on its own
        compose.onNodeWithText("Retry").performClick()
        compose.waitForText("GATED CONTENT")
        assertEquals(2, server.requestCount)
    }

    @Test
    fun aStoredAddressThatIsNotAUrlReadsAsUnreachableNotACrash() {
        // OkHttp throws IllegalArgumentException, not IOException, for an
        // address without a scheme; the gate must treat the user's own
        // stored value as input, not as a bug.
        val pairing = Prefs.Pairing(serverUrl = "myserver:8080", token = "test-token")
        compose.setContent { MaterialTheme { AppApiGate(pairing) { Text("GATED CONTENT") } } }
        compose.waitForText("Retry")
        compose.onNodeWithText("Retry").assertIsDisplayed()
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aRecordedLevelIsUsedWithoutAProbe() {
        runBlocking { Prefs.setAppApi(context, 1) }
        setGate()
        compose.waitForText("GATED CONTENT")
        assertEquals(0, server.requestCount)
    }
}
