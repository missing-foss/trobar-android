// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
// Which mode the app opens in: Auto on either side of its rule (no cellular
// radio and smaller than a tablet means Simple), and a pinned choice
// overriding it both ways.

package org.missing_foss.trobar

import org.junit.Assert.assertEquals
import org.junit.Test

class AppModeTest {
    private val dap = DeviceProfile(hasTelephony = false, smallestWidthDp = 360)
    private val phone = DeviceProfile(hasTelephony = true, smallestWidthDp = 411)

    @Test
    fun autoIsSimpleOnADap() {
        assertEquals(AppMode.SIMPLE, resolveAppMode(AppModeChoice.AUTO, dap))
        // A wide DAP is still one: most are as wide in dp as phones.
        assertEquals(AppMode.SIMPLE, resolveAppMode(AppModeChoice.AUTO, DeviceProfile(false, 432)))
    }

    @Test
    fun autoIsFullOnAPhoneHoweverSmall() {
        assertEquals(AppMode.FULL, resolveAppMode(AppModeChoice.AUTO, phone))
        assertEquals(AppMode.FULL, resolveAppMode(AppModeChoice.AUTO, DeviceProfile(true, 240)))
    }

    @Test
    fun autoIsFullOnATabletWithoutAMobileNetwork() {
        assertEquals(AppMode.SIMPLE, resolveAppMode(AppModeChoice.AUTO, DeviceProfile(false, 599)))
        assertEquals(AppMode.FULL, resolveAppMode(AppModeChoice.AUTO, DeviceProfile(false, 600)))
    }

    @Test
    fun aPinnedChoiceOverridesAutoBothWays() {
        assertEquals(AppMode.FULL, resolveAppMode(AppModeChoice.FULL, dap))
        assertEquals(AppMode.SIMPLE, resolveAppMode(AppModeChoice.SIMPLE, phone))
    }

    @Test
    fun anUnknownStoredValueIsReadAsAuto() {
        assertEquals(AppMode.SIMPLE, resolveAppMode("compact", dap))
        assertEquals(AppMode.FULL, resolveAppMode(null, phone))
    }
}
