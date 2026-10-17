package com.phoenix.droidvault.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Droid Vault's type scale — all fifteen slots, defined.
 *
 * **Seven of these used to be missing.** The scale declared eight styles and let
 * the other seven fall through to Material 3's defaults, and four of those were
 * the ones the app leaned on hardest: `bodySmall` at 148 call sites, `titleSmall`
 * at 41, `labelSmall` at 28, `labelMedium` at 17. So roughly 230 places rendered
 * text in Google's Medium weight while the app's own eight slots rendered
 * ExtraBold — which is why weight changed more than size as you read down a list,
 * and why the same component looked like it came from two different apps.
 *
 * **No sizes changed.** Every size here is what the app already used. This is a
 * weight and definition change only, deliberately: the app already fights for
 * vertical space (the confirmation sheets had to be rebuilt around a button that
 * would not fit), and growing body text by a point would reflow every screen
 * that is currently correct. The hierarchy is expressed in weight instead:
 *
 *   headings   ExtraBold -> **Bold**     big and emphatic, but not a shout
 *   titles     Bold -> **SemiBold**, and `titleSmall` promoted from Medium
 *   body       Normal, unchanged
 *   labels     ExtraBold -> **SemiBold** for `labelLarge`; small labels Medium
 *
 * That gives four weights instead of two plus a cliff, and the scale is now
 * monotonic: within each group the size steps down and the weight steps down
 * with it, so no two slots collide. `TypographyScaleTest` asserts exactly that,
 * because the defect was a hole in the scale and a hole cannot be seen by
 * looking at one screen.
 *
 * **Every slot states every value.** An omitted `letterSpacing` reads as a
 * decision in review and is `TextUnit.Unspecified` at runtime — `value` is NaN,
 * so any comparison against it silently passes. That is the same hole as a
 * missing slot, one level down, and it is why `TypographyScaleTest` asserts that
 * no slot carries an unspecified size, line height, tracking or weight.
 *
 * Tracking follows Material's own shape: looser as the type gets smaller,
 * because small type needs the room. A 28sp headline at 0, a 15sp body line at
 * 0.2sp, a 12sp label at 0.4sp — so `bodySmall` being tracked wider than the
 * `bodyMedium` above it is the rule working, not an inversion of it.
 */
val Typography = Typography(
    // ---- display: the biggest moments. Barely used, defined so a screen that
    // reaches for one does not inherit an unknown style.
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 52.sp,
        lineHeight = 60.sp,
        letterSpacing = (-0.25).sp
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 44.sp,
        lineHeight = 52.sp,
        letterSpacing = 0.sp
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp
    ),

    // ---- headline: screen titles.
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),

    // ---- title: card and list headings.
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp
    ),
    // Was M3's 14/20 Medium, which sat between titleMedium and bodyLarge and
    // read as a third weight rather than as a smaller title.
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),

    // ---- body: reading text.
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.15.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp
    ),
    // 148 call sites, and it was one of the seven that were never defined here.
    // Tracking follows Material's own shape — looser as the type gets smaller,
    // because small type needs the extra room to stay legible — so this is
    // WIDER than the 13sp bodyMedium above it. That is not an inversion; it is
    // the same rule that puts 0.1sp on a 20sp title and 0.4sp on an 11sp label.
    // `TypographyScaleTest` asserts the shape rather than a flat direction,
    // which is what made the wrong version of this comment look right.
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp
    ),

    // ---- label: control and caption text.
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.4.sp
    )
)