package com.phoenix.droidvault.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.materialkolor.hct.Hct
import com.materialkolor.palettes.TonalPalette
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The accent the user chose, and everything derived from it.
 *
 * ## Why this exists, and why it is not the app's own colour model
 *
 * The app used to generate every colour from **HSL** (see [DvPalette]), and
 * HSL's lightness is not perceptually uniform: a yellow at L = 0.35 is far
 * brighter than a blue at the same L, and a deep blue at L = 0.68 is far
 * darker than a warm red at the same L. So a scheme generated from an
 * arbitrary hue in HSL is not merely different — it is **unreadable** at the
 * ends of the wheel. Measured over the whole Material palette, 375 of the
 * pairings failed their floor: the accent as text on a card fell to 2.94:1 for
 * a pale yellow, the tonal plate against the card to 1.09:1 for a mid lime,
 * and the label on the plate to 3.35:1.
 *
 * Material's own answer is the **HCT** colour space — hue, chroma and a
 * perceptual *tone* — and a *tonal palette*: one hue and one chroma, thirteen
 * tones. Because a tone is a perceptual lightness, the same tone has the same
 * contrast against the same surface at EVERY hue. That is the property that
 * lets a user pick any colour at all and still get a readable app, and it is
 * why this file is built on `material-color-utilities` rather than on the
 * app's own arithmetic.
 *
 * The app's own model did not go away: the thirteen SEMANTIC roles still
 * generate from their own hues, and they now do it through the same tonal
 * palettes so that they too behave the same at every hue.
 */
@Immutable
data class AccentSeed(
    /** The chosen colour, ARGB. */
    val argb: Int,
    /** How loud the accent is. */
    val contrast: AccentContrast = AccentContrast.STANDARD
) {
    /** The colour as HCT — hue, chroma and perceptual tone. */
    val hct: Hct get() = Hct.fromInt(argb)

    /** The hue, in HCT degrees. */
    val hue: Double get() = hct.hue

    /** The chroma — how saturated the colour is, independent of its lightness. */
    val chroma: Double get() = hct.chroma

    companion object {
        /**
         * The app's own accent: a muted plum.
         *
         * Expressed as a SEED rather than as a set of hexes, so that the
         * default and a user's pick travel the same code path and the same
         * guarantees.
         */
        val Plum = AccentSeed(0xFFC497C0.toInt())
    }
}

/**
 * How loud the accent is.
 *
 * Three steps rather than a slider: a continuous control is a fiddly thing to
 * aim at, and a name is easier to remember than a position. Each step moves
 * the accent's chroma and its tone, so "soft" is genuinely quieter and
 * "punchy" is genuinely louder — and every step is measured against the same
 * floors.
 */
enum class AccentContrast {
    /** Quiet: less chroma, and the accent closer to the surface. */
    SOFT,

    /** The app's own balance. */
    STANDARD,

    /** Loud: more chroma, and the accent further from the surface. */
    PUNCHY;

    /** How much of the seed's chroma this step keeps. */
    val chromaScale: Double
        get() = when (this) {
            SOFT -> 0.72
            STANDARD -> 1.0
            PUNCHY -> 1.15
        }
}

/**
 * The tones the accent's roles are painted at.
 *
 * These are the app's own values, expressed as HCT tones: the dark accent
 * sits at tone 68 (which is where the plum's `#C497C0` already was, measured),
 * its plate at 20 and its selection at 24. The light theme's accent is at
 * tone 35, its plate at 88 and its selection at 84.
 */
private data class RoleTones(
    val accent: Int,
    val onAccent: Int,
    val plate: Int,
    val selection: Int,
    /** The plate's chroma, as a fraction of the accent's. */
    val plateChroma: Double
)

