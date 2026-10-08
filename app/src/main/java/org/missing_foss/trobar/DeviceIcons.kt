// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics

/** The twelve device pictures the server offers, by the id it stores in a
 * device's `icon`: the same glyphs the web UI draws. */
private val DEVICE_ICONS = mapOf(
    "phone" to R.drawable.ic_device_phone,
    "phone-button" to R.drawable.ic_device_phone_button,
    "tablet" to R.drawable.ic_device_tablet,
    "tablet-small" to R.drawable.ic_device_tablet_small,
    "clickwheel" to R.drawable.ic_device_clickwheel,
    "cassette" to R.drawable.ic_device_cassette,
    "dap-buttons" to R.drawable.ic_device_dap_buttons,
    "headphones" to R.drawable.ic_device_headphones,
    "watch" to R.drawable.ic_device_watch,
    "sdcard" to R.drawable.ic_device_sdcard,
    "usb" to R.drawable.ic_device_usb,
    "folder" to R.drawable.ic_device_folder,
)

/** A device type's own picture, as the web UI maps it. "android" is the
 * phone alias older rows still carry; any other type draws as a phone. */
private val TYPE_ICONS = mapOf(
    "phone" to "phone",
    "android" to "phone",
    "tablet" to "tablet",
    "watch" to "watch",
    "dap" to "headphones",
    "sdcard" to "usb",
    "folder" to "folder",
)

/** The device's own icon when the server sent one this app knows; else its
 * type's. A null icon (none chosen, or a server from before icons) and an
 * id from a later server both fall back to the type. */
@DrawableRes
internal fun deviceIconRes(icon: String?, deviceType: String): Int =
    DEVICE_ICONS[icon] ?: DEVICE_ICONS.getValue(TYPE_ICONS[deviceType] ?: "phone")

/** Which drawable a device icon shows, for tests to read. */
internal val DeviceIconRes = SemanticsPropertyKey<Int>("DeviceIconRes")
private var SemanticsPropertyReceiver.deviceIconRes by DeviceIconRes

@Composable
internal fun DeviceIcon(
    icon: String?,
    deviceType: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    val res = deviceIconRes(icon, deviceType)
    Icon(
        painterResource(res),
        contentDescription = contentDescription,
        modifier = modifier.semantics { deviceIconRes = res },
        tint = tint,
    )
}
