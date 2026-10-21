package com.phoenix.droidvault

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.phoenix.droidvault.ui.theme.AccentSeed
import com.phoenix.droidvault.ui.theme.accentColorScheme
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The M3 scheme's own contrast — the accent the app's buttons, links and
 * chips are painted with.
 *
 * **`AccentContrastTest` measures the ROLE palette, not this.** It asserts the
 * thirteen semantic accents and their containers; the M3 scheme — `primary`,
 * `onPrimary`, the tonal plate, the selection container — was asserted
 * NOWHERE. So the accent could have been swapped for a colour that no one
 * could read, and every test in the suite would still have been green.
 *
 * That is not hypothetical: the accent WAS swapped (a fully saturated
 * periwinkle and a blue-cast set of neutrals, replaced by a muted plum and
 * warm greys), and the only reason it is safe is that these pairings were
 * measured before the swap.
 *
 * The floors: 4.5:1 is WCAG AA for text; 3:1 is the AA floor for a non-text
 * UI shape. The tonal plate is held to the text floor because it carries a
 * LABEL, not just a shape — it is a button.
 */
class SchemeContrastTest {

    private fun contrastRatio(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    private data class Theme(val name: String, val scheme: ColorScheme)

    // The three themes the app ships, each from the app's own accent.
    private val themes = listOf(
        Theme("light", accentColorScheme(AccentSeed.Plum, isDark = false, isAmoled = false)),
        Theme("dark", accentColorScheme(AccentSeed.Plum, isDark = true, isAmoled = false)),
        Theme("amoled", accentColorScheme(AccentSeed.Plum, isDark = true, isAmoled = true))
    )

    @Test
    fun theLabelOnAFilledButtonIsReadable() {
        for ((name, s) in themes) {
            val ratio = contrastRatio(s.onPrimary, s.primary)
            assertTrue(
                "$name label-on-primary was %.2f:1, needs >= 4.5".format(ratio),
                ratio >= 4.5
            )
        }
    }

    @Test
    fun theAccentIsReadableAsTextOnACard() {
        for ((name, s) in themes) {
            val ratio = contrastRatio(s.primary, s.surfaceContainer)
            assertTrue(
                "$name accent-on-card was %.2f:1, needs >= 4.5 — the accent is " +
                    "used as TEXT on cards (links, the live run status)".format(ratio),
                ratio >= 4.5
            )
        }
    }

    @Test
    fun theTonalPlateCarriesAReadableLabel() {
        for ((name, s) in themes) {
            val ratio = contrastRatio(s.onPrimaryContainer, s.primaryContainer)
            assertTrue(
                "$name label-on-tonal-plate was %.2f:1, needs >= 4.5 — the plate " +
                    "carries a LABEL, so it is held to the text floor".format(ratio),
                ratio >= 4.5
            )
        }
    }

    @Test
    fun theTonalPlateSeparatesFromTheCardItSitsOn() {
        for ((name, s) in themes) {
            val ratio = contrastRatio(s.primaryContainer, s.surfaceContainer)
            assertTrue(
                "$name tonal-plate-on-card was %.2f:1, needs >= 1.15 — a button " +
                    "painted in the card's own tone is not a button".format(ratio),
                ratio >= 1.15
            )
        }
    }

    @Test
    fun theSelectionContainerCarriesAReadableLabel() {
        for ((name, s) in themes) {
            val ratio = contrastRatio(s.onSecondaryContainer, s.secondaryContainer)
            assertTrue(
                "$name label-on-selection was %.2f:1, needs >= 4.5 — the selected " +
                    "chip and the selected row wear this".format(ratio),
                ratio >= 4.5
            )
        }
    }

    @Test
    fun theReadingColourIsReadableOnEverySurface() {
        for ((name, s) in themes) {
            for ((role, surface) in listOf(
                "surface" to s.surface,
                "card" to s.surfaceContainer,
                "surfaceVariant" to s.surfaceVariant
            )) {
                val ratio = contrastRatio(s.onSurface, surface)
                assertTrue(
                    "$name onSurface-on-$role was %.2f:1, needs >= 4.5".format(ratio),
                    ratio >= 4.5
                )
            }
        }
    }

    @Test
    fun theErrorColourIsReadableAsText() {
        for ((name, s) in themes) {
            val ratio = contrastRatio(s.error, s.surfaceContainer)
            assertTrue(
                "$name error-on-card was %.2f:1, needs >= 4.5 — the destructive " +
                    "confirmation wears this".format(ratio),
                ratio >= 4.5
            )
        }
    }

    /**
     * The app's chrome holds no blue.
     *
     * The accent used to be a fully saturated periwinkle on a blue-cast set
     * of neutrals, and the user asked for the blue out — "for the whole
     * codebase, whether it's texts or UI/UX buttons". This is the rule that
     * keeps it out: **no role of the M3 scheme may sit in the blue band.**
     *
     * The band is a HUE range, not a channel comparison. "r < b" would call
     * violet, plum and magenta blue — and the accent the user chose IS a
     * plum. The blue band is 190°–255°: cyan through azure to indigo. A
     * violet (262°) is outside it, and a hue of 0 means the colour is a grey
     * and has no hue to be.
     *
     * **The semantic role hues are not covered here**, and that is a
     * decision rather than an oversight: three of the thirteen (Backup 214,
     * Migrate 190, Info 205) are in the blue band, and every assignment that
     * moved all three out of blue was searched — the best one drops the
     * minimum separation on the Home grid from 48° to 22°, so the six role
     * badges that screen shows would crowd and the colour would stop
     * carrying meaning. Their CHROMA came down instead. See `DvRole`.
     */
    @Test
    fun noPartOfTheChromeIsBlue() {
        val offenders = mutableListOf<String>()
        for ((name, s) in themes) {
            val roles = listOf(
                "primary" to s.primary,
                "onPrimary" to s.onPrimary,
                "primaryContainer" to s.primaryContainer,
                "onPrimaryContainer" to s.onPrimaryContainer,
                "secondaryContainer" to s.secondaryContainer,
                "onSecondaryContainer" to s.onSecondaryContainer,
                "tertiary" to s.tertiary,
                "tertiaryContainer" to s.tertiaryContainer,
                "error" to s.error,
                "errorContainer" to s.errorContainer,
                "background" to s.background,
                "surface" to s.surface,
                "surfaceVariant" to s.surfaceVariant,
                "surfaceContainer" to s.surfaceContainer,
                "onSurface" to s.onSurface
            )
            for ((role, c) in roles) {
                val (hue, sat) = hueOf(c)
                if (sat < 0.10f) continue          // a grey has no hue to be blue
                if (hue in BLUE_BAND) {
                    offenders += "$name/$role is in the blue band (hue %.0f°, sat %.2f)"
                        .format(hue, sat)
                }
            }
        }
        assertTrue(
            "the chrome is meant to hold no blue at all:\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    /** The hue band that reads as blue: cyan (190°) through azure to indigo (255°). */
    private val BLUE_BAND = 190f..255f

    /**
     * The hue and saturation of a colour, in degrees and 0..1.
     *
     * Written out rather than taken from `androidx.core.graphics.ColorUtils`,
     * whose `HSLToColor` calls `android.graphics.Color` — which is stubbed in
     * a unit test and throws.
     */
    private fun hueOf(c: Color): Pair<Float, Float> {
        val r = c.red
        val g = c.green
        val b = c.blue
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val d = max - min
        if (d == 0f) return 0f to 0f
        val sat = if (max == 0f) 0f else d / max
        var h = when (max) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }
        if (h < 0f) h += 360f
        return h to sat
    }
}