private fun tones(dark: Boolean, contrast: AccentContrast): RoleTones = when {
    dark && contrast == AccentContrast.SOFT -> RoleTones(66, 12, 18, 22, 0.46)
    dark && contrast == AccentContrast.PUNCHY -> RoleTones(72, 8, 24, 28, 0.52)
    dark -> RoleTones(68, 10, 20, 24, 0.46)
    contrast == AccentContrast.SOFT -> RoleTones(38, 100, 84, 88, 0.30)
    contrast == AccentContrast.PUNCHY -> RoleTones(30, 100, 76, 84, 0.38)
    else -> RoleTones(35, 100, 80, 88, 0.34)
}

/**
 * The tonal palette of a seed — one hue, one chroma, thirteen tones.
 *
 * The chroma is the seed's own, scaled by the contrast step and clamped to
 * what the sRGB gamut can actually show at that hue.
 */
fun AccentSeed.palette(chromaScale: Double = 1.0): TonalPalette =
    TonalPalette.fromHueAndChroma(hue, (chroma * chromaScale).coerceIn(0.0, 160.0))

/** The tonal palette of one role, at the role's own hue and chroma. */
fun rolePalette(hue: Double, chroma: Double, chromaScale: Double = 1.0): TonalPalette =
    TonalPalette.fromHueAndChroma(hue, (chroma * chromaScale).coerceIn(0.0, 160.0))

/**
 * The scheme for a seed, in one theme.
 *
 * **The neutrals do NOT follow the seed.** The app's surfaces are warm
 * greys, and they stay warm greys: a surface tinted with the accent is the
 * same complaint the user made about a blue-tinted surface, in a different
 * colour. The seed tints the ACCENT — the buttons, the links, the live
 * status, the switches — and nothing else.
 *
 * `tertiary` and `error` are the app's own (the restore violet, the DvRole
 * red): they carry meaning, and no user choice may make "Delete" look safe.
 */
fun accentColorScheme(seed: AccentSeed, isDark: Boolean, isAmoled: Boolean): ColorScheme {
    val t = tones(isDark, seed.contrast)
    val palette = seed.palette(seed.contrast.chromaScale)
    val primary = Color(palette.tone(t.accent))
    val onPrimary = Color(palette.tone(t.onAccent))
    val plate = Color(palette.tone(t.plate))
    val selection = Color(palette.tone(t.selection))
    // The plate and the selection are tinted blocks, not the accent at full
    // chroma — a saturated block behind a coloured label is the loud look
    // this palette exists to avoid.
    val plateChroma = (seed.chroma * seed.contrast.chromaScale * t.plateChroma).coerceIn(0.0, 160.0)
    val plateTone = TonalPalette.fromHueAndChroma(seed.hue, plateChroma)
    val selectionChroma = (plateChroma * 1.25).coerceIn(0.0, 160.0)
    val selectionTone = TonalPalette.fromHueAndChroma(seed.hue, selectionChroma)

    return if (isDark) {
        darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = Color(plateTone.tone(t.plate)),
            onPrimaryContainer = primary,
            secondaryContainer = Color(selectionTone.tone(t.selection)),
            onSecondaryContainer = primary,
            tertiary = DvFixedRoles.DarkRestoreViolet,
            onTertiary = DvFixedRoles.DarkRestoreVioletInk,
            tertiaryContainer = DvFixedRoles.DarkRestoreVioletContainer,
            onTertiaryContainer = DvFixedRoles.DarkRestoreViolet,
            error = DvFixedRoles.DarkError,
            onError = DvFixedRoles.DarkErrorInk,
            errorContainer = DvFixedRoles.DarkErrorContainer,
            onErrorContainer = DvFixedRoles.DarkError,
            background = if (isAmoled) DvNeutrals.AmoledBackground else DvNeutrals.DarkBackground,
            surface = if (isAmoled) DvNeutrals.AmoledSurface else DvNeutrals.DarkSurface,
            surfaceVariant = if (isAmoled) DvNeutrals.AmoledSurfaceVariant else DvNeutrals.DarkSurfaceVariant,
            surfaceContainerLowest = if (isAmoled) DvNeutrals.AmoledCardLowest else DvNeutrals.DarkCardLowest,
            surfaceContainerLow = if (isAmoled) DvNeutrals.AmoledCardLow else DvNeutrals.DarkCard,
            surfaceContainer = if (isAmoled) DvNeutrals.AmoledCard else DvNeutrals.DarkCard,
            surfaceContainerHigh = if (isAmoled) DvNeutrals.AmoledCardHigh else DvNeutrals.DarkCard,
            surfaceContainerHighest = if (isAmoled) DvNeutrals.AmoledCardHighest else DvNeutrals.DarkCard,
            onBackground = DvNeutrals.DarkOnSurface,
            onSurface = DvNeutrals.DarkOnSurface,
            onSurfaceVariant = DvNeutrals.DarkOnSurface.copy(alpha = 0.7f)
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = Color.White,
            primaryContainer = Color(plateTone.tone(t.plate)),
            onPrimaryContainer = primary,
            secondaryContainer = Color(selectionTone.tone(t.selection)),
            onSecondaryContainer = primary,
            tertiary = DvFixedRoles.LightRestoreViolet,
            onTertiary = Color.White,
            tertiaryContainer = DvFixedRoles.LightRestoreVioletContainer,
            onTertiaryContainer = DvFixedRoles.LightRestoreVioletInk,
            error = DvFixedRoles.LightError,
            onError = Color.White,
            errorContainer = DvFixedRoles.LightErrorContainer,
            onErrorContainer = DvFixedRoles.LightErrorInk,
            background = DvNeutrals.LightBackground,
            surface = DvNeutrals.LightSurface,
            surfaceVariant = DvNeutrals.LightSurfaceVariant,
            surfaceContainerLowest = DvNeutrals.LightCardLowest,
            surfaceContainerLow = DvNeutrals.LightCard,
            surfaceContainer = DvNeutrals.LightCard,
            surfaceContainerHigh = DvNeutrals.LightCard,
            surfaceContainerHighest = DvNeutrals.LightCard,
            onBackground = DvNeutrals.LightOnSurface,
            onSurface = DvNeutrals.LightOnSurface,
            onSurfaceVariant = DvNeutrals.LightOnSurface.copy(alpha = 0.7f)
        )
    }
}

