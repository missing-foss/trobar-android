// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight

/** The tab title on Home and Library: Trobar's own wordmark, sized for a
 * top bar, not the name of the screen. */
@Composable
fun TrobarWordmarkTitle() {
    Text(trobarWordmark(), style = MaterialTheme.typography.titleLarge.copy(fontFamily = FredokaFamily, fontWeight = FontWeight.SemiBold))
}

/** The one destination in force (#50): only the device's icon in the bar --
 * its name is in the menu the icon opens, and in the icon's description for
 * a screen reader. The menu lists every device the owner may stage to and
 * marks the current one. Nothing until the devices are read. */
@Composable
fun DeviceSelector(state: SharedState, onChoose: (Long) -> Unit) {
    // The same groups whether or not the devices have arrived.
    val current = state.destination
    var open by remember { mutableStateOf(false) }
    if (current != null) Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("device_selector")) {
            DeviceIcon(current.icon, current.deviceType, contentDescription = stringResource(R.string.header_destination, current.name))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.devices.forEach { device ->
                DropdownMenuItem(
                    text = { Text(device.name) },
                    leadingIcon = { DeviceIcon(device.icon, device.deviceType, contentDescription = null) },
                    trailingIcon = {
                        if (device.id == current.id) {
                            Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.header_destination_current))
                        }
                    },
                    onClick = {
                        open = false
                        onChoose(device.id)
                    },
                )
            }
        }
    }
}
