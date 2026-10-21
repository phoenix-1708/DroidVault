package com.phoenix.droidvault.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The Material palette — every hue family and every shade of it.
 *
 * The user asked for "a large set of all the available colours with all the
 * shades", and this is the canonical answer: the nineteen Material families,
 * each with its ten shades and (for the fourteen chromatic ones) its four
 * accent shades. Two hundred and fifty swatches, and every one of them is a
 * colour somebody has actually used.
 *
 * The shades are not just lighter and darker versions of each other: their
 * CHROMA varies too, which is what makes them worth choosing from. A pale
 * pink is a quiet seed and a deep pink is a loud one, and the app's accent
 * takes the seed's chroma — so picking the pale one gives a muted accent and
 * picking the deep one gives a vivid one.
 *
 * The app renders each swatch as the accent it would actually produce, so
 * what the user sees is what they get.
 */
data class MaterialFamily(
    val name: String,
    /** The shades, in order: 50, 100, 200 … 900, then A100, A200, A400, A700. */
    val shades: List<Color>,
    /** The names of the shades, in the same order. */
    val shadeNames: List<String>
)

/**
 * The thirteen tones a tonal palette has, and what they are called. */
val TONE_NAMES = listOf(0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 95, 99, 100)

/**
 * A hex colour, parsed here rather than with `android.graphics.Color`.
 *
 * The framework's parser is stubbed out in a unit test and throws, and this
 * table is read by one.
 */
private fun hex(value: String): Color {
    val v = value.removePrefix("#").toLong(16)
    return Color(((v and 0xFFFFFF0000000L) shr 32) or (v and 0xFFFFFFL))
}

private fun colours(hexes: List<String>) = hexes.map { hex(it) }

