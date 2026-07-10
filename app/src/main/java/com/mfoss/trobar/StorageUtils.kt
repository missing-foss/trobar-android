// SPDX-License-Identifier: GPL-3.0-or-later
package com.mfoss.trobar

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.DocumentsContract
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
    } catch (e: Exception) {
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
            } catch (e: Exception) {
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
    } catch (e: Exception) {
        null
    }
}
