package com.phoenix.droidvault

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.phoenix.droidvault.ui.theme.DarkDvPalette
import com.phoenix.droidvault.ui.theme.DvRole
import com.phoenix.droidvault.ui.theme.LightDvPalette
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Contrast guard for the semantic accent system.
 *
 * The palette is generated from per-role hues with fixed lightness constants,
 * so these relationships hold for every role by construction. Asserting them
 * means a future hue change (or a careless hand-edit of the constants) fails the
 * build instead of quietly shipping an unreadable icon.
 *
 * Thresholds: 4.5:1 is WCAG AA for text; 3:1 is the AA floor for non-text UI
 * such as icons and shapes. We keep a margin above both.
 */
class AccentContrastTest {

    private val allRoles = DvRole.entries

    private fun contrastRatio(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    /** Surfaces the badges actually sit on, from Color.kt. */
    private val lightSurface = Color(0xFFF1F5FF)
    private val darkSurface = Color(0xFF1D1F26)
    private val amoledSurface = Color(0xFF121216)

    @Test
    fun iconOnContainerMeetsAAInLightTheme() {
        for (role in allRoles) {
            val ratio = contrastRatio(
                LightDvPalette.accent(role),
                LightDvPalette.container(role)
            )
            assertTrue(
                "$role light icon-on-container was %.2f:1, needs >= 4.5".format(ratio),
                ratio >= 4.5
            )
        }
    }

    @Test
    fun iconOnContainerMeetsAAInDarkTheme() {
        for (role in allRoles) {
            val ratio = contrastRatio(
                DarkDvPalette.accent(role),
                DarkDvPalette.container(role)
            )
            assertTrue(
                "$role dark icon-on-container was %.2f:1, needs >= 4.5".format(ratio),
                ratio >= 4.5
            )
        }
    }

    @Test
    fun containerSeparatesFromTheSurfaceItSitsOn() {
        for ((palette, surfaces, name) in listOf(
            Triple(LightDvPalette, listOf(lightSurface), "light"),
            Triple(DarkDvPalette, listOf(darkSurface, amoledSurface), "dark")
        )) {
            for (surface in surfaces) {
                for (role in allRoles) {
                    val ratio = contrastRatio(palette.container(role), surface)
                    assertTrue(
                        "$name $role container-on-surface was %.2f:1, badge would vanish".format(ratio),
                        ratio >= 1.15
                    )
                }
            }
        }
    }

    @Test
    fun accentTextMeetsAAOnEverySurface() {
        for ((palette, surfaces, name) in listOf(
            Triple(LightDvPalette, listOf(lightSurface), "light"),
            Triple(DarkDvPalette, listOf(darkSurface, amoledSurface), "dark")
        )) {
            for (surface in surfaces) {
                for (role in allRoles) {
                    val ratio = contrastRatio(palette.accent(role), surface)
                    assertTrue(
                        "$name $role accent-on-surface was %.2f:1".format(ratio),
                        ratio >= 4.5
                    )
                }
            }
        }
    }

    /**
     * The bottom-nav and card accents are the app's primary signal. If two roles
     * that appear together collapse to a near-identical hue, the colour stops
     * carrying meaning and the app just looks randomly tinted.
     */
    @Test
    fun theFourTabRolesAreHueDistinct() {
        val tabs = listOf(DvRole.Flash, DvRole.Migrate, DvRole.Tools, DvRole.Auto)
        for (i in tabs.indices) {
            for (j in i + 1 until tabs.size) {
                val delta = abs(tabs[i].hue - tabs[j].hue)
                val distance = minOf(delta, 360f - delta)
                assertTrue(
                    "${tabs[i]} and ${tabs[j]} are only %.0f degrees apart".format(distance),
                    distance >= 30f
                )
            }
        }
    }

    /** Destructive must never be mistaken for a neutral or informational role. */
    @Test
    fun destructiveStandsApartFromToolsAndStorage() {
        assertTrue(abs(DvRole.Destructive.hue - DvRole.Tools.hue) >= 30f)
        assertTrue(abs(DvRole.Destructive.hue - DvRole.Storage.hue) >= 30f)
    }

    /**
     * The Live log's severity colours, on the surface they are actually read on.
     *
     * The accent system is checked above for ICON-ON-CONTAINER, which is a
     * different background from the one a log line sits on: the diagnostics
     * sheet is a `ModalBottomSheet` on `surfaceContainerHigh`, and a hue that
     * clears 4.5:1 on a tinted container can still be unreadable on a flat
     * surface. Adding the level filter is what put coloured text where there
     * was grey text, so the new pairing needs its own measurement rather than
     * an assumption that the old one transfers.
     *
     * Themed by hand because [com.poenix.droidvault.ui.theme.dvAccent] reads a
     * CompositionLocal, and a unit test has none. The three pairs below are the
     * three themes the app ships.
     */
    @Test
    fun logSeverityColoursAreReadableOnTheSheetSurface() {
        // The three roles the Live log tints by: errors, warnings, info.
        val roles = listOf(DvRole.Error, DvRole.Warning, DvRole.Info)
        val themes = listOf(
            Triple("light", LightDvPalette, lightSurface),
            Triple("dark", DarkDvPalette, darkSurface),
            Triple("amoled", DarkDvPalette, amoledSurface)
        )
        for ((name, palette, surface) in themes) {
            for (role in roles) {
                val ratio = contrastRatio(palette.accent(role), surface)
                assertTrue(
                    "$name $role on the diagnostics sheet was %.2f:1, needs >= 4.5 — " +
                        "pick a different DvRole rather than lowering the bar".format(ratio),
                    ratio >= 4.5
                )
            }
        }
    }

    /** The three severity roles must be tellable apart, not just individually legible. */
    @Test
    fun logSeverityRolesAreHueDistinct() {
        val roles = listOf(DvRole.Error, DvRole.Warning, DvRole.Info)
        for (i in roles.indices) {
            for (j in i + 1 until roles.size) {
                val delta = abs(roles[i].hue - roles[j].hue)
                val distance = minOf(delta, 360f - delta)
                assertTrue(
                    "${roles[i]} and ${roles[j]} are only %.0f degrees apart, so a log " +
                        "line's colour stops carrying information".format(distance),
                    distance >= 30f
                )
            }
        }
    }

    /**
     * The Import card's status colours, on the surface that card is drawn on.
     *
     * A different background from the log sheet's, so it gets its own
     * measurement rather than an assumption that the other one transfers. The
     * card background is `surfaceContainerLow`, which is a tint of the surface
     * rather than the surface itself.
     *
     * This pairing is load-bearing in a way the icon ones are not: the status
     * label is TEXT in the accent colour, and "Complete" used to be a grey pill
     * that read as a disabled control rather than as health. Green, amber and
     * red are exactly the three states a reader has to tell apart at a glance.
     */
    @Test
    fun transferStatusColoursAreReadableOnTheCard() {
        val roles = listOf(DvRole.Success, DvRole.Warning, DvRole.Error)
        val themes = listOf(
            Triple("light", LightDvPalette, lightSurface),
            Triple("dark", DarkDvPalette, darkSurface),
            Triple("amoled", DarkDvPalette, amoledSurface)
        )
        for ((name, palette, surface) in themes) {
            for (role in roles) {
                val ratio = contrastRatio(palette.accent(role), surface)
                assertTrue(
                    "$name $role on an import card was %.2f:1, needs >= 4.5 — " +
                        "pick a different DvRole rather than lowering the bar".format(ratio),
                    ratio >= 4.5
                )
            }
        }
    }

    /** The three states must be tellable apart, not just individually legible. */
    @Test
    fun transferStatusRolesAreHueDistinct() {
        val roles = listOf(DvRole.Success, DvRole.Warning, DvRole.Error)
        for (i in roles.indices) {
            for (j in i + 1 until roles.size) {
                val delta = abs(roles[i].hue - roles[j].hue)
                val distance = minOf(delta, 360f - delta)
                assertTrue(
                    "${roles[i]} and ${roles[j]} are only %.0f degrees apart, so a " +
                        "card's status stops carrying information".format(distance),
                    distance >= 30f
                )
            }
        }
    }
}
