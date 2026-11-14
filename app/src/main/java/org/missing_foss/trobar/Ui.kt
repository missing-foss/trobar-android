// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** "Trob" in the ambient text color, "ar" in the brand rose accent —
 * matches the web app's wordmark treatment (`Tro<b>bar</b>`). */
@Composable
fun trobarWordmark(): AnnotatedString = buildAnnotatedString {
    append("Trob")
    withStyle(SpanStyle(color = MaterialTheme.colorScheme.secondary)) { append("ar") }
}

/** Best-effort human-readable rendering of a SAF tree URI — not a real
 * filesystem path (SAF doesn't expose one), but the decoded document ID
 * ("primary:Music/Trobar") is recognizable enough to confirm the right
 * folder is selected. */
fun humanReadablePath(context: Context, uri: Uri): String = try {
    DocumentsContract.getTreeDocumentId(uri).replace("primary:", context.getString(R.string.internal_storage_prefix))
} catch (ignored: Exception) {
    uri.toString()
}

/** Error text + a "Copier" button — added because plain on-screen error text
 * isn't useful for reporting a bug (no way to get the exact message off the
 * phone without retyping it by hand). Kept as persistent inline text rather
 * than a Snackbar: lastSyncError survives app restarts and should still be
 * visible (and copyable) whenever this screen is open, not just briefly
 * after the failure happens. */
@Composable
fun CopyableError(text: String, onDismiss: (() -> Unit)? = null) {
    val clipboard = LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // #45: a long/multi-line server error is bounded and scrolls in place,
        // so it can't push the rest of the Status screen off a short viewport;
        // the copy/clear actions below always stay reachable. A no-op for the
        // common short error (below the cap).
        Text(
            text,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .heightIn(max = ERROR_MAX_HEIGHT)
                .verticalScroll(rememberScrollState()),
        )
        Row {
            TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) {
                Text(stringResource(R.string.copy_error_button))
            }
            if (onDismiss != null) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.clear_error_button))
                }
            }
        }
    }
}


@Composable
fun TileRow(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: (() -> Unit)? = null,
    description: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        val text = @Composable {
            Column {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                Text(value, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline)
                if (description != null) {
                    Text(description, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        if (onClick == null) {
            Box(Modifier.weight(1f)) { text() }
        } else {
            // The arrow goes under the text when a word of it would not fit
            // beside the arrow, instead of that word breaking in two.
            LabelBesideControls(
                label = text,
                controls = {
                    Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = null,
                        modifier = Modifier.padding(start = 16.dp).size(14.dp), tint = MaterialTheme.colorScheme.outline)
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
fun SwitchRow(
    icon: ImageVector,
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // The whole row toggles, not just the small Switch thumb — a bigger
            // tap target and one screen-reader stop. toggleable() merges
            // descendant semantics on its own, so the Switch below is set to
            // onCheckedChange = null: it stays purely visual so its own touch
            // target doesn't compete with the Row's.
            .toggleable(value = checked, onValueChange = onCheckedChange, role = Role.Switch)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        // The switch goes under the text when a word of it would not fit
        // beside the switch, instead of that word breaking in two.
        LabelBesideControls(
            label = {
                Column {
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                    Text(description, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline)
                }
            },
            controls = { Switch(checked = checked, onCheckedChange = null, modifier = Modifier.padding(start = 16.dp)) },
            modifier = Modifier.weight(1f),
        )
    }
}

/** A TileRow whose value is picked from a radio list in a popup — replaces
 * the segmented button rows, whose three cells clipped the longer French
 * labels. The row shows the current choice; the dialog shows the full-length
 * option labels plus the setting's description when there is one. */
@Composable
fun ChoiceRow(
    icon: ImageVector,
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    description: String? = null,
    onSelect: (String) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    TileRow(icon, label, options.firstOrNull { it.first == selected }?.second ?: "",
        onClick = { showDialog = true })
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(label) },
            text = {
                Column {
                    if (description != null) {
                        Text(description, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(bottom = 8.dp))
                    }
                    options.forEach { (value, optionLabel) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showDialog = false; onSelect(value) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            RadioButton(selected = selected == value, onClick = null)
                            Text(optionLabel, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text(stringResource(R.string.cancel_button)) }
            },
        )
    }
}

/** What the app accepts as a server address: a scheme it can build a
 * request from. OkHttp refuses anything else with an IllegalArgumentException
 * at request time, so the check belongs where the address is stored, before
 * either probe can meet it. */
fun isServerUrl(text: String): Boolean = text.trim().let {
    (it.startsWith("http://") || it.startsWith("https://")) && it.substringAfter("://").isNotEmpty()
}

@Composable
fun EditValueDialog(
    title: String,
    label: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    // Disables Save rather than showing an inline error — matches
    // EnrollmentWizard's existing disable-until-valid convention rather than
    // introducing a new error-message pattern for one dialog. Defaults to
    // "anything goes" so existing callers (storage limit, etc.) are unaffected.
    isValid: (String) -> Boolean = { true },
) {
    var field by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = field, onValueChange = { field = it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(field) }, enabled = isValid(field)) { Text(stringResource(R.string.save_button)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_button)) } },
    )
}

@Composable
fun SectionLabel(text: String, info: String? = null) {
    val style = MaterialTheme.typography.labelSmall
    if (info == null) {
        Text(text, style = style, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 4.dp))
    } else {
        LabelWithInfo(
            text, info, Modifier.fillMaxWidth().padding(start = 4.dp), style = style, color = MaterialTheme.colorScheme.outline,
        )
    }
}

/** A label with its info icon right after it. The icon is measured first
 * and always keeps its width: a label too long for the row wraps, it does
 * not push the icon out -- a Row measures unweighted children first, so a
 * plain label before the icon took the whole row at 320 dp and 2.0x and
 * left the icon zero wide. `fill = false` keeps the icon beside a short
 * label rather than at the far end. [modifier] sizes the pair: give it a
 * weight in a row that has a control after it. */
@Composable
fun LabelWithInfo(
    label: String,
    info: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = style, color = color, modifier = Modifier.weight(1f, fill = false))
        InfoButton(about = label, text = info)
    }
}

/** An explanation kept off the screen until asked for: an info icon beside
 * the heading or label it explains, whose tap opens the text. A tap, not a
 * long press, which a phone never hints at; a dialog, which TalkBack reads
 * and which holds a long text at a large font scale. */
@Composable
fun InfoButton(about: String, text: String, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = modifier) {
        Icon(
            Icons.Outlined.Info, contentDescription = stringResource(R.string.info_about, about),
            tint = MaterialTheme.colorScheme.outline,
        )
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(about) },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.close_button)) } },
        )
    }
}

/** Bytes per gigabyte as the server counts them (decimal), shared by every screen that shows or takes a size in GB. */
internal const val BYTES_PER_GB = 1e9
