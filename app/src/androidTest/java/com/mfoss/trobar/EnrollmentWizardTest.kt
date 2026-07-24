// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
// #61: the pairing flow, end-to-end against a real local HTTP server standing
// in for trobar-server — exercises the manual URL+code path (the QR-scan
// button is left untapped; it hands off to zxing's own Activity, which isn't
// this app's code to verify) through both wizard steps and the real
// redeemEnrollment() network call.

package com.mfoss.trobar

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EnrollmentWizardTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var server: MockWebServer
    private var enrolledUrl: String? = null
    private var enrolledToken: String? = null

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
        enrolledUrl = null
        enrolledToken = null
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    private fun setWizard() {
        compose.setContent {
            MaterialTheme {
                EnrollmentWizard(onEnrolled = { url, token -> enrolledUrl = url; enrolledToken = token })
            }
        }
    }

    /** Drives ConnectStep with the server's own URL — real callers get this
     * from the QR/manual entry; the test supplies it directly since it's the
     * one value that has to point at the MockWebServer instance. */
    private fun connect(code: String = "ABCD1234") {
        compose.onNodeWithText("Server URL").performTextInput(server.url("/").toString())
        compose.onNodeWithText("Enrollment code").performTextInput(code)
        compose.onNodeWithText("Continue").performClick()
    }

    @Test
    fun successfulEnrollmentReturnsServerUrlAndToken() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id": 1, "name": "Test Phone", "token": "abc123"}""",
            ),
        )
        setWizard()
        connect()
        compose.onNodeWithText("Device name").performTextInput("Test Phone")
        compose.onNodeWithText("Enroll").performClick()

        compose.waitUntil(timeoutMillis = 5_000) { enrolledToken != null }
        assertEquals(server.url("/").toString(), enrolledUrl)
        assertEquals("abc123", enrolledToken)
    }

    @Test
    fun expiredCodeShowsServerErrorAndDoesNotEnroll() {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"error": "Invalid or expired code"}""",
            ),
        )
        setWizard()
        connect()
        compose.onNodeWithText("Device name").performTextInput("Test Phone")
        compose.onNodeWithText("Enroll").performClick()

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Invalid or expired code").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Invalid or expired code").assertIsDisplayed()
        assertNull(enrolledToken)
    }

    @Test
    fun continueDisabledUntilBothFieldsFilled() {
        setWizard()
        compose.onNodeWithText("Continue").assertIsNotEnabled()
        compose.onNodeWithText("Server URL").performTextInput(server.url("/").toString())
        compose.onNodeWithText("Continue").assertIsNotEnabled()
        compose.onNodeWithText("Enrollment code").performTextInput("ABCD1234")
        // Now both fields are filled — Continue advances (checked by the
        // presence of step 2's field, the same "did it navigate" signal
        // the other tests use).
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Device name").assertIsDisplayed()
    }
}
