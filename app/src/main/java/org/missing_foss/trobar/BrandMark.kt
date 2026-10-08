// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import android.content.Context
import android.content.res.Resources
import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.RenderVectorGroup
import androidx.compose.ui.graphics.vector.VectorConfig
import androidx.compose.ui.graphics.vector.VectorProperty
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** The brand theme in force, provided by [TrobarTheme]. */
internal val LocalBrand = staticCompositionLocalOf { BrandTheme.DEFAULT }

/** The activity's theme with [brand]'s overlay on top: the kit's drawables
 * (mark, wordmark, lockup) read their colours from `?attr/trobar*`, which
 * the overlay sets. One drawable serves all five themes. */
@Composable
internal fun rememberBrandResTheme(brand: BrandTheme): Resources.Theme {
    val context = LocalContext.current
    return remember(context, brand) {
        context.resources.newTheme().apply {
            setTo(context.theme)
            applyStyle(brand.overlay, true)
        }
    }
}

/** A kit drawable in [brand]'s colours, as an ImageVector. */
@Composable
internal fun rememberBrandVector(@DrawableRes id: Int, brand: BrandTheme = LocalBrand.current): ImageVector {
    val context = LocalContext.current
    val theme = rememberBrandResTheme(brand)
    return remember(theme, id) { ImageVector.vectorResource(theme = theme, res = context.resources, resId = id) }
}

/** A kit drawable (lockup, wordmark, small mark) in [brand]'s colours. */
@Composable
internal fun BrandImage(@DrawableRes id: Int, contentDescription: String?, modifier: Modifier = Modifier, brand: BrandTheme = LocalBrand.current) {
    Image(rememberVectorPainter(rememberBrandVector(id, brand)), contentDescription, modifier)
}

/** The system's animator duration scale; 0 when the user turned animations
 * off in developer options or accessibility settings. */
internal fun animatorScale(context: Context): Float =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

/** brand/BRAND.md §5: only the star turns, 90° every 2.4 s, eased. */
private const val STAR_TURN_MS = 2400
private val StarEasing = CubicBezierEasing(0.45f, 0f, 0.55f, 1f)

/** What the mark shows (brand/BRAND.md §5). [PLAIN] is the mark as the About
 * screen shows it; the other four are the sync states. */
enum class MarkState { PLAIN, IDLE, SYNCING, SUCCESS, ERROR }

/** Below this the kit's small mark (no pommels) is drawn instead of the full one. */
private val SMALL_MARK_BELOW = 24.dp

/** The Trobar rosette in the active brand theme, in one of the [MarkState]s.
 * While syncing, the star alone turns (the `star` group of the drawable);
 * disc, pommels and hub stay still. With animations off system-wide it holds
 * still. Idle is the whole mark at half opacity; success and error add the
 * kit's badge, bottom right. */
@Composable
fun AppLogo(size: Dp, modifier: Modifier = Modifier, state: MarkState = MarkState.PLAIN, contentDescription: String? = "Trobar") {
    val context = LocalContext.current
    val brand = LocalBrand.current
    val vector = rememberBrandVector(if (size < SMALL_MARK_BELOW) R.drawable.ic_trobar_mark_small else R.drawable.ic_trobar_mark)
    val spinning = state == MarkState.SYNCING && remember(context) { animatorScale(context) } > 0f
    val angle = if (spinning) {
        val transition = rememberInfiniteTransition(label = "mark-star")
        val a by transition.animateFloat(
            initialValue = 0f,
            targetValue = 90f,
            // The star repeats every 90° (two squares at 0° and 45°), so
            // restarting at 0 is seamless.
            animationSpec = infiniteRepeatable(tween(STAR_TURN_MS, easing = StarEasing), RepeatMode.Restart),
            label = "star-angle",
        )
        a
    } else {
        0f
    }
    val painter = rememberVectorPainter(
        defaultWidth = vector.defaultWidth,
        defaultHeight = vector.defaultHeight,
        viewportWidth = vector.viewportWidth,
        viewportHeight = vector.viewportHeight,
        name = vector.name,
        tintColor = Color.Unspecified,
        autoMirror = false,
    ) { _, _ ->
        RenderVectorGroup(vector.root, configs = mapOf("star" to StarRotation(angle)))
    }
    Image(
        painter = painter,
        contentDescription = contentDescription,
        modifier = modifier
            .size(size)
            .alpha(if (state == MarkState.IDLE) IDLE_ALPHA else 1f)
            .drawWithContent {
                drawContent()
                if (state == MarkState.SUCCESS || state == MarkState.ERROR) drawStateBadge(state == MarkState.SUCCESS, brand)
            },
    )
}

private const val IDLE_ALPHA = 0.5f

/** The kit's state badge (svg/states), in its 200-unit viewport: a disc at
 * 160,160 in [BrandTheme.dot] (success) or [BrandTheme.text] (error), ringed in
 * the ground colour, carrying a check or an "!" in the ground colour. */
private fun DrawScope.drawStateBadge(success: Boolean, brand: BrandTheme) {
    val u = size.minDimension / 200f
    val centre = Offset(160 * u, 160 * u)
    val radius = 36 * u
    drawCircle(if (success) brand.dot else brand.text, radius, centre)
    drawCircle(brand.ground, radius, centre, style = Stroke(width = 8 * u))
    if (success) {
        val start = Offset(144 * u, 162 * u)
        val down = Offset(11 * u, 11 * u)
        val up = Offset(20 * u, -22 * u)
        val check = Path().apply {
            moveTo(start.x, start.y)
            relativeLineTo(down.x, down.y)
            relativeLineTo(up.x, up.y)
        }
        val checkWidth = 9 * u
        drawPath(check, brand.ground, style = Stroke(width = checkWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
    } else {
        val barTopLeft = Offset(155 * u, 138 * u)
        val barSize = Size(10 * u, 26 * u)
        val barCorner = CornerRadius(5 * u)
        val dotRadius = 5.5f * u
        val dotCentre = Offset(160 * u, 176 * u)
        drawRoundRect(brand.ground, barTopLeft, barSize, barCorner)
        drawCircle(brand.ground, dotRadius, dotCentre)
    }
}

/** Overrides the rotation of the mark's `star` group, nothing else. */
private class StarRotation(private val degrees: Float) : VectorConfig {
    @Suppress("UNCHECKED_CAST")
    override fun <T> getOrDefault(property: VectorProperty<T>, defaultValue: T): T =
        if (property == VectorProperty.Rotation) degrees as T else defaultValue
}
