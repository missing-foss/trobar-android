// SPDX-License-Identifier: GPL-3.0-or-later
package com.mfoss.trobar

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** #34/#162: enrollment wizard — the app's pairing flow. Scan the QR the web
 * UI shows (`{server_url, enroll_code}`) or enter the server URL + 8-char code
 * by hand, then name the device / set its space limit + audio format, and
 * redeem the code. On success the server has created a device and returned its
 * Bearer token, which we store via [onEnrolled] and use from then on. The SAF
 * download folder is picked in the step that follows (client-local, never sent
 * to the server), so it isn't collected here. */
@Composable
fun EnrollmentWizard(onEnrolled: (String, String) -> Unit) {
    var serverUrl by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var showDetails by remember { mutableStateOf(false) }
    var enrolling by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val raw = result.contents ?: return@rememberLauncherForActivityResult
        try {
            val json = JSONObject(raw)
            serverUrl = json.getString("server_url")
            code = json.optString("enroll_code").ifBlank { json.optString("code") }.trim().uppercase()
            error = null
            if (serverUrl.isNotBlank() && code.isNotBlank()) showDetails = true
        } catch (ignored: Exception) {
            // Not an enrollment QR — the user can still type the URL + code below.
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
                ConnectStep(
                    serverUrl = serverUrl, onServerUrl = { serverUrl = it },
                    code = code, onCode = { code = it.trim().uppercase() },
                    onScan = { scanLauncher.launch(ScanOptions().setOrientationLocked(false)) },
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

/** Step 1: acquire the server URL + enrollment code (QR scan or manual entry). */
@Composable
private fun ColumnScope.ConnectStep(
    serverUrl: String,
    onServerUrl: (String) -> Unit,
    code: String,
    onCode: (String) -> Unit,
    onScan: () -> Unit,
    onContinue: () -> Unit,
) {
    Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.scan_qr_button))
    }
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
    OutlinedTextField(
        value = code, onValueChange = onCode, singleLine = true,
        label = { Text(stringResource(R.string.enroll_code_label)) },
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = onContinue,
        enabled = serverUrl.isNotBlank() && code.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.enroll_continue)) }
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

private const val BYTES_PER_GB = 1e9
