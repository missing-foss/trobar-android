// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
// The pairing QR's one parser, shared by the camera and an imported image,
// and the decoder that reads a QR out of an image: a screenshot, a large
// photo (decoded downscaled), an image with no QR, and a file that is not an
// image at all. The QR images are drawn here with zxing's own encoder.

package org.missing_foss.trobar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PairingQrTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val files = mutableListOf<File>()

    @After
    fun deleteFiles() {
        files.forEach { it.delete() }
    }

    @Test
    fun aPairingQrGivesItsServerAndCode() {
        assertEquals(
            PairingQr("https://music.example.org", "ABCD1234"),
            parsePairingQr("""{"server_url": "https://music.example.org", "enroll_code": "abcd1234"}"""),
        )
    }

    @Test
    fun anOlderServersCodeKeyIsRead() {
        assertEquals(
            PairingQr("https://music.example.org", "ABCD1234"),
            parsePairingQr("""{"server_url": "https://music.example.org", "code": "ABCD1234"}"""),
        )
    }

    @Test
    fun anythingElseIsNotAPairingQr() {
        assertNull(parsePairingQr(null))
        assertNull(parsePairingQr(""))
        assertNull(parsePairingQr("https://music.example.org"))
        assertNull(parsePairingQr("""{"server_url": "https://music.example.org"}"""))
        assertNull(parsePairingQr("""{"enroll_code": "ABCD1234"}"""))
        assertNull(parsePairingQr("""{"server_url": " ", "enroll_code": "ABCD1234"}"""))
        assertNull(parsePairingQr("""["https://music.example.org", "ABCD1234"]"""))
    }

    @Test
    fun aQrIsReadFromAScreenshot() {
        val uri = png(qrOnCanvas(SCREENSHOT_WIDTH, SCREENSHOT_HEIGHT, qrSide = 600))
        assertEquals(PAYLOAD, decodeQrFromImage(context, uri))
    }

    @Test
    fun aQrIsReadFromALargePhotoDecodedSmaller() {
        // 6000 px on its long side: read at 1500, where the QR is still 375 px.
        val uri = png(qrOnCanvas(PHOTO_WIDTH, PHOTO_HEIGHT, qrSide = 1500))
        assertEquals(PAYLOAD, decodeQrFromImage(context, uri))
    }

    @Test
    fun anImageWithNoQrGivesNothing() {
        val blank = Bitmap.createBitmap(SCREENSHOT_WIDTH, SCREENSHOT_HEIGHT, Bitmap.Config.ARGB_8888)
        blank.eraseColor(Color.WHITE)
        assertNull(decodeQrFromImage(context, png(blank)))
    }

    @Test
    fun aFileThatIsNotAnImageGivesNothing() {
        val file = File.createTempFile("not-an-image", ".png", context.cacheDir).also { files += it }
        file.writeText("not an image")
        assertNull(decodeQrFromImage(context, Uri.fromFile(file)))
    }

    @Test
    fun aMissingFileGivesNothing() {
        assertNull(decodeQrFromImage(context, Uri.fromFile(File(context.cacheDir, "no-such-image.png"))))
    }

    /** A white canvas with the pairing QR, [qrSide] px square, in its middle. */
    private fun qrOnCanvas(width: Int, height: Int, qrSide: Int): Bitmap {
        val matrix = QRCodeWriter().encode(PAYLOAD, BarcodeFormat.QR_CODE, qrSide, qrSide)
        val qr = Bitmap.createBitmap(qrSide, qrSide, Bitmap.Config.ARGB_8888)
        for (y in 0 until qrSide) for (x in 0 until qrSide) qr.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        val canvas = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        canvas.eraseColor(Color.WHITE)
        Canvas(canvas).drawBitmap(qr, ((width - qrSide) / 2).toFloat(), ((height - qrSide) / 2).toFloat(), null)
        qr.recycle()
        return canvas
    }

    private fun png(bitmap: Bitmap): Uri {
        val file = File.createTempFile("qr-image", ".png", context.cacheDir).also { files += it }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return Uri.fromFile(file)
    }

    companion object {
        const val PAYLOAD = """{"server_url": "https://music.example.org", "enroll_code": "ABCD1234"}"""
        private const val SCREENSHOT_WIDTH = 1080
        private const val SCREENSHOT_HEIGHT = 2400
        private const val PHOTO_WIDTH = 6000
        private const val PHOTO_HEIGHT = 4000
    }
}
