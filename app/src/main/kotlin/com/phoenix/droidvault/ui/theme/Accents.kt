package com.phoenix.droidvault.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Semantic accent roles.
 *
 * Droid Vault used to tint every icon with `colorScheme.primary`, so backup,
 * restore, device-data, storage, migration, tools and destructive actions were
 * all the same blue. That is the "monotone" look this system replaces: colour
 * now carries meaning, so a user can tell what a control does before reading it.
 *
 * Hues are spaced roughly 45 degrees apart around the wheel so the four bottom
 * tabs (Flash / Migrate / Tools / Auto) stay maximally distinguishable from each
 * other, and no two roles that co-occur on one screen are neighbours:
 *
 *   Auto 128 (green)      Storage 42 (amber)     Restore 262 (violet)
 *   Success 152 (leaf)    Tools   24 (orange)    Flash  306 (magenta)
 *   Device 172 (teal)     Warning 34 (gold)      Destructive 344 (rose)
 *   Migrate 190 (cyan)    Backup  214 (azure)    Info 205 / Error 4
 *
 * Status roles (Success / Warning / Error / Info) deliberately reuse the same
 * hue family as their functional counterpart: an "Auto enabled" check and a
 * "backup finished" check are the same visual event to a user.
 */
enum class DvRole(val hue: Float) {
    /** Creating a backup: apps, partitions, folders, device data. */
    Backup(214f),

    /** Moving data to another device / storage target. */
    Migrate(190f),

    /** Putting data back: apps, partitions, folders, device data. */
    Restore(262f),

    /** Flashing recovery images / partitions. */
    Flash(306f),

    /** Scheduling and running unattended jobs. */
    Auto(128f),

    /** Utilities and maintenance. */
    Tools(24f),

    /** Folders, partitions and storage locations. */
    Storage(42f),

    /** Device-owned data: messages, calls, ROM data. */
    Device(172f),

    /** Anything that removes or overwrites. */
    Destructive(344f),

    Success(152f),
    Warning(34f),
    Error(4f),
    Info(205f)
}

/**
 * A themed set of semantic accents.
 *
 * Colours are generated from each role's hue with fixed saturation/lightness
 * constants rather than being hand-picked per role, which is what guarantees
 * the contrast floor: every role gets the same measured relationships instead
 * of some hues being quietly unreadable. `AccentContrastTest` asserts them.
 *
 *   light: accent on container 5.6:1, container on surface 1.2:1, accent on surface 6.8:1
 *   dark:  accent on container 5.4:1, container on surface 1.3:1, accent on surface 6.8:1
 */
@Immutable
class DvPalette(
    private val accentSaturation: Float,
    private val accentLightness: Float,
    private val containerSaturation: Float,
    private val containerLightness: Float
) {
    /** The saturated role colour. Use for icons on a container, and for accent text. */
    fun accent(role: DvRole): Color =
        Color.hsl(role.hue, accentSaturation, accentLightness)

    /** A tinted block that carries [accent] and separates from ordinary surfaces. */
    fun container(role: DvRole): Color =
        Color.hsl(role.hue, containerSaturation, containerLightness)

    /** Colour for text/icons sitting on [container]. */
    fun onContainer(role: DvRole): Color = accent(role)
}

/**
 * Light-theme accents.
 *
 * Containers sit at L=0.84 rather than the very pale 0.90-0.94 that the old
 * `primary.copy(alpha = 0.15f)` produced. That alpha tint over a near-white
 * surface collapsed to ~1.05:1, so in light mode the About-page capability
 * chips and every MenuCard badge were effectively invisible.
 */
val LightDvPalette = DvPalette(
    accentSaturation = 0.60f,
    accentLightness = 0.25f,
    containerSaturation = 0.55f,
    containerLightness = 0.84f
)

/** Dark-theme accents, including AMOLED (same values; surfaces are darker, so the step is larger). */
val DarkDvPalette = DvPalette(
    accentSaturation = 0.70f,
    accentLightness = 0.76f,
    containerSaturation = 0.40f,
    containerLightness = 0.25f
)

val LocalDvPalette = staticCompositionLocalOf { DarkDvPalette }

/** Accent colour for a semantic role, e.g. a "Backup" action icon or heading. */
@Composable
@ReadOnlyComposable
fun dvAccent(role: DvRole): Color = LocalDvPalette.current.accent(role)

/** Tinted container colour for a semantic role. */
@Composable
@ReadOnlyComposable
fun dvContainer(role: DvRole): Color = LocalDvPalette.current.container(role)

/**
 * Resolve the accent palette for the scheme that is actually in effect.
 *
 * Luminance of `background` is used rather than the stored `themeMode` int so
 * this stays correct for all five modes (system / light / dark / AMOLED /
 * AMOLED+accent) AND for Android 12+ dynamic (Monet) colour, which Droid Vault
 * enables by default. The accents themselves stay fixed by design: they are the
 * product's identity, and keeping them independent of the wallpaper seed is what
 * stops the UI from collapsing back to one wallpaper-derived hue.
 */
@Composable
@ReadOnlyComposable
fun dvPaletteFor(scheme: androidx.compose.material3.ColorScheme): DvPalette =
    if (scheme.background.luminance() < 0.5f) DarkDvPalette else LightDvPalette

