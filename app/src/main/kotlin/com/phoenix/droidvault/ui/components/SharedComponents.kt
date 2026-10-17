package com.phoenix.droidvault.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoenix.droidvault.models.AppInfo
import com.phoenix.droidvault.ui.theme.DvMotion
import com.phoenix.droidvault.ui.theme.DvRole
import com.phoenix.droidvault.ui.theme.dvAccent
import com.phoenix.droidvault.ui.theme.dvContainer
import coil.compose.AsyncImage
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun CircularCheckbox(
    checked: Boolean,
    onCheckedChange: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val containerColor by animateColorAsState(
        if (checked) MaterialTheme.colorScheme.primary else Color.Transparent,
        label = "checkbox_container"
    )
    val borderColor by animateColorAsState(
        if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(
            alpha = 0.5f
        ),
        label = "checkbox_border"
    )

    Box(
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(containerColor)
            .border(2.dp, borderColor, CircleShape)
            .clickable(enabled = onCheckedChange != null) { onCheckedChange?.invoke() },
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * One named action, in a row that WRAPS rather than scrolls.
 *
 * This used to be a 48 dp bare circle that revealed its name for three seconds when
 * tapped, inside a `LazyRow` that pushed the last controls off the right edge with
 * no hint they existed. On the Manage screen that left "All System Apps" and
 * "Auto Select" reachable only by swiping a row that looked complete — and
 * `uiautomator` on the reference device returns `contentDescription` for nothing
 * useful, so a collapsed chip was unidentifiable to a screen reader too.
 *
 * So the name is permanent, and the caller wraps the row. The wrap is not a
 * concession: `FlowChipGroup` in `ConfigUi.kt` already does exactly this for the
 * Automation tab's device chips, and this is the same control in the place that
 * had not caught up.
 *
 * The cost is honest and worth stating: six chips is two lines and seven is
 * three. That is the price of every control being named and reachable without a
 * gesture, and it buys back a row that used to lie about its own contents.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimatedFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    leadingIcon: ImageVector,
    /** Disables the chip. A permanently-lit chip the user cannot press is a
     *  dead control, so "nothing to do" must read as unavailable, not enabled. */
    enabled: Boolean = true,
    selectedContainerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    selectedContentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer
) {
    val containerColor =
        if (selected) selectedContainerColor else MaterialTheme.colorScheme.surfaceContainerHigh
    val contentColor =
        if (selected) selectedContentColor else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        selected = selected,
        enabled = enabled,
        onClick = onClick,
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
        modifier = Modifier.height(30.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 8.dp, end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = leadingIcon,
                // The text is always on screen, so the glyph is decoration and
                // naming it too would read the label twice.
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpressiveRefreshIndicator(
    state: PullToRefreshState,
    isRefreshing: Boolean,
    modifier: Modifier = Modifier
) {
    val containerColor = MaterialTheme.colorScheme.primaryContainer
    val iconColor = MaterialTheme.colorScheme.onPrimaryContainer

    // Only spin while actually refreshing. This composable is permanently
    // composed as the PullToRefreshBox indicator on the Migrate tab, so an
    // unconditional infiniteRepeatable kept invalidating a Canvas every frame
    // at display refresh for the whole session even when idle.
    val rotation by if (isRefreshing) {
        val transition = rememberInfiniteTransition(label = "refresh")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = DvMotion.rotate(),
                repeatMode = RepeatMode.Restart
            ),
            label = "rotation"
        )
    } else {
        remember { mutableStateOf(0f) }
    }

    Box(
        modifier = modifier
            .size(48.dp)
            .graphicsLayer {
                val scale = if (isRefreshing) 1f else state.distanceFraction.coerceIn(0f, 1f)
                scaleX = scale
                scaleY = scale
                alpha = scale
            }
            .background(containerColor, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(28.dp)) {
            val radius = size.minDimension / 2f
            val innerRadius = radius * 0.75f
            val points = 10
            val path = Path()

            for (i in 0 until points * 2) {
                val r = if (i % 2 == 0) radius else innerRadius
                val angle = (i * Math.PI / points).toFloat()
                val x = center.x + r * cos(angle)
                val y = center.y + r * sin(angle)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()

            rotate(if (isRefreshing) rotation else state.distanceFraction * 180f) {
                drawPath(path, iconColor)
            }
        }
    }
}

/**
 * The single icon-container treatment used across Droid Vault.
 *
 * Replaces the open-coded
 * `Box(size(48.dp).clip(CircleShape).background(primary.copy(alpha = 0.15f)))`
 * that appeared in thirteen places with identical (and, in light theme,
 * effectively invisible) colouring. Sizing rules live here so every badge has
 * the same icon-to-container ratio and the same corner language.
 *
 * [role] carries the semantic meaning; pass `null` only where the control has
 * no functional category (then it falls back to the scheme primary).
 */
@Composable
fun DvIconBadge(
    role: DvRole?,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    containerSize: Dp = 48.dp,
    iconSize: Dp = 24.dp,
    contentDescription: String? = null,
    shape: Shape = CircleShape
) {
    Box(
        modifier = modifier
            .size(containerSize)
            .clip(shape)
            .background(
                if (role != null) dvContainer(role)
                // Neutral fallback deliberately does NOT use
                // primary.copy(alpha = 0.15f): that is the exact treatment this
                // component replaced, and it measures ~1.05:1 on the light
                // surface, i.e. an invisible badge. surfaceContainerHigh is at
                // least a distinct elevation step from the card behind it.
                else MaterialTheme.colorScheme.surfaceContainerHigh
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (role != null) dvAccent(role)
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(iconSize)
        )
    }
}

@Composable
fun MenuCard(
    title: String,
    icon: ImageVector,
    description: String,
    role: DvRole? = null,
    onClick: () -> Unit
) {

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = DvMotion.Springs.settle(),
        label = "MenuCardBounce"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(MaterialTheme.shapes.large)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick
            ),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DvIconBadge(
                role = role,
                icon = icon,
                containerSize = 52.dp,
                iconSize = 28.dp,
                contentDescription = title
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun AppListItem(
    app: AppInfo,
    showBackupTime: Boolean = true,
    isMonochrome: Boolean = false,
    showSelectedComponents: Boolean = false,
    /**
     * Opens the per-app detail page. Null means "the row itself is the only
     * affordance", which is what the modes without per-app components use.
     *
     * The row body opens the page while the trailing checkbox keeps toggling
     * selection. Splitting them matters: one tap target for both meant picking
     * an app and picking its parts fought over the same gesture, and bulk
     * selection was impossible without opening a page per app.
     */
    onOpenDetail: (() -> Unit)? = null,
    onToggleSelect: (String) -> Unit
) {
    val containerColor by animateColorAsState(
        targetValue = if (app.isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        label = "item_color"
    )

    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(containerColor)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = {
                    if (onOpenDetail != null) onOpenDetail() else onToggleSelect(app.packageName)
                }
            )
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val iconModifier = Modifier
            .size(50.dp)
            .clip(CircleShape)
        // Two allocations per visible row per recomposition before, and never
        // referentially stable. Restore/Manage make most rows monochrome, so
        // this ran for a whole screenful every tick.
        val imageColorFilter = remember(isMonochrome) {
            if (isMonochrome) {
                ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
            } else {
                null
            }
        }

        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            if (app.iconPath != null) {
                AsyncImage(
                    model = app.iconPath,
                    contentDescription = "App Icon",
                    colorFilter = imageColorFilter,
                    modifier = iconModifier
                )
            } else {
                val letter = app.label.firstOrNull()?.uppercase() ?: "?"
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(Color.Transparent),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = letter,
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 22.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )

            if (showSelectedComponents && app.isSelected) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable(
                        interactionSource = null,
                        indication = null,
                        onClick = { onOpenDetail?.invoke() }
                    )
                ) {
                    val comps = app.activeComponents?.sorted().orEmpty()
                    // "…" rather than an empty string: a selected app with no
                    // parts yet is a real state (the user cleared them on the
                    // detail page) and a bare icon read as a rendering fault.
                    val summary = comps.joinToString(" • ") {
                        com.phoenix.droidvault.models.AppParts.shortLabel(it)
                    }.ifEmpty { "No parts selected" }

                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (comps.isEmpty()) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                    )
                }
            } else if (showBackupTime && app.backupTime.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = app.backupTime,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (app.backupTime == "No backup on device") {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (app.size.isNotEmpty()) {
                Text(
                    text = app.size,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            CircularCheckbox(
                checked = app.isSelected,
                onCheckedChange = { onToggleSelect(app.packageName) }
            )
        }
    }
}

@Composable
fun ShimmerAppListItem() {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val alpha by transition.animateFloat(initialValue = 0.2f, targetValue = 0.6f, animationSpec = infiniteRepeatable(animation = DvMotion.breathe(), repeatMode = RepeatMode.Reverse), label = "alpha")
    val shimmerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(MaterialTheme.shapes.small)
                .background(shimmerColor)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(16.dp)
                    .clip(CircleShape)
                    .background(shimmerColor)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.4f)
                    .height(12.dp)
                    .clip(CircleShape)
                    .background(shimmerColor)
            )
        }
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(shimmerColor)
        )
    }
}