/**
 * The hue every role takes, for a given seed.
 *
 * **The eight functional roles follow the pick; the five status roles do
 * not.** Error stays red and Destructive stays rose, because a "Delete" that
 * is teal is a lie; the other three status roles (Warning, Success, Info)
 * rotate WITH the functional family, so the palette stays one family.
 *
 * A rigid rotation is not enough on its own: thirteen hues plus two pinned
 * reds do not fit a circle with the separations the app needs. So the
 * rotation is followed by a repair that pushes apart the roles that ended up
 * too close — the functional ones move, the pinned ones never do — and a
 * seed that cannot be repaired falls back to the app's own layout, which is
 * always valid.
 */
fun roleHues(seedHue: Double): Map<DvRole, Double> {
    // The anchor is the app's OWN accent, so the default seed gives the
    // default layout, unchanged: the plum sits at 331° on the wheel, between
    // Destructive's rose and Flash's magenta, and that is the mark the family
    // turns around.
    val delta = seedHue - DEFAULT_SEED_HUE
    val hues = DvRole.entries.associateWith { role ->
        if (role.pinned) role.baseHue else (role.baseHue + delta).mod360()
    }
    return repairHues(hues)
}

/** The hue of the app's own accent — the family's anchor. */
private const val DEFAULT_SEED_HUE = 331.1

/**
 * The base hue of a role.
 *
 * The hues live on the enum itself ([DvRole.hue]) — there is one table of
 * them, not two.
 */
private val DvRole.baseHue: Double get() = hue.toDouble()

/**
 * Whether a role's hue is fixed.
 *
 * Only the two that must read as "danger" and "failure" whatever the user
 * chose: Destructive (rose) and Error (red). Everything else follows the
 * accent.
 */
