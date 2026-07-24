// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
// #61/#38: the compact-vs-expanded size-class branch, driven through the same
// CompositionLocal seam MainActivity provides in production (LocalWindowWidthSizeClass)
// — no real window resize/rotation needed to exercise the branch logic itself.

package com.mfoss.trobar

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdaptiveLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun isCompactWidthTrueUnderCompactSizeClass() {
        compose.setContent {
            CompositionLocalProvider(LocalWindowWidthSizeClass provides WindowWidthSizeClass.Compact) {
                Text(if (isCompactWidth()) "COMPACT" else "EXPANDED")
            }
        }
        compose.onNodeWithText("COMPACT").assertIsDisplayed()
    }

    @Test
    fun isCompactWidthFalseUnderExpandedSizeClass() {
        compose.setContent {
            CompositionLocalProvider(LocalWindowWidthSizeClass provides WindowWidthSizeClass.Expanded) {
                Text(if (isCompactWidth()) "COMPACT" else "EXPANDED")
            }
        }
        compose.onNodeWithText("EXPANDED").assertIsDisplayed()
    }

    // Much wider than CONTENT_MAX_WIDTH (560.dp) so the two size classes
    // produce genuinely different measured widths below. A plain
    // Box(Modifier.width(900.dp)) wouldn't do this reliably — on a device/
    // emulator whose real screen is narrower than 900.dp (as this test proved
    // empirically), the root's own incoming constraints clamp it right back
    // down. LooseMaxWidthContainer below sidesteps that by offering its child
    // an explicit max-width constraint, independent of the real screen size.
    private val parentWidth = 900.dp

    @Test
    fun adaptiveContentWidthFillsParentOnCompact() {
        compose.setContent {
            CompositionLocalProvider(LocalWindowWidthSizeClass provides WindowWidthSizeClass.Compact) {
                LooseMaxWidthContainer(parentWidth) {
                    Box(Modifier.then(adaptiveContentWidth()).testTag("compact_content"))
                }
            }
        }
        compose.onNodeWithTag("compact_content").assertWidthIsEqualTo(parentWidth)
    }

    @Test
    fun adaptiveContentWidthCapsOnExpanded() {
        compose.setContent {
            CompositionLocalProvider(LocalWindowWidthSizeClass provides WindowWidthSizeClass.Expanded) {
                LooseMaxWidthContainer(parentWidth) {
                    Box(Modifier.then(adaptiveContentWidth()).testTag("expanded_content"))
                }
            }
        }
        compose.onNodeWithTag("expanded_content").assertWidthIsEqualTo(CONTENT_MAX_WIDTH)
    }
}

/** Measures [content] with a loose (min 0, max [width]) width constraint,
 * independent of whatever constraints this node itself receives from the
 * real screen/window. This mirrors the real production parent (a Box with
 * an alignment, per adaptiveContentWidth()'s own doc comment) — a *tight*
 * (min == max) constraint here would leave widthIn(max = ...) no room to
 * shrink below the incoming min, defeating the cap it's meant to test. */
@Composable
private fun LooseMaxWidthContainer(width: Dp, content: @Composable () -> Unit) {
    Layout(content = content) { measurables, _ ->
        val constraints = Constraints(maxWidth = width.roundToPx())
        val placeable = measurables.first().measure(constraints)
        layout(placeable.width, placeable.height) {
            placeable.placeRelative(0, 0)
        }
    }
}
