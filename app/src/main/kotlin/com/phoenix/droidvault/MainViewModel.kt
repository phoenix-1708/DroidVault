package com.phoenix.droidvault
import com.phoenix.droidvault.utils.DroidVaultLogger
import com.phoenix.droidvault.utils.TransferBus
import com.phoenix.droidvault.utils.TransferLocator
import com.phoenix.droidvault.utils.TransferOp
import com.phoenix.droidvault.utils.TransferService
import com.phoenix.droidvault.utils.TransferStatus
import com.phoenix.droidvault.utils.VaultTransferManager
import com.phoenix.droidvault.utils.VaultVerdictStore
import com.phoenix.droidvault.models.TransferCandidate
import com.phoenix.droidvault.models.TransferProgress
import com.phoenix.droidvault.models.ImportSource
import com.phoenix.droidvault.models.VaultTransfer
import android.widget.Toast
import com.phoenix.droidvault.ui.safeToast

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.graphics.createBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.phoenix.droidvault.models.AppInfo
import com.phoenix.droidvault.models.AppInstallInfo
import com.phoenix.droidvault.models.AppState
import com.phoenix.droidvault.models.DeviceArchiveRead
import com.phoenix.droidvault.models.DeviceDataItem
import com.phoenix.droidvault.models.RootState
import com.phoenix.droidvault.models.classifyRoot
import com.phoenix.droidvault.models.AppParts
import com.phoenix.droidvault.models.ChangeDetection
import com.phoenix.droidvault.models.FlashAction
import com.phoenix.droidvault.models.HomeShortcut
import com.phoenix.droidvault.models.MigratorMode
import com.phoenix.droidvault.models.Trash
import com.phoenix.droidvault.models.VaultVerdict
import com.phoenix.droidvault.models.VerificationReport
import com.phoenix.droidvault.models.isVaultSourced
import com.phoenix.droidvault.models.verifyReportAllowsRestore
import com.phoenix.droidvault.utils.AutomationRunner
import com.phoenix.droidvault.utils.AutomationSnapshot
import com.phoenix.droidvault.utils.AutomationStore
import com.phoenix.droidvault.utils.BackendInstaller
import com.phoenix.droidvault.utils.BackupConfigFolders
import com.phoenix.droidvault.utils.BackupFolderStore
import com.phoenix.droidvault.utils.DeviceManager
import com.phoenix.droidvault.utils.FlashManager
import com.phoenix.droidvault.utils.Formatters
import com.phoenix.droidvault.utils.MigratorManager
import com.phoenix.droidvault.utils.RunCoordinator
import com.phoenix.droidvault.utils.ProgressTracker
import com.phoenix.droidvault.utils.SettingsManager
import com.phoenix.droidvault.utils.ShellQuote
import com.phoenix.droidvault.utils.ToolsManager
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.time.Duration.Companion.milliseconds

/** How long a vault-contents listing is served from memory before it is
 *  recomputed. Collapses the Home -> Settings double-fire without hiding a
 *  manual "Repair mirrors" result (that path forces a refresh). */
private const val VAULT_CONTENTS_TTL_MS = 30_000L

/** Prefs key holding the ordered ids of the user's Home quick-access row. */
private const val KEY_HOME_SHORTCUTS = "home_shortcut_ids"

/** Tab index of the Home dashboard — the app always opens here. */
const val TAB_HOME = 0

/**
 * Guards the one process-wide resource that two independent startup paths both
 * reach for: the cached root shell. `LegacyMigration.complete` runs from
 * `Application.onCreate` and the ViewModel's own probe runs from `init`, and
 * neither knows about the other.
 *
 * FILE-level on purpose, and that is load-bearing. As a member it was declared
 * far below `init`, and Kotlin initialises properties in declaration order — so
 * `init { checkRootAccess() }`, which launches on IO, read the field before the
 * constructor had assigned it. First as `NullPointerException: Null reference
 * used for synchronization (monitor-enter)`, then after switching to `by lazy`:
 * `Attempt to invoke virtual method 'Object lh1.getValue()' on a null object
 * reference`. `by lazy` only defers the VALUE; the delegate field itself is still
 * assigned in declaration order, so the coroutine still beat it. Both crashes
 * were on the release build on the POCO F6.
 *
 * At file scope the holder is created when the class is loaded, which happens
 * before any instance exists, so no constructor-order race is possible.
 */
private val shellTeardownLock = Any()

