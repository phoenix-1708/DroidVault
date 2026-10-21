package com.phoenix.droidvault.ui.theme

import android.app.WallpaperManager
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(30.dp),
    extraLarge = RoundedCornerShape(30.dp)
)

/**
 * Radius tokens for the two container levels Droid Vault actually uses.
 *
 * These were open-coded as `RoundedCornerShape(28.dp)` (23 call sites across
 * Automate / Settings / ConfigUi / MainScreen) and `RoundedCornerShape(14.dp)`
 * for the nested level. The values were already internally consistent, so this
 * centralises them WITHOUT changing a single pixel - it removes the duplicate
 * literals rather than imposing a new scale.
 */
object DvShapes {
    /** Outer/section container. */
    val card = RoundedCornerShape(28.dp)

    /** Container nested inside a card. */
    val nested = RoundedCornerShape(14.dp)
}

/** Shared layout rhythm for cards, sections, and touch-target grouping. */
object DvSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val section = 32.dp
}

/**
 * The app's theme.
 *
 * **The accent seed is the user's choice, and it wins.** A seed that is not
 * the app's own plum replaces the scheme entirely: the app's colours come
 * from the tonal palettes the seed generates, and the neutrals stay the
 * app's own warm greys.
 *
 * The order of precedence is: the user's accent, then the wallpaper (dynamic
 * colour), then the app's own plum.
 */
@Composable
fun DroidVaultTheme(
    themeMode: Int = 0,
    dynamicColor: Boolean = true,
    accent: AccentSeed = AccentSeed.Plum,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val context = LocalContext.current

    fun getLegacySeedColor(): Color? {
        try {
            val wallpaperManager = WallpaperManager.getInstance(context)
            val colors = wallpaperManager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            if (colors != null) {
                return Color(colors.primaryColor.toArgb())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    val isDark = when (themeMode) {
        1 -> false
        2, 3, 4 -> true
        else -> systemDark
    }
    val isAmoled = themeMode == 3 || themeMode == 4

    // A chosen accent replaces everything, including the wallpaper: the user
    // picked a colour on purpose.
    val userAccent = accent != AccentSeed.Plum

    val colorScheme = when {
        userAccent -> accentColorScheme(accent, isDark, isAmoled)

        themeMode == 0 -> {
            if (dynamicColor) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (systemDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(
                        context
                    )
                } else {
                    val seed = getLegacySeedColor()
                    seed?.toColorScheme(systemDark)
                        ?: accentColorScheme(AccentSeed.Plum, systemDark, false)
                }
            } else {
                accentColorScheme(AccentSeed.Plum, systemDark, false)
            }
        }

        themeMode == 1 -> {
            if (dynamicColor) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    dynamicLightColorScheme(context)
                } else {
                    val seed = getLegacySeedColor()
                    seed?.toColorScheme(false) ?: accentColorScheme(AccentSeed.Plum, false, false)
                }
            } else {
                accentColorScheme(AccentSeed.Plum, false, false)
            }
        }

        themeMode == 2 -> {
            if (dynamicColor) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    dynamicDarkColorScheme(context)
                } else {
                    val seed = getLegacySeedColor()
                    seed?.toColorScheme(true) ?: accentColorScheme(AccentSeed.Plum, true, false)
                }
            } else {
                accentColorScheme(AccentSeed.Plum, true, false)
            }
        }

        else -> {
            if (dynamicColor) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    dynamicDarkColorScheme(context).copy(
                        background = DvNeutrals.AmoledBackground,
                        surface = DvNeutrals.AmoledSurface,
                        surfaceVariant = DvNeutrals.AmoledSurfaceVariant,
                        surfaceContainerLowest = DvNeutrals.AmoledCardLowest,
                        surfaceContainerLow = DvNeutrals.AmoledCardLow,
                        surfaceContainer = DvNeutrals.AmoledCard,
                        surfaceContainerHigh = DvNeutrals.AmoledCardHigh,
                        surfaceContainerHighest = DvNeutrals.AmoledCardHighest
                    )
                } else {
                    val seed = getLegacySeedColor()
                    seed?.toColorScheme(isDark = true, isAmoled = true)
                        ?: accentColorScheme(AccentSeed.Plum, true, true)
                }
            } else {
                accentColorScheme(AccentSeed.Plum, true, true)
            }
        }
    }

    // The semantic roles follow the accent too, so the app's badges and its
    // chrome are one family — except the two that carry a meaning (danger
    // and failure), which never move.
    val rolePalette = remember(accent, isDark) {
        rolePaletteFor(accent, isDark, roleHues(accent.hue))
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = ExpressiveShapes,
    ) {
        // Semantic accents follow the RESOLVED scheme (light/dark/AMOLED and
        // Android 12+ dynamic colour all land here), not the themeMode int.
        CompositionLocalProvider(
            LocalDvPalette provides rolePalette,
            // Default the content colour ONCE, here, rather than trusting every
            // container to resolve its own.
            //
            // A `Surface` takes `contentColor = contentColorFor(color)`, and
            // `contentColorFor` answers only for the roles it knows. For a
            // `surfaceContainer*` colour it returns `Unspecified`, so the
            // container inherits whatever is above it — and what is above it,
            // on this app, is `primary`. Every colourless `Text` inside one of
            // the 29 container Surfaces was therefore rendering in the accent
            // blue rather than the reading colour: the Home shortcut titles read
            // as hyperlinks, and `Magisk 30.7.5` read as a link rather than a
            // value. Measured, not guessed: rgb(142,171,255) where `onSurface`
            // is rgb(228,229,240).
            //
            // It read correctly for years by accident — with dynamic colour ON,
            // the wallpaper's `primary` happened to be a pale near-white, so
            // `primary` and `onSurface` were indistinguishable on screen. That
            // is why the defect survived: it was invisible under exactly the
            // setting that shipped as the default. `AccentContrastTest` could
            // not see it either, because every role it checks is one the app
            // sets explicitly.
            LocalContentColor provides colorScheme.onSurface,
            content = content
        )
    }
}
