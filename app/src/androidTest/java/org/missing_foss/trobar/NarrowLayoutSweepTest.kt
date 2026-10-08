// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Every screen the app shell reaches, at the narrowest phone width, in
 * English and French, at 1.0x, 1.3x and 2.0x text: no text on it may break
 * a line between two letters. Such breaks don't show in English at 1.0x,
 * which is how screens get built, so each new screen belongs in [sweep].
 *
 * Each screen is checked only once something that screen alone shows is
 * there, so a tap that did not navigate fails the test instead of checking
 * the previous screen twice. A lazy list is walked item by item, so rows
 * below the fold are read too.
 */
@RunWith(AndroidJUnit4::class)
class NarrowLayoutSweepTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: MockWebServer
    private lateinit var res: android.content.res.Resources

    // Long names in both languages, so a screen that leaves text too little
    // room shows it.
    private val base = FakeAppApi(
        devices = listOf(
            1L to "Pixel",
            2L to "Baladeur de la cuisine du rez-de-chaussée",
            3L to "Tablet in the living room",
            4L to "Carte SD",
        ),
    )
    private val dash = FakeDashboardApi(base)
    private val lists = FakePlaylistApi(base)

    /** Screen -> the texts on it that break between two letters. */
    private val broken = linkedMapOf<String, Set<String>>()
    private val missed = mutableListOf<String>()
    /** Headers whose controls sit beside a label that should have a line of its own. */
    private val beside = mutableListOf<String>()

    @Before
    fun start() {
        runBlocking { Prefs.clearAppApi(context); Prefs.clearHeaderDevice(context) }
        base.ownedBy[3L] = "bartholomew"
        base.artist(
            LONG_ARTIST,
            Triple(LONG_ALBUM, 2019, 12),
            Triple("Short", 2001, 3),
        )
        base.artist("ABBA", Triple("Arrival", 1976, 10))
        base.artist(WORDY_ARTIST, Triple("Live", 2010, 8))
        base.stage(1, "$LONG_ARTIST||$LONG_ALBUM", 2L, 3L)
        base.stage(2, "ABBA||Arrival", 1L)
        dash.recentlyAdded += Triple(LONG_ARTIST, LONG_ALBUM, "library")
        dash.recentlyReleased += Triple("ABBA", "Arrival", "library")
        dash.suggestions += Triple(LONG_ARTIST, LONG_ALBUM, "lastfm")
        dash.mostPlayed += Triple("ABBA", "Arrival", 1234)
        lists.playlists += FakePlaylistApi.Playlist(1, LONG_PLAYLIST, trackCount = 142, matchedCount = 62)
        lists.playlists += FakePlaylistApi.Playlist(2, "Household", trackCount = 3, matchedCount = 3, ownerUsername = "bartholomew", isOwn = false)
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path == "/api/device/info" -> MockResponse().setResponseCode(200).setBody(
                        """{"name": "Pixel", "max_size_bytes": null, "device_type": "phone", "source_of_truth": "server", "app_api": 2}""",
                    )
                    path.contains("playlist") || path.contains("subscription") -> lists.dispatch(request)
                    else -> dash.dispatch(request)
                }
            }
        }
        server.start()
    }

    @After
    fun stop() = server.shutdown()

    @Test fun englishAt1x() = sweep(french = false, fontScale = 1.0f)
    @Test fun englishAt1_3x() = sweep(french = false, fontScale = 1.3f)
    @Test fun englishAt2x() = sweep(french = false, fontScale = 2.0f)
    @Test fun frenchAt1x() = sweep(french = true, fontScale = 1.0f)
    @Test fun frenchAt1_3x() = sweep(french = true, fontScale = 1.3f)
    @Test fun frenchAt2x() = sweep(french = true, fontScale = 2.0f)

    private fun sweep(french: Boolean, fontScale: Float) {
        val locale = if (french) Locale.FRENCH else Locale.ENGLISH
        res = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocale(locale) },
        ).resources
        val pairing = Prefs.Pairing(serverUrl = server.url("/").toString(), token = "test-token")
        compose.setContent {
            val ctx = LocalContext.current
            val owner = LocalActivityResultRegistryOwner.current!!
            val config = Configuration(ctx.resources.configuration).apply {
                setLocale(locale)
                this.fontScale = fontScale
                screenWidthDp = NARROWEST_PHONE_DP
            }
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides ctx.createConfigurationContext(config),
                LocalConfiguration provides config,
                LocalDensity provides Density(density.density, fontScale),
                LocalActivityResultRegistryOwner provides owner,
                LocalWindowWidthSizeClass provides WindowWidthSizeClass.Compact,
            ) {
                MaterialTheme {
                    Box(Modifier.requiredWidth(NARROWEST_PHONE_DP.dp)) {
                        AppShell(
                            pairing = pairing,
                            treeUri = "content://test/tree",
                            onServerUrlChanged = {},
                            onFolderChanged = {},
                            onUnpair = {},
                            onReEnroll = {},
                            sharedLink = SharedLinkIn(null) {},
                        )
                    }
                }
            }
        }
        arrive("home", hasText(s(R.string.widget_view_library))) // the library widget, first on Home
        if (click(s(R.string.widget_prefs_title), "home widgets")) {
            arrive("home widgets", hasText(s(R.string.widget_prefs_title))); back()
        }
        if (click(s(R.string.tab_library), "library")) {
            arrive("library", hasText(LONG_ARTIST))
            if (click(LONG_ARTIST, "artist", text = true)) {
                arrive("artist", hasText(s(R.string.library_sync_entire_artist)))
                nameAboveSync(LONG_ARTIST)
                back()
            }
            if (click(WORDY_ARTIST, "short-worded artist", text = true)) {
                arrive("short-worded artist", hasText(s(R.string.library_sync_entire_artist)))
                nameAboveSync(WORDY_ARTIST)
                back()
            }
        }
        if (click(s(R.string.tab_playlists), "playlists")) {
            arrive("playlists", hasText(LONG_PLAYLIST))
            if (click(LONG_PLAYLIST, "playlist", text = true)) { arrive("playlist", hasTestTag("playlist_entries")); back() }
            if (click(s(R.string.subs_title), "subscriptions")) { arrive("subscriptions", hasText(s(R.string.subs_title))); back() }
        }
        if (click(s(R.string.basket_title), "basket")) { arrive("basket", hasText(s(R.string.basket_clear))); back() }
        if (click(s(R.string.settings_title), "settings")) {
            arrive("settings", hasText(s(R.string.about_tile_label)))
            if (click(s(R.string.about_tile_label), "about", text = true)) { arrive("about", hasText(s(R.string.about_title))); back() }
        }
        val where = "${locale.language} at ${fontScale}x, $NARROWEST_PHONE_DP dp"
        assertEquals("screens not reached, $where", emptyList<String>(), missed)
        assertEquals("text broken between two letters, $where", emptyMap<String, Set<String>>(), broken)
        assertEquals("controls beside a name that needs its own line, $where", emptyList<String>(), beside)
    }

    private fun s(id: Int) = res.getString(id)

    /** The artist page's [artist] has a line of its own: the sync button
     * starts below it, never beside it, where a name of several words would
     * wrap word by word with none of them broken. */
    private fun nameAboveSync(artist: String) {
        val names = compose.onAllNodes(hasText(artist) and isHeading()).fetchSemanticsNodes()
        val buttons = compose.onAllNodes(hasText(s(R.string.library_sync_entire_artist))).fetchSemanticsNodes()
        if (names.isEmpty() || buttons.isEmpty()) { missed += "$artist header"; return }
        val name = names.single().boundsInRoot
        val button = buttons.single().boundsInRoot
        if (button.top < name.bottom) beside += "$artist: button top ${button.top} < name bottom ${name.bottom}"
    }

    /** Clicks the first node with this text or description, scrolled into
     * view; with none, [opens] counts as not reached. */
    private fun click(label: String, opens: String, text: Boolean = false): Boolean {
        val nodes = compose.onAllNodes(if (text) hasText(label) else hasContentDescription(label))
        if (nodes.fetchSemanticsNodes().isEmpty()) { missed += opens; return false }
        runCatching { nodes[0].performScrollTo() }
        nodes[0].performClick()
        compose.waitForIdle()
        return true
    }

    /** Once [marker] shows, reads every text on [screen], down to the last
     * item of each vertical lazy list on it. */
    private fun arrive(screen: String, marker: SemanticsMatcher) {
        val there = runCatching {
            compose.waitUntil(ARRIVE_MS) { compose.onAllNodes(marker).fetchSemanticsNodes().isNotEmpty() }
        }.isSuccess
        if (!there) { missed += screen; return }
        compose.waitForIdle()
        val found = linkedSetOf<String>()
        found += readTexts()
        val lists = compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex) and
                SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
        )
        for (i in 0 until lists.fetchSemanticsNodes().size) {
            var index = 0
            // An index past the last item is refused; that is the end.
            while (index < MAX_LIST_ITEMS && runCatching { lists[i].performScrollToIndex(index) }.isSuccess) {
                compose.waitForIdle()
                found += readTexts()
                index++
            }
            runCatching { lists[i].performScrollToIndex(0) }
        }
        if (found.isNotEmpty()) broken[screen] = found
    }

    private fun readTexts(): List<String> =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes().mapNotNull { midWordBreak(it) }

    private fun back() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private companion object {
        const val NARROWEST_PHONE_DP = 320 // the smallest width Android's compatibility definition allows a phone
        const val ARRIVE_MS = 10_000L
        const val MAX_LIST_ITEMS = 60 // well past the longest list the fakes serve
        const val LONG_ARTIST = "Orchestre Philharmonique de Radio France"
        // Every word short enough to fit beside the sync button, so the name
        // can wrap there word by word without breaking any of them.
        const val WORDY_ARTIST = "The Very Long Band Name"
        const val LONG_ALBUM = "Symphonie fantastique, op. 14 (version intégrale remasterisée)"
        const val LONG_PLAYLIST = "Road trip across the Pyrenees, summer edition"

        /** The node's text with each line break between two letters or
         * digits marked "|", or null when it has none. */
        fun midWordBreak(node: SemanticsNode): String? {
            val layouts = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            val layout = layouts.firstOrNull() ?: return null
            val text = layout.layoutInput.text.text
            val cuts = (0 until layout.lineCount - 1).map { layout.getLineEnd(it) }
                .filter { it in 1 until text.length && text[it - 1].isLetterOrDigit() && text[it].isLetterOrDigit() }
            if (cuts.isEmpty()) return null
            return buildString {
                var from = 0
                for (cut in cuts) { append(text, from, cut); append('|'); from = cut }
                append(text, from, text.length)
            }
        }
    }
}
