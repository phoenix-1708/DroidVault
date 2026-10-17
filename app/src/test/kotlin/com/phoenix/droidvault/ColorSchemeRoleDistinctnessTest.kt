package com.phoenix.droidvault

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.phoenix.droidvault.ui.theme.AmoledAccentColorScheme
import com.phoenix.droidvault.ui.theme.DarkColorScheme
import com.phoenix.droidvault.ui.theme.LightColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Material 3 resolves a container's content colour by **matching the container's
 * own colour against the scheme's roles for exact equality** —
 * `contentColorFor(color)` is a `when` over `colorScheme.surfaceVariant`,
 * `colorScheme.primaryContainer`, `colorScheme.surfaceContainerLow`, and so on,
 * and it returns the content colour of whichever branch matches first.
 *
 * That makes **distinctness a load-bearing property of the palette**, not a
 * nicety. If two roles that the function tells apart share a colour, the one
 * declared later never gets its chance: every container painted in that colour
 * silently takes the other's content colour.
 *
 * This shipped. `Color.kt` assigned the same `container` value to
 * `primaryContainer` *and* to `surfaceContainerLow`, `surfaceContainer`,
 * `surfaceContainerHigh` and `surfaceContainerHighest`, and M3 checks
 * `primaryContainer` before the `surfaceContainer*` roles — so every card,
 * sheet, dialog and row painted `surfaceContainerLow` resolved its content
 * colour to `onPrimaryContainer`, the accent blue. Measured on the reference
 * device: a shortcut title rendered `rgb(142,171,255)` where `onSurface` is
 * `rgb(228,229,240)` — so "Back up apps", "Restore apps", "Backup
 * Configurations" and every other navigation title read as hyperlinks.
 *
 * **It could not be found by contrast testing.** Every individual colour in the
 * scheme is fine; the defect is a collision between two of them. `AccentContrastTest`
 * passed throughout, and so did a theme-root
 * `LocalContentColor provides colorScheme.onSurface` — because a `Surface`
 * **always** provides `LocalContentColor` from `contentColorFor(its own colour)`,
 * overriding whatever the theme set. That provide only reaches content that is
 * not inside a `Surface`, which is why it fixed some call sites by luck and not
 * the ones a user actually notices.
 *
 * So this asserts the property `contentColorFor` depends on, for every role
 * pair it distinguishes.
 */
class ColorSchemeRoleDistinctnessTest {

    /** The roles `contentColorFor` resolves, with the content colour each must yield. */
    private data class Role(val name: String, val colour: (ColorScheme) -> Color, val on: (ColorScheme) -> Color)

    private fun roles(s: ColorScheme) = listOf(
        Role("background", { it.background }, { it.onBackground }),
        Role("surface", { it.surface }, { it.onSurface }),
        Role("surfaceVariant", { it.surfaceVariant }, { it.onSurfaceVariant }),
        Role("primary", { it.primary }, { it.onPrimary }),
        Role("secondary", { it.secondary }, { it.onSecondary }),
        Role("tertiary", { it.tertiary }, { it.onTertiary }),
        Role("primaryContainer", { it.primaryContainer }, { it.onPrimaryContainer }),
        Role("secondaryContainer", { it.secondaryContainer }, { it.onSecondaryContainer }),
        Role("tertiaryContainer", { it.tertiaryContainer }, { it.onTertiaryContainer }),
        Role("surfaceContainerLowest", { it.surfaceContainerLowest }, { it.onSurface }),
        Role("surfaceContainerLow", { it.surfaceContainerLow }, { it.onSurface }),
        Role("surfaceContainer", { it.surfaceContainer }, { it.onSurface }),
        Role("surfaceContainerHigh", { it.surfaceContainerHigh }, { it.onSurface }),
        Role("surfaceContainerHighest", { it.surfaceContainerHighest }, { it.onSurface })
    )

