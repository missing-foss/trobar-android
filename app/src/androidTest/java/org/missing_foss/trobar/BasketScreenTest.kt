// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
// The basket screen against an in-memory App API: sections grouped by
// device, the two removals, a send's result surfaced, and the sync that
// starts after a send only when this phone was the target, with the send's
// result still said; a header that holds a long device name at the largest
// font scale, and rows that each carry the same square of art.

package org.missing_foss.trobar

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BasketScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: MockWebServer
    private lateinit var api: FakeAppApi
    private var syncNowCalls = 0
    private var showSyncCalls = 0
    private val sentAll = mutableListOf<Pair<String, Boolean>>()
    private lateinit var shared: AppState
    private var syncBlock: Int? = null

    @Before
    fun startServer() {
        runBlocking {
            // The emulator's network counts as any of the modes; the offer
            // must not depend on which transport it happens to report.
            Prefs.setNetworkMode(context, Prefs.NETWORK_ANY)
        }
        syncNowCalls = 0
        showSyncCalls = 0
        sentAll.clear()
        syncBlock = null
        api = FakeAppApi()
        server = MockWebServer()
        server.dispatcher = api
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    // A round trip plus a store read inside one wait; generous on purpose,
    // a pass returns the moment the text is there.
    private fun waitForText(text: String) {
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForSend(device: String): SemanticsNodeInteraction {
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithContentDescription("Send $device").fetchSemanticsNodes().isNotEmpty()
        }
        return compose.onNodeWithContentDescription("Send $device")
    }

    private fun setScreen(fontScale: Float? = null) {
        val pairing = Prefs.Pairing(serverUrl = server.url("/").toString(), token = "test-token")
        shared = testAppState(context, pairing)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale ?: density.fontScale),
                LocalManualSync provides ManualSync(start = {}, blockedBy = { syncBlock }),
            ) {
                MaterialTheme {
                    BasketScreen(pairing = pairing, onBack = {}, onSyncNow = { syncNowCalls++ }, onShowSync = { showSyncCalls++ },
                        shared = shared, onSentAll = { message, offer -> sentAll += message to offer })
                }
            }
        }
    }

    private fun layoutOf(text: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        return layouts.single()
    }

    @Test
    fun aLongDeviceNameLeavesTheMarkerAndTheButtonWholeAtTheLargestFontScale() {
        server.shutdown()
        api = FakeAppApi(devices = listOf(1L to LONG_NAME, 2L to "Car"))
        server = MockWebServer().also { it.dispatcher = api; it.start() }
        api.stage(10, "Alpha||X", 1)
        setScreen(fontScale = LARGEST_FONT_SCALE)
        val send = waitForSend(LONG_NAME)
        val marker = layoutOf("this phone")
        // The marker keeps the width its text needs: one line, nothing ellipsised or cut.
        val needed = marker.multiParagraph.intrinsics.maxIntrinsicWidth
        assertEquals("the marker is on one line", 1, marker.lineCount)
        assertFalse("the marker is not ellipsised", marker.isLineEllipsized(0))
        assertTrue("the marker is ${marker.size.width}px wide, its text needs $needed", marker.size.width >= needed - 1)
        // The name gives way instead, on one line.
        assertEquals(1, layoutOf(LONG_NAME).lineCount)
        // The button is whole and on screen.
        send.assertIsDisplayed()
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        assertTrue("the button ends inside the screen", send.getUnclippedBoundsInRoot().right <= root.right)
    }

    @Test
    fun howSendingWorksIsBehindTheInfoIconAtTheEndOfTheList() {
        api.stage(10, "Alpha||X", 1)
        setScreen()
        waitForText("Basket · 1 item")
        compose.onAllNodesWithText("Sending a device sends its whole section", substring = true).assertCountEquals(0)
        compose.onNodeWithContentDescription("About Basket").performScrollTo().performClick()
        compose.onNodeWithText("Sending a device sends its whole section", substring = true).assertIsDisplayed()
    }

    @Test
    fun everyRowCarriesTheSameSquareOfArt() {
        api.stage(10, "Alpha||X", 1)
        api.stage(11, "Alpha", 1, type = "artist")
        api.stage(12, "42", 1, type = "playlist")
        api.stage(13, "77", 1, type = "track")
        setScreen()
        waitForText("Basket · 4 items")
        val art = compose.onAllNodesWithTag("basket_art").fetchSemanticsNodes().map { it.boundsInRoot }
        assertEquals("one square per row", 4, art.size)
        val side = art.first().height
        art.forEach {
            assertEquals("square", it.width, it.height, 0.5f)
            assertEquals("same size in every row", side, it.height, 0.5f)
        }
        // Rows are evenly spaced, so a row with no picture is not a different height.
        val gaps = art.map { it.top }.sorted().zipWithNext { a, b -> b - a }
        gaps.forEach { assertEquals("row pitch", gaps.first(), it, 0.5f) }
    }

    @Test
    fun itemsAreGroupedOneSectionPerDeviceAndThisPhoneIsMarked() {
        api.stage(10, "Alpha||X", 1, 2)
        api.stage(11, "Alpha||Y", 2)
        setScreen()
        waitForText("Car")
        compose.onNodeWithText("Pixel").assertIsDisplayed()
        compose.onNodeWithText("this phone").assertIsDisplayed()
        compose.onAllNodesWithText("X").assertCountEquals(2) // staged for both devices: once per section
        compose.onAllNodesWithText("Y").assertCountEquals(1)
        compose.onNodeWithText("Basket · 2 items").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send Car").assertIsDisplayed()
    }

    @Test
    fun anEmptyBasketSaysHowToStage() {
        setScreen()
        // The one screen someone opens because they have not found how to
        // stage: it names the gesture, since a single tap does nothing.
        waitForText("Nothing staged. Double-tap an album in the Library or on Home to add it for the device chosen at the top.")
    }

    @Test
    fun unstagingTheLastDeviceRemovesTheItem() {
        api.stage(11, "Alpha||Y", 2)
        setScreen()
        waitForText("Y")
        compose.onNodeWithContentDescription("Actions for Y").performClick()
        compose.onNodeWithText("Remove from Car").performClick()
        waitForText("Nothing staged")
        compose.onAllNodesWithText("Y").assertCountEquals(0)
        assertEquals(0, api.items.size)
    }

    @Test
    fun removingAnItemTakesItOffEveryDevice() {
        api.stage(10, "Alpha||X", 1, 2)
        setScreen()
        waitForText("Car")
        compose.onAllNodesWithContentDescription("Actions for X")[0].performClick()
        compose.onNodeWithText("Remove from basket").performClick()
        waitForText("Nothing staged")
        assertEquals(0, api.items.size)
    }

    @Test
    fun sendingAnotherDeviceReportsTheResultAndOffersNoSync() {
        api.stage(10, "Alpha||X", 2)
        api.stage(11, "Alpha||Y", 2)
        api.skippedOnSend = 1
        setScreen()
        waitForSend("Car").performClick()
        waitForText("Sent 2 selections to Car.")
        compose.onNodeWithText("1 item could not be sent and stays in the basket.", substring = true).assertIsDisplayed()
        assertEquals(listOf(listOf(2L)), api.sends)
        assertEquals(0, syncNowCalls)
        assertEquals("something was skipped: the basket stays", emptyList<Pair<String, Boolean>>(), sentAll)
    }

    @Test
    fun sendingAnotherDeviceWithNothingLeftHandsTheMessageBack() {
        api.stage(10, "Alpha||X", 2)
        setScreen()
        waitForSend("Car").performClick()
        compose.waitUntil(timeoutMillis = 30_000) { sentAll.isNotEmpty() }
        assertEquals(listOf("Sent 1 selection to Car." to false), sentAll)
        assertEquals(0, syncNowCalls)
        // Said once, where the user goes back to: not here as well.
        compose.onAllNodesWithText("Sent 1 selection", substring = true).assertCountEquals(0)
        assertEquals("a send elsewhere leaves Home's grids alone", 0, shared.state.value.thisPhoneSelections)
    }

    @Test
    fun sendingThisPhoneWithNothingLeftStartsTheSyncAndHandsBackTheOffer() {
        api.stage(10, "Alpha||X", 1)
        api.stage(11, "Alpha||Y", 1)
        setScreen()
        waitForSend("Pixel").performClick()
        compose.waitUntil(timeoutMillis = 30_000) { sentAll.isNotEmpty() }
        assertEquals(listOf("Sent 2 selections to Pixel. The sync has started." to true), sentAll)
        assertEquals(1, syncNowCalls)
        assertEquals(0, showSyncCalls)
        assertEquals("Home reads its grids again", 1, shared.state.value.thisPhoneSelections)
    }

    @Test
    fun aHeldBackSyncStaysOnTheBasketAndSaysWhy() {
        syncBlock = R.string.sync_blocked_needs_unmetered
        api.stage(10, "Alpha||X", 1)
        setScreen()
        waitForSend("Pixel").performClick()
        waitForText("Sent 1 selection to Pixel.")
        compose.onNodeWithText("unmetered connection", substring = true).assertIsDisplayed()
        assertEquals("nothing left, but held back: the reason stays in view", emptyList<Pair<String, Boolean>>(), sentAll)
        assertEquals(0, syncNowCalls)
    }

    @Test
    fun aSendWhoseReadBackFailsStaysOnTheBasketAndSaysSo() {
        api.stage(10, "Alpha||X", 2)
        api.basketReadFailsAfterSend = true
        setScreen()
        waitForSend("Car").performClick()
        // Two messages, one after the other in either order: what was sent, and
        // that the basket could not be read back. Both must be seen.
        val wanted = listOf("Sent 1 selection to Car.", "basket read failed")
        val seen = mutableSetOf<String>()
        compose.waitUntil(timeoutMillis = 30_000) {
            wanted.filterTo(seen) { compose.onAllNodesWithText(it, substring = true).fetchSemanticsNodes().isNotEmpty() }
            seen.size == wanted.size
        }
        assertEquals(listOf(listOf(2L)), api.sends)
        assertEquals("the read-back failed: the basket stays", emptyList<Pair<String, Boolean>>(), sentAll)
        compose.onNodeWithText("Basket · 1 item").assertIsDisplayed()
    }

    @Test
    fun aSendThatLeavesAnotherDevicesSectionStaysOnTheBasket() {
        api.stage(10, "Alpha||X", 2)
        api.stage(11, "Alpha||Y", 1)
        setScreen()
        waitForSend("Car").performClick()
        waitForText("Sent 1 selection to Car.")
        waitForSend("Pixel").assertIsDisplayed() // what is left to send is still in view
        assertEquals(emptyList<Pair<String, Boolean>>(), sentAll)
    }

    @Test
    fun sendingThisPhoneStartsTheSyncAndStillSaysWhatWasSent() {
        api.stage(10, "Alpha||X", 1)
        api.stage(11, "Alpha||Y", 1)
        api.skippedOnSend = 1
        setScreen()
        waitForSend("Pixel").performClick()
        compose.waitUntil(timeoutMillis = 30_000) { syncNowCalls == 1 } // started, with nothing to accept
        assertEquals(listOf(listOf(1L)), api.sends)
        // The result is not lost to the sync: what was sent, what stays behind, and that the sync runs.
        waitForText("Sent 2 selections to Pixel.")
        compose.onNodeWithText("1 item could not be sent and stays in the basket.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("The sync has started.", substring = true).assertIsDisplayed()
        assertEquals(0, showSyncCalls) // started where the user is; the sync is shown only when asked
        compose.onNodeWithText("Show").performClick()
        compose.waitUntil(timeoutMillis = 30_000) { showSyncCalls == 1 }
        assertEquals("something was skipped: the basket stays", emptyList<Pair<String, Boolean>>(), sentAll)
    }

    @Test
    fun clearEmptiesEveryDevice() {
        api.stage(10, "Alpha||X", 1, 2)
        setScreen()
        waitForText("Clear")
        compose.onNodeWithText("Clear").performClick()
        compose.onNodeWithText("Remove everything staged, for every device? Nothing already sent is affected.").assertIsDisplayed()
        compose.onNodeWithText("Clear basket").performClick()
        waitForText("Nothing staged")
        assertEquals(0, api.items.size)
    }

    private companion object {
        const val LONG_NAME = "Pixel 9 Pro XL in the blue case with the cracked corner"
        const val LARGEST_FONT_SCALE = 2f
    }
}
