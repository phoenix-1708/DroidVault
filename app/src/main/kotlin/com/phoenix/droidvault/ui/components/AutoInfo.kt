package com.phoenix.droidvault.ui.components

import com.phoenix.droidvault.ui.theme.DvRole
import com.phoenix.droidvault.ui.theme.DvShapes
import com.phoenix.droidvault.ui.theme.dvAccent
import com.phoenix.droidvault.ui.theme.dvContainer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The "i" information pattern for the Automation tab: explanations are
 * INVISIBLE by default and appear only while the "i" button is selected
 * (tap) or held (long-press selects the same way), keeping screens clean.
 *
 * [InfoButton] is the bare control; [InfoBlock] and [InfoRow] own the
 * show/hide state and render the explanation directly beneath their content,
 * so the reveal can use the full row width.
 */

/** The bare 30dp "i" control. Tap or long-press toggles [shown]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InfoButton(
    shown: Boolean,
    onToggle: () -> Unit,
    desc: String,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val active = shown || pressed
    // Inactive state used surfaceContainerHigh, which in every shipped scheme is
    // the SAME value as the card behind it - the "i" circle had no edge at all
    // and read as a floating glyph. It now sits in the Info container, which
    // AccentContrastTest pins at >= 1.15:1 against the surface, and the glyph
    // carries the matching accent.
    val bg by animateColorAsState(
        if (active) dvAccent(DvRole.Info) else dvContainer(DvRole.Info),
        label = "info_bg"
    )
    val tint by animateColorAsState(
        if (active) MaterialTheme.colorScheme.surfaceContainerLowest
        else dvAccent(DvRole.Info),
        label = "info_tint"
    )
    Surface(
        shape = CircleShape,
        color = bg,
        modifier = modifier
            .size(30.dp)
            .clip(CircleShape)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onToggle,
                onLongClick = onToggle
            )
            .semantics {
                contentDescription = "$desc. Tap or hold the info button to show the explanation."
            }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * Title block with an "i" button: the explanation replaces the old
 * always-visible caption and appears only while the "i" is selected/held.
 * Optional trailing slot for a control that shares the header row.
 */
@Composable
fun InfoBlock(
    title: String,
    info: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    titleStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleMedium,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    var shown by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = titleStyle, fontWeight = FontWeight.Bold)
                if (summary != null) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (trailing != null) {
                trailing()
                Spacer(modifier = Modifier.width(10.dp))
            }
            InfoButton(shown = shown, onToggle = { shown = !shown }, desc = title)
        }
        InfoBody(info, shown)
    }
}

/**
 * Setting-style row: title + optional value line on the left, "i" button on
 * the right. [isError]/[isPrimary] tint the value for states like "no
 * folders registered yet" or live counts.
 */
@Composable
fun InfoRow(
    title: String,
    info: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    isError: Boolean = false,
    isPrimary: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    var shown by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                if (value != null) {
                    Text(
                        value,
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            isError -> MaterialTheme.colorScheme.error
                            isPrimary -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
            if (trailing != null) {
                trailing()
                Spacer(modifier = Modifier.width(10.dp))
            }
            InfoButton(shown = shown, onToggle = { shown = !shown }, desc = title)
        }
        InfoBody(info, shown)
    }
}

/** The revealed explanation; width follows the row that embeds it. */
@Composable
private fun InfoBody(info: String, shown: Boolean) {
    AnimatedVisibility(
        visible = shown,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        Surface(
            shape = DvShapes.nested,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
        ) {
            Text(
                info,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
    }
}
