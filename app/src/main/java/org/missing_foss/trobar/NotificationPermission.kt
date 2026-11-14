// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.app.NotificationManagerCompat

/** Whether to ask for the notification permission now. Android 13 added it;
 * the app asks once, after the folder picker, and never again by itself:
 * once refused, Android may not show the prompt again anyway, so Settings
 * sends people to Android's own page for the app instead. */
fun shouldAskForNotifications(sdkInt: Int, granted: Boolean, alreadyAsked: Boolean): Boolean =
    sdkInt >= Build.VERSION_CODES.TIRAMISU && !granted && !alreadyAsked

/** Whether the sync's progress notification can show. */
fun notificationsEnabled(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled()

/** Opens Android's notification settings for this app. */
fun openAppNotificationSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/** What Settings needs to know and do about the notification permission;
 * a seam for its tests, since revoking the real permission kills the app's
 * process, and the instrumentation with it. */
class NotificationAccess(
    val allowedNow: (Context) -> Boolean = ::notificationsEnabled,
    val openSettings: (Context) -> Unit = ::openAppNotificationSettings,
)

/** Settings reads this; only tests provide another. */
val LocalNotificationAccess = staticCompositionLocalOf { NotificationAccess() }
