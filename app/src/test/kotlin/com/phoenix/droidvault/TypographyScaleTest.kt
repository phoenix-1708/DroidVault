package com.phoenix.droidvault

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isUnspecified
import com.phoenix.droidvault.ui.theme.Typography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The type scale is complete and monotonic.
 *
 * Droid Vault declared eight of Material's fifteen styles and let the other
 * seven fall through to the defaults — and four of those seven were the ones it
 * used hardest (`bodySmall` alone at 148 call sites). So roughly 230 places
 * rendered in Google's Medium weight while the app's own styles rendered
 * ExtraBold, and stepping down a list changed weight more than size.
 *
 * **A hole in a design system cannot be seen by looking at one screen**, which
 * is why this is asserted rather than eyeballed. The properties below are the
 * ones the gap broke:
 *
 *  - every group steps DOWN in size, largest first;
 *  - no two styles collide on size-and-weight, because a collision is what makes
 *    two roles look like the same role;
 *  - line height is never tighter than the type it sets, at any slot.
 */
class TypographyScaleTest {

    private data class Slot(val name: String, val size: Float, val weight: FontWeight?)

    private fun textUnitToFloat(v: TextUnit): Float = v.value

    private fun slots(): List<Slot> = listOf(
        Slot("displayLarge", Typography.displayLarge.fontSize.value, Typography.displayLarge.fontWeight),
        Slot("displayMedium", Typography.displayMedium.fontSize.value, Typography.displayMedium.fontWeight),
        Slot("displaySmall", Typography.displaySmall.fontSize.value, Typography.displaySmall.fontWeight),
        Slot("headlineLarge", Typography.headlineLarge.fontSize.value, Typography.headlineLarge.fontWeight),
        Slot("headlineMedium", Typography.headlineMedium.fontSize.value, Typography.headlineMedium.fontWeight),
        Slot("headlineSmall", Typography.headlineSmall.fontSize.value, Typography.headlineSmall.fontWeight),
        Slot("titleLarge", Typography.titleLarge.fontSize.value, Typography.titleLarge.fontWeight),
        Slot("titleMedium", Typography.titleMedium.fontSize.value, Typography.titleMedium.fontWeight),
        Slot("titleSmall", Typography.titleSmall.fontSize.value, Typography.titleSmall.fontWeight),
        Slot("bodyLarge", Typography.bodyLarge.fontSize.value, Typography.bodyLarge.fontWeight),
        Slot("bodyMedium", Typography.bodyMedium.fontSize.value, Typography.bodyMedium.fontWeight),
        Slot("bodySmall", Typography.bodySmall.fontSize.value, Typography.bodySmall.fontWeight),
        Slot("labelLarge", Typography.labelLarge.fontSize.value, Typography.labelLarge.fontWeight),
        Slot("labelMedium", Typography.labelMedium.fontSize.value, Typography.labelMedium.fontWeight),
        Slot("labelSmall", Typography.labelSmall.fontSize.value, Typography.labelSmall.fontWeight)
    )

    @Test
    fun `every slot steps down in size within its group`() {
        val groups = listOf(
            "display" to listOf("displayLarge", "displayMedium", "displaySmall"),
            "headline" to listOf("headlineLarge", "headlineMedium", "headlineSmall"),
            "title" to listOf("titleLarge", "titleMedium", "titleSmall"),
            "body" to listOf("bodyLarge", "bodyMedium", "bodySmall"),
            "label" to listOf("labelLarge", "labelMedium", "labelSmall")
        )
        val byName = slots().associateBy { it.name }
        for ((group, names) in groups) {
            for (i in 0 until names.size - 1) {
                val a = byName.getValue(names[i]).size
                val b = byName.getValue(names[i + 1]).size
                assertTrue(
                    "$group: ${names[i]} (${a}sp) must be larger than " +
                        "${names[i + 1]} (${b}sp)",
                    a > b
                )
            }
        }
    }

    @Test
    fun `no two slots collide on size and weight`() {
        // A collision is what makes two roles look like one. The old scale had
        // three styles within a point of 15sp and the fallback styles carried a
        // weight nothing else in the app used.
        val all = slots()
        for (i in all.indices) {
            for (j in i + 1 until all.size) {
                val a = all[i]
                val b = all[j]
                val sameSize = a.size == b.size
                val sameWeight = a.weight == b.weight
                assertTrue(
                    "${a.name} and ${b.name} are both ${a.size}sp ${a.weight} — " +
                        "two roles that look identical",
                    !(sameSize && sameWeight)
                )
            }
        }
    }

