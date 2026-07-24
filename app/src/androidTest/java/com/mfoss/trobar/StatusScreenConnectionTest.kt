// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
// #61: StatusScreen's on-open server probe (#34) — OK/UNAUTHORIZED/UNREACHABLE
// each driven through a real ApiClient.getDeviceInfo() call against a local
// MockWebServer, not a fake ConnState. UNREACHABLE is produced by shutting the
// server down before the probe runs, so the request genuinely fails to connect
// rather than by asserting on a contrived response.

package com.mfoss.trobar

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StatusScreenConnectionTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var server: MockWebServer

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stopServer() {
        // Already shut down by the UNREACHABLE test itself; shutdown() on an
        // already-stopped server is a no-op, not an error.
        server.shutdown()
    }

    private fun setScreen() {
        val pairing = Prefs.Pairing(serverUrl = server.url("/").toString(), token = "test-token")
        compose.setContent {
            MaterialTheme {
                StatusScreen(pairing = pairing, onOpenSettings = {}, onReEnroll = {})
            }
        }
    }

    @Test
    fun okResponseShowsDeviceNameAndSyncPrompt() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"name": "Test Phone", "max_size_bytes": null, "device_type": "phone", "source_of_truth": "server"}""",
            ),
        )
        setScreen()

        compose.onNodeWithText("Test Phone").assertIsDisplayed()
        compose.onNodeWithText("Tap the logo to sync").assertIsDisplayed()
    }

    @Test
    fun unauthorizedResponseShowsReEnrollScreen() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error": "invalid token"}"""))
        setScreen()

        compose.onNodeWithText("Device not recognized").assertIsDisplayed()
        compose.onNodeWithText("Re-enroll").assertIsDisplayed()
    }

    @Test
    fun unreachableServerShowsRetryScreen() {
        // Shut the server down before the composable's own probe fires, so
        // the real request fails to connect rather than getting a response.
        server.shutdown()
        setScreen()

        compose.onNodeWithText("Can't reach the server").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsDisplayed()
    }
}
