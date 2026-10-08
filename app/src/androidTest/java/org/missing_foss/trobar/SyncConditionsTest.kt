// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** The rule behind the sentence a send to this phone shows instead of
 * starting the sync: the constraint each Sync via setting maps to in the
 * worker (Wi-Fi only is UNMETERED, not a transport), and nothing else
 * holds a manual sync. */
@RunWith(AndroidJUnit4::class)
class SyncConditionsTest {
    @Test
    fun offlineBlocksEveryMode() {
        for (mode in listOf(Prefs.NETWORK_WIFI_ONLY, Prefs.NETWORK_WIFI_AND_MOBILE, Prefs.NETWORK_ANY)) {
            assertEquals(mode, R.string.sync_blocked_offline, SyncConditions.blockingReason(mode, online = false, unmetered = false, roaming = false))
        }
    }

    @Test
    fun wifiOnlyWaitsForAnUnmeteredNetworkWhateverTheTransport() {
        // Metered Wi-Fi (a hotspot) holds the sync; unmetered mobile does not.
        assertEquals(R.string.sync_blocked_needs_unmetered, SyncConditions.blockingReason(Prefs.NETWORK_WIFI_ONLY, online = true, unmetered = false, roaming = false))
        assertNull(SyncConditions.blockingReason(Prefs.NETWORK_WIFI_ONLY, online = true, unmetered = true, roaming = false))
    }

    @Test
    fun mobileWithoutRoamingWaitsWhileRoaming() {
        assertEquals(R.string.sync_blocked_roaming, SyncConditions.blockingReason(Prefs.NETWORK_WIFI_AND_MOBILE, online = true, unmetered = false, roaming = true))
        assertNull(SyncConditions.blockingReason(Prefs.NETWORK_WIFI_AND_MOBILE, online = true, unmetered = false, roaming = false))
    }

    @Test
    fun anyNetworkRunsWheneverOnline() {
        assertNull(SyncConditions.blockingReason(Prefs.NETWORK_ANY, online = true, unmetered = false, roaming = true))
    }
}
