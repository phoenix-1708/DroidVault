package com.phoenix.droidvault.ui.screens

import android.content.pm.PackageManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.activity.compose.rememberLauncherForActivityResult
import com.phoenix.droidvault.ui.safeToast
import com.phoenix.droidvault.models.VaultTransfer
import android.widget.Toast
import android.net.Uri
import java.io.File
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Download
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.RemoveDone
import androidx.compose.material.icons.filled.RestorePage
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsPhone
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.phoenix.droidvault.MainViewModel
import com.phoenix.droidvault.models.AppInfo
import com.phoenix.droidvault.models.AppParts
import com.phoenix.droidvault.models.ChangeDetection
import com.phoenix.droidvault.models.AppState
import com.phoenix.droidvault.models.MigratorMode
import com.phoenix.droidvault.ui.components.AnimatedFilterChip
import com.phoenix.droidvault.ui.components.AppListItem
import com.phoenix.droidvault.ui.components.ExpressiveRefreshIndicator
import com.phoenix.droidvault.ui.components.MenuCard
import com.phoenix.droidvault.ui.theme.DvRole
import com.phoenix.droidvault.ui.theme.dvAccent
import com.phoenix.droidvault.ui.components.ShimmerAppListItem
import com.phoenix.droidvault.utils.BackupConfigFolders
import com.phoenix.droidvault.utils.MigratorManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.phoenix.droidvault.utils.getAvatarColor
import coil.compose.AsyncImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import java.util.Locale
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun MigratorTab(
    appList: List<AppInfo>,
    viewModel: MainViewModel,
    labelMap: Map<String, Set<String>> = emptyMap(),
    labelNames: Map<String, String> = emptyMap()
) {
    // The screen BODY runs on the deduplicated shell flow, so the app list (hundreds
    // of rows) no longer recomposes on every engine progress tick. The raw flow
    // is subscribed to only by MigratorActionScreen's progress panel, which is
    // the one place that renders currentStep/progress.
    val liveAppState by viewModel.shellUiState.collectAsStateWithLifecycle()
    // The component page is a layer ABOVE whichever submenu is showing, not a
    // mode of its own: it must keep the mode underneath intact so Back returns
    // to the app list the user came from rather than to the Migrate menu.
    val editorPackage by viewModel.componentEditorPackage.collectAsStateWithLifecycle()
    val detailApps by viewModel.detailApps.collectAsStateWithLifecycle()

    AnimatedContent(
        targetState = Triple(
            liveAppState.migratorMode == MigratorMode.MENU,
            editorPackage != null,
            liveAppState.migratorMode
        ),
        transitionSpec = {
            val (wasMenu, hadEditor) = initialState
            val (isMenu, hasEditor) = targetState
            when {
                // Opening or closing the component page slides over the list.
                hadEditor != hasEditor ->
                    (slideInHorizontally(tween(220, easing = FastOutSlowInEasing)) { it } +
                            fadeIn(tween(180))) togetherWith
                            (slideOutHorizontally(tween(200, easing = FastOutLinearInEasing)) { it } +
                                    fadeOut(tween(140)))

                // Back out of a submenu to the menu: no trailing slide, for the
                // same reason the Auto tab skips it (double animation).
                !wasMenu && isMenu -> EnterTransition.None togetherWith ExitTransition.None
                else -> slideInHorizontally(tween(150, easing = FastOutSlowInEasing)) { it } togetherWith
                        fadeOut(tween(130))
            }
        },
        label = "MigratorTransition"
    ) { (isMenu, hasEditor, _) ->
        if (hasEditor) {
            val app = detailApps.firstOrNull { it.packageName == editorPackage }
            if (app != null) {
                AppComponentScreen(
                    app = app,
                    mode = liveAppState.migratorMode,
                    selected = viewModel.pageSelection(app),
                    selectable = viewModel.selectableComponents(app),
                    freeKb = liveAppState.storageFreeKb,
                    allowPermissions = liveAppState.allowPermRestore,
                    allowSsaid = liveAppState.allowSsaidRestore,
                    onToggle = { viewModel.toggleAppComponent(app.packageName, it) },
                    onSetAll = { viewModel.setAllAppComponents(app.packageName, it) },
                    onBack = { viewModel.closeComponentEditor() }
                )
            } else {
                // The app vanished mid-edit (a refresh, a vault move). Render
                // nothing rather than a blank page; the shell's Back handler
                // closes it.
                Box(Modifier.fillMaxSize())
            }
        } else if (isMenu) {
            MigratorMenu(viewModel)
        } else {
            MigratorActionScreen(liveAppState, appList, viewModel, labelMap, labelNames)
        }
    }
}

/** The Folders dialog row label carries a count — match both forms. */
private fun isFolderLabel(label: String): Boolean = label == "Folders" || label.startsWith("Folders (")

