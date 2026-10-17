package com.phoenix.droidvault.ui.screens
import android.widget.Toast
import com.phoenix.droidvault.ui.safeToast

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phoenix.droidvault.MainViewModel
import com.phoenix.droidvault.ui.components.DvIconBadge
import com.phoenix.droidvault.models.AppParts
import com.phoenix.droidvault.models.DiagLog
import com.phoenix.droidvault.ui.theme.DvMotion
import com.phoenix.droidvault.ui.theme.DvRole
import com.phoenix.droidvault.ui.theme.DvShapes
import com.phoenix.droidvault.ui.theme.dvAccent
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.Description
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.core.content.FileProvider
import com.phoenix.droidvault.utils.DroidVaultLogger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
private fun ConsentRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onEnableRequest: () -> Unit,
    onDisable: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = { want -> if (want) onEnableRequest() else onDisable() })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTab(
    context: Context,
    viewModel: MainViewModel,
    onOpenAbout: () -> Unit = {}
) {
    val savedPath by viewModel.savedPath.collectAsStateWithLifecycle()
    val currentTheme by viewModel.themeMode.collectAsStateWithLifecycle()
    val dynamicColor by viewModel.dynamicColor.collectAsStateWithLifecycle()
    // Only the three consent toggles are read from the state, so collect the
    // DEDUPLICATED shell flow: the raw flow carries currentStep/progress, which
    // update several times a second during a run, and the gear is reachable
    // while one is in progress — every tick used to recompose this whole screen.
    val uiState by viewModel.shellUiState.collectAsStateWithLifecycle()
    var permCheck by remember { mutableStateOf<com.phoenix.droidvault.utils.PermissionChecker.CheckState?>(null) }
    var permCheckBusy by remember { mutableStateOf(false) }
    val permScope = rememberCoroutineScope()
    val refreshPermissionStatus: () -> Unit = {
        permCheckBusy = true
        viewModel.checkRootAccess {
            // `PermissionChecker.check` blocks on a root-shell engine probe with a
            // 10s bound, so it must never be reached from the main thread. It was
            // safe here ONLY because `checkRootAccess` happens to invoke its hook
            // on IO — and its three sibling callbacks in MainViewModel all invoke
            // theirs on Main, so "align this one with the others" would have moved
            // a ten-second blocking probe onto the composition thread. The hop is
            // made here, at the call site that knows the work is blocking, so the
            // screen does not depend on someone else's dispatcher. Same pattern as
            // the LaunchedEffect below.
            permScope.launch {
                permCheck = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.phoenix.droidvault.utils.PermissionChecker.check(context)
                }
                permCheckBusy = false
            }
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshPermissionStatus() }

    LaunchedEffect(Unit) { viewModel.loadConsentState() }

    var inputPath by rememberSaveable { mutableStateOf(savedPath) }
    var isMoving by rememberSaveable { mutableStateOf(false) }
    var showMoveWarning by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(savedPath) { if (!isMoving) inputPath = savedPath }
    val isEditing = inputPath != savedPath

    // Saveable: the theme picker recreates the Activity when applying a mode,
    // and a plain remember closed the sheet mid-selection.
    var showThemeSheet by rememberSaveable { mutableStateOf(false) }

    // --- Diagnostics logger: state + live ticker. The ticker refreshes the
    // status line every 500ms and, while the live-view sheet is open, the
    // in-memory ring. File stats stay on IO.
    var diagEnabled by rememberSaveable { mutableStateOf(DroidVaultLogger.isEnabled(context)) }
    var diagStatus by rememberSaveable { mutableStateOf("") }
    var showLogSheet by rememberSaveable { mutableStateOf(false) }
    var logLines by remember { mutableStateOf<List<String>>(emptyList()) }
    val logScope = rememberCoroutineScope()
    // Deliberately the deprecated LocalClipboardManager. LocalClipboard is the
    // replacement, but `setText` on it is SUSPEND, so migrating means threading
    // a coroutine scope into a non-suspending click handler. That is a behaviour
    // change, not a warning cleanup, and is not worth making for a deprecation.
    val clipboard = LocalClipboardManager.current

    /**
     * ONE timer for both the status line and the log itself.
     *
     * There were two, and they disagreed about whether Settings being open was
     * reason enough to work. The snapshot loop was keyed on the sheet, with a
     * comment explaining that polling twice a second for the whole time Settings
     * is open — sheet closed, nothing to show — is work for nobody. The status
     * loop ten lines above it did exactly that, unkeyed, and nobody applied the
     * same reasoning to it: two `File.length()` calls at 2 Hz for as long as the
     * user sat in Settings reading a toggle.
     *
     * So: one loop, and the cadence follows what is on screen. The status still
     * refreshes with the sheet closed, because the row above it is visible then
     * — but a byte counter does not need 2 Hz, and a snapshot of 2000 strings
     * certainly does not need it while nothing is displaying it.
     */
    LaunchedEffect(showLogSheet) {
        while (true) {
            diagStatus = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                DroidVaultLogger.statusSummary(context)
            }
            if (showLogSheet) {
                val snapshot = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    DroidVaultLogger.snapshot()
                }
                // Published ONLY on a real change. This replaced the list
                // wholesale every tick, so with the sheet open and no run going
                // the same 2000 strings were handed to a LazyColumn twice a
                // second and every visible row recomposed. Comparing on the IO
                // thread also gives the list a STABLE IDENTITY between ticks,
                // which is what stops it re-keying — and a content `key` would
                // risk a duplicate-key crash on two identical lines inside one
                // millisecond, which a millisecond-resolution format allows.
                if (snapshot != logLines) logLines = snapshot
            }
            kotlinx.coroutines.delay(if (showLogSheet) 500L else 2_000L)
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            // Platform types: null for a malformed tree URI / no external volume.
            // See MainScreen's copy of this callback for the full note.
            val docId = android.provider.DocumentsContract.getTreeDocumentId(uri) ?: return@rememberLauncherForActivityResult
            val split = docId.split(":")
            val basePath = android.os.Environment.getExternalStorageDirectory()?.absolutePath
                ?: return@rememberLauncherForActivityResult
            val path = if ("primary".equals(split[0], true)) "$basePath/${split.getOrNull(1) ?: ""}" else "/storage/${split[0]}/${split.getOrNull(1) ?: ""}"
            val finalPath = if (path.endsWith("DroidVault")) path else "$path/DroidVault"
            inputPath = finalPath

            val oldIsInternal =
                savedPath.startsWith(basePath) || savedPath.startsWith("/storage/emulated")
            val newIsInternal = "primary".equals(split[0], true)

            if (oldIsInternal != newIsInternal) {
                showMoveWarning = finalPath
            } else {
                isMoving = true
                viewModel.migrateFolder(finalPath) { isMoving = false }
            }
        }
    }

    if (showMoveWarning != null) {
        AlertDialog(
            onDismissRequest = { showMoveWarning = null },
            confirmButton = {
                Button(
                    onClick = {
                        val path = showMoveWarning!!
                        showMoveWarning = null
                        isMoving = true
                        viewModel.migrateFolder(path) { isMoving = false }
                    }
                ) {
                    Text("Proceed")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showMoveWarning = null }) {
                    Text("Cancel")
                }
            },
            title = { Text("Moving Backups") },
            text = { Text("Moving your existing Droid Vault files between Internal Storage and MicroSD will take time. Please don't close the app or don't go back during this process.") },
            shape = DvShapes.card,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    }

    if (showThemeSheet) {
        ModalBottomSheet(
            onDismissRequest = { showThemeSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 12.dp)) {
                Text("Choose Theme", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(16.dp))

                val options = remember {
                    listOf(
                        0 to "System Default",
                        1 to "Light",
                        2 to "Dark",
                        3 to "Amoled (Black)"
                    )
                }
                options.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { viewModel.setTheme(value); showThemeSheet = false }
                            .padding(vertical = 14.dp, horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(label, style = MaterialTheme.typography.titleMedium, color = if (currentTheme == value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        if (currentTheme == value) Icon(Icons.Default.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { viewModel.setDynamicColor(!dynamicColor) }
                        .padding(vertical = 12.dp, horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            "Dynamic Colors",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        val subText =
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) "Use Monet colors based on wallpaper" else "Auto-detect accent colors from wallpaper"
                        Text(
                            subText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = dynamicColor,
                        onCheckedChange = { viewModel.setDynamicColor(it) },
                        thumbContent = {
                            Icon(
                                imageVector = if (dynamicColor) Icons.Filled.Check else Icons.Filled.Close,
                                contentDescription = null,
                                modifier = Modifier.size(SwitchDefaults.IconSize)
                            )
                        }
                    )
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    Column(modifier = Modifier
        .padding(horizontal = 16.dp)
        .fillMaxSize()
        .verticalScroll(rememberScrollState())) {
        Spacer(modifier = Modifier.height(16.dp))

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
                .animateContentSize(animationSpec = DvMotion.settle(DvMotion.emphasised)),
            shape = RoundedCornerShape(30.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 0.dp
        ) {
            Column {
                Column(modifier = Modifier.padding(24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DvIconBadge(
                            role = DvRole.Storage,
                            icon = Icons.Default.Folder,
                            containerSize = 48.dp,
                            iconSize = 24.dp,
                            contentDescription = "Vault Location"
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Vault Location",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text("All backups and files are saved here", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { if (!isMoving) launcher.launch(null) }) { Icon(Icons.Default.FolderOpen, contentDescription = "Browse", modifier = Modifier.size(28.dp)) }
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    OutlinedTextField(
                        value = inputPath,
                        onValueChange = { inputPath = it },
                        singleLine = true,
                        shape = CircleShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        placeholder = { Text("${android.os.Environment.getExternalStorageDirectory().absolutePath}/DroidVault") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Storage,
                                null,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    )

                    AnimatedVisibility(visible = isEditing || isMoving, enter = expandVertically(), exit = shrinkVertically()) {
                        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                            Spacer(modifier = Modifier.height(16.dp))
                            if (isMoving) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text("Saving Directory...", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                                }
                            } else {
                                Button(
                                    onClick = {
                                        val basePath =
                                            android.os.Environment.getExternalStorageDirectory().absolutePath
                                        val oldIsInternal =
                                            savedPath.startsWith(basePath) || savedPath.startsWith("/storage/emulated")
                                        val newIsInternal =
                                            inputPath.startsWith(basePath) || inputPath.startsWith("/storage/emulated")

                                        if (oldIsInternal != newIsInternal) {
                                            showMoveWarning = inputPath
                                        } else {
                                            isMoving = true
                                            viewModel.migrateFolder(inputPath) { isMoving = false }
                                        }
                                    },
                                    shape = CircleShape,
                                    modifier = Modifier.height(52.dp)
                                ) { Text("Apply Path", style = MaterialTheme.typography.titleMedium) }
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))

                // --- Folder backup sources (DeviceDataItem.FOLDERS) ---
                val backupFolders by viewModel.backupFolders.collectAsStateWithLifecycle()
                var folderPendingDelete by remember { mutableStateOf<String?>(null) }
                val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                    if (uri != null) {
                        val path = com.phoenix.droidvault.utils.BackupFolderStore.pathFromTreeUri(uri)
                        if (path == null) {
                            context.safeToast(
                                "Folder not supported",
                                Toast.LENGTH_SHORT
                            )
                        } else {
                            // addBackupFolder persists off the main thread (it
                            // fsyncs prefs), so its result arrives by callback.
                            viewModel.addBackupFolder(path) { ok ->
                                if (!ok) {
                                    context.safeToast(
                                        "Folder not supported: $path",
                                        Toast.LENGTH_SHORT
                                    )
                                }
                            }
                        }
                    }
                }
                Text(
                    "Backup Folders",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                )
                Text(
                    "Folders packed on every Device Data backup with \"Folders\" enabled. Root access lets backups include Android/data and Android/obb.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                backupFolders.forEach { folder ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DvIconBadge(
                            role = DvRole.Storage,
                            icon = Icons.Default.Folder,
                            containerSize = 40.dp,
                            iconSize = 22.dp,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        // Final folder name only ("IntegrityFIX"); full path
                        // as content description so nothing is lost.
                        Text(
                            com.phoenix.droidvault.utils.BackupConfigFolders.shortDisplayName(folder),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { folderPendingDelete = folder }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Remove $folder",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { folderPicker.launch(null) }
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(14.dp))
                    Text(
                        "Add folder",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))

                if (folderPendingDelete != null) {
                    AlertDialog(
                        onDismissRequest = { folderPendingDelete = null },
                        title = { Text("Remove folder from backups?") },
                        text = { Text("$folderPendingDelete will no longer be packed into Device Data backups. Files on the device are not touched.") },
                        confirmButton = {
                            Button(onClick = {
                                folderPendingDelete?.let { viewModel.removeBackupFolder(it) }
                                folderPendingDelete = null
                            }) { Text("Remove") }
                        },
                        dismissButton = {
                            OutlinedButton(onClick = { folderPendingDelete = null }) { Text("Cancel") }
                        },
                        shape = DvShapes.card,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))

                // --- What's in my vault folder (self-backup explainer) ---
                // The files Droid Vault itself keeps in the vault
                // folder so the automation state and reinstall path survive
                // an uninstall or clean flash. Refreshes on every visit (the
                // tab re-enters composition) and on folder change.
                val vaultContents by viewModel.vaultContents.collectAsStateWithLifecycle()
                LaunchedEffect(savedPath) { viewModel.refreshVaultContents() }
                Text(
                    "What's in my vault folder",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                )
                Text(
                    "The Apps and Device subfolders hold your backup data. These files are Droid Vault's own — they exist so everything comes back after an uninstall or a clean flash.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                val vaultFiles = vaultContents?.files
                    ?: com.phoenix.droidvault.utils.AutomationSnapshot.vaultFileNames.map {
                        com.phoenix.droidvault.utils.AutomationSnapshot.VaultFileInfo(
                            name = it, path = "", present = false, sizeBytes = 0L, modifiedAt = 0L
                        )
                    }
                vaultFiles.forEach { info ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            when (info.name) {
                                com.phoenix.droidvault.utils.AutomationSnapshot.APK_FILE -> Icons.Default.Star
                                com.phoenix.droidvault.utils.AutomationSnapshot.BACKEND_FILE -> Icons.Default.Storage
                                com.phoenix.droidvault.utils.AutomationSnapshot.LAUNCHER_FILE -> Icons.Default.Storage
                                else -> Icons.Default.Folder
                            },
                            contentDescription = info.name,
                            tint = if (info.present) dvAccent(DvRole.Success)
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                info.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (info.present) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                when (info.name) {
                                    com.phoenix.droidvault.utils.AutomationSnapshot.APK_FILE ->
                                        "Copy of this app — reinstall it to get everything back after an uninstall or clean flash."
                                    com.phoenix.droidvault.utils.AutomationSnapshot.BACKEND_FILE ->
                                        "Rust backup engine — redeploys to /data/adb so restores work on a fresh system."
                                    com.phoenix.droidvault.utils.AutomationSnapshot.LAUNCHER_FILE ->
                                        "Compatibility launcher used by Android entry points to start the Rust engine."
                                    else ->
                                        "Labels, configurations and schedules — what Restore Configuration brings back."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                if (!info.present) {
                                    "Not created yet — appears after the first backup run"
                                } else {
                                    val size = com.phoenix.droidvault.utils.Formatters.formatBytes(info.sizeBytes)
                                    val datePart = if (info.modifiedAt > 0L) {
                                        // Observe the configuration locale instead of
                                        // Locale.getDefault() so the date reformats on
                                        // a locale change (lint NonObservableLocale,
                                        // audit A-25).
                                        " • " + java.text.SimpleDateFormat(
                                            "dd MMM, hh:mm a",
                                            androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
                                        ).format(java.util.Date(info.modifiedAt))
                                    } else ""
                                    val stale = vaultContents?.health?.get(info.name) ==
                                        com.phoenix.droidvault.utils.AutomationSnapshot.VaultHealth.STALE
                                    "$size$datePart" + if (stale) " • Out of date" else ""
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = when {
                                    !info.present -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    vaultContents?.health?.get(info.name) ==
                                        com.phoenix.droidvault.utils.AutomationSnapshot.VaultHealth.STALE ->
                                        MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                }
                // One-tap repair: a missing or out-of-date APK/engine/launcher mirror
                // defeats the whole reinstall kit. The state file is excluded
                // — it appears with the first run, it is not a mirror.
                val needsRepair = vaultFiles.any { info ->
                    info.name != com.phoenix.droidvault.utils.AutomationSnapshot.STATE_FILE && (
                        !info.present || vaultContents?.health?.get(info.name) ==
                            com.phoenix.droidvault.utils.AutomationSnapshot.VaultHealth.STALE
                        )
                }
                if (needsRepair) {
                    val repairing by viewModel.vaultRepairing.collectAsStateWithLifecycle()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (repairing) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                "Repairing…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            TextButton(onClick = {
                                viewModel.repairVaultMirrors { ok ->
                                    context.safeToast(
                                        if (ok) "Vault mirrors repaired" else "Repair failed — check root access",
                                        Toast.LENGTH_SHORT
                                    )
                                }
                            }) { Text("Repair mirrors") }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showThemeSheet = true }
                        .padding(horizontal = 24.dp, vertical = 24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DvIconBadge(
                        role = DvRole.Tools,
                        icon = Icons.Default.Palette,
                        containerSize = 48.dp,
                        iconSize = 24.dp,
                        contentDescription = "Theme"
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            "Appearance",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        val themeText = when (currentTheme) {
                            1 -> "Light"; 2 -> "Dark"; 3, 4 -> "Amoled"; else -> "System Default"
                        }
                        val dynamicText = if (dynamicColor) " (Dynamic)" else ""
                        Text(
                            "Theme: $themeText$dynamicText",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))

                // --- Safety consents (restore-side default ON; fast install strict opt-in) ---
                var pendingConsent by remember { mutableStateOf<Triple<String, String, (Boolean) -> Unit>?>(null) }
                Text(
                    "Safety Consents",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                )
                ConsentRow(
                    title = "Fast install",
                    subtitle = "Disables verifier + spoofs Play installer. Faster, weaker checks.",
                    checked = uiState.allowFastInstall,
                    onEnableRequest = {
                        pendingConsent = Triple(
                            "Enable Fast install?",
                            "Disables package verification during install (restored after). Only if you trust your APK sources.",
                            { viewModel.setFastInstallAllowed(true) }
                        )
                    },
                    onDisable = { viewModel.setFastInstallAllowed(false) }
                )
                ConsentRow(
                    title = "Restore Android ID",
                    subtitle = "On by default. Clones SSAID across ROMs; may break banking/push.",
                    checked = uiState.allowSsaidRestore,
                    onEnableRequest = {
                        pendingConsent = Triple(
                            "Restore Android IDs?",
                            "Cloned IDs can break apps relying on unique IDs.",
                            { viewModel.setSsaidRestoreAllowed(true) }
                        )
                    },
                    onDisable = { viewModel.setSsaidRestoreAllowed(false) }
                )
                ConsentRow(
                    title = "Restore permissions silently",
                    subtitle = "On by default. Re-grants perms + app-ops without prompts.",
                    checked = uiState.allowPermRestore,
                    onEnableRequest = {
                        pendingConsent = Triple(
                            "Restore permissions silently?",
                            "Apps regain backed-up permissions without asking. Review after restore.",
                            { viewModel.setPermRestoreAllowed(true) }
                        )
                    },
                    onDisable = { viewModel.setPermRestoreAllowed(false) }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))

                // --- Permissions self-check: live freshness of the standing
                // grants. These four fail silently (a ROM switch wipes the
                // usage-stats appop, a notification-permission revoke mutes
                // scheduled-run cards, battery optimization defers schedules,
                // a stale engine breaks restores) and nothing in system UI
                // surfaces them — so Settings shows each as green/red.
                LaunchedEffect(Unit) {
                    permCheckBusy = true
                    // Probes are blocking root-shell calls — never on main.
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.phoenix.droidvault.utils.PermissionChecker.check(context)
                    }.let { permCheck = it; permCheckBusy = false }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Permissions",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        val notification = android.Manifest.permission.POST_NOTIFICATIONS
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                            com.phoenix.droidvault.utils.PermissionChecker.missing(context, arrayOf(notification)).isNotEmpty()
                        ) {
                            notificationPermissionLauncher.launch(notification)
                        } else {
                            // Full grant path (usage-stats appop + whitelist),
                            // then re-probe so every Settings row updates live.
                            refreshPermissionStatus()
                        }
                    }, enabled = !permCheckBusy) { Text("Re-grant") }
                }
                val permRows = listOf(
                    Triple(
                        "Usage stats access", "App sizes and storage listings",
                        permCheck?.appopUsageStats
                    ),
                    Triple(
                        "Notifications", "Run progress and completion cards",
                        permCheck?.postNotifications
                    ),
                    Triple(
                        "Battery optimization off", "Schedules fire on time",
                        permCheck?.deviceidleWhitelisted
                    ),
                    Triple(
                        "Backup engine up to date", "/data/adb engine matches this build",
                        permCheck?.engineMatch
                    ),
                    Triple(
                        "Restricted settings allowed",
                        "Android 15+ gate on the default-SMS role (message restore)",
                        permCheck?.restrictedSettingsAllowed
                    )
                )
                permRows.forEach { (title, subtitle, ok) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // `null` reaches here only when the probe was RUN and gave
                        // no answer (a timeout, a failed shell) — the rows are not
                        // rendered at all before the check completes. So this is
                        // not "checking", and calling it that is the same
                        // laundering a timeout into a state it never had. The
                        // neutral Info icon is right; the wording has to be too.
                        Icon(
                            when {
                                ok == null -> Icons.Default.Info
                                ok -> Icons.Default.Check
                                else -> Icons.Default.Close
                            },
                            contentDescription = if (ok == null) {
                                "Could not be checked"
                            } else if (ok) "OK" else "Attention needed",
                            tint = when {
                                ok == null -> MaterialTheme.colorScheme.onSurfaceVariant
                                ok -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        when {
                            ok == null && permCheckBusy -> CircularProgressIndicator(
                                modifier = Modifier.size(16.dp), strokeWidth = 2.dp
                            )
                            ok == false -> Text(
                                "Fix: Re-grant",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                    }
                }
                // `== true`, not a bare `&&`: `engineMatch` is tri-state, and an
                // unanswered probe must not count as "fresh" any more than it
                // counts as stale. The summary line is a claim about every grant,
                // so it may only be made when every one of them was actually read.
                if (permCheck != null && permCheck!!.let { it.appopUsageStats && it.postNotifications && it.deviceidleWhitelisted && it.engineMatch == true && it.restrictedSettingsAllowed }) {
                    // A reassuring footnote, not a link. It rendered in the accent
                    // blue, which read as "tap here" on a row that cannot be tapped.
                    Text(
                        "All standing grants are fresh.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                    )
                }

                if (pendingConsent != null) {
                    AlertDialog(
                        onDismissRequest = { pendingConsent = null },
                        confirmButton = {
                            Button(onClick = { pendingConsent!!.third(true); pendingConsent = null }) { Text("Enable") }
                        },
                        dismissButton = {
                            OutlinedButton(onClick = { pendingConsent = null }) { Text("Cancel") }
                        },
                        title = { Text(pendingConsent!!.first) },
                        text = { Text(pendingConsent!!.second) },
                        shape = DvShapes.card
                    )
                }

                // --- Diagnostics logger: every notable app/backend event with
                // millisecond timestamps (engine lines, run start/done, fetch
                // timings, SMS receipt, engine deploys). Live view reads the
                // in-memory ring; export stages current + previous day file
                // into the existing FileProvider staging dir.
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DvIconBadge(
                        role = DvRole.Tools,
                        icon = Icons.Default.Description,
                        containerSize = 48.dp,
                        iconSize = 24.dp,
                        contentDescription = "Diagnostics logger"
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Diagnostics logger",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            if (diagEnabled) diagStatus else "Off — file writes paused",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { showLogSheet = true }) { Text("View") }
                    Switch(checked = diagEnabled, onCheckedChange = { want ->
                        DroidVaultLogger.setEnabled(context, want)
                        diagEnabled = want
                    })
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenAbout() }
                        .padding(horizontal = 24.dp, vertical = 24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DvIconBadge(
                        role = DvRole.Info,
                        icon = Icons.Default.Info,
                        containerSize = 48.dp,
                        iconSize = 24.dp,
                        contentDescription = "About"
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text("About", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text("What Droid Vault does, and this build's details", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // --- Live log sheet: in-memory ring, newest at the bottom,
                // auto-scrolled; Export / Copy / Clear. Export goes through
                // the same FileProvider staging dir as automation exports
                // (cache-path automation-exports/, authority in the manifest).
                if (showLogSheet) {
                    ModalBottomSheet(
                        onDismissRequest = { showLogSheet = false },
                        // Matches the theme sheet above. Left at the default this
                        // one sat on a different surface from every other sheet in
                        // the app, which nobody notices until two are open.
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                            Text(
                                "Live log",
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                if (diagEnabled) diagStatus
                                else "Logging disabled — file writes paused (memory ring still live)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            // A severity floor and a search, because 2000 lines
                            // in one grey is not a diagnostic tool, it is a file.
                            // The question this screen exists to answer is "what
                            // went wrong", and that is one E line somewhere in
                            // the middle of them.
                            var minLevel by rememberSaveable { mutableStateOf('I') }
                            var logQuery by rememberSaveable { mutableStateOf("") }
                            val visible = remember(logLines, minLevel, logQuery) {
                                DiagLog.filter(logLines, minLevel, logQuery)
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                DiagLevelChip("All", selected = minLevel == 'V') { minLevel = 'V' }
                                DiagLog.LEVELS.forEach { lv ->
                                    DiagLevelChip(
                                        label = lv.toString(),
                                        selected = minLevel == lv,
                                        tint = diagLevelTint(lv)
                                    ) {
                                        // Tapping the active level returns to
                                        // everything, so there is always a way
                                        // back to the full log without hunting
                                        // for an "off" state.
                                        minLevel = if (minLevel == lv) 'V' else lv
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            OutlinedTextField(
                                value = logQuery,
                                onValueChange = { logQuery = it },
                                singleLine = true,
                                placeholder = { Text("Search tags and messages") },
                                leadingIcon = {
                                    Icon(Icons.Default.Search, contentDescription = null)
                                },
                                trailingIcon = {
                                    if (logQuery.isNotEmpty()) {
                                        TextButton(onClick = { logQuery = "" }) { Text("Clear") }
                                    }
                                },
                                shape = CircleShape,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(6.dp))

                            Row {
                                TextButton(onClick = {
                                    logScope.launch {
                                        val staged = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            DroidVaultLogger.stageExport(context)
                                        }
                                        if (staged == null) {
                                            context.safeToast("No log file yet", Toast.LENGTH_SHORT)
                                        } else {
                                            val uri = FileProvider.getUriForFile(
                                                context, "com.phoenix.droidvault.fileprovider", staged
                                            )
                                            val send = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                putExtra(Intent.EXTRA_SUBJECT, staged.name)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(Intent.createChooser(send, "Export log"))
                                        }
                                    }
                                }) { Text("Export") }
                                TextButton(onClick = {
                                    logScope.launch {
                                        val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            DroidVaultLogger.recent(500)
                                        }
                                        // `"".lines()` is a ONE-element list, so
                                        // an empty ring counted as "Copied 1 lines"
                                        // AND put an empty string on the
                                        // clipboard — right after Clear, which is
                                        // exactly when someone tries it.
                                        if (text.isBlank()) {
                                            context.safeToast("Nothing to copy", Toast.LENGTH_SHORT)
                                        } else {
                                            clipboard.setText(AnnotatedString(text))
                                            context.safeToast(
                                                "Copied ${AppParts.count(text.lines().size, "line")}",
                                                Toast.LENGTH_SHORT
                                            )
                                        }
                                    }
                                }) { Text("Copy") }
                                TextButton(onClick = {
                                    logScope.launch {
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            DroidVaultLogger.clearAll(context)
                                        }
                                        logLines = emptyList()
                                        diagStatus = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            DroidVaultLogger.statusSummary(context)
                                        }
                                    }
                                }) { Text("Clear") }
                            }

                            if (visible.isEmpty() && logLines.isNotEmpty()) {
                                Text(
                                    "Nothing matches. " +
                                        AppParts.count(logLines.size, "line") + " in memory" +
                                        // `!= 'V'`, NOT `> 'V'`. The levels are
                                        // ordered by SEVERITY in DiagLog's own
                                        // table, and a Chan's numeric order is
                                        // the alphabet: 'E' is 69 and 'V' is 86,
                                        // so the comparison was true for W and
                                        // false for E, D and I. Caught on the
                                        // device by tapping the E chip and
                                        // reading a sentence that had lost the
                                        // one word explaining why it was empty.
                                        (if (minLevel != 'V') " at $minLevel or worse" else "") +
                                        (if (logQuery.isNotBlank()) " matching \"$logQuery\"" else "") +
                                        ".",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 12.dp)
                                )
                            }

                            val listState = rememberLazyListState()
                            // Follow the tail ONLY while the reader is already
                            // at it. This was an unconditional
                            // `scrollToItem(size - 1)` on every size change, and
                            // the log grows many times a second during a run —
                            // so scrolling up to read a line was impossible,
                            // because the next line yanked the view back down.
                            // That is invariant 31's shape on a scroll position:
                            // a live view that moves when nobody asked it to is
                            // worse than a static one.
                            // Where the tail goes, and when it does not.
                            //
                            // Two separate questions, and conflating them is
                            // what broke this twice. ARRIVALS are governed by
                            // `shouldStickToTail`: follow only while the reader
                            // is already there, because scrolling up is a
                            // statement and the log grows many times a second
                            // during a run. The OPENING is not governed by it
                            // at all -- on the first frame the list is at the top
                            // purely because it has just been laid out, so
                            // "is the reader at the tail" is false before anyone
                            // has read anything, and a conditional scroll does
                            // nothing. That left the sheet OPENING AT THE OLDEST
                            // LINE, twice over: first because nothing was laid
                            // out to measure, and again once the wait was added
                            // and the measurement returned "not at the tail".
                            //
                            // `tailChosen` is remembered OUTSIDE the effect, so
                            // it survives the effect restarting on every size
                            // change -- declared inside it, every arriving line
                            // would look like a first frame and yank the reader
                            // back down. It is inside `if (showLogSheet)`, so
                            // closing and reopening the sheet decides afresh.
                            var tailChosen by remember { mutableStateOf(false) }
                            LaunchedEffect(visible.size) {
                                // Wait for a real layout before asking anything:
                                // on the opening frame `layoutInfo` is empty.
                                snapshotFlow { listState.layoutInfo.visibleItemsInfo.size }
                                    .first { it > 0 }
                                if (!tailChosen) {
                                    tailChosen = true
                                    // Instant, not animated: the jump from the
                                    // top of a 2000-line log is not a
                                    // transition, it is an arrival.
                                    listState.scrollToItem(visible.size - 1)
                                } else {
                                    val last =
                                        listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                                    if (last != null && DiagLog.shouldStickToTail(last, visible.size)) {
                                        // Animated, because this one is a line or
                                        // two arriving and the movement is the point.
                                        listState.animateScrollToItem(visible.size - 1)
                                    }
                                }
                            }
                            // Breathing room before the log. At a 2x font the
                            // list is scrolled near its end, so its first row
                            // is half a line tall and sat directly against the
                            // buttons -- legible, but reading as one run-on
                            // block rather than a control and its output.
                            Spacer(modifier = Modifier.height(4.dp))
                            // heightIn, NOT height. A fixed 420dp holds a
                            // handful of lines once the user turns their font
                            // up, and the sheet's own column does not scroll,
                            // so a short log should be allowed to be short.
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 420.dp)
                            ) {
                                // Was items(logLines.size) { i -> Text(logLines[i]) — the
                                // count is captured when the scope runs but the read
                                // happens later, lazily, while Clear replaces the
                                // MutableState under the list's feet -> IOOBE.
                                items(visible) { line ->
                                    Text(
                                        line,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = diagLevelTint(DiagLog.levelOf(line))
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

/**
 * The colour a log line wears, by severity.
 *
 * The only real question this screen exists to answer is "what went wrong", and
 * with 2000 lines in one grey the answer is a needle in a haystack you have to
 * read with a thumb. So the level is carried by hue as well as by the letter
 * already in the text.
 *
 * **A line with no level gets the neutral colour, not a guess.** `DiagLog`
 * returns null for a line this app did not write, and inventing a severity for
 * it would be the one thing a diagnostic view must not do.
 *
 * The hues are the app's own `DvRole` accents, which `AccentContrastTest` pins
 * against the sheet surface in all three themes — the pairing is measured, not
 * eyeballed, and a role that failed would fail the build rather than ship.
 */
@Composable
private fun diagLevelTint(level: Char?): Color = when (level) {
    'E' -> dvAccent(DvRole.Error)
    'W' -> dvAccent(DvRole.Warning)
    'I' -> dvAccent(DvRole.Info)
    // D and V are the narration. They are deliberately NOT dimmed: a reader who
    // has asked for debug output is looking for it, and fading it to make room
    // for the severity colours would punish the only person who asked for it.
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** One severity control. Tapping the active level returns to showing all. */
@Composable
private fun DiagLevelChip(
    label: String,
    selected: Boolean,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) tint.copy(alpha = 0.20f)
        else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (selected) tint else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.height(30.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 11.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
