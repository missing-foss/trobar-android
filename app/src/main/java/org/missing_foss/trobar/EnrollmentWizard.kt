// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization

/** #34/#162: enrollment wizard — the app's pairing flow. Scan the QR the web
 * UI shows (`{server_url, enroll_code}`), read it from an image (a
 * screenshot, a picture someone sent), or enter the server URL + 8-char code
 * by hand, then name the device / set its space limit + audio format, and
 * redeem the code. On success the server has created a device and returned its
 * Bearer token, which we store via [onEnrolled] and use from then on. The SAF
 * download folder is picked in the step that follows (client-local, never sent
 * to the server), so it isn't collected here. */
@Composable
fun EnrollmentWizard(onEnrolled: (String, String) -> Unit, initialServerUrl: String = "") {
    // #101: re-pairing after a credentials-unreadable failure offers the
    // still-known server URL back, so it's one QR/code away rather than a
    // fully blank form — the URL itself was never the part that failed.
    var serverUrl by remember { mutableStateOf(initialServerUrl.ifBlank { "https://" }) }
    var code by remember { mutableStateOf("") }
    var showDetails by remember { mutableStateOf(false) }
    var enrolling by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // A QR read by either route goes through parsePairingQr; one that is
    // not a pairing QR says so instead of leaving the form as it was.
    var qrMessage by remember { mutableStateOf<String?>(null) }
    fun applyQr(qr: PairingQr?, notFound: Int) {
        if (qr == null) {
            qrMessage = context.getString(notFound)
            return
        }
        serverUrl = qr.serverUrl
        code = qr.code
        qrMessage = null
        error = null
        showDetails = true
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        // No contents: the scan was cancelled, which needs no message.
        val raw = result.contents ?: return@rememberLauncherForActivityResult
        applyQr(parsePairingQr(raw), R.string.qr_not_a_pairing_code)
    }

    // The system photo picker: no storage permission, and on API 26-29 it
    // falls back to the document picker.
    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.Default) { decodeQrFromImage(context, uri) }
            applyQr(parsePairingQr(text), R.string.qr_none_in_image)
        }
    }

    fun enroll(name: String, spaceGb: String, transcode: String) {
        scope.launch {
            enrolling = true
            error = null
            try {
                val bytes = spaceGb.replace(",", ".").toDoubleOrNull()?.let { (it * BYTES_PER_GB).toLong() }
                val result = withContext(Dispatchers.IO) {
                    redeemEnrollment(
                        context, serverUrl.trim(), code.trim().uppercase(),
                        name.trim(), "phone", bytes, transcode.ifBlank { null },
                    )
                }
                onEnrolled(serverUrl.trim(), result.token)
            } catch (e: Exception) {
                error = e.message ?: context.getString(R.string.enroll_failed_generic)
            }
            enrolling = false
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        BrandHeader(stringResource(R.string.enroll_title), stringResource(R.string.enroll_subtitle))
        Column(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .then(adaptiveContentWidth())
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!showDetails) {
                QrButtons(
                    onScan = {
                        qrMessage = null
                        // The prompt in the app's language: zxing's own screen is
                        // another activity, which below API 33 never sees the
                        // in-app language choice (AppLanguage).
                        scanLauncher.launch(
                            ScanOptions().setOrientationLocked(false).setPrompt(context.getString(R.string.scan_qr_prompt)),
                        )
                    },
                    message = qrMessage,
                )
                ConnectStep(
                    serverUrl = serverUrl, onServerUrl = { serverUrl = it },
                    code = code, onCode = { code = enrollCodeOf(it) },
                    onImportImage = {
                        qrMessage = null
                        imageLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onContinue = { error = null; showDetails = true },
                )
            } else {
                DetailsStep(
                    error = error, enrolling = enrolling,
                    onEnroll = { name, spaceGb, transcode -> enroll(name, spaceGb, transcode) },
                    onBack = { showDetails = false; error = null },
                )
            }
        }
    }
}

/** Step 1, the QR routes: the camera, or a QR in an image. [message] says
 * why the last QR read did not pair. */
@Composable
private fun QrButtons(onScan: () -> Unit, message: String?) {
    Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.scan_qr_button))
    }
    message?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("qr_message"),
        )
    }
}

