package com.phoenix.droidvault.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phoenix.droidvault.models.AppProgress
import com.phoenix.droidvault.models.LiveOp
import com.phoenix.droidvault.models.Parts
import com.phoenix.droidvault.models.PartState
import com.phoenix.droidvault.models.ProgressState
import com.phoenix.droidvault.models.RunPhase
import coil.compose.AsyncImage
import com.phoenix.droidvault.utils.AutomationRunner
import com.phoenix.droidvault.utils.ProgressTracker

/**
 * Live backup/restore progress screen (inspired by DataBackup's Processing
 * page): a hero ring segmented per part around the current app icon, an
 * expandable per-app card list, a slim determinate top bar, and bottom
 * Continue/Finish actions. Renders [ProgressTracker]'s state; works for both
 * the Migrate tab and the Automation tab since both feed the same bus.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessingScreen(
    onBack: () -> Unit,
    onFinished: () -> Unit,
) {
    val state by ProgressTracker.state.collectAsStateWithLifecycle()
    var showCancelDialog by remember { mutableStateOf(false) }
    var showBackDialog by remember { mutableStateOf(false) }

    val running = state is ProgressState.Running

    androidx.activity.compose.BackHandler(enabled = true) {
        if (running) showBackDialog = true else onBack()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        // Top app bar + slim progress bar (exactly like the reference).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { if (running) showBackDialog = true else onBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            // Key on a cheap derived title, NOT the whole state object: the
            // previous targetState=state re-ran this 250 ms crossfade on EVERY
            // progress tick (several per second) — the run screen's stutter.
            val s = state
            val headerTitle = when (s) {
                is ProgressState.Running -> when (s.phase) {
                    RunPhase.FINALIZING -> "Finalizing"
                    else -> if (s.op == LiveOp.RESTORE) "Restoring" else "Backing up"
                }
                is ProgressState.Done -> s.title
                is ProgressState.Failed -> s.title
                ProgressState.Idle -> ""
            }
            AnimatedContent(
                targetState = headerTitle,
                transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(250)) },
                label = "title",
                modifier = Modifier.weight(1f),
            ) { title ->
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        val topPct = when (val s = state) {
            is ProgressState.Running -> if (s.overallPct <= 0) null else s.overallPct / 100f
            is ProgressState.Done -> 1f
            is ProgressState.Failed -> null
            ProgressState.Idle -> null
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
        ) {
            if (topPct != null) {
                val animated by animateFloatAsState(topPct, tween(400), label = "topbar")
                LinearProgressIndicator(
                    progress = { animated },
                    modifier = Modifier.fillMaxSize(),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxSize(),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
        }

        when (val s = state) {
            // The 1 Hz ticker lives INSIDE RunningBody, its only consumer. It
            // used to sit here, so every second the entire screen recomposed —
            // including FinishedBody/FailedBody and the list scope — for a value
            // that only the running view reads.
            is ProgressState.Running -> RunningBody(s, Modifier.weight(1f))
            is ProgressState.Done -> FinishedBody(s, Modifier.weight(1f))
            is ProgressState.Failed -> FailedBody(s, Modifier.weight(1f))
            ProgressState.Idle -> Box(Modifier.weight(1f))
        }

        // Bottom action bar.
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                val isRunning = state is ProgressState.Running
                Button(
                    onClick = {
                        when {
                            isRunning -> showCancelDialog = true
                            else -> {
                                ProgressTracker.dismiss()
                                onFinished()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (isRunning) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                ) {
                    Text(if (isRunning) "Cancel operation" else "Finish")
                }
            }
        }
    }

    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = { Text("Cancel operation?") },
            text = { Text("Apps that already finished are kept. The current app will stop mid-way.") },
            confirmButton = {
                TextButton(onClick = {
                    showCancelDialog = false
                    AutomationRunner.requestCancel()
                    ProgressTracker.cancelRequested()
                }) { Text("Stop", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) { Text("Keep going") }
            },
        )
    }
    if (showBackDialog) {
        AlertDialog(
            onDismissRequest = { showBackDialog = false },
            title = { Text("Leave this screen?") },
            text = { Text("The operation keeps running in the background. You can come back from the notification.") },
            confirmButton = {
                TextButton(onClick = {
                    showBackDialog = false
                    onBack()
                }) { Text("Leave") }
            },
            dismissButton = {
                TextButton(onClick = { showBackDialog = false }) { Text("Stay") }
            },
        )
    }
}

@Composable
private fun RunningBody(s: ProgressState.Running, modifier: Modifier = Modifier) {
    // One-second ticker so elapsed/ETA stay live without per-event recomposition.
    // Scoped to this composable: it is the only reader of nowMs.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            nowMs = System.currentTimeMillis()
        }
    }
    Column(modifier) {
        // Recently finished apps (backend order preserved = completion order).
        // Excludes the app under the ring — the hero already represents it.
        // Memoised: this list was rebuilt on every tracker tick.
        val finishedApps = remember(s.apps, s.currentIndex) {
            s.apps.filterIndexed { i, a -> a.done && i != s.currentIndex }
        }
        HeroSection(
            op = s.op,
            phase = s.phase,
            subtitle = s.subtitle,
            app = s.currentApp,
            segments = s.currentApp?.parts?.size ?: 0,
            // During the pre-restore install pass the completed-app counter is
            // honestly 0 — reporting that verbatim is what looked frozen at
            // "0/63". Track the install progress for that phase and the
            // completed-app count for the data phase.
            counterText = when {
                s.totalCount <= 0 -> ""
                s.phase == RunPhase.PREPARING && s.preparedCount > 0 ->
                    "${s.preparedCount} of ${s.totalCount} ready"
                // "1/63" beside "App 2 of 63" reads as a contradiction: one
                // number counts FINISHED apps, the other counts POSITION. Said
                // in words, it is just true.
                else -> "${s.processedCount} of ${s.totalCount} done"
            },
            statsText = statsText(s, nowMs),
            finishedApps = finishedApps,
        )
        Spacer(Modifier.height(12.dp))
        AppListSection(
            apps = s.apps,
            currentIndex = s.currentIndex,
            running = true,
            op = s.op,
            modifier = Modifier.weight(1f),
        )
    }
}

/** "Elapsed 2m 14s • 1.2 GB done • ~4m left" — measured, never invented. */
private fun statsText(s: ProgressState.Running, nowMs: Long): String {
    val elapsedSec = ((nowMs - s.startedAtMs) / 1000).coerceAtLeast(0)
    if (elapsedSec < 3) return ""
    val elapsed = formatDuration(elapsedSec)
    // Weighted by the parts' own sizes, not "app size x mean of part percentages".
    // An app that is 13.6 GB in one part and a few MB in the rest used to add
    // 80% of 13.6 GB the moment the small parts finished, which is how the screen
    // came to claim "24.6 GB done" over a media copy that had written nothing.
    val totalKb = s.apps.sumOf { it.expectedKb }
    val doneKb = s.apps.sumOf { it.doneKb }
    val parts = mutableListOf(elapsed)
    // "38.8 GB of 43.2 GB", not "38.8 GB done". An incremental run SKIPS the
    // parts that are already current, and a skipped part counts as complete
    // bytes — so "done" claims the phone wrote 38 GB in the 28 seconds it was
    // open. The fraction is true either way; only the wording was lying.
    if (doneKb > 0 && totalKb > 0) parts.add("${formatKb(doneKb)} of ${formatKb(totalKb)}")
    if (totalKb > 0 && doneKb > 0 && s.overallPct in 1..99) {
        val etaSec = (elapsedSec * (totalKb - doneKb) / doneKb)
        // An estimate built from a frozen byte counter only ever grows — the
        // screenshots caught "~51s left" climbing to "~1m 9s left" over half a
        // minute of no progress. A number that moves the wrong way is worse than
        // no number, because it reads as "this is going to take a while" when the
        // run may in fact be nearly finished. Only shown when the remaining
        // fraction is small enough for a whole-run rate to mean anything.
        if (etaSec in 5..(6 * 3600) && doneKb * 4 >= totalKb) {
            parts.add("~${formatDuration(etaSec)} left")
        }
    }
    return parts.joinToString(" • ")
}

