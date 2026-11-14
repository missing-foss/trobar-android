// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import java.io.File

/** Tells the OS's media index about the files a sync wrote or removed, so
 * players that read MediaStore (Poweramp and friends) see them at once
 * rather than whenever the OS next scans, which on some devices is after a
 * reboot. MediaScannerConnection takes filesystem paths, not SAF URIs, so
 * the picked folder is mapped back to its directory first. */
object MediaIndex {
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private const val BATCH = 200

    /** How the paths reach the scanner; a test records them instead. */
    internal var scanPaths: (Context, Array<String>) -> Unit = { c, paths ->
        MediaScannerConnection.scanFile(c, paths, null, null)
    }

    /** After a sync: index what it changed, unless the folder is hidden from
     * media apps by .nomedia, where indexing it would undo what was asked.
     * Returns how many paths were handed to the scanner. */
    fun afterSync(context: Context, treeUri: Uri, changedPaths: List<String>, nomediaEnabled: Boolean): Int {
        if (nomediaEnabled || changedPaths.isEmpty()) return 0
        val dir = directoryOf(context, treeUri) ?: return 0
        val paths = changedPaths.distinct().map { File(dir, it).path }
        for (batch in paths.chunked(BATCH)) scanPaths(context, batch.toTypedArray())
        return paths.size
    }

    /** The directory a picked folder stands for, or null when it can't be
     * told: a folder from another provider has no path to give. A primary
     * storage tree is "primary:Music/Trobar"; a card's is "1234-ABCD:Music". */
    internal fun directoryOf(context: Context, treeUri: Uri): File? {
        if (treeUri.scheme == "file") return treeUri.path?.let(::File)
        if (treeUri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
        val docId = try { DocumentsContract.getTreeDocumentId(treeUri) } catch (ignored: Exception) { return null }
        val volume = docId.substringBefore(':')
        val relative = docId.substringAfter(':', "")
        val base = volumeDirectory(context, volume) ?: return null
        return if (relative.isEmpty()) base else File(base, relative)
    }

    @Suppress("DEPRECATION") // getExternalStorageDirectory: still the primary volume's path, and minSdk is 26
    private fun volumeDirectory(context: Context, volume: String): File? {
        if (volume == "primary") return Environment.getExternalStorageDirectory()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val sm = context.getSystemService(StorageManager::class.java)
            sm?.storageVolumes?.firstOrNull { it.uuid.equals(volume, ignoreCase = true) }?.directory?.let { return it }
        }
        return File("/storage/$volume")
    }
}