/** How many callers currently need the shell to survive a teardown. */
private val shellUsers = java.util.concurrent.atomic.AtomicInteger(0)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    // Root must be verified before onboarding is composed: its first step can
    // request Android runtime permissions. Starting optimistic caused those
    // system dialogs to appear under the root gate on a clean install.
    private val _uiState = MutableStateFlow(AppState(hasRoot = false, isFetchingApps = true))
    val uiState: StateFlow<AppState> = _uiState.asStateFlow()
    private val _appOperationCompletion = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val appOperationCompletion = _appOperationCompletion.asSharedFlow()

    /**
     * State consumed by the app shell and inactive tabs. Backup progress can
     * update several times a second; keeping those fields out of this flow
     * prevents every progress tick from invalidating navigation and all pager
     * pages. The active Migrate screen subscribes to [uiState] directly.
     */
    val shellUiState: StateFlow<AppState> = uiState
        .map { state ->
            if (state.showAppInstaller) state
            else state.copy(currentStep = "", progress = 0)
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, _uiState.value)

    private val _appList = MutableStateFlow<List<AppInfo>>(emptyList())
    val appList: StateFlow<List<AppInfo>> = _appList.asStateFlow()

    private val _flashActions = MutableStateFlow<List<FlashAction>>(emptyList())
    val flashActions: StateFlow<List<FlashAction>> = _flashActions.asStateFlow()

    private val prefs = application.getSharedPreferences("droidvault_prefs", Context.MODE_PRIVATE)

    private val _savedPath = MutableStateFlow(
        prefs.getString("base_path", SettingsManager.getDefaultPath())
            ?: SettingsManager.getDefaultPath()
    )
    val savedPath: StateFlow<String> = _savedPath.asStateFlow()

    /**
     * Bumped whenever the Droid Vault folder changes (onboarding, Settings move).
     * Screens whose listings derive from the folder collect this and reload —
     * the reactive replacement for the old "restart to apply" behavior.
     */
    val pathVersion = MutableStateFlow(0)

    companion object {
        private const val CHANNEL_PROGRESS_ID = "droidvault_progress_v2"
        private const val CHANNEL_ALERT_ID = "droidvault_alerts_v2"
        private const val NOTIFICATION_ID = 1001

        const val KEY_PENDING_SMS_RESTORE = "pending_sms_restore"

        /**
         * Mirror of [savedPath] for headless readers (scheduler worker, runs
         * started from another process entry point): prefs re-reads see the
         * persisted value, but anything spawned while the app is alive must
         * see the CURRENT folder, not a stale one. Kept in sync wherever
         * _savedPath changes.
         */
        @Volatile
        var liveBasePath: String = SettingsManager.getDefaultPath()
            private set
    }

    // Declared BEFORE the init blocks below: Kotlin initializes properties in
    // declaration order, and this was previously declared further down the
    // class — so createNotificationChannel() ran against a still-null receiver
    // and crashed every launch with an NPE inside MainViewModel.<init>.
    private val notificationManager = NotificationManagerCompat.from(application)

    init {
        liveBasePath = _savedPath.value
    }

    init {
        createNotificationChannel()
        observeTransferService()
        // Restore consent gates BEFORE any run can start — not only when the
        // Settings screen opens (a restored grant must hold without a visit).
        loadConsentState()
        checkRootAccess()
        // Automation recovery runs HERE, at startup, rather than only when the
        // Auto tab binds.
        //
        // It used to be triggered solely by AutomationViewModel's refresh, so
        // whether a reinstalled app recovered its labels/configs — and whether
        // the "Automation data restored" notice appeared at all — depended on
        // the user visiting the Auto tab. Reported as the popup showing up "at
        // random": it was never random, it was navigation-dependent, and on a
        // fresh install that sat on the onboarding carousel it never ran.
        //
        // `_savedPath` is already resolved here (read synchronously in the
        // property initialiser), so there is nothing to wait for. Recovery is
        // idempotent and cheap when there is nothing to do.
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                com.phoenix.droidvault.utils.AutomationStore
                    .autoImportStateFromFile(getApplication(), _savedPath.value)
            }.onFailure {
                DroidVaultLogger.w(
                    "Automation",
                    "automation state recovery skipped: ${it.javaClass.simpleName}: ${it.message}"
                )
            }
        }
    }

    /**
     * Every device archive, with size and mtime, as read from the vault folder.
     *
     * This is the single read. [availableDeviceBackups] — which the Home card,
     * the erase dialog and the folder-archive row have always used — is derived
     * from it rather than being a second `ls` of the same directory, because two
     * reads of one fact is two answers waiting to disagree, and they are read at
     * different moments.
     */
    private val _deviceArchives =
        MutableStateFlow<DeviceArchiveRead>(DeviceArchiveRead.Unreadable("Not read yet"))
    val deviceArchives: StateFlow<DeviceArchiveRead> = _deviceArchives.asStateFlow()

    /**
     * The archive FILE NAMES present in `Device/`, which is what the erase
     * dialog's `Folders.shift` check and the Home card ask for.
     *
     * Derived, so it cannot disagree with [deviceArchives] about whether a file
     * is there. It was a separate `ls` before, and a screen reading both could
     * show "Folders backed up" beside a folder archive it could not find.
     *
     * An unreadable folder answers "no file here", which is the right answer for
     * a PREDICATE — the erase dialog is asking whether to offer a row, and
     * offering nothing on a folder it could not read is the safe direction. It
     * is the wrong answer for a REPORT, which is why the Manage card reads
     * [deviceArchives] and not this.
     */
    val availableDeviceBackups: StateFlow<Set<String>> =
        _deviceArchives
            .map { read ->
                read.archivesOrEmpty().filter { it.present }.map { it.item.archiveFile }.toSet()
            }
            .stateIn(
                viewModelScope,
                kotlinx.coroutines.flow.SharingStarted.Eagerly,
                emptySet()
            )

    /**
     * Re-read the device archives.
     *
     * The name is kept because four call sites and three other screens already
     * use it, and renaming it would spread a refactor through Migrate, Home and
     * the trash paths for no gain. What changed is what it reads.
     *
     * The failure is a STATE, not an empty list. `readDeviceArchives` calls
     * `ShellQuote.requireAbsPath`, which THROWS on a vault path that is no
     * longer absolute, and the original `getOrDefault(emptyList())` turned that
     * into a Manage card reading "0 of 7 backed up" — the app asserting as fact
     * that a user has no device backups, because it could not open the folder.
     * That is invariant 16 (absence is not failure) in its worst form: a
     * confident, destructive-sounding claim about data the user cannot check
     * without going into Settings. A folder that cannot be read is now
     * [DeviceArchiveRead.Unreadable], the card says so, and the reason goes to
     * the diagnostics log so the report is answerable.
     */
    fun refreshDeviceBackups() {
        viewModelScope.launch(Dispatchers.IO) {
            val saved = _savedPath.value
            _deviceArchives.value = try {
                DeviceArchiveRead.Ok(DeviceManager.readDeviceArchives(saved))
            } catch (t: Throwable) {
                // Deliberately Throwable: the failure modes here are
                // IllegalArgumentException from `requireAbsPath` and a
                // SecurityException from a vanished volume. Neither should
                // escape into a coroutine, and the screen degrades to "could
                // not read" either way, so there is nothing for it to crash on.
                val reason = t.message?.takeIf { it.isNotBlank() }
                    ?: t.javaClass.simpleName
                DroidVaultLogger.w("DeviceData", "read failed for $saved: $reason")
                DeviceArchiveRead.Unreadable(reason)
            }
        }
    }

    /**
     * Re-point every path-derived view at the current Droid Vault folder.
     * Called after onboarding picks a folder and after Settings moves it, so
     * archive listings, restore lists, and config screens reflect the new
     * folder without an app restart.
     */
    fun refreshPathBoundState() {
        // The app-list caches carry sizes read from the folder registry and the
        // vault index, so they describe the PREVIOUS folder after a move. They
        // were never invalidated here, which is why "backup on device" kept
        // showing the old folder's answers until an unrelated run finished.
        com.phoenix.droidvault.utils.MigratorManager.clearCache()
        refreshDeviceBackups()
        refreshArchivedFolders()
        refreshFolderArchive()
        // forced: the folder just changed, so the cached listing describes the
        // old path's files.
        refreshVaultContents(force = true)
        // State import is idempotent on non-empty stores.
        viewModelScope.launch(Dispatchers.IO) {
            com.phoenix.droidvault.utils.AutomationStore.autoImportStateFromFile(
                getApplication(),
                _savedPath.value
            )
        }
        pathVersion.value++
    }

    // ---------- Folder backup sources (DeviceDataItem.FOLDERS) ----------
    // Delegates to the process-wide store so Settings, the Migrate tab, and
    // Automation config editors all see one list (an add in any tab is
    // visible everywhere immediately).
    val backupFolders: StateFlow<List<String>> = BackupFolderStore.folders

    fun addBackupFolder(path: String, onDone: ((Boolean) -> Unit)? = null) {
        // BackupFolderStore persists with prefs.commit(), which fsyncs, and
        // pruneRemovedBackupFolder does two blocking root-shell round trips plus
        // a full state-file parse. Both used to run on the calling thread — the
        // UI thread — where a cold `su` session is a 1-10 s ANR-class freeze.
        viewModelScope.launch(Dispatchers.IO) {
            val ok = BackupFolderStore.add(getApplication(), path)
            onDone?.let { withContext(Dispatchers.Main) { it(ok) } }
        }
    }

    fun removeBackupFolder(path: String, onDone: (() -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            BackupFolderStore.remove(getApplication(), path)
            // Settings is the master registry: the removal must prune EVERY
            // persisted config's folderPaths immediately, not on the next
            // AutomationViewModel bind — otherwise a config editor opened before
            // that bind still offers the dead path (and its save would
            // resurrect the folder through the re-register heal).
            try {
                AutomationStore.pruneRemovedBackupFolder(getApplication(), path)
            } catch (_: Exception) {
            }
            refreshPathBoundState()
            // MigratorManager's app-list caches describe sizes read from the
            // folder registry, so a registry change has to invalidate them.
            com.phoenix.droidvault.utils.MigratorManager.clearCache()
            onDone?.let { withContext(Dispatchers.Main) { it() } }
        }
    }

    /** Folders inside the current Folders.shift archive (manifest-based). */
    private val _archivedFolders = MutableStateFlow<List<String>>(emptyList())
    val archivedFolders: StateFlow<List<String>> = _archivedFolders.asStateFlow()

    fun refreshArchivedFolders() {
        viewModelScope.launch(Dispatchers.IO) {
            _archivedFolders.value =
                DeviceManager.listArchivedFolders(_savedPath.value)
        }
    }

    /** Real metadata of the on-disk Folders.shift archive (Manage Backups). */
    data class FolderArchiveInfo(
        val path: String,
        val folders: List<String>,
        val sizeKb: Long,
        val modifiedAt: Long
    )

    private val _folderArchive = MutableStateFlow<FolderArchiveInfo?>(null)
    val folderArchive: StateFlow<FolderArchiveInfo?> = _folderArchive.asStateFlow()

    /**
     * Discover the Folders.shift archive through the root shell (scoped
     * storage hides it from java.io): existence, size, mtime, and the real
     * folder list from the manifest inside it. Powers the Manage Backups
     * folder card — actual indexed backup content, not a placeholder.
     */
    fun refreshFolderArchive() {
        viewModelScope.launch(Dispatchers.IO) {
            val path = _savedPath.value
            val archive = "$path/Device/${com.phoenix.droidvault.utils.BackupConfigFolders.ARCHIVE_FILE}"
            val exists = Shell.cmd("[ -f ${ShellQuote.q(archive)} ] && echo y").exec()
                .out.firstOrNull()?.trim() == "y"
            if (!exists) {
                _folderArchive.value = null
                return@launch
            }
            val stat = Shell.cmd("stat -c '%s %Y' ${ShellQuote.q(archive)} 2>/dev/null").exec()
                .out.joinToString("").trim().split(" ")
            val sizeKb = (stat.getOrNull(0)?.toLongOrNull() ?: 0L) / 1024
            val mtime = (stat.getOrNull(1)?.toLongOrNull() ?: 0L) * 1000
            _folderArchive.value = FolderArchiveInfo(
                path = archive,
                folders = DeviceManager.listArchivedFolders(path),
                sizeKb = sizeKb,
                modifiedAt = mtime
            )
        }
    }

    /** Self-backup artifacts in the vault folder (Settings explainer card). */
    data class VaultContentInfo(
        val path: String,
        val files: List<AutomationSnapshot.VaultFileInfo>,
        val health: Map<String, AutomationSnapshot.VaultHealth> = emptyMap()
    )

    private val _vaultContents = MutableStateFlow<VaultContentInfo?>(null)
    val vaultContents: StateFlow<VaultContentInfo?> = _vaultContents.asStateFlow()

    /**
     * Stat the self-backup artifacts (APK, Rust engine, compatibility
     * launcher, and state file) in the current vault folder for the Settings
     * "What's in my vault folder" explainer. Fired on folder bind and on
     * every Settings visit (the tab re-enters composition, so its
     * LaunchedEffect re-triggers this).
     */
    fun refreshVaultContents(force: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val path = _savedPath.value
            if (path.isBlank()) {
                _vaultContents.value = null
                return@launch
            }
            // This costs 4 root forks and ~1.7 MB of SHA-256 (engine + zapdos
            // mirrors vs /data/adb), and it is fired by the Home page's
            // LaunchedEffect AND by Settings — so a Home -> Settings round trip
            // paid it twice, every time. Serve a recent answer instead, and
            // never start two of these concurrently.
            if (!force) {
                val skip = synchronized(vaultContentsLock) {
                    val cached = _vaultContents.value
                    val fresh = cached != null && cached.path == path &&
                        now() - vaultContentsAt[path].orZero() < VAULT_CONTENTS_TTL_MS
                    if (fresh) true else !vaultContentsInFlight.add(path)
                }
                if (skip) return@launch
            }
            try {
                val files = AutomationSnapshot.listVaultContents(path)
            val apkUpdatedAt = try {
                getApplication<Application>().packageManager
                    .getPackageInfo(getApplication<Application>().packageName, 0)
                    .lastUpdateTime
            } catch (_: Exception) {
                0L
            }
            val (mirrorHash, sourceHash) = AutomationSnapshot.engineMirrorVsSourceHash(path)
            val (launcherMirrorHash, launcherSourceHash) =
                AutomationSnapshot.launcherMirrorVsSourceHash(path)
            val health = AutomationSnapshot.computeVaultHealth(
                    files = files,
                    apkUpdatedAt = apkUpdatedAt,
                    engineMirrorHash = mirrorHash,
                    engineSourceHash = sourceHash,
                    launcherMirrorHash = launcherMirrorHash,
                    launcherSourceHash = launcherSourceHash
                )
                synchronized(vaultContentsLock) { vaultContentsAt[path] = now() }
                _vaultContents.value = VaultContentInfo(path, files, health)
            } finally {
                if (!force) synchronized(vaultContentsLock) { vaultContentsInFlight.remove(path) }
            }
        }
    }

    private val vaultContentsLock = Any()
    private val vaultContentsAt = HashMap<String, Long>()
    private val vaultContentsInFlight = HashSet<String>()

    private fun now() = System.currentTimeMillis()
    private fun Long?.orZero() = this ?: 0L

    private val _vaultRepairing = MutableStateFlow(false)
    val vaultRepairing: StateFlow<Boolean> = _vaultRepairing.asStateFlow()

    /**
     * One-tap repair for the explainer card: force re-copy the APK + engine
     * mirrors into the vault folder, then refresh the card in place.
     */
    fun repairVaultMirrors(onDone: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            if (_vaultRepairing.value) return@launch
            _vaultRepairing.value = true
            val path = _savedPath.value
            val ok = path.isNotBlank() && try {
                AutomationSnapshot.forceRefreshMirrors(getApplication(), path)
                AutomationSnapshot.listVaultContents(path).all { it.present }
            } catch (_: Exception) {
                false
            }
            // forced: the repair just rewrote the mirrors, so a cached answer would
            // show the pre-repair state and hide the result the user tapped for.
            refreshVaultContents(force = true)
            _vaultRepairing.value = false
            kotlinx.coroutines.withContext(Dispatchers.Main) { onDone(ok) }
        }
    }

    /**
     * Backed-up Folders Management (Manage Backups): remove the given folders
     * from the on-disk Folders.shift archive by rebuilding it without them.
     * The remaining archive is all-or-nothing — a folder is removed whole.
     * Refreshes the folder-archive card and the available-backup set after.
     */
    fun deleteArchivedFolders(context: Context, removePaths: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            // Rebuilding Folders.shift rewrites the archive a restore may be
            // streaming right now — the same shared-state hazard the run gate
            // covers for device backups. Refuse rather than corrupt it.
            // Acquire rather than merely check, for the same reason as
            // deleteDeviceBackups: reading isBusy and proceeding leaves the window
            // open for a concurrent run to start on the archive being rewritten.
            if (!com.phoenix.droidvault.utils.RunCoordinator.tryAcquire("delete:folders")) {
                val holder =
                    com.phoenix.droidvault.utils.RunCoordinator.currentOwner ?: "another operation"
                withContext(Dispatchers.Main) {
                    context.safeToast(
                        "Cannot edit the folders archive while $holder is running — try again when it finishes",
                        Toast.LENGTH_LONG
                    )
                }
                return@launch
            }
            val remaining = try {
                DeviceManager.deleteArchivedFolders(
                    getApplication<Application>(),
                    _savedPath.value,
                    removePaths,
                    getApplication<Application>().cacheDir.absolutePath
                )
            } finally {
                com.phoenix.droidvault.utils.RunCoordinator.release()
            }
            refreshDeviceBackups()
            refreshFolderArchive()
            withContext(Dispatchers.Main) {
                context.safeToast(
                    when {
                        remaining == null -> "Folders archive could not be updated"
                        remaining.isEmpty() -> "Folders archive deleted"
                        else -> "Removed ${removePaths.size} folder${if (removePaths.size == 1) "" else "s"} from backup"
                    },
                    Toast.LENGTH_SHORT
                )
            }
        }
    }

    /**
     * Dedicated Folders-only runs for the Migrate tab's own section. Thin
     * wrappers over the shared engine: [runDeviceDataOperation] with only
     * the FOLDERS item selected, using the given folder subset (empty = all
     * registered folders for backup / everything archived for restore).
     */
    fun runFoldersBackup(context: Context, folders: List<String>) {
        runDeviceDataOperation(
            context,
            isBackup = true,
            doSms = false, doCall = false, doContacts = false,
            doWifi = false, doWallpaper = false, doBluetooth = false,
            doFolders = true,
            folderPaths = if (folders.isEmpty()) backupFolders.value else folders
        )
    }

    fun runFoldersRestore(context: Context, folders: List<String>) {
        runDeviceDataOperation(
            context,
            isBackup = false,
            doSms = false, doCall = false, doContacts = false,
            doWifi = false, doWallpaper = false, doBluetooth = false,
            doFolders = true,
            folderPaths = folders
        )
    }
    val isFirstRun = MutableStateFlow(prefs.getBoolean("is_first_run", true))

    private val _themeMode = MutableStateFlow(prefs.getInt("theme_mode", 0))
    val themeMode: StateFlow<Int> = _themeMode.asStateFlow()

    fun setTheme(mode: Int) {
        _themeMode.value = mode
        prefs.edit { putInt("theme_mode", mode) }
    }

    /**
     * Dynamic colour is OFF unless the user turns it on.
     *
     * The default was `true`, so every install inherited the wallpaper's palette
     * — including the eight `DvRole` accents, which are contrast-tested against
     * Droid Vault's own surfaces. On a pastel wallpaper a "success" green drops
     * below the 4.5:1 body-text ratio, so a state that is supposed to read as
     * safe can read as a faint smudge, and the app's accent system stops being
     * one thing. Dynamic colour is a good opt-in and a bad identity: it makes the
     * same screen a different colour on every device.
     *
     * The key was already written by `setDynamicColor`, so existing installs
     * keep whatever they chose — only a fresh install sees the new default.
     */
    private val _dynamicColor = MutableStateFlow(prefs.getBoolean("dynamic_color", false))
    val dynamicColor: StateFlow<Boolean> = _dynamicColor.asStateFlow()

    fun setDynamicColor(enabled: Boolean) {
        _dynamicColor.value = enabled
        prefs.edit { putBoolean("dynamic_color", enabled) }
    }

    // Shell order: Home, Flash, Migrate, Tools, Auto.
    //
    // The app ALWAYS opens on Home. The previous build restored
    // `last_selected_tab_v2`, which meant a launch after a background schedule
    // dropped the user into whatever submenu the last run left behind — often
    // mid-flow, with no indication of where they were. The dashboard is the
    // product's front door and the natural "what now" screen; the persisted
    // preference is retained only so a mid-session restore is still possible.
    private val _currentTab = MutableStateFlow(TAB_HOME)
    val currentTab: StateFlow<Int> = _currentTab.asStateFlow()

    fun setTab(index: Int) {
        val safeIndex = index.coerceIn(0, 4)
        _currentTab.value = safeIndex
        prefs.edit { putInt("last_selected_tab_v2", safeIndex) }
    }

    /**
     * Return to the landing page.
     *
     * Also unwinds any submenu state the user is leaving behind. Without this a
     * later visit to that tab re-opened the page the user had just backed out
     * of, because only [setTab] cleaned the mode up.
     */
    fun goHome() {
        _currentTab.value = TAB_HOME
        closeComponentEditor()
        if (_uiState.value.migratorMode != MigratorMode.MENU) {
            _uiState.update { it.copy(migratorMode = MigratorMode.MENU) }
        }
    }

    // ---------- Editable Home quick access ----------

    private val _homeShortcuts = MutableStateFlow(HomeShortcut.parseList(prefs.getString(KEY_HOME_SHORTCUTS, null)))
    val homeShortcuts: StateFlow<List<HomeShortcut>> = _homeShortcuts.asStateFlow()

    private fun persistShortcuts(list: List<HomeShortcut>) {
        _homeShortcuts.value = list
        prefs.edit { putString(KEY_HOME_SHORTCUTS, HomeShortcut.toStorage(list)) }
    }

    /**
     * Add [item] if absent. Returns false when it was already there, so the
     * editor can stay open and explain rather than silently doing nothing.
     */
    fun addHomeShortcut(item: HomeShortcut): Boolean {
        if (_homeShortcuts.value.any { it == item }) return false
        persistShortcuts(_homeShortcuts.value + item)
        return true
    }

    /**
     * Returns false when the refusal below applied, so the caller can TELL the user.
     *
     * An empty row would be a dead zone on the landing page, so the last remaining
     * shortcut is refused — but refusing silently leaves an X button that does
     * nothing when pressed, in a UI whose whole promise is honesty. Same refusal
     * applies to unticking the last pin in the picker.
     */
    fun removeHomeShortcut(item: HomeShortcut): Boolean {
        val next = _homeShortcuts.value.filterNot { it == item }
        if (next.isEmpty()) return false
        persistShortcuts(next)
        return true
    }

    fun moveHomeShortcut(from: Int, to: Int) {
        val list = _homeShortcuts.value.toMutableList()
        if (from !in list.indices || to !in list.indices) return
        list.add(to, list.removeAt(from))
        persistShortcuts(list)
    }

    fun resetHomeShortcuts() = persistShortcuts(HomeShortcut.DEFAULT)

    // ---------- Home overview (storage + root manager) ----------

    data class StorageInfo(val freeBytes: Long, val totalBytes: Long) {
        val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0L)
        /** 0f..1f. A zero/unknown total reports 0 rather than dividing by zero. */
        val usedFraction: Float
            get() = if (totalBytes <= 0L) 0f else (usedBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
    }

    data class RootInfo(
        val granted: Boolean,
        /** e.g. "MAGISK:R", or "KernelSU" when Magisk's own version is absent. */
        val managerVersion: String,
        val managerCode: String
    )

    data class HomeOverview(
        val storage: StorageInfo?,
        val root: RootInfo?,
        /** How much of the vault is actually backed-up data, in KB. */
        val vaultKb: Long? = null,
        /** How many apps have an archive in the vault. */
        val backupCount: Int? = null
    )

    private val _overview = MutableStateFlow<HomeOverview?>(null)
    val overview: StateFlow<HomeOverview?> = _overview.asStateFlow()

    /**
     * The numbers behind the landing overview card.
     *
     * Storage comes from [android.os.StatFs] on the VAULT path rather than
     * `df`: it is a single syscall instead of a root fork, it needs no shell,
     * and it reports the filesystem the backups actually land on.
     *
     * The root line is Magisk's own `magisk -v` / `magisk -V`, which is the same
     * string every root app shows. Non-Magisk managers are detected by their
     * marker directory and reported honestly instead of borrowing Magisk's
     * format with made-up numbers.
     */
    fun refreshOverview() {
        viewModelScope.launch(Dispatchers.IO) {
            val path = _savedPath.value
            val storage = try {
                val stat = android.os.StatFs(if (path.isBlank()) "/" else path)
                StorageInfo(
                    freeBytes = stat.availableBytes,
                    totalBytes = stat.totalBytes
                )
            } catch (_: Exception) {
                null
            }
            // The vault's own size and how many apps are in it. This is what the
            // app is FOR, and the landing page was showing free space instead —
            // a number about the phone, not about the user's backups.
            // Two root forks, the same cost class as the storage stat above.
            val backupCount = try {
                Shell.cmd(
                    "ls -1 ${ShellQuote.q("$path/Apps/User")} ${ShellQuote.q("$path/Apps/System")} " +
                        "2>/dev/null | grep -c . "
                ).exec().out.joinToString("").trim().toIntOrNull()
            } catch (_: Exception) {
                null
            }
            val vaultKb = try {
                Shell.cmd("du -sk ${ShellQuote.q("$path/Apps")} ${ShellQuote.q("$path/Device")} 2>/dev/null")
                    .exec().out.sumOf {
                        it.trim().split("\\s+".toRegex()).firstOrNull()?.toLongOrNull() ?: 0L
                    }.takeIf { it > 0L }
            } catch (_: Exception) {
                null
            }
            val root = try {
                val v = Shell.cmd("magisk -v 2>/dev/null").exec().out.firstOrNull()?.trim()
                val code = Shell.cmd("magisk -V 2>/dev/null").exec().out.firstOrNull()?.trim()
                when {
                    !v.isNullOrEmpty() && !code.isNullOrEmpty() ->
                        RootInfo(granted = true, managerVersion = v, managerCode = code)
                    // Not Magisk. Report whichever manager's marker exists rather
                    // than inventing a version string.
                    Shell.cmd("[ -d /data/adb/ksu ] && echo YES").exec()
                        .out.any { it.contains("YES") } ->
                        RootInfo(true, "KernelSU", "")
                    Shell.cmd("[ -d /data/adb/ap ] && echo YES").exec()
                        .out.any { it.contains("YES") } ->
                        RootInfo(true, "APatch", "")
                    else -> RootInfo(appStateHasRoot(), "", "")
                }
            } catch (_: Exception) {
                null
            }
            _overview.value = HomeOverview(storage, root, vaultKb, backupCount)
        }
    }

    private fun appStateHasRoot(): Boolean = _uiState.value.hasRoot

    // ---------- Per-app component selection ----------

    /**
     * The app whose component page is open, or null.
     *
     * Held here rather than as Composable state because the selection it edits
     * lives in the ViewModel: a page opened from a list row must survive the row
     * scrolling out of view and back, and it has to be dismissible by the shell's
     * Back handler rather than only by an on-screen control.
     */
    private val _componentEditorPackage = MutableStateFlow<String?>(null)
    val componentEditorPackage: StateFlow<String?> = _componentEditorPackage.asStateFlow()

    private val _detailApps = MutableStateFlow<List<AppInfo>>(emptyList())
    /** The selection backing [componentEditorPackage], with stable icon paths. */
    val detailApps: StateFlow<List<AppInfo>> = _detailApps.asStateFlow()

    fun openComponentEditor(packageName: String) {
        if (_appList.value.none { it.packageName == packageName }) return
        _detailApps.value = _appList.value
        _componentEditorPackage.value = packageName
    }

    fun closeComponentEditor() {
        _componentEditorPackage.value = null
        _detailApps.value = emptyList()
    }

    /** The parts this app may be seeded with, given mode, archive and consent. */
    /**
     * What the component page shows checked, and what a toggle starts from.
     *
     * Both must read the same value or the page lies: an unselected app shows
     * nothing checked, so a toggle that began from the default set would add
     * part 1 to a hidden selection of all five and report "4 of 5 parts" after a
     * single tap.
     */
    fun pageSelection(app: AppInfo): Set<Int> = AppParts.pageSelection(
        isSelected = app.isSelected,
        activeComponents = app.activeComponents,
        // A mode with no per-app parts still needs SOME answer here; the empty
        // set is correct because there is no page to show in those modes.
        default = defaultSelectionFor(app) ?: emptySet()
    )

    fun selectableComponents(app: AppInfo): Set<Int> {
        val state = _uiState.value
        return if (state.migratorMode == MigratorMode.RESTORE_APPS) {
            AppParts.selectableForRestore(
                availableInBackup = app.availableInBackup,
                allowPermissions = state.allowPermRestore,
                allowSsaid = state.allowSsaidRestore
            )
        } else {
            AppParts.ALL
        }
    }

    /**
     * Flip one part for the app being edited.
     *
     * Turning a part on selects the app: the user asked for that part, so
     * running without the app selected would silently ignore the request.
     * Turning the last part off deselects it, since a selected app with nothing
     * to do is a run that does less than the screen shows.
     */
    fun toggleAppComponent(packageName: String, componentId: Int) {
        val state = _uiState.value
        val index = _appList.value.indexOfFirst { it.packageName == packageName }
        if (index < 0) return
        val app = _appList.value[index]
        val selectable = selectableComponents(app)
        val current = pageSelection(app)
        val next = AppParts.toggle(current, componentId, selectable)
        // No-op: an unselectable part, or a toggle that landed on the same set.
        if (next == current && app.isSelected == next.isNotEmpty()) return
        val list = _appList.value.toMutableList()
        list[index] = app.copy(activeComponents = next, isSelected = next.isNotEmpty())
        _appList.value = list
        _detailApps.value = list
        updateStorageInfo()
    }

    /** Select or clear every selectable part for one app (the Select all row). */
    fun setAllAppComponents(packageName: String, select: Boolean) {
        val index = _appList.value.indexOfFirst { it.packageName == packageName }
        if (index < 0) return
        val app = _appList.value[index]
        val selectable = selectableComponents(app)
        // Clearing when nothing is selectable would leave the app "selected with
        // zero parts", which reads as a mistake in the UI and does nothing.
        if (!select && selectable.isEmpty()) return
        val next = if (select) selectable else emptySet()
        val list = _appList.value.toMutableList()
        list[index] = app.copy(activeComponents = next, isSelected = select)
        _appList.value = list
        _detailApps.value = list
        updateStorageInfo()
    }

    /**
     * Write [components] onto every selected app — the "apply to all" action.
     *
     * This is the ONLY path that overwrites per-app choices, and it does so
     * explicitly: a per-app edit made on the detail page is otherwise never
     * clobbered by a later change to the default.
     */
    fun applyComponentsToAllSelected(components: Set<Int>) {
        val state = _uiState.value
        val list = _appList.value.map { app ->
            if (!app.isSelected) app
            else {
                val selectable = selectableComponents(app)
                // Never grant a part the mode or consent forbids: "apply to all"
                // must not become a way around the gate.
                app.copy(activeComponents = components intersect selectable)
            }
        }
        _appList.value = list
        _detailApps.value = list
        // Keep the default in step so a later newly-selected app matches what
        // the user just applied everywhere.
        _uiState.update { it.copy(globalComponents = components) }
        if (state.migratorMode == MigratorMode.RESTORE_APPS) updateStorageInfo()
    }

    /**
     * The parts a newly selected app starts with, or null in a mode that has no
     * per-app parts at all.
     *
     * Debloat, Systemize and Manage act on whole packages — there is nothing to
     * choose — so storing a set for them would be noise that the next refactor
     * would have to know about.
     */
    private fun defaultSelectionFor(app: AppInfo): Set<Int>? {
        val state = _uiState.value
        return when (state.migratorMode) {
            MigratorMode.RESTORE_APPS -> AppParts.defaultSelection(
                isRestore = true,
                availableInBackup = app.availableInBackup,
                allowPermissions = state.allowPermRestore,
                allowSsaid = state.allowSsaidRestore
            )

            MigratorMode.BACKUP_APPS -> state.globalComponents
            else -> null
        }
    }

    // ---------- One-shot cross-tab requests ----------

    /**
     * A device-data dialog the Home dashboard asked to open on the Migrate tab.
     *
     * The dialogs themselves are local state inside MigratorMenu, so a Home
     * shortcut cannot open one directly. This carries the intent across and is
     * consumed exactly once ([consumeDeviceDialogRequest]) so a later recomposition
     * — or the next visit to the tab — does not re-open a dialog the user
     * already dismissed. `null` means "nothing pending".
     */
    private val _deviceDialogRequest = MutableStateFlow<Boolean?>(null)
    val deviceDialogRequest: StateFlow<Boolean?> = _deviceDialogRequest.asStateFlow()

    /** true = backup, false = restore. */
    fun requestDeviceDialog(backup: Boolean) {
        _deviceDialogRequest.value = backup
    }

    fun consumeDeviceDialogRequest() {
        _deviceDialogRequest.value = null
    }

    private var lastNotificationUpdateTime = 0L

    /**
     * Guards the one process-wide resource that two independent startup paths
     * both reach for: the cached root shell. `LegacyMigration.complete` runs from
     * `Application.onCreate` and the ViewModel's own probe runs from `init`, and
     * neither knows about the other.
     */
    fun checkRootAccess(onDone: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isFetchingApps = true)
            // Drop the cached shell only when nothing else is using it. This used
            // to close unconditionally, and on a fresh install that raced the
            // one-time migration: `DroidVaultApp.onCreate` starts
            // `LegacyMigration.complete` on IO, which issues a root command
            // within milliseconds, and this closed the process-wide shell out
            // from under it — so the migration's verdict, including whether its
            // one-time DONE marker gets written, came from a destroyed shell.
            // Whichever got there first wins; the loser re-reads a fresh one.
            synchronized(shellTeardownLock) {
                val inUse = shellUsers.getAndIncrement()
                if (inUse == 0) Shell.getCachedShell()?.close()
            }
            val isRooted = Shell.getShell().isRoot
            // Probed through the UNROOTED shell deliberately: asking `command -v
            // su` as root would succeed on a device with no root manager at all,
            // because root's own PATH is not the app's.
            val hasSu = try {
                Shell.cmd("command -v su").exec().out.any { it.isNotBlank() }
            } catch (_: Throwable) {
                false
            }
            shellUsers.decrementAndGet()
            val rootState = classifyRoot(hasSu, isRooted)
            // The probe used to record NOTHING, so "why does the app think it has
            // no root" was unanswerable from a field report — and this gate is a
            // decision taken silently, which the conventions forbid.
            DroidVaultLogger.i(
                "Root",
                "probe: su binary=$hasSu shellIsRoot=$isRooted -> $rootState"
            )
            if (rootState == RootState.NotGranted) {
                DroidVaultLogger.w(
                    "Root",
                    "a root manager is present but did not grant Droid Vault; " +
                        "the screen must offer the manual route rather than a " +
                        "button that silently does nothing"
                )
            }
            _uiState.value = _uiState.value.copy(
                hasRoot = isRooted,
                rootState = rootState,
                isFetchingApps = false
            )

            if (isRooted) {
                val whitelistJob = async {
                    Shell.cmd("dumpsys deviceidle whitelist +com.phoenix.droidvault").exec()
                }

                val engineJob = async {
                    val success = BackendInstaller.installEngine(getApplication())
                    if (success) prefs.edit { putBoolean("is_engine_installed", true) }
                }

                val cleanupJob = async {
                    val commands = arrayOf(
                        "mkdir -p /data/local/tmp",
                        // Restore the AOSP mode instead of weakening it. An
                        // earlier version of this app ran `chmod 1777
                        // /data/local/tmp` on every launch, which handed
                        // world-WRITE on a system directory to every app on
                        // the device and let any of them pre-create (and keep
                        // ownership of) the staging dirs below — so a root
                        // `cp`/`mkdir -p` could be redirected at will.
                        // Nothing here needs world-write: every writer is the
                        // root shell, and system_server only needs to TRAVERSE
                        // the tree for install-write, which 0771 already allows.
                        "chmod 0771 /data/local/tmp",
                        "chown shell:shell /data/local/tmp",
                        // Own the staging dirs as root. 0711 keeps them
                        // listable by nobody but traversable by system_server,
                        // and root ownership removes the swap/symlink attack.
                        "rm -rf /data/local/tmp/droidvault_apps /data/local/tmp/droidvault_targets.txt",
                        "mkdir -p /data/local/tmp/droidvault_apps",
                        // 711: system_server (UID 1000) must traverse here for install-write.
                        // 700 breaks installs with "Unable to open file".
                        "chmod 0711 /data/local/tmp/droidvault_apps",
                        "chown root:root /data/local/tmp/droidvault_apps",
                        "pm grant ${getApplication<Application>().packageName} android.permission.PACKAGE_USAGE_STATS",
                        "appops set ${getApplication<Application>().packageName} GET_USAGE_STATS allow"
                    )
                    Shell.cmd(*commands).exec()
                    // Android 15+ marks every sideloaded app "restricted", which
                    // blocks the SMS role + SMS runtime permission a message
                    // restore needs. Root lifts it silently so the user never
                    // has to hunt for the App Info toggle.
                    com.phoenix.droidvault.utils.PermissionChecker
                        .grantRestrictedSettings(getApplication<Application>().packageName)
                }

            whitelistJob.await()
                engineJob.await()
                cleanupJob.await()
            }

            // Finish any SMS role handoff a previous run died during.
            healPendingSmsHandoff()
            // Optional completion hook (Settings "Re-grant" re-probes after
            // this; startup calls run hook-free). MUST run for BOTH outcomes:
            // the hook is what clears the caller's busy flag, so a denial that
            // never invoked it left Settings' "Re-grant" button permanently
            // disabled and its permission rows on a permanent spinner.
            //
            // Invoked on IO, NOT Main — deliberately, and unlike the three sibling
            // hooks above which wrap in `withContext(Dispatchers.Main)`. Do not
            // "align" this one: Settings' hook runs a root-shell engine probe with
            // a 10s bound, and moving it to Main puts that probe on the
            // composition thread. The Settings call site makes its own hop, so
            // correctness there does not depend on this dispatcher's accident
            // (§2.89).
            onDone?.invoke(isRooted)
        }
    }

    private fun createNotificationChannel() {
        // Channels are cosmetic startup work — a failure here must never take
        // down the ViewModel (and with it, the whole app).
        try {
            val progressChannel = NotificationChannel(
                CHANNEL_PROGRESS_ID,
                "Task Progress",
                NotificationManager.IMPORTANCE_LOW
            )
            val alertChannel = NotificationChannel(
                CHANNEL_ALERT_ID,
                "Task Completed",
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(progressChannel)
            notificationManager.createNotificationChannel(alertChannel)
        } catch (_: Exception) {
        }
    }

    private fun updateProgressNotification(
        title: String,
        content: String,
        progress: Int = -1,
        max: Int = 100
    ) {
        val currentTime = System.currentTimeMillis()
        if (progress != 100 && progress != -1 && currentTime - lastNotificationUpdateTime < 250) {
            return
        }
        lastNotificationUpdateTime = currentTime

        if (ContextCompat.checkSelfPermission(
                getApplication(),
                "android.permission.POST_NOTIFICATIONS"
            ) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        ) {

            val parts = content.split("\n")
            val mainText = parts[0]
            val subText = if (parts.size > 1) parts[1] else null

            val displayContent = if (progress in 0..100) "$mainText  •  $progress%" else mainText

            // Tap → reopen the live Processing screen (works from a cold start
            // too: MainActivity reads the extra in onCreate).
            val openIntent = Intent(
                getApplication(),
                com.phoenix.droidvault.MainActivity::class.java
            ).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(com.phoenix.droidvault.MainActivity.EXTRA_SHOW_PROGRESS, true)
            }
            val contentIntent = android.app.PendingIntent.getActivity(
                getApplication(),
                1001,
                openIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )

            val builder = NotificationCompat.Builder(getApplication(), CHANNEL_PROGRESS_ID)
                .setSmallIcon(R.drawable.ic_home)
                .setContentTitle(title)
                .setContentText(displayContent)
                .setContentIntent(contentIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setOnlyAlertOnce(true)
                .setOngoing(true)

            if (subText != null) {
                builder.setStyle(
                    NotificationCompat.BigTextStyle().bigText("$displayContent\n$subText")
                )
            }

            // Cancel action — same receiver/flag the automation progress
            // notification uses, so manual Migrate runs are cancellable from
            // the shade exactly like scheduled runs.
            builder.addAction(
                0,
                "Cancel",
                android.app.PendingIntent.getBroadcast(
                    getApplication(),
                    0,
                    Intent(getApplication(), com.phoenix.droidvault.utils.RunCancelReceiver::class.java)
                        .setAction(com.phoenix.droidvault.utils.AutomationNotifications.CANCEL_ACTION),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )

            if (progress in 0..100) {
                builder.setProgress(max, progress, false)
            } else {
                builder.setProgress(0, 0, true)
            }

            notificationManager.notify(NOTIFICATION_ID, builder.build())
        }
    }

    private fun showCompletionNotification(title: String, content: String) {
        if (ContextCompat.checkSelfPermission(
                getApplication(),
                "android.permission.POST_NOTIFICATIONS"
            ) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        ) {
            val openIntent = Intent(
                getApplication(),
                com.phoenix.droidvault.MainActivity::class.java
            ).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(com.phoenix.droidvault.MainActivity.EXTRA_SHOW_PROGRESS, true)
            }
            val contentIntent = android.app.PendingIntent.getActivity(
                getApplication(),
                1001,
                openIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val builder = NotificationCompat.Builder(getApplication(), CHANNEL_ALERT_ID)
                .setSmallIcon(R.drawable.ic_home)
                .setContentTitle(title)
                .setContentText(content)
                .setContentIntent(contentIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setDefaults(NotificationCompat.DEFAULT_ALL)

            notificationManager.notify(NOTIFICATION_ID, builder.build())
        }
    }

    private fun cancelNotification() {
        notificationManager.cancel(NOTIFICATION_ID)
    }

    fun finishOnboarding() {
        prefs.edit { putBoolean("is_first_run", false) }
        isFirstRun.value = false
    }

    fun openFlashWizard() {
        _flashActions.value = emptyList()
        _uiState.value = _uiState.value.copy(
            flashWizardStep = 1,
            flashWipePartitions = setOf("dalvik", "cache"),
            flashFormatData = false,
            flashRebootOption = "system"
        )
    }

    fun flashWizardStepBack() {
        val currentStep = _uiState.value.flashWizardStep
        if (currentStep > 1) {
            // Step 4 is the only step that has ARMED something on disk. Backing
            // out of it must disarm it: the script can carry `format data`,
            // `wipe data`, `install` and `reboot`, it survives leaving the tab
            // and process death, and it fires on the NEXT recovery boot for any
            // reason at all — with nothing on screen saying it was armed.
            if (currentStep == 4) disarmRecoveryScript()
            _uiState.value = _uiState.value.copy(flashWizardStep = currentStep - 1)
        } else {
            closeFlashWizard()
        }
    }

    fun closeFlashWizard() {
        if (_uiState.value.flashWizardStep > 0) disarmRecoveryScript()
        _uiState.value = _uiState.value.copy(flashWizardStep = 0)
    }

    /**
     * Remove a recovery script this app wrote.
     *
     * Best effort and off the caller's thread: the wizard must close whether or
     * not the engine answers, because a failure here is not worth trapping the
     * user inside a destructive screen. It is logged when it fails so a field
     * report can say the script was still armed.
     */
    fun disarmRecoveryScript() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Shell.cmd("sh /data/adb/DroidVault/DroidVault.sh --clear-ors").exec()
            } catch (t: Throwable) {
                com.phoenix.droidvault.utils.DroidVaultLogger.w(
                    "Flash", "could not clear the recovery script: ${t.message}"
                )
            }
        }
    }

    fun analyzeApps(uris: List<Uri>, showInstaller: Boolean = false, isIntent: Boolean = false) {
        val context = getApplication<Application>()
        val initialApps = uris.mapIndexed { index, uri ->
            val resolvedPath = FlashManager.getPathFromUri(context, uri) ?: ""

            AppInstallInfo(
                path = resolvedPath,
                uriString = uri.toString(),
                status = "Analyzing",
                label = "Analyzing app ${index + 1}..."
            )
        }

        _uiState.update {
            it.copy(
                isAnalyzingApps = true,
                showAppInstaller = if (showInstaller) true else it.showAppInstaller,
                isInstallerIntent = isIntent,
                batchInstallApps = if (isIntent) initialApps else it.batchInstallApps + initialApps,
                totalInstallTimeSeconds = 0L
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val pm = context.packageManager
            val tempDir = File(context.cacheDir, "app_analysis")
            tempDir.mkdirs()

            coroutineScope {
                initialApps.forEach { initialApp ->
                    launch(Dispatchers.IO) {
                        val startTime = System.currentTimeMillis()
                        val uri = Uri.parse(initialApp.uriString)
                        val originalPath = initialApp.path

                        val analysisDir = File(
                            tempDir,
                            "analysis_${System.currentTimeMillis()}_${java.util.UUID.randomUUID()}"
                        )
                        analysisDir.mkdirs()

                        try {
                            val stagingDir = "/data/local/tmp/droidvault_install"
                            // 711 so UID 1000 can traverse for install-write (see backend note).
                            // `mkdir -p` on a pre-existing directory owned by
                            // another uid succeeds and keeps that ownership, so
                            // force root:root — otherwise an unprivileged app
                            // that created this path first could swap the staged
                            // APK between our analysis and the user's install.
                            Shell.cmd("rm -rf ${ShellQuote.q(stagingDir)} && mkdir -p ${ShellQuote.q(stagingDir)} && chmod 711 ${ShellQuote.q(stagingDir)} && chown root:root ${ShellQuote.q(stagingDir)}").exec()

                            val ext = getExtensionFromUri(context, uri)
                            val tempInputFile = File(analysisDir, "input_app.$ext")
                            
                            var success = false
                            var localAnalysisPath = ""

                            if (originalPath.isNotEmpty()) {
                                try {
                                    val source = File(originalPath)
                                    if (source.exists() && source.canRead()) {
                                        localAnalysisPath = originalPath
                                        success = true
                                    }
                                } catch (_: Exception) {
                                }
                            }

                            if (!success) {
                                try {
                                    context.contentResolver.openInputStream(uri)?.use { input ->
                                        tempInputFile.outputStream()
                                            .use { output -> input.copyTo(output) }
                                    }
                                    if (tempInputFile.exists() && tempInputFile.length() > 0) {
                                        localAnalysisPath = tempInputFile.absolutePath
                                        success = true
                                    }
                                } catch (e: Exception) {
                                    Log.e("DroidVault_Batch", "Staging Error: ${e.message}")
                                }
                            }

                            if (success) {
                                var analysisApkPath = localAnalysisPath

                                if (ext != "apk" && ext.isNotEmpty() && ext in listOf(
                                        "apks",
                                        "xapk",
                                        "apkm",
                                        "zip"
                                    )
                                ) {
                                    val unzipDir = File(analysisDir, "unzipped")
                                    unzipDir.mkdirs()

                                    var extractedBaseFile: File? = null
                                    try {
                                        java.util.zip.ZipFile(File(localAnalysisPath)).use { zip ->
                                            val entries = zip.entries().asSequence()
                                            val targetEntry =
                                                entries.find { it.name.endsWith("base.apk", true) }
                                                    ?: zip.entries().asSequence()
                                                        .filter { it.name.endsWith(".apk", true) }
                                                        .maxByOrNull { it.size }

                                            if (targetEntry != null) {
                                                val targetFile = File(
                                                    unzipDir,
                                                    targetEntry.name.substringAfterLast("/")
                                                )
                                                zip.getInputStream(targetEntry).use { input ->
                                                    targetFile.outputStream()
                                                        .use { output -> input.copyTo(output) }
                                                }
                                                extractedBaseFile = targetFile
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.e(
                                            "DroidVault_Batch",
                                            "Fast extraction failed: ${e.message}"
                                        )
                                    }

                                    extractedBaseFile?.let {
                                        analysisApkPath = it.absolutePath
                                    }
                                }

                                val info = pm.getPackageArchiveInfo(analysisApkPath, 0)
                                if (info != null && info.applicationInfo != null) {
                                    val appInfo = info.applicationInfo!!
                                    appInfo.sourceDir = analysisApkPath
                                    appInfo.publicSourceDir = analysisApkPath

                                    val label = appInfo.loadLabel(pm).toString()
                                    val pkgName = info.packageName
                                    val vCode = PackageInfoCompat.getLongVersionCode(info)

                                    val targetSdkInt = appInfo.targetSdkVersion
                                    val verName = getAndroidVersion(targetSdkInt)
                                    val targetSdk =
                                        if (verName.startsWith("API")) verName else "Android $verName"

                                    val arch = getArchitecture(analysisApkPath)
                                    val iconPath = getIconPath(pm, appInfo, pkgName)

                                    val finalPath =
                                        "$stagingDir/${pkgName}_${System.currentTimeMillis()}.${ext}"
                                    ShellQuote.requirePackage(pkgName)
                                    // 644: install-write runs as UID 1000 and must read this file.
                                    Shell.cmd("cp ${ShellQuote.q(localAnalysisPath)} ${ShellQuote.q(finalPath)} && chmod 644 ${ShellQuote.q(finalPath)}")
                                        .exec()

                                    val updatedApp = initialApp.copy(
                                        label = label,
                                        packageName = pkgName,
                                        path = finalPath,
                                        version = info.versionName ?: "Unknown",
                                        versionCode = vCode,
                                        size = formatPreciseSize(File(localAnalysisPath).length()),
                                        installedVersion = try {
                                            pm.getPackageInfo(pkgName, 0).versionName
                                        } catch (_: Exception) {
                                            null
                                        },
                                        installedVersionCode = try {
                                            PackageInfoCompat.getLongVersionCode(
                                                pm.getPackageInfo(
                                                    pkgName,
                                                    0
                                                )
                                            )
                                        } catch (_: Exception) {
                                            null
                                        },
                                        isInstalled = try {
                                            pm.getPackageInfo(pkgName, 0); true
                                        } catch (_: Exception) {
                                            false
                                        },
                                        iconPath = iconPath,
                                        status = "Pending",
                                        isAnalysisComplete = true,
                                        targetSdk = targetSdk,
                                        architecture = arch
                                    )

                                    withContext(Dispatchers.Main) {
                                        _uiState.update { state ->
                                            val existing =
                                                state.batchInstallApps.find { it.packageName == pkgName && it.isAnalysisComplete }
                                            if (existing != null) {
                                                if (vCode > existing.versionCode) {
                                                    state.copy(batchInstallApps = state.batchInstallApps.filterNot { it.path == existing.path }
                                                        .map { if (it.uriString == initialApp.uriString) updatedApp else it })
                                                } else {
                                                    state.copy(batchInstallApps = state.batchInstallApps.filterNot { it.uriString == initialApp.uriString })
                                                }
                                            } else {
                                                state.copy(batchInstallApps = state.batchInstallApps.map { if (it.uriString == initialApp.uriString) updatedApp else it })
                                            }
                                        }
                                    }
                                    Log.d(
                                        "DroidVault_Batch",
                                        "Total Analysis Time: ${System.currentTimeMillis() - startTime}ms"
                                    )
                                } else {
                                    throw Exception("Failed to parse package info")
                                }
                            } else {
                                throw Exception("Failed to access file data")
                            }
                        } catch (e: Exception) {
                            Log.e("DroidVault_Batch", "Analysis CRASHED: ${e.message}")
                            withContext(Dispatchers.Main) {
                                context.safeToast(
                                    "Skipping invalid file: ${e.message}",
                                    Toast.LENGTH_SHORT
                                )
                                _uiState.update { state ->
                                    val newList =
                                        state.batchInstallApps.filterNot { it.uriString == initialApp.uriString }
                                    state.copy(
                                        batchInstallApps = newList,
                                        showAppInstaller = if (newList.isEmpty()) false else state.showAppInstaller
                                    )
                                }
                            }
                        } finally {
                            Shell.cmd("rm -rf -- ${ShellQuote.q(analysisDir.absolutePath)}").exec()
                        }
                    }
                }
            }

            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(isAnalyzingApps = false) }
            }
        }
    }

    private fun getExtensionFromUri(context: Context, uri: Uri): String {
        val resolvedPath = FlashManager.getPathFromUri(context, uri)
        if (!resolvedPath.isNullOrEmpty()) {
            val ext = resolvedPath.substringAfterLast(".", "").lowercase()
            if (ext.length in 2..5 && ext.all { it.isLetterOrDigit() }) return ext
        }

        try {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name =
                        cursor.getString(cursor.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME))
                    val ext = name.substringAfterLast(".", "").lowercase()
                    if (ext.length in 2..5 && ext.all { it.isLetterOrDigit() }) return ext
                }
            }
        } catch (_: Exception) {
        }

        val mime = context.contentResolver.getType(uri)
        if (mime != null) {
            if (mime.contains("android.package-archive")) return "apk"
            android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
                ?.let { return it }
        }

        return "apk"
    }

    private fun formatPreciseSize(bytes: Long): String = Formatters.formatBytes(bytes)

    private fun getAndroidVersion(api: Int): String {
        return when (api) {
            37 -> "17"; 36 -> "16"; 35 -> "15"; 34 -> "14"; 33 -> "13"; 32 -> "12.1"; 31 -> "12"
            30 -> "11"; 29 -> "10"; 28 -> "9"; 27 -> "8.1"; 26 -> "8"; 25 -> "7.1"; 24 -> "7"
            else -> "API $api"
        }
    }

    private fun getArchitecture(path: String): String {
        if (path.isEmpty()) return "Universal"
        return try {
            val out = Shell.cmd("unzip -l ${ShellQuote.q(path)}").exec().out.joinToString("\n")
            when {
                out.contains("lib/arm64-v8a/") -> "arm64-v8a"
                out.contains("lib/armeabi-v7a/") -> "armeabi-v7a"
                out.contains("lib/x86_64/") -> "x86_64"
                out.contains("lib/x86/") -> "x86"
                else -> "Universal"
            }
        } catch (_: Exception) {
            "Universal"
        }
    }

    private fun getIconPath(
        pm: PackageManager,
        appInfo: android.content.pm.ApplicationInfo,
        pkgName: String
    ): String? {
        val icon = appInfo.loadIcon(pm)
        val iconDir = File(getApplication<Application>().cacheDir, "icons")
        iconDir.mkdirs()
        val iconFile = File(iconDir, "$pkgName.png")

        try {
            val bitmap = if (icon is BitmapDrawable) {
                icon.bitmap
            } else {
                val b = createBitmap(
                    icon.intrinsicWidth.coerceAtLeast(1),
                    icon.intrinsicHeight.coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(b)
                icon.setBounds(0, 0, canvas.width, canvas.height)
                icon.draw(canvas)
                b
            }
            FileOutputStream(iconFile).use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            return iconFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    fun executeBatchInstall() {
        val allApps = _uiState.value.batchInstallApps
        val apps = allApps.filter { it.isSelected && it.isAnalysisComplete }
        if (apps.isEmpty()) return

        val startTime = System.currentTimeMillis()
        _uiState.update {
            it.copy(
                isRunning = true,
                currentAction = "Installing Apps...",
                progress = -1,
                installStartTime = startTime
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            // Engine (root) installs EVERY line it reads: sanitize labels and
            // reject any field that could break the pipe/newline protocol
            // (audit A-02 — a crafted APK label injected an extra root install).
            val lines = apps.mapNotNull {
                ShellQuote.installerTargetLine(it.path, it.packageName, it.label)
            }
            if (lines.isEmpty()) {
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        currentAction = "Nothing to install",
                        currentStep = "No valid install targets."
                    )
                }
                return@launch
            }
            val targetFile = File(context.cacheDir, "install_targets.txt")
            targetFile.writeText(lines.joinToString("\n"))
            targetFile.setReadable(true, true)
            targetFile.setWritable(true, true)

            Shell.cmd("chmod 600 ${ShellQuote.q(targetFile.absolutePath)}").exec()

            val activeLabels = mutableListOf<String>()
            val env = com.phoenix.droidvault.utils.InstallerPolicy.shellEnv(context)

            val installResult = Shell.cmd("$env sh /data/adb/DroidVault/DroidVault.sh --install-apps ${ShellQuote.q(targetFile.absolutePath)}")
                .to(object : java.util.ArrayList<String>() {
                    override fun add(element: String): Boolean {
                        if (element.startsWith("INFO:STEP|MSG:INSTALLING|")) {
                            val pkg = element.substringAfter("PKG:").substringBefore("|")
                            // Stop at the field delimiter: the engine appends
                            // |JOBS:N after LABEL, and an unterminated
                            // substringAfter yielded "My App|JOBS:1" into the
                            // active-label list shown as "Installing …".
                            val label = element.substringAfter("LABEL:").substringBefore("|")
                            viewModelScope.launch(Dispatchers.Main) {
                                synchronized(activeLabels) {
                                    if (!activeLabels.contains(label)) activeLabels.add(label)
                                }
                                _uiState.update { state ->
                                    val currentText = synchronized(activeLabels) {
                                        activeLabels.take(3).joinToString(", ")
                                    }
                                    state.copy(
                                        currentStep = "Installing $currentText...",
                                        batchInstallApps = state.batchInstallApps.map {
                                            if (it.packageName == pkg) it.copy(status = "Installing") else it
                                        }
                                    )
                                }
                            }
                        } else if (element.startsWith("ACTION:INSTALL_DONE|PKG:")) {
                            val pkg = element.substringAfter("PKG:")
                            viewModelScope.launch(Dispatchers.Main) {
                                _uiState.update { state ->
                                    val labelToRemove =
                                        state.batchInstallApps.find { it.packageName == pkg }?.label
                                    synchronized(activeLabels) {
                                        activeLabels.remove(labelToRemove)
                                    }
                                    val currentText = synchronized(activeLabels) {
                                        activeLabels.take(3).joinToString(", ")
                                    }
                                    state.copy(
                                        currentStep = if (currentText.isNotEmpty()) "Installing $currentText..." else "Finishing up...",
                                        batchInstallApps = state.batchInstallApps.map {
                                            if (it.packageName == pkg) it.copy(status = "Done") else it
                                        }
                                    )
                                }
                            }
                        } else if (element.startsWith("ACTION:INSTALL_ERROR|PKG:")) {
                            val pkg = element.substringAfter("PKG:").substringBefore("|")
                            viewModelScope.launch(Dispatchers.Main) {
                                _uiState.update { state ->
                                    val labelToRemove =
                                        state.batchInstallApps.find { it.packageName == pkg }?.label
                                    synchronized(activeLabels) {
                                        activeLabels.remove(labelToRemove)
                                    }
                                    val currentText = synchronized(activeLabels) {
                                        activeLabels.take(3).joinToString(", ")
                                    }
                                    state.copy(
                                        currentStep = if (currentText.isNotEmpty()) "Installing $currentText..." else "Error occurred during some installs.",
                                        batchInstallApps = state.batchInstallApps.map {
                                            if (it.packageName == pkg) it.copy(status = "Error") else it
                                        }
                                    )
                                }
                            }
                        }
                        return super.add(element)
                    }
                }).exec()

            val endTime = System.currentTimeMillis()
            val totalSeconds = (endTime - startTime) / 1000

            withContext(Dispatchers.Main) {
                val finalApps =
                    _uiState.value.batchInstallApps.filter { it.isSelected && it.isAnalysisComplete }
                val successCount = finalApps.count { it.status == "Done" }
                val errorCount = finalApps.count { it.status == "Error" }
                // A non-zero shell exit means the engine never ran to
                // completion (missing/stale engine, revoked root, launcher
                // unreadable). No ACTION:INSTALL_* line ever arrives in that
                // case, so successCount and errorCount are both 0 — and the
                // unguarded branch below reported "Installation Complete /
                // Installed 0 apps", i.e. success for work that never
                // happened. Decide from the exit code first.
                val engineFailed = !installResult.isSuccess

                val title = when {
                    engineFailed -> "Installation Failed"
                    errorCount > 0 -> "Installation Finished with Errors"
                    else -> "Installation Complete"
                }
                val content = when {
                    engineFailed ->
                        "Could not run the installer (exit ${installResult.code}). " +
                            "Grant root access to Droid Vault and try again."
                    errorCount > 0 ->
                        "Installed $successCount, Failed $errorCount apps in ${totalSeconds}s."
                    else -> "Installed $successCount apps in ${totalSeconds}s."
                }
                if (engineFailed) {
                    _uiState.update { state ->
                        state.copy(
                            batchInstallApps = state.batchInstallApps.map {
                                if (it.isSelected && it.isAnalysisComplete) it.copy(status = "Error") else it
                            }
                        )
                    }
                }

                showCompletionNotification(title, content)
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        currentAction = title,
                        currentStep = content,
                        progress = 100,
                        totalInstallTimeSeconds = totalSeconds
                    )
                }
                targetFile.delete()
                Shell.cmd("rm -rf /data/local/tmp/droidvault_install").exec()
            }
        }
    }

    fun closeAppInstaller(onFinish: (() -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            Shell.cmd("rm -rf /data/local/tmp/droidvault_install").exec()
        }
        
        val wasIntent = _uiState.value.isInstallerIntent
        _uiState.update {
            it.copy(
                showAppInstaller = false,
                batchInstallApps = emptyList(),
                isInstallerIntent = false,
                totalInstallTimeSeconds = 0L
            )
        }
        if (wasIntent) {
            viewModelScope.launch {
                kotlinx.coroutines.delay(100.milliseconds)
                onFinish?.invoke()
            }
        }
    }

    fun toggleAppInstallSelection(path: String) {
        _uiState.update { state ->
            state.copy(batchInstallApps = state.batchInstallApps.map {
                if (it.path == path) it.copy(isSelected = !it.isSelected) else it
            })
        }
    }

    fun removeAction(index: Int) {
        val l = _flashActions.value.toMutableList()
        if (index in l.indices) {
            l.removeAt(index)
            _flashActions.value = l
        }
    }

    fun moveActionUp(index: Int) {
        if (index > 0) {
            val l = _flashActions.value.toMutableList()
            val item = l.removeAt(index)
            l.add(index - 1, item)
            _flashActions.value = l
        }
    }

    fun moveActionDown(index: Int) {
        val l = _flashActions.value.toMutableList()
        if (index < l.size - 1) {
            val item = l.removeAt(index)
            l.add(index + 1, item)
            _flashActions.value = l
        }
    }

    fun processSelectedZips(uris: List<Uri>, append: Boolean = false) {
        _uiState.value = _uiState.value.copy(isProcessingZips = true)
        viewModelScope.launch(Dispatchers.IO) {
            val currentZips =
                if (append) _flashActions.value.filterIsInstance<FlashAction.InstallZip>()
                    .map { it.zip } else emptyList()
            val newZips = com.phoenix.droidvault.utils.FlashOps.processZips(uris, currentZips, append, getApplication())
            
            withContext(Dispatchers.Main) {
                val actions = mutableListOf<FlashAction>()

                if (!append && _uiState.value.flashWipePartitions.isNotEmpty()) {
                    actions.add(FlashAction.Wipe(_uiState.value.flashWipePartitions))
                }

                if (append) {
                    val existing = _flashActions.value.toMutableList()
                    newZips.filter { nz -> _flashActions.value.none { it is FlashAction.InstallZip && it.zip.path == nz.path } }
                        .forEach { existing.add(FlashAction.InstallZip(it)) }
                    _flashActions.value = existing
                } else {
                    newZips.forEach { actions.add(FlashAction.InstallZip(it)) }
                    if (_uiState.value.flashFormatData) {
                        actions.add(FlashAction.FormatData)
                    }
                    _flashActions.value = actions
                }
                
                _uiState.value = _uiState.value.copy(
                    isProcessingZips = false,
                    flashWizardStep = 2
                )
            }
        }
    }

    fun checkLockscreenAndProceed() {
        viewModelScope.launch(Dispatchers.IO) {
            val isLocked = FlashManager.checkLockscreen()
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(hasLockscreen = isLocked, flashWizardStep = 3)
            }
        }
    }

    fun toggleFlashWipePartition(partition: String) {
        val current = _uiState.value.flashWipePartitions.toMutableSet()
        if (current.contains(partition)) current.remove(partition) else current.add(partition)
        _uiState.value = _uiState.value.copy(flashWipePartitions = current)
    }

    fun setFlashFormatData(format: Boolean) {
        _uiState.value = _uiState.value.copy(flashFormatData = format)
    }

    fun setFlashRebootOption(option: String) {
        _uiState.value = _uiState.value.copy(flashRebootOption = option)
    }

    fun generateOrsAndProceed() {
        viewModelScope.launch(Dispatchers.IO) {
            com.phoenix.droidvault.utils.FlashOps.generateOrs(
                _flashActions.value,
                _uiState.value.flashRebootOption
            )
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(flashWizardStep = 4) }
            }
        }
    }

    fun restartFlashWizard() {
        viewModelScope.launch(Dispatchers.IO) {
            FlashManager.restartFlashWizard(); withContext(Dispatchers.Main) {
            _flashActions.value = emptyList()
            _uiState.value = _uiState.value.copy(
                flashWizardStep = 1,
            )
        }
        }
    }

    fun executeFlashNow() {
        _uiState.value = _uiState.value.copy(
            currentAction = "Rebooting to Recovery...",
            flashWizardStep = 4
        ); viewModelScope.launch(Dispatchers.IO) { FlashManager.executeFlashNow() }
    }

    fun getAllPartitions(): List<String> {
        val parts = FlashManager.getAllPartitions().toMutableList()
        return parts.sorted()
    }

    fun getBackedUpImages(): List<String> = FlashManager.getBackedUpImages(_savedPath.value)
    fun deleteLivePartitionImage(imgName: String) =
        FlashManager.deleteLivePartitionImage(_savedPath.value, imgName)

    fun runLiveOperation(
        action: String,
        partition: String,
        customPath: String? = null,
        onComplete: (String) -> Unit
    ) {
        // A partition image drives the SAME `droidvault-engine` binary as every
        // backup, and it was the one engine-driving operation that took NO run
        // gate. So a `dd` could run beside a backup or a debloat — and any of
        // them hitting its watchdog would `pkill -f droidvault-engine` straight
        // through the middle of it. Two engines, one name, one kill.
        //
        // §2.57 removed the clean-finish kill; this removes the overlap that
        // made it reachable at all.
        val owner = "flash:${action.removePrefix("--")}"
        if (!RunCoordinator.tryAcquire(owner)) {
            val holder = RunCoordinator.currentOwner ?: "another operation"
            DroidVaultLogger.w("Gate", "$owner refused: gate held by $holder")
            onComplete("$holder is still running. Try again when it finishes.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            // Honest result (audit A-11): the engine's success token gates the
            // message — a failed partition flash must never read "success".
            val ok = try {
                FlashManager.runLiveOperation(action, partition, customPath, _savedPath.value)
            } catch (_: Exception) {
                false
            }
            val msg = when {
                ok && action == "--live-backup" -> "Backed up $partition successfully!"
                ok -> "Flashed $partition successfully!"
                action == "--live-backup" -> "Backup failed for $partition. Check the log and try again."
                else -> "Flash failed for $partition. Nothing was reported as written."
            }
            RunCoordinator.release()
            withContext(Dispatchers.Main) { onComplete(msg) }
        }
    }

    /**
     * Move/merge the vault folder and re-point every path-derived listing.
     * [onResult] receives true only when the move verifiably succeeded; on
     * failure the old vault is untouched, the failure is surfaced, and the
     * saved path does NOT move (previously this always reported success, so
     * a failed move silently stranded the vault — audit A-01).
     */
    fun migrateFolder(newPath: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val oldPath = _savedPath.value.trimEnd('/')
            val destination = newPath.trimEnd('/').ifEmpty { "/" }

            // Moving the vault renames or merges the exact tree a running
            // engine is streaming, then `rm -rf`s the source. Settings is
            // reachable mid-run (a device-data run leaves migratorMode at MENU
            // and the gear live), so without this the backup's own directory
            // gets pulled out from under it. Refuse like deleteDeviceBackups.
            val busyHolder = com.phoenix.droidvault.utils.RunCoordinator.currentOwner
            if (com.phoenix.droidvault.utils.RunCoordinator.isBusy) {
                val holder = busyHolder ?: "another operation"
                withContext(Dispatchers.Main) {
                    getApplication<Application>().safeToast(
                        "Cannot move the vault while $holder is running — try again when it finishes",
                        Toast.LENGTH_LONG
                    )
                    onResult(false)
                }
                return@launch
            }

            val moved = try {
                ShellQuote.requireAbsPath(destination)
                if (oldPath == destination) {
                    // First-run auto-detection pre-fills the current path. A
                    // confirmation of that same path is a valid operation:
                    // ensure the marker exists and persist the selection
                    // instead of treating it as a failed move.
                    val marker = ShellQuote.q("$destination/.droidvault_dir")
                    Shell.cmd(
                        "mkdir -p ${ShellQuote.q(destination)} && touch $marker && [ -f $marker ]"
                    ).exec().isSuccess.also { ok ->
                        if (ok) prefs.edit { putString("base_path", destination) }
                    }
                } else {
                    SettingsManager.migrateFolder(oldPath, destination, prefs)
                }
            } catch (_: Exception) {
                false
            }
            if (!moved) {
                withContext(Dispatchers.Main) {
                    getApplication<Application>().safeToast(
                        "Could not move the vault folder. Your existing files were left untouched — check the path and try again.",
                        Toast.LENGTH_LONG
                    )
                    onResult(false)
                }
                return@launch
            }
            _savedPath.value = destination
            liveBasePath = destination
            // The verdict belongs to the folder it was taken in. Reloading here
            // cannot carry the old folder's report over: `VaultVerdict.forVault`
            // path-matches, so a verdict about the vault just left behind reads
            // back as null, which is the honest "not checked yet".
            reloadVerdict()
            // Auto-import: a folder the user just chose may hold a previous
            // device's automation-state.json — pick it up immediately so the
            // app is fully restored without hunting through menus.
            com.phoenix.droidvault.utils.AutomationStore.autoImportStateFromFile(getApplication(), destination)
            // ROM-survival mirrors must exist in the NEW folder too — the old
            // folder's copies moved with it, but re-mirroring is cheap and
            // hash-gated + existence-probed (no-ops when the copies came
            // along and the APK/backend didn't change).
            try {
                com.phoenix.droidvault.utils.AutomationStore.syncPrimaryStateFile(getApplication())
                com.phoenix.droidvault.utils.AutomationSnapshot.snapshotSelfApk(getApplication(), destination)
                com.phoenix.droidvault.utils.AutomationSnapshot.snapshotBackend(getApplication(), destination)
            } catch (_: Exception) {
            }
            // Re-point every path-derived listing (backups, archives, restore
            // lists) at the new folder so all tabs reflect it instantly.
            refreshPathBoundState()
            updateStorageInfo()
            withContext(Dispatchers.Main) { onResult(true) }
        }
    }

    fun autoDetectVaultFolder(onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val searchCmd =
                "find /data/media/0 /mnt/media_rw -maxdepth 5 -type f -name '.droidvault_dir' 2>/dev/null | head -n 1"
            val markerFile = Shell.cmd(searchCmd).exec().out.joinToString("").trim()
            var detected =
                if (markerFile.isNotEmpty()) markerFile.removeSuffix("/.droidvault_dir") else ""

            if (detected.startsWith("/data/media/0")) {
                detected = detected.replaceFirst("/data/media/0", "/storage/emulated/0")
            } else if (detected.startsWith("/mnt/media_rw/")) {
                detected = detected.replaceFirst("/mnt/media_rw/", "/storage/")
            }

            if (detected.isEmpty()) {
                detected = SettingsManager.autoDetectFolder(prefs) ?: ""
            }

            withContext(Dispatchers.Main) {
                if (detected.isNotEmpty()) {
                    _savedPath.value = detected
                    liveBasePath = detected
                    // Same rule as the move path: a verdict describes ONE folder's
                    // archives, so pointing the app at another one must re-read it
                    // rather than keep vouching for (or refusing) files it never
                    // read.
                    reloadVerdict()
                    getApplication<Application>().safeToast(
                        "Auto-detected folder at: $detected",
                        Toast.LENGTH_SHORT
                    )
                    onResult(true)
                } else {
                    getApplication<Application>().safeToast(
                        "No existing Droid Vault folder found. Please select manually.",
                        Toast.LENGTH_SHORT
                    )
                    onResult(false)
                }
            }
        }
    }

    fun setDefaultSmsHandled() {
        _uiState.update { it.copy(requestDefaultSms = false) }
    }

    /** Returns true if `pkg` is now the default SMS app. Never throws.
     * Uses a single atomic shell invocation: releasing the role kills our process,
     * so anything issued after that point would never run. Verified via
     * Settings.Secure (no runtime perm needed). */
    private fun trySetDefaultSms(pkg: String): Boolean {
        return try {
            DeviceManager.releaseSmsRoleTo(pkg, getApplication<Application>().packageName)
            val resolver = getApplication<Application>().contentResolver
            android.provider.Settings.Secure.getString(resolver, "sms_default_application") == pkg
        } catch (e: Exception) {
            Log.w("MainViewModel", "SMS handoff failed: ${e.message}")
            false
        }
    }

    /** Startup self-heal: if a previous run died mid-handoff, finish returning the role.
     * Uses Settings.Secure (no runtime perm needed — ours may have been revoked by
     * the kill) instead of Telephony.Sms.getDefaultSmsPackage (throws without READ_SMS). */
    fun healPendingSmsHandoff() {
        viewModelScope.launch(Dispatchers.IO) {
            val pending = prefs.getString(KEY_PENDING_SMS_RESTORE, null)
            if (!pending.isNullOrEmpty()) {
                val resolver = getApplication<Application>().contentResolver
                val current = try {
                    android.provider.Settings.Secure.getString(resolver, "sms_default_application")
                } catch (_: Exception) { null }
                if (current == getApplication<Application>().packageName) {
                    // Effect first (return value needs READ_SMS; verify via Settings instead).
                    trySetDefaultSms(pending)
                    // Role-holder changes propagate asynchronously; re-check before deciding.
                    kotlinx.coroutines.delay(2500)
                    val now = try {
                        android.provider.Settings.Secure.getString(resolver, "sms_default_application")
                    } catch (_: Exception) { null }
                    if (now == pending) {
                        prefs.edit { remove(KEY_PENDING_SMS_RESTORE) }
                        // The run's handback card is now stale — the role IS back.
                        com.phoenix.droidvault.utils.AutomationNotifications
                            .notifySmsHandbackConfirmed(getApplication(), pending)
                    } else {
                        Log.w(
                            "MainViewModel",
                            "handback heal to $pending failed (setting=$now); marker kept for next launch"
                        )
                    }
                } else {
                    prefs.edit { remove(KEY_PENDING_SMS_RESTORE) }
                    if (current == pending) {
                        // Role already returned (a post-release death beat the
                        // runner to its marker-clear): close the cycle with the
                        // confirmation card instead of leaving stale cards.
                        com.phoenix.droidvault.utils.AutomationNotifications
                            .notifySmsHandbackConfirmed(getApplication(), pending)
                    } else {
                        // Role belongs to a third app (maybe not the recorded
                        // target): the pending card is stale truth — drop it
                        // rather than claim anything.
                        com.phoenix.droidvault.utils.AutomationNotifications
                            .cancelSmsCards(getApplication())
                    }
                }
            }
        }
    }

    fun runDeviceDataOperation(
        context: Context,
        isBackup: Boolean,
        doSms: Boolean,
        doCall: Boolean,
        doContacts: Boolean,
        doWifi: Boolean,
        doWallpaper: Boolean,
        doBluetooth: Boolean,
        doFolders: Boolean = false,
        folderPaths: List<String> = emptyList()
    ) {
        val title = if (isBackup) "Backing up Device Data" else "Restoring Device Data"

        // A device restore reads an archive back into the provider, which holds
        // the only copy of SMS, calls and contacts on the device. If the check
        // already found that archive unreadable or altered, refuse NOW, naming the
        // item — restoring it would write unreadable data and report success for
        // work that did not happen. Only a conclusive finding blocks.
        //
        // This is the plan the run is about to execute, read from the same
        // arguments the run is about to use. A selection cached anywhere else
        // could disagree with what actually runs, and the gate would then be
        // guarding a different restore than the one that starts.
        if (!isBackup) {
            val wanted = buildSet {
                if (doSms) add(com.phoenix.droidvault.models.DeviceDataItem.MESSAGES)
                if (doCall) add(com.phoenix.droidvault.models.DeviceDataItem.CALLS)
                if (doContacts) add(com.phoenix.droidvault.models.DeviceDataItem.CONTACTS)
                if (doWifi) add(com.phoenix.droidvault.models.DeviceDataItem.WIFI)
                if (doWallpaper) add(com.phoenix.droidvault.models.DeviceDataItem.WALLPAPER)
                if (doBluetooth) add(com.phoenix.droidvault.models.DeviceDataItem.BLUETOOTH)
                if (doFolders) add(com.phoenix.droidvault.models.DeviceDataItem.FOLDERS)
            }
            val blocked = restoreBlockedByDevice(_verification.value, wanted)
            if (blocked != null) {
                // Written down as well as shown. The refusal is the whole point of
                // the gate and a toast is gone in two seconds, so without this the
                // diagnostics log holds no evidence that a restore was ever
                // blocked — which is exactly the question a field report asks
                // ("I restored and nothing came back"). The reason names the
                // archive, never its contents.
                DroidVaultLogger.w("Verify", "Device restore refused: $blocked")
                _uiState.value = _uiState.value.copy(
                    isRunning = false,
                    currentAction = "Cannot restore",
                    currentStep = blocked,
                    progress = 0
                )
                context.safeToast(blocked)
                return
            }
        }

        _uiState.value = _uiState.value.copy(
            isRunning = true,
            currentAction = title,
            currentStep = "Processing via ContentResolver...",
            progress = -1
        )
        updateProgressNotification(title, "Starting Process...", -1)

        // Seed order matches DeviceManager's real processing order (Messages
        // LAST — the role takeover item): the live screen's initial "up next"
        // then mirrors what will actually run first.
        val selectedItems = mutableListOf<String>()
        if (doCall) selectedItems.add("Calls")
        if (doContacts) selectedItems.add("Contacts")
        if (doWifi) selectedItems.add("Wi-Fi")
        if (doWallpaper) selectedItems.add("Wallpaper")
        if (doBluetooth) selectedItems.add("Bluetooth")
        if (doFolders) selectedItems.add("Folders")
        if (doSms) selectedItems.add("Messages")
        val itemsProcessed = if (selectedItems.isNotEmpty()) selectedItems.joinToString(", ") else "No data selected"

        // Live Processing page + per-item rings, exactly like automation runs:
        // one pseudo app per item (Folders shows the SPECIFIC folder via the
        // subLabel hook). Refused only when a foreign run owns the tracker —
        // then this run stays notification-only. Same cancel flag file as
        // automation: the Processing screen's Cancel button and the
        // notification's Cancel action both work for manual runs.
        AutomationRunner.clearCancel()
        val trackerOwner = "manual-dev-${System.nanoTime()}"
        val tracked = ProgressTracker.startDevicePhase(
            ownerId = trackerOwner,
            op = if (isBackup) com.phoenix.droidvault.models.LiveOp.BACKUP else com.phoenix.droidvault.models.LiveOp.RESTORE,
            itemLabels = selectedItems,
            title = title,
            interactive = true,
            attach = false
        )

        viewModelScope.launch(Dispatchers.IO) {
            val pkg = context.packageName
            try {
                // Free-space pre-flight for MANUAL device-data backups (audit
                // A-21): Folders can be huge; refuse up-front with a clear
                // message instead of per-file ENOSPC mid-archive. -1 = df
                // unavailable → never block on a failed probe.
                if (isBackup) {
                    val freeKb =
                        com.phoenix.droidvault.utils.AutomationSafety.freeSpaceKb(_savedPath.value)
                    if (freeKb in 0 until com.phoenix.droidvault.utils.AutomationSafety.MIN_FREE_KB) {
                        throw IllegalStateException(
                            "Not enough free space at ${_savedPath.value} " +
                                "(${freeKb / 1024} MB free, " +
                                "${com.phoenix.droidvault.utils.AutomationSafety.MIN_FREE_KB / 1024} MB required)"
                        )
                    }
                }


                var cancelled = false
                var lastTrackedItem: String? = null
                // Per-item failures reported BY the pipeline (Messages refused
                // to read its store, a backup produced no archive, …). The
                // automation runner consumes this same signal for its history
                // records — one pipeline, one truth, both tabs.
                val itemFailures = LinkedHashMap<String, String>()
                try {
                DeviceManager.runOperation(
                    context,
                    isBackup,
                    doSms,
                    doCall,
                    doContacts,
                    doWifi,
                    doWallpaper,
                    doBluetooth,
                    _savedPath.value,
                    doFolders,
                    folderPaths,
                    onItemFailure = { label, reason -> itemFailures[label] = reason },
                    onItem = if (tracked) { label, subLabel, prog, step ->
                        // Transition-based completion: DeviceManager has no
                        // per-item done callback, so when a DIFFERENT item
                        // starts, the previous one is finished — mark it done
                        // then, not in an end-of-run burst (verbose logging
                        // showed every ring sitting RUNNING until the tail).
                        if (lastTrackedItem != null && lastTrackedItem != label) {
                            ProgressTracker.deviceItemDone(lastTrackedItem!!)
                        }
                        lastTrackedItem = label
                        ProgressTracker.deviceItemStep(label, prog, step, subLabel)
                    } else null,
                    shouldCancel = { com.phoenix.droidvault.utils.AutomationRunner.isCancelRequested() }
                ) { step, prog ->
                    _uiState.value = _uiState.value.copy(currentStep = step, progress = prog)
                    updateProgressNotification(title, step, prog)
                }
                } catch (e: DeviceManager.DeviceDataCancelled) {
                    cancelled = true
                }
                withContext(Dispatchers.Main) {
                    if (cancelled) {
                        val msg = "Operation cancelled — finished items are kept"
                        if (tracked) {
                            ProgressTracker.complete("Operation Cancelled", msg)
                            ProgressTracker.endRun(trackerOwner)
                        }
                        cancelNotification()
                        _uiState.value = _uiState.value.copy(
                            isRunning = false,
                            currentAction = "Cancelled",
                            currentStep = msg,
                            progress = 100
                        )
                        return@withContext
                    }
                    val finalMsg = if (itemFailures.isEmpty()) {
                        if (isBackup) "Backup Complete!" else "Restore Complete!"
                    } else {
                        // Never "Complete" for a run that did not finish an
                        // item — the automation tab records the same outcome as
                        // a FAILED history record.
                        if (isBackup) "Backup finished — ${itemFailures.size} item failed"
                        else "Restore finished — ${itemFailures.size} item failed"
                    }
                    val detailMsg = if (itemFailures.isEmpty()) itemsProcessed else {
                        itemFailures.entries.joinToString("\n") { "${it.key}: ${it.value}" }
                    }

                    refreshDeviceBackups()
                    showCompletionNotification(finalMsg, detailMsg)
                    if (tracked) {
                        // Mark the final in-flight item done; earlier items were
                        // completed transitionally in onItem. Failures are
                        // applied last so a failed final item stays failed.
                        lastTrackedItem?.let { ProgressTracker.deviceItemDone(it) }
                        itemFailures.forEach { (label, reason) ->
                            ProgressTracker.deviceItemFailed(label, reason)
                        }
                        ProgressTracker.complete(
                            if (itemFailures.isEmpty()) "Completed" else "Completed with errors",
                            if (itemFailures.isEmpty()) {
                                "Device data: ${selectedItems.size} item${if (selectedItems.size == 1) "" else "s"} ok"
                            } else {
                                "Device data: ${selectedItems.size - itemFailures.size} ok, " +
                                    "${itemFailures.size} failed"
                            }
                        )
                        ProgressTracker.endRun(trackerOwner)
                    }
                    _uiState.value = _uiState.value.copy(
                        isRunning = false,
                        currentAction = finalMsg,
                        currentStep = detailMsg,
                        progress = 100,

                    )
                }
            } catch (e: DeviceManager.DeviceDataRefused) {
                // Refused before any work happened (busy gate / engine could
                // not be repaired). Never "Restore Complete!" — the wording
                // mirrors the app phase's refusal (MigratorManager.onComplete)
                // so both Migrate run types read the same.
                withContext(Dispatchers.Main) {
                    if (tracked) {
                        ProgressTracker.fail(e.title, e.message ?: "operation refused")
                        ProgressTracker.endRun(trackerOwner)
                    }
                    cancelNotification()
                    _uiState.value = _uiState.value.copy(
                        isRunning = false,
                        currentAction = e.title,
                        currentStep = e.message ?: "",
                        progress = 100
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (tracked) {
                        ProgressTracker.fail("Device Data Failed", e.message ?: "device data failed")
                        ProgressTracker.endRun(trackerOwner)
                    }
                    cancelNotification()
                    _uiState.value = _uiState.value.copy(
                        isRunning = false,
                        currentAction = "Error occurred",
                        currentStep = e.message ?: ""
                    )
                }
            }
        }
    }


    fun deleteDeviceBackups(
        context: Context,
        doSms: Boolean,
        doCall: Boolean,
        doContacts: Boolean,
        doWifi: Boolean,
        doWallpaper: Boolean,
        doBluetooth: Boolean,
        doFolders: Boolean = false
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val path = _savedPath.value
            ShellQuote.requireAbsPath(path)
            // These archives are the SAME files automation reads and writes.
            // Deleting one mid-run (a scheduled restore is streaming it) is a
            // shared-state hazard the run gate exists for — refuse instead.
            // ACQUIRE, not merely read. This used to check `isBusy` and then run
            // anyway, so a scheduled restore could start streaming Messages.shift
            // between the check and the `mv` — and, worse, two deletes could be
            // inside moveToTrash at once, each reading the trash index before the
            // other wrote it. The second writer then saved a manifest missing the
            // first batch, leaving those archives in .trash/<id>/ with no record:
            // invisible, unrecoverable, never purged, and reported to the user as
            // "recoverable for 7 days".
            if (!com.phoenix.droidvault.utils.RunCoordinator.tryAcquire("delete:device")) {
                val holder = com.phoenix.droidvault.utils.RunCoordinator.currentOwner ?: "another operation"
                withContext(Dispatchers.Main) {
                    context.safeToast(
                        "Another operation is in progress ($holder) — try again when it finishes",
                        Toast.LENGTH_LONG
                    )
                }
                return@launch
            }
            // The gate is held from here until the `finally` below, so the
            // read-modify-write of the trash index inside moveToTrash cannot race
            // a second delete or a run that is streaming one of these archives.
            val allowed = setOf(
                "Messages.shift", "CallLogs.shift", "Contacts.shift",
                "Wi-Fi.shift", "Wallpaper.shift", "Bluetooth.shift",
                BackupConfigFolders.ARCHIVE_FILE
            )
            // MOVED, not erased — the same rule as app archives (invariant 11).
            // A device archive is often the ONLY copy: an SMS backed up months
            // ago and since deleted on the phone cannot be re-derived from the
            // phone, so `rm -f` here was irreversible in exactly the same way
            // `rm -rf` on an app archive was.
            val targets = mutableListOf<String>()
            fun want(file: String, selected: Boolean) {
                require(file in allowed) { "Invalid target" }
                if (selected) targets += "$path/Device/$file"
            }
            want("Messages.shift", doSms)
            want("CallLogs.shift", doCall)
            want("Contacts.shift", doContacts)
            want("Wi-Fi.shift", doWifi)
            want("Wallpaper.shift", doWallpaper)
            want("Bluetooth.shift", doBluetooth)
            want(BackupConfigFolders.ARCHIVE_FILE, doFolders)

            // Declared OUTSIDE the try so the summary below can report on what
            // actually moved, whether or not the body threw.
            var batch: com.phoenix.droidvault.models.Trash.Batch? = null
            var failures = 0
            try {
                if (targets.isNotEmpty()) {
                    batch = runCatching {
                        MigratorManager.moveToTrash(context, path, targets, "device backups")
                    }.getOrNull()
                    // A target that still exists after the move did NOT move.
                    // Reporting success anyway is the exact class of bug this app
                    // shipped before (audit 2026-10-07).
                    failures = targets.count {
                        Shell.cmd("[ -e ${ShellQuote.q(it)} ]").exec().isSuccess
                    }
                    batch?.let { refreshTrash() }
                }
            } finally {
                com.phoenix.droidvault.utils.RunCoordinator.release()
            }

            refreshDeviceBackups()
            withContext(Dispatchers.Main) {
                val moved = batch?.count ?: 0
                context.safeToast(
                    when {
                        failures > 0 && moved == 0 ->
                            "Could not delete anything — check root access and the vault path"
                        failures > 0 ->
                            "$moved moved to trash; $failures could not be deleted"
                        moved == 0 -> "Nothing to delete"
                        else ->
                            // "1 item(s) moved to trash" — and a per-item delete
                            // from the Manage card makes one item the ORDINARY
                            // case rather than the exception.
                            AppParts.count(moved, "item") +
                                " moved to trash — recoverable for 7 days"
                    },
                    Toast.LENGTH_SHORT
                )
            }
        }
    }
    suspend fun isMagisk(): Boolean = withContext(Dispatchers.IO) {
        Shell.cmd("[ -d /data/adb/magisk ] && echo YES").exec().out.joinToString("")
            .trim() == "YES"
    }

    suspend fun canSystemize(): Boolean = withContext(Dispatchers.IO) {
        if (isMagisk()) return@withContext true
        val check =
            Shell.cmd("[ -d /data/adb/modules/mountify ] || [ -d /data/adb/modules/meta-overlayfs ] || [ -d /data/adb/metamodule ] && echo YES")
                .exec().out.joinToString("").trim()
        return@withContext check == "YES"
    }

    fun toggleSystemApps() {
        _uiState.update { it.copy(showSystemApps = !it.showSystemApps) }
    }

    fun togglePartialSystemApps() {
        _uiState.update { it.copy(showPartialSystemApps = !it.showPartialSystemApps) }
    }

    fun setActionFilter(newState: Int) {
        val mode = _uiState.value.migratorMode
        val currentState = _uiState.value.actionFilterState
        if (currentState == newState) return

        when (mode) {
            MigratorMode.DEBLOAT -> {
                // Switching to Restore has to REPLACE the list, not extend it.
                // This fetched "Uninstalled" — the package manager's apps with
                // FLAG_INSTALLED cleared — and appended the result to the installed
                // list still on screen. That source cannot list an app debloat
                // removed outright, so the append added nothing, the stale
                // installed list stayed, and the screen's `!isInstalled` filter hid
                // every row of it: an empty Restore list with a full debloat list
                // one tap behind it. The vault, not the package manager, decides
                // what can be restored.
                if (newState == 2) {
                    _uiState.value = _uiState.value.copy(isFetchingApps = true)
                    viewModelScope.launch {
                        MigratorManager.clearCache()
                        val backed = MigratorManager.fetchAppsList(
                            getApplication(),
                            _savedPath.value,
                            "AllBackups",
                            false,
                            _appList.value,
                            isManage = false,
                            includeOverhead = false
                        )
                        _appList.value = backed
                        _uiState.value = _uiState.value.copy(
                            actionFilterState = newState,
                            isFetchingApps = false
                        )
                    }
                } else {
                    _uiState.value = _uiState.value.copy(
                        actionFilterState = newState,
                        isFetchingApps = true
                    )
                    viewModelScope.launch {
                        MigratorManager.clearCache()
                        val installed = MigratorManager.fetchAppsList(
                            getApplication(),
                            _savedPath.value,
                            "AllInstalled",
                            false,
                            _appList.value,
                            isManage = false,
                            includeOverhead = false
                        )
                        _appList.value = installed
                        _uiState.value = _uiState.value.copy(isFetchingApps = false)
                    }
                }
            }

            MigratorMode.SYSTEMIZE -> {
                if (!_uiState.value.systemAppsFetched && newState == 2) {
                    _uiState.value = _uiState.value.copy(isFetchingApps = true)
                    viewModelScope.launch {
                        val sys = MigratorManager.fetchAppsList(
                            getApplication(),
                            _savedPath.value,
                            "System",
                            true,
                            _appList.value,
                            isManage = false,
                            includeOverhead = false,
                            onlyAppSize = true
                        )
                        _appList.value = sys
                        _uiState.value = _uiState.value.copy(
                            systemAppsFetched = true,
                            actionFilterState = newState,
                            isFetchingApps = false
                        )
                    }
                } else {
                    _uiState.value = _uiState.value.copy(actionFilterState = newState)
                }
            }

            else -> {
                _uiState.value = _uiState.value.copy(actionFilterState = newState)
            }
        }
    }

    fun setMigratorMode(mode: MigratorMode) {
        val showSysApps =
            (mode == MigratorMode.DEBLOAT || mode == MigratorMode.RESTORE_APPS || mode == MigratorMode.MANAGE || mode == MigratorMode.SYSTEMIZE)

        _appList.value = emptyList()
        _uiState.value = _uiState.value.copy(
            migratorMode = mode,
            progress = 0,
            searchQuery = "",
            currentStep = "",
            showUserApps = true,
            showSystemApps = if (mode == MigratorMode.DEBLOAT) false else showSysApps,
            showPartialSystemApps = (mode == MigratorMode.DEBLOAT),
            systemAppsFetched = false,
            actionFilterState = if (mode == MigratorMode.BACKUP_APPS || mode == MigratorMode.RESTORE_APPS || mode == MigratorMode.MANAGE) 0 else 1,
            globalComponents = setOf(1, 2, 3, 4, 5),
            keepDebloatData = true,
            pendingDebloatConfirm = false
        )

        when (mode) {
            MigratorMode.BACKUP_APPS -> fetchAppsList("AllInstalled")
            MigratorMode.RESTORE_APPS -> {
                MigratorManager.clearCache(); fetchAppsList("AllBackups")
            }

            MigratorMode.DEBLOAT -> fetchAppsList("AllInstalled")
            MigratorMode.MANAGE -> {
                MigratorManager.clearCache(); fetchAppsList("AllBackups")
                refreshFolderArchive()
                // Arriving is the request to look. `refreshCurrentList()` also
                // reads this, but pull-to-refresh and post-operation refreshes
                // are the SECOND time — without this the Exports card was empty
                // on the first visit and only filled in after the user had been
                // to the Import page, which is a behaviour that depends on
                // navigation and so reads as randomness (invariant 21).
                refreshTransfers()
                // And the device archives, for the same reason and with the same
                // consequence: the Device data card was a permanent "0 of 7
                // backed up" on the one screen whose job is answering what is in
                // the vault. The Home card is the only other reader, and it is a
                // different tab.
                refreshDeviceBackups()
            }

            MigratorMode.SYSTEMIZE -> fetchAppsList("AllInstalled")
            else -> {}
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    /**
     * Change one part of the DEFAULT that newly selected apps start with.
     *
     * This is the chip row, not a per-app control: it changes what future
     * selections get and leaves every app already selected alone. Overwriting
     * those is a separate, explicit action
     * ([applyComponentsToAllSelected]) — the previous behaviour did it
     * implicitly here, which silently discarded per-app choices the user had
     * made on the detail page.
     */
    fun toggleGlobalComponent(id: Int) {
        val current = _uiState.value.globalComponents.toMutableSet()
        if (current.contains(id)) current.remove(id) else current.add(id)
        _uiState.update { it.copy(globalComponents = current) }
    }

    fun toggleAppSelection(packageName: String) {
        val currentList = _appList.value.toMutableList()
        val index = currentList.indexOfFirst { it.packageName == packageName }
        if (index != -1) {
            val app = currentList[index]
            // Materialise the default on select instead of leaving the set null.
            // Null means "fall back to globalComponents" in every consumer, so a
            // per-app edit made after selecting was invisible in the size
            // estimate and could be lost by any code path that re-read the
            // global set.
            currentList[index] = if (app.isSelected) {
                app.copy(isSelected = false, activeComponents = null)
            } else {
                app.copy(isSelected = true, activeComponents = defaultSelectionFor(app))
            }
            // defaultSelectionFor returns null outside the two component modes,
            // which leaves activeComponents null there — exactly as before.
            _appList.value = currentList
            _detailApps.value = currentList
            updateStorageInfo()
        }
    }

    fun smartSelect(visibleApps: List<AppInfo>) {
        val state = _uiState.value
        val visiblePackages = visibleApps.map { it.packageName }.toSet()

        fun isSmartMatch(app: AppInfo): Boolean {
            return when (state.migratorMode) {
                MigratorMode.BACKUP_APPS -> app.availableInBackup.isNotEmpty()
                MigratorMode.RESTORE_APPS -> !app.isInstalled
                MigratorMode.MANAGE -> app.isInstalled
                MigratorMode.DEBLOAT -> state.actionFilterState == 1 && app.availableInBackup.contains(
                    1
                )
                else -> false
            }
        }

        val matches = visibleApps.filter { isSmartMatch(it) }
        val allMatchesSelected = matches.isNotEmpty() && matches.all { it.isSelected }

        val updatedList = _appList.value.map { app ->
            if (visiblePackages.contains(app.packageName) && isSmartMatch(app)) {
                val shouldSelect = !allMatchesSelected
                // Restores seed only what the archive holds, which is what
                // Auto Select means here; the old `availableInBackup intersect
                // globalComponents` could leave an app selected with a set the
                // default had already dropped, so the run and the screen
                // disagreed.
                val comps = if (shouldSelect) defaultSelectionFor(app) else null
                app.copy(isSelected = shouldSelect, activeComponents = comps)
            } else app
        }
        _appList.value = updatedList
        _detailApps.value = updatedList
        updateStorageInfo()
    }

    fun selectAllVisibleApps(select: Boolean, visibleApps: List<AppInfo>) {
        val visiblePackageNames = visibleApps.map { it.packageName }.toSet()
        val updatedList = _appList.value.map { app ->
            // Leave rows whose selection state would not change completely
            // alone. Previously every visible row got `activeComponents = null`
            // unconditionally, so tapping select-all while a subset was already
            // selected wiped the per-app component choice that Auto Select had
            // just made — the chips and the "already backed up" banner vanished
            // even though nothing about those apps had changed.
            if (!visiblePackageNames.contains(app.packageName)) {
                app
            } else if (app.isSelected == select) {
                app
            } else {
                app.copy(
                    isSelected = select,
                    activeComponents = if (select) defaultSelectionFor(app) else null
                )
            }
        }
        _appList.value = updatedList
        _detailApps.value = updatedList
        updateStorageInfo()
    }

    /**
     * Select only apps whose backup would actually change.
     *
     * The companion to [smartSelect], which selects everything backed up. A
     * nightly re-run wants this one: 63 apps ticked, 2 changed, a fraction of a
     * gigabyte written.
     */
    fun selectChangedApps(visibleApps: List<AppInfo>) {
        val state = _uiState.value
        val visible = visibleApps.map { it.packageName }.toSet()
        val list = _appList.value.map { app ->
            if (app.packageName !in visible) app
            else if (ChangeDetection.isUpToDate(app, state.globalComponents)) app.copy(isSelected = false)
            else app.copy(isSelected = true, activeComponents = defaultSelectionFor(app))
        }
        _appList.value = list
        _detailApps.value = list
        updateStorageInfo()
    }

    fun updateStorageInfo() {
        val state = _uiState.value
        val allApps = _appList.value
        // The runnable set, not every ticked row: the "Need:" figure and the
        // count must describe what the run will do, or the free-space guard
        // passes on a selection that then fails for space.
        val selectedApps = runnableSelectedApps(state, allApps)

        var totalBackupsKb = 0L

        val selectedKb = if (state.migratorMode == MigratorMode.MANAGE ||
            state.migratorMode == MigratorMode.DEBLOAT ||
            state.migratorMode == MigratorMode.SYSTEMIZE
        ) {
            if (state.migratorMode == MigratorMode.MANAGE) {
                totalBackupsKb = allApps.sumOf { it.appSizeKb + it.dataSizeKb + it.mediaSizeKb }
            }
            if (state.migratorMode == MigratorMode.DEBLOAT && state.keepDebloatData && state.actionFilterState == 1) {
                selectedApps.sumOf { it.appSizeKb }
            } else {
                selectedApps.sumOf { it.appSizeKb + it.dataSizeKb + it.mediaSizeKb }
            }
        } else {
            // Delegated to AppParts so the "Need:" figure, the estimate on
            // the component page and the engine's own accounting cannot drift
            // apart — they were three copies of these five lines.
            // Count only the parts a run will ACTUALLY re-write. The engine
            // skips a part whose recorded size still matches the live one, so
            // adding every selected app's full size made a nightly re-backup of
            // unchanged apps claim "Need: 38 GB" and then write almost nothing.
            selectedApps.sumOf { app ->
                ChangeDetection.writeEstimateKb(
                    app.activeComponents ?: state.globalComponents,
                    app
                )
            }
        }

        val targetPath =
            if (state.migratorMode == MigratorMode.RESTORE_APPS ||
                state.migratorMode == MigratorMode.DEBLOAT ||
                state.migratorMode == MigratorMode.SYSTEMIZE
            ) "/data" else _savedPath.value

        val freeKb = MigratorManager.getAvailableSpaceKb(targetPath)
        val totalKb = MigratorManager.getTotalSpaceKb(targetPath)
        val usedKb = if (totalKb > freeKb) totalKb - freeKb else 0L

        _uiState.value = _uiState.value.copy(
            totalRequiredKb = selectedKb,
            totalBackupsSizeKb = totalBackupsKb,
            storageUsedKb = usedKb,
            storageFreeKb = freeKb,
            selectedRunCount = selectedApps.size
        )
    }

    fun clearLogs() {
        _uiState.value = _uiState.value.copy(
            currentStep = "",
            progress = 0
        )
    }

    private fun fetchAppsList(type: String, append: Boolean = false) {
        _uiState.value =
            _uiState.value.copy(isFetchingApps = true, currentAction = "Fetching apps list...")

        viewModelScope.launch(Dispatchers.IO) {
            val apps = MigratorManager.fetchAppsList(
                getApplication(),
                _savedPath.value,
                type,
                append,
                _appList.value,
                isManage = _uiState.value.migratorMode == MigratorMode.MANAGE,
                includeOverhead = _uiState.value.migratorMode == MigratorMode.BACKUP_APPS || _uiState.value.migratorMode == MigratorMode.RESTORE_APPS,
                onlyAppSize = _uiState.value.migratorMode == MigratorMode.SYSTEMIZE
            )
            withContext(Dispatchers.Main) {
                // A relist can drop the app whose component page is open (its
                // package vanished, or the mode changed). Close the page rather
                // than leave it editing a selection that no longer exists — its
                // next toggle would silently hit a missing index.
                val editing = _componentEditorPackage.value
                if (editing != null && apps.none { it.packageName == editing }) {
                    closeComponentEditor()
                }
                _appList.value = apps
                _detailApps.value = apps
                _uiState.value = _uiState.value.copy(
                    isFetchingApps = false,
                )
                updateStorageInfo()
                if (type == "System" || type == "RestoreSystem" || type == "AllBackups") {
                    _uiState.value = _uiState.value.copy(systemAppsFetched = true)
                }
            }
        }
    }

    /**
 * Seed the installed app list for the Auto tab, which needs it even when the
 * user never opened Migrate.
 *
 * Refuses outright while a vault-sourced mode owns the list. Without that guard
 * the Auto tab's launch effect replaced an empty — and correct — Restore list
 * with every installed app, so Restore offered to restore backups that do not
 * exist, and its per-app component page offered parts nothing had archived.
 */
    fun ensureInstalledApps() {
        if (_uiState.value.migratorMode.isVaultSourced) return
        if (_appList.value.isEmpty() && !_uiState.value.isFetchingApps) {
            fetchAppsList("AllInstalled")
        }
    }

    /**
     * Selected apps a run would actually process.
     *
     * Exposed rather than re-derived at each call site: the button's enabled
     * state and its "N Selected" label both used the raw isSelected count while
     * the run applied this filter, so a ticked system app with the system-app
     * filter off produced a live button that did nothing.
     *
     * The system-app test is deliberately the SAME one the screen uses to decide
     * whether to draw the row at all — `showSystemApps`, or
     * `showPartialSystemApps` for a launchable one. It used to be
     * `showSystemApps` alone, which made the two disagree: turn on "Partial
     * system apps", tick Chrome, and the row drew ticked and highlighted while
     * the count stayed at 0 and the button stayed dead. Reported on device.
     *
     * **A runnable set that is stricter than the set the user can see and tick
     * is a lie told by a checkbox.** A filter is for finding things; it must not
     * silently un-select something already selected. Whatever the screen shows,
     * ticking means it is in the run.
     */
    private fun runnableSelectedApps(state: AppState, apps: List<AppInfo> = _appList.value) =
        apps.filter { it.isSelected && com.phoenix.droidvault.models.isShownInMigrator(it, state) }

    // ---------- Archive verification ----------

    /**
     * The last verification result, kept until a NEW verification replaces it.
     *
     * Deliberately NOT cleared when the dialog is dismissed. It is the only
     * thing the pre-restore gate has to work from, and clearing it on dismiss
     * meant the flow was: verify, read "1 damaged", tap Close, press Restore —
     * and the restore ran anyway. Verified on device before this split.
     */
    private val _verification = MutableStateFlow<VerificationReport?>(null)
    val verification: StateFlow<VerificationReport?> = _verification.asStateFlow()

    /**
     * The vault's change counter: bumped each time a run releases the gate.
     *
     * Screens that state a verdict about the vault collect this and hand it to
     * [BackupHealth.from], so a report taken before a run cannot be rendered as
     * if it described the vault now. Nothing needs to remember to invalidate the
     * report by hand — every writer already holds the gate, which is the point of
     * it (`RunCoordinator.vaultEpoch`).
     */
    val vaultEpoch: StateFlow<Long> = com.phoenix.droidvault.utils.RunCoordinator.vaultEpoch

    /**
     * What kind of run last released the gate, for the health row's footnote.
     *
     * "A run has been over the vault since" is weaker than "an app backup has run
     * since", and the difference decides whether the user re-checks now or assumes
     * the report is stale in a harmless way. Label only — see `RunCoordinator`.
     */
    val lastVaultWriter: StateFlow<String?> = com.phoenix.droidvault.utils.RunCoordinator.lastWriter

    private val _isVerifying = MutableStateFlow(false)
    val isVerifying: StateFlow<Boolean> = _isVerifying.asStateFlow()

    /** Whether the report dialog is showing. Transient UI, unlike [verification]. */
    private val _verificationDialogVisible = MutableStateFlow(false)
    val verificationDialogVisible: StateFlow<Boolean> = _verificationDialogVisible.asStateFlow()

    /**
     * Bring back the verdict from the previous launch.
     *
     * An `init` block HERE, under the flow it writes, because Kotlin initialises
     * properties in declaration order: the same restore in the startup block at
     * the top of the class would run before `_verification` exists and crash
     * every launch — the trap `notificationManager` documents above.
     *
     * Synchronous on purpose. It is one small prefs read, and it has to finish
     * before anything can write a fresh report: a check started the moment the
     * app opens must not be overwritten by the loading of the verdict it
     * replaced.
     */
    init {
        reloadVerdict()
    }

    /**
     * Re-read the stored verdict for the vault currently in use.
     *
     * Called at startup and from every place the vault folder changes — the two
     * are one rule, not two: an in-memory report is not path-checked, so a moved
     * vault would otherwise go on being vouched for by a check of files it never
     * read. The load itself path-matches (`VaultVerdict.forVault`), so this is
     * deliberately a LOAD and never a clear: the state a folder has never been
     * checked in is null, and clearing would say the same thing while also
     * destroying a record that belongs to the folder it was taken in.
     */
    private fun reloadVerdict() {
        _verification.value = VaultVerdictStore.load(getApplication(), _savedPath.value)
    }

    /**
     * Read every archive in the vault back through the engine.
     *
     * Deliberately cheap to run: it only lists and decompresses the archive
     * headers, so it costs seconds, not the minutes a re-backup would.
     */
    // ---------- Trash ----------

    private val _trash = MutableStateFlow<List<Trash.Batch>>(emptyList())
    val trash: StateFlow<List<Trash.Batch>> = _trash.asStateFlow()

    fun refreshTrash() {
        viewModelScope.launch(Dispatchers.IO) {
            _trash.value = try {
                MigratorManager.loadTrash(getApplication(), _savedPath.value)
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    fun restoreTrashBatch(batchId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            MigratorManager.restoreTrashBatch(getApplication(), _savedPath.value, batchId)
                .onSuccess { restored ->
                    getApplication<Application>().safeToast(
                        // "Recovered 1 backup(s)" — the (s) shorthand is the thing
                        // six other plural rules in this app got wrong, and a
                        // restore of a single export is the ordinary case.
                        if (restored > 0) "Recovered ${AppParts.count(restored, "backup")}"
                        else "Nothing could be recovered",
                        Toast.LENGTH_LONG
                    )
                }
                .onFailure {
                    getApplication<Application>().safeToast("Undo failed", Toast.LENGTH_LONG)
                }
            // The app list reads the vault tree, so it is now stale either way.
            MigratorManager.clearCache()
            refreshTrash()
            // And so is whatever ELSE the batch put back. A batch of exports is
            // restored into `Download/DroidVault`, and the Exports card was
            // showing one export while the disk held two — the §2.58 rule, which
            // is "anything that writes re-reads the list its own screen is
            // showing", applied to a list that is not the app list at all.
            // `refreshCurrentList()` re-reads per the current mode, and its
            // MANAGE branch is where the Exports card lives.
            refreshCurrentList()
        }
    }

    // ---------- cross-device transfer ----------

    /** Write the vault to shared Downloads as one transfer file. */
    /**
     * Copy the vault to a single file in Downloads.
     *
     * This used to be fire-and-forget: nothing set `isRunning`, nothing took the
     * run gate, and nothing was written to the progress bus. `tar` over a
     * multi-gigabyte vault takes minutes, so tapping Export looked like it did
     * nothing at all — and on a phone without the free space it silently filled
     * the disk before failing. It now sets the same `isRunning` the app's bottom
     * bar and the run overlay read, so the user sees "Exporting…" with a live
     * spinner and the app cannot start a backup on top of it.
     */
    /**
     * Split sizes offered on export, in bytes. 0 is one file.
     *
     * Persisted: the choice is about the user's PC and cables, which do not
     * change between exports, so asking every time would be nagging.
     */
    val splitOptions = listOf(
        "One file" to 0L,
        "2 GB parts" to 2L * 1024 * 1024 * 1024,
        "4 GB parts" to 4L * 1024 * 1024 * 1024,
        "8 GB parts" to 8L * 1024 * 1024 * 1024
    )

    /** Last split choice, so the dialog opens where the user left it. */
    var splitChoice: Int
        get() = getApplication<Application>()
            .getSharedPreferences("droidvault_transfer_prefs", Context.MODE_PRIVATE)
            .getInt("split_choice", 0)
            .coerceIn(splitOptions.indices)
        set(value) {
            getApplication<Application>()
                .getSharedPreferences("droidvault_transfer_prefs", Context.MODE_PRIVATE)
                .edit().putInt("split_choice", value.coerceIn(splitOptions.indices)).apply()
        }

    /**
     * Start an export, as foreground work.
     *
     * The work runs in [com.phoenix.droidvault.utils.TransferService], not here.
     * It used to run in `viewModelScope`, and that scope is cancelled when the
     * Activity finishes — so pressing Back, or the system reclaiming the app,
     * killed a thirty-gigabyte pack halfway and abandoned it on disk. The
     * service has a foreground notification, which is what makes the process
     * un-evictable; this only watches it and reports.
     */
    fun exportVaultForTransfer(context: Context, splitBytes: Long = 0) {
        if (_uiState.value.isRunning || TransferBus.isRunning) {
            context.safeToast("Wait for the current operation to finish.", Toast.LENGTH_SHORT)
            return
        }
        _exportCancel.set(false)
        _isExporting.value = true
        _uiState.update {
            it.copy(
                isRunning = true,
                currentAction = "Exporting...",
                currentStep = "Packing your backups into one file.",
                progress = -1
            )
        }
        TransferService.startExport(context, _savedPath.value, splitBytes)
    }

    /**
     * Watch [TransferBus] and reflect a service-owned transfer in the UI.
     *
     * Called from `init`, so the sheet is correct whether the transfer was
     * started here or while this Activity did not exist yet.
     */
    private fun observeTransferService() {
        viewModelScope.launch {
            TransferBus.status.collect { status ->
                if (!status.running) return@collect
                _isExporting.value = status.op == TransferOp.EXPORT
                _isImporting.value = status.op == TransferOp.IMPORT
                // The Import page's own copy. Set here rather than collected
                // separately so the page can never show a different transfer
                // than the panel does.
                _transferStatus.value = status
                if (_transferStartedMs.value == 0L) {
                    _transferStartedMs.value = System.currentTimeMillis()
                }
                // One sample per report, pruned to a few windows. The figure is the same
                // `percent` the panel shows — monotonic, and measured against a
                // real total — so the estimate can never be derived from a number
                // the user is not also being shown.
                val now = System.currentTimeMillis()
                val kept = (_transferSamples.value + TransferProgress.Estimate.Sample(now, status.percent))
                    .filter { now - it.atMs <= TransferProgress.Estimate.RATE_WINDOW_MS * 3 }
                _transferSamples.value = kept
                _uiState.update {
                    it.copy(
                        isRunning = true,
                        currentAction = if (status.op == TransferOp.IMPORT) {
                            // The phase when the run has reported one, so the
                            // bottom panel says which stage it is in rather than
                            // the same word for twenty minutes.
                            status.phaseLabel.ifBlank { "Importing..." }
                        } else {
                            status.phaseLabel.ifBlank { "Exporting..." }
                        },
                        currentStep = status.detail,
                        progress = status.percent
                    )
                }
            }
        }
        viewModelScope.launch {
            TransferBus.done.collect { result ->
                _exportCancel.set(false)
                _isExporting.value = false
                _isImporting.value = false
                // The page's own progress strip has to be torn down too, or a
                // finished import leaves a 100% bar pinned over the list forever.
                // Same reason the panel's terminal state is explicit.
                _transferStatus.value = TransferStatus()
                _transferStartedMs.value = 0L
                _transferSamples.value = emptyList()
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        currentAction = when {
                            (result as? VaultTransferManager.Result.Failed)
                                ?.reason == VaultTransferManager.CANCELLED -> "Transfer cancelled"
                            result is VaultTransferManager.Result.Failed -> "Transfer failed"
                            else -> "Done"
                        },
                        currentStep = when (result) {
                            is VaultTransferManager.Result.Failed -> result.reason
                            else -> ""
                        },
                        progress = 100
                    )
                }
                when (result) {
                    // An import owes the UI more than a toast: the summary, the
                    // refreshes and the configuration prompt.
                    is VaultTransferManager.Result.Imported -> {
                        // The run's own verdict, written down. §2.57 measured an
                        // import that extracted all 482,592,475 bytes and then
                        // reported "Import cancelled" — the engine logged
                        // CANCELLED, the UI said cancelled, and neither line said
                        // how much had actually landed, so the two could be
                        // compared but never reconciled. This is the app's half of
                        // that pair, and the `failed` list is the part that turns
                        // "it worked" into something a report can argue with.
                        val o = result.outcome
                        DroidVaultLogger.i(
                            "Transfer",
                            "import finished from ${result.path}: " +
                                "${o.addedCount} added, ${o.replaced.size} replaced, " +
                                "${o.keptExisting.size} kept, ${o.failed.size} failed" +
                                if (o.failed.isNotEmpty()) " (${o.failed.joinToString(", ")})"
                                else ""
                        )
                        finishImport(getApplication<Application>(), result)
                    }

                    is VaultTransferManager.Result.Exported -> {
                        DroidVaultLogger.i(
                            "Transfer",
                            "export finished to ${result.path}: " +
                                "${AppParts.count(result.appCount, "backup")} in " +
                                "${AppParts.count(result.partCount, "part")}, " +
                                "${result.bytesKb} KB"
                        )
                        getApplication<Application>()
                        .safeToast(
                            run {
                                val apps = AppParts.count(result.appCount, "backup")
                                val parts = AppParts.count(result.partCount, "part")
                                if (result.partCount > 1) {
                                    "Exported $apps in $parts to Downloads/DroidVault/"
                                } else {
                                    "Exported $apps to Downloads/DroidVault/"
                                }
                            },
                            Toast.LENGTH_LONG
                        )
                    }

                    is VaultTransferManager.Result.Failed -> {
                        // A refusal and a crash land in the same branch, and they
                        // are not the same thing. The space gate refusing a 32 GB
                        // import on a phone with 31 GB free is correct behaviour
                        // and must read as a decision somebody can act on, not as
                        // an error — so it is logged at W with its reason, and
                        // only a reason that is not a known refusal is an error.
                        val reason = result.reason
                        val refusal = reason == VaultTransferManager.CANCELLED ||
                            reason.contains("Not enough", ignoreCase = true) ||
                            reason.contains("free", ignoreCase = true)
                        if (refusal) {
                            DroidVaultLogger.w("Transfer", "transfer refused: $reason")
                        } else {
                            DroidVaultLogger.e("Transfer", "transfer failed: $reason")
                        }
                        getApplication<Application>()
                        .safeToast(
                            if (reason == VaultTransferManager.CANCELLED) {
                                "Transfer cancelled"
                            } else {
                                "Transfer failed: $reason"
                            },
                            Toast.LENGTH_LONG
                        )
                    }
                }
                if (result is VaultTransferManager.Result.Exported) {
                    MigratorManager.clearCache()
                }
            }
        }
    }

    /**
     * Import a transfer file into the vault.
     *
     * [replace] is opt-in and false by default: an import must never quietly
     * destroy the destination's newer backups.
     */
    /**
     * Import from a SAF URI: copy it into the cache first, then import it.
     *
     * The copy was happening in the picker callback, on the MAIN thread, before
     * any running state was set — so importing a real 3.7 GB archive froze the UI
     * for minutes with no bar and no toast. That is the same silent failure the
     * export had (T-01), just one step earlier, and it is why import behaved
     * nothing like export.
     *
     * The SAF handle is not a filesystem path this app's root shell can read, and
     * the vault cannot be read through SAF at all under scoped storage, so the
     * staged copy is unavoidable — but it belongs on IO with progress, not on the
     * UI thread.
     */
    /**
     * A picked file, resolved to something the confirmation can describe.
     *
     * [source] is null when the pick cannot become an import at all — the caller
     * says so in [reason]. [localPath] is non-null when the file is already
     * readable as a path, which is the case that avoids a 34 GB copy.
     */
    private data class PickedTransfer(
        val source: ImportSource?,
        val reason: String?,
        val localPath: String?,
        val uri: android.net.Uri,
        val replace: Boolean
    )

    /**
     * A file picked through the system picker, held until it is confirmed.
     *
     * The SAF path used to start the import itself. That is the same
     * multi-minute, tens-of-gigabytes write the listed path now asks about
     * first, so a confirmation on the list and none on the picker is the gate
     * existing only where a list happened to be — and the picker is the way
     * someone imports a transfer this phone has never seen. Held on the
     * ViewModel rather than in a composable so the resolution survives the
     * Activity being recreated while the sheet is up.
     */
    private val _pendingPick = MutableStateFlow<PickedTransfer?>(null)

    /**
     * What the picked file is, for the confirmation sheet — and ONLY that.
     *
     * Deliberately not the holder: the resolved local path and the `replace`
     * flag are the import's business, and a public property cannot leak a
     * private type. The sheet needs a description; the run needs the rest.
     */
    val pendingPickSource: StateFlow<ImportSource?> =
        _pendingPick.map { it?.source }.stateIn(
            viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null
        )

    /**
     * Work out what a picked file IS, without importing it.
     *
     * Cheap and read-only by construction: the provider is asked for a size and
     * a name, and the local path is resolved with the same `resolveLocalTransfer`
     * the import already used. **Nothing is copied and nothing is written** —
     * the 34 GB copy out of a cloud document happens only after the confirmation
     * is accepted, because a sheet that says "this will copy 34 GB first" has to
     * know that before it says it.
     *
     * A failure returns a reason rather than an empty source, so the caller can
     * refuse honestly instead of showing a sheet with nothing in it.
     */
    private suspend fun resolvePickedTransfer(
        context: Context,
        uri: android.net.Uri,
        replace: Boolean
    ): PickedTransfer {
        val local = resolveLocalTransfer(context, uri)
        if (local != null) {
            val name = java.io.File(local).name
            if (VaultTransfer.isPartName(name)) {
                // A piece names its set; the siblings are found beside it. The
                // count comes from the manifest when there is one, which is the
                // only way to say how many SHOULD be here.
                val dir = java.io.File(local).parentFile
                val prefix = VaultTransfer.splitPrefix(name) ?: name
                val manifest = dir
                    ?.listFiles { f -> f.name == "$prefix.parts.json" }
                    ?.firstOrNull()
                val expected = manifest?.let {
                    runCatching {
                        org.json.JSONObject(it.readText()).optInt("parts", 0)
                    }.getOrNull()?.takeIf { n -> n > 0 }
                }
                val here = dir?.listFiles { f ->
                    VaultTransfer.isPartName(f.name) && VaultTransfer.splitPrefix(f.name) == prefix
                }?.size ?: 1
                return PickedTransfer(
                    source = ImportSource(
                        from = dir?.absolutePath ?: local,
                        totalBytes = manifest?.let { runCatching { it.length() }.getOrNull() } ?: 0L,
                        isSplit = true,
                        piecesHere = here,
                        piecesExpected = expected ?: here,
                        piecesMissing = ((expected ?: here) - here).coerceAtLeast(0),
                        stagedFirst = null
                    ),
                    reason = null,
                    localPath = local,
                    uri = uri,
                    replace = replace
                )
            }
            val bytes = shellFileSize(local).coerceAtLeast(0L)
            return PickedTransfer(
                source = ImportSource(
                    from = local,
                    totalBytes = bytes,
                    isSplit = false,
                    piecesHere = 1,
                    piecesExpected = 1,
                    piecesMissing = 0,
                    stagedFirst = null
                ),
                reason = null,
                localPath = local,
                uri = uri,
                replace = replace
            )
        }

        // Not readable as a path — a cloud document, or anything else the
        // resolver could not map. A lone piece can never be imported: its
        // siblings are unreachable through one SAF handle.
        val pickedName = uri.lastPathSegment?.substringAfterLast('/') ?: ""
        if (VaultTransfer.isPartName(pickedName)) {
            return PickedTransfer(
                source = null,
                reason = "That is one piece of a split transfer, and the rest are " +
                    "not on this phone. Copy every .part file and the .parts.json " +
                    "file into Downloads/DroidVault first.",
                localPath = null,
                uri = uri,
                replace = replace
            )
        }

        // Ask the provider how big it is BEFORE copying it. A sheet that cannot
        // state the size is a sheet asking someone to agree to an unknown.
        val bytes = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
        }.getOrNull()?.takeIf { it > 0 } ?: 0L
        return PickedTransfer(
            source = ImportSource(
                from = pickedName.ifBlank { "the file you picked" },
                totalBytes = bytes,
                isSplit = false,
                piecesHere = 1,
                piecesExpected = 1,
                piecesMissing = 0,
                stagedFirst = "Not on this phone yet, so it is copied into app " +
                    "storage first — that needs room for it a second time."
            ),
            reason = null,
            localPath = null,
            uri = uri,
            replace = replace
        )
    }

    /**
     * The SAF entry point: resolve the pick and ASK, rather than importing.
     *
     * The import itself is [runPickedImport], which is only reached once the
     * confirmation is accepted. Everything expensive happens there.
     */
    fun importVaultFromUri(context: Context, uri: android.net.Uri, replace: Boolean = false) {
        if (_uiState.value.isRunning || TransferBus.isRunning) {
            context.safeToast("Wait for the current operation to finish.", Toast.LENGTH_SHORT)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val picked = resolvePickedTransfer(context, uri, replace)
            if (picked.source == null) {
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            isRunning = false,
                            currentAction = "Import failed",
                            currentStep = "",
                            progress = 100
                        )
                    }
                    _isImporting.value = false
                    context.safeToast(
                        picked.reason ?: "That file cannot be imported.",
                        Toast.LENGTH_LONG
                    )
                }
                return@launch
            }
            _pendingPick.value = picked
        }
    }

    /** Dismiss the confirmation for a picked file without importing it. */
    fun dismissPendingPick() {
        _pendingPick.value = null
    }

    /**
     * Run the import the confirmation sheet just described.
     *
     * This is where the work starts, and it is unchanged: the local path is
     * read where it already is, and anything else is streamed out of the
     * provider into `filesDir` first, with progress, before the import begins.
     */
    fun runPickedImport(context: Context) {
        val picked = _pendingPick.value ?: return
        _pendingPick.value = null
        _importCancel.set(false)
        _isImporting.value = true
        if (_uiState.value.isRunning) {
            context.safeToast("Wait for the current operation to finish.", Toast.LENGTH_SHORT)
            return
        }
        _uiState.update {
            it.copy(
                isRunning = true,
                currentAction = "Importing...",
                currentStep = "Opening the file you picked",
                progress = -1
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val local = picked.localPath
            if (local != null) {
                if (VaultTransfer.isPartName(java.io.File(local).name)) {
                    importVaultFromParts(context, local, picked.replace)
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        currentStep = "Reading the file where it already is",
                        progress = -1
                    )
                }
                importVaultFrom(context, local, picked.replace, alreadyStaged = true)
                return@launch
            }
            val dest = runCatching {
                val original = uriName(picked.uri)
                val name = original
                    ?.map { c -> if (c.isLetterOrDigit() || c == '.' || c == '-' || c == '_') c else '_' }
                    ?.joinToString("")
                    ?.takeIf { VaultTransfer.looksLikeTransferFile(it) }
                    ?: "import.${VaultTransfer.EXTENSION}"
                // filesDir, NOT cacheDir. Cache is EVICTABLE: the system deletes
                // app cache under pressure, and it demonstrably deleted a 3.72 GB
                // staged copy between the copy finishing and the import reading
                // it — so the import failed with "Could not read that file" on a
                // file the user had just watched finish copying, with no reason
                // given. `filesDir` is app-private and persistent.
                val dir = java.io.File(context.filesDir, "transfer").apply { mkdirs() }
                val file = java.io.File(dir, name)
                context.contentResolver.openInputStream(picked.uri)?.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(256 * 1024)
                        var copied = 0L
                        val total = runCatching {
                            context.contentResolver.openFileDescriptor(picked.uri, "r")
                                ?.use { it.statSize }
                        }.getOrNull()?.takeIf { it > 0 } ?: 0L
                        var lastShown = 0L
                        while (true) {
                            if (_importCancel.get()) {
                                runCatching { input.close() }
                                file.delete()
                                break
                            }
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            copied += n
                            if (copied - lastShown >= BYTE_STEP) {
                                lastShown = copied
                                _uiState.update {
                                    it.copy(
                                        currentStep = "Copying the file here " +
                                            "(${TransferProgress.sizeLabel(copied, total)})",
                                        progress = -1
                                    )
                                }
                            }
                        }
                    }
                } ?: return@runCatching null
                file
            }.getOrNull()
            if (dest == null) {
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            isRunning = false,
                            currentAction = "Import failed",
                            currentStep = "",
                            progress = 100
                        )
                    }
                    _isImporting.value = false
                    context.safeToast("Could not read that file", Toast.LENGTH_LONG)
                }
                return@launch
            }
            importVaultFrom(
                context, dest.absolutePath, picked.replace,
                alreadyStaged = true, stagedCopy = dest
            )
        }
    }

    /** The file name a SAF uri ends in, or null when it has none. */
    private fun uriName(uri: android.net.Uri): String? =
        uri.lastPathSegment?.substringAfterLast('/')

    private fun importVaultFromParts(context: Context, pickedPath: String, replace: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val fail = { reason: String ->
                viewModelScope.launch(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            isRunning = false,
                            currentAction = "Import failed",
                            currentStep = "",
                            progress = 100
                        )
                    }
                    _isImporting.value = false
                    context.safeToast(reason, Toast.LENGTH_LONG)
                }
            }
            val dir = java.io.File(pickedPath).parent ?: run {
                fail("Could not read that file. Pick it again.")
                return@launch
            }
            val base = VaultTransfer.splitPrefix(java.io.File(pickedPath).name) ?: run {
                fail("That is not a transfer file.")
                return@launch
            }
            val prefix = "$dir/$base"
            val manifest = VaultTransfer.PartsManifest.fromJson(
                VaultTransferManager.readTransferManifest(prefix)
            )
            if (manifest == null) {
                fail(
                    "This transfer was split into parts but its manifest " +
                        "(${base}.parts.json) is missing. Copy every .part file " +
                        "AND the .parts.json file back to this phone first."
                )
                return@launch
            }
            val names = VaultTransfer.expectedPartNames(base, manifest.parts) ?: run {
                fail("That transfer names more parts than can be listed.")
                return@launch
            }
            val missing = VaultTransfer.missingParts(
                base, manifest.parts, VaultTransferManager.listTransferParts(prefix)
            )
            if (missing.isNotEmpty()) {
                fail(
                    "This transfer is missing ${missing.size} part(s) " +
                        "(${missing.take(2).joinToString(", ")}${if (missing.size > 2) "…" else ""}). " +
                        "Copy every .part file back first."
                )
                return@launch
            }
            val problem = manifest.check(
                VaultTransferManager.transferPartSizes(names.map { "$dir/$it" })
            )
            if (problem != null) {
                fail(problem)
                return@launch
            }
            _uiState.update {
                it.copy(
                    currentStep = "All ${manifest.parts} parts verified — reading the transfer",
                    progress = -1
                )
            }
            importVaultFrom(
                context, prefix, replace,
                alreadyStaged = true,
                sourceParts = names.map { "$dir/$it" }
            )
        }
    }

    fun importVaultFrom(
        context: Context,
        archivePath: String,
        replace: Boolean = false,
        alreadyStaged: Boolean = false,
        stagedCopy: java.io.File? = null,
        sourceParts: List<String> = emptyList()
    ) {
        if (!alreadyStaged) {
            if (_uiState.value.isRunning) {
                context.safeToast("Wait for the current operation to finish.", Toast.LENGTH_SHORT)
                return
            }
            _uiState.update {
                it.copy(
                    isRunning = true,
                    currentAction = "Importing...",
                    currentStep = "Reading the transfer file",
                    progress = 0
                )
            }
        }
        // The heavy work runs in TransferService, for the same reason export
        // does: viewModelScope dies with the Activity, and an import is the
        // longest thing this app does. Everything after it — the message, the
        // refreshes, the configuration prompt — happens in finishImport().
        //
        // The START is logged here, before the service is handed the work, and
        // not in the service: the engine's own `ACTION:` lines say what the
        // engine is doing but never what was asked of it, so a log full of
        // `EXTRACT` lines could not distinguish a 482 MB import from a 34 GB one,
        // nor a split import from a single file. A run with no start line and a
        // CANCELLED line is the §2.57 shape, where the two halves could be
        // compared but not reconciled.
        DroidVaultLogger.i(
            "Transfer",
            "import starting from $archivePath: " +
                (if (sourceParts.isEmpty()) "single file"
                else "${AppParts.count(sourceParts.size, "part")}") +
                ", merge=${if (replace) "REPLACE" else "KEEP_DESTINATION"}"
        )
        TransferService.startImport(
            context,
            _savedPath.value,
            archivePath,
            sourceParts,
            if (replace) VaultTransfer.Merge.REPLACE else VaultTransfer.Merge.KEEP_DESTINATION,
            currentConfigCounts(),
            stagedCopy?.absolutePath
        )
    }

    /**
     * Everything an import owes the UI once its work is done.
     *
     * Split out of the call site so the service-owned path and any future one
     * report identically — the summary, the refreshes, and the configuration
     * prompt that a clean install cannot do without.
     */
    private suspend fun finishImport(
        context: Context,
        result: VaultTransferManager.Result
    ) {
        val message: String = when (result) {
            is VaultTransferManager.Result.Imported -> buildString {
                val o = result.outcome
                append("Imported ${o.addedCount} new")
                if (o.replaced.isNotEmpty()) append(", replaced ${o.replaced.size}")
                if (o.keptExisting.isNotEmpty()) append(", kept ${o.keptExisting.size} existing")
                if (o.failed.isNotEmpty()) append(", ${o.failed.size} FAILED")
                result.configsRestored?.let { append(". Restored your ${it.describe()}") }
            }

            is VaultTransferManager.Result.Failed ->
                if (result.reason == VaultTransferManager.CANCELLED) "Import cancelled"
                else "Import failed: ${result.reason}"
            // Not reachable: import() cannot return Exported.
            is VaultTransferManager.Result.Exported -> ""
        }
        MigratorManager.clearCache()
        refreshTrash()
        refreshDeviceBackups()
        // An import writes NEW archives into the vault, so the app list on screen
        // is stale the moment it finishes — `clearCache()` only drops the cache,
        // it does not re-read anything, and nothing repopulated it while the
        // screen stayed bound. Measured on device: an import that restored a
        // deleted app's four archives left it missing from the Manage list until
        // the screen was left and re-entered, which reads as "the import did not
        // work" on the one screen that shows its result. `refreshCurrentList()`
        // re-reads whatever list the current mode is showing (the same call the
        // debloat/restore completion makes) and subsumes the clearCache above.
        refreshCurrentList()
        _pendingConfigs.value = (result as? VaultTransferManager.Result.Imported)?.configsPending
        withContext(Dispatchers.Main) {
            _uiState.update {
                it.copy(
                    isRunning = false,
                    currentAction = when {
                        (result as? VaultTransferManager.Result.Failed)
                            ?.reason == VaultTransferManager.CANCELLED -> "Import cancelled"
                        result is VaultTransferManager.Result.Failed -> "Import failed"
                        else -> "Imported"
                    },
                    currentStep = "",
                    progress = 100
                )
            }
            context.safeToast(message, Toast.LENGTH_LONG)
        }
        _isImporting.value = false
    }


    /**
     * The real filesystem path of a picked transfer file, or null.
     *
     * Resolved from the SAF document id, which for local storage is
     * `<volume>:<relative path>`: `primary:Download/x.tar.gz` means
     * `/storage/emulated/0/Download/x.tar.gz`, and a volume UUID means
     * `/storage/<uuid>/…`. Only returned when the file is really there and its
     * size agrees with the provider's, so a Drive document with a matching name
     * is never mistaken for a local file and silently read as a broken one.
     */
    private fun resolveLocalTransfer(context: Context, uri: android.net.Uri): String? =
        runCatching {
            // The document id first: it is the only answer that carries WHERE
            // the file is, so it is the only one that works for a transfer the
            // user copied somewhere other than our export folder.
            //
            // It used to run second, behind a guess at the Downloads root. The
            // guess was right until exports moved into a folder of their own,
            // and then it silently failed and the correct answer underneath
            // never ran — found on device as "that is one piece of a split
            // transfer, and the rest are not on this phone", with all fourteen
            // parts sitting right there. The specific answer
            // comes first; the guess is the fallback.
            val id = runCatching {
                android.provider.DocumentsContract.getDocumentId(uri)
            }.getOrNull()
            if (id != null) {
                resolveDocumentPath(id)?.let { path ->
                    val name = path.substringAfterLast('/')
                    if ((VaultTransfer.looksLikeTransferFile(name) ||
                            VaultTransfer.isPartName(name)) &&
                        shellFileSize(path) > 0L
                    ) {
                        Log.i("VaultTransfer", "resolved by document id: $path")
                        return path
                    }
                }
            }
            // Fallback: ask the provider what the file is CALLED and look for
            // it where we put exports. Only two places, and both named from
            // [VaultTransfer] — never a folder spelled out here, which is how
            // the two sides drifted apart in the first place.
            runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(
                        android.provider.OpenableColumns.DISPLAY_NAME,
                        android.provider.OpenableColumns.SIZE
                    ),
                    null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val name = cursor.getString(0) ?: ""
                        val size = runCatching { cursor.getLong(1) }.getOrNull() ?: -1L
                        if (VaultTransfer.looksLikeTransferFile(name) ||
                            VaultTransfer.isPartName(name)
                        ) {
                            for (dir in listOf(
                                VaultTransfer.EXPORT_DIR, "/storage/emulated/0/Download"
                            )) {
                                val candidate = "$dir/$name"
                                if (shellFileSize(candidate)
                                        .let { it > 0 && (size <= 0 || it == size) }
                                ) {
                                    Log.i("VaultTransfer", "resolved by display name: $candidate")
                                    return candidate
                                }
                            }
                        }
                    }
                }
            }
            val path = resolveDocumentPath(id ?: "") ?: return null
            val fileName = path.substringAfterLast('/')
            if (!VaultTransfer.looksLikeTransferFile(fileName) &&
                !VaultTransfer.isPartName(fileName)
            ) {
                return null
            }
            // Through the root shell, NOT java.io.File: shared storage is not
            // directly visible under scoped storage, so File.isFile lies about
            // files that are really there. Found on device as imports that
            // resolved one install and silently stopped the next — the only
            // thing that changed was the storage grant, and the resolver
            // trusted the app's own filesystem view instead of root's.
            val actual = shellFileSize(path)
            if (actual <= 0L) return null
            // The provider's own size, when it has one. A mismatch means this is
            // not the same object — a stub, a placeholder, or a different volume.
            val claimed = runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
            }.getOrNull() ?: -1L
            if (claimed > 0L && claimed != actual) return null
            path
        }.getOrNull()

    /** Size of a file on shared storage, through the root shell. Shared storage is
     * not directly visible under scoped storage, so java.io.File lies about
     * files that are really there — every size check here goes through root.
     */
    private fun shellFileSize(path: String): Long =
        Shell.cmd("stat -c %s ${ShellQuote.q(path)} 2>/dev/null").exec()
            .out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L

    /**
     * Turn a SAF document id into a real path, or null.
     *
     * Three shapes, and only the first is obvious:
     *
     *  - `raw:/storage/emulated/0/Download/x.tar.gz` — an absolute path behind a
     *    prefix. What the picker actually returns for shared storage.
     *  - `primary:Download/x.tar.gz` — the external-storage provider. The volume
     *    maps onto `/storage/emulated/0` or `/storage/<uuid>`.
     *  - `msf:12496` — what MediaStore's own provider actually returns here, as
     *    captured on device. It is a row id, not a path, so it has to be asked
     *    about. `_data` answers with the path directly; the display-name and
     *    relative-path columns are the fallback for a device that has retired it.
     *
     * The MediaStore query runs through the root shell: the app may hold no media
     * permission at all, and root always can.
     */
    private fun resolveDocumentPath(id: String): String? {
        // `raw:` and `file:` carry an ABSOLUTE path behind a prefix. The picker
        // hands these out for anything already on shared storage — captured on
        // device as
        //   raw:/storage/emulated/0/Download/DroidVault/DV-….part-aa
        // — and the `volume:relative` branch below read "raw" as a volume name
        // and built `/storage/raw//storage/emulated/0/…`, which is a path to
        // nothing. The bytes were right there the whole time; only the shape
        // was unrecognised.
        for (prefix in listOf("raw:", "file:")) {
            if (id.startsWith(prefix)) {
                val abs = id.removePrefix(prefix)
                if (abs.startsWith("/") && abs.length > 1) return abs
                return null
            }
        }
        if (id.startsWith("msf:")) {
            val row = id.removePrefix("msf:").takeWhile { it.isDigit() }
            if (row.isEmpty()) return null
            fun query(vararg cols: String): String =
                Shell.cmd(
                    "content query --uri content://media/external/file --projection " +
                        cols.joinToString(":") { ShellQuote.q(it) } +
                        " --where \"_id=$row\" 2>/dev/null"
                ).exec().out.joinToString("").trim()
            val direct = query("_data")
            if (direct.startsWith("/")) return direct.trim()
            val name = query("_display_name", "relative_path")
            val rel = name.substringAfter("relative_path=", "").trim()
            val disp = name.substringAfter("_display_name=", "").substringBefore(',').trim()
            if (rel.isEmpty() || disp.isEmpty()) return null
            return "/storage/emulated/0/${rel.trimEnd('/')}/$disp"
        }
        val volume = id.substringBefore(':', "")
        val relative = id.substringAfter(':', "")
        if (volume.isEmpty() || relative.isEmpty()) return null
        val root = if (volume.equals("primary", ignoreCase = true)) {
            "/storage/emulated/0"
        } else {
            "/storage/$volume"
        }
        return "$root/$relative"
    }

    /**
     * What this phone has configured right now.
     * Read live rather than parsed off disk: the point is to decide whether the
     * phone is ALREADY set up, and the store is what the user sees in the Auto
     * tab, so that is the thing the decision has to be about.
     */
    private fun currentConfigCounts(): VaultTransfer.ConfigCounts {
        val app = getApplication<Application>()
        return VaultTransfer.ConfigCounts(
            labels = AutomationStore.listLabels(app).size,
            configs = AutomationStore.listConfigs(app).size,
            schedules = AutomationStore.listSchedules(app).size,
            folders = BackupFolderStore.folders.value.size
        )
    }

    /**
     * A transfer carried configurations and this phone already had its own.
     *
     * Not applied — the import reports it and the UI asks. Set only when the
     * transfer had something to offer AND this phone was already configured.
     */
    private val _pendingConfigs = MutableStateFlow<VaultTransfer.ConfigCounts?>(null)
    val pendingConfigs: StateFlow<VaultTransfer.ConfigCounts?> = _pendingConfigs.asStateFlow()

    /** Dismiss without changing anything. The held file is dropped. */
    fun dismissCarriedConfigs() {
        _pendingConfigs.value = null
    }

    /**
     * Take the configurations out of the transfer, replacing this phone's.
     *
     * Explicit because it is the one irreversible thing an import does: the
     * current state file is moved into `.trash` first and the message names
     * where, so "my settings changed" is never a mystery (invariant 24).
     */
    fun adoptCarriedConfigs(context: Context) {
        _pendingConfigs.value = null
        viewModelScope.launch(Dispatchers.IO) {
            val kept = VaultTransferManager.adoptCarriedConfigs(context, _savedPath.value)
            // Re-read from the file we just wrote, per-section, so the Auto tab
            // shows what actually landed rather than what was in memory.
            AutomationStore.autoImportStateFromFile(
                getApplication(), _savedPath.value, force = true
            )
            BackupFolderStore.init(getApplication())
            withContext(Dispatchers.Main) {
                context.safeToast(
                    if (kept != null) {
                        "Configurations replaced. The previous ones were moved to $kept"
                    } else {
                        "Configurations restored"
                    },
                    Toast.LENGTH_LONG
                )
            }
        }
    }

    /** How often the copy label refreshes. Every 8 MB: visible, not chatty. */
    private val BYTE_STEP = 8L * 1024L * 1024L

    /**
     * Set while the user asks to stop an import.
     *
     * Checked in every loop — the SAF copy, the listing, the extraction and the
     * copy into the vault — so a stuck transfer can be abandoned without waiting
     * out a watchdog. An import of a 34 GB archive runs for a very long time,
     * and being unable to stop it is not acceptable.
     */
    private val _importCancel = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Set while the user asks to stop an export. Mirrors the import flag. */
    private val _exportCancel = java.util.concurrent.atomic.AtomicBoolean(false)
    private val _isExporting = MutableStateFlow(false)

    /** True while an export is running, so the sheet can offer Cancel. */
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()

    /** Stop the running export. Removes staging; never touches the vault. */
    fun cancelExport(context: Context) {
        // Through the bus, not a flag this ViewModel owns: the running work
        // belongs to the service now, and a cancel aimed at a dead scope stops
        // nothing. `requestCancel` also raises the engine's own cancel flag,
        // which is what the pack actually watches.
        TransferBus.requestCancel()
        _exportCancel.set(true)
        context.safeToast("Stopping the export…", Toast.LENGTH_SHORT)
    }

    private val _isImporting = MutableStateFlow(false)

    /** True while an import is running, so the sheet can offer Cancel. */
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    // ---------- Import screen: the exports this phone already holds ----------

    private val _transferCandidates = MutableStateFlow<List<TransferCandidate>>(emptyList())
    val transferCandidates: StateFlow<List<TransferCandidate>> = _transferCandidates.asStateFlow()

    /**
     * Free space on the filesystem an export writes to, in KB.
     *
     * **A different filesystem from the vault on most phones**, so the number
     * `overview.storage.freeBytes` carries cannot answer the export sheet's
     * question — that is the export GATE's figure, and it is measured on
     * [VaultTransfer.EXPORT_DIR]. Null means not measured, and the sheet omits
     * the row rather than rendering zero: a failed probe shown as "0 GB free"
     * would be §2.79 with the volume turned down, and this is the same class of
     * claim.
     *
     * Read fresh whenever the sheet opens, because free space is the one figure
     * on it that goes stale between launches, and a sheet quoting a number from
     * startup is a sheet quoting the wrong number by the time you need it.
     */
    private val _exportFreeKb = MutableStateFlow<Long?>(null)
    val exportFreeKb: StateFlow<Long?> = _exportFreeKb.asStateFlow()

    /** Refresh the export destination's free space. Cheap: one shell `df`. */
    fun refreshExportFreeSpace() {
        viewModelScope.launch(Dispatchers.IO) {
            _exportFreeKb.value =
                com.phoenix.droidvault.utils.AutomationSafety.freeSpaceKb(VaultTransfer.EXPORT_DIR)
        }
    }

    /**
     * The folder the NEXT export will be written to, for the confirmation sheet.
     *
     * A prediction, and labelled as one by the sheet's own wording — the real
     * name is minted when the export runs, and a collision appends `-2`. It only
     * has to be right often enough to be useful ("about here, in a folder of
     * today's date"), because the alternative was the sheet naming no
     * destination at all, which is the question the sheet exists to answer.
     */
    fun nextExportDir(): String =
        VaultTransfer.exportBatchDir(VaultTransfer.exportBatchName(System.currentTimeMillis()))

    private val _transfersScanning = MutableStateFlow(false)

    /** True while the listing is being read, so the page is not a blank frame. */
    val transfersScanning: StateFlow<Boolean> = _transfersScanning.asStateFlow()

    /**
     * The transfer in flight, as the Import page reads it.
     *
     * The page cannot use `uiState` for this. That flow is the per-tick one the
     * shell uses, and `MigratorActionScreen` deliberately confines its high
     * frequency to the one panel that renders it; a screen whose whole content
     * is a list of files should not recompose on every progress tick just to
     * show a header strip.
     */
    private val _transferStatus = MutableStateFlow(TransferStatus())
    val transferStatus: StateFlow<TransferStatus> = _transferStatus.asStateFlow()

    /**
     * When the current transfer started, for "11 min elapsed".
     *
     * Held here rather than in the screen: the screen can be recreated (a
     * rotation, a process restart with the service still running) and an
     * elapsed clock that resets on recreation would report a run that has been
     * going for twenty minutes as having just begun.
     */
    private val _transferStartedMs = MutableStateFlow(0L)
    val transferStartedMs: StateFlow<Long> = _transferStartedMs.asStateFlow()

    /**
     * Real byte samples, for the rate the estimate is derived from.
     *
     * Kept as a short trailing window rather than two counters, because the
     * whole point of [VaultTransfer.Estimate] is to measure over a span long
     * enough to survive one app's install. `report()` can be called many times
     * a second, so this is capped and pruned by time, not by count.
     */
    private val _transferSamples = MutableStateFlow<List<TransferProgress.Estimate.Sample>>(emptyList())
    val transferSamples: StateFlow<List<TransferProgress.Estimate.Sample>> =
        _transferSamples.asStateFlow()

    /**
     * Re-read the export folders.
     *
     * Not automatic. An import page that refreshes itself would move rows out
     * from under a finger mid-tap, and a scan is a privileged shell round trip
     * — the user asks for it by arriving or by pulling to refresh.
     *
     * A failure is not an error state: it lands as an empty list, which the page
     * renders as its empty state with the system picker still offered. A listing
     * that cannot be read must never be a dead end.
     */
    fun refreshTransfers() {
        if (_transfersScanning.value) return
        _transfersScanning.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val found = TransferLocator.discover()
            _transferCandidates.value = found
            _transfersScanning.value = false
        }
    }

    /**
     * Delete an export from the phone, through the recoverable trash.
     *
     * Not `rm`. An export is a copy of the user's vault that may exist nowhere
     * else — an old phone's only backup, made precisely so the phone could be
     * wiped — so erasing it is the one irreversible action in this screen, and
     * it does not have to be. The vault and `Download/` are on the same
     * filesystem (measured: device id 163 on both, on the reference phone), so
     * `mv` into `<vault>/.trash` is a rename, not a copy: instant, and
     * undoable for seven days through the same card that already covers every
     * other deletion.
     *
     * That is a stronger promise than the confirm dialog originally planned,
     * and it is the reason there is no "this cannot be brought back" warning
     * here: there is nothing irreversible to warn about. The trash card is the
     * warning, and it appears once the delete lands.
     *
     * Holds the run gate for the same reason [deleteDeviceBackups] does: an
     * import reads this very folder, and deleting pieces out from under a
     * running one is a shared-state hazard. `moveToTrash` also does a
     * read-modify-write of the trash index, which two concurrent deletes would
     * lose.
     */
    fun deleteTransfer(context: Context, candidate: TransferCandidate) {
        viewModelScope.launch(Dispatchers.IO) {
            val vaultPath = _savedPath.value
            ShellQuote.requireAbsPath(vaultPath)
            val targets = candidate.trashTargets
            if (targets.isEmpty()) {
                withContext(Dispatchers.Main) {
                    context.safeToast("That export is no longer on disk.", Toast.LENGTH_SHORT)
                }
                return@launch
            }
            if (!RunCoordinator.tryAcquire("delete:export")) {
                val holder = RunCoordinator.currentOwner ?: "another operation"
                withContext(Dispatchers.Main) {
                    context.safeToast(
                        "Another operation is in progress ($holder) — try again when it finishes",
                        Toast.LENGTH_LONG
                    )
                }
                return@launch
            }
            var batch: Trash.Batch? = null
            try {
                batch = runCatching {
                    MigratorManager.moveToTrash(context, vaultPath, targets, "exports")
                }.getOrNull()
                // A target that still exists did NOT move. Reporting success
                // anyway is the exact class of bug this app shipped before.
                val remaining = targets.count {
                    Shell.cmd("[ -e ${ShellQuote.q(it)} ]").exec().isSuccess
                }
                if (remaining > 0) {
                    DroidVaultLogger.w(
                        "Transfer",
                        "export delete: ${targets.size - remaining} of ${targets.size} moved; " +
                            "$remaining still on disk"
                    )
                }
                batch?.let { refreshTrash() }
            } finally {
                RunCoordinator.release()
            }
            // The listing is re-read whatever happened, because it just changed
            // and this is the screen that shows it (§2.58's rule, one list over).
            refreshTransfers()
            withContext(Dispatchers.Main) {
                val moved = batch?.count ?: 0
                context.safeToast(
                    when {
                        moved == 0 ->
                            "Could not delete it — check root access and the vault path"
                        else ->
                            "${candidate.sizeLabel} freed — recoverable for 7 days in Recently deleted"
                    },
                    Toast.LENGTH_LONG
                )
            }
        }
    }

    /**
     * Delete ONE device archive, from the Manage screen's device list.
     *
     * This is a flag mapper and nothing else. [deleteDeviceBackups] is the only
     * device-delete path in the app — it holds the run gate, moves rather than
     * erases, re-checks that the file actually left, and is the same function
     * the Erase Native dialog calls. Writing a second delete "just for one item"
     * would be exactly the duplication that made the consent and restore gates
     * ship wrong on one path each (invariant 18), and the archives it touches are
     * the ones a scheduled Automation run is reading at the same time.
     *
     * Restore deliberately does NOT come from here: restoring device data lives
     * in Migrate and goes through [runDeviceDataOperation], which is the same
     * function `AutomationRunner` restores through. A second restore entry point
     * on a third screen would be a third answer to "is this item damaged?".
     */
    fun deleteDeviceBackup(context: Context, item: DeviceDataItem) {
        deleteDeviceBackups(
            context = context,
            doSms = item == DeviceDataItem.MESSAGES,
            doCall = item == DeviceDataItem.CALLS,
            doContacts = item == DeviceDataItem.CONTACTS,
            doWifi = item == DeviceDataItem.WIFI,
            doWallpaper = item == DeviceDataItem.WALLPAPER,
            doBluetooth = item == DeviceDataItem.BLUETOOTH,
            doFolders = item == DeviceDataItem.FOLDERS
        )
    }

    /**
     * Start an import from a listed export.
     *
     * The page has already resolved the whole set — every piece, in stream order
     * — so this hands the existing importer a complete list rather than letting
     * it work one name at a time from a single picked file. That is the same code
     * path the picker reaches through `importVaultFromUri`, minus the URI.
     *
     * `alreadyStaged` is left at its default on purpose. It reads like it means
     * "the archive is already on disk" — which is true here — but it actually
     * means "**the caller has already taken the run gate and set the progress
     * state**", and it skips that block when true. Passing it to avoid a
     * redundant copy started an import with no gate held and no `isRunning`
     * flag: the engine never ran, nothing was written, and the screen looked
     * exactly as it had before the tap. Two copies of the gate rule would also
     * be invariant 18's failure, so this asks [importVaultFrom] for the one it
     * already owns. There is no staged copy to clean up, so `stagedCopy` stays
     * null and nothing is deleted on the way out.
     *
     * The list is deliberately NOT cleared. An import reads the export and writes
     * the VAULT; it does not consume the file, and every piece is still on disk
     * afterwards. Clearing it produced "No exports on this phone" — a flat lie,
     * on a screen whose entire value is that what it lists is really there.
     */
    fun importTransfer(context: Context, candidate: TransferCandidate) {
        if (!candidate.canImport) {
            context.safeToast(
                candidate.problem ?: "This export is not complete.",
                Toast.LENGTH_LONG
            )
            return
        }
        importVaultFrom(
            context,
            candidate.importPath,
            sourceParts = candidate.sourceParts
        )
    }

    /**
     * Stop the running import.
     *
     * Everything unwinds from here: the copy loop closes its stream, the shell
     * worker is killed, the staging tree is removed and the staged copy of the
     * picked file is deleted. The vault is never touched by a cancel — only
     * staging is.
     */
    fun cancelImport(context: Context) {
        // Same reason as cancelExport: the work is not in this scope.
        TransferBus.requestCancel()
        _importCancel.set(true)
        context.safeToast("Stopping the import…", Toast.LENGTH_SHORT)
    }

    /** The one irreversible action in the app, and it says so. */
    fun purgeTrashBatch(batchId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            MigratorManager.purgeTrashBatch(getApplication(), _savedPath.value, batchId)
            MigratorManager.clearCache()
            refreshTrash()
        }
    }

    // `deviceData` has NO default on purpose. Both call sites pass `true`; a
    // default of `false` is how the check silently became app-archives-only on
    // every entry point (B-01), and a default that narrows a check must not be
    // one forgotten argument away from coming back (§2.44).
    fun verifyVault(deviceData: Boolean) {
        if (_isVerifying.value) return
        _isVerifying.value = true
        // The dialog is NOT opened here. A check does not know which screen asked
        // for it, and only the Backups tab composes `VerificationDialog` — so the
        // old line here meant a check started on Home revealed its dialog on
        // whatever screen the user happened to open next. Found on device
        // 2026-10-13: tapping Check on Home and then tapping "Back up apps"
        // produced a "Backup health" dialog over a screen that never asked for
        // one. The control that starts a check opens its own dialog
        // (`showVerificationDialog`), which is invariant 21's rule — nothing this
        // app does may depend on where the user navigates.
        //
        // The PREVIOUS result is deliberately LEFT IN PLACE, including while this
        // check runs. It is the only thing the pre-restore gate has to work from,
        // and a check that has not finished has measured nothing — so withdrawing
        // it here unblocked a restore of an archive every screen is still calling
        // damaged, for as long as the check ran (minutes on a 30 GB vault).
        // Nothing is shown from it meanwhile: the card reads "Checking backups"
        // while `isVerifying`, and the Manage banner hides itself for the same
        // reason, so keeping it is invisible except to the gate that should keep
        // refusing. A stale HEALTHY report was never the danger the old comment
        // claimed — a healthy verdict blocks nothing either way; a withdrawn
        // DAMAGED one is the whole reason this gate exists.
        //
        // Taken BEFORE the first byte is read, and stamped onto the finished
        // report. Reading the counter afterwards would invert the question: a run
        // that finished mid-verification bumps the epoch, and a stamp taken after
        // that bump would describe the report as current even though the run wrote
        // archives it never looked at. Stamping the value from before means a
        // report that raced a run is treated as out of date — the one direction
        // that costs nothing but a second check.
        val epochAtStart = com.phoenix.droidvault.utils.RunCoordinator.vaultEpoch.value
        viewModelScope.launch(Dispatchers.IO) {
            // Check the apps AND the device data in one go.
            //
            // Both entry points called this with the default deviceData = false,
            // so the engine only ever ran --verify-apps and Messages.shift,
            // CallLogs.shift and Folders.shift were never read. For a user whose
            // only backup IS their messages — a very ordinary situation — the one
            // button that says "check my backups" silently checked nothing, and
            // then the Home row was suppressed entirely because the app count was
            // 0, leaving no way to verify at all. Two engine runs, one verdict:
            // the user gets a button that covers everything instead of a choice
            // they would not know how to make.
            var report = try {
                // Apps FIRST, always: the device-only verb reports TOTAL:0, and a
                // report with total == 0 is inconclusive by construction — so
                // passing deviceData through here verified device data twice,
                // never touched the 107 app archives, and the card honestly read
                // "did not finish". Found on device: only VERIFY_DEVICE_DONE in
                // the log after tapping Check, zero VERIFY_APP lines.
                MigratorManager.verifyBackups(getApplication(), _savedPath.value, false)
            } catch (e: Exception) {
                VerificationReport(error = e.message ?: "Verification failed")
            }
            if (deviceData) {
                val device = try {
                    MigratorManager.verifyBackups(getApplication(), _savedPath.value, true)
                } catch (e: Exception) {
                    VerificationReport(error = e.message ?: "Verification failed")
                }
                // Every count the device run produced travels into the verdict.
                // The hand-written merge this replaces kept apps/total/ok/
                // corrupt/error and dropped the rest, so a device archive whose
                // digest had changed disappeared from the verdict and the card
                // read "all readable" over it (§2.47).
                report = report.mergedWith(device)
            }
            // The age a finding carries belongs to the FINDING, not to this check:
            // whatever was on screen before holds the first sighting of every
            // archive that fails both then and now. Taken before the save, so the
            // record on disk carries the same ages the screen shows.
            val prior = _verification.value
                ?: VaultVerdictStore.load(getApplication(), _savedPath.value)
            val stamped = report
                .withFindingAges(prior, System.currentTimeMillis())
                .copy(takenAtEpoch = epochAtStart)
            // Written down before anything else can see it, so it outlives this
            // process — a restart, a low-memory kill, or the user closing the app
            // between the check and the restore the check was meant to gate. An
            // inconclusive run does not replace the stored verdict.
            VaultVerdictStore.saveIfConclusive(getApplication(), _savedPath.value, stamped)
            // And shown from the same rule: a run that observed nothing is not a
            // reason to forget a finding. Without this, a cancelled or errored
            // check emptied the pre-restore gate and a known-damaged archive was
            // restorable until the next successful look.
            _verification.value = VaultVerdict.shownAfter(
                stamped,
                VaultVerdictStore.load(getApplication(), _savedPath.value)
            )
            _isVerifying.value = false
        }
    }

    /** Hide the dialog. The result itself stays, so the restore gate still works. */
    fun dismissVerificationDialog() {
        _verificationDialogVisible.value = false
    }

    /** Re-show a result that is already in hand (the inline banner). */
    fun openVerificationDialog() {
        if (_verification.value != null) _verificationDialogVisible.value = true
    }

    /**
     * Reveal the result dialog, including while a check is still running.
     *
     * For the control that STARTS a check, which is the screen that can render
     * the dialog at all. [openVerificationDialog] stays for the banner, where a
     * result already exists and the only question is whether to re-open it.
     */
    fun showVerificationDialog() {
        _verificationDialogVisible.value = true
    }

    /**
     * Refuse a restore when a selected app's archive is known to be unreadable.
     *
     * Only refuses on a CONCLUSIVE corrupt result. An empty vault, a cancelled
     * run or an engine that would not start all return null from the gate, and
     * null must not block — otherwise a fresh install could never restore, and
     * an engine problem would masquerade as "your backups are damaged".
     */
    /**
     * Shared with the scheduler so both paths apply one rule.
     *
     * Takes no `AppState`: the rule reads the selection off `_appList`, which is
     * the live value the run itself uses. Passing state in as well invited a
     * second, staler copy of the selection — and the previous signature took one
     * it never read.
     */
    private fun restoreBlockedBy(report: VerificationReport?): String? {
        val selected = _appList.value.filter { it.isSelected }.map { it.packageName }.toSet()
        return com.phoenix.droidvault.models.restoreBlockReason(report, selected)
    }

    /**
     * Selected packages for the current restore screen.
     *
     * Extracted so the app-phase and device-data restore gates both read from
     * the same selection rather than one reading the list and the other
     * recomputing it.
     */
    fun selectedRestorePackages(): Set<String> {
        return _appList.value.filter { it.isSelected }.map { it.packageName }.toSet()
    }

    /**
     * Refuse a device-data restore when a selected device archive is known to be
     * unreadable or altered.
     *
     * Called from the one place a device restore starts
     * ([runDeviceDataOperation]), which is where the plan is actually known: the
     * previous version of this gate read a selection off `AppState` that nothing
     * ever wrote, so it could not fire — and a restore plan that nothing writes
     * is not a policy, it is a comment. The rule itself stays in the model
     * (`restoreBlockReasonDevice`), shared with the scheduler, because a rule
     * enforced in two places drifts on one of them (invariant 18).
     *
     * Only refuses on a CONCLUSIVE result, same as the app-phase gate. An empty
     * vault, a cancelled run or an engine that would not start all return null,
     * and null must not block — otherwise a fresh install could never restore
     * device data, and an engine problem would masquerade as "your backups are
     * damaged".
     */
    private fun restoreBlockedByDevice(
        report: VerificationReport?,
        items: Set<com.phoenix.droidvault.models.DeviceDataItem>
    ): String? = com.phoenix.droidvault.models.restoreBlockReasonDevice(report, items)

    fun runDynamicOperation(confirmedPermanentDebloat: Boolean = false) {
        val state = _uiState.value
        val selectedApps = runnableSelectedApps(state)
        if (selectedApps.isEmpty()) return

        // Debloat with Keep Data OFF is the one action on this screen that Droid Vault
        // cannot undo BY ITSELF. `tools::remove` runs `cmd package uninstall
        // <pkg>` with no `--user 0`, which uninstalls for every user and deletes
        // /data. Before the engine could reinstall from the app's own archive,
        // this was a silent one-tap permanent uninstall sitting under a button
        // labelled "Debloat", next to a Restore toggle implying the opposite.
        //
        // Confirmed rather than merely defaulted, because the toggle is the only
        // thing standing between the two, and invariant 24 allows an irreversible
        // path only where the user was told which one it is.
        if (state.migratorMode == MigratorMode.DEBLOAT &&
            state.actionFilterState == 1 &&
            !state.keepDebloatData &&
            !confirmedPermanentDebloat
        ) {
            _uiState.value = state.copy(pendingDebloatConfirm = true)
            return
        }
        if (state.pendingDebloatConfirm) {
            _uiState.value = state.copy(pendingDebloatConfirm = false)
        }

        // A restore reads archives back. If we already know an archive is
        // unreadable, refuse NOW with the reason rather than half-restoring an
        // app and leaving it in a state neither the app nor the user expects.
        // Only a conclusive corrupt result blocks; see restoreBlockedBy.
        //
        // RESTORE_APPS restores app archives. MANAGE is the delete path and does
        // not restore, so the pre-restore gate does not apply to it.
        //
        // The gate reads the SAME verdict every restore uses, including the
        // stored verdict carried across restarts. A damaged verdict never clears
        // itself; an inconclusive one does not block. This is the damaged-backup
        // gate: a corrupt archive is refused before it can be written into the
        // provider, not discovered at restore time.
        if (state.migratorMode == MigratorMode.RESTORE_APPS) {
            val blocked = restoreBlockedBy(_verification.value)
            if (blocked != null) {
                // Same reason as the device gate's line: a refusal that only ever
                // existed as a toast leaves no trace of the decision that stopped
                // the run, and "why did Restore do nothing" is otherwise
                // unanswerable from a log.
                DroidVaultLogger.w("Verify", "Restore refused: $blocked")
                _uiState.value = state.copy(
                    isRunning = false,
                    currentAction = "Cannot restore",
                    currentStep = blocked
                )
                return
            }
        }

        val initText = when (state.migratorMode) {
            MigratorMode.RESTORE_APPS -> "Restoring..."
            MigratorMode.DEBLOAT -> if (state.actionFilterState == 2) "Restoring..." else "Debloating..."
            MigratorMode.SYSTEMIZE -> if (state.actionFilterState == 2) "De-Systemizing..." else "Systemizing..."
            MigratorMode.MANAGE -> "Deleting..."
            else -> "Backing Up..."
        }

        // DEBOOT / SYSTEMIZE drive the SAME engine as a backup, so they take the
        // SAME gate. Without it a debloat of 200 apps could run beside a manual
        // backup or a scheduled run — two `droidvault-engine` processes on one
        // box (invariant 6) — and a backup that hit its timeout would `pkill -f
        // droidvault-engine` straight through the middle of the debloat. The
        // Migrate twin above refuses here for exactly this reason; this path was
        // the one that never did.
        val toolMode = state.migratorMode == MigratorMode.DEBLOAT ||
            state.migratorMode == MigratorMode.SYSTEMIZE
        val acquiredHere = if (toolMode) {
            RunCoordinator.tryAcquire("tools:${state.migratorMode.name}")
        } else {
            false
        }
        if (toolMode && !acquiredHere) {
            val holder = RunCoordinator.currentOwner ?: "another operation"
            DroidVaultLogger.w("Tools", "$initText refused: gate held by $holder")
            getApplication<Application>().safeToast(
                "$holder is still running. Try again when it finishes."
            )
            return
        }

        _uiState.value = state.copy(
            isRunning = true,
            currentAction = initText,
            currentStep = "Preparing Data...",
            progress = -1
        )

        viewModelScope.launch {
            val updateProgress: (String, String, Int) -> Unit = { action, step, prog ->
                val safeAction = action.ifEmpty { "Droid Vault" }
                updateProgressNotification(safeAction, step, prog)

                _uiState.update { currentState ->
                    currentState.copy(
                        currentAction = action.ifEmpty { currentState.currentAction },
                        currentStep = step.ifEmpty { currentState.currentStep },
                        progress = prog
                    )
                }
            }

            val onComplete: (String, String) -> Unit = { action, step ->
                // Backup/restore changed on-disk state; drop fetch caches so the
                // next list load refetches once instead of showing stale data.
                MigratorManager.clearCache()
                viewModelScope.launch(Dispatchers.Main) {
                    val finalStepText = when (state.migratorMode) {
                        MigratorMode.MANAGE -> {
                            val cleanStep =
                                if (step == "Freed up storage space.") "Freed space" else step
                            if (cleanStep.isNotBlank()) "$cleanStep | Apps: ${selectedApps.size}" else "Apps: ${selectedApps.size}"
                        }

                        MigratorMode.DEBLOAT, MigratorMode.SYSTEMIZE -> step
                        else -> {
                            if (step.isNotBlank()) "$step | Apps: ${selectedApps.size}" else "Apps: ${selectedApps.size}"
                        }
                    }

                    showCompletionNotification(action, finalStepText)
                    _appOperationCompletion.tryEmit(action != "Operation Failed!")

                    when (state.migratorMode) {
                        MigratorMode.DEBLOAT, MigratorMode.SYSTEMIZE -> {
                            // Was an unconditional AllInstalled: after a RESTORE
                            // from this screen that replaced the vault list with the
                            // installed one, so the apps just restored were gone
                            // from the list the user was looking at. The right list
                            // depends on which half of the screen is showing.
                            MigratorManager.clearCache()
                            refreshCurrentList()
                        }

                        MigratorMode.MANAGE -> {
                            MigratorManager.clearCache()
                            fetchAppsList("AllBackups")
                            // The delete just QUARANTINED the archives, so the
                            // trash the user can undo them from changed on disk.
                            // Without this the "Recently deleted" card stayed on
                            // its previous (empty) value and "Review" only
                            // appeared after leaving and re-entering Manage —
                            // reported on device as the Review button going
                            // missing right after a delete.
                            refreshTrash()
                        }

                        else -> {}
                    }
                    _uiState.value = _uiState.value.copy(
                        isRunning = false,
                        currentAction = action,
                        currentStep = finalStepText,
                        progress = 100
                    )
                }
                // ROM-survival for manual Migrate runs too: mirror automation state
                // + self APK into the backup folder (replaces old copies, silent).
                // Automation runs snapshot inside the shared core; this covers UI runs.
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val app = getApplication<Application>()
                        val path = app.getSharedPreferences("droidvault_prefs", Context.MODE_PRIVATE)
                            .getString(
                                "base_path",
                                SettingsManager.getDefaultPath()
                            ) ?: SettingsManager.getDefaultPath()
                        // automation-state.json is the PRIMARY store — mutations
                        // already keep it written; sync defensively after runs.
                        com.phoenix.droidvault.utils.AutomationStore.syncPrimaryStateFile(app)
                        com.phoenix.droidvault.utils.AutomationSnapshot.snapshotSelfApk(app, path)
                        com.phoenix.droidvault.utils.AutomationSnapshot.snapshotBackend(app, path)
                    } catch (_: Exception) {
                    }
                }
            }

            when (state.migratorMode) {
                MigratorMode.DEBLOAT -> {
                    ToolsManager.runDebloatOperation(
                        context = getApplication(),
                        selectedApps = selectedApps,
                        isRestore = state.actionFilterState == 2,
                        vaultPath = _savedPath.value,
                        keepData = state.keepDebloatData,
                        updateLog = {},
                        updateProgress = updateProgress,
                        onComplete = onComplete
                    )
                }

                MigratorMode.SYSTEMIZE -> {
                    if (state.actionFilterState == 2) {
                        // The module folder is keyed on the DISPLAY LABEL, and
                        // two apps can share one ("Settings", "Clock"). Say so
                        // rather than silently removing the other app's
                        // systemized copy: the removal is keyed on the same
                        // folder the engine writes.
                        val byLabel = selectedApps.groupBy { ShellQuote.safeLabel(it.label) }
                            .filterValues { it.size > 1 }
                        if (byLabel.isNotEmpty()) {
                            DroidVaultLogger.w(
                                "Tools",
                                "de-systemize: labels shared by more than one app: ${byLabel.keys}"
                            )
                        }
                        // OFF the main thread. This is a blocking root `rm -rf`
                        // per app inside `viewModelScope.launch { }`, which with no
                        // dispatcher runs on Main — the A-07/A-19 class, for an
                        // unbounded selection. Its sibling systemize path already
                        // wraps in withContext(Dispatchers.IO).
                        withContext(Dispatchers.IO) {
                        selectedApps.forEach { app ->
                            // Stripping to ASCII leaves "" for any label with no
                            // Latin characters (Chinese, Cyrillic, Arabic, emoji-only),
                            // and `require` then threw an IllegalArgumentException from
                            // inside viewModelScope with no handler — a process crash
                            // from ticking an ordinary app on a non-English phone. The
                            // sibling systemize path already uses ShellQuote.safeLabel,
                            // which never returns empty; use the same one.
                            val safeLabel = ShellQuote.safeLabel(app.label)
                            Shell.cmd("rm -rf -- ${ShellQuote.q("/data/adb/modules/DroidVault/system/product/priv-app/$safeLabel")} ${ShellQuote.q("/data/adb/modules_update/DroidVault/system/product/priv-app/$safeLabel")}").exec()
                        }
                        }
                        updateProgress("De-Systemizing", "Removed from module folder", 100)
                        onComplete("De-Systemize Complete!", "Reboot required to apply changes.")
                    } else {
                        ToolsManager.runSystemizeOperation(
                            context = getApplication(),
                            selectedApps = selectedApps,
                            updateLog = {},
                            updateProgress = updateProgress,
                            onComplete = onComplete
                        )
                    }
                }

                else -> {
                    // Live Processing screen: only the real backup/restore engine
                    // supports per-app/per-part tracking (debloat/systemize/manage
                    // keep the old progress panel).
                    val track = state.migratorMode == MigratorMode.BACKUP_APPS ||
                            state.migratorMode == MigratorMode.RESTORE_APPS
                    MigratorManager.runDynamicOperation(
                        context = getApplication(),
                        state = state,
                        selectedApps = selectedApps,
                        currentPath = _savedPath.value,
                        updateProgress = updateProgress,
                        onComplete = onComplete,
                        trackProgress = track
                    )
                    // A Processing-screen cancel leaves the cooperative cancel
                    // flag behind; the backend self-heals it next run, but clear
                    // it now so nothing else trips over a stale flag.
                    if (track) {
                        try {
                            com.phoenix.droidvault.utils.AutomationRunner.clearCancel()
                        } catch (_: Exception) {
                        }
                    }
                }
            }
            // The gate is released HERE and only here, whatever the branch above
            // did — including a branch that threw. Leaking it would leave the app
            // refusing every later run for the life of the process.
            if (acquiredHere) RunCoordinator.release()
        }
    }

    fun refreshCurrentList() {
        MigratorManager.clearCache()
        val state = _uiState.value
        if (state.migratorMode != MigratorMode.MENU) {
            when (state.migratorMode) {
                MigratorMode.BACKUP_APPS -> fetchAppsList("AllInstalled")
                MigratorMode.RESTORE_APPS -> fetchAppsList("AllBackups")
                MigratorMode.DEBLOAT -> {
                    // The restore half of this screen used to fetch "Uninstalled" —
                    // the package manager's apps with FLAG_INSTALLED cleared, which
                    // is a list the debloat itself shortens. An app removed with Keep
                    // Data off is gone from the package manager entirely (verified:
                    // `pm path`, `pm list packages`, `pm list packages -u` and
                    // `dumpsys package` all return nothing for it), so it could never
                    // appear in the very list meant to put it back — reported on
                    // device as an empty Restore list right after debloating.
                    //
                    // What can be restored is decided by the VAULT, not by what the
                    // package manager still knows, so this is the same fetch the
                    // Migrate restore screen uses. Automation already states the rule
                    // (AutomationRunner: an installed-list filter "would silently drop
                    // every uninstalled app").
                    val type = if (state.actionFilterState == 2) "AllBackups" else "AllInstalled"
                    fetchAppsList(type)
                }

                MigratorMode.SYSTEMIZE -> fetchAppsList("AllInstalled")
                MigratorMode.MANAGE -> {
                    fetchAppsList("AllBackups")
                    refreshFolderArchive()
                    // The transfer files this phone holds are managed here too, so
                    // the list that shows them is read on arrival rather than only
                    // when the Import page happens to be opened. Without this the
                    // Exports card was permanently empty on the one screen whose job
                    // is managing what is on the phone.
                    refreshTransfers()
                    // Same for the device archives the Device data card lists.
                    // A batch restored from the trash can put a `Messages.shift`
                    // back, and this is the read that would notice (§2.71 — write
                    // the refresh rule against the write, not against the list
                    // that happened to be on screen).
                    refreshDeviceBackups()
                }
                MigratorMode.IMPORT_TRANSFERS -> {
                    // Deliberately fetches NO app list. This screen lists the
                    // exports this phone holds — files in Download/DroidVault —
                    // and seeding it with the installed package set would be
                    // both a pointless scan of hundreds of rows and a second
                    // thing for the screen to have to ignore. `refreshTransfers`
                    // is driven by the screen's own arrival instead.
                }
            }
        }
    }

    fun toggleKeepDebloatData() {
        _uiState.update { it.copy(keepDebloatData = !it.keepDebloatData) }
    }

    fun dismissDebloatConfirm() {
        _uiState.update { it.copy(pendingDebloatConfirm = false) }
    }

    fun confirmPermanentDebloat() {
        _uiState.update { it.copy(pendingDebloatConfirm = false) }
        runDynamicOperation(confirmedPermanentDebloat = true)
    }

    fun setLabelFilter(labelId: String?) {
        _uiState.update { it.copy(filterLabelId = labelId) }
    }

    // --- Consent gates. Defaults since 2.0.4: Restore Android ID and Restore
    // permissions ON (InstallerPolicy), fast install OFF. Switches persist
    // through InstallerPolicy and are loaded at VM init (loadConsentState). ---
    fun setFastInstallAllowed(allowed: Boolean) {
        com.phoenix.droidvault.utils.InstallerPolicy.setFastInstall(getApplication(), allowed)
        _uiState.update { it.copy(allowFastInstall = allowed) }
    }
    fun setSsaidRestoreAllowed(allowed: Boolean) {
        com.phoenix.droidvault.utils.InstallerPolicy.setSsaid(getApplication(), allowed)
        _uiState.update { it.copy(allowSsaidRestore = allowed) }
    }
    fun setPermRestoreAllowed(allowed: Boolean) {
        com.phoenix.droidvault.utils.InstallerPolicy.setPermRestore(getApplication(), allowed)
        _uiState.update { it.copy(allowPermRestore = allowed) }
    }
    fun loadConsentState() {
        val app = getApplication<Application>()
        _uiState.update {
            it.copy(
                allowFastInstall = com.phoenix.droidvault.utils.InstallerPolicy.isFastInstallAllowed(app),
                allowSsaidRestore = com.phoenix.droidvault.utils.InstallerPolicy.isSsaidAllowed(app),
                allowPermRestore = com.phoenix.droidvault.utils.InstallerPolicy.isPermRestoreAllowed(app)
            )
        }
    }
}
