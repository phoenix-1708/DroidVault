package com.phoenix.droidvault.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.SecurityUpdateGood
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phoenix.droidvault.MainViewModel
import com.phoenix.droidvault.models.AppInfo
import com.phoenix.droidvault.models.AppState
import com.phoenix.droidvault.models.MigratorMode
import com.phoenix.droidvault.ui.components.MenuCard
import com.phoenix.droidvault.ui.theme.DvRole
import kotlinx.coroutines.launch

@Composable
fun ToolsTab(appState: AppState, appList: List<AppInfo>, viewModel: MainViewModel) {
    var showMetaWarningDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Debloat and Systemize never claim ProgressTracker, so MigratorActionScreen's
    // progress panel is their ONLY progress feedback — and it reads currentStep
    // and progress. The shell hands this tab the deduplicated shellUiState,
    // which zeroes exactly those two fields (they update several times a
    // second and must not invalidate navigation). The result was a
    // determinate bar frozen at 0% with no per-app line and no completion
    // indicator. Subscribe to the raw flow here, the way MigratorTab already
    // does, so only this subtree recomposes on a progress tick.
    val liveState by viewModel.uiState.collectAsStateWithLifecycle()

    if (showMetaWarningDialog) {
        AlertDialog(
            shape = RoundedCornerShape(30.dp),
            onDismissRequest = { showMetaWarningDialog = false },
            title = {
                Text(
                    "Mountify Required",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.error
                )
            },
            text = {
                Text(
                    "You are not using Magisk. To systemize apps via KernelSU or APatch, you must have the Mountify module installed. Please download and flash it in your root manager, then try again.",
                    style = MaterialTheme.typography.bodyLarge
                )
            },
            confirmButton = {
                Button(onClick = { showMetaWarningDialog = false }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                    Text("OK")
                }
            },
            dismissButton = {}
        )
    }

    AnimatedContent(
        targetState = appState.migratorMode !in listOf(MigratorMode.DEBLOAT, MigratorMode.SYSTEMIZE),
        transitionSpec = {
            val goingBack = !initialState && targetState 
            if (goingBack) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {

                slideInHorizontally(tween(150, easing = FastOutSlowInEasing)) { it } togetherWith fadeOut(tween(130))
            }
        },
        label = "ToolsTransition"
    ) { isMenu ->
        if (!isMenu) {
            MigratorActionScreen(liveState, appList, viewModel)
        } else {

            Column(modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 16.dp)) {
                Column(modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())) {
                    Spacer(modifier = Modifier.height(16.dp))
                    MenuCard("Debloat / Restore Apps", Icons.Default.DeleteSweep, "Uninstall and restore System/User apps", DvRole.Destructive) { viewModel.setMigratorMode(MigratorMode.DEBLOAT) }
                    MenuCard("Systemize User Apps", Icons.Default.SecurityUpdateGood, "Turn user apps into system apps so they can't be uninstalled", DvRole.Tools) {
                        scope.launch {
                            if (viewModel.canSystemize()) {
                                viewModel.setMigratorMode(MigratorMode.SYSTEMIZE)
                            } else {
                                showMetaWarningDialog = true
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(120.dp))
                }
            }
        }
    }
}
