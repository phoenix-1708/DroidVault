package com.phoenix.droidvault.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape

/**
 * The app's action button.
 *
 * **A filled button was the loudest thing in the app, and it was loud in
 * blue.** The old `Button` painted `primary` — a fully saturated periwinkle —
 * and its label in a near-black navy, and it sat on a near-black background:
 * a bright slab, at 8.6:1 against the page, for an action the user has to aim
 * at rather than read. The accent left blue (`Color.kt`), and this is the
 * other half of that change: **the action is a plate, not a slab.**
 *
 * The plate is `primaryContainer` — a deep tint of the accent — and the label
 * is the accent itself:
 *
 * | | the plate against the page | the label on the plate |
 * |---|---|---|
 * | the old filled button | 8.59:1 | 7.84:1 |
 * | this one | ~1.4:1 | 5.38:1 |
 *
 * So the button stops shouting and starts meaning: a quiet, unmistakably
 * coloured block, legible in its own colour rather than in a dark label on a
 * bright one.
 *
 * ## Why one component and not a colour argument
 *
 * A `Button` with `colors = ButtonDefaults.buttonColors(containerColor =
 * primaryContainer)` at sixty-seven call sites is a rule that will be broken
 * by the sixty-eighth, and broken silently: a filled button and a tonal one
 * look nothing alike, so nothing would report it. `ConventionGuardTest`
 * fails on a plain `Button(` outside this file, so the loud button cannot
 * come back without the build saying so.
 *
 * The `colors` parameter is still there for the two buttons that are NOT the
 * app's accent — the destructive confirmations, which wear the error role, so
 * the only saturated thing on screen is the thing that destroys.
 */
@Composable
fun DvButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.small,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    colors: ButtonColors = tonalButtonColors(),
    content: @Composable RowScope.() -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        contentPadding = contentPadding,
        colors = colors,
        content = content
    )
}

/**
 * The app's action colours: a plate of the accent, labelled in the accent.
 *
 * The disabled colours are Material's own (a ghost of the surface and a
 * dimmed label), so a disabled action reads as unavailable rather than as a
 * quieter version of itself.
 *
 * Public because a call site that chooses its colours per state still has to
 * be able to say "tonal" — the else branch of a destructive-or-not button
 * must not fall back to Material's FILLED default, which is the loud button
 * this component exists to replace.
 */
@Composable
fun tonalButtonColors(): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.primaryContainer,
    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
)