/** The nineteen families, in the order the Material palette lists them. */
val MaterialPalette: List<MaterialFamily> = listOf(
    MaterialFamily("red", colours(listOf("#FFEBEE", "#FFCDD2", "#EF9A9A", "#E57373", "#EF5350", "#F44336", "#E53935", "#D32F2F", "#C62828", "#B71C1C", "#FF8A80", "#FF5252", "#FF1744", "#D50000")), listOf("50", "100", "200", "300", "400", "500", "600", "700", "800", "900", "A100", "A200", "A400", "A700")),
    MaterialFamily("pink", colours(listOf("#FCE4EC", "#F8BBD0", "#F48FB1", "#F06292", "#EC407A", "#E91E63", "#D81B60", "#C2185B", "#AD1457", "#880E4F", "#FF80AB", "#FF4081", "#F50057", "#C51162")), listOf("50", "100", "200", "300", "400", "500", "600", "700", "800", "900", "A100", "A200", "A400", "A700")),
    MaterialFamily("purple", colours(listOf("#F3E5F5", "#E1BEE7", "#CE93D8", "#BA68C8", "#AB47BC", "#9C27B0", "#8E24AA", "#7B1FA2", "#6A1B9A", "#4A148C", "#EA80FC", "#E040FB", "#D500F9", "#AA00FF")), listOf("50", "100", "200", "300", "400", "500", "600", "700", "800", "900", "A100", "A200", "A400", "A700")),
    MaterialFamily("deep purple", colours(listOf("#EDE7F6", "#D1C4E9", "#B39DDB", "#9575CD", "#7E57C2", "#673AB7", "#5E35B1", "#512DA8", "#4527A0", "#311B92", "#B388FF", "#7C4DFF", "#651FFF", "#6200EA")), listOf("50", "100", "200", "300", "400", "500", "600", "700", "800", "900", "A100", "A200", "A400", "A700")),
    MaterialFamily("indigo", colours(listOf("#E8EAF6", "#C5CAE9", "#9FA8DA", "#7986CB", "#5C6BC0", "#3F51B5", "#3949AB", "#303F9F", "#283593", "#1A237E", "#8C9EFF", "#536DFE", "#3D5AFE", "#304FFE")), listOf("50", "100", "200", "300", "400", "500", "600", "700", "800", "900", "A100", "A200", "A400", "A700")),
    MaterialFamily("blue", colours(listOf("#E3F2FD", "#BBDEFB", "#90CAF9", "#64B5F6", "#42A5F5", "#2196F3", "#1E88E5", "#1976D2", "#1565C0", "#0D47A1", "#82B1FF", "#448AFF", "#2979FF", "#2962FF")), listOf("50", "100", "200", "300", "400", "500", "600", "700", "800", "900", "A100", "A200", "A400", "A700")),
    MaterialFamily("light blue", colours(listOf("#E1F5FE", "#B3E5FC", "#81D4FA", "#4FC3F7", "#29B6F6", "#03A9F4", "#039BE5", "#0288D1", "#0277BD", "#01579B", "#80D8FF", "#40C4FF", "#00B0FF", "#0091EA")), listOf("50", "100", "200", "300", "400", "500", "600", "700", "800", "900", "A100", "A200", "A400", "A700")),
    MaterialFamily("cyan", colours(listOf("#E0F7FA", "#B2EBF2", "#80DEEA", "#4DD0E1", "#26C6DA", "#00BCD4", "#00ACC1", "#0097A7", "#00838F", "#006064", "#84FFFF", "#18FFFF", "#00E5FF", "#00B8D4")), listOf("50", "100", "200", "300", "400", "500", "600", "700", "800", "900", "A100", "A200", "A400", "A700")),
    MaterialFamily("teal", colours(listOf("#E0F2F1", "#B2DFDB", "#80CBC4", "#4DB6AC", "#26A69A", "#009688", "#00897B", "#00796B", "#00695C", "#004D40", "#A7FFEB", "#64FFDA", "#1DE9B6", "#00BFA5")), listOf("50", "100", "200", "300", "400", "500", "600", "700", "800", "900", "A100", "A200", "A400", "A700")),
    MaterialFamily("green", colours(listOf("#E8F5E9", "#C8E6C9", "#A5D6A7", "#81C784", "#66BB6A", "#4CAF50", "#43A047", "#388E3C", "#2E7D32", "#1B5E20", "#B9F6CA", "#69F0AE", "#00E676", "#00C853")), listOf("50", "100", "200", "300", "400", "500", "600", "700", "800", "900", "A100", "A200", "A400", "A700")),
    MaterialFamily("light green", colours(listOf("#F1F8E9", "#DCEDC8", "#C5E1A5", "#AED581", "#9CCC65", "#8BC34A", "#7CB342", "#689F38", "#558B2F", "#33691E", "#CCFF90", "#B2FF59", "#76FF03", "#64DD17")), listOf("50", "100", "200", "300", "400", "500", "600", "700", "800", "900", "A100", "A200", "A400", "A700")),
    MaterialFamily("lime", colours(listOf("#F9FBE7", "#F0F4C3", "#E6EE9C", "#DCE775", "#D4E157", "#CDDC39", "#C0CA33", "#AFB42B", "#9E9D24", "#827717", "#F4FF81", "#EEFF41", "#C6FF00", "#AEEA00")), listOf("50", "100", "200", "300", "400", "500", "600", "700", "800", "900", "A100", "A200", "A400", "A700")),
    MaterialFamily("yellow", colours(listOf("#FFFDE7", "#FFF9C4", "#FFF59D", "#FFF176", "#FFEE58", "#FFEB3B", "#FDD835", "#FBC02D", "#F9A825", "#F57F17", "#FFFF8D", "#FFFF00", "#FFEA00", "#FFD600")), listOf("50", "100", "200", "300", "400", "500", "600", "700", "800", "900", "A100", "A200", "A400", "A700")),
    MaterialFamily("amber", colours(listOf("#FFF8E1", "#FFECB3", "#FFE082", "#FFD54F", "#FFCA28", "#FFC107", "#FFB300", "#FFA000", "#FF8F00", "#FF6F00", "#FFE57F", "#FFD740", "#FFC400", "#FFAB00")), listOf("50", "100", "200", "300", "400", "500", "600", "700", "800", "900", "A100", "A200", "A400", "A700")),
    MaterialFamily("orange", colours(listOf("#FFF3E0", "#FFE0B2", "#FFCC80", "#FFB74D", "#FFA726", "#FF9800", "#FB8C00", "#F57C00", "#EF6C00", "#E65100", "#FFD180", "#FFAB40", "#FF9100", "#FF6D00")), listOf("50", "100", "200", "300", "400", "500", "600", "700", "800", "900", "A100", "A200", "A400", "A700")),
    MaterialFamily("deep orange", colours(listOf("#FBE9E7", "#FFCCBC", "#FFAB91", "#FF8A65", "#FF7043", "#FF5722", "#F4511E", "#E64A19", "#D84315", "#BF360C")), listOf("50", "100", "200", "300", "400", "500", "600", "700", "800", "900")),
    MaterialFamily("brown", colours(listOf("#EFEBE9", "#D7CCC8", "#BCAAA4", "#A1887F", "#8D6E63", "#795548", "#6D4C41", "#5D4037", "#4E342E", "#3E2723")), listOf("50", "100", "200", "300", "400", "500", "600", "700", "800", "900")),
    MaterialFamily("grey", colours(listOf("#FAFAFA", "#F5F5F5", "#EEEEEE", "#E0E0E0", "#BDBDBD", "#9E9E9E", "#757575", "#616161", "#424242", "#212121")), listOf("50", "100", "200", "300", "400", "500", "600", "700", "800", "900")),
    MaterialFamily("blue grey", colours(listOf("#ECEFF1", "#CFD8DC", "#B0BEC5", "#90A4AE", "#78909C", "#607D8B", "#546E7A", "#455A64", "#37474F", "#263238")), listOf("50", "100", "200", "300", "400", "500", "600", "700", "800", "900")),
)
