// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import androidx.compose.runtime.Composable

/** Where Settings leads: About, and back when it was pushed (Simple mode)
 * rather than shown as a tab (Full mode), where [onBack] is null. */
class SettingsNav(
    val onOpenAbout: () -> Unit,
    val onBack: (() -> Unit)? = null,
    val problem: @Composable () -> Unit = {},
)