private val DvRole.pinned: Boolean
    get() = this == DvRole.Destructive || this == DvRole.Error

/**
 * The separations the app asserts, as (roles, minimum degrees).
 *
 * These are the rules the existing colour tests enforce; the repair has to
 * keep them, and the sweep test proves it does for every seed.
 */
private val REQUIRED_SEPARATIONS: List<Pair<List<DvRole>, Double>> = listOf(
    listOf(DvRole.Flash, DvRole.Migrate, DvRole.Tools, DvRole.Auto) to 30.0,
    listOf(DvRole.Error, DvRole.Warning, DvRole.Info) to 30.0,
    listOf(DvRole.Success, DvRole.Warning, DvRole.Error) to 30.0,
    listOf(DvRole.Destructive, DvRole.Tools) to 30.0,
    listOf(DvRole.Destructive, DvRole.Storage) to 30.0
)

/**
 * How close any role may come to a PINNED one.
 *
 * The pinned roles are the two that must read as danger and failure whatever
 * the user chose, so nothing may crowd them. The app's own layout keeps
 * Destructive 40° from Tools and 58° from Storage; 20° is the tightest
 * pinned pair the base layout has (Error and Tools), and it is the floor.
 */
private const val PINNED_SEPARATION = 20.0

/**
 * The tightest any two roles may be, in the app's own layout.
 *
 * The base layout has Storage 8° from Warning and Backup 9° from Info, and
 * it ships that way — a rotation preserves those gaps exactly, and the
 * repair may not tighten them.
 */
private const val TIGHTEST_PAIR = 8.0

private fun Double.mod360(): Double = ((this % 360.0) + 360.0) % 360.0

private fun hueDistance(a: Double, b: Double): Double {
    val d = abs(a - b) % 360.0
    return min(d, 360.0 - d)
}

/**
 * Push the roles apart until every required separation holds.
 *
 * A rigid rotation preserves every separation among the rotating roles
 * EXACTLY — the app's own tightest pair (Storage 8° from Warning) survives a
 * rotation untouched. What a rotation CAN break is a rotating role landing
 * on one of the two PINNED roles, because those never move: thirteen hues on
 * a wheel, with Error and Destructive holding their red, leave the family
 * very little room to turn.
 *
 * So the repair is a SEARCH, not a nudge: a role that is in the way is
 * offered every position within reach, and the one that keeps EVERY rule
 * while sitting CLOSEST to where the rotation put it wins. The family
 * therefore follows the picked colour as closely as the rules allow, and
 * gives way only where it must.
 *
 * A seed with no valid position falls back to the app's own layout, which
 * is valid by construction — so this function ALWAYS returns a layout the
 * app can ship.
 */
private fun repairHues(hues: Map<DvRole, Double>): Map<DvRole, Double> {
    val base = DvRole.entries.associateWith { it.baseHue }
    var current = hues.toMutableMap()

    /**
     * Whether a role may sit at [candidate], judged ONLY against the roles
     * already placed.
     *
     * The check is local on purpose: a rule that involves two OTHER roles is
     * not this role's business, and a global check could never be satisfied
     * (every role is waiting for every other one to move). Each role is
     * therefore placed in turn, against the ones before it, and the ones
     * after it will move around it when their turn comes.
     */
    fun fits(role: DvRole, candidate: Double): Boolean {
        val trial = current.toMutableMap()
        trial[role] = candidate.mod360()
        for ((group, minSep) in REQUIRED_SEPARATIONS) {
            if (!group.contains(role)) continue
            for (other in group) {
                if (other == role) continue
                if (hueDistance(candidate, trial.getValue(other)) < minSep - 0.001) return false
            }
        }
        if (!role.pinned) {
            for (pinned in DvRole.entries.filter { it.pinned }) {
                if (hueDistance(candidate, trial.getValue(pinned)) < PINNED_SEPARATION - 0.001) {
                    return false
                }
            }
        }
        for (other in DvRole.entries) {
            if (other == role) continue
            if (hueDistance(candidate, trial.getValue(other)) < TIGHTEST_PAIR - 0.001) return false
        }
        return true
    }

    // One pass per role, in the enum's order, so the result is deterministic.
    for (role in DvRole.entries) {
        if (role.pinned) continue
        val from = current.getValue(role)
        if (fits(role, from)) continue
        // Every position within reach, closest first.
        var best: Double? = null
        for (offset in 0..SEARCH_RANGE.toInt()) {
            for (direction in listOf(1.0, -1.0)) {
                if (offset == 0 && direction < 0) continue
                val candidate = from + direction * offset
                if (fits(role, candidate)) {
                    best = candidate.mod360()
                    break
                }
            }
            if (best != null) break
        }
        if (best == null) return base
        current[role] = best
    }
    return current.mapValues { it.value.mod360() }
}

