package com.phoenix.droidvault.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils

val PrimaryBlueLight = Color(0xFF2D5BD0)
val PrimaryBlueDark = Color(0xFF8EABFF)
val Periwrinkle = Color(0xFFE4E5F0)

/**
 * AMOLED surface elevation ramp.
 *
 * An earlier version mapped EVERY container role to pure black, identical to
 * `background`. That silently removed all surface hierarchy: dialogs sat
 * dialog-on-dialog, cards floated on the background, unselected chips matched
 * their row, and `LinearProgressIndicator(trackColor = surfaceVariant)`
 * rendered black-on-black so a progress bar looked permanently stuck.
 * These values keep the OLED power win (background/surface stay #000000)
 * while restoring a visible tonal step per elevation level.
 */
// NOT pure black: `background` is `#000000`, and a `surfaceContainerLowest`
// painted in the same value resolves to `onBackground` rather than `onSurface`.
// One step up is invisible against black and removes the collision.
internal val AmoledSurfaceLowest = Color(0xFF06070A)
internal val AmoledSurfaceLow = Color(0xFF0A0A0C)
internal val AmoledSurface = Color(0xFF121216)
internal val AmoledSurfaceVariant = Color(0xFF1A1A20)
// Distinct from `AmoledSurfaceVariant` above, which resolves to
// `onSurfaceVariant` while this resolves to `onSurface`.
internal val AmoledSurfaceHigh = Color(0xFF212430)
internal val AmoledSurfaceHighest = Color(0xFF23232B)

fun Color.toColorScheme(isDark: Boolean, isAmoled: Boolean = false): ColorScheme {
    val argb = this.toArgb()
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(argb, hsl)

    if (hsl[1] < 0.10f) {
        hsl[0] = 225f
        hsl[1] = 0.40f
    }

    if (hsl[1] < 0.15f) hsl[1] = 0.30f
    if (hsl[1] > 0.5f) hsl[1] = 0.45f

    fun getColor(h: Float, s: Float, l: Float) = Color(ColorUtils.HSLToColor(floatArrayOf(h, s, l)))

    return if (isDark) {
        val accent = getColor(hsl[0], 0.65f, 0.82f)
        val container = getColor(hsl[0], 0.10f, 0.12f)
        val darkText = getColor(hsl[0], 0.50f, 0.15f)

        // AMOLED keeps background/surface pure black but must still step the
        // container roles, or cards/dialogs/progress tracks lose all contrast
        // against the background. See the AmoledAccentColorScheme note.
        val cont = if (isAmoled) AmoledSurface else container

        darkColorScheme(
            primary = accent,
            onPrimary = darkText,
            primaryContainer = container,
            onPrimaryContainer = accent,
            secondaryContainer = getColor(hsl[0], 0.15f, 0.22f),
            onSecondaryContainer = accent,

            background = if (isAmoled) Color.Black else getColor(hsl[0], 0.08f, 0.05f),
            surface = if (isAmoled) Color.Black else getColor(hsl[0], 0.08f, 0.05f),
            // NOT `container`. Material matches a container's colour against the
            // scheme's roles for equality and checks `primaryContainer` first, so
            // reusing `container` here makes every `surfaceContainer*` card take
            // the accent blue as its text colour. ColorSchemeRoleDistinctnessTest.
            surfaceVariant = if (isAmoled) AmoledSurfaceVariant
                else getColor(hsl[0], 0.09f, 0.11f),

            surfaceContainerLowest = if (isAmoled) AmoledSurfaceLowest
                else getColor(hsl[0], 0.08f, 0.10f),
            surfaceContainerLow = cont,
            surfaceContainer = cont,
            surfaceContainerHigh = cont,
            surfaceContainerHighest = cont,

            // `background == surface`, so without this they resolve to two
            // different colours depending on which branch Material matched.
            onBackground = Periwrinkle,
            onSurface = Periwrinkle,
            onSurfaceVariant = Periwrinkle.copy(alpha = 0.7f)
        )
    } else {
        val accent = getColor(hsl[0], 0.65f, 0.40f)
        val container = getColor(hsl[0], 0.15f, 0.94f)

        lightColorScheme(
            primary = accent,
            onPrimary = Color.White,
            primaryContainer = container,
            onPrimaryContainer = accent,
            secondaryContainer = getColor(hsl[0], 0.35f, 0.85f),
            onSecondaryContainer = accent,

            background = getColor(hsl[0], 0.08f, 0.98f),
            surface = getColor(hsl[0], 0.08f, 0.98f),
            // Distinct from `container` — see the dark branch above.
            surfaceVariant = getColor(hsl[0], 0.13f, 0.90f),

            surfaceContainerLowest = Color.White,
            surfaceContainerLow = container,
            surfaceContainer = container,
            surfaceContainerHigh = container,
            surfaceContainerHighest = container,

            // `background == surface` — see the dark branch above.
            onBackground = Color.Black,
            onSurface = Color.Black,
            onSurfaceVariant = Color.Black.copy(alpha = 0.7f)
        )
    }
}