/** Step 1, by hand: the server URL + enrollment code. */
@Composable
private fun ColumnScope.ConnectStep(
    serverUrl: String,
    onServerUrl: (String) -> Unit,
    code: String,
    onCode: (String) -> Unit,
    onImportImage: () -> Unit,
    onContinue: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    Text(
        stringResource(R.string.enroll_or_manual),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.outline,
    )
    OutlinedTextField(
        value = serverUrl, onValueChange = onServerUrl, singleLine = true,
        label = { Text(stringResource(R.string.server_url_label)) },
        modifier = Modifier.fillMaxWidth(),
    )
    EnrollCodeBoxes(code, onCode)
    // Stacked, full width: neither label ever shares its row with the other.
    OutlinedButton(
        onClick = { clipboard.getText()?.let { onCode(it.text) } },
        modifier = Modifier.fillMaxWidth().testTag("enroll_paste"),
    ) { Text(stringResource(R.string.enroll_paste)) }
    OutlinedButton(onClick = onImportImage, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.import_qr_image_button))
    }
    Button(
        onClick = onContinue,
        enabled = isServerUrl(serverUrl) && code.length == ENROLL_CODE_LENGTH,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.enroll_continue)) }
}

/** The code the server hands out: upper case, letters and digits only, and
 * what's over the length dropped, so a paste with spaces or a line end works. */
internal fun enrollCodeOf(text: String): String =
    text.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }.take(ENROLL_CODE_LENGTH)

/** One field, drawn as a box per character. */
@Composable
private fun EnrollCodeBoxes(code: String, onCode: (String) -> Unit) {
    val label = stringResource(R.string.enroll_code_label)
    Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.fillMaxWidth())
    BasicTextField(
        value = code, onValueChange = onCode, singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
        cursorBrush = SolidColor(Color.Transparent),
        modifier = Modifier.fillMaxWidth().testTag("enroll_code").semantics { contentDescription = label },
        decorationBox = { field ->
            Box {
                Row(horizontalArrangement = Arrangement.spacedBy(CODE_BOX_GAP)) {
                    repeat(ENROLL_CODE_LENGTH) { i ->
                        Box(
                            Modifier.weight(1f).heightIn(min = CODE_BOX_HEIGHT)
                                .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small),
                            contentAlignment = Alignment.Center,
                        ) { Text(code.getOrNull(i)?.toString().orEmpty(), style = MaterialTheme.typography.titleMedium) }
                    }
                }
                // The real field, unseen: it holds the cursor and the keyboard.
                Box(Modifier.matchParentSize().alpha(0f)) { field() }
            }
        },
    )
}

/** Step 2: device settings sent to the server on redemption (name required),
 * plus the Enroll action. Owns the field state — the wizard only needs the
 * final values, handed back through [onEnroll]. */
@Composable
private fun ColumnScope.DetailsStep(
    error: String?,
    enrolling: Boolean,
    onEnroll: (name: String, spaceGb: String, transcode: String) -> Unit,
    onBack: () -> Unit,
) {
    var name by remember { mutableStateOf(Build.MODEL ?: "") }
    var spaceGb by remember { mutableStateOf("") }
    var transcode by remember { mutableStateOf("") } // "" = originals; else "mp3_320"
    OutlinedTextField(
        value = name, onValueChange = { name = it }, singleLine = true,
        label = { Text(stringResource(R.string.device_name_label)) },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = spaceGb, onValueChange = { spaceGb = it }, singleLine = true,
        label = { Text(stringResource(R.string.limit_gb_field_label)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    ChoiceRow(
        icon = Icons.Filled.GraphicEq,
        label = stringResource(R.string.transcode_label),
        options = listOf(
            "" to stringResource(R.string.transcode_originals),
            "mp3_320" to stringResource(R.string.transcode_mp3_320),
        ),
        selected = transcode,
        onSelect = { transcode = it },
    )
    Text(
        stringResource(R.string.enroll_code_expiry_note),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        textAlign = TextAlign.Center,
    )
    error?.let { CopyableError(it) }
    Button(
        onClick = { onEnroll(name, spaceGb, transcode) },
        enabled = name.isNotBlank() && !enrolling,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (enrolling) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp), strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Text(stringResource(R.string.enroll_button))
        }
    }
    OutlinedButton(
        onClick = onBack, enabled = !enrolling, modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.enroll_back)) }
}

private const val ENROLL_CODE_LENGTH = 8
private val CODE_BOX_HEIGHT = 44.dp
private val CODE_BOX_GAP = 4.dp
