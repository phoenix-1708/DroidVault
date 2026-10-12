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
}