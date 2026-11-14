// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * One drag of a row by its handle: which row, how far it has moved
 * (px), each row's height as last laid out, and where the finger is on
 * screen, for the auto-scroll. The row follows the finger; the rows it
 * passes shift to show where it will land; on release the whole order is
 * written once.
 */
internal class RowDrag {
    var id by mutableStateOf<String?>(null)
    var offset by mutableFloatStateOf(0f)
    var from = -1
    var pointerY by mutableFloatStateOf(Float.NaN)
    var viewport: Rect = Rect.Zero
    val heights = mutableMapOf<String, Int>()

    /** Where the dragged row would land now, among [order]. */
    fun target(order: List<String>): Int {
        var to = from
        var travelled = 0f
        if (offset > 0) {
            for (j in from + 1 until order.size) {
                val h = heights[order[j]] ?: break
                if (offset > travelled + h / 2f) to = j else break
                travelled += h
            }
        } else if (offset < 0) {
            for (j in from - 1 downTo 0) {
                val h = heights[order[j]] ?: break
                if (-offset > travelled + h / 2f) to = j else break
                travelled += h
            }
        }
        return to
    }

    /** How far the row at [index] moves aside for the dragged one. */
    fun shiftFor(index: Int, order: List<String>): Float {
        val dragged = id ?: return 0f
        val h = heights[dragged]?.toFloat() ?: return 0f
        val to = target(order)
        return when {
            index in (from + 1)..to -> -h
            index in to until from -> h
            else -> 0f
        }
    }
}

/** The handle's gesture: a drag moves the row, and the drop moves what it stands for. */
internal fun Modifier.dragHandle(drag: RowDrag, id: String, order: List<String>, onDrop: (String, Int) -> Unit): Modifier {
    var coordinates: LayoutCoordinates? = null
    return onGloballyPositioned { coordinates = it }.pointerInput(id, order) {
        detectDragGestures(
            onDragStart = {
                drag.id = id
                drag.from = order.indexOf(id)
                drag.offset = 0f
                drag.pointerY = Float.NaN
            },
            onDrag = { change, amount ->
                change.consume()
                drag.offset += amount.y
                drag.pointerY = coordinates?.localToWindow(change.position)?.y ?: Float.NaN
            },
            onDragEnd = {
                val to = drag.target(order)
                drag.id = null
                if (to != drag.from) onDrop(id, to)
            },
            onDragCancel = { drag.id = null },
        )
    }
}

/** While a row is dragged near the top or bottom of the screen, the list
 * scrolls under it, and the row keeps its place under the finger. */
@Composable
internal fun AutoScrollWhileDragging(drag: RowDrag, scroll: ScrollableState) {
    val edge = with(LocalDensity.current) { AUTO_SCROLL_EDGE.toPx() }
    val step = with(LocalDensity.current) { AUTO_SCROLL_STEP.toPx() }
    LaunchedEffect(drag.id) {
        while (drag.id != null) {
            val y = drag.pointerY
            val v = drag.viewport
            val delta = when {
                y.isNaN() -> 0f
                y < v.top + edge -> -step
                y > v.bottom - edge -> step
                else -> 0f
            }
            if (delta != 0f) drag.offset += scroll.scrollBy(delta)
            delay(AUTO_SCROLL_FRAME_MS)
        }
    }
}

private val AUTO_SCROLL_EDGE = 48.dp
private val AUTO_SCROLL_STEP = 6.dp
private const val AUTO_SCROLL_FRAME_MS = 16L
