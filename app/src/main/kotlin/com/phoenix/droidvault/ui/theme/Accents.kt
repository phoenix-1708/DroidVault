package com.phoenix.droidvault.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

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
 *
 * **Three of these hues are still in the blue band** (Backup 214, Migrate 190,
 * Info 205) after the accent left blue, and that was a decision rather than
 * an oversight: every assignment that moves all three out of blue was
 * searched, and the best one drops the **minimum separation on the Home grid
 * from 48° to 22°** — the six role badges that screen shows at once would
 * crowd, and the colour stops carrying meaning. The hues are kept and the
 * CHROMA came down instead (see [DvPalette]), so a blue role reads as a
 * muted steel-blue rather than as the loud blue the app used to be built on.
 *
 * **These are the BASE hues.** A user-chosen accent rotates the family —
 * see [roleHues] — and the two that carry a meaning (Destructive rose, Error
 * red) never move.
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
 * The colours come from **tonal palettes** — one hue, one chroma, thirteen
 * perceptual tones — rather than from the HSL arithmetic this file used
 * before. The reason is measured, not aesthetic: HSL's lightness is not
 * perceptually uniform, so the same HSL lightness is a very different
 * brightness at different hues, and a palette generated that way is
 * unreadable at the ends of the wheel. A TONE is a perceptual lightness, so
 * the same tone has the same contrast against the same surface at every hue
 * — which is what lets a user choose any colour and still get a legible app.
 *
 * The constants are the app's own values, expressed as tones: the dark
 * accent sits at tone 70, its container at 24; the light accent at tone 40
 * and its container at 88. `AccentContrastTest` asserts the pairings against
 * the card tones they actually sit on.
 */
@Immutable
class DvPalette(
    private val hueFor: (DvRole) -> Double,
    private val chroma: Double,
    private val accentTone: Int,
    private val containerTone: Int,
    private val containerChromaScale: Double
) {
    /** The saturated role colour. Use for icons on a container, and for accent text. */
    fun accent(role: DvRole): Color =
        Color(rolePalette(hueFor(role), chroma).tone(accentTone))

    /** A tinted block that carries [accent] and separates from ordinary surfaces. */
    fun container(role: DvRole): Color =
        Color(rolePalette(hueFor(role), chroma * containerChromaScale).tone(containerTone))

    /** Colour for text/icons sitting on [container]. */
    fun onContainer(role: DvRole): Color = accent(role)
}

/**
 * The role palette for the app's own accent.
 *
 * The palette the app ships with is [rolePaletteFor] over the plum seed and
 * the base role hues; this is the same thing, spelled out for the tests and
 * for anything that needs the app's own palette without a seed.
 */
val LightDvPalette: DvPalette
    get() = rolePaletteFor(AccentSeed.Plum, isDark = false, roleHues(AccentSeed.Plum.hue))

/** Dark-theme accents, including AMOLED (same values; surfaces are darker, so the step is larger). */
val DarkDvPalette: DvPalette
    get() = rolePaletteFor(AccentSeed.Plum, isDark = true, roleHues(AccentSeed.Plum.hue))

val LocalDvPalette = staticCompositionLocalOf { DarkDvPalette }

/** Accent colour for a semantic role, e.g. a "Backup" action icon or heading. */
@Composable
@ReadOnlyComposable
fun dvAccent(role: DvRole): Color = LocalDvPalette.current.accent(role)

/** Tinted container colour for a semantic role. */
@Composable
@ReadOnlyComposable
fun dvContainer(role: DvRole): Color = LocalDvPalette.current.container(role)
