// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import android.content.ContentResolver
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Covers on their way to the basket icon: where the icon is, where the
 * overlay that draws them sits, and the flights under way. Several fly at
 * once -- a second double-tap never waits for the first to land. */
@Stable
class BasketFlight internal constructor(private val resolver: ContentResolver) {
    internal var target by mutableStateOf<Rect?>(null)
    internal var origin by mutableStateOf(Offset.Zero)
    internal val flights = mutableStateListOf<Flight>()
    private var nextId = 0L

    /** Sends a cover from [from] to the basket icon, unless the system's
     * animations are off: then nothing flies, and the badge alone says the
     * item went in. No cover (a playlist has none) flies the image fallback,
     * as the basket row shows it. */
    fun launch(coverUrl: String?, from: Rect) {
        if (!animationsOn()) return
        flights += Flight(nextId++, coverUrl, from)
    }

    private fun animationsOn(): Boolean =
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

    internal class Flight(val id: Long, val coverUrl: String?, val from: Rect)
}

@Composable
fun rememberBasketFlight(): BasketFlight {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) { BasketFlight(resolver) }
}

/** Marks the basket icon the covers fly to. */
fun Modifier.basketFlightTarget(flight: BasketFlight): Modifier = onGloballyPositioned { flight.target = it.boundsInRoot() }

/** Draws the covers in flight; put it over the whole screen, after the
 * content. It takes no input, so the screen under it keeps its gestures. */
@Composable
fun BasketFlightOverlay(flight: BasketFlight, pairing: Prefs.Pairing) {
    Box(Modifier.fillMaxSize().onGloballyPositioned { flight.origin = it.positionInRoot() }) {
        flight.flights.forEach { f ->
            key(f.id) { FlyingCover(f, flight, pairing) { flight.flights.remove(f) } }
        }
    }
}

/** One cover: it lifts, shrinks and travels to the icon along a short arc,
 * then is gone. The badge moves on the server's answer, not on landing:
 * a flight never claims a stage the server refused. */
@Composable
private fun FlyingCover(f: BasketFlight.Flight, flight: BasketFlight, pairing: Prefs.Pairing, onLanded: () -> Unit) {
    val landed by rememberUpdatedState(onLanded)
    val target = flight.target
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (target != null) progress.animateTo(1f, tween(FLIGHT_MILLIS, easing = FastOutSlowInEasing))
        landed()
    }
    if (target == null) return
    val density = LocalDensity.current
    val p = progress.value
    // A list row has no cover of its own: the cover starts as a square at
    // the row's start, as tall as the row.
    val startSize = min(f.from.width, f.from.height)
    val endSize = target.height * LANDING_SCALE
    val size = startSize + (endSize - startSize) * p
    val start = f.from.topLeft
    val end = target.center - Offset(endSize / 2, endSize / 2)
    val lift = with(density) { LIFT.toPx() } * sin(Math.PI * p).toFloat()
    val x = start.x + (end.x - start.x) * p - flight.origin.x
    val y = start.y + (end.y - start.y) * p - lift - flight.origin.y
    Box(
        Modifier
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .size(with(density) { size.toDp() })
            .graphicsLayer {
                shadowElevation = (1f - p) * ELEVATION.toPx()
                shape = RoundedCornerShape(CORNER)
                clip = true
                alpha = 1f - FADE * p
            }
            .testTag("basket_flight"),
    ) {
        TrobarImage(url = f.coverUrl, contentDescription = null, pairing = pairing, modifier = Modifier.fillMaxSize())
    }
}

/** Double-tap stages; a single tap does nothing, and there is no ripple:
 * nothing about the item suggests a button. [onStage] gets the item's
 * bounds, for the flight. Screen readers activate with a double-tap of
 * their own, so the click action stages too, named for what it does, and
 * the item reads as one: its cover and its lines together. */
@Composable
fun Modifier.stageOnDoubleTap(label: String, onStage: (from: Rect) -> Unit): Modifier {
    val current by rememberUpdatedState(onStage)
    val bounds = remember { arrayOf(Rect.Zero) }
    return this
        .onGloballyPositioned { bounds[0] = it.boundsInRoot() }
        .pointerInput(Unit) { detectTapGestures(onDoubleTap = { current(bounds[0]) }) }
        .semantics(mergeDescendants = true) { onClick(label) { current(bounds[0]); true } }
}

/** For an item that opens and can be staged -- a playlist: a tap opens it,
 * a double-tap stages it. A tap waits out the double-tap window before it
 * opens. For screen readers, activating the item opens it, as a tap does,
 * and staging is a named action of its own. */
@Composable
fun Modifier.openOnTapStageOnDoubleTap(stageLabel: String, onOpen: () -> Unit, onStage: (from: Rect) -> Unit): Modifier {
    val open by rememberUpdatedState(onOpen)
    val stage by rememberUpdatedState(onStage)
    val bounds = remember { arrayOf(Rect.Zero) }
    return this
        .onGloballyPositioned { bounds[0] = it.boundsInRoot() }
        .pointerInput(Unit) { detectTapGestures(onTap = { open() }, onDoubleTap = { stage(bounds[0]) }) }
        .semantics(mergeDescendants = true) {
            onClick { open(); true }
            customActions = listOf(CustomAccessibilityAction(stageLabel) { stage(bounds[0]); true })
        }
}

/** What a screen does with a stage's first answer: the cover flies when
 * the stage started, a quiet message says why when it did not. */
@Composable
fun rememberStageAnswer(flight: BasketFlight, snackbar: SnackbarHostState): (StageStart, coverUrl: String?, from: Rect?) -> Unit {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    return remember(flight, snackbar, context) {
        { answer, coverUrl, from ->
            when (answer) {
                StageStart.Started -> if (from != null) flight.launch(coverUrl, from)
                is StageStart.AlreadyStaged -> scope.launch {
                    snackbar.showSnackbar(context.getString(R.string.stage_already_staged, answer.device.name))
                }
                StageStart.NoDestination -> scope.launch { snackbar.showSnackbar(context.getString(R.string.stage_no_destination)) }
            }
        }
    }
}

private const val FLIGHT_MILLIS = 450
private const val LANDING_SCALE = 0.6f
private const val FADE = 0.4f
private val LIFT = 48.dp
private val ELEVATION = 8.dp
private val CORNER = 6.dp