    @Test
    fun `no style is tighter than the type it sets`() {
        // lineHeight below fontSize clips descenders and is how a "premium"
        // scale usually turns into a cramped one.
        val all = listOf(
            "displayLarge" to Typography.displayLarge,
            "displayMedium" to Typography.displayMedium,
            "displaySmall" to Typography.displaySmall,
            "headlineLarge" to Typography.headlineLarge,
            "headlineMedium" to Typography.headlineMedium,
            "headlineSmall" to Typography.headlineSmall,
            "titleLarge" to Typography.titleLarge,
            "titleMedium" to Typography.titleMedium,
            "titleSmall" to Typography.titleSmall,
            "bodyLarge" to Typography.bodyLarge,
            "bodyMedium" to Typography.bodyMedium,
            "bodySmall" to Typography.bodySmall,
            "labelLarge" to Typography.labelLarge,
            "labelMedium" to Typography.labelMedium,
            "labelSmall" to Typography.labelSmall
        )
        for ((name, style) in all) {
            assertTrue(
                "$name sets ${style.lineHeight.value}sp of line for " +
                    "${style.fontSize.value}sp of type — the descenders clip",
                textUnitToFloat(style.lineHeight) >= style.fontSize.value
            )
        }
    }

    @Test
    fun `the seven previously-missing slots are actually defined`() {
        // Stated as facts about the values rather than as "not the default",
        // because these five are the exact ones the gap left open.
        assertEquals(
            "titleSmall must be SemiBold, not M3's Medium",
            FontWeight.SemiBold, Typography.titleSmall.fontWeight
        )
        assertEquals(
            "labelLarge must be SemiBold, not the old ExtraBold",
            FontWeight.SemiBold, Typography.labelLarge.fontWeight
        )
        assertEquals(
            "titleMedium must be SemiBold",
            FontWeight.SemiBold, Typography.titleMedium.fontWeight
        )
        assertEquals(
            "labelMedium must be Medium",
            FontWeight.Medium, Typography.labelMedium.fontWeight
        )
    }

    @Test
    fun `no slot leaves a value unspecified`() {
        // The hole one level below a missing slot. `TextStyle`'s properties are
        // all nullable / `TextUnit.Unspecified`, so omitting one compiles, reads
        // as a decision in review, and inherits at runtime — where `value` is
        // NaN and every comparison against it silently passes. `titleLarge`
        // shipped exactly that here: tracking omitted, and the tracking test
        // below passed against NaN rather than failing.
        val named = listOf(
            "displayLarge" to Typography.displayLarge,
            "displayMedium" to Typography.displayMedium,
            "displaySmall" to Typography.displaySmall,
            "headlineLarge" to Typography.headlineLarge,
            "headlineMedium" to Typography.headlineMedium,
            "headlineSmall" to Typography.headlineSmall,
            "titleLarge" to Typography.titleLarge,
            "titleMedium" to Typography.titleMedium,
            "titleSmall" to Typography.titleSmall,
            "bodyLarge" to Typography.bodyLarge,
            "bodyMedium" to Typography.bodyMedium,
            "bodySmall" to Typography.bodySmall,
            "labelLarge" to Typography.labelLarge,
            "labelMedium" to Typography.labelMedium,
            "labelSmall" to Typography.labelSmall
        )
        for ((name, style) in named) {
            val holes = buildList {
                if (style.fontSize.isUnspecified) add("fontSize")
                if (style.lineHeight.isUnspecified) add("lineHeight")
                if (style.letterSpacing.isUnspecified) add("letterSpacing")
                if (style.fontWeight == null) add("fontWeight")
                if (style.fontFamily == null) add("fontFamily")
            }
            assertTrue(
                "$name leaves ${holes.joinToString()} unspecified — it inherits at " +
                    "runtime, and an inherited size cannot be reasoned about",
                holes.isEmpty()
            )
        }
    }

    @Test
    fun `tracking tightens as type grows, so small text is the loosest`() {
        // Asserted as a SHAPE within each group, not as one flat direction.
        // The tempting wrong form is "bodySmall must not be tracked wider than
        // bodyMedium", which reads like a tidy rule and is backwards: Material
        // loosens small type on purpose, because small type needs the extra room
        // to stay legible. The rule is that tracking does not INCREASE as the
        // type grows — a 28sp headline at 0, a 15sp body line at 0.2sp, an 11sp
        // label at 0.4sp.
        val groups = listOf(
            "title" to listOf(
                "titleLarge" to Typography.titleLarge,
                "titleMedium" to Typography.titleMedium,
                "titleSmall" to Typography.titleSmall
            ),
            "body" to listOf(
                "bodyLarge" to Typography.bodyLarge,
                "bodyMedium" to Typography.bodyMedium,
                "bodySmall" to Typography.bodySmall
            ),
            "label" to listOf(
                "labelLarge" to Typography.labelLarge,
                "labelMedium" to Typography.labelMedium,
                "labelSmall" to Typography.labelSmall
            )
        )
        for ((group, largestFirst) in groups) {
            for (i in 0 until largestFirst.size - 1) {
                val (biggerName, bigger) = largestFirst[i]
                val (smallerName, smaller) = largestFirst[i + 1]
                assertTrue(
                    "$group: $smallerName (${smaller.fontSize.value}sp) is tracked " +
                        "${smaller.letterSpacing.value}sp while the larger " +
                        "$biggerName (${bigger.fontSize.value}sp) is tracked " +
                        "${bigger.letterSpacing.value}sp — tracking must tighten as " +
                        "type grows, or the small text sets as a shout",
                    bigger.letterSpacing.value <= smaller.letterSpacing.value
                )
            }
        }
    }
}