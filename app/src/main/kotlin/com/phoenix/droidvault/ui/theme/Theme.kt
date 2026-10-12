package com.phoenix.droidvault.ui.theme

import android.app.WallpaperManager
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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

@Composable
fun DroidVaultTheme(
    themeMode: Int = 0,
    dynamicColor: Boolean = true,
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

    val colorScheme = when (themeMode) {
        0 -> {
            if (dynamicColor) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (systemDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(
                        context
                    )
                } else {
                    val seed = getLegacySeedColor()
                    seed?.toColorScheme(systemDark)
                        ?: if (systemDark) DarkColorScheme else LightColorScheme
                }
            } else {
                if (systemDark) DarkColorScheme else LightColorScheme
            }
        }

        1 -> {
            if (dynamicColor) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    dynamicLightColorScheme(context)
                } else {
                    val seed = getLegacySeedColor()
                    seed?.toColorScheme(false) ?: LightColorScheme
                }
            } else {
                LightColorScheme
            }
        }

        2 -> {
            if (dynamicColor) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    dynamicDarkColorScheme(context)
                } else {
                    val seed = getLegacySeedColor()
                    seed?.toColorScheme(true) ?: DarkColorScheme
                }
            } else {
                DarkColorScheme
            }
        }

        3, 4 -> {
            if (dynamicColor) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    dynamicDarkColorScheme(context).copy(
                        background = Color(0xFF000000),
                        surface = Color(0xFF000000),
                        surfaceVariant = AmoledSurfaceVariant,
                        surfaceContainerLowest = AmoledSurfaceLowest,
                        surfaceContainerLow = AmoledSurfaceLow,
                        surfaceContainer = AmoledSurface,
                        surfaceContainerHigh = AmoledSurfaceHigh,
                        surfaceContainerHighest = AmoledSurfaceHighest
                    )
                } else {
                    val seed = getLegacySeedColor()
                    seed?.toColorScheme(isDark = true, isAmoled = true) ?: AmoledAccentColorScheme
                }
            } else {
                AmoledAccentColorScheme
            }
        }

        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = ExpressiveShapes,
    ) {
        // Semantic accents follow the RESOLVED scheme (light/dark/AMOLED and
        // Android 12+ dynamic colour all land here), not the themeMode int.
        CompositionLocalProvider(
            LocalDvPalette provides dvPaletteFor(colorScheme),
            content = content
        )
    }
}
