// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
// #61: first instrumented Compose UI test. ChoiceRow is the settings
// radio-dialog component (missing-file policy, network mode, …); this also
// guards the #54 area — that every option stays reachable in the dialog.

package com.mfoss.trobar

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChoiceRowTest {
    @get:Rule
    val compose = createComposeRule()

    private val options =
        listOf("ask" to "Ask me", "resync" to "Re-sync", "leave" to "Leave deleted")

    private fun setChoiceRow(onSelect: (String) -> Unit = {}) {
        compose.setContent {
            MaterialTheme {
                ChoiceRow(
                    icon = Icons.Filled.Sync,
                    label = "Missing files",
                    options = options,
                    selected = "ask",
                    onSelect = onSelect,
                )
            }
        }
    }

    @Test
    fun showsLabelAndCurrentSelection() {
        setChoiceRow()
        compose.onNodeWithText("Missing files").assertIsDisplayed()
        // the row's subtitle is the selected option's label
        compose.onNodeWithText("Ask me").assertIsDisplayed()
    }

    @Test
    fun tappingOpensDialogWithEveryOptionReachable() {
        setChoiceRow()
        compose.onNodeWithText("Missing files").performClick()
        // #54 guard: the non-selected options exist only in the dialog, so their
        // presence proves the dialog opened and nothing is clipped/hidden.
        compose.onNodeWithText("Re-sync").assertIsDisplayed()
        compose.onNodeWithText("Leave deleted").assertIsDisplayed()
        // the selected label now appears twice: the row subtitle + the dialog option
        compose.onAllNodesWithText("Ask me").assertCountEquals(2)
    }

    @Test
    fun selectingAnOptionFiresOnSelectWithItsValue() {
        var picked: String? = null
        setChoiceRow(onSelect = { picked = it })
        compose.onNodeWithText("Missing files").performClick()
        compose.onNodeWithText("Re-sync").performClick()
        assertEquals("resync", picked)
    }
}
