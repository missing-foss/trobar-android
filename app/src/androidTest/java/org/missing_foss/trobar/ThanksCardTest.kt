// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
// The one-time thank-you card: due from the 50th sync this install finished
// with no error, gone for good after either button, in EN and FR; and the
// report each such sync sends the server. Where the worker counts and
// reports a sync is pinned by dev/check_call_sites.py.

package org.missing_foss.trobar

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasTestTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThanksCardTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    @After
    fun reset() = runBlocking { Prefs.resetThanksCard(context) }

    private fun syncs(n: Int) = runBlocking { repeat(n) { Prefs.countSuccessfulSync(context) } }

    private fun shown() = runBlocking { Prefs.thanksCardShown(context).first() }

    private fun setCard(french: Boolean = false, onOpenUrl: (String) -> Unit = {}) {
        compose.setContent {
            val base = LocalContext.current
            val config = Configuration(base.resources.configuration).apply { if (french) setLocale(Locale.FRENCH) }
            CompositionLocalProvider(
                LocalContext provides base.createConfigurationContext(config),
                LocalConfiguration provides config,
            ) {
                MaterialTheme { ThanksCardIfDue(onOpenUrl = onOpenUrl) }
            }
        }
    }

    private fun textOf(tag: String): String =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }

    @Test
    fun notDueBeforeTheFiftiethSync() {
        syncs(Prefs.THANKS_CARD_SYNCS - 1)
        assertFalse(shown())
        setCard()
        compose.waitForIdle()
        assertTrue(compose.onAllNodes(hasTestTag("thanks_card")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun fromTheFiftiethItThanksSaysWhyAndOffersKofi() {
        syncs(Prefs.THANKS_CARD_SYNCS)
        assertTrue(shown())
        setCard()
        compose.onNodeWithTag("thanks_card").assertIsDisplayed()
        assertEquals("Thank you", textOf("thanks_card_heading"))
        assertEquals("Trobar has synced 50 times for you.\nIf it's useful, a coffee helps.", textOf("thanks_card_text"))
        assertEquals("Support on Ko-fi", textOf("thanks_card_kofi"))
    }

    @Test
    fun andInFrench() {
        syncs(Prefs.THANKS_CARD_SYNCS)
        setCard(french = true)
        assertEquals("Mer ci", textOf("thanks_card_heading"))
        assertEquals("Trobar a synchronisé 50 fois pour vous.\nS'il vous est utile, un café aide.", textOf("thanks_card_text"))
    }

    @Test
    fun theCrossDismissesItForGood() {
        syncs(Prefs.THANKS_CARD_SYNCS)
        setCard()
        compose.onNodeWithTag("thanks_card_dismiss").performClick()
        compose.waitUntil(UI_WAIT_MS) { !shown() }
        compose.waitUntil(UI_WAIT_MS) { compose.onAllNodes(hasTestTag("thanks_card")).fetchSemanticsNodes().isEmpty() }
        syncs(1)
        assertFalse(shown()) // a later sync doesn't bring it back
    }

    @Test
    fun supportOnKofiOpensKofiAndDismissesItToo() {
        syncs(Prefs.THANKS_CARD_SYNCS)
        val opened = mutableListOf<String>()
        setCard(onOpenUrl = { opened += it })
        compose.onNodeWithTag("thanks_card_kofi").performClick()
        compose.waitUntil(UI_WAIT_MS) { !shown() }
        assertEquals(listOf("https://ko-fi.com/missingfoss"), opened)
    }

    @Test
    fun theReportPostsToTheServersSyncCount() {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status": "ok"}"""))
            ApiClient(context, server.url("/").toString(), "test-token").reportSyncCompleted()
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/device/sync-completed", request.path)
            assertEquals("Bearer test-token", request.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun anOlderServersNotFoundIsAnErrorTheWorkerCatches() {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(404))
            try {
                ApiClient(context, server.url("/").toString(), "test-token").reportSyncCompleted()
                fail("a 404 should throw, for the worker's catch")
            } catch (expected: Exception) { /* the worker swallows this */ }
        } finally {
            server.shutdown()
        }
    }
}
