package com.phoenix.droidvault.ui.screens
import android.widget.Toast
import com.phoenix.droidvault.ui.safeToast

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.net.toUri
import com.phoenix.droidvault.MainViewModel
import com.phoenix.droidvault.AutomationViewModel
import com.phoenix.droidvault.AutoMode
import com.phoenix.droidvault.R
import com.phoenix.droidvault.TAB_HOME
import com.phoenix.droidvault.models.AppInfo
import com.phoenix.droidvault.models.AppState
import com.phoenix.droidvault.models.FlashAction
import com.phoenix.droidvault.models.MigratorMode
import com.phoenix.droidvault.ui.components.AppInstallerDialog
import com.phoenix.droidvault.ui.components.BatchInstallerDialog
import com.phoenix.droidvault.ui.theme.DvMotion
import com.phoenix.droidvault.ui.theme.DvRole
import com.phoenix.droidvault.ui.theme.DvShapes
import com.phoenix.droidvault.ui.theme.dvAccent
import com.phoenix.droidvault.ui.theme.dvContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
fun MainScreen(viewModel: MainViewModel) {
    // Held here because MainScreen is the only composable that sees BOTH ends of
    // the shortcut editor: the pencil that toggles it is in AppScaffold's app
    // bar, and the grid it rearranges is inside DashboardScreen.
    //
    // `rememberSaveable`, so leaving the tab and coming back does not silently
    // drop the user into a rearranged grid they had already committed to.
    var homeEditMode by rememberSaveable { mutableStateOf(false) }
    val appState by viewModel.shellUiState.collectAsStateWithLifecycle()
    val appList by viewModel.appList.collectAsStateWithLifecycle()
    val flashActions by viewModel.flashActions.collectAsStateWithLifecycle()
    val isFirstRun by viewModel.isFirstRun.collectAsStateWithLifecycle()
    val selectedTab by viewModel.currentTab.collectAsStateWithLifecycle()
    val autoViewModel: AutomationViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    // Project to the two fields actually read below (mode, detailReturn).
    // Collecting the whole uiState made this entire body — predictive-back
    // layer, animatables, processing overlay — recompose on every ~10 Hz
    // runStep/runProgress tick for the whole duration of a backup. That is the
    // same "Auto tab feels laggy" bug AppScaffold documents fixing; this screen
    // never got the treatment.
    val autoFull by autoViewModel.uiState.collectAsStateWithLifecycle()
    val autoMode by remember { derivedStateOf { autoFull.mode } }
    // rememberSaveable (not remember): a system theme change recreates the
    // Activity, and this state lives OUTSIDE any ViewModel (it is pure UI
    // navigation). With plain remember, toggling system dark mode while in
    // Settings silently dumped the user back on a main tab. The saver keeps
    // that location (and the About layer) across recreation and process death.
    var showSettings by rememberSaveable { mutableStateOf(false) }
    // About is a SIBLING of Settings, not a nested screen: the shared app bar
    // already provides the title + back arrow, so About owns only the body and
    // back returns to Settings instead of leaving Settings entirely.
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var showExitConfirmation by rememberSaveable { mutableStateOf(false) }
    var dashboardShortcutPending by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    // Root managers can approve a request while Droid Vault is backgrounded.
    // The original probe may already have completed with a non-root shell, so
    // re-probe after returning from the root manager instead of requiring a
    // force-close/relaunch.
    DisposableEffect(lifecycleOwner, appState.hasRoot) {
        if (appState.hasRoot) return@DisposableEffect onDispose { }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                lifecycleOwner.lifecycleScope.launch {
                    delay(350)
                    val current = viewModel.uiState.value
                    if (!current.hasRoot && !current.isFetchingApps) viewModel.checkRootAccess()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // SMS role handoff kills our process (platform revokes role perms). Close the
    // task first so the kill lands invisibly instead of looking like a crash.


    // Post-onboarding restore notice: on a fresh install the automation store
    // is recovered silently from the vault folder's automation-state.json
    // (uninstall / clean-flash recovery). Tell the user it happened — once —
    // with a jump to the Auto tab. Stays until dismissed (a kill before
    // dismissal re-offers; a dismissal is remembered).
    // Observed, not read once. The store publishes the notice the moment it
    // recovers, and recovery finishes asynchronously (measured on device: ~1.2s
    // after launch, while this screen composes at ~0.4s). The previous
    // `LaunchedEffect(Unit)` read happened first, saw nothing, and never looked
    // again — which is why the popup appeared "randomly", or not at all.
    val restoreHint by com.phoenix.droidvault.utils.AutomationStore
        .restoreHint.collectAsStateWithLifecycle()
    // Seed from prefs in case recovery already finished before this composed
    // (a warm start, or a screen rebuilt after the store published).
    LaunchedEffect(Unit) {
        com.phoenix.droidvault.utils.AutomationStore.peekRestoreHint(context)
    }
    restoreHint?.let { hint ->
        AlertDialog(
            onDismissRequest = {
                // clearRestoreHint publishes null on the flow, which is what
                // closes this dialog. Assigning the collected val is not allowed.
                com.phoenix.droidvault.utils.AutomationStore.clearRestoreHint(context)
            },
            title = { Text("Automation data restored") },
            text = {
                Text(
                    buildString {
                        fun plural(n: Int, one: String) = "$n $one" + if (n == 1) "" else "s"
                        append("Restored from your backup folder: ")
                        append(
                            listOfNotNull(
                                plural(hint.labels, "label").takeIf { hint.labels > 0 },
                                // Assignments are the apps-to-labels mapping — the part a
                                // user relies on to know a backup will contain anything.
                                // They were restored and counted, but never mentioned.
                                plural(hint.assignments, "app assignment").takeIf { hint.assignments > 0 },
                                plural(hint.configs, "configuration").takeIf { hint.configs > 0 },
                                plural(hint.schedules, "schedule").takeIf { hint.schedules > 0 }
                            ).joinToString(", ").ifEmpty { "your saved setup" }
                        )
                        append(".\n\n")
                        // Say what is true. Recovery deliberately disarms schedules when
                        // the master switch was off, so "everything is back the way it
                        // was" could promise unattended backups that will never run.
                        append(
                            if (hint.armed) "Automation is switched on, so schedules will run as set."
                            else "Automation is switched off, so nothing will run on its own until you turn it on."
                        )
                    }
                )
            },
            confirmButton = {
                Button(onClick = {
                    com.phoenix.droidvault.utils.AutomationStore.clearRestoreHint(context)
                    viewModel.setTab(4)
                }) { Text("Open Auto") }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    com.phoenix.droidvault.utils.AutomationStore.clearRestoreHint(context)
                }) { Text("Dismiss") }
            },
            shape = DvShapes.card
        )
    }

    // Keep the app-level Back handling above the root/onboarding early returns
    // so Back behaves identically on every top-level app screen.
    //
    // Two distinct destinations, in this order:
    //   1. the per-app component page, if it is open (a layer over a Migrate
    //      submenu, not a mode of its own);
    //   2. the top-level tab -> exit confirmation.
    //
    // A top-level tab OTHER than Home first returns to Home and only raises the
    // confirmation on the following press. Previously the first Back from any
    // tab popped the exit dialog, so leaving the app required confirming a quit
    // the user had not asked for.
    //
    // The `isRunning` clause is deliberate: isBackEnabled below is the exact
    // complement EXCEPT while a run is in flight, and when neither handler is
    // enabled the platform default finishes the activity with no
    // confirmation at all. Reachable for the Migrate submenus that never claim
    // ProgressTracker (Debloat, Systemize, Manage), which also have no
    // Processing overlay to consume the press.
    val componentEditorOpen by viewModel.componentEditorPackage.collectAsStateWithLifecycle()
    val isAtTopLevel = !appState.hasRoot || isFirstRun || (
        !showSettings && !showAbout && componentEditorOpen == null &&
            (appState.migratorMode == MigratorMode.MENU && appState.flashWizardStep == 0 &&
                (selectedTab != 4 || autoMode == AutoMode.MENU) || appState.isRunning)
        )
    BackHandler(enabled = componentEditorOpen != null) {
        viewModel.closeComponentEditor()
    }
    BackHandler(enabled = isAtTopLevel) {
        if (selectedTab != TAB_HOME) {
            // Leave any leftover submenu state behind on the way home, or the
            // next visit to that tab reopens the page the user just abandoned.
            viewModel.goHome()
        } else {
            showExitConfirmation = true
        }
    }
    if (showExitConfirmation) {
        AlertDialog(
            onDismissRequest = { showExitConfirmation = false },
            title = { Text("Exit Droid Vault?") },
            text = { Text("Do you want to close the app?") },
            confirmButton = {
                Button(onClick = {
                    showExitConfirmation = false
                    (context as? android.app.Activity)?.finish()
                }) { Text("Exit") }
            },
            dismissButton = {
                // "Stay" returns to the landing page first. A confirmation can
                // be raised from any top-level tab, and leaving the user parked
                // on Flash or Auto after they declined to quit is jarring — the
                // dashboard is where they can see what to do next.
                TextButton(onClick = {
                    showExitConfirmation = false
                    viewModel.goHome()
                }) { Text("Stay") }
            },
            shape = DvShapes.card
        )
    }

    if (appState.showAppInstaller && appState.batchInstallApps.isNotEmpty()) {
        if (appState.batchInstallApps.size == 1 && !appState.isRunning && appState.totalInstallTimeSeconds == 0L) {
            AppInstallerDialog(
                app = appState.batchInstallApps[0],
                onDismiss = { viewModel.closeAppInstaller { (context as? android.app.Activity)?.finish() } },
                onInstall = { viewModel.executeBatchInstall() }
            )
        } else {
            BatchInstallerDialog(
                apps = appState.batchInstallApps,
                isRunning = appState.isRunning,
                isAnalyzing = appState.isAnalyzingApps,
                currentStep = appState.currentStep,
                totalTime = appState.totalInstallTimeSeconds,
                onInstall = { viewModel.executeBatchInstall() },
                onCancel = { viewModel.closeAppInstaller { (context as? android.app.Activity)?.finish() } },
                onToggleSelect = { viewModel.toggleAppInstallSelection(it) }
            )
        }
    }

    if (!appState.hasRoot) {
        NoRootScreen(
            isChecking = appState.isFetchingApps,
            rootState = appState.rootState,
            onRetry = { viewModel.checkRootAccess() }
        )
        return
    }

    if (isFirstRun) {
        OnboardingWizard(viewModel)
        return
    }

    // Auto mode is scoped to the Auto tab: stray non-MENU state (leak,
    // restore edge) must never wedge back navigation on other tabs.
    // The component page is included explicitly: while it is open the Migrate mode
    // is non-MENU anyway, and without this clause a predictive back would pop
    // the whole submenu (resetting the mode) instead of just the page.
    val isBackEnabled = !appState.isRunning && (componentEditorOpen != null ||
        appState.migratorMode != MigratorMode.MENU || showSettings || showAbout ||
            appState.flashWizardStep > 0 || (selectedTab == 4 && autoMode != AutoMode.MENU))
    // While a run holds the screen, Back is intentionally inert (the exit
    // confirmation above owns the press) so a run cannot be abandoned halfway
    // by an accidental gesture.
    BackHandler(enabled = appState.isRunning) { }

    // All tab entry points (rail, bottom bar, dashboard shortcuts, and pager
    // swipes) go through the same cleanup path. Leaving a submenu must not
    // leave that area's hidden navigation state waiting to reappear later.
    val selectTab: (Int) -> Unit = { index ->
        val destination = index.coerceIn(0, 4)
        viewModel.setTab(destination)
        // The component page belongs to a Migrate submenu. Tabbing away from it
        // must close it, or it survives as an overlay on an unrelated tab.
        if (destination != 2) viewModel.closeComponentEditor()
        if (destination != 2 && appState.migratorMode != MigratorMode.MENU) {
            viewModel.setMigratorMode(MigratorMode.MENU)
        }
        if (destination != 4 && autoMode != AutoMode.MENU) {
            autoViewModel.setMode(AutoMode.MENU)
        }
    }

    // Dashboard shortcuts have a short task flow: once the selected backup,
    // restore, or manage action finishes successfully, return to the overview.
    // Actions entered from the Backups tab retain their normal in-place result.
    LaunchedEffect(viewModel) {
        viewModel.appOperationCompletion.collect { succeeded ->
            if (dashboardShortcutPending) {
                dashboardShortcutPending = false
                // Manage does NOT return to the overview. Reported on device:
                // deleting a backup from the dashboard shortcut closed Manage and
                // jumped to Home, and the trash card holding the just-deleted
                // backups was on Manage — so "Review" appeared to be missing
                // until the user went back in and reopened it. The destination
                // of a deletion is the screen showing how to undo it.
                val staysPut = viewModel.uiState.value.migratorMode == MigratorMode.MANAGE
                if (succeeded && !staysPut) {
                    viewModel.setMigratorMode(MigratorMode.MENU)
                    selectTab(0)
                }
            }
        }
    }

    val backProgress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var isPopping by remember { mutableStateOf(false) }

    val triggerBackNavigation = {
        if (!isPopping) {
            isPopping = true
            scope.launch {
                // Auto submenus: navigate FIRST, no trailing slide. The system
                // gesture already animated; re-sliding the same page then
                // switching late is exactly the reported "same page ~1s" bug.
                // requestBack (not modeBack): editors with unsaved changes get
                // the discard confirm instead of a silent drop — the app-bar
                // arrow and predictive back share the editor's guard now.
                if (componentEditorOpen != null) {
                    // Close only the page. The Migrate mode underneath stays put
                    // so Back from the page returns to the app list, not to the
                    // Migrate menu.
                    viewModel.closeComponentEditor()
                    isPopping = false
                } else if (!showSettings && !showAbout && appState.flashWizardStep == 0 &&
                    autoMode != AutoMode.MENU && selectedTab == 4
                ) {
                    autoViewModel.requestBack()
                    isPopping = false
                } else {
                    backProgress.animateTo(1f, animationSpec = DvMotion.settle(DvMotion.quick))

                    if (showAbout) showAbout = false
                    else if (showSettings) showSettings = false
                    else if (appState.flashWizardStep > 0) viewModel.flashWizardStepBack()
                    else viewModel.setMigratorMode(MigratorMode.MENU)

                    isPopping = false
                }
                backProgress.snapTo(0f)
            }
        }
    }

    PredictiveBackHandler(enabled = isBackEnabled) { progress ->
        try {
            progress.collect { backEvent ->
                backProgress.snapTo(backEvent.progress)
            }
            triggerBackNavigation()
        } catch (_: CancellationException) {
            scope.launch { backProgress.animateTo(0f, animationSpec = DvMotion.settle(DvMotion.quick)) }
        }
    }

    val screenWidthPx =
        androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width.toFloat()
    Box(modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (backProgress.value > 0f) {
                        translationX = backProgress.value * screenWidthPx
                        val cornerRadius = 20.dp * backProgress.value
                        shape = RoundedCornerShape(cornerRadius)
                        clip = true
                        shadowElevation = 24f * backProgress.value
                    }
                }
                .background(MaterialTheme.colorScheme.surface)
        ) {
            AppScaffold(
                appState = appState,
                appList = appList,
                flashActions = flashActions,
                showSettings = showSettings,
                showAbout = showAbout,
                selectedTab = selectedTab,
                viewModel = viewModel,
                onTabSelect = selectTab,
                onSettingsToggle = { showSettings = it },
                onOpenAbout = { showAbout = true },
                onBackClick = { triggerBackNavigation() },
                onDashboardShortcut = { dashboardShortcutPending = true },
                // One flag for both ends of the shortcut editor. The pencil that
                // toggles it is in AppScaffold's app bar; the grid it rearranges
                // is inside DashboardScreen. Held HERE because that is the only
                // composable that sees both — AppScaffold takes it as a
                // parameter, and a parameter is what stopped a local of the same
                // name from shadowing the real value.
                homeEditMode = homeEditMode,
                onToggleHomeEdit = { homeEditMode = !homeEditMode }
            )
        }

        LiveProcessingOverlay()
    }
}