private fun formatDuration(sec: Long): String = when {
    sec >= 3600 -> "${sec / 3600}h ${(sec % 3600) / 60}m"
    sec >= 60 -> "${sec / 60}m ${sec % 60}s"
    else -> "${sec}s"
}

private fun formatKb(kb: Long): String = when {
    kb >= 1_048_576 -> String.format(java.util.Locale.US, "%.1f GB", kb / 1_048_576.0)
    kb >= 1024 -> String.format(java.util.Locale.US, "%.1f MB", kb / 1024.0)
    else -> "$kb KB"
}

@Composable
private fun FinishedBody(s: ProgressState.Done, modifier: Modifier = Modifier) {
    Column(modifier) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            val badge = MaterialTheme.colorScheme.primaryContainer
            val check = MaterialTheme.colorScheme.onPrimaryContainer
            if (s.failedCount > 0) {
                Box(
                    Modifier.size(96.dp).background(MaterialTheme.colorScheme.errorContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(52.dp),
                    )
                }
            } else {
                SuccessBadge(container = badge, checkColor = check)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                s.title,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                s.subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(12.dp))
        }
        AppListSection(
            apps = s.apps,
            currentIndex = -1,
            running = false,
            op = s.op,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun FailedBody(s: ProgressState.Failed, modifier: Modifier = Modifier) {
    Column(modifier) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            Box(
                Modifier
                    .size(96.dp)
                    .background(MaterialTheme.colorScheme.errorContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(52.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                s.title,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                s.message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(12.dp))
        }
        AppListSection(
            apps = s.apps,
            currentIndex = -1,
            running = false,
            op = s.op,
            modifier = Modifier.weight(1f),
        )
    }
}

/** "Android/media/com.whatsapp" → "A"; empty → "?" (never crashes on blank labels). */
private fun avatarLetter(label: String): String =
    label.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"

@Composable
private fun SuccessBadge(container: Color, checkColor: Color) {
    val infinite = rememberInfiniteTransition(label = "pop")
    val scale by infinite.animateFloat(
        initialValue = 0.92f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "popScale",
    )
    Box(
        Modifier
            .size((96 * scale).dp)
            .background(container, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Check,
            contentDescription = null,
            tint = checkColor,
            modifier = Modifier.size(52.dp),
        )
    }
}

@Composable
private fun HeroSection(
    op: LiveOp,
    phase: RunPhase,
    subtitle: String,
    app: AppProgress?,
    segments: Int,
    counterText: String,
    statsText: String = "",
    finishedApps: List<AppProgress> = emptyList(),
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Segmented ring: one segment per part; byte-fill inside the active
            // segment, full fill for completed ones.
            SegmentedRing(
                segments = segments.coerceAtLeast(1),
                partProgress = app?.parts ?: emptyList(),
                modifier = Modifier.size(168.dp),
            )
            when {
                app != null && app.iconPath != null -> AsyncImage(
                    model = app.iconPath,
                    contentDescription = app.label,
                    modifier = Modifier
                        .size(104.dp)
                        .clip(CircleShape),
                )
                app != null && app.packageName.startsWith("device:") -> Box(
                    Modifier
                        .size(104.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Folder,
                        contentDescription = app.label,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(44.dp),
                    )
                }
                app != null -> Box(
                    Modifier
                        .size(104.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        avatarLetter(app.label),
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                else -> Icon(
                    if (phase == RunPhase.FINALIZING) Icons.Default.Check else Icons.Default.Remove,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(64.dp),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        AnimatedContent(
            targetState = app?.label ?: if (phase == RunPhase.FINALIZING) "Wrapping up" else "Preparing",
            transitionSpec = {
                (fadeIn(tween(250)) + scaleIn(initialScale = 0.94f, animationSpec = tween(250))) togetherWith
                        (fadeOut(tween(150)) + scaleOut(targetScale = 0.98f, animationSpec = tween(150)))
            },
            label = "heroLabel",
        ) { label ->
            Text(
                label,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (counterText.isNotEmpty()) {
                Text(
                    counterText,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
        if (statsText.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                statsText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        // Live thumbnail strip of recently finished apps; fixed slot keeps the
        // hero from jumping when the first app completes.
        Box(Modifier.fillMaxWidth().height(46.dp), contentAlignment = Alignment.Center) {
            if (finishedApps.isNotEmpty()) {
                FinishedAppsStrip(apps = finishedApps)
            }
        }
    }
}

/** Small circular icons of finished apps, most recent first, with a check badge. */
@Composable
private fun FinishedAppsStrip(apps: List<AppProgress>) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(apps.asReversed(), key = { it.packageName }) { app ->
            Box(contentAlignment = Alignment.BottomEnd) {
                if (app.iconPath != null) {
                    AsyncImage(
                        model = app.iconPath,
                        contentDescription = app.label,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape),
                    )
                } else if (app.packageName.startsWith("device:")) {
                    Box(
                        Modifier
                            .size(34.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Folder,
                            contentDescription = app.label,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                } else {
                    Box(
                        Modifier
                            .size(34.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            avatarLetter(app.label),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // Check badge
                Box(
                    Modifier
                        .size(15.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(10.dp),
                    )
                }
            }
        }
    }
}

/** Alias so the ring reads part rows without exposing the model type name twice. */
private typealias AppProgressPartAlias = com.phoenix.droidvault.models.PartProgress

/** Ring of rounded segments: completed parts fully filled, the RUNNING part filled by its byte progress. */
@Composable
private fun SegmentedRing(
    segments: Int,
    partProgress: List<AppProgressPartAlias>,
    modifier: Modifier = Modifier,
) {
    val active = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val progressList = partProgress
    Canvas(modifier = modifier.padding(10.dp)) {
        val stroke = 10.dp.toPx()
        val diameter = size.minDimension - stroke
        val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        val segSweep = 360f / segments
        val gapDegrees = 6f
        val arcSweep = segSweep - gapDegrees
        for (i in 0 until segments) {
            val start = -90f + i * segSweep + gapDegrees / 2f
            drawArc(
                color = track,
                startAngle = start,
                sweepAngle = arcSweep,
                useCenter = false,
                topLeft = topLeft,
                size = androidx.compose.ui.geometry.Size(diameter, diameter),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            val p = progressList.getOrNull(i)
            val filled = when {
                p == null -> 0f
                p.state == PartState.DONE || p.state == PartState.SKIPPED -> 1f
                p.state == PartState.FAILED -> 1f
                p.state == PartState.RUNNING -> p.progress.coerceIn(0, 100) / 100f
                else -> 0f
            }
            if (filled > 0f) {
                drawArc(
                    color = active,
                    startAngle = start,
                    sweepAngle = arcSweep * filled,
                    useCenter = false,
                    topLeft = topLeft,
                    size = androidx.compose.ui.geometry.Size(diameter, diameter),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
    }
}

@Composable
private fun AppListSection(
    apps: List<AppProgress>,
    currentIndex: Int,
    running: Boolean,
    op: LiveOp,
    modifier: Modifier = Modifier,
) {
    var expandedPkg by rememberSaveable(running) { mutableStateOf<String?>(null) }
    // Auto-expand the running app; user can collapse/expand others freely.
    LaunchedEffect(currentIndex) {
        if (running && currentIndex >= 0) expandedPkg = apps.getOrNull(currentIndex)?.packageName
    }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(currentIndex) {
        if (running && currentIndex > 0) {
            runCatching { listState.animateScrollToItem(currentIndex) }
        }
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
        ) {
            items(apps, key = { it.packageName }) { app ->
                val expanded = expandedPkg == app.packageName
                val isCurrent = running && app.packageName == apps.getOrNull(currentIndex)?.packageName
                AppProgressCard(
                    app = app,
                    op = op,
                    expanded = expanded,
                    highlighted = isCurrent,
                    clickable = !running || !isCurrent,
                    onToggle = {
                        expandedPkg = if (expanded) null else app.packageName
                    },
                )
            }
        }
    }
}

@Composable
private fun AppProgressCard(
    app: AppProgress,
    op: LiveOp,
    expanded: Boolean,
    highlighted: Boolean,
    clickable: Boolean,
    onToggle: () -> Unit,
) {
    val summaryColor = when (app.summaryState) {
        PartState.DONE -> MaterialTheme.colorScheme.primary
        PartState.FAILED -> MaterialTheme.colorScheme.error
        PartState.RUNNING -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (highlighted) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                else Color.Transparent
            )
            .animateContentSize(tween(220))
            .clickable(enabled = clickable, onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (app.iconPath != null) {
                AsyncImage(
                    model = app.iconPath,
                    contentDescription = null,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape),
                )
            } else if (app.packageName.startsWith("device:")) {
                Box(
                    Modifier
                        .size(36.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                Box(
                    Modifier
                        .size(36.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        avatarLetter(app.label),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    app.label,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = buildList {
                    if (app.parts.isNotEmpty()) add("${app.doneCount}/${com.phoenix.droidvault.models.AppParts.count(app.parts.size, "part")}}")
                    if (app.sizeKb > 0) add(formatKb(app.sizeKb))
                }
                if (meta.isNotEmpty()) {
                    Text(
                        meta.joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            SummaryIcon(state = app.summaryState, tint = summaryColor)
            Spacer(Modifier.width(8.dp))
            if (clickable && app.parts.isNotEmpty()) {
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AnimatedVisibility(visible = expanded && app.parts.isNotEmpty()) {
                    Column(Modifier.padding(start = 48.dp, top = 6.dp)) {
                        app.parts.forEach { part ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                            ) {
                                PartDot(state = part.state)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    Parts.displayName(op, part.key),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (part.state == PartState.RUNNING && part.progress in 1..99) {
                                    Text(
                                        "${part.progress}%",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.tertiary,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                if (part.reason.isNotBlank() &&
                                    (part.state == PartState.SKIPPED || part.state == PartState.FAILED)
                                ) {
                                    Text(
                                        part.reason,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.alpha(0.9f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
    }
}

@Composable
private fun SummaryIcon(state: PartState, tint: Color) {
    when (state) {
        PartState.DONE -> Box(
            Modifier.size(24.dp).background(tint.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Check, contentDescription = "Done", tint = tint, modifier = Modifier.size(16.dp))
        }
        PartState.FAILED -> Icon(
            Icons.Default.Close, contentDescription = "Failed", tint = tint, modifier = Modifier.size(22.dp)
        )
        PartState.RUNNING -> Box(
            Modifier.size(22.dp).background(tint.copy(alpha = 0.2f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            val trans = rememberInfiniteTransition(label = "spin")
            val angle by trans.animateFloat(
                0f, 360f,
                infiniteRepeatable(tween(900, easing = LinearEasing)),
                label = "angle",
            )
            Canvas(Modifier.size(16.dp)) {
                drawArc(
                    color = tint,
                    startAngle = angle,
                    sweepAngle = 260f,
                    useCenter = false,
                    style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
        else -> Icon(
            Icons.Default.Remove, contentDescription = "Pending", tint = tint, modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun PartDot(state: PartState) {
    val color = when (state) {
        PartState.DONE -> MaterialTheme.colorScheme.primary
        PartState.FAILED -> MaterialTheme.colorScheme.error
        PartState.RUNNING -> MaterialTheme.colorScheme.tertiary
        PartState.SKIPPED -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        PartState.PENDING -> MaterialTheme.colorScheme.surfaceVariant
    }
    Box(
        Modifier
            .size(12.dp)
            .background(color, CircleShape),
    )
}
