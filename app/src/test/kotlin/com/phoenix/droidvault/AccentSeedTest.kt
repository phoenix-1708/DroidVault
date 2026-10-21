package com.phoenix.droidvault

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.phoenix.droidvault.ui.theme.AccentContrast
import com.phoenix.droidvault.ui.theme.AccentSeed
import com.phoenix.droidvault.ui.theme.DvRole
import com.phoenix.droidvault.ui.theme.accentColorScheme
import com.phoenix.droidvault.ui.theme.roleHues
import com.phoenix.droidvault.ui.theme.rolePaletteFor
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.min

/**
 * **Every colour the user can pick produces a readable app.**
 *
 * That is the whole promise of the picker, and it is a promise about
 * arithmetic, not about taste: a seed colour goes through the tonal-palette
 * engine, and the roles it lands on are measured against the surfaces they
 * actually sit on. So the guarantee is TESTED over the whole wheel rather
 * than asserted.
 *
 * The reason it needed the tonal-palette engine at all is measured in
 * `AUDIT.md` §2.109: the app's own HSL model produced 375 failing pairings
 * across the Material palette, because HSL's lightness is not perceptually
 * uniform — a yellow at the same L as a blue is a very different brightness,
 * and the accent as text on a card fell to 2.94:1.
 */
class AccentSeedTest {

