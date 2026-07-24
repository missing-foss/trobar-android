// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
// #61: the three settings named in the issue — server URL edit, missing-file
// policy, and dynamic color — each verified against the real Prefs DataStore,
// not just that the row renders. getDeviceInfo() on load hits a real local
// HTTP server (see EnrollmentWizardTest's header for why).

package com.mfoss.trobar

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: MockWebServer
    // A dummy SAF tree URI — storageStatsForTree/humanReadablePath both
    // degrade gracefully on one that doesn't resolve to a real picked
    // folder (see StorageStats.kt/MainActivity.kt), so no real SAF pick is
    // needed just to host this screen.
    private val dummyTreeUri = "content://com.android.externalstorage.documents/tree/primary%3ATrobarTest"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"name": "Test Phone", "max_size_bytes": null, "device_type": "phone", "source_of_truth": "server"}""",
            ),
        )
        // Reset the two DataStore prefs this test touches, so a prior run's
        // leftover state can't make this one flaky either way.
        runBlocking {
            Prefs.setMissingFileBehavior(context, Prefs.MISSING_ASK)
            Prefs.setUseDynamicColor(context, false)
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun setScreen(onServerUrlChanged: (String) -> Unit = {}) {
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    pairing = Prefs.Pairing(serverUrl = server.url("/").toString(), token = "test-token"),
                    treeUri = dummyTreeUri,
                    onBack = {},
                    onOpenAbout = {},
                    onServerUrlChanged = onServerUrlChanged,
                    onFolderChanged = {},
                    onUnpair = {},
                )
            }
        }
    }

    @Test
    fun serverUrlEditFiresCallbackWithNewValue() {
        var changedTo: String? = null
        setScreen(onServerUrlChanged = { changedTo = it })

        compose.onNodeWithText("Server").performClick()
        compose.onNodeWithText("URL").performTextClearance()
        compose.onNodeWithText("URL").performTextInput("https://new.example.com")
        compose.onNodeWithText("Save").performClick()

        assertEquals("https://new.example.com", changedTo)
    }

    @Test
    fun missingFilePolicyPersistsToRealPrefs() {
        setScreen()
        compose.onNodeWithText("Locally deleted files").performClick()
        compose.onNodeWithText("Resync").performClick()

        compose.waitUntil(timeoutMillis = 5_000) {
            runBlocking { Prefs.missingFileBehavior(context).first() } == Prefs.MISSING_ALWAYS_RESYNC
        }
        assertEquals(Prefs.MISSING_ALWAYS_RESYNC, runBlocking { Prefs.missingFileBehavior(context).first() })
    }

    @Test
    fun dynamicColorTogglePersistsToRealPrefs() {
        // Only shown on API 31+ (Build.VERSION_CODES.S) — see SettingsScreen.
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        setScreen()
        assertEquals(false, runBlocking { Prefs.useDynamicColor(context).first() })

        // SettingsScreen is a verticalScroll Column, not a LazyColumn — every
        // row exists in the semantics tree regardless of scroll position, but
        // performClick() dispatches a real touch at the node's actual
        // coordinates. This row sits below Server/Storage/Language, off the
        // initial viewport, so the click needs to scroll it into view first
        // or it silently lands nowhere.
        compose.onNodeWithTag("dynamic_color_switch").performScrollTo().performClick()

        compose.waitUntil(timeoutMillis = 5_000) {
            runBlocking { Prefs.useDynamicColor(context).first() }
        }
        assertTrue(runBlocking { Prefs.useDynamicColor(context).first() })
    }
}
