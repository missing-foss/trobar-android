// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import android.content.Context

/** The shared holder a screen under test is given, on the same pairing as
 * its own clients -- in the app the shell creates one for every screen. */
fun testAppState(context: Context, pairing: Prefs.Pairing): AppState = AppState(
    context,
    AppApiClient(context, pairing.serverUrl, pairing.token),
    AppApiDashboardClient(context, pairing.serverUrl, pairing.token),
)