    private fun contrastRatio(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    /** The card tones the app's surfaces actually have, per theme. */
    private val lightCard = Color(0xFFF1F0EE)
    private val darkCard = Color(0xFF1D1C1B)
    private val amoledCard = Color(0xFF141414)

    private fun seed(hue: Double, chroma: Double, contrast: AccentContrast): AccentSeed {
        // Build a colour with that hue and chroma at a mid tone, so the seed
        // carries them.
        val argb = com.materialkolor.hct.Hct.from(hue, chroma, 50.0).toInt()
        return AccentSeed(argb, contrast)
    }

    @Test
    fun everyHueProducesARreadableScheme() {
        val chromas = listOf(8.0, 24.0, 48.0, 80.0, 120.0)
        var checked = 0
        for (hue in 0 until 360 step 3) {
            for (chroma in chromas) {
                for (contrast in AccentContrast.entries) {
                    val s = seed(hue.toDouble(), chroma, contrast)
                    for (isDark in listOf(false, true)) {
                        val scheme = accentColorScheme(s, isDark, isAmoled = false)
                        val card = if (isDark) darkCard else lightCard
                        val label = "hue $hue chroma $chroma ${contrast.name} ${if (isDark) "dark" else "light"}"

                        val onButton = contrastRatio(scheme.onPrimary, scheme.primary)
                        assertTrue(
                            "$label: the label on the button was %.2f:1, needs >= 4.5".format(onButton),
                            onButton >= 4.5
                        )
                        val asText = contrastRatio(scheme.primary, card)
                        assertTrue(
                            "$label: the accent as text on a card was %.2f:1, needs >= 4.5".format(asText),
                            asText >= 4.5
                        )
                        val onPlate = contrastRatio(scheme.onPrimaryContainer, scheme.primaryContainer)
                        assertTrue(
                            "$label: the label on the plate was %.2f:1, needs >= 4.5".format(onPlate),
                            onPlate >= 4.5
                        )
                        val onSelection = contrastRatio(scheme.onSecondaryContainer, scheme.secondaryContainer)
                        assertTrue(
                            "$label: the label on the selection was %.2f:1, needs >= 4.5".format(onSelection),
                            onSelection >= 4.5
                        )
                        val plate = contrastRatio(scheme.primaryContainer, card)
                        assertTrue(
                            "$label: the plate against the card was %.2f:1, needs >= 1.15".format(plate),
                            plate >= 1.15
                        )
                        checked++
                    }
                }
            }
        }
        assertTrue("the sweep checked nothing", checked > 500)
    }

    @Test
    fun everyHueProducesReadableRoles() {
        for (hue in 0 until 360 step 3) {
            for (chroma in listOf(8.0, 30.0, 60.0, 100.0)) {
                for (contrast in AccentContrast.entries) {
                    for (isDark in listOf(false, true)) {
                        val s = seed(hue.toDouble(), chroma, contrast)
                        val hues = roleHues(s.hue)
                        val palette = rolePaletteFor(s, isDark, hues)
                        val card = if (isDark) darkCard else lightCard
                        val label = "hue $hue chroma $chroma ${contrast.name} ${if (isDark) "dark" else "light"}"
                        for (role in DvRole.entries) {
                            val accent = palette.accent(role)
                            val onCard = contrastRatio(accent, card)
                            assertTrue(
                                "$label: $role on the card was %.2f:1, needs >= 4.5".format(onCard),
                                onCard >= 4.5
                            )
                            val onContainer = contrastRatio(accent, palette.container(role))
                            assertTrue(
                                "$label: $role on its container was %.2f:1, needs >= 4.5".format(onContainer),
                                onContainer >= 4.5
                            )
                            val containerOnCard = contrastRatio(palette.container(role), card)
                            assertTrue(
                                "$label: $role's container on the card was %.2f:1, needs >= 1.15"
                                    .format(containerOnCard),
                                containerOnCard >= 1.15
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun everyRoleLayoutKeepsTheRolesTellableApart() {
        for (hue in 0 until 360) {
            val hues = roleHues(hue.toDouble())
            for (role in DvRole.entries) {
                assertTrue(
                    "hue $hue: $role has no hue",
                    hues.containsKey(role)
                )
            }
            // The separations the app asserts.
            assertSeparated(hue, hues, listOf(DvRole.Flash, DvRole.Migrate, DvRole.Tools, DvRole.Auto), 30.0)
            assertSeparated(hue, hues, listOf(DvRole.Error, DvRole.Warning, DvRole.Info), 30.0)
            assertSeparated(hue, hues, listOf(DvRole.Success, DvRole.Warning, DvRole.Error), 30.0)
            assertSeparated(hue, hues, listOf(DvRole.Destructive, DvRole.Tools), 30.0)
            assertSeparated(hue, hues, listOf(DvRole.Destructive, DvRole.Storage), 30.0)
            // And no two roles may be indistinguishable: the app's own layout
            // keeps Storage 8° from Warning and Backup 9° from Info, and a
            // rotation preserves those gaps exactly.
            for (i in DvRole.entries.indices) {
                for (j in i + 1 until DvRole.entries.size) {
                    val a = DvRole.entries[i]
                    val b = DvRole.entries[j]
                    val d = hueDistance(hues.getValue(a), hues.getValue(b))
                    assertTrue(
                        "hue $hue: $a and $b are only %.0f degrees apart, so two badges " +
                            "would look the same".format(d),
                        d >= 7.9
                    )
                }
            }
        }
    }

    @Test
    fun theDangerAndErrorHuesNeverMove() {
        for (hue in 0 until 360) {
            val hues = roleHues(hue.toDouble())
            assertTrue(
                "hue $hue: Destructive moved to ${hues.getValue(DvRole.Destructive)}",
                hueDistance(hues.getValue(DvRole.Destructive), 344.0) < 0.001
            )
            assertTrue(
                "hue $hue: Error moved to ${hues.getValue(DvRole.Error)}",
                hueDistance(hues.getValue(DvRole.Error), 4.0) < 0.001
            )
        }
    }

    @Test
    fun theFunctionalRolesFollowThePick() {
        // The eight functional roles rotate with the accent, so a teal pick
        // moves the family; the two pinned ones do not.
        val teal = roleHues(172.0)
        val plum = roleHues(305.0)
        assertTrue(
            "the functional family must move with the pick",
            hueDistance(teal.getValue(DvRole.Backup), plum.getValue(DvRole.Backup)) > 20.0
        )
    }

    private fun assertSeparated(
        hue: Int,
        hues: Map<DvRole, Double>,
        group: List<DvRole>,
        minSep: Double
    ) {
        for (i in group.indices) {
            for (j in i + 1 until group.size) {
                val d = hueDistance(hues.getValue(group[i]), hues.getValue(group[j]))
                assertTrue(
                    "hue $hue: ${group[i]} and ${group[j]} are only %.0f degrees apart, needs " +
                        ">= %.0f".format(d, minSep),
                    d >= minSep - 0.001
                )
            }
        }
    }

    private fun hueDistance(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return min(d, 360.0 - d)
    }
}