@Composable
fun MigratorMenu(viewModel: MainViewModel) {
    val context = LocalContext.current
    val availableBackups by viewModel.availableDeviceBackups.collectAsStateWithLifecycle()

    var showDeviceBackupDialog by remember { mutableStateOf(false) }
    var showDeviceRestoreDialog by remember { mutableStateOf(false) }
    var showPermissionWarning by remember { mutableStateOf(false) }
    var pendingDeviceAction by remember { mutableStateOf<Pair<Boolean, List<Boolean>>?>(null) }

    // A Home shortcut asked to open one of the device-data dialogs. Consumed
    // exactly once so dismissing it does not resurrect it on the next visit,
    // and so a recomposition mid-show cannot re-trigger the launch.
    val deviceDialogRequest by viewModel.deviceDialogRequest.collectAsStateWithLifecycle()
    LaunchedEffect(deviceDialogRequest) {
        val backup = deviceDialogRequest ?: return@LaunchedEffect
        viewModel.consumeDeviceDialogRequest()
        if (backup) showDeviceBackupDialog = true else showDeviceRestoreDialog = true
    }

    // --- Separate Folders section (own cards + dialogs, not Device Data) ---
    var showFolderBackupDialog by remember { mutableStateOf(false) }
    var showFolderRestoreDialog by remember { mutableStateOf(false) }
    var folderBackupSelection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var folderRestoreSelection by remember { mutableStateOf<Set<String>>(emptySet()) }

    var doSms by remember { mutableStateOf(true) }
    var doCall by remember { mutableStateOf(true) }
    var doContacts by remember { mutableStateOf(false) }
    var doWifi by remember { mutableStateOf(true) }
    var doWallpaper by remember { mutableStateOf(true) }
    var doBluetooth by remember { mutableStateOf(true) }
    var doFolders by remember { mutableStateOf(false) }
    val configuredFolders by viewModel.backupFolders.collectAsStateWithLifecycle()
    val archivedFolders by viewModel.archivedFolders.collectAsStateWithLifecycle()

    // Real system permission dialogs for Device Data runs (clean-install bug:
    // no popup ever appeared; root silently granted everything). Whatever the
    // user grants now is used directly; whatever is denied is still root-
    // granted at run start, so a denial never blocks the run.
    val devicePermLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) {
        pendingDeviceAction?.let { (isBackup, flags) ->
            val wanted = buildList {
                if (flags[0]) addAll(com.phoenix.droidvault.utils.PermissionChecker.SMS_RUNTIME)
                if (flags[1]) addAll(com.phoenix.droidvault.utils.PermissionChecker.CALL_LOG_RUNTIME)
                if (flags[2]) addAll(com.phoenix.droidvault.utils.PermissionChecker.CONTACTS_RUNTIME)
            }.toTypedArray()
            if (com.phoenix.droidvault.utils.PermissionChecker.missing(context, wanted).isEmpty()) {
                pendingDeviceAction = null
                viewModel.runDeviceDataOperation(
                    context, isBackup,
                    flags[0], flags[1], flags[2], flags[3], flags[4], flags[5],
                    doFolders, configuredFolders
                )
            } else {
                // Still missing after the system dialogs (denied / "don't ask
                // again"): explain the root fallback before starting.
                showPermissionWarning = true
            }
        }
    }

    LaunchedEffect(showDeviceRestoreDialog, showDeviceBackupDialog) {
        if (showDeviceRestoreDialog) {
            viewModel.refreshDeviceBackups()
            doSms = false; doCall = false; doContacts = false
            doWifi = false; doWallpaper = false; doBluetooth = false
            doFolders = false
        } else if (showDeviceBackupDialog) {
            doSms = true; doCall = true; doContacts = false
            doWifi = true; doWallpaper = true; doBluetooth = true
            doFolders = false
        }
    }

    LaunchedEffect(availableBackups, showDeviceRestoreDialog) {
        if (showDeviceRestoreDialog) {
            if (availableBackups.contains("Messages.shift")) doSms = true
            if (availableBackups.contains("CallLogs.shift")) doCall = true
            if (availableBackups.contains("Contacts.shift")) doContacts = true
            if (availableBackups.contains("Wi-Fi.shift")) doWifi = true
            if (availableBackups.contains("Wallpaper.shift")) doWallpaper = true
            if (availableBackups.contains("Bluetooth.shift")) doBluetooth = true
            if (availableBackups.contains(BackupConfigFolders.ARCHIVE_FILE)) doFolders = true
        }
    }

    LaunchedEffect(showFolderRestoreDialog) {
        if (showFolderRestoreDialog) {
            viewModel.refreshArchivedFolders()
        }
    }

    LaunchedEffect(configuredFolders, showFolderBackupDialog) {
        if (showFolderBackupDialog) {
            folderBackupSelection = configuredFolders.toSet()
        }
    }

    LaunchedEffect(archivedFolders, showFolderRestoreDialog) {
        if (showFolderRestoreDialog) {
            folderRestoreSelection = archivedFolders.toSet()
        }
    }

    if (showFolderBackupDialog) {
        AlertDialog(
            shape = MaterialTheme.shapes.large,
            onDismissRequest = { showFolderBackupDialog = false },
            icon = {
                Icon(
                    Icons.Default.Folder,
                    null,
                    tint = dvAccent(DvRole.Storage),
                    modifier = Modifier.size(28.dp)
                )
            },
            title = { Text("Backup Folders") },
            text = {
                Column {
                    if (configuredFolders.isEmpty()) {
                        Text(
                            "No folders registered yet.\n\nAdd folders in Settings → Backup Folders first. Root access lets backups include Android/data and Android/obb.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        Text(
                            "Packed root-side — Android/data and Android/obb included.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        // Short names in the list; the full path rides as the
                        // row's content description (accessibility) so nothing
                        // is lost.
                        BackupConfigFolders.setDisplayContext(configuredFolders)
                        configuredFolders.forEach { folder ->
                            val checked = folder in folderBackupSelection
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.medium)
                                    .clickable {
                                        folderBackupSelection =
                                            if (checked) folderBackupSelection - folder else folderBackupSelection + folder
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                Text(
                                    BackupConfigFolders.shortDisplayName(folder),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.semantics {
                                        contentDescription = folder
                                    }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = configuredFolders.isNotEmpty() && folderBackupSelection.isNotEmpty(),
                    onClick = {
                        showFolderBackupDialog = false
                        viewModel.runFoldersBackup(context, folderBackupSelection.toList())
                    }
                ) { Text("Backup") }
            },
            dismissButton = {
                TextButton(onClick = { showFolderBackupDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showFolderRestoreDialog) {
        AlertDialog(
            shape = MaterialTheme.shapes.large,
            onDismissRequest = { showFolderRestoreDialog = false },
            icon = {
                Icon(
                    Icons.Default.SettingsPhone,
                    null,
                    tint = dvAccent(DvRole.Restore),
                    modifier = Modifier.size(28.dp)
                )
            },
            title = { Text("Restore Folders") },
            text = {
                Column {
                    if (archivedFolders.isEmpty()) {
                        Text(
                            "No folder archive found in this backup folder.\n\nRun \u201cBackup Folders\u201d first — the archive lists exactly what it holds.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        Text(
                            "Each folder is restored to its original location from the archive's manifest.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        BackupConfigFolders.setDisplayContext(archivedFolders)
                        archivedFolders.forEach { folder ->
                            val checked = folder in folderRestoreSelection
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.medium)
                                    .clickable {
                                        folderRestoreSelection =
                                            if (checked) folderRestoreSelection - folder else folderRestoreSelection + folder
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                Text(
                                    BackupConfigFolders.shortDisplayName(folder),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.semantics {
                                        contentDescription = folder
                                    }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = archivedFolders.isNotEmpty() && folderRestoreSelection.isNotEmpty(),
                    onClick = {
                        showFolderRestoreDialog = false
                        viewModel.runFoldersRestore(context, folderRestoreSelection.toList())
                    }
                ) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = { showFolderRestoreDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showPermissionWarning) {
        AlertDialog(
            shape = MaterialTheme.shapes.large,
            onDismissRequest = { showPermissionWarning = false },
            icon = {
                Icon(
                    Icons.Default.Security,
                    contentDescription = null,
                    tint = dvAccent(DvRole.Warning),
                    modifier = Modifier.size(28.dp)
                )
            },
            title = { Text("Permissions Required") },
            text = {
                Text(
                    "Some permissions were not granted. Droid Vault will grant the required native Android permissions via root to back up / restore SMS, Call Logs, or Contacts. Do you want to continue?",
                    style = MaterialTheme.typography.bodyLarge
                )
            },
            confirmButton = {
                Button(onClick = {
                    showPermissionWarning = false
                    // Close the selection dialog too: the run starts NOW and
                    // the Processing screen must not stay hidden underneath
                    // this dialog (field-reported overlay bug).
                    showDeviceBackupDialog = false
                    showDeviceRestoreDialog = false
                    pendingDeviceAction?.let { (isBackup, flags) ->
                        pendingDeviceAction = null
                        viewModel.runDeviceDataOperation(
                            context,
                            isBackup,
                            flags[0],
                            flags[1],
                            flags[2],
                            flags[3],
                            flags[4],
                            flags[5],
                            doFolders,
                            configuredFolders
                        )
                    }
                }) { Text("Yes, Start") }
            },
            dismissButton = { TextButton(onClick = { showPermissionWarning = false }) { Text("Cancel") } }
        )
    }

    // Hoisted out of the dialog's `text` lambda so the confirm and dismiss buttons
    // can see whether there is anything to act on at all.
    val deviceOptions = mutableListOf(
        "Messages" to doSms,
        "Calls" to doCall,
        "Contacts" to doContacts,
        "Wi-Fi" to doWifi,
        "Wallpaper" to doWallpaper,
        "Bluetooth" to doBluetooth
    )
    // Whether a restore has anything to restore from. Computed here, not inside
    // the dialog's `text` lambda, because AlertDialog composes its buttons before
    // its text: the flag would otherwise be read before `retainAll` filtered the
    // list, and a Restore button would be live on an empty vault.
    val nothingAvailable = if (showDeviceRestoreDialog) {
        deviceOptions.none { (label, _) ->
            val fileName = when (label) {
                "Messages" -> "Messages.shift"
                "Calls" -> "CallLogs.shift"
                "Contacts" -> "Contacts.shift"
                "Wi-Fi" -> "Wi-Fi.shift"
                "Wallpaper" -> "Wallpaper.shift"
                "Bluetooth" -> "Bluetooth.shift"
                else -> ""
            }
            fileName.isNotEmpty() && availableBackups.contains(fileName)
        }
    } else {
        false
    }
    if (showDeviceBackupDialog || showDeviceRestoreDialog) {
        val isBackup = showDeviceBackupDialog
        AlertDialog(
            shape = MaterialTheme.shapes.large,
            onDismissRequest = { showDeviceBackupDialog = false; showDeviceRestoreDialog = false },
            icon = {
                Icon(
                    if (isBackup) Icons.Default.CloudUpload else Icons.Default.SettingsPhone,
                    null,
                    tint = dvAccent(DvRole.Device),
                    modifier = Modifier.size(28.dp)
                )
            },
            title = { Text(if (isBackup) "Backup Device Data" else "Restore Device Data") },
            text = {
                Column {
                    // Folders is deliberately NOT offered here: the Migrate tab
                    // has a dedicated Backup/Restore Folders section with its own
                    // per-folder picker — a second control for the same archive
                    // only invites double selects and confusion.

                    if (!isBackup) {
                        deviceOptions.retainAll { (label, _) ->
                            val fileName = when (label) {
                                "Messages" -> "Messages.shift"
                                "Calls" -> "CallLogs.shift"
                                "Contacts" -> "Contacts.shift"
                                "Wi-Fi" -> "Wi-Fi.shift"
                                "Wallpaper" -> "Wallpaper.shift"
                                "Bluetooth" -> "Bluetooth.shift"
                                else -> ""
                            }
                            availableBackups.contains(fileName) ||
                                (label.startsWith("Folders") && availableBackups.contains(BackupConfigFolders.ARCHIVE_FILE))
                        }
                    }

                    if (nothingAvailable) {
                        // Restoring with nothing to restore from. The old text was a
                        // red one-liner that named no cause, and the Restore button
                        // below stayed enabled: pressing it closed the dialog and did
                        // nothing at all, indistinguishable from "already done".
                        // Say what is missing and where to get it.
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                if (isBackup) "Nothing to save yet"
                                else "Nothing to restore from",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                if (isBackup)
                                    "This folder has no previous device backup, so there is " +
                                        "nothing to overwrite. Choose Backup to create one."
                                else
                                    "This folder has no device backup, so there is nothing to " +
                                        "restore. Run Backup Device Data first, or check that " +
                                        "Droid Vault is using the folder you backed up to.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    deviceOptions.forEach { (label, state) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(MaterialTheme.shapes.medium)
                                .background(
                                    if (state) MaterialTheme.colorScheme.secondaryContainer.copy(
                                        alpha = 0.5f
                                    ) else Color.Transparent
                                )
                                .clickable {
                                    when {
                                        label == "Messages" -> doSms = !doSms
                                        label == "Calls" -> doCall = !doCall
                                        label == "Contacts" -> doContacts = !doContacts
                                        label == "Wi-Fi" -> doWifi = !doWifi
                                        label == "Wallpaper" -> doWallpaper = !doWallpaper
                                        label == "Bluetooth" -> doBluetooth = !doBluetooth
                                        isFolderLabel(label) -> doFolders = !doFolders
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(label, style = MaterialTheme.typography.titleMedium)
                            Switch(
                                checked = state,
                                onCheckedChange = null,
                                thumbContent = { Icon(imageVector = if (state) Icons.Filled.Check else Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (doSms || doCall || doContacts || doWifi || doWallpaper || doBluetooth || doFolders) {

                        // Ask through the REAL system dialogs first (the ask
                        // itself was missing on clean installs). Denials fall
                        // back to the root auto-grant before the run starts.
                        val wanted = buildList {
                            if (doSms) addAll(com.phoenix.droidvault.utils.PermissionChecker.SMS_RUNTIME)
                            if (doCall) addAll(com.phoenix.droidvault.utils.PermissionChecker.CALL_LOG_RUNTIME)
                            if (doContacts) addAll(com.phoenix.droidvault.utils.PermissionChecker.CONTACTS_RUNTIME)
                        }.toTypedArray()
                        val missing = com.phoenix.droidvault.utils.PermissionChecker.missing(context, wanted)

                        showDeviceBackupDialog = false
                        showDeviceRestoreDialog = false
                        if (missing.isNotEmpty()) {
                            pendingDeviceAction = Pair(
                                isBackup,
                                listOf(doSms, doCall, doContacts, doWifi, doWallpaper, doBluetooth)
                            )
                            devicePermLauncher.launch(missing)
                        } else {
                            viewModel.runDeviceDataOperation(
                                context,
                                isBackup,
                                doSms,
                                doCall,
                                doContacts,
                                doWifi,
                                doWallpaper,
                                doBluetooth,
                                doFolders,
                                configuredFolders
                            )
                        }
                    } else {
                        // Nothing ticked: honour Cancel rather than closing silently,
                        // so a tap can no longer look like it did something.
                        showDeviceBackupDialog = false
                        showDeviceRestoreDialog = false
                        context.safeToast("Nothing selected")
                    }
                },
                // A Restore button that is live with nothing to restore is the same
                // dead affordance this dialog had before the text above changed.
                enabled = !nothingAvailable
                ) { Text(if (isBackup) "Backup" else "Restore") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDeviceBackupDialog = false; showDeviceRestoreDialog = false
                }) { Text(if (nothingAvailable) "Close" else "Cancel") }
            }
        )
    }
    Column(modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .padding(horizontal = 16.dp)) {
        Column(modifier = Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState())) {
            Spacer(modifier = Modifier.height(12.dp))
            MenuCard("Backup Apps", Icons.Default.CloudUpload, "Your apps, with their data", DvRole.Backup) { viewModel.setMigratorMode(MigratorMode.BACKUP_APPS) }
            MenuCard("Restore Apps", Icons.Default.RestorePage, "Put your apps back from the vault", DvRole.Restore) { viewModel.setMigratorMode(MigratorMode.RESTORE_APPS) }
            MenuCard(
                "Backup Device Data",
                Icons.Default.Sms,
                // "ROM Data" is not a thing a user recognises. It means Wi-Fi,
                // wallpaper and Bluetooth, so name all seven items instead.
                "Messages, calls, contacts, Wi-Fi, wallpaper, Bluetooth",
                DvRole.Device
            ) { showDeviceBackupDialog = true }
            MenuCard(
                "Restore Device Data",
                Icons.Default.SettingsPhone,
                "Put messages, calls and contacts back",
                DvRole.Device
            ) { showDeviceRestoreDialog = true }
            MenuCard(
                "Backup Folders",
                Icons.Default.Folder,
                "Backup custom folders — Android/data included (root)",
                DvRole.Storage
            ) { showFolderBackupDialog = true }
            MenuCard(
                "Restore Folders",
                Icons.Default.RestorePage,
                "Restore folders from the Folders archive",
                DvRole.Storage
            ) { showFolderRestoreDialog = true }
            MenuCard(
                // A red bin for a menu that mostly REVIEWS sends the wrong signal.
                // The red Delete button inside it is where the destructive colour
                // belongs.
                "Manage Backups",
                // Icons.Default.Layers — already resolved elsewhere in this file,
                // so it needs no new import, and it reads as "a set of things"
                // rather than the destructive red bin the menu used to wear.
                Icons.Default.Layers,
                "See, verify and delete your backups",
                DvRole.Storage
            ) { viewModel.setMigratorMode(MigratorMode.MANAGE) }
            Spacer(modifier = Modifier.height(100.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MigratorActionScreen(
    appState: AppState,
    appList: List<AppInfo>,
    viewModel: MainViewModel,
    labelMap: Map<String, Set<String>> = emptyMap(),
    labelNames: Map<String, String> = emptyMap()
) {
    val context = LocalContext.current

    // The ONE deliberate raw-state subscription in this screen. The bottom
    // progress panel renders currentStep/progress, which the shell flow
    // deliberately zeroes; subscribing here confines that per-tick invalidation
    // to the panel instead of the whole screen and its app list. MigratorTab,
    // ToolsTab and ToolsScreen all pass a deduplicated state down, so the list
    // above is stable between ticks.
    val progressState by viewModel.uiState.collectAsStateWithLifecycle()

    // Hoisted: the per-folder rows below used to call collectAsState() from
    // inside a forEach body, creating a fresh subscription per row on every
    // recomposition.
    val archivedFolders by viewModel.archivedFolders.collectAsStateWithLifecycle()

    val smsLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { viewModel.setDefaultSmsHandled() }

    LaunchedEffect(appState.requestDefaultSms) {
        if (appState.requestDefaultSms) {
            val intent =
                android.content.Intent(android.provider.Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
            intent.putExtra(
                android.provider.Telephony.Sms.Intents.EXTRA_PACKAGE_NAME,
                context.packageName
            )
            smsLauncher.launch(intent)
        }
    }


    // A plain memo, NOT derivedStateOf: the block reads no snapshot state (every
    // input is a captured key), so derivedStateOf could never invalidate and
    // only looked reactive. migratorMode was read inside but missing from the
    // key list, which let the memo hand a Debloat-mode list to the Systemize
    // screen. It is now a key, and the list is a stable reference between ticks.
    val filteredApps = remember(
        appList,
        appState.searchQuery,
        appState.showSystemApps,
        appState.showPartialSystemApps,
        appState.actionFilterState,
        appState.filterLabelId,
        appState.migratorMode,
        labelMap
    ) {
        run {
            appList.filter { app ->
                val matchesSearch = app.label.contains(
                    appState.searchQuery,
                    ignoreCase = true
                ) || app.packageName.contains(appState.searchQuery, ignoreCase = true)

                val matchesLabel = appState.filterLabelId == null ||
                    labelMap[app.packageName]?.contains(appState.filterLabelId) == true

                val isDebloatedMode =
                    appState.migratorMode == MigratorMode.DEBLOAT && appState.actionFilterState == 2

                val matchesType = isDebloatedMode || if (app.isSystem) {
                    appState.showSystemApps || (appState.showPartialSystemApps && app.isLaunchable)
                } else true

                val matchesAction = when (appState.migratorMode) {
                    MigratorMode.SYSTEMIZE -> {
                        if (appState.actionFilterState == 1) !app.isSystem && !app.isSystemized else app.isSystemized
                    }

                    MigratorMode.DEBLOAT -> {
                        if (appState.actionFilterState == 1) app.isInstalled else !app.isInstalled
                    }

                    else -> true
                }

                matchesSearch && matchesLabel && matchesType && matchesAction
            }
        }
    }

    var expandedChipLabel by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var timerJob by remember { mutableStateOf<Job?>(null) }

    fun expandChip(label: String) {
        expandedChipLabel = label
        timerJob?.cancel()
        timerJob = scope.launch {
            delay(3000.milliseconds)
            expandedChipLabel = null
        }
    }

    var showComponentHelp by remember { mutableStateOf(false) }
    var showTrash by remember { mutableStateOf(false) }

    // Import entry point for cross-device transfer. The picked URI is copied
    // into the app's own cache first: the SAF handle is not a filesystem path
    // this app's root shell can read, and the vault cannot be read through SAF
    // either (scoped storage).
    // OpenDocument, not GetContent, and with the archive's real MIME types.
    //
    // Nothing on Android can *open* a .tar.gz, so a picker left to guess opened
    // an "Open with / Unable to preview file" sheet instead of returning the
    // file — the transfer could not be selected at all. Naming gzip/x-gzip/x-tar
    // says what we want to READ rather than what someone might want to launch.
    val pickTransfer = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        // The staged copy is done by the ViewModel, on IO, with a progress bar.
        // It used to happen HERE, on the main thread, before any running state
        // existed — so a real multi-gigabyte archive froze the UI silently.
        viewModel.importVaultFromUri(context, uri)
    }
    val pendingConfigs by viewModel.pendingConfigs.collectAsStateWithLifecycle()
    val isImporting by viewModel.isImporting.collectAsStateWithLifecycle()
    val isExporting by viewModel.isExporting.collectAsStateWithLifecycle()
    val trash by viewModel.trash.collectAsStateWithLifecycle()
    val verification by viewModel.verification.collectAsStateWithLifecycle()
    val isVerifying by viewModel.isVerifying.collectAsStateWithLifecycle()
    val verificationDialogVisible by viewModel.verificationDialogVisible
        .collectAsStateWithLifecycle()
    // The transfer carried a configuration and this phone already has one.
    // Not applied on import: replacing what the user has set up on the phone in
    // front of them, as a side effect of importing some backups, is exactly the
    // surprise this app is not allowed to produce (invariant 24).
    pendingConfigs?.let { carried ->
        AlertDialog(
            shape = MaterialTheme.shapes.large,
            onDismissRequest = { viewModel.dismissCarriedConfigs() },
            icon = {
                Icon(
                    Icons.Default.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = { Text("Backup setups in this file") },
            text = {
                Text(
                    "This transfer also carries ${carried.describe()} from the phone it " +
                        "came from. This phone already has setups of its own, so nothing " +
                        "has been changed.\n\nTaking the ones from the file replaces what " +
                        "is here now. The current ones are moved to the trash folder " +
                        "first, so they can be recovered from there.",
                    style = MaterialTheme.typography.bodyLarge
                )
            },
            confirmButton = {
                Button(onClick = { viewModel.adoptCarriedConfigs(context) }) {
                    Text("Use the file's")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissCarriedConfigs() }) {
                    Text("Keep this phone's")
                }
            }
        )
    }
    var showDeviceDeleteDialog by remember { mutableStateOf(false) }
    // One tap used to export straight away. Splitting needs a choice first —
    // one file or parts, and how big — so the chip opens this instead. The
    // choice persists: it describes the user's PC and cables, which do not
    // change between exports.
    var showExportDialog by remember { mutableStateOf(false) }
    if (showExportDialog) {
        var choice by remember { mutableIntStateOf(viewModel.splitChoice) }
        AlertDialog(
            shape = MaterialTheme.shapes.large,
            onDismissRequest = { showExportDialog = false },
            title = { Text("Export backups") },
            text = {
                Column {
                    Text(
                        "One big file, or smaller parts that are easier to move " +
                            "to a PC. Parts import the same way — pick any one " +
                            "of them.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(Modifier.height(12.dp))
                    viewModel.splitOptions.forEachIndexed { i, (label, _) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { choice = i }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = choice == i, onClick = { choice = i })
                            Spacer(Modifier.width(8.dp))
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.splitChoice = choice
                    showExportDialog = false
                    viewModel.exportVaultForTransfer(
                        context, viewModel.splitOptions[choice].second
                    )
                }) {
                    Text("Export")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
    // Nothing selected by default. Every one of these was `true`, so a user
    // who opened the dialog and tapped the confirm button without reading
    // moved EVERY device archive into the trash — found on device while
    // testing the undo path: tapping two rows OFF moved the other three.
    // A destructive dialog must start empty; confirming it should be the only
    // way anything is selected.
    var delSms by remember { mutableStateOf(false) }
    var delCall by remember { mutableStateOf(false) }
    var delContacts by remember { mutableStateOf(false) }
    var delWifi by remember { mutableStateOf(false) }
    var delWallpaper by remember { mutableStateOf(false) }
    var delBluetooth by remember { mutableStateOf(false) }
    var delFolders by remember { mutableStateOf(false) }
    // Reset on dismiss so the next open is empty again rather than carrying
    // the previous run's selection forward.
    LaunchedEffect(showDeviceDeleteDialog) {
        if (!showDeviceDeleteDialog) {
            delSms = false; delCall = false; delContacts = false; delWifi = false
            delWallpaper = false; delBluetooth = false; delFolders = false
        }
    }
    val delHasFolders = viewModel.availableDeviceBackups.collectAsStateWithLifecycle().value
        .contains(BackupConfigFolders.ARCHIVE_FILE)

    // The Folders row above is derived from that set: refresh it when the
    // erase dialog opens, so an archive written or deleted since the last look
    // (an automation run can do either) is reflected instead of showing a
    // stale row.
    LaunchedEffect(appState.migratorMode) {
        if (appState.migratorMode == MigratorMode.MANAGE) viewModel.refreshTrash()
    }

    LaunchedEffect(showDeviceDeleteDialog) {
        if (showDeviceDeleteDialog) viewModel.refreshDeviceBackups()
    }

    // Constant lookup tables — hoisted to file scope because rebuilding three
    // maps on every recomposition meant once per engine progress tick.

    if (showTrash) {
        TrashDialog(
            batches = trash,
            onRestore = { viewModel.restoreTrashBatch(it) },
            onPurge = { viewModel.purgeTrashBatch(it) },
            onDismiss = { showTrash = false }
        )
    }

    val report = verification
    if ((report != null || isVerifying) && verificationDialogVisible) {
        VerificationDialog(
            report = report,
            running = isVerifying,
            onDismiss = { viewModel.dismissVerificationDialog() }
        )
    }

    if (showComponentHelp) {
        ComponentHelpDialog(
            isRestore = appState.migratorMode == MigratorMode.RESTORE_APPS,
            onDismiss = { showComponentHelp = false }
        )
    }

    if (showDeviceDeleteDialog) {
        AlertDialog(
            shape = MaterialTheme.shapes.large,
            onDismissRequest = { showDeviceDeleteDialog = false },
            icon = {
                Icon(
                    Icons.Default.DeleteForever,
                    null,
                    tint = dvAccent(DvRole.Destructive),
                    modifier = Modifier.size(28.dp)
                )
            },
            title = { Text("Erase Native Backups") },
            text = {
                Column {
                    // State the outcome, do not imply erasure: a device archive
                    // is frequently the only copy left, which is the whole
                    // reason this moves instead of deleting.
                    Text(
                        "Selected items are moved to Recently deleted and stay " +
                            "recoverable for 7 days. Nothing is erased here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    val options = listOf(
                        "Messages" to delSms,
                        "Calls" to delCall,
                        "Contacts" to delContacts,
                        "Wi-Fi" to delWifi,
                        "Wallpaper" to delWallpaper,
                        "Bluetooth" to delBluetooth
                    ) + if (delHasFolders) listOf("Folders" to delFolders) else emptyList()
                    options.forEach { (label, state) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(MaterialTheme.shapes.medium)
                                .clickable {
                                    when (label) {
                                        "Messages" -> delSms = !delSms
                                        "Calls" -> delCall = !delCall
                                        "Contacts" -> delContacts = !delContacts
                                        "Wi-Fi" -> delWifi = !delWifi
                                        "Wallpaper" -> delWallpaper = !delWallpaper
                                        "Bluetooth" -> delBluetooth = !delBluetooth
                                        "Folders" -> delFolders = !delFolders
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(label, style = MaterialTheme.typography.titleMedium)
                                // Real content, never a generic "Folder": the
                                // Folders row names the archived folders and
                                // shows when the archive was written.
                                if (label == "Folders" && delHasFolders) {
                                    Text(
                                        if (archivedFolders.isEmpty()) BackupConfigFolders.ARCHIVE_FILE
                                        else archivedFolders.joinToString(
                                            ", "
                                        ) { BackupConfigFolders.shortDisplayName(it) },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Switch(
                                checked = state,
                                onCheckedChange = null,
                                thumbContent = {
                                    Icon(
                                        imageVector = if (state) Icons.Filled.Check else Icons.Filled.Close,
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize)
                                    )
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                val anySelected = delSms || delCall || delContacts || delWifi ||
                        delWallpaper || delBluetooth || delFolders
                Button(
                    enabled = anySelected,
                    onClick = {
                        if (anySelected) viewModel.deleteDeviceBackups(
                            context,
                            delSms,
                            delCall,
                            delContacts,
                            delWifi,
                            delWallpaper,
                            delBluetooth,
                            delFolders
                        )
                        showDeviceDeleteDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Move to Trash") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDeviceDeleteDialog = false
                }) { Text("Cancel") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Spacer(modifier = Modifier.height(14.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = appState.searchQuery,
                onValueChange = { viewModel.updateSearchQuery(it) },
                placeholder = {
                    Text(
                        "Search apps...",
                        style = MaterialTheme.typography.bodyLarge
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = "Search",
                        modifier = Modifier.size(22.dp)
                    )
                },
                singleLine = true,
                shape = CircleShape,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            val allSelected = filteredApps.isNotEmpty() && filteredApps.all { it.isSelected }
            FilledTonalIconButton(
                onClick = { viewModel.selectAllVisibleApps(!allSelected, filteredApps) },
                modifier = Modifier.size(52.dp),
                shape = CircleShape
            ) {
                Icon(
                    if (allSelected) Icons.Default.RemoveDone else Icons.Default.DoneAll,
                    contentDescription = "Select All",
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        Column(modifier = Modifier.padding(bottom = 8.dp)) {
            if (labelNames.isNotEmpty()) {
                // Wraps rather than scrolls: the label set is unbounded, so a
                // horizontal scroller hid labels past the edge with nothing to
                // suggest they were there — the same trap the component chips
                // had. Wrapping costs a second line only once a user actually
                // has that many labels.
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = appState.filterLabelId == null,
                        onClick = { viewModel.setLabelFilter(null) },
                        label = { Text("All") }
                    )
                    labelNames.toList().forEach { (id, name) ->
                        FilterChip(
                            selected = appState.filterLabelId == id,
                            onClick = {
                                viewModel.setLabelFilter(if (appState.filterLabelId == id) null else id)
                            },
                            label = { Text(name) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
            val showActionChip = when (appState.migratorMode) {
                MigratorMode.DEBLOAT, MigratorMode.SYSTEMIZE -> true
                else -> false
            }

            val showEraseDevice = appState.migratorMode == MigratorMode.MANAGE
            val hasButtons = showActionChip || showEraseDevice

            val hasComponents =
                appState.migratorMode == MigratorMode.BACKUP_APPS || appState.migratorMode == MigratorMode.RESTORE_APPS

            val showAppFilters =
                appState.migratorMode != MigratorMode.SYSTEMIZE && !(appState.migratorMode == MigratorMode.DEBLOAT && appState.actionFilterState == 2)

            val showPartialSystemFilter =
                appState.migratorMode == MigratorMode.BACKUP_APPS || appState.migratorMode == MigratorMode.DEBLOAT

            val showSmartSelect = when (appState.migratorMode) {
                MigratorMode.BACKUP_APPS, MigratorMode.RESTORE_APPS, MigratorMode.MANAGE -> true
                MigratorMode.DEBLOAT -> appState.actionFilterState == 1
                else -> false
            }

            if (hasComponents) {
                ComponentPickerCard(
                    isRestore = appState.migratorMode == MigratorMode.RESTORE_APPS,
                    defaultComponents = appState.globalComponents,
                    hasSelection = appState.selectedRunCount > 0 ||
                            appList.any { it.isSelected },
                    matchesAllSelected = remember(
                        appList, appState.globalComponents, appState.migratorMode
                    ) {
                        val sel = appList.filter { it.isSelected }
                        sel.isNotEmpty() && sel.all {
                            (it.activeComponents ?: appState.globalComponents) ==
                                    appState.globalComponents
                        }
                    },
                    onToggleComponent = { viewModel.toggleGlobalComponent(it) },
                    onApplyAll = {
                        viewModel.applyComponentsToAllSelected(appState.globalComponents)
                    },
                    onShowHelp = { showComponentHelp = true }
                )
            }

            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showEraseDevice) {
                    item {
                        // Verify reads every archive back. Without it a corrupt
                        // archive is discovered at restore time, which is the one
                        // moment it is least useful.
                        AnimatedFilterChip(
                            selected = isVerifying,
                            onClick = { viewModel.verifyVault() },
                            label = if (isVerifying) "Verifying…" else "Verify backups",
                            leadingIcon = Icons.Default.VerifiedUser,
                            showLabel = expandedChipLabel == "Verify backups",
                            onExpand = { expandChip("Verify backups") },
                            collapsible = false,
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    item {
                        AnimatedFilterChip(
                            // Was hard-coded false, so the chip never lit up and a
                            // tap left no trace anywhere. Bind it to the run state so
                            // an in-flight operation is visible on the control the
                            // user just pressed.
                            selected = appState.isRunning,
                            onClick = { showDeviceDeleteDialog = true },
                            label = "Erase Native",
                            leadingIcon = Icons.Default.SettingsPhone,
                            showLabel = expandedChipLabel == "Erase Native",
                            onExpand = { expandChip("Erase Native") },
                            selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                            selectedContentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }

                if (appState.migratorMode == MigratorMode.MANAGE) {
                    // Cross-device transfer. Export is one tap (it writes a single
                    // file into Downloads the user then moves); import needs a
                    // picker because the file is wherever the user put it.
                    item {
                        AnimatedFilterChip(
                            // Export can take minutes. Showing it as "working" is
                            // the only feedback the user gets while it runs.
                            selected = appState.isRunning,
                            onClick = { showExportDialog = true },
                            label = "Export",
                            leadingIcon = Icons.Default.IosShare,
                            showLabel = expandedChipLabel == "Export",
                            onExpand = { expandChip("Export") },
                            collapsible = false
                        )
                    }
                    item {
                        AnimatedFilterChip(
                            selected = false,
                            onClick = {
                                pickTransfer.launch(
                                    arrayOf(
                                        "application/gzip",
                                        "application/x-gzip",
                                        "application/x-tar",
                                        "application/tar",
                                        "application/octet-stream",
                                        "*/*"
                                    )
                                )
                            },
                            label = "Import",
                            leadingIcon = Icons.Default.Download,
                            showLabel = expandedChipLabel == "Import",
                            onExpand = { expandChip("Import") },
                            collapsible = false
                        )
                    }
                }
                if (showActionChip) {
                    if (appState.migratorMode == MigratorMode.DEBLOAT) {
                        item {
                            AnimatedFilterChip(
                                selected = appState.actionFilterState == 1,
                                onClick = { viewModel.setActionFilter(1) },
                                label = "Debloat",
                                leadingIcon = Icons.Default.Delete,
                                showLabel = expandedChipLabel == "Debloat",
                                onExpand = { expandChip("Debloat") }
                            )
                        }
                        item {
                            AnimatedFilterChip(
                                selected = appState.actionFilterState == 2,
                                onClick = { viewModel.setActionFilter(2) },
                                label = "Restore",
                                leadingIcon = Icons.Default.RestorePage,
                                showLabel = expandedChipLabel == "Restore",
                                onExpand = { expandChip("Restore") }
                            )
                        }
                    } else if (appState.migratorMode == MigratorMode.SYSTEMIZE) {
                        item {
                            AnimatedFilterChip(
                                selected = appState.actionFilterState == 1,
                                onClick = { viewModel.setActionFilter(1) },
                                label = "Systemize",
                                leadingIcon = Icons.Default.Settings,
                                showLabel = expandedChipLabel == "Systemize",
                                onExpand = { expandChip("Systemize") }
                            )
                        }
                        item {
                            AnimatedFilterChip(
                                selected = appState.actionFilterState == 2,
                                onClick = { viewModel.setActionFilter(2) },
                                label = "De-Systemize",
                                leadingIcon = Icons.Default.Build,
                                showLabel = expandedChipLabel == "De-Systemize",
                                onExpand = { expandChip("De-Systemize") }
                            )
                        }
                    }
                }

                // Component parts moved OUT of this row into a wrapping card
                // below. They are independent toggles with no mutual exclusion,
                // so squeezing five labelled pills into a horizontal scroller
                // meant the last three were only reachable by swiping a row
                // that gave no hint there was more.

                if (hasButtons && (showSmartSelect || showAppFilters)) {
                    item {
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .width(1.dp)
                                .height(24.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant)
                        )
                    }
                }

                if (showSmartSelect) {
                    // Only where parts exist to be compared. Debloat/Systemize
                    // act on whole packages, so "changed" is meaningless there.
                    if (hasComponents) {
                        item {
                            val changedCount = remember(appList, appState.globalComponents) {
                                appList.count {
                                    !ChangeDetection.isUpToDate(it, appState.globalComponents)
                                }
                            }
                            AnimatedFilterChip(
                                selected = false,
                                onClick = { viewModel.selectChangedApps(filteredApps) },
                                label = if (changedCount == 0) "All up to date" else "Changed only",
                                leadingIcon = Icons.Default.Update,
                                showLabel = expandedChipLabel == "Changed only",
                                onExpand = { expandChip("Changed only") },
                                collapsible = false,
                                enabled = changedCount > 0
                            )
                        }
                    }
                    item {
                        val allSmartMatchesSelected = remember(
                            filteredApps,
                            appState.migratorMode,
                            appState.actionFilterState
                        ) {
                            val matches = filteredApps.filter { app ->
                                when (appState.migratorMode) {
                                    MigratorMode.BACKUP_APPS -> app.availableInBackup.isNotEmpty()
                                    MigratorMode.RESTORE_APPS -> !app.isInstalled
                                    MigratorMode.MANAGE -> app.isInstalled
                                    MigratorMode.DEBLOAT -> appState.actionFilterState == 1 && app.availableInBackup.contains(
                                        1
                                    )
                                    else -> false
                                }
                            }
                            matches.isNotEmpty() && matches.all { it.isSelected }
                        }

                        AnimatedFilterChip(
                            selected = allSmartMatchesSelected,
                            onClick = {
                                viewModel.smartSelect(filteredApps)
                            },
                            label = "Auto Select",
                            leadingIcon = Icons.Default.AutoAwesome,
                            showLabel = expandedChipLabel == "Auto Select",
                            onExpand = { expandChip("Auto Select") },
                            collapsible = false
                        )
                    }

                    if (appState.migratorMode == MigratorMode.DEBLOAT && appState.actionFilterState == 1) {
                        item {
                            AnimatedFilterChip(
                                selected = appState.keepDebloatData,
                                onClick = { viewModel.toggleKeepDebloatData() },
                                label = "Keep Data",
                                leadingIcon = Icons.Default.Storage,
                                showLabel = expandedChipLabel == "Keep Data",
                                onExpand = { expandChip("Keep Data") }
                            )
                        }
                    }
                }

                if (showAppFilters) {
                    if (showPartialSystemFilter) {
                        item {
                            AnimatedFilterChip(
                                selected = appState.showPartialSystemApps,
                                onClick = { viewModel.togglePartialSystemApps() },
                                label = "System Apps",
                                leadingIcon = Icons.Default.Layers,
                                showLabel = expandedChipLabel == "System Apps",
                                onExpand = { expandChip("System Apps") }
                            )
                        }
                    }

                    item {
                        AnimatedFilterChip(
                            selected = appState.showSystemApps,
                            onClick = { viewModel.toggleSystemApps() },
                            label = "All System Apps",
                            leadingIcon = Icons.Default.Settings,
                            showLabel = expandedChipLabel == "All System Apps",
                            onExpand = { expandChip("All System Apps") }
                        )
                    }
                }
            }
        }

        // An honest summary of what the current selection will actually do. It used to
        // claim "Apps already backed up with previous components" whenever any
        // row carried an explicit set — and since selecting a row now always
        // materialises one, that banner appeared on every selection and was
        // wrong for apps that had never been backed up.
        val selectedWithParts = remember(filteredApps, appState.migratorMode) {
            filteredApps.filter { it.isSelected && appState.migratorMode.let {
                    it == MigratorMode.BACKUP_APPS || it == MigratorMode.RESTORE_APPS
                } }
        }
        if (selectedWithParts.isNotEmpty()) {
            val distinctParts = selectedWithParts.map { viewModel.pageSelection(it) }.distinct()
            val summary = if (distinctParts.size > 1) {
                "Parts vary per app"
            } else {
                distinctParts.first()
                    .sorted()
                    .joinToString(" • ") { com.phoenix.droidvault.models.AppParts.shortLabel(it) }
                    .ifEmpty { "No parts selected" }
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = CircleShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        "$summary — tap an app to change what it includes",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        val pullRefreshState = rememberPullToRefreshState()
        // A finished verification leaves a one-line result on the Manage screen
        // instead of only in a dialog the user has already dismissed.
        val report = verification
        if (report != null && !isVerifying && !verificationDialogVisible &&
            appState.migratorMode == MigratorMode.MANAGE
        ) {
            // Re-opening from the banner; it does not discard the result.
            VerificationBanner(report) {
                viewModel.clearVerification()
                viewModel.openVerificationDialog()
            }
        }
        // Deleted backups, and the way back. Refreshed when Manage is entered
        // because a delete from anywhere can change it.
        // EVERY mode, not just Manage. Today every delete lives in Manage, so
        // this is belt-and-braces — but a quarantiner whose card is scoped to
        // one mode means the next delete added elsewhere could tell the user
        // "recoverable for 7 days" with no way to prove it. TrashCard returns
        // early when nothing is deleted, so the common case costs nothing.
        val trash by viewModel.trash.collectAsStateWithLifecycle()
        TrashCard(
            batches = trash,
            onRestore = { viewModel.restoreTrashBatch(it) },
            onPurge = { viewModel.purgeTrashBatch(it) },
            onOpen = { showTrash = true }
        )
        val folderArchive = viewModel.folderArchive.collectAsStateWithLifecycle().value
        val backupDateFormat = remember {
            java.text.SimpleDateFormat("hh:mm a • dd MMM, yyyy", java.util.Locale.getDefault())
        }
        var foldersExpanded by remember { mutableStateOf(false) }
        var manageFolderSelection by remember { mutableStateOf<Set<String>>(emptySet()) }
        // Manage Backups: the indexed folder archive renders above the app
        // list — real folder paths with per-folder selection, included in
        // search filtering, and individually deletable (rebuild archive).
        // Tap the collapsed card header → erase dialog for the whole archive.
        if (appState.migratorMode == MigratorMode.MANAGE) {
            if (folderArchive == null) {
                if (appState.searchQuery.isEmpty()) {
                    Text(
                        "No Folders archive in this backup folder — create one from Migrate → Backup Folders.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                    )
                }
            } else {
                val matchesSearch = appState.searchQuery.isEmpty() ||
                    folderArchive.folders.any {
                        it.contains(appState.searchQuery, ignoreCase = true)
                    } ||
                    "folders".contains(appState.searchQuery, ignoreCase = true)
                if (matchesSearch) {
                    // Selection pruned to what the archive actually holds —
                    // stale ids from a previous archive can never delete.
                    val archivedSelection = folderArchive.folders.filter { it in manageFolderSelection }
                    val allFoldersSelected = folderArchive.folders.isNotEmpty() &&
                        archivedSelection.size == folderArchive.folders.size
                    ElevatedCard(
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        foldersExpanded = !foldersExpanded
                                        manageFolderSelection = emptySet()
                                    }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(getAvatarColor("Folders")),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "Backed-up Folders",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        "${folderArchive.folders.size} folder${if (folderArchive.folders.size == 1) "" else "s"} in " +
                                            BackupConfigFolders.ARCHIVE_FILE +
                                            (if (foldersExpanded) " — tap a folder to select" else " • tap to manage"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        MigratorManager.formatSize(folderArchive.sizeKb.toString()) +
                                            " • " + backupDateFormat.format(java.util.Date(folderArchive.modifiedAt)),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = { foldersExpanded = !foldersExpanded }) {
                                    Icon(
                                        if (foldersExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = if (foldersExpanded) "Collapse" else "Expand"
                                    )
                                }
                            }
                            if (foldersExpanded && folderArchive.folders.isNotEmpty()) {
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            manageFolderSelection =
                                                if (allFoldersSelected) emptySet() else folderArchive.folders.toSet()
                                        }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = allFoldersSelected,
                                        onCheckedChange = null
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        if (allFoldersSelected) "Deselect all" else "Select all",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                }
                                folderArchive.folders.forEach { path ->
                                    val checked = path in manageFolderSelection
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(MaterialTheme.shapes.medium)
                                            .clickable {
                                                manageFolderSelection =
                                                    if (checked) manageFolderSelection - path
                                                    else manageFolderSelection + path
                                            }
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(checked = checked, onCheckedChange = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                BackupConfigFolders.shortDisplayName(path),
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (BackupConfigFolders.shortDisplayName(path) != path) {
                                                Text(
                                                    path,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        if (archivedSelection.isEmpty()) {
                                            if (allFoldersSelected) "All folders will be removed"
                                            else "Select folders to remove from the backup"
                                        } else {
                                            "${archivedSelection.size} of ${folderArchive.folders.size} selected"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    FilledTonalIconButton(
                                        onClick = {
                                            val toRemove = archivedSelection
                                            if (toRemove.isNotEmpty()) {
                                                viewModel.deleteArchivedFolders(context, toRemove)
                                                manageFolderSelection = emptySet()
                                                foldersExpanded = false
                                            }
                                        },
                                        enabled = archivedSelection.isNotEmpty(),
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.DeleteForever,
                                            contentDescription = "Remove selected folders from backup",
                                            tint = if (archivedSelection.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                                            else MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        PullToRefreshBox(
            isRefreshing = appState.isFetchingApps,
            onRefresh = { viewModel.refreshCurrentList() },
            state = pullRefreshState,
            indicator = {
                ExpressiveRefreshIndicator(
                    state = pullRefreshState,
                    isRefreshing = appState.isFetchingApps,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp)
                )
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (appState.isFetchingApps) {
                LazyColumn(modifier = Modifier.fillMaxSize()) { items(8) { ShimmerAppListItem() } }
            } else if (filteredApps.isEmpty()) {
                // Distinguish "nothing is filtered out of a populated list" from
                // "this mode's source is genuinely empty". In Restore the source
                // is the vault, so an empty list means there are no backups —
                // telling the user to pull down to refresh sends them to retry
                // something that cannot succeed.
                val vaultSourced = appState.migratorMode == MigratorMode.RESTORE_APPS ||
                        appState.migratorMode == MigratorMode.MANAGE
                val nothingBackedUp = vaultSourced && appList.isEmpty()
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        if (nothingBackedUp) Icons.Default.CloudOff else Icons.Default.SearchOff,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (nothingBackedUp) {
                            if (appState.migratorMode == MigratorMode.RESTORE_APPS)
                                "No app backups in this vault folder yet"
                            else "No backups in this vault folder yet"
                        } else {
                            "No apps match this filter"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (nothingBackedUp) {
                            "Back up some apps first, or check that Droid Vault is " +
                                    "pointing at the folder you backed up to."
                        } else {
                            "Clear the search or filters to see the full list."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    if (nothingBackedUp && appState.migratorMode == MigratorMode.RESTORE_APPS) {
                        Spacer(Modifier.height(16.dp))
                        androidx.compose.material3.Button(
                            onClick = { viewModel.setMigratorMode(MigratorMode.BACKUP_APPS) },
                            shape = CircleShape
                        ) { Text("Back up apps") }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 12.dp, top = 6.dp)
                ) {
                    items(filteredApps, key = { it.packageName }, contentType = { "app" }) { app ->

                        val showTime = appState.migratorMode == MigratorMode.BACKUP_APPS || appState.migratorMode == MigratorMode.RESTORE_APPS || appState.migratorMode == MigratorMode.MANAGE || appState.migratorMode == MigratorMode.DEBLOAT
                        val isMonochrome = (appState.migratorMode == MigratorMode.RESTORE_APPS || appState.migratorMode == MigratorMode.MANAGE) && !app.isInstalled

                        val showComps =
                            appState.migratorMode == MigratorMode.BACKUP_APPS || appState.migratorMode == MigratorMode.RESTORE_APPS

                        // No animateItem(): placement animation on 300+ rows
                        // re-triggers on every selection change and janks.
                        Box {
                            Column {
                                AppListItem(
                                    app = app,
                                    showBackupTime = showTime,
                                    isMonochrome = isMonochrome,
                                    showSelectedComponents = showComps,
                                    // Only the two modes that HAVE per-app parts
                                    // get a detail page. Debloat/Systemize/
                                    // Manage act on whole packages, so a page of
                                    // checkboxes there would be pure noise.
                                    onOpenDetail = if (showComps) {
                                        { viewModel.openComponentEditor(app.packageName) }
                                    } else null,
                                    onToggleSelect = { pkgName -> viewModel.toggleAppSelection(pkgName) }
                                )
                                val appLabels = labelMap[app.packageName]
                                    ?.mapNotNull { labelNames[it] }
                                    ?.sorted()
                                if (!appLabels.isNullOrEmpty()) {
                                    Text(
                                        appLabels.joinToString(" • "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        modifier = Modifier.padding(start = 90.dp, end = 12.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Read from progressState (the raw flow), not appState: the panel is the sole
        // consumer of the high-frequency fields that the shell flow masks.
        val showProgressPanel = progressState.isRunning || progressState.currentStep.isNotEmpty()

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            shadowElevation = 4.dp
        ) {
            AnimatedContent(
                targetState = showProgressPanel,
                transitionSpec = {
                    fadeIn(tween(200)) togetherWith fadeOut(tween(200))
                },
                label = "BottomBarTransition"
            ) { isProcessing ->
                if (isProcessing) {
                    val isCompleted = !progressState.isRunning && progressState.progress == 100
                    // Success is an explicit ALLOW-LIST, not a search for the word
                    // "Error". Substring-matching prose was wrong in both directions:
                    // the real terminal strings are "Operation Failed!" and
                    // "Deletion Failed!", neither of which contains "Error", so a
                    // FAILED backup rendered a green Verified icon whose
                    // contentDescription was literally "Success" — with the failure
                    // text beside it in the primary colour. This panel is what the
                    // user is left looking at after the overlay is dismissed.
                    //
                    // Unknown strings default to error: never report success for
                    // work that did not happen.
                    val isError = remember(progressState.currentAction) {
                        val a = progressState.currentAction
                        !(
                            a.startsWith("Completed") ||
                                a.endsWith("Complete!") ||
                                a.startsWith("Backup Complete") ||
                                a.startsWith("Restore Complete") ||
                                a.startsWith("Deletion Complete") ||
                                a.contains("Partially completed", ignoreCase = true)
                            )
                    }

                    val processingApp = remember(progressState.currentAction, progressState.currentStep) {
                        appList.firstOrNull { app ->
                            app.isSelected && (
                                    progressState.currentAction.contains(app.label, ignoreCase = true) ||
                                            progressState.currentStep.contains(
                                                app.label,
                                                ignoreCase = true
                                            ) ||
                                            progressState.currentAction.contains(
                                                app.packageName,
                                                ignoreCase = true
                                            ) ||
                                            progressState.currentStep.contains(
                                                app.packageName,
                                                ignoreCase = true
                                            )
                                    )
                        }
                        }

                    Column(
                        modifier = Modifier
                            .navigationBarsPadding()
                            .padding(horizontal = 24.dp, vertical = 16.dp)
                        ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {

                            // Keyed on isCompleted ONLY. It used to be keyed on
                            // `isCompleted to (processingApp != null)`, but
                            // processingApp flips null/non-null as the step
                            // text rolls through app labels — restarting this
                            // 400 ms scale+fade several times per run, which
                            // read as the icon popping mid-progress. The app-icon
                            // swap now cross-fades on its own, much shorter.
                            AnimatedContent(
                                targetState = isCompleted,
                                transitionSpec = {
                                    (scaleIn(tween(400, easing = FastOutSlowInEasing)) + fadeIn(
                                        tween(400)
                                    )) togetherWith
                                            (scaleOut(tween(200)) + fadeOut(tween(200)))
                                },
                                label = "IconTransition"
                            ) { completed ->
                                val hasApp = processingApp != null
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (completed) {
                                        Icon(
                                            imageVector = if (isError) Icons.Default.Error else Icons.Default.Verified,
                                            contentDescription = if (isError) "Error" else "Success",
                                            tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(42.dp)
                                        )
                                        Spacer(modifier = Modifier.width(16.dp))
                                    } else if (hasApp) {
                                        val iconModifier = Modifier
                                            .size(42.dp)
                                            .clip(RoundedCornerShape(12.dp))

                                        if (processingApp.iconPath != null) {
                                            AsyncImage(
                                                model = processingApp.iconPath,
                                                contentDescription = null,
                                                modifier = iconModifier
                                                )
                                        } else {
                                            val letter =
                                                processingApp.label.firstOrNull()?.uppercase()
                                                    ?: "?"
                                            Box(
                                                modifier = iconModifier.background(
                                                    getAvatarColor(
                                                        processingApp.label
                                                    )
                                                ),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = letter,
                                                    color = Color.White,
                                                    fontWeight = FontWeight.Medium,
                                                    fontSize = 20.sp
                                                )
                                            }
                                            }
                                        Spacer(modifier = Modifier.width(16.dp))
                                        }
                                    }
                                }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = progressState.currentAction,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (progressState.currentStep.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = progressState.currentStep,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                }

                            if (!progressState.isRunning) {
                                Spacer(modifier = Modifier.width(12.dp))
                                FilledTonalIconButton(
                                    onClick = { viewModel.clearLogs() },
                                    modifier = Modifier.size(40.dp),
                                    shape = CircleShape
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Dismiss",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            } else if (progressState.progress in 0..100) {
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "${progressState.progress}%",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                        Spacer(modifier = Modifier.height(12.dp))

                        if (progressState.progress in 0..100) {
                            LinearProgressIndicator(
                                progress = { progressState.progress / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(CircleShape),
                                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(CircleShape),
                                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                            )
                        }

                        // One Cancel, for EVERY phase. It used to live inside the
                        // determinate branch only, so it vanished the moment a
                        // phase went indeterminate — which is precisely when a
                        // 34 GB import looks most stuck and a user most needs a
                        // way out. A 34 GB import runs for a very long time; the
                        // only alternative to this is force-stopping the app and
                        // losing all of it. Cancel removes staging and the staged
                        // copy. It never touches the vault.
                        if (isImporting || isExporting) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(
                                    onClick = {
                                        if (isExporting) viewModel.cancelExport(context)
                                        else viewModel.cancelImport(context)
                                    }
                                ) {
                                    Text(
                                        if (isExporting) "Cancel export" else "Cancel import",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }
                            }
                        }
                        }
                } else {
                    // The count the RUN will use, not the number of ticked rows.
                    // They differ whenever a system app is ticked with the
                    // system-app filter off, which made the button read "3
                    // Selected" and enable a run that processed one app.
                    val selectedCount = appState.selectedRunCount
                    Row(
                        modifier = Modifier
                            .navigationBarsPadding()
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {

                        val isNeedFreeMode = appState.migratorMode == MigratorMode.BACKUP_APPS ||
                                appState.migratorMode == MigratorMode.RESTORE_APPS ||
                                (appState.migratorMode == MigratorMode.DEBLOAT && appState.actionFilterState == 2) ||
                                (appState.migratorMode == MigratorMode.SYSTEMIZE && appState.actionFilterState == 1)

                        val isNowMayFreeMode = appState.migratorMode == MigratorMode.MANAGE ||
                                (appState.migratorMode == MigratorMode.DEBLOAT && appState.actionFilterState == 1) ||
                                (appState.migratorMode == MigratorMode.SYSTEMIZE && appState.actionFilterState == 2)

                        val isInsufficientSpace =
                            appState.totalRequiredKb > appState.storageFreeKb && isNeedFreeMode

                        Column(modifier = Modifier.weight(1f)) {
                            AnimatedContent(
                                targetState = selectedCount,
                                transitionSpec = {
                                    if (targetState > initialState) {
                                        (slideInVertically { height -> height } + fadeIn()) togetherWith
                                                (slideOutVertically { height -> -height } + fadeOut())
                                    } else {
                                        (slideInVertically { height -> -height } + fadeIn()) togetherWith
                                                (slideOutVertically { height -> height } + fadeOut())
                                    }
                                },
                                label = "CountAnimation"
                            ) { count ->
                                Text(
                                    text = "$count Selected",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = if (isInsufficientSpace) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                                )
                            }
                            if (selectedCount > 0 && isNeedFreeMode) {
                                val reqSize =
                                    com.phoenix.droidvault.utils.MigratorManager.formatSize(
                                        appState.totalRequiredKb.toString()
                                    )
                                val freeSize =
                                    com.phoenix.droidvault.utils.MigratorManager.formatSize(
                                        appState.storageFreeKb.toString()
                                    )
                                // Two figures turn red and the run button goes dead,
                                // with nothing saying why or what to do about it. This
                                // is the single most common failure on a long backup,
                                // and it reads as a broken app rather than a full disk.
                                // Say it in words, and say the way out.
                                if (isInsufficientSpace) {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = buildAnnotatedString {
                                                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                                    append("Need: ")
                                                }
                                                append(reqSize)
                                                append(" | ")
                                                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                                    append("Free: ")
                                                }
                                                append(freeSize)
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                        Text(
                                            "Not enough free space. Free up some room, or " +
                                                "back up fewer apps at a time.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                } else {
                                    Text(
                                        text = buildAnnotatedString {
                                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                                append("Need: ")
                                            }
                                            append(reqSize)
                                            append(" | ")
                                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                                append("Free: ")
                                            }
                                            append(freeSize)
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else if (selectedCount > 0 && isNowMayFreeMode) {
                                val selSize =
                                    com.phoenix.droidvault.utils.MigratorManager.formatSize(
                                        appState.totalRequiredKb.toString()
                                    )
                                val usedSize =
                                    com.phoenix.droidvault.utils.MigratorManager.formatSize(
                                        appState.storageUsedKb.toString()
                                    )
                                Text(
                                    text = buildAnnotatedString {
                                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                            append("Used: ")
                                        }
                                        append(usedSize)
                                        append(" | ")
                                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                            append("Free Up: ")
                                        }
                                        append(selSize)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))

                        Button(
                            onClick = { viewModel.runDynamicOperation() },
                            enabled = selectedCount > 0 && !isInsufficientSpace,
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (appState.migratorMode == MigratorMode.MANAGE || (appState.migratorMode == MigratorMode.DEBLOAT && appState.actionFilterState == 2) || (appState.migratorMode == MigratorMode.SYSTEMIZE && appState.actionFilterState == 2)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier
                                .height(52.dp)
                                .widthIn(min = 120.dp)
                        ) {
                            val act = when (appState.migratorMode) {
                                MigratorMode.MANAGE -> "Delete Apps"
                                MigratorMode.DEBLOAT -> if (appState.actionFilterState == 2) "Restore Apps" else "Debloat Apps"
                                MigratorMode.RESTORE_APPS -> "Restore Apps"
                                MigratorMode.SYSTEMIZE -> if (appState.actionFilterState == 2) "De-Systemize" else "Systemize Apps"
                                else -> "Backup Apps"
                            }
                            Text(act, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            }
        }
    }
