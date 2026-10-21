package com.phoenix.droidvault.utils

import androidx.compose.ui.graphics.Color
import kotlin.math.absoluteValue


/**
 * The colour of a generated label avatar.
 *
 * Twelve hues, and none of them blue: the old set carried five (indigo, sky
 * blue, two cyans and a teal) and the app's accent left blue in the same
 * change, so a row of avatars would have been the last blue left in it. The
 * set is also softer than the stock Material 200 palette it came from — these
 * are the same hues at a lower chroma, so an avatar sits with the card
 * instead of on top of it.
 */
fun getAvatarColor(name: String): Color {
    val colors = listOf(
        Color(0xFFC98A80), Color(0xFFC98AA4), Color(0xFFAE8AC0),
        Color(0xFF8A8AC0), Color(0xFF8AA6C0), Color(0xFF8ABAC0),
        Color(0xFF8AC0B4), Color(0xFFA8C08A), Color(0xFFC0B48A),
        Color(0xFFC0A88A), Color(0xFFC08A94), Color(0xFFC08A80)
    )
    return colors[name.hashCode().absoluteValue % colors.size]
}