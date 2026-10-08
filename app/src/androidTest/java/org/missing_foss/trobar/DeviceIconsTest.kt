// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A device draws its own icon when it has one this app knows, else its
 * type's, as the web UI does; a folder target draws as a folder. */
@RunWith(AndroidJUnit4::class)
class DeviceIconsTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aDeviceWithAnIconDrawsThatIcon() {
        assertEquals(R.drawable.ic_device_clickwheel, deviceIconRes("clickwheel", "dap"))
        assertEquals(R.drawable.ic_device_cassette, deviceIconRes("cassette", "phone"))
    }

    @Test
    fun withoutAnIconOrWithOneThisAppDoesNotKnowTheTypeDecides() {
        assertEquals(R.drawable.ic_device_headphones, deviceIconRes(null, "dap")) // none chosen, or an older server
        assertEquals(R.drawable.ic_device_headphones, deviceIconRes("minidisc", "dap")) // a later server's id
    }

    @Test
    fun eachTypeDrawsTheWebsPicture() {
        val expected = mapOf(
            "phone" to R.drawable.ic_device_phone,
            "android" to R.drawable.ic_device_phone,
            "tablet" to R.drawable.ic_device_tablet,
            "watch" to R.drawable.ic_device_watch,
            "dap" to R.drawable.ic_device_headphones,
            "sdcard" to R.drawable.ic_device_usb,
            "folder" to R.drawable.ic_device_folder, // was drawn as a phone
            "toaster" to R.drawable.ic_device_phone, // a type this app does not know
        )
        assertEquals(expected, expected.mapValues { (type, _) -> deviceIconRes(null, type) })
    }

    @Test
    fun theHeaderAndItsMenuDrawEachDevicesOwnIcon() {
        val state = SharedState(
            devices = listOf(
                device(1L, "phone", icon = "phone-button"),
                device(2L, "folder", icon = null),
                device(3L, "dap", icon = "some-later-id"),
            ),
            thisDeviceId = 1L,
        )
        compose.setContent { MaterialTheme { DeviceSelector(state, onChoose = {}) } }
        assertEquals(listOf("ic_device_phone_button"), drawn())
        compose.onNodeWithTag("device_selector").performClick()
        compose.waitForIdle()
        // The button's own, then the three menu rows in order.
        assertEquals(
            listOf("ic_device_phone_button", "ic_device_phone_button", "ic_device_folder", "ic_device_headphones"),
            drawn(),
        )
    }

    /** The drawables the device icons on screen show, by name. */
    private fun drawn(): List<String> = compose.onAllNodes(SemanticsMatcher.keyIsDefined(DeviceIconRes), useUnmergedTree = true)
        .fetchSemanticsNodes().map { context.resources.getResourceEntryName(it.config[DeviceIconRes]) }

    private fun device(id: Long, type: String, icon: String?) =
        AppDevice(id, "Device $id", type, null, "alice", true, false, null, DeviceSyncStatus(0, null), icon = icon)
}
