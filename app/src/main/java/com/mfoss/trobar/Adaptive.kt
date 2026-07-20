// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package com.mfoss.trobar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// #38: the current window's size classes, computed once in MainActivity and
// read anywhere via these locals — so screens adapt without each re-measuring.
val LocalWindowWidthSizeClass = staticCompositionLocalOf { WindowWidthSizeClass.Compact }
val LocalWindowHeightSizeClass = staticCompositionLocalOf { WindowHeightSizeClass.Medium }

/** #38: a readable max content width — forms/lists cap to this and center on
 * bigger windows instead of stretching edge to edge. */
val CONTENT_MAX_WIDTH = 560.dp

/** True on a phone-width window (< 600dp) — content fills the width. On Medium/
 * Expanded (tablets, unfolded foldables, landscape phones) it's capped. */
@Composable
fun isCompactWidth(): Boolean = LocalWindowWidthSizeClass.current == WindowWidthSizeClass.Compact

/** True on a short viewport (a landscape phone) — hero headers shrink so they
 * don't dominate the little vertical space there is. */
@Composable
fun isCompactHeight(): Boolean = LocalWindowHeightSizeClass.current == WindowHeightSizeClass.Compact

/** #38: a width modifier for a screen's form/list content — full width on a
 * phone, capped to [CONTENT_MAX_WIDTH] on bigger windows. Combine with
 * `Modifier.align(Alignment.CenterHorizontally)` on a Column child so the
 * capped block is centered rather than left-aligned. */
@Composable
fun adaptiveContentWidth(): Modifier =
    if (isCompactWidth()) Modifier.fillMaxWidth()
    else Modifier.widthIn(max = CONTENT_MAX_WIDTH).fillMaxWidth()

/** #38: the burgundy brand hero shared by the enrollment wizard and the folder
 * picker — full-bleed, but shrinks on a short (landscape) viewport so it
 * doesn't eat the screen. */
@Composable
fun BrandHeader(title: String, subtitle: String) {
    val short = isCompactHeight()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (short) 132.dp else 220.dp)
            .background(BrandGradientBrush),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppLogo(if (short) 48.dp else 72.dp)
            Spacer(Modifier.height(4.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
            )
        }
    }
}
