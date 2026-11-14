// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

/** Where the dashboard can send the user. */
class HomeNavigation(
    val onOpenBasket: () -> Unit,
    val onOpenPrefs: () -> Unit,
    val onOpenLibrary: () -> Unit,
    val onOpenDevice: (Long) -> Unit = {},
    val onOpenPlaylists: () -> Unit = {},
)
