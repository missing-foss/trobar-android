// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
// The brand themes: a dark-only theme ignores the light setting; a kit
// drawable loaded through the theme overlay takes that theme's colours (the
// one approach the mark, wordmark and lockup all use); picking a theme in
// Settings stores it and recolours the UI at once; every kit drawable inflates,
// and the single-colour marks keep their holes; the syncing star turns
// only while the system lets things animate, in the full and the small mark;
// below 24 dp the small mark, without pommels, replaces the full one;
// and the success and error badges take each theme's colours.

package org.missing_foss.trobar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrandThemeTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @After
    fun reset() {
        runBlocking { Prefs.setBrandTheme(context, BrandTheme.DEFAULT.key) }
        shell("settings put global animator_duration_scale 1")
    }

    /** Runs [cmd] and waits for it: reading the output to its end is what
     * waits, closing the descriptor alone returns before the command ran. */
    private fun shell(cmd: String) {
        val fd = instrumentation.uiAutomation.executeShellCommand(cmd)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
    }

    @Test
    fun anUnknownStoredKeyIsTroubadour() {
        assertEquals(BrandTheme.TROUBADOUR, BrandTheme.of("bard"))
        assertEquals(BrandTheme.TROUBADOUR, BrandTheme.of(null))
        assertEquals(BrandTheme.GARRIGA, BrandTheme.of("garriga"))
    }

    @Test
    fun aDarkOnlyThemeIsDarkEvenWhenTheSystemIsLight() {
        var nuech: ColorScheme? = null
        var garriga: ColorScheme? = null
        compose.setContent {
            TrobarTheme(brand = BrandTheme.NUECH, darkTheme = false) { nuech = MaterialTheme.colorScheme }
            TrobarTheme(brand = BrandTheme.GARRIGA, darkTheme = false) { garriga = MaterialTheme.colorScheme }
        }
        compose.waitForIdle()
        assertEquals(brandColorScheme(BrandTheme.NUECH, dark = true).background, nuech!!.background)
        assertEquals(brandColorScheme(BrandTheme.GARRIGA, dark = false).background, garriga!!.background)
        assertTrue("Garriga light has a light ground", garriga!!.background.luminance() > 0.5f)
    }

    /** With the wallpaper colouring the UI, a dark-only theme still makes it
     * the wallpaper's dark scheme: the generated brand schemes are dark either
     * way, so this is where the dark-only rule actually decides something. */
    @Test
    fun aDarkOnlyThemeKeepsTheWallpaperSchemeDark() {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
        var scheme: ColorScheme? = null
        compose.setContent {
            TrobarTheme(brand = BrandTheme.NUECH, darkTheme = false, dynamicColor = true) { scheme = MaterialTheme.colorScheme }
        }
        compose.waitForIdle()
        assertEquals(androidx.compose.material3.dynamicDarkColorScheme(context).background, scheme!!.background)
    }

    @Test
    fun aKitDrawableTakesTheThemesColoursThroughItsOverlay() {
        val fills = mutableMapOf<BrandTheme, List<Color>>()
        compose.setContent {
            for (theme in BrandTheme.entries) {
                fills[theme] = solidFills(rememberBrandVector(R.drawable.ic_trobar_mark, theme).root)
            }
        }
        compose.waitForIdle()
        for (theme in BrandTheme.entries) {
            // ic_trobar_mark: disc, pommels, the star group (two paths), hub, dot.
            assertEquals("$theme mark colours", listOf(theme.disc, theme.star, theme.star, theme.star, theme.disc, theme.dot), fills[theme])
        }
    }

    /** Every drawable the kit installs inflates and draws. A path string over
     * 32,767 bytes compiles to "STRING_TOO_LARGE" with only a warning, and the
     * drawable then throws on inflation; the sync notification using it is
     * silently dropped. */
    @Test
    fun everyKitDrawableInflatesAndDraws() {
        val theme = context.resources.newTheme().apply {
            applyStyle(R.style.Theme_Trobar, true)
            applyStyle(BrandTheme.DEFAULT.overlay, true)
        }
        val ids = mapOf(
            "ic_launcher_foreground_troubadour" to R.drawable.ic_launcher_foreground_troubadour,
            "ic_launcher_monochrome" to R.drawable.ic_launcher_monochrome,
            "ic_notification" to R.drawable.ic_notification,
            "ic_trobar_lockup" to R.drawable.ic_trobar_lockup,
            "ic_trobar_mark" to R.drawable.ic_trobar_mark,
            "ic_trobar_mark_mono" to R.drawable.ic_trobar_mark_mono,
            "ic_trobar_mark_small" to R.drawable.ic_trobar_mark_small,
            "ic_trobar_wordmark" to R.drawable.ic_trobar_wordmark,
            "ic_launcher" to R.mipmap.ic_launcher,
            "ic_launcher_round" to R.mipmap.ic_launcher_round,
        )
        for ((name, id) in ids) {
            val bitmap = render(ResourcesCompat.getDrawable(context.resources, id, theme)!!, 216)
            assertTrue("$name draws something", (0 until bitmap.height).any { y -> (0 until bitmap.width).any { x -> Color(bitmap.getPixel(x, y)).alpha > 0f } })
        }
    }

    /** The single-colour marks keep the rosette's holes: pommels, star and
     * centre dot are empty, disc and hub are filled. Each is one even-odd
     * path, so the star must be a single outline: drawn as its two squares,
     * their overlap would fill again. Points are in the mark's 200-unit space. */
    @Test
    fun theSingleColourMarksKeepTheirHoles() {
        val full = mapOf(
            (100f to 8f) to true, // disc
            (100f to 20f) to false, // a pommel
            (100f to 60f) to false, // the star, where its two squares overlap
            (136f to 64f) to false, // the star, in the 0° square only
            (100f to 46f) to false, // the star, in the 45° square only
            (100f to 78f) to true, // hub
            (100f to 100f) to false, // centre dot
        )
        // The notification icon is the small variant: no pommels, a larger star.
        val small = mapOf(
            (100f to 8f) to true, // disc
            (100f to 20f) to true, // disc, where the full mark has a pommel
            (100f to 60f) to false, // the star, where its two squares overlap
            (140f to 60f) to false, // the star, in the 0° square only
            (100f to 42f) to false, // the star, in the 45° square only
            (100f to 78f) to true, // hub
            (100f to 100f) to false, // centre dot
        )
        val size = 1080
        val inMark = { p: Float -> p * size / 200 }
        // ic_launcher_monochrome places the mark at 21 + 0.33 × p in a 108-unit icon.
        val inLauncher = { p: Float -> (21 + 0.33f * p) * size / 108 }
        val marks = listOf(
            Triple(R.drawable.ic_notification, inMark, small),
            Triple(R.drawable.ic_trobar_mark_mono, inMark, full),
            Triple(R.drawable.ic_launcher_monochrome, inLauncher, full),
        )
        for ((id, at, expected) in marks) {
            val bitmap = render(ResourcesCompat.getDrawable(context.resources, id, null)!!, size)
            for ((point, filled) in expected) {
                val alpha = Color(bitmap.getPixel(at(point.first).toInt(), at(point.second).toInt())).alpha
                assertEquals("${context.resources.getResourceEntryName(id)} at $point filled", filled, alpha > 0.5f)
            }
        }
    }

    private fun render(drawable: android.graphics.drawable.Drawable, size: Int): android.graphics.Bitmap {
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(android.graphics.Canvas(bitmap))
        return bitmap
    }

    private fun solidFills(group: VectorGroup): List<Color> = group.flatMap { node ->
        when (node) {
            is VectorPath -> listOf((node.fill as SolidColor).value)
            is VectorGroup -> solidFills(node)
            else -> emptyList()
        }
    }

    @Test
    fun pickingAThemeInSettingsStoresItAndRecoloursTheUiAtOnce() {
        var primary: Color? = null
        compose.setContent {
            Host { primary = MaterialTheme.colorScheme.primary; SettingsUnderTest() }
        }
        compose.waitForIdle()
        assertEquals(brandColorScheme(BrandTheme.TROUBADOUR, dark = false).primary, primary)
        compose.onNodeWithText("Theme").performScrollTo().performClick()
        compose.onNodeWithText("Garriga").performClick()
        compose.waitUntil(UI_WAIT_MS) { runBlocking { Prefs.brandTheme(context).first() } == "garriga" }
        compose.waitUntil(UI_WAIT_MS) { primary == brandColorScheme(BrandTheme.GARRIGA, dark = false).primary }
    }

    /** What MainActivity does: the theme follows the stored key. Light, so
     * the light schemes are the ones compared. */
    @Composable
    private fun Host(content: @Composable () -> Unit) {
        val key by Prefs.brandTheme(context).collectAsState(initial = BrandTheme.DEFAULT.key)
        TrobarTheme(brand = BrandTheme.of(key), darkTheme = false) { content() }
    }

    @Composable
    private fun SettingsUnderTest() {
        SettingsScreen(
            pairing = Prefs.Pairing(serverUrl = "http://127.0.0.1:9", token = "test-token"),
            treeUri = "content://com.android.externalstorage.documents/tree/primary%3ATrobarTest",
            nav = SettingsNav(onOpenAbout = {}),
            onServerUrlChanged = {},
            onFolderChanged = {},
            onUnpair = {},
        )
    }

    @Test
    fun theSyncingStarTurnsWithAnimationsOn() {
        assertTrue("two frames 600 ms apart differ", starMoves(scale = "1"))
    }

    // Under 24 dp AppLogo draws the small mark, whose star is a group of its own.
    @Test
    fun theSmallMarksStarTurnsWithAnimationsOn() {
        assertTrue("two frames 600 ms apart differ", starMoves(scale = "1", size = 20))
    }

    @Test
    fun theSmallMarksStarHoldsStillWithAnimationsOff() {
        assertFalse("two frames 600 ms apart are identical", starMoves(scale = "0", size = 20))
    }

    @Test
    fun theBadgesTakeEachThemesColours() {
        val size = 100
        compose.setContent {
            Column {
                BrandTheme.entries.forEach { brand ->
                    TrobarTheme(brand = brand) {
                        Row {
                            listOf(MarkState.PLAIN, MarkState.SUCCESS, MarkState.ERROR).forEach { state ->
                                AppLogo(size.dp, Modifier.testTag("${brand.key}-${state.name}"), state)
                            }
                        }
                    }
                }
            }
        }
        BrandTheme.entries.forEach { brand ->
            // In the 200 viewport. 172,168 is inside the badge's disc and clear of
            // both glyphs (the check passes 9 units away, the "!" 7). Without a
            // badge, 160,160 is plain disc, away from pommels and star.
            fun at(state: MarkState, x: Int, y: Int): Int {
                val b = compose.onNodeWithTag("${brand.key}-${state.name}").captureToImage().asAndroidBitmap()
                return b.getPixel(b.width * x / 200, b.height * y / 200)
            }
            assertColour("${brand.key} plain", brand.disc, at(MarkState.PLAIN, 160, 160))
            assertColour("${brand.key} success", brand.dot, at(MarkState.SUCCESS, 172, 168))
            assertColour("${brand.key} error", brand.text, at(MarkState.ERROR, 172, 168))
        }
    }

    @Test
    fun idleIsTheMarkAtHalfOpacity() {
        compose.setContent {
            TrobarTheme {
                Box(Modifier.background(Color.White)) {
                    Row {
                        AppLogo(100.dp, Modifier.testTag("plain"), MarkState.PLAIN)
                        AppLogo(100.dp, Modifier.testTag("idle"), MarkState.IDLE)
                    }
                }
            }
        }
        fun disc(tag: String): Int {
            val b = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
            // Between two pommels, inside the disc: 86 from the centre at 15°.
            return b.getPixel(b.width * 122 / 200, b.height * 17 / 200)
        }
        val disc = BrandTheme.DEFAULT.disc
        assertColour("plain", disc, disc("plain"))
        // Half the disc over the white behind it.
        assertColour("idle", Color((disc.red + 1f) / 2, (disc.green + 1f) / 2, (disc.blue + 1f) / 2), disc("idle"))
    }

    @Test
    fun belowTwentyFourDpTheMarkDropsItsPommels() {
        compose.setContent {
            TrobarTheme {
                Row {
                    AppLogo(23.dp, Modifier.testTag("23"))
                    AppLogo(24.dp, Modifier.testTag("24"))
                }
            }
        }
        // The full mark's twelve pommels sit 80 from the centre of the 200
        // viewport, every 30°; the small mark has plain disc there. Each centre
        // pixel counts as a pommel when it is nearer the star's colour than the
        // disc's, which antialiasing at this size cannot tip.
        fun pommels(tag: String): Int {
            val b = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
            val brand = BrandTheme.DEFAULT
            return (0 until POMMELS).count { i ->
                val a = Math.toRadians(i * 360.0 / POMMELS)
                val x = 100 + POMMEL_RADIUS * kotlin.math.cos(a)
                val y = 100 + POMMEL_RADIUS * kotlin.math.sin(a)
                val pixel = b.getPixel((b.width * x / 200).toInt(), (b.height * y / 200).toInt())
                distance(brand.star, pixel) < distance(brand.disc, pixel)
            }
        }
        assertEquals("pommels at 23 dp", 0, pommels("23"))
        assertEquals("pommels at 24 dp", POMMELS, pommels("24"))
    }

    private fun distance(colour: Color, pixel: Int): Int {
        val c = colour.toArgb()
        return listOf(16, 8, 0).sumOf { shift -> kotlin.math.abs((c shr shift and 0xFF) - (pixel shr shift and 0xFF)) }
    }

    private fun assertColour(what: String, expected: Color, pixel: Int) {
        val e = expected.toArgb()
        val diff = listOf(16, 8, 0).maxOf { shift -> kotlin.math.abs((e shr shift and 0xFF) - (pixel shr shift and 0xFF)) }
        assertTrue("$what: expected #%06X, got #%06X".format(e and 0xFFFFFF, pixel and 0xFFFFFF), diff <= COLOUR_TOLERANCE)
    }

    @Test
    fun theSyncingStarHoldsStillWithAnimationsOff() {
        assertFalse("two frames 600 ms apart are identical", starMoves(scale = "0"))
    }

    /** Two frames of the syncing mark, 600 ms of animation clock apart. The
     * scale is set before composition: AppLogo reads it once. */
    private fun starMoves(scale: String, size: Int = 96): Boolean {
        shell("settings put global animator_duration_scale $scale")
        assertEquals("the scale AppLogo will read", scale.toFloat(), animatorScale(context), 0f)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            TrobarTheme { AppLogo(size.dp, Modifier.testTag("mark"), MarkState.SYNCING) }
        }
        compose.mainClock.advanceTimeBy(100)
        val a = compose.onNodeWithTag("mark").captureToImage().asAndroidBitmap()
        compose.mainClock.advanceTimeBy(600)
        val b = compose.onNodeWithTag("mark").captureToImage().asAndroidBitmap()
        return !a.sameAs(b)
    }

    private companion object {
        /** Per channel, for antialiasing and the half-opacity blend's rounding. */
        const val COLOUR_TOLERANCE = 6
        const val POMMELS = 12
        const val POMMEL_RADIUS = 80.0
    }
}