    /**
     * Reimplementation of M3's `contentColorFor`, branch order included. A test
     * that calls the real one would be better, but it is `internal` to M3 and
     * needs a `CompositionLocal`; this is the contract it actually implements,
     * and the assertion below is the thing that matters either way.
     */
    private fun contentColorFor(s: ColorScheme, colour: Color): Color? {
        for (r in roles(s)) {
            if (r.colour(s) == colour) return r.on(s)
        }
        return null
    }

    /** `#AARRGGBB`. `String.format` has no UInt converter, and
     *  `android.graphics.Color` is stubbed out in unit tests, so the packed
     *  value is printed straight from the field rather than via `toArgb`. */
    private fun hex(c: Color): String {
        val v = c.value.toLong()
        return "#" + java.lang.Long.toHexString(v).padStart(8, '0').uppercase()
    }

    private fun assertDistinct(scheme: ColorScheme, label: String) {
        val rs = roles(scheme)
        val collisions = mutableListOf<String>()
        for (i in rs.indices) {
            for (j in i + 1 until rs.size) {
                val a = rs[i]
                val b = rs[j]
                if (a.colour(scheme) != b.colour(scheme)) continue
                // A collision is only harmful if it changes the resolved content
                // colour. Two roles that resolve to the SAME content colour are
                // harmless and should not fail the build.
                val wantA = a.on(scheme)
                val wantB = b.on(scheme)
                if (wantA == wantB) continue
                collisions += "${a.name} == ${b.name} (both ${hex(a.colour(scheme))}) " +
                    "but resolve to ${hex(wantA)} and ${hex(wantB)}"
            }
        }
        assertTrue(
            collisions.joinToString("\n") {
                "$label: two scheme roles share a colour, and Material resolves them " +
                    "by that colour — so one of them silently takes the other's " +
                    "content colour.\n  $it"
            },
            collisions.isEmpty()
        )
    }

    /** The hand-written AMOLED scheme, which is the app's dark default. */
    @Test
    fun amoledSchemeRolesAreDistinct() {
        assertDistinct(AmoledAccentColorScheme, "AmoledAccentColorScheme")
    }

    /** The light scheme. It carried the identical defect: `primaryContainer`,
     *  `surfaceVariant` and all four used `surfaceContainer*` roles were one
     *  value, so light-mode containers resolved to `onPrimaryContainer` too. */
    @Test
    fun lightSchemeRolesAreDistinct() {
        assertDistinct(LightColorScheme, "LightColorScheme")
    }

    /** The app's default dark scheme — `theme_mode` defaults to 0, which is this. */
    @Test
    fun darkSchemeRolesAreDistinct() {
        assertDistinct(DarkColorScheme, "DarkColorScheme")
    }

    /**
     * The specific defect, asserted directly so the reason this file exists is
     * legible: a `surfaceContainerLow` container must resolve to `onSurface`, not
     * to `onPrimaryContainer`. This is what the Home shortcut titles were getting.
     */
    @Test
    fun aSurfaceContainerResolvesToOnSurfaceNotTheAccent() {
        for ((label, scheme) in listOf(
            "AmoledAccentColorScheme" to AmoledAccentColorScheme,
            "DarkColorScheme" to DarkColorScheme,
            "LightColorScheme" to LightColorScheme
        )) {
            for (role in listOf("surfaceContainerLowest", "surfaceContainerLow", "surfaceContainer",
                    "surfaceContainerHigh", "surfaceContainerHighest")) {
                val colour = roles(scheme).first { it.name == role }.colour(scheme)
                val resolved = contentColorFor(scheme, colour)
                assertEquals(
                    "$label/$role is painted ${hex(colour)} and Material resolves that to " +
                        "${hex(resolved ?: Color.Unspecified)}; expected onSurface " +
                        "${hex(scheme.onSurface)}. Two scheme roles must not share a " +
                        "colour, or the later branch of contentColorFor never runs.",
                    scheme.onSurface,
                    resolved
                )
            }
        }
    }
}