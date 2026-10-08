// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
// The About screen's two buttons: Donate stacked below Check for updates,
// the pair the same width -- where they broke side by side (320 dp, French,
// 2x) and at the default size in English. And the documentation link's label,
// on one line where the longer one broke.

package org.missing_foss.trobar

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AboutScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun setScreen(widthDp: Int? = null, fontScale: Float? = null, french: Boolean = false) {
        compose.setContent {
            val base = LocalContext.current
            val config = Configuration(base.resources.configuration).apply { if (french) setLocale(Locale.FRENCH) }
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides base.createConfigurationContext(config),
                LocalConfiguration provides config,
                LocalDensity provides Density(density.density, fontScale ?: density.fontScale),
            ) {
                MaterialTheme {
                    Box(if (widthDp != null) Modifier.requiredWidth(widthDp.dp) else Modifier) { AboutScreen(onBack = {}) }
                }
            }
        }
    }

    private fun donateBelowUpdateAndTheSameWidth() {
        val update = compose.onNodeWithTag("check_updates").assertIsDisplayed().getUnclippedBoundsInRoot()
        val donate = compose.onNodeWithTag("donate").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("Donate at $donate, Check for updates at $update", donate.top >= update.bottom)
        assertEquals("the pair's widths", update.width.value, donate.width.value, 0.5f)
    }

    @Test
    fun donateIsBelowCheckForUpdatesOnANarrowPhoneInFrenchAtTheLargestFontScale() {
        setScreen(widthDp = NARROWEST_PHONE_DP, fontScale = LARGEST_FONT_SCALE, french = true)
        donateBelowUpdateAndTheSameWidth()
    }

    @Test
    fun donateIsBelowCheckForUpdatesAtTheDefaultSize() {
        setScreen()
        donateBelowUpdateAndTheSameWidth()
    }

    @Test
    fun theDocumentationLinksLabelKeepsOneLineOnTheNarrowestPhone() {
        setScreen(widthDp = NARROWEST_PHONE_DP, fontScale = LARGEST_FONT_SCALE, french = true)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val label = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocale(Locale.FRENCH) },
        ).getString(R.string.about_documentation)
        // "Documentation" beside the tile's icon and chevron broke as
        // "Documentatio/n"; the shorter label fits on one line.
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        assertEquals("lines of \"$label\"", 1, layouts.single().lineCount)
    }

    private companion object {
        const val NARROWEST_PHONE_DP = 320 // the smallest width Android's compatibility definition allows a phone
        const val LARGEST_FONT_SCALE = 2f
    }
}