private data class ProgressOverlayPhase(val name: String, val interactive: Boolean)

private fun progressOverlayPhase(
    progress: com.phoenix.droidvault.models.ProgressState
): ProgressOverlayPhase = when (progress) {
    is com.phoenix.droidvault.models.ProgressState.Running -> ProgressOverlayPhase("R", progress.interactive)
    is com.phoenix.droidvault.models.ProgressState.Done -> ProgressOverlayPhase("D", progress.interactive)
    is com.phoenix.droidvault.models.ProgressState.Failed -> ProgressOverlayPhase("F", progress.interactive)
    com.phoenix.droidvault.models.ProgressState.Idle -> ProgressOverlayPhase("I", true)
}

/**
 * Observes only run phase changes. The full progress stream is consumed by
 * ProcessingScreen, so its frequent byte/step updates never recompose the
 * navigation shell or the pager underneath it.
 */
@Composable
private fun LiveProcessingOverlay() {
    val phaseFlow = remember {
        com.phoenix.droidvault.utils.ProgressTracker.state
            .map(::progressOverlayPhase)
            .distinctUntilChanged()
    }
    val phase by phaseFlow.collectAsStateWithLifecycle(
        initialValue = progressOverlayPhase(com.phoenix.droidvault.utils.ProgressTracker.state.value)
    )
    val showRequests by ProcessingScreenController.showRequests.collectAsStateWithLifecycle()
    var showProcessing by remember { mutableStateOf(false) }
    var lastPhase by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(phase) {
        val show = when {
            lastPhase == null -> false
            phase.name == "R" && lastPhase != "R" && phase.interactive -> true
            lastPhase == "R" && (phase.name == "D" || phase.name == "F") && phase.interactive -> true
            else -> false
        }
        if (show) showProcessing = true
        lastPhase = phase.name
    }
    LaunchedEffect(showRequests) {
        if (showRequests > 0 && phase.name != "I") showProcessing = true
    }

    if (showProcessing && phase.name != "I") {
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(DvMotion.settle()) + scaleIn(initialScale = 0.96f, animationSpec = DvMotion.settle()),
            exit = fadeOut(DvMotion.settle()) + scaleOut(targetScale = 0.98f, animationSpec = DvMotion.settle()),
            modifier = Modifier.fillMaxSize(),
        ) {
            ProcessingScreen(
                onBack = { showProcessing = false },
                onFinished = { showProcessing = false },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
    appState: AppState,
    appList: List<AppInfo>,
    flashActions: List<FlashAction>,
    showSettings: Boolean,
    showAbout: Boolean,
    selectedTab: Int,
    viewModel: MainViewModel,
    onTabSelect: (Int) -> Unit,
    onSettingsToggle: (Boolean) -> Unit,
    onOpenAbout: () -> Unit = {},
    onBackClick: () -> Unit,
    onDashboardShortcut: () -> Unit = {},
    // Home's shortcut edit mode. Held here rather than inside DashboardScreen
    // because the control that toggles it is the app bar's, and a state two
    // composables apart cannot be owned by neither.
    homeEditMode: Boolean = false,
    onToggleHomeEdit: () -> Unit = {},
) {
    val autoViewModel: AutomationViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    // Scaffold chrome only consumes run-state-free fields of the Automation
    // state. Full collection + derivedStateOf dedupe: readers of autoMode
    // recompose only when a field they can see changes — not on every
    // runProgress/runStep tick (the classic "Auto tab feels laggy").
    //
    // Project each field separately rather than `autoFull.copy(runStep = "",
    // runProgress = 100)`: the copy allocated a whole ~40-field object on
    // every tick, and masking runProgress to 100 is a value that means
    // "complete" everywhere else in the app, so any future reader silently got
    // a wrong answer with no compiler signal.
    val autoFull by autoViewModel.uiState.collectAsStateWithLifecycle()
    val globalRunOwner by com.phoenix.droidvault.utils.RunCoordinator.ownerState.collectAsStateWithLifecycle()
    val autoIsRunning by remember { derivedStateOf { autoFull.isRunning } }
    val mainIsRunning by remember { derivedStateOf { appState.isRunning } }
    // An export holds the run gate now (§2.40), and an export does NOT set
    // `isRunning` on this screen's state — it drives its own sheet. Without the
    // two flags below, taking the gate made the banner appear DURING the user's
    // own export: "Droid Vault is busy — export:transfer", while they were the
    // one making it busy. The gate is for excluding OTHER work, not for telling
    // the person who started this one that they are busy.
    val transferRunning by viewModel.isExporting.collectAsStateWithLifecycle()
    val importRunning by viewModel.isImporting.collectAsStateWithLifecycle()
    val operationRunningElsewhere = globalRunOwner != null &&
        !mainIsRunning && !autoIsRunning && !transferRunning && !importRunning
    val autoMode by remember { derivedStateOf { autoFull.mode } }
    val autoAssignments by remember { derivedStateOf { autoFull.assignments } }
    val autoLabels by remember { derivedStateOf { autoFull.labels } }
    // Tab role drives the icon/label accent, so each destination has a stable
    // colour identity everywhere it appears (bar, headers, empty states).
    data class TabItem(val label: String, val icon: ImageVector, val role: DvRole)
    val tabs = listOf(
        TabItem("Home", Icons.Default.Home, DvRole.Migrate),
        TabItem("Flash", Icons.Default.FlashOn, DvRole.Flash),
        // "Migrate", not "Backups": the tab also restores, manages and transfers, so a
        // label naming only one direction was wrong on arrival. This matches the
        // vocabulary the rest of the codebase already uses (MigratorMode,
        // DvRole.Migrate) and the upstream project's name for this destination.
        TabItem("Migrate", Icons.Default.CloudSync, DvRole.Migrate),
        TabItem("Tools", Icons.Default.Build, DvRole.Tools),
        TabItem("Auto", Icons.Default.PlayArrow, DvRole.Auto)
    )

    val dynamicTitle = when {
        showAbout -> "About"
        showSettings -> "Settings"
        appState.flashWizardStep > 0 -> "Auto Flash Wizard"
        appState.migratorMode != MigratorMode.MENU -> {
            when (appState.migratorMode) {
                MigratorMode.BACKUP_APPS -> "Backup Apps"
                MigratorMode.RESTORE_APPS -> "Restore Apps"
                MigratorMode.MANAGE -> "Manage Backups"
                MigratorMode.DEBLOAT -> "Debloat / Restore Apps"
                MigratorMode.SYSTEMIZE -> "Systemize User Apps"
                MigratorMode.IMPORT_TRANSFERS -> "Import"
            }
        }
        // Auto submenu: the content screens carry their own headings, so the
        // app bar labels the AREA ("Automation"), hides the gear, and shows
        // the single back arrow. Previously it kept "Droid Vault" + gear
        // while ALSO showing a back arrow — the double-arrow chrome.
        selectedTab == 4 && autoMode != AutoMode.MENU -> "Automation"
        else -> "Droid Vault"
    }

    val isHome = dynamicTitle == "Droid Vault"
    // Material 3's compact/expanded breakpoint is 600dp. On expanded windows
    // a rail preserves the vertical workspace; compact phones keep the tuned
    // bottom bar (including its font-scale-aware labels).
    val isExpandedWindow = LocalConfiguration.current.screenWidthDp >= 600
    val showPrimaryNavigation = !showSettings && appState.flashWizardStep == 0 &&
        appState.migratorMode == MigratorMode.MENU &&
        (selectedTab != 4 || autoMode == AutoMode.MENU)
    val isBackEnabled =
        !appState.isRunning && (appState.migratorMode != MigratorMode.MENU || showSettings || showAbout || appState.flashWizardStep > 0 || (selectedTab == 4 && autoMode != AutoMode.MENU))

    // The nav bar is an OVERLAY, not a Scaffold bottomBar, so nothing reserves
    // its space automatically. Measured where the bar is drawn and read here, so
    // every page reserves it exactly once instead of each screen guessing a
    // hard-coded clearance — and guessing wrong, which is how the last card of a
    // page slid under the bar and Migrate's "N Selected / Backup Apps" footer
    // ended up underneath it.
    var navBarHeight by remember { mutableStateOf(0.dp) }
    val navDensity = LocalDensity.current

    val pagerState = rememberPagerState(initialPage = selectedTab, pageCount = { 5 })

    LaunchedEffect(pagerState.settledPage) {
        if (pagerState.settledPage != selectedTab) {
            onTabSelect(pagerState.settledPage)
        }
    }

    LaunchedEffect(selectedTab) {
        val from = pagerState.currentPage
        if (from != selectedTab) {
            // Animate ONLY an adjacent step. A HorizontalPager animates a
            // multi-page jump by scrolling THROUGH every page in between, so
            // Back -> Home swept past Backups and FLASH — the user saw a tab
            // they never asked to visit mid-transition. Multi-page jumps are
            // programmatic (nav bar, Back, Home shortcuts), never swipes, so a
            // cut is right there while a swipe still animates.
            if (kotlin.math.abs(from - selectedTab) == 1) {
                pagerState.animateScrollToPage(
                    selectedTab,
                    animationSpec = DvMotion.decelerate()
                )
            } else {
                pagerState.scrollToPage(selectedTab)
            }
        }
    }

    // The pulse is only ever read while a run is in flight, but an
    // infiniteRepeatable keeps the Recomposer requesting frames at display
    // refresh for the WHOLE app lifetime even when idle — permanent battery
    // and frame-clock cost. Created only while the animation is visible;
    // alpha is then the steady "running" value rather than 0.2.
    val logoAlpha by if (appState.isRunning) {
        val pulse = rememberInfiniteTransition(label = "logoPulse")
        pulse.animateFloat(
            initialValue = 0.2f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = DvMotion.breathe(),
                repeatMode = RepeatMode.Reverse
            ),
            label = "logoAlpha"
        )
    } else {
        remember { mutableStateOf(1f) }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp),
                shadowElevation = 0.dp,
                tonalElevation = 0.dp,
                modifier = Modifier.zIndex(1f)
            ) {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isHome) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_home),
                                    contentDescription = "Logo",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .size(32.dp)
                                        .graphicsLayer {
                                            alpha = if (appState.isRunning) logoAlpha else 1f
                                        }
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                            }
                            Text(
                                text = dynamicTitle,
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    },
                    navigationIcon = {
                        if (isBackEnabled) {
                            IconButton(
                                onClick = onBackClick,
                                modifier = Modifier.padding(start = 8.dp)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    "Back",
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }
                    },
                    actions = {
                        if (isHome) {
                            // The shortcut pencil lives HERE, not in the column.
                            //
                            // As a floating right-aligned icon in the page it sat in
                            // a 48dp touch target holding one 20dp glyph, so the space
                            // above it was the column's 20dp gap PLUS half a touch
                            // target and the space below was another half — two voids
                            // around one control, in the middle of the screen, where
                            // it read as a layout hole rather than as an affordance.
                            // The app bar is where Material puts edit actions, and the
                            // Home bar had one control and a wide empty middle.
                            IconButton(onClick = onToggleHomeEdit) {
                                Icon(
                                    if (homeEditMode) Icons.Default.Close else Icons.Default.Edit,
                                    contentDescription = if (homeEditMode)
                                        "Finish editing shortcuts" else "Edit shortcuts",
                                    tint = if (homeEditMode) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            IconButton(
                                onClick = { onSettingsToggle(true) },
                                modifier = Modifier.padding(end = 8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Settings,
                                    contentDescription = "Settings",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        } else {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_home),
                                contentDescription = "Logo",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(end = 16.dp)
                                    .size(32.dp)
                                    .graphicsLayer {
                                        alpha = if (appState.isRunning) logoAlpha else 1f
                                    }
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent
                    )
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier
            .padding(
                start = if (isExpandedWindow && showPrimaryNavigation) 88.dp else 0.dp,
                top = innerPadding.calculateTopPadding() +
                    if (operationRunningElsewhere) 60.dp else 0.dp
            )
            .fillMaxSize()) {

            AnimatedContent(
                targetState = showSettings,
                transitionSpec = {
                    if (initialState && !targetState) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else if (!initialState && targetState) {
                        slideInHorizontally(
                            DvMotion.settle(DvMotion.quick)
                        ) { it } togetherWith fadeOut(DvMotion.settle(DvMotion.quick))
                    } else {
                        fadeIn(DvMotion.settle(DvMotion.quick)) togetherWith fadeOut(DvMotion.settle(DvMotion.quick))
                    }
                },
                label = "AppContentTransition",
                modifier = Modifier.fillMaxSize()
            ) { isSettingsOpen ->
                if (isSettingsOpen) {
                    // Settings <-> About inside the settings branch: the outer
                    // slide still owns Settings vs. the app itself.
                    AnimatedContent(
                        targetState = showAbout,
                        transitionSpec = {
                            if (initialState && !targetState) {
                                // About -> Settings, always via back. The
                                // predictive-back layer has ALREADY animated
                                // this exact move and is the page the user is
                                // looking at when the real layer snaps back
                                // into place, so the commit must be instant.
                                // Cross-fading here replayed About on top of
                                // the destination for ~200 ms after the
                                // animation ended ("About flashes, then
                                // Settings").
                                EnterTransition.None togetherWith ExitTransition.None
                            } else {
                                // Settings -> About is a forward navigation
                                // with no gesture behind it: cross-fade.
                                fadeIn(DvMotion.settle()) togetherWith fadeOut(DvMotion.settle(DvMotion.quick))
                            }
                        },
                        label = "SettingsSubPage",
                        modifier = Modifier.fillMaxSize()
                    ) { aboutOpen ->
                        if (aboutOpen) {
                            val ctx = LocalContext.current
                            // shellUiState, not uiState: About reads exactly
                            // one boolean, and the raw flow made it recompose on
                            // every progress tick of a background run.
                            val aboutRoot by remember(viewModel) {
                                viewModel.shellUiState
                            }.collectAsStateWithLifecycle()
                            AboutScreen(context = ctx, hasRoot = aboutRoot.hasRoot)
                        } else {
                            SettingsTab(
                                context = LocalContext.current,
                                viewModel = viewModel,
                                onOpenAbout = onOpenAbout
                            )
                        }
                    }
                } else {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        userScrollEnabled = !appState.isRunning && appState.migratorMode == MigratorMode.MENU &&
                            (selectedTab != 4 || autoMode == AutoMode.MENU)
                    ) { page ->
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            // Bound line length and card width on large displays;
                            // each feature screen still receives the full phone
                            // width when the window is compact.
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .widthIn(max = 1100.dp)
                                    // Reserve the overlay nav bar's height for
                                    // EVERY page, once. Applied on the rail
                                    // layout too, where the bar is a left rail:
                                    // it still occupies the bottom-left corner's
                                    // visual weight and the gesture inset, and a
                                    // uniform inset keeps the pages from jumping
                                    // when the window crosses 600dp.
                                    .padding(
                                        bottom = if (showPrimaryNavigation) {
                                            navBarHeight
                                        } else {
                                            0.dp
                                        }
                                    )
                            ) {
                                when (page) {
                                    0 -> DashboardScreen(
                                        viewModel = viewModel,
                                        appState = appState,
                                        onOpenTab = onTabSelect,
                                        editMode = homeEditMode,
                                        onOpenMigrator = { mode ->
                                            onDashboardShortcut()
                                            viewModel.setMigratorMode(mode)
                                            onTabSelect(2)
                                        },
                                        onOpenAutomation = { mode ->
                                            onDashboardShortcut()
                                            // Leave the Auto submenu in a known
                                            // state before switching, so the
                                            // previous run's deep screen does
                                            // not appear under a shortcut whose
                                            // name says otherwise.
                                            autoViewModel.setMode(mode)
                                            onTabSelect(4)
                                        },
                                        onOpenDeviceData = { backup ->
                                            onDashboardShortcut()
                                            // The device-data dialogs live in
                                            // the Migrate tab's menu, so the
                                            // intent is handed over and opened
                                            // there once the tab is showing.
                                            viewModel.requestDeviceDialog(backup)
                                            onTabSelect(2)
                                        }
                                    )
                                    1 -> FlashTab(appState, flashActions, LocalContext.current, viewModel)
                                    2 -> MigratorTab(
                                        appList,
                                        viewModel,
                                        labelMap = autoAssignments,
                                        labelNames = remember(autoLabels) {
                                            autoLabels.associate { it.id to it.name }
                                        }
                                    )
                                    3 -> ToolsTab(appState, appList, viewModel)
                                    else -> AutomateTab(appState, appList, viewModel, autoViewModel, null)
                                }
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = showPrimaryNavigation,
                enter = EnterTransition.None,
                exit = slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = DvMotion.decelerate(DvMotion.emphasised)
                ) + fadeOut(DvMotion.settle(DvMotion.emphasised)),
                modifier = Modifier.align(
                    if (isExpandedWindow) Alignment.CenterStart else Alignment.BottomCenter
                )
            ) {

                if (isExpandedWindow) {
                    NavigationRail(
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(start = 8.dp, top = 12.dp, bottom = 12.dp)
                            .clip(RoundedCornerShape(28.dp)),
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ) {
                        Spacer(modifier = Modifier.height(24.dp))
                        tabs.forEachIndexed { index, tab ->
                            val selected = selectedTab == index
                            NavigationRailItem(
                                selected = selected,
                                onClick = { onTabSelect(index) },
                                icon = {
                                    Icon(
                                        imageVector = tab.icon,
                                        contentDescription = null,
                                        tint = if (selected) dvAccent(tab.role)
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                label = { Text(tab.label) },
                                alwaysShowLabel = true
                            )
                        }
                    }
                } else {

                // Nav bar layout: icon ABOVE label for every item, label always
                // shown. The previous design only rendered a label on the
                // selected tab and laid it out SIDE-BY-SIDE with the icon
                // inside a pill sized `fillMaxWidth(0.95f)` of the tab slot,
                // then scaled the whole pill to 1.1x.
                //
                // That combination could not fit "Migrate" (the longest label):
                // icon 24dp + 6dp gap + 16dp padding + a ~13sp ExtraBold word
                // exceeds the ~100dp slot it was given, and `graphicsLayer`
                // scaleX grew it further, so the trailing "e" was pushed under
                // the parent Surface's CircleShape clip and vanished in portrait.
                //
                // Stacking the label under the icon gives each word the FULL tab
                // width, and the pill now hugs the icon instead of competing with
                // the text, so nothing can be clipped at any width or font scale.
                // Showing every label also keeps the four labels on one baseline
                // instead of one item shifting sideways when it is selected.
                val navFontScale = LocalDensity.current.fontScale
                // Nav labels must FIT their slot at any width and any system
                // font scale. Because the label is a single line with
                // `softWrap = false`, Compose measures it at its intrinsic
                // width, so a label wider than the tab slot does not clip - it
                // spills past the slot and collides with its neighbour. At
                // 2.0x font scale on a ~305dp phone that produced
                // "FlashMigr ToolsAuto".
                //
                // The width a label needs is MEASURED against the slot it is
                // actually being drawn in (BoxWithConstraints below), and the
                // font size is found by bisection. Nothing is estimated from a
                // character count, so the result is exact for the real font,
                // the real weight and the real slot at any width and any
                // system font scale. Nothing is ever abbreviated, ellipsised
                // or cut off - it just gets smaller.
                val labelMeasurer = rememberTextMeasurer()
                val labelDensity = LocalDensity.current
                val navBaseStyle = MaterialTheme.typography.labelMedium
                val labelDir = LocalLayoutDirection.current

                Surface(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                        // Measured AFTER the inset padding, so the clearance the
                        // pages reserve is exactly what the bar occupies —
                        // including the gesture-pill inset.
                        .onSizeChanged { navBarHeight = with(navDensity) { it.height.toDp() } }
                        // BOTH bounds are required. `heightIn(min = 64.dp)`
                        // alone leaves the maximum at whatever the parent Box
                        // offers, and because the Row below used fillMaxSize
                        // the bar expanded to the full screen height - its
                        // CircleShape-clipped Surface then covered every
                        // screen. The max keeps growth bounded to the extra
                        // room a large font scale genuinely needs; the min
                        // keeps the 64dp touch target.
                        .heightIn(min = 64.dp, max = 96.dp)
                        .fillMaxWidth()
                        .clip(CircleShape),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 0.dp
                ) {
                    Row(
                        // Wrap the height: the bar sizes to its content, so it
                        // can never be stretched by an unbounded parent.
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        tabs.forEachIndexed { index, tab ->
                            val isSelected = selectedTab == index

                            val iconScale by animateFloatAsState(
                                targetValue = if (isSelected) 1.08f else 1f,
                                // Snappy over bouncy: low-stiffness springs settle
                                // for seconds and drop frames on big lists.
                                animationSpec = DvMotion.Springs.settle(),
                                label = "tabIconScale"
                            )
                            // BoxWithConstraints (not Column) so the label can be fitted
                            // to the width this tab really has after the
                            // weight split. The tap target, semantics and
                            // rounded press shape all stay on this node.
                            BoxWithConstraints(
                                modifier = Modifier
                                    .weight(1f)
                                    .wrapContentHeight()
                                    // NO CircleShape clip here, and that is the whole 2x nav bug.
                                    //
                                    // `clip` on a Box clips to a circle whose DIAMETER is
                                    // the box's SHORTER side. This box is a full tab slot
                                    // wide (213px on the reference phone) and about half
                                    // that tall, so the clip is a ~150px circle, and at
                                    // the label's vertical position the chord across it
                                    // is roughly 90px wide. The label fitter, which
                                    // correctly measured "Migrate" at 200px against a
                                    // 213px slot, was reasoning about a width the pixels
                                    // never had: at 2x font the 'e' was cut in half by
                                    // geometry nobody had measured.
                                    //
                                    // The rounding that matters belongs to the pill, and
                                    // the pill is a `Surface` carrying its own
                                    // `clip(CircleShape)`. On this box the clip bought
                                    // nothing - the ripple is already suppressed with
                                    // `indication = null` - so all it ever did was crop
                                    // glyphs.
                                    //
                                    // The fitter stays: a slot can still be narrower than
                                    // its longest label, and it now measures against a
                                    // width that is real.
                                    .clickable(
                                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        onTabSelect(index)
                                    }
                                    .semantics {
                                        contentDescription = tab.label
                                        selected = isSelected
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                // Leave 3dp of air between neighbouring labels
                                // so they never read as one joined word.
                                val slotLimitPx =
                                    with(labelDensity) { (maxWidth - 3.dp).toPx() }.toInt()
                                val labelWeight =
                                    if (isSelected) FontWeight.Bold else FontWeight.Medium
                                val navLabelSp = remember(tab.label, slotLimitPx, labelWeight, navFontScale) {
                                    fun fits(sp: Float): Boolean {
                                        val w = labelMeasurer.measure(
                                            text = AnnotatedString(tab.label),
                                            style = navBaseStyle.copy(
                                                fontSize = sp.sp,
                                                fontWeight = labelWeight
                                            ),
                                            overflow = TextOverflow.Clip,
                                            softWrap = false,
                                            maxLines = 1,
                                            // No width constraint here on
                                            // purpose: passing maxWidth would
                                            // make Compose clamp the measured
                                            // width to the constraint, so fits()
                                            // would answer "yes" for every
                                            // size and the label would never
                                            // shrink. The intrinsic width is
                                            // what has to be compared.
                                            constraints = Constraints(),
                                            layoutDirection = labelDir,
                                            density = labelDensity
                                        ).size.width
                                        return w <= slotLimitPx
                                    }
                                    // UNITS: `sp` is multiplied by the system
                                    // font scale to get dp, so the design size
                                    // is 11sp and it renders 11dp at 1.0x but
                                    // 22dp at 2.0x. The floor is expressed in dp
                                    // too (9dp rendered = 9 / fontScale sp), and
                                    // the ceiling is the design 11sp. Bisection
                                    // over that range lands on the largest size
                                    // whose REAL measured width fits the slot.
                                    var lo = 9f / navFontScale
                                    var top = 11f
                                    if (fits(top)) {
                                        top
                                    } else {
                                        repeat(7) {
                                            val mid = (lo + top) / 2f
                                            if (fits(mid)) lo = mid else top = mid
                                        }
                                        lo
                                    }
                                }
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                Box(
                                    modifier = Modifier
                                        .widthIn(min = 44.dp)
                                        .height(30.dp)
                                        .clip(CircleShape)
                                        .background(
                                            color = if (isSelected) dvContainer(tab.role)
                                            else Color.Transparent
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = tab.icon,
                                        contentDescription = null,
                                        tint = if (isSelected) dvAccent(tab.role)
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .size(22.dp)
                                            .graphicsLayer {
                                                scaleX = iconScale; scaleY = iconScale
                                            }
                                    )
                                }
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = tab.label,
                                    style = navBaseStyle,
                                    fontSize = navLabelSp.sp,
                                    fontWeight = labelWeight,
                                    color = if (isSelected) dvAccent(tab.role)
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    softWrap = false,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                }
                            }
                        }
                    }
                }
                }
            }

            AnimatedVisibility(
                visible = operationRunningElsewhere,
                enter = fadeIn(DvMotion.settle()) + scaleIn(initialScale = 0.98f, animationSpec = DvMotion.settle()),
                exit = fadeOut(DvMotion.settle(DvMotion.quick)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .zIndex(2f)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    shape = RoundedCornerShape(18.dp),
                    tonalElevation = 1.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.CloudSync, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            // Name the holder. "Working in another area" told a
                            // user nothing they could act on, and read as though
                            // some other app were involved — it is always Droid
                            // Vault itself, and the owner string says which run.
                            buildString {
                                append("Droid Vault is busy")
                                globalRunOwner?.let { append(" — $it") }
                            },
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun OnboardingWizard(viewModel: MainViewModel) {
    val context = LocalContext.current
    // `rememberSaveable`, not `remember`: a theme change or rotation recreates the
    // Activity — stated in this same file as the reason `showSettings`/`showAbout`
    // are saveable — and `remember` dumped the user back at step 1 with the typed
    // vault path lost. It also re-entered step 1, and because `AnimatedContent`
    // keys its content by `targetState`, that made `LaunchedEffect(Unit) {
    // requestInitialPermissions() }` a NEW effect, re-raising the SMS / call-log
    // / contacts batch the platform has usually stopped showing.
    var step by rememberSaveable { mutableIntStateOf(1) }

    val backProgress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var isPopping by remember { mutableStateOf(false) }

    var hasAttemptedAutoDetect by remember { mutableStateOf(false) }

    val triggerBackNavigation = {
        if (!isPopping) {
            isPopping = true
            scope.launch {
                backProgress.animateTo(1f, animationSpec = DvMotion.settle(DvMotion.quick))
                step -= 1
                backProgress.snapTo(0f)
                isPopping = false
            }
        }
    }

    PredictiveBackHandler(enabled = step > 1) { progress ->
        try {
            progress.collect { backEvent ->
                backProgress.snapTo(backEvent.progress)
            }
            triggerBackNavigation()
        } catch (_: CancellationException) {
            scope.launch {
                backProgress.animateTo(
                    0f,
                    animationSpec = DvMotion.settle(DvMotion.quick)
                )
            }
        }
    }

    val screenWidthPx =
        androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width.toFloat()

    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                // getTreeDocumentId returns null when pathSegments.size() < 2 and
                // throws for a non-tree URI; getExternalStorageDirectory is null with
                // no external volume. Both are platform types, so Kotlin inserts no
                // check. BackupFolderStore.pathFromTreeUri already guards both — these
                // two callbacks did not, and a bad tree URI from any provider crashed
                // the screen from inside the ActivityResult callback.
                val docId = android.provider.DocumentsContract.getTreeDocumentId(uri) ?: return@rememberLauncherForActivityResult
                val split = docId.split(":")
                val basePath = android.os.Environment.getExternalStorageDirectory()?.absolutePath
                    ?: return@rememberLauncherForActivityResult
                val path = if ("primary".equals(split[0], true)) "$basePath/${split.getOrNull(1) ?: ""}"
                else "/storage/${split[0]}/${split.getOrNull(1) ?: ""}"
                val finalPath = if (path.endsWith("DroidVault")) path else "$path/DroidVault"
                viewModel.migrateFolder(finalPath) { ok -> if (ok) step = 3 }
            }
        }

    /** Creates the default "/storage/emulated/0/DroidVault" folder in place. */
    val createDefault = {
        viewModel.migrateFolder(
            (android.os.Environment.getExternalStorageDirectory()?.absolutePath ?: "/storage/emulated/0") + "/DroidVault"
        ) { ok -> if (ok) step = 3 }
        Unit
    }

    // Request notification consent by itself so ROM permission controllers
    // cannot hide it in the larger SMS/call/contact request batch. Then request
    // the separate device-data runtime grants; all denials remain non-fatal.
    val devicePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }
    val notificationPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Keep notification consent independent from the SMS/call/contact
        // groups. Android and some ROM permission controllers suppress or
        // combine multi-permission prompts in surprising ways.
        devicePermLauncher.launch(com.phoenix.droidvault.utils.PermissionChecker.allRuntime())
    }
    val requestInitialPermissions = {
        val notification = android.Manifest.permission.POST_NOTIFICATIONS
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            com.phoenix.droidvault.utils.PermissionChecker.missing(context, arrayOf(notification)).isNotEmpty()
        ) {
            notificationPermLauncher.launch(notification)
        } else {
            devicePermLauncher.launch(com.phoenix.droidvault.utils.PermissionChecker.allRuntime())
        }
    }

    Box(modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
    ) {

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (backProgress.value > 0f) {
                        translationX = backProgress.value * screenWidthPx
                        val cornerRadius = 20.dp * backProgress.value
                        shape = RoundedCornerShape(cornerRadius)
                        clip = true
                        shadowElevation = 24f * backProgress.value
                    }
                }
                .background(MaterialTheme.colorScheme.surface)
        ) {
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val goingBack = targetState < initialState
                    if (goingBack) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        slideInHorizontally(DvMotion.settle(DvMotion.quick)) { it } togetherWith fadeOut(DvMotion.settle(DvMotion.quick))
                    }
                },
                label = "OnboardingTransition",
                modifier = Modifier.fillMaxSize()
            ) { currentStep ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    OnboardingStepContent(
                        step = currentStep,
                        context = context,
                        viewModel = viewModel,
                        onNext = { step = it },
                        launcher = launcher,
                        requestInitialPermissions = requestInitialPermissions,
                        createDefaultFolder = createDefault,
                        hasAttemptedAutoDetect = hasAttemptedAutoDetect,
                        onSetAttemptedAutoDetect = { hasAttemptedAutoDetect = true },
                        onBack = { triggerBackNavigation() }
                    )
                }
            }
        }
    }
}