/** How far the repair may move a role to find a legal position, in degrees. */
private const val SEARCH_RANGE = 180.0

/**
 * The role palette for a seed.
 *
 * The roles' hues follow the pick (see [roleHues]) and their colours come
 * from tonal palettes, so every role reads the same at every hue — the
 * property the HSL model did not have.
 */
fun rolePaletteFor(seed: AccentSeed, isDark: Boolean, hueMap: Map<DvRole, Double>): DvPalette {
    val chroma = (seed.chroma * seed.contrast.chromaScale).coerceIn(4.0, 120.0)
    val accentTone = if (isDark) 70 else 40
    val containerTone = if (isDark) 24 else 88
    val containerChroma = if (isDark) 0.55 else 0.42
    return DvPalette(
        hueFor = { hueMap.getValue(it) },
        chroma = chroma,
        accentTone = accentTone,
        containerTone = containerTone,
        containerChromaScale = containerChroma
    )
}

/** A hex string for a seed, for the prefs and for the picker's field. */
fun AccentSeed.hex(): String = "#%06X".format(argb and 0xFFFFFF)

/**
 * The accent a seed produces in a theme.
 *
 * The three helpers below are what the picker draws its swatches with: the
 * accent itself, the plate a button is painted on, and the selection
 * container. They are the same colours the theme uses, so the swatch the user
 * picks IS the colour they will see.
 */
fun AccentSeed.accentColor(isDark: Boolean): Color {
    val t = tones(isDark, contrast)
    return Color(palette(contrast.chromaScale).tone(t.accent))
}

/** The plate a filled action is painted on. */
fun AccentSeed.plateColor(isDark: Boolean): Color {
    val t = tones(isDark, contrast)
    val plateChroma = (chroma * contrast.chromaScale * t.plateChroma).coerceIn(0.0, 160.0)
    return Color(TonalPalette.fromHueAndChroma(hue, plateChroma).tone(t.plate))
}

/** The container a selected item is painted on. */
fun AccentSeed.selectionColor(isDark: Boolean): Color {
    val t = tones(isDark, contrast)
    val plateChroma = (chroma * contrast.chromaScale * t.plateChroma).coerceIn(0.0, 160.0)
    val selectionChroma = (plateChroma * 1.25).coerceIn(0.0, 160.0)
    return Color(TonalPalette.fromHueAndChroma(hue, selectionChroma).tone(t.selection))
}

/**
 * A seed taken from a wallpaper colour — the legacy dynamic-colour path, for
 * the devices before Android 12 that have no Monet scheme of their own.
 *
 * A grey wallpaper has no hue to follow, so the app's own plum is used
 * instead — the old fallback was hue 225, which is blue, and blue is the one
 * thing this palette exists to get away from.
 */
fun wallpaperSeed(argb: Int): AccentSeed {
    val hct = Hct.fromInt(argb)
    val hue = if (hct.chroma < 4.0) DEFAULT_SEED_HUE else hct.hue
    val chroma = hct.chroma.coerceIn(12.0, 90.0)
    return AccentSeed(Hct.from(hue, chroma, 50.0).toInt())
}
