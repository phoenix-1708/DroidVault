package com.phoenix.droidvault.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.phoenix.droidvault.ui.components.DvButton
import com.phoenix.droidvault.ui.theme.AccentContrast
import com.phoenix.droidvault.ui.theme.AccentSeed
import com.phoenix.droidvault.ui.theme.MaterialFamily
import com.phoenix.droidvault.ui.theme.MaterialPalette
import com.phoenix.droidvault.ui.theme.accentColor
import com.phoenix.droidvault.ui.theme.hex
import com.phoenix.droidvault.ui.theme.accentColorScheme
import com.phoenix.droidvault.ui.theme.plateColor
import com.phoenix.droidvault.ui.theme.selectionColor

/**
 * The user's own colour.
 *
 * **Nineteen families and every shade of each** — the canonical Material
 * palette, two hundred and fifty swatches — plus a hex field for anything
 * outside it. What each swatch shows is the accent the app would actually
 * produce from it, so what the user picks is what they get.
 *
 * The app renders the seed through the tonal-palette engine, so the
 * swatch's own lightness does not survive into the theme: the seed's HUE and
 * CHROMA do, and its tone is the app's own. That is why the shades are worth
 * choosing from — their chroma varies, and a pale pick gives a quiet accent
 * while a deep one gives a vivid accent.
 */
@Composable
fun AccentPickerScreen(
    viewModel: com.phoenix.droidvault.MainViewModel,
    modifier: Modifier = Modifier
) {
    val accent by viewModel.accent.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val dynamicColor by viewModel.dynamicColor.collectAsStateWithLifecycle()
    // The theme the picker draws in: the same resolution the app's theme
    // uses, so the swatches show the colours the user will actually get.
    val isDark = when (themeMode) {
        1 -> false
        2, 3, 4 -> true
        else -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    val isAmoled = themeMode == 3 || themeMode == 4

    fun pick(argb: Int) {
        viewModel.setAccent(argb)
        // A chosen colour WINS over the wallpaper: the user picked it on
        // purpose.
        if (dynamicColor) viewModel.setDynamicColor(false)
    }

    // The family whose shades are on show. It follows the accent's own hue,
    // so the screen opens on the colour that is actually in use.
    val seedHue = accent.hue
    val familyHues = remember { MaterialPalette.map { family -> AccentSeed(family.shades[5].toArgbInt()).hue } }
    var familyIndex by remember(seedHue) {
        mutableStateOf(
            familyHues.indices.minByOrNull { hueDistance(seedHue, familyHues[it]) } ?: 0
        )
    }
    var hexText by remember(accent.argb) { mutableStateOf(accent.hex()) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        SectionLabel("Colour family")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MaterialPalette.forEachIndexed { index, family ->
                val swatchSeed = AccentSeed(family.shades[5].toArgbInt())
                Swatch(
                    color = swatchSeed.accentColor(isDark),
                    selected = index == familyIndex,
                    label = family.name,
                    onClick = { familyIndex = index }
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        val family: MaterialFamily = MaterialPalette[familyIndex]
        // The label is built OUTSIDE the template: a template that closes
        // twice renders the second `}` to the user (§2.67), and the guard
        // that catches it is in the build.
        val familyLabel = family.name.replaceFirstChar { it.uppercase() }
        SectionLabel(familyLabel + " — every shade")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            family.shades.forEachIndexed { index, shade ->
                val seed = AccentSeed(shade.toArgbInt())
                Swatch(
                    color = seed.accentColor(isDark),
                    selected = seed.argb == accent.argb,
                    label = "${family.name} ${family.shadeNames.getOrElse(index) { "" }}",
                    onClick = {
                        hexText = seed.hex()
                        pick(seed.argb)
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        SectionLabel("Contrast")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccentContrast.entries.forEach { step ->
                FilterChip(
                    selected = accent.contrast == step,
                    onClick = { viewModel.setAccentContrast(step) },
                    label = {
                        Text(
                            step.name.lowercase().replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        SectionLabel("A colour of your own")
        OutlinedTextField(
            value = hexText,
            onValueChange = { text ->
                hexText = text
                val parsed = parseHex(text)
                if (parsed != null) pick(parsed)
            },
            singleLine = true,
            label = { Text("Hex colour") },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(20.dp))

        SectionLabel("Preview")
        AccentPreview(accent = accent, isDark = isDark, isAmoled = isAmoled)

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Reset to Droid Vault's plum",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = { viewModel.resetAccent() }) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Reset")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

/**
 * The theme the chosen colour produces, in miniature.
 *
 * A button, a link and a live status — the three things the accent is
 * actually for — so the user can judge the colour where it will be used and
 * not on a swatch.
 */
@Composable
private fun AccentPreview(accent: AccentSeed, isDark: Boolean, isAmoled: Boolean) {
    val scheme = accentColorScheme(accent, isDark, isAmoled)
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = scheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DvButton(onClick = {}) {
                    Text("Run backup")
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(scheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "2",
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.onPrimaryContainer
                    )
                }
                Text(
                    "2 of 8 done",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.primary
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                "The plate a button is painted on",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(accent.plateColor(isDark))
                )
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(accent.selectionColor(isDark))
                )
                Text(
                    "plate · selection",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

/**
 * One colour, as a button.
 *
 * 40 dp of colour inside a 48 dp target: the app's rule is that a control
 * is at least 48 dp, and a swatch is a control.
 */
@Composable
private fun Swatch(
    color: Color,
    selected: Boolean,
    label: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(color)
                .then(
                    if (selected) {
                        Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.Black.copy(alpha = 0.65f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** A colour's ARGB, from a Compose colour. */
private fun Color.toArgbInt(): Int =
    (alpha * 255).toInt() shl 24 or
        ((red * 255).toInt() shl 16) or
        ((green * 255).toInt() shl 8) or
        (blue * 255).toInt()

/** Parse a hex colour, or null if it is not one. */
private fun parseHex(text: String): Int? {
    val v = text.trim().removePrefix("#")
    if (v.length != 6 && v.length != 8) return null
    val parsed = v.toLongOrNull(16) ?: return null
    return if (v.length == 6) (0xFF000000L or parsed).toInt() else parsed.toInt()
}

/** The distance between two hues, in degrees. */
private fun hueDistance(a: Double, b: Double): Double {
    val d = kotlin.math.abs(a - b) % 360.0
    return minOf(d, 360.0 - d)
}
