package com.phoenix.droidvault.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * The scheme for a wallpaper colour — the legacy dynamic-colour path, for
 * the devices before Android 12.
 *
 * It used to build a whole scheme by hand, with the WALLPAPER's hue tinting
 * every surface: a blue wallpaper gave a blue-black page with a blue card,
 * which is the cast the user asked to be rid of. The neutrals are the app's
 * own now, and only the accent follows the wallpaper.
 */
fun Color.toColorScheme(isDark: Boolean, isAmoled: Boolean = false): ColorScheme =
    accentColorScheme(wallpaperSeed(toArgb()), isDark, isAmoled)

/**
 * Droid Vault's neutrals — the surfaces and the reading colours.
 *
 * **They do not follow the accent.** The surfaces are warm greys and they
 * stay warm greys: a surface tinted with the accent is the same complaint
 * the user made about a blue-tinted surface, in a different colour. A chosen
 * accent tints the ACCENT — the buttons, the links, the live status, the
 * switches — and nothing else.
 *
 * The numbers here are the app's own, and they are what the colour tests
 * measure against: the dark card is `#1D1C1B`, the light card `#F1F0EE`, and
 * the reading colour is a warm off-white in the dark themes and a warm near
 * black in the light one.
 */
object DvNeutrals {
    // ---- dark
    val DarkBackground = Color(0xFF0D0D0C)
    val DarkSurface = DarkBackground
    val DarkSurfaceVariant = Color(0xFF201F1D)
    val DarkCard = Color(0xFF1D1C1B)
    val DarkCardLowest = Color(0xFF131312)
    val DarkOnSurface = Color(0xFFE8E6E3)

    // ---- amoled
    val AmoledBackground = Color(0xFF000000)
    val AmoledSurface = AmoledBackground
    val AmoledSurfaceVariant = Color(0xFF1C1C1C)
    val AmoledCard = Color(0xFF141414)
    val AmoledCardLow = Color(0xFF0C0C0C)
    val AmoledCardLowest = Color(0xFF070707)
    val AmoledCardHigh = Color(0xFF232323)
    val AmoledCardHighest = Color(0xFF2A2A2A)
    val AmoledOnSurface = DarkOnSurface

    // ---- light
    val LightBackground = Color(0xFFF8F8F7)
    val LightSurface = LightBackground
    val LightSurfaceVariant = Color(0xFFEBE9E5)
    val LightCard = Color(0xFFF1F0EE)
    val LightCardLowest = Color(0xFFFFFFFF)
    val LightOnSurface = Color(0xFF191815)
}

/**
 * The roles that carry a MEANING rather than a hue.
 *
 * The restore violet is the app's "put data back" accent, and the error rose
 * is its "this went wrong" accent. Neither follows a user's chosen colour:
 * a "Delete" that is teal is a lie, and the destructive confirmations wear
 * the error role.
 */
object DvFixedRoles {
    val DarkRestoreViolet = Color(0xFFB79FDF)
    val DarkRestoreVioletInk = Color(0xFF2A1F3D)
    val DarkRestoreVioletContainer = Color(0xFF3A304B)
    val LightRestoreViolet = Color(0xFF50367D)
    val LightRestoreVioletContainer = Color(0xFFE4DEED)
    val LightRestoreVioletInk = Color(0xFF4A2F72)
    val DarkError = Color(0xFFDD8E88)
    val DarkErrorInk = Color(0xFF3A1512)
    val DarkErrorContainer = Color(0xFF4B3230)
    val LightError = Color(0xFF742A25)
    val LightErrorInk = Color(0xFF5C201C)
    val LightErrorContainer = Color(0xFFF0DCDB)
}
