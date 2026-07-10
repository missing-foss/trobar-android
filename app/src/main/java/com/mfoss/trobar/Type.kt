// SPDX-License-Identifier: GPL-3.0-or-later
package com.mfoss.trobar

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

// Brand fonts, bundled as static .ttf resources (downloaded from Google Fonts,
// OFL-licensed) rather than relying on the Downloadable Fonts API — keeps the
// app working the same on de-Googled devices, no Play Services dependency.
// The SIL OFL 1.1 text and per-family copyright notices ship inside the APK at
// assets/THIRD_PARTY_NOTICES.md, satisfying OFL's attribution term.

internal val FredokaFamily = FontFamily(
    Font(R.font.fredoka_semibold, FontWeight.SemiBold),
    Font(R.font.fredoka_bold, FontWeight.Bold),
)

internal val SpaceGroteskFamily = FontFamily(
    Font(R.font.space_grotesk_regular, FontWeight.Normal),
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_semibold, FontWeight.SemiBold),
    Font(R.font.space_grotesk_bold, FontWeight.Bold),
)

internal val SpaceMonoFamily = FontFamily(
    Font(R.font.space_mono_regular, FontWeight.Normal),
    Font(R.font.space_mono_bold, FontWeight.Bold),
)

/** Per the brand handoff: Space Grotesk is the UI/body font everywhere.
 * Fredoka is reserved for the "Trobar" wordmark/monogram specifically
 * (applied via [FredokaFamily] directly on those Text calls, not here). */
internal val BrandTypography = Typography().let { base ->
    Typography(
        displayLarge = base.displayLarge.copy(fontFamily = SpaceGroteskFamily),
        displayMedium = base.displayMedium.copy(fontFamily = SpaceGroteskFamily),
        displaySmall = base.displaySmall.copy(fontFamily = SpaceGroteskFamily),
        headlineLarge = base.headlineLarge.copy(fontFamily = SpaceGroteskFamily),
        headlineMedium = base.headlineMedium.copy(fontFamily = SpaceGroteskFamily),
        headlineSmall = base.headlineSmall.copy(fontFamily = SpaceGroteskFamily),
        titleLarge = base.titleLarge.copy(fontFamily = SpaceGroteskFamily),
        titleMedium = base.titleMedium.copy(fontFamily = SpaceGroteskFamily),
        titleSmall = base.titleSmall.copy(fontFamily = SpaceGroteskFamily),
        bodyLarge = base.bodyLarge.copy(fontFamily = SpaceGroteskFamily),
        bodyMedium = base.bodyMedium.copy(fontFamily = SpaceGroteskFamily),
        bodySmall = base.bodySmall.copy(fontFamily = SpaceGroteskFamily),
        labelLarge = base.labelLarge.copy(fontFamily = SpaceGroteskFamily),
        labelMedium = base.labelMedium.copy(fontFamily = SpaceGroteskFamily),
        labelSmall = base.labelSmall.copy(fontFamily = SpaceGroteskFamily),
    )
}
