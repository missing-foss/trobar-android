// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package com.mfoss.trobar

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Fixed brand colors — the "Side A" record label palette — used in the
// static color scheme and the app logo. The UI gradient (BrandGradientBrush)
// reads primary/tertiary from the active theme instead, so it follows
// dynamic color automatically.
internal val BrandBurgundy = Color(0xFFA83250) // disc label
internal val BrandRose = Color(0xFFD76A83)     // UI accent

// Reads primary→tertiary from the current theme. When dynamic color is off,
// those map to BrandBurgundy/BrandRose; when on, they follow the wallpaper.
internal val BrandGradientBrush: Brush
    @Composable get() = Brush.linearGradient(
        colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
    )

private val LightColors = lightColorScheme(
    primary = BrandBurgundy,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF6D9DF),
    onPrimaryContainer = Color(0xFF3F0F1C),
    secondary = BrandRose,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFBE1E7),
    onSecondaryContainer = Color(0xFF4A1522),
    tertiary = BrandRose,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE28AA0),
    onPrimary = Color(0xFF4A0F1E),
    primaryContainer = Color(0xFF6E1A2E),
    onPrimaryContainer = Color(0xFFF6D9DF),
    secondary = Color(0xFFD76A83),
    onSecondary = Color(0xFF3D0F1A),
    secondaryContainer = Color(0xFF7A2A3A),
    onSecondaryContainer = Color(0xFFFBE1E7),
    tertiary = Color(0xFFD76A83),
    // Material's default dark scheme background/surface are a neutral gray
    // close enough in tone to the vinyl's near-blacks that the spinning disc
    // stopped reading as a separate object against it — pin these to the
    // brand's own canvas/panel tokens instead of the M3 default.
    background = Color(0xFF100E08),
    surface = Color(0xFF100E08),
    surfaceVariant = Color(0xFF221E16),
)

@Composable
fun TrobarTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, typography = BrandTypography, content = content)
}