@Composable
fun OnboardingStepContent(
    step: Int,
    context: Context,
    viewModel: MainViewModel,
    onNext: (Int) -> Unit,
    launcher: androidx.activity.compose.ManagedActivityResultLauncher<Uri?, Uri?>,
    requestInitialPermissions: () -> Unit,
    createDefaultFolder: () -> Unit = {},
    hasAttemptedAutoDetect: Boolean = false,
    onSetAttemptedAutoDetect: () -> Unit = {},
    onBack: () -> Unit = {}
) {

    val iconScale = remember { Animatable(0.3f) }
    LaunchedEffect(step) {
        iconScale.animateTo(
            targetValue = 1f,
            animationSpec = DvMotion.Springs.overshoot()
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (step) {
                1 -> {
                    LaunchedEffect(Unit) {
                        requestInitialPermissions()
                    }

                    var isDetecting by remember { mutableStateOf(false) }

                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .graphicsLayer { scaleX = iconScale.value; scaleY = iconScale.value }
                            .clip(CircleShape)
                            .background(dvContainer(DvRole.Backup)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_home),
                            contentDescription = "Droid Vault",
                            modifier = Modifier.size(56.dp),
                            tint = dvAccent(DvRole.Backup)
                        )
                    }
                    Spacer(Modifier.height(32.dp))
                    Text(
                        "Get Started with",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Droid Vault",
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Backs up your apps with their data, plus messages, calls, contacts, " +
                            "Wi-Fi, wallpaper, Bluetooth and any folder you choose — into one " +
                            "folder on this phone.\n\nRestores them onto a freshly flashed ROM. " +
                            "Everything stays on the device: Droid Vault has no internet access, " +
                            "so nothing is ever uploaded.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(48.dp))

                    Button(
                        onClick = {
                            if (!hasAttemptedAutoDetect) {
                                isDetecting = true
                                viewModel.autoDetectVaultFolder {
                                    isDetecting = false
                                    onSetAttemptedAutoDetect()
                                    // Even if a previous vault is found, show
                                    // the destination step so a clean install
                                    // always lets the user verify/change the
                                    // folder before setup completes.
                                    onNext(2)
                                }
                            } else {
                                onNext(2)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = CircleShape,
                        enabled = !isDetecting
                    ) {
                        if (isDetecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Searching...", style = MaterialTheme.typography.titleMedium)
                        } else {
                            Text("Next", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }

                2 -> {
                    val savedPath by viewModel.savedPath.collectAsStateWithLifecycle()
                    var inputPath by remember { mutableStateOf(savedPath) }

                    LaunchedEffect(savedPath) { inputPath = savedPath }

                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .graphicsLayer { scaleX = iconScale.value; scaleY = iconScale.value }
                            .clip(CircleShape)
                            .background(dvContainer(DvRole.Storage)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Folder,
                            null,
                            modifier = Modifier.size(56.dp),
                            tint = dvAccent(DvRole.Storage)
                        )
                    }
                    Spacer(Modifier.height(32.dp))
                    Text(
                        "Droid Vault Directory",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Manually browse or enter the path where you want to store the files.\n\n" +
                                // The audience is about to hand this folder their whole message
                                // history, media library and banking-app data. AboutScreen says
                                // the truth three menus deep; the first-run path said nothing,
                                // and "protected" elsewhere on this screen read as encryption.
                                "Backups here are not encrypted. Anyone who can read this folder " +
                                "can read the contents of every archived app.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(48.dp))

                    OutlinedTextField(
                        value = inputPath,
                        onValueChange = { inputPath = it },
                        singleLine = true,
                        shape = CircleShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        // `@Nullable`, and Kotlin inserts no check for a platform type — this threw
                        // on a device with no external volume, in the one
                        // onboarding step that cannot be skipped. Two other calls
                        // in this same file already guard it (`:1301`, `:1313`).
                        placeholder = {
                            Text(
                                (android.os.Environment.getExternalStorageDirectory()
                                    ?.absolutePath ?: "/storage/emulated/0") + "/DroidVault"
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Storage,
                                null,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    )
                    Spacer(Modifier.height(16.dp))

                    Button(
                        onClick = { viewModel.migrateFolder(inputPath) { ok -> if (ok) onNext(3) } },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = CircleShape
                    ) {
                        Text(
                            "Confirm & Next",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    // One-tap default: create /storage/emulated/0/DroidVault
                    // without browsing — the common first-install path.
                    OutlinedButton(
                        onClick = createDefaultFolder,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = CircleShape
                    ) {
                        Text(
                            "Create default folder (DroidVault)",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { launcher.launch(null) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = CircleShape
                    ) {
                        Text(
                            "Browse Storage",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }

                3 -> {
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .graphicsLayer { scaleX = iconScale.value; scaleY = iconScale.value }
                            .clip(CircleShape)
                            .background(dvContainer(DvRole.Tools)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Favorite,
                            null,
                            modifier = Modifier.size(56.dp),
                            tint = dvAccent(DvRole.Tools)
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        "Made by phoenix-1708",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Thank you for using Droid Vault — local-first, privacy-respecting device backups.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(32.dp))
                    Button(
                        onClick = { viewModel.finishOnboarding() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = CircleShape
                    ) { Text("Let's Go!", style = MaterialTheme.typography.titleMedium) }
                }
            }
        }
        if (step > 1) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 24.dp, start = 8.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
fun NoRootScreen(
    isChecking: Boolean,
    rootState: com.phoenix.droidvault.models.RootState,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Surface(
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(
                    Icons.Default.Security,
                    contentDescription = null,
                    modifier = Modifier.padding(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Connect root access",
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Droid Vault needs root to protect app data and restore device backups.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = DvShapes.card,
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("To get started", style = MaterialTheme.typography.titleMedium)
                    // The dead end this replaces: on a ROM where Magisk's
                    // authorisation dialog fails to open, tapping the button
                    // raised nothing and this card went on telling the user to
                    // "Approve Droid Vault in your root manager" — naming as the
                    // next step the exact thing that was broken. Each state says
                    // what is actually true, and `NotGranted` gives the manual
                    // route that works when the prompt does not appear.
                    Text(
                        when (rootState) {
                            com.phoenix.droidvault.models.RootState.NoRootManager ->
                                "This device has no root manager, so Droid Vault cannot " +
                                    "read other apps' data. Install Magisk, boot, then tap " +
                                    "Check access and approve Droid Vault."
                            com.phoenix.droidvault.models.RootState.NotGranted ->
                                "A root manager is installed but has not granted Droid " +
                                    "Vault.\n\nIf no prompt appeared, grant it manually: " +
                                    "open Magisk → Superuser, find Droid Vault, and turn " +
                                    "it on. Then tap Check access again."
                            else ->
                                "1. Tap Check access.\n2. Approve Droid Vault in your root " +
                                    "manager.\n3. Return here if the permission prompt was " +
                                    "missed."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Button(
                onClick = onRetry,
                enabled = !isChecking,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                if (isChecking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Checking access…", style = MaterialTheme.typography.labelLarge)
                } else {
                    Icon(Icons.Default.Security, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Check access", style = MaterialTheme.typography.labelLarge)
                }
            }

            Text(
                "Your backups stay on this device. Root is used only to read and write app data and to run restores.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