val LightColorScheme = lightColorScheme(
    primary = PrimaryBlueLight,
    onPrimary = Color.White,
    // Distinct from the card tone BELOW, and deliberately so. Material resolves a
    // container's content colour by matching that container's own colour against
    // the scheme's roles for EQUALITY, and checks `primaryContainer` before the
    // `surfaceContainer*` roles. When these two are the same value, every card,
    // sheet and dialog silently takes `onPrimaryContainer` — the accent blue — as
    // its text colour. See ColorSchemeRoleDistinctnessTest. These two are a shade
    // off the card tone: invisible as a fill, load-bearing as a distinction.
    primaryContainer = Color(0xFFE4ECFE),
    onPrimaryContainer = PrimaryBlueLight,
    secondaryContainer = Color(0xFFD0DBFF),
    onSecondaryContainer = PrimaryBlueLight,

    background = Color(0xFFF5F8FF),
    surface = Color(0xFFF5F8FF),
    surfaceVariant = Color(0xFFEAEFF9),

    // The five `surfaceContainer*` roles share one value on purpose: they all
    // resolve to `onSurface`, so colliding with EACH OTHER is harmless, and this
    // is the tone every card in the app is painted.
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F5FF),
    surfaceContainer = Color(0xFFF1F5FF),
    surfaceContainerHigh = Color(0xFFF1F5FF),
    surfaceContainerHighest = Color(0xFFF1F5FF),

    // `background == surface` here, which would otherwise resolve one of them to
    // M3's default `onBackground` and the other to `onSurface`. Making them the
    // same colour makes the collision harmless instead of fixing the colours.
    onBackground = Color.Black,
    onSurface = Color.Black,
    onSurfaceVariant = Color.Black.copy(alpha = 0.7f)
)

val DarkColorScheme = darkColorScheme(
    primary = PrimaryBlueDark,
    onPrimary = Color(0xFF00144B),
    // See LightColorScheme for why this must NOT equal the card tone below. It
    // was `#1D1F26` — the same value as `surfaceContainerLow` and every other
    // `surfaceContainer*` role — so every card, chip, sheet and dialog in the
    // dark theme resolved its text colour to `onPrimaryContainer`. Measured on
    // the reference device: Home shortcut titles, "Backup Configurations" and
    // the Auto rows rendered rgb(142,171,255) where onSurface is rgb(228,229,240).
    primaryContainer = Color(0xFF20232C),
    onPrimaryContainer = PrimaryBlueDark,
    secondaryContainer = Color(0xFF213A75),
    onSecondaryContainer = PrimaryBlueDark,

    background = Color(0xFF0A0F1A),
    surface = Color(0xFF0A0F1A),
    // Distinct from the card tone: `surfaceVariant` resolves to
    // `onSurfaceVariant` and `surfaceContainer*` to `onSurface`, so sharing a
    // value means one of the two content colours is never used.
    surfaceVariant = Color(0xFF1A1D25),

    // All five share the card tone. They resolve to the same `onSurface`, so
    // colliding with each other is harmless - see the test.
    surfaceContainerLowest = Color(0xFF1D1F26),
    surfaceContainerLow = Color(0xFF1D1F26),
    surfaceContainer = Color(0xFF1D1F26),
    surfaceContainerHigh = Color(0xFF1D1F26),
    surfaceContainerHighest = Color(0xFF1D1F26),

    onBackground = Periwrinkle,
    onSurface = Periwrinkle,
    onSurfaceVariant = Periwrinkle.copy(alpha = 0.7f)
)

val AmoledAccentColorScheme = darkColorScheme(
    primary = PrimaryBlueDark,
    onPrimary = Color(0xFF00144B),
    primaryContainer = Color(0xFF1D1F26),
    onPrimaryContainer = PrimaryBlueDark,
    secondaryContainer = Color(0xFF213A75),
    onSecondaryContainer = PrimaryBlueDark,

    background = Color(0xFF000000),
    surface = Color(0xFF000000),
    // Keep background/surface pure black (that is the point of AMOLED) but
    // give the container roles a small tonal step. They were ALL 0xFF000000,
    // identical to background, which erased every surface elevation:
    // AlertDialog/ModalBottomSheet containers, ElevatedCard/Surface rows,
    // unselected chips, and — worst — LinearProgressIndicator tracks
    // (trackColor = surfaceVariant) rendered black-on-black, so a progress
    // bar read as "stuck". Dividers drawn with onSurface.copy(alpha = 0.1f)
    // over black landed at ~1.1:1 and vanished too.
    surfaceVariant = AmoledSurfaceVariant,

    surfaceContainerLowest = AmoledSurfaceLowest,
    surfaceContainerLow = AmoledSurfaceLow,
    surfaceContainer = AmoledSurface,
    surfaceContainerHigh = AmoledSurfaceHigh,
    surfaceContainerHighest = AmoledSurfaceHighest,

    onBackground = Periwrinkle,
    onSurface = Periwrinkle,
    onSurfaceVariant = Periwrinkle.copy(alpha = 0.7f)
)