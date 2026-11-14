// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
// #61: the pairing flow, end-to-end against a real local HTTP server standing
// in for trobar-server — exercises the manual URL+code path through both
// wizard steps and the real redeemEnrollment() network call. The two QR
// routes are driven through a stand-in result registry: the camera's answer
// is what zxing's own Activity would return (that Activity isn't this app's
// code to verify), and the photo picker's is a real image file holding a QR.

package org.missing_foss.trobar

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BarcodeFormat
import com.google.zxing.client.android.Intents
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import java.util.Locale
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsEnabled
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

    /** What the camera or the picker hands back when launched: the scanned
     * text (null = cancelled), or the picked image (null = nothing picked). */
    private var scanned: String? = null
    private var picked: Uri? = null
    private var launches = 0

    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            launches++
            val result = if (contract is ActivityResultContracts.PickVisualMedia) {
                picked?.let { Intent().setData(it) }
            } else {
                scanned?.let { Intent().putExtra(Intents.Scan.RESULT, it) }
            }
            dispatchResult(requestCode, if (result != null) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
        }
    }

    private fun setWizard(widthDp: Int? = null, fontScale: Float? = null, french: Boolean = false) {
        compose.setContent {
            val base = LocalContext.current
            val config = Configuration(base.resources.configuration).apply { if (french) setLocale(Locale.FRENCH) }
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides base.createConfigurationContext(config),
                LocalConfiguration provides config,
                LocalDensity provides Density(density.density, fontScale ?: density.fontScale),
                LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner {
                    override val activityResultRegistry = registry
                },
            ) {
                MaterialTheme {
                    Box(if (widthDp != null) Modifier.requiredWidth(widthDp.dp) else Modifier) {
                        EnrollmentWizard(onEnrolled = { url, token -> enrolledUrl = url; enrolledToken = token })
                    }
                }
            }
        }
    }

    /** Drives ConnectStep with the server's own URL — real callers get this
     * from the QR/manual entry; the test supplies it directly since it's the
     * one value that has to point at the MockWebServer instance. */
    private fun connect(code: String = "ABCD1234") {
        compose.onNodeWithText("Server URL").performTextReplacement(server.url("/").toString())
        compose.onNodeWithTag("enroll_code").performTextInput(code)
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

        compose.waitUntil(timeoutMillis = UI_WAIT_MS) { enrolledToken != null }
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

        compose.waitUntil(timeoutMillis = UI_WAIT_MS) {
            compose.onAllNodesWithText("Invalid or expired code").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Invalid or expired code").assertIsDisplayed()
        assertNull(enrolledToken)
    }

    @Test
    fun theAddressStartsAtHttpsAndThatAloneIsNotAnAddress() {
        setWizard()
        compose.onNodeWithText("https://").assertExists()
        compose.onNodeWithTag("enroll_code").performTextInput("ABCD1234")
        compose.onNodeWithText("Continue").assertIsNotEnabled()
        compose.onNodeWithText("Server URL").performTextReplacement("music.example")
        compose.onNodeWithText("Continue").assertIsNotEnabled()
        compose.onNodeWithText("Server URL").performTextReplacement("https://music.example")
        compose.onNodeWithText("Continue").assertIsEnabled()
    }

    @Test
    fun aShortCodeIsNotEnough() {
        setWizard()
        compose.onNodeWithText("Server URL").performTextReplacement("https://music.example")
        compose.onNodeWithTag("enroll_code").performTextInput("ABCD123")
        compose.onNodeWithText("Continue").assertIsNotEnabled()
    }

    @Test
    fun theCodeShowsInOneBoxPerCharacterInUpperCase() {
        setWizard()
        compose.onNodeWithTag("enroll_code").performTextInput("ab cd")
        compose.onNodeWithText("A", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("D", useUnmergedTree = true).assertExists()
    }

    @Test
    fun pasteFillsTheCodeFromTheClipboard() {
        setWizard()
        compose.runOnUiThread {
            InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(android.content.ClipboardManager::class.java)
                .setPrimaryClip(android.content.ClipData.newPlainText("code", " abcd-1234\n"))
        }
        compose.onNodeWithTag("enroll_paste").performClick()
        compose.onNodeWithText("Server URL").performTextReplacement("https://music.example")
        compose.onNodeWithText("Continue").assertIsEnabled()
    }

    @Test
    fun aCodeIsCleanedOfWhatAPasteBrings() {
        assertEquals("ABCD1234", enrollCodeOf(" abcd-1234\n"))
        assertEquals("ABCD1234", enrollCodeOf("ABCD1234EXTRA"))
    }

    @Test
    fun continueDisabledUntilBothFieldsFilled() {
        setWizard()
        compose.onNodeWithText("Continue").assertIsNotEnabled()
        compose.onNodeWithText("Server URL").performTextReplacement(server.url("/").toString())
        compose.onNodeWithText("Continue").assertIsNotEnabled()
        compose.onNodeWithTag("enroll_code").performTextInput("ABCD1234")
        // Now both fields are filled — Continue advances (checked by the
        // presence of step 2's field, the same "did it navigate" signal
        // the other tests use).
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Device name").assertIsDisplayed()
    }

    @Test
    fun aPairingQrInAnImageFillsTheFormAndMovesOn() {
        picked = qrImage(PairingQrTest.PAYLOAD)
        setWizard()
        compose.onNodeWithText("Import QR from an image").performClick()
        compose.waitForText("Device name")
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("https://music.example.org").assertIsDisplayed()
        compose.onNodeWithText("ABCD1234").assertIsDisplayed()
    }

    @Test
    fun anImageWithoutAPairingQrSaysSoAndStays() {
        picked = qrImage("https://example.org/not-a-pairing-code")
        setWizard()
        compose.onNodeWithText("Import QR from an image").performClick()
        compose.waitForText("No pairing QR code found in this image.")
        compose.onNodeWithText("Continue").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("Device name").fetchSemanticsNodes().size)
    }

    @Test
    fun nothingPickedSaysNothing() {
        picked = null
        setWizard()
        compose.onNodeWithText("Import QR from an image").performClick()
        compose.waitForIdle()
        assertEquals(1, launches)
        assertEquals(0, compose.onAllNodes(hasTestTag("qr_message")).fetchSemanticsNodes().size)
    }

    @Test
    fun aScannedPairingQrFillsTheFormAndMovesOn() {
        scanned = """{"server_url": "https://music.example.org", "code": "abcd1234"}"""
        setWizard()
        compose.onNodeWithText("Scan QR code").performClick()
        compose.waitForText("Device name")
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("ABCD1234").assertIsDisplayed()
    }

    @Test
    fun aScannedQrThatIsNotAPairingCodeSaysSo() {
        scanned = "https://example.org/not-a-pairing-code"
        setWizard()
        compose.onNodeWithText("Scan QR code").performClick()
        compose.waitForText("This QR code is not a pairing code.")
        compose.onNodeWithText("Continue").assertIsDisplayed()
    }

    @Test
    fun aCancelledScanSaysNothing() {
        scanned = null
        setWizard()
        compose.onNodeWithText("Scan QR code").performClick()
        compose.waitForIdle()
        assertEquals(1, launches)
        assertEquals(0, compose.onAllNodes(hasTestTag("qr_message")).fetchSemanticsNodes().size)
    }

    @Test
    fun theTwoQrButtonsBreakBetweenWordsOnlyAtTheNarrowestWidthInFrenchAtTheLargestScale() {
        picked = qrImage("not a pairing code")
        setWizard(widthDp = NARROWEST_PHONE_DP, fontScale = LARGEST_FONT_SCALE, french = true)
        val fr = InstrumentationRegistry.getInstrumentation().targetContext.let {
            it.createConfigurationContext(Configuration(it.resources.configuration).apply { setLocale(Locale.FRENCH) })
        }
        // The message too: it shows under the buttons, in the same width.
        compose.onNodeWithText(fr.getString(R.string.import_qr_image_button)).performClick()
        compose.waitForText(fr.getString(R.string.qr_none_in_image))
        for (id in listOf(R.string.scan_qr_button, R.string.import_qr_image_button, R.string.qr_none_in_image)) {
            val text = fr.getString(id)
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
                .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
            val layout = layouts.single()
            for (line in 0 until layout.lineCount - 1) {
                val end = layout.getLineEnd(line)
                assertTrue("\"$text\" breaks mid-word after \"${text.substring(0, end)}\"", text[end - 1] == ' ' || text.getOrNull(end) == ' ')
            }
        }
    }

    /** A PNG holding a QR of [text], as the picker would hand it over. */
    private fun qrImage(text: String): Uri {
        val side = 600
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, side, side)
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        for (y in 0 until side) for (x in 0 until side) bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("qr-image", ".png", context.cacheDir).also { it.deleteOnExit() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return Uri.fromFile(file)
    }

    private companion object {
        const val NARROWEST_PHONE_DP = 320 // the smallest width Android's compatibility definition allows a phone
        const val LARGEST_FONT_SCALE = 2f
    }
}
