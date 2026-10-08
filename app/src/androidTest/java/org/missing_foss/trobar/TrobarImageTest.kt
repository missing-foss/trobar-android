// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
// TrobarImage against a real MockWebServer: the picture renders, the device
// credential goes to the paired server and to no other host, and a refused
// request shows the fallback once rather than retrying in a loop.

package org.missing_foss.trobar

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.compose.AsyncImagePainter
import java.io.ByteArrayOutputStream
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrobarImageTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var server: MockWebServer
    private lateinit var foreign: MockWebServer

    @Before
    fun startServers() {
        server = MockWebServer()
        server.start()
        foreign = MockWebServer()
        foreign.start()
    }

    @After
    fun stopServers() {
        server.shutdown()
        foreign.shutdown()
    }

    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    private fun pngResponse() = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "image/png")
        .setBody(Buffer().write(png()))

    private fun pairing() = Prefs.Pairing(serverUrl = server.url("/").toString(), token = "test-token")

    private fun show(url: String, states: MutableList<AsyncImagePainter.State>) {
        compose.setContent {
            MaterialTheme {
                TrobarImage(
                    url = url,
                    contentDescription = "cover",
                    pairing = pairing(),
                    modifier = Modifier.size(64.dp),
                    onState = { states += it },
                )
            }
        }
    }

    private fun waitUntilSettled(states: List<AsyncImagePainter.State>) {
        compose.waitUntil(timeoutMillis = UI_WAIT_MS) {
            states.lastOrNull().let { it is AsyncImagePainter.State.Success || it is AsyncImagePainter.State.Error }
        }
    }

    @Test
    fun rendersAndSendsTheDeviceCredentialToThePairedServer() {
        server.enqueue(pngResponse())
        val states = mutableListOf<AsyncImagePainter.State>()

        show(server.url("/api/device/artist-image?artist=Aphelion").toString(), states)
        waitUntilSettled(states)

        assert(states.last() is AsyncImagePainter.State.Success) { "expected Success, got ${states.last()}" }
        val request = server.takeRequest()
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
        assertNotNull(request.getHeader("Accept-Language"))
    }

    @Test
    fun aRefusedRequestShowsTheFallbackOnceAndDoesNotRetry() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error": "invalid token"}"""))
        val states = mutableListOf<AsyncImagePainter.State>()

        show(server.url("/api/device/artist-image?artist=Nobody").toString(), states)
        waitUntilSettled(states)

        assert(states.last() is AsyncImagePainter.State.Error) { "expected Error, got ${states.last()}" }
        compose.onNodeWithTag("image_fallback").assertIsDisplayed()
        // Give a retry loop time to show itself before counting.
        Thread.sleep(RETRY_GRACE_MS)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aForeignHostGetsNoCredential() {
        foreign.enqueue(pngResponse())
        val states = mutableListOf<AsyncImagePainter.State>()

        show(foreign.url("/elsewhere.png").toString(), states)
        waitUntilSettled(states)

        val request = foreign.takeRequest()
        assertNull(request.getHeader("Authorization"))
        assertEquals(0, server.requestCount)
    }

    private companion object {
        const val RETRY_GRACE_MS = 1_000L
    }
}
