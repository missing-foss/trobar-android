// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun FolderPickerScreen(onPicked: (android.net.Uri) -> Unit) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) onPicked(uri)
    }
    Column(modifier = Modifier.fillMaxSize()) {
        BrandHeader(stringResource(R.string.folder_picker_title), stringResource(R.string.folder_picker_subtitle))
        Column(
            modifier = Modifier.align(Alignment.CenterHorizontally).then(adaptiveContentWidth()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { launcher.launch(null) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.choose_folder_button)) }
            // #87: the recovery-manifest walk (SyncEngine.recoverIfPending)
            // already avoids re-downloading anything already present in
            // whatever folder gets picked here — it fires on every fresh
            // enrollment, not just a same-phone reinstall. That mechanism
            // predates this hint; the hint is the only thing that was
            // actually missing.
            Text(
                stringResource(R.string.folder_picker_recovery_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// one entry of the "found missing locally, awaiting a decision"
// batch persisted by SyncWorker (see Prefs.pendingMissingTracks).
