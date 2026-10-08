// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.DocumentsContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

data class StorageStats(val freeBytes: Long, val totalBytes: Long)

/** Free + total space of the SAF tree's underlying storage volume.
 *
 * Tries the standard Root.COLUMN_AVAILABLE_BYTES/COLUMN_CAPACITY_BYTES
 * columns first. Confirmed directly this isn't reliable though: free space
 * came back null both for a Pixel's internal storage AND for a DAP's
 * actual SD card — apparently neither ExternalStorageProvider
 * implementation populates it consistently.
 *
 * Falls back to matching the tree's volume against one of
 * ContextCompat.getExternalFilesDirs() — those app-specific directories are
 * guaranteed to exist on every available storage volume without needing
 * any extra permission, and free/total space are properties of the whole
 * volume, so StatFs on any accessible path on it gives the right numbers
 * even though it isn't the user's actual SAF folder. */
fun storageStatsForTree(context: Context, treeUri: Uri): StorageStats? {
    val rootId = try {
        DocumentsContract.getTreeDocumentId(treeUri).substringBefore(":")
    } catch (ignored: Exception) {
        return null
    }

    storageStatsFromRootsQuery(context, treeUri)?.let { return it }

    val candidateDirs = ContextCompat.getExternalFilesDirs(context, null)
    for (dir in candidateDirs) {
        if (dir == null) continue
        val path = dir.absolutePath
        val matches = if (rootId == "primary") {
            path.contains("/storage/emulated/")
        } else {
            path.contains("/storage/$rootId")
        }
        if (matches) {
            return try {
                val stat = StatFs(path)
                StorageStats(stat.availableBytes, stat.totalBytes)
            } catch (ignored: Exception) {
                null
            }
        }
    }
    return null
}

private fun storageStatsFromRootsQuery(context: Context, treeUri: Uri): StorageStats? {
    return try {
        val authority = treeUri.authority ?: return null
        val rootId = DocumentsContract.getTreeDocumentId(treeUri).substringBefore(":")
        val rootsUri = DocumentsContract.buildRootsUri(authority)
        context.contentResolver.query(
            rootsUri,
            arrayOf(
                DocumentsContract.Root.COLUMN_ROOT_ID,
                DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
                DocumentsContract.Root.COLUMN_CAPACITY_BYTES,
            ),
            null, null, null,
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_ROOT_ID)
            val freeIdx = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES)
            val totalIdx = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_CAPACITY_BYTES)
            if (idIdx < 0 || freeIdx < 0 || totalIdx < 0) return null
            while (cursor.moveToNext()) {
                if (cursor.getString(idIdx) == rootId) {
                    if (cursor.isNull(freeIdx) || cursor.isNull(totalIdx)) return null
                    return StorageStats(cursor.getLong(freeIdx), cursor.getLong(totalIdx))
                }
            }
            null
        }
    } catch (ignored: Exception) {
        null
    }
}

/** The volume's use as a bar, "x GB free of y GB", and a warning when the
 * device's allocated space ([limitBytes]) is more than is free. Settings shows
 * it under the allocated space; Simple mode on its Sync screen. */
@Composable
fun StorageUsage(stats: StorageStats, limitBytes: Long?, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LinearProgressIndicator(
            progress = { (stats.totalBytes - stats.freeBytes).toFloat() / stats.totalBytes.toFloat() },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.storage_free_of_total, formatGB(stats.freeBytes), formatGB(stats.totalBytes)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        if (limitBytes != null && limitBytes > stats.freeBytes) {
            Text(
                stringResource(R.string.limit_exceeds_free_space),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
