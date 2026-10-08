// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.ReaderException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.json.JSONException
import org.json.JSONObject

/** What the web UI's pairing QR carries: `{server_url, enroll_code}` (`code`
 * in older servers). */
internal data class PairingQr(val serverUrl: String, val code: String)

/** The one reading of a pairing QR's text, for the camera and for an
 * imported image alike. Null for anything else: no text, not JSON, or JSON
 * missing either value. */
internal fun parsePairingQr(text: String?): PairingQr? {
    if (text.isNullOrBlank()) return null
    val json = try {
        JSONObject(text)
    } catch (ignored: JSONException) {
        return null
    }
    val serverUrl = json.optString("server_url").trim()
    val code = json.optString("enroll_code").ifBlank { json.optString("code") }.trim().uppercase()
    return if (serverUrl.isNotEmpty() && code.isNotEmpty()) PairingQr(serverUrl, code) else null
}

/** The longest side an image is decoded at. A screenshot keeps its size;
 * a camera photo is read at a fraction of it, which a QR survives and which
 * keeps a 50-megapixel picture from filling the heap. */
private const val MAX_DECODE_SIDE = 2048

/** The text of the QR code in the image at [uri], or null when the image
 * cannot be read or holds no QR code. Blocking: call it off the main thread.
 * The image is streamed from the provider and only held in memory while it
 * is decoded; nothing is copied or kept, since the code it carries is a
 * credential. */
internal fun decodeQrFromImage(context: Context, uri: Uri): String? {
    val bitmap = loadDownscaled(context, uri) ?: return null
    try {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
        )
        return try {
            MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (ignored: ReaderException) {
            null
        }
    } finally {
        bitmap.recycle()
    }
}

private fun loadDownscaled(context: Context, uri: Uri): Bitmap? = try {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    if (longest <= 0) {
        null
    } else {
        var sample = 1
        while (longest / sample > MAX_DECODE_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }
} catch (ignored: java.io.IOException) {
    null
} catch (ignored: SecurityException) {
    null
}
