// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import coil3.compose.AsyncImagePainter
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade

/** A cover or artist picture from the paired server. Shows a music-note
 * glyph on a muted ground while loading and when the server has no picture
 * (a 404 is the normal outcome for many artists), so a grid keeps its shape
 * and never shows a broken image. Callers give the size; a square in a grid
 * is `Modifier.aspectRatio(1f)`. [onState] exists for tests. */
@Composable
fun TrobarImage(
    url: String?,
    contentDescription: String?,
    pairing: Prefs.Pairing,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    onState: ((AsyncImagePainter.State) -> Unit)? = null,
) {
    val context = LocalContext.current
    val loader = remember(pairing) { TrobarImages.loader(context, pairing) }
    val request = remember(url) {
        ImageRequest.Builder(context).data(url).crossfade(true).build()
    }
    SubcomposeAsyncImage(
        model = request,
        contentDescription = contentDescription,
        imageLoader = loader,
        modifier = modifier,
        contentScale = contentScale,
        onLoading = onState,
        onSuccess = onState,
        onError = onState,
        loading = { ImageGlyph(tag = "image_loading") },
        error = { ImageGlyph(tag = "image_fallback") },
    )
}

@Composable
private fun ImageGlyph(tag: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxSize(GLYPH_FRACTION),
        )
    }
}

private const val GLYPH_FRACTION = 0.4f
