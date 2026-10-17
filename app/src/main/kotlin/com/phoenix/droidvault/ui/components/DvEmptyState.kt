package com.phoenix.droidvault.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.phoenix.droidvault.ui.theme.DvRole
import com.phoenix.droidvault.ui.theme.dvAccent
import com.phoenix.droidvault.ui.theme.dvContainer

/**
 * The "there is nothing here" block, in one shape.
 *
 * **Two copies of this existed and were identical apart from three arguments.**
 * `ConfigsEmptyState` and `HistoryEmptyState` in `AutomateScreen.kt` were the
 * same `Column`, the same 96dp `Box`, the same 16dp and 4dp gaps, the same
 * `titleMedium` over `bodyMedium`, differing only in the icon and the tint. Two
 * private helpers in one file, one screen apart, that would drift.
 *
 * The tint difference was the more interesting half. One used
 * `dvContainer(role)` / `dvAccent(role)` — the app's own semantic accents — and
 * the other reached for `primaryContainer.copy(alpha = 0.45f)` and
 * `colorScheme.primary`. So the second one painted an empty state with a colour
 * that means nothing, which is the same mistake as a container inheriting its
 * content colour (invariant 61): a raw tint instead of a role.
 *
 * ## What this deliberately does NOT cover
 *
 * Plenty of "nothing here" strings are not this shape and must not be swept into
 * it. Three live next door in `LabelDetailScreen` and are genuinely three
 * different situations:
 *
 *  - "No apps **assigned** yet." — a real state, with an "Assign apps" button.
 *  - "No apps **loaded** yet." — *no data*, with a "Reload apps" button. That is
 *    an absence of a read, not an empty list (invariant 58).
 *  - "No apps **match**. Adjust search or filters." — filtered out.
 *
 * One component that rendered all three the same way would flatten exactly the
 * distinction the app is built on. They stay as they are.
 *
 * Nor is this for a by-design skip or an honest failure: "No matching rule" and
 * "Not enough free space at …" are reasons, and a reason is not an empty state.
 */
@Composable
fun DvEmptyState(
    icon: ImageVector,
    role: DvRole,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(96.dp)
                .background(dvContainer(role), CircleShape)
        ) {
            Icon(
                icon,
                // The title below already names this in words. A screen reader
                // reading "image, image, No runs recorded yet" is worse than one
                // that reads the sentence once.
                contentDescription = null,
                tint = dvAccent(role),
                modifier = Modifier.size(44.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        if (action != null) {
            Spacer(modifier = Modifier.height(20.dp))
            action()
        }
    }
}