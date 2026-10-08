// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import android.content.Context
import android.content.pm.PackageManager

/** How much of the app this device shows: the sync alone, or everything.
 * UI only: the background sync is the same either way. */
enum class AppMode { SIMPLE, FULL }

/** The stored choice: [AUTO] lets [autoAppMode] decide; the other two pin
 * a mode whatever the device. */
object AppModeChoice {
    const val AUTO = "auto"
    const val SIMPLE = "simple"
    const val FULL = "full"
}

/** What Auto looks at, read once for the device: whether it has a cellular
 * radio, and its smallest screen width in dp. */
data class DeviceProfile(val hasTelephony: Boolean, val smallestWidthDp: Int) {
    companion object {
        /** From the application's configuration rather than an activity's,
         * since Auto is about the device, not a window. The smallest width is
         * the smaller side whichever way the screen is turned, so a rotation
         * does not change it. */
        fun of(context: Context): DeviceProfile {
            val app = context.applicationContext
            return DeviceProfile(
                hasTelephony = app.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY),
                smallestWidthDp = app.resources.configuration.smallestScreenWidthDp,
            )
        }
    }
}

/** Below Android's tablet width. */
private const val TABLET_SMALLEST_WIDTH_DP = 600

/** Auto: Simple on a device with no cellular radio that is not tablet-sized,
 * which is what a DAP is; Full everywhere else. Screen size alone cannot
 * tell them apart: most DAPs are as wide in dp as phones, and a small phone
 * is still a phone. */
fun autoAppMode(profile: DeviceProfile): AppMode =
    if (!profile.hasTelephony && profile.smallestWidthDp < TABLET_SMALLEST_WIDTH_DP) AppMode.SIMPLE else AppMode.FULL

/** The mode in force: a pinned choice, else what Auto decides. An unknown
 * stored value is read as Auto. */
fun resolveAppMode(choice: String?, profile: DeviceProfile): AppMode = when (choice) {
    AppModeChoice.SIMPLE -> AppMode.SIMPLE
    AppModeChoice.FULL -> AppMode.FULL
    else -> autoAppMode(profile)
}
