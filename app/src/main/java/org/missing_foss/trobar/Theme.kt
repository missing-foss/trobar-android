// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext

// Reads primary→tertiary from the current scheme: the brand theme's disc and
// dot when dynamic color is off, the wallpaper's when it is on.
internal val BrandGradientBrush: Brush
    @Composable get() = Brush.linearGradient(
        colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
    )

/** The app's colours: [brand]'s derived scheme (BrandTokens.kt, generated
 * from brand/trobar-themes.json), light or dark. Nuèch and Contraste are dark
 * whatever [darkTheme] says. With [dynamicColor] (Android 12+), the wallpaper
 * colours the UI instead; the brand theme still colours the mark and
 * wordmark, through [LocalBrand]. */
@Composable
fun TrobarTheme(
    brand: BrandTheme = BrandTheme.DEFAULT,
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = brand.darkOnly || darkTheme
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> brandColorScheme(brand, dark)
    }
    CompositionLocalProvider(LocalBrand provides brand) {
        MaterialTheme(colorScheme = colorScheme, typography = BrandTypography, content = content)
    }
}
