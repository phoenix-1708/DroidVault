package com.phoenix.droidvault

import android.app.Application
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.phoenix.droidvault.models.AppHistoryRecord
import com.phoenix.droidvault.models.AppInfo
import com.phoenix.droidvault.models.AppLabel
import com.phoenix.droidvault.models.AutomationSchedule
import com.phoenix.droidvault.models.BackupComponent
import com.phoenix.droidvault.models.BackupConfig
import com.phoenix.droidvault.models.BackupPlan
import com.phoenix.droidvault.models.ConfigOperation
import com.phoenix.droidvault.models.ConfigRule
import com.phoenix.droidvault.models.DeviceDataItem
import com.phoenix.droidvault.models.FallbackPolicy
import com.phoenix.droidvault.models.PlannableApp
import com.phoenix.droidvault.models.validateAutomationSchedule
import com.phoenix.droidvault.utils.AutomationCapability
import com.phoenix.droidvault.utils.AppDetail
import com.phoenix.droidvault.utils.AppDetailLoader
import com.phoenix.droidvault.utils.AutomationRunner
import com.phoenix.droidvault.utils.AutomationSnapshot
import com.phoenix.droidvault.utils.AutomationStore
import com.phoenix.droidvault.utils.BackupConfigFolders
import com.phoenix.droidvault.utils.BackupFolderStore
import com.phoenix.droidvault.utils.BackupPlanner
import com.phoenix.droidvault.utils.InstallerPolicy
import com.phoenix.droidvault.utils.MigratorManager
import com.phoenix.droidvault.utils.SettingsManager
import com.phoenix.droidvault.utils.ToolsManager
import com.phoenix.droidvault.utils.validateAutomationConfig
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AutoMode { MENU, LABELS, LABEL_DETAIL, CONFIGS, EDITOR, PREVIEW, RESTORE_CONFIGS, RESTORE_EDITOR, RESTORE_PREVIEW, HISTORY, HISTORY_DETAIL, SCHEDULES, APP_DETAIL }

/**
 * Restore configurations reuse the BackupConfig shape with
 * operation = RESTORE. This wrapper tags editing/preview state so the two
 * screens can never mix: a restore config always saves/edits/previews in the
 * restore screens, a backup config in the backup ones.
 */
data class RestoreConfigOperation(val config: BackupConfig) {
    val operation: ConfigOperation get() = config.operation
}

/** Thin state holder over AutomationStore + BackupPlanner (both unit-tested). */
@Immutable
data class AutomationUiState(
    val mode: AutoMode = AutoMode.MENU,
    val labels: List<AppLabel> = emptyList(),
    /** packageName -> labelIds */
    val assignments: Map<String, Set<String>> = emptyMap(),
    val configs: List<BackupConfig> = emptyList(),
    val selectedLabelId: String? = null,
    val editingConfig: BackupConfig? = null,
    val previewPlan: BackupPlan? = null,
    val previewConfigId: String? = null,
    /** Editing state for Restore Configurations (operation is always RESTORE). */
    val editingRestoreConfig: RestoreConfigOperation? = null,
    /** Dry-run plan for Restore Configurations. */
    val restorePreviewPlan: BackupPlan? = null,
    val restorePreviewConfigId: String? = null,
    /** Backup-folder view for Restore Configurations (like Migrate → Restore). */
    val restoreApps: List<AppInfo> = emptyList(),
    val isLoadingRestoreApps: Boolean = false,
    val history: List<AutomationStore.ExecutionSummary> = emptyList(),
    /** Per-app records for the run being drilled into (P4 transparency). */
    val historyDetailId: String? = null,
    val historyDetailApps: List<AppHistoryRecord> = emptyList(),
    /** Pre-clear history JSON for the snackbar's Undo affordance; null = none. */
    val historyUndoSnapshot: String? = null,
    val lastTransfer: String? = null,
    /** Monotonic run id: diagnoses re-arms (a new run while one looks stuck). */
    val runSeq: Long = 0,
    /** State backup found in the Droid Vault folder while local store is empty. */
    val foundStateBackup: AutomationSnapshot.StateBackupInfo? = null,
    /** Content URI string for the latest export, consumed once by the share sheet. */
    val shareUri: String? = null,
    val schedules: List<AutomationSchedule> = emptyList(),
    val editingSchedule: AutomationSchedule? = null,
    val masterEnabled: Boolean = true,
    val detailPkg: String? = null,
    val detailReturn: AutoMode = AutoMode.MENU,
    val detail: AppDetail? = null,
    val detailLoading: Boolean = false,
    val isRunning: Boolean = false,
    /** Which config the current run belongs to — only its card flips to Cancel. */
    val runningConfigId: String? = null,
    val runAction: String = "",
    val runStep: String = "",
    val runProgress: Int = 0,
    val error: String? = null,
    /**
     * True while a back request is waiting on an unsaved-changes confirm
     * (see [requestBack]). The editor screens render the dialog from this.
     */
    val pendingBack: Boolean = false
)

class AutomationViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(AutomationUiState())
    val uiState: StateFlow<AutomationUiState> = _uiState.asStateFlow()

    /**
     * Folder backup sources (DeviceDataItem.FOLDERS) — the same process-wide
     * store the Migrate tab and Settings use, so config editors always offer
     * the current registered folders.
     */
    val backupFolders: kotlinx.coroutines.flow.StateFlow<List<String>> = BackupFolderStore.folders

    /**
     * Registers a picked folder (shared with Settings/Migrate).
     *
     * Off the main thread: BackupFolderStore persists with prefs.commit(), which
     * fsyncs, and this used to be called directly from a folder-picker
     * callback on the UI thread. [onDone] receives the result.
     */
    fun addBackupFolder(path: String, onDone: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = BackupFolderStore.add(getApplication(), path)
            onDone?.let { kotlinx.coroutines.withContext(Dispatchers.Main) { it(ok) } }
        }
    }

    /**
     * Reacts to Droid Vault folder changes made in Settings/onboarding: re-reads
     * prefs (via resolveSavedPath users) and auto-imports any automation
     * state saved in the NEW folder, then refreshes every screen's data.
     * Without this, config lists kept the old folder's data until restart.
     */
    fun onBasePathChanged() {
        // The caches describe sizes read from the OLD folder, so emptying the
        // list and refilling from them served the stale disk view. Drop them
        // BEFORE the refill.
        com.phoenix.droidvault.utils.MigratorManager.clearCache()
        // Stale disk view: the restore lists were read from the OLD folder.
        _uiState.update { it.copy(restoreApps = emptyList()) }
        refresh()
        loadRestoreApps()
    }

    init {
        // Fire the same reaction when the persisted base_path changes under
        // us (Settings move, onboarding pick) — main-process prefs listener.
        try {
            getApplication<Application>().getSharedPreferences("droidvault_prefs", android.content.Context.MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener { _, key ->
                    if (key == "base_path") onBasePathChanged()
                }
        } catch (_: Exception) {
        }
    }

    /** Handle of the current manual/batch run; cancelRun() stops it cooperatively. */
    private var runJob: kotlinx.coroutines.Job? = null

    fun setMode(mode: AutoMode) {
        _uiState.update { it.copy(mode = mode, error = null) }
    }

    fun modeBack() {
        val current = _uiState.value
        _uiState.update {
            it.copy(
                mode = when (current.mode) {
                    AutoMode.LABEL_DETAIL -> AutoMode.LABELS
                    AutoMode.EDITOR, AutoMode.PREVIEW -> AutoMode.CONFIGS
                    AutoMode.RESTORE_EDITOR, AutoMode.RESTORE_PREVIEW -> AutoMode.RESTORE_CONFIGS
                    AutoMode.HISTORY_DETAIL -> AutoMode.HISTORY
                    AutoMode.APP_DETAIL -> current.detailReturn
                    else -> AutoMode.MENU
                },
                editingConfig = if (current.mode == AutoMode.CONFIGS) null else current.editingConfig,
                previewPlan = if (current.mode == AutoMode.CONFIGS) null else current.previewPlan,
                editingRestoreConfig = if (current.mode == AutoMode.RESTORE_CONFIGS) null else current.editingRestoreConfig,
                restorePreviewPlan = if (current.mode == AutoMode.RESTORE_CONFIGS) null else current.restorePreviewPlan,
                error = null,
                pendingBack = false
            )
        }
    }

    /**
     * THE back path for every Auto submenu (app-bar arrow, predictive back).
     * Editors with unsaved changes raise the discard confirm instead of
     * navigating; everything else backs out immediately. Previously the
     * in-content arrows went through the editors' local guard while the
     * app-bar arrow called [modeBack] directly and SILENTLY DISCARDED edits
     * — one guard, shared by all entry points, fixes both the duplicate
     * affordance and that inconsistency.
     */
    fun requestBack() {
        val s = _uiState.value
        val needsConfirm =
            (s.mode == AutoMode.EDITOR && s.editingConfig != null && editingDirty()) ||
                (s.mode == AutoMode.RESTORE_EDITOR && s.editingRestoreConfig != null && restoreEditingDirty())
        if (needsConfirm) {
            _uiState.update { it.copy(pendingBack = true) }
        } else {
            modeBack()
        }
    }

    /** Stay in the editor (discard confirm dismissed). */
    fun keepEditing() {
        _uiState.update { it.copy(pendingBack = false) }
    }

    /** Discard confirm accepted: drop the editor and go back. */
    fun discardEditing() {
        _uiState.update { it.copy(pendingBack = false) }
        modeBack()
    }

    /**
     * Restore configs are BackupConfigs with operation = RESTORE, filtered by
     * the UI from the same [configs] list so export/import/schedules/history
     * stay single-sourced with backup configs.
     */

    fun refresh() {
        // Re-entrancy guard: every config save, schedule change and run
        // completion calls refresh(); overlapping runs would re-read all
        // stores concurrently and cascade full recomposition through every
        // Auto screen. The last signal still triggers a fresh read (false
        // clears the flag when the job finishes).
        if (!_refreshInFlight.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = getApplication<Application>()
                // Folder-bind + primary-store reconcile ONCE per refresh: the
                // primary state file is resolved by the section reads below
                // (TTL-cached), so this only handles the promote/backfill
                // paths (legacy prefs→file migration, app-data-clear recovery).
                AutomationStore.autoImportStateFromFile(app, savedPathOf(app))
                // Reinstall self-heal: rules/assignments carry the previous
                // install's label UUIDs; the state file brings labels back
                // under fresh UUIDs. Exact by-name relink, no-op on healthy
                // stores (two list reads, zero writes).
                AutomationStore.relinkDanglingRules(app)
                val labels = AutomationStore.listLabels(app)
                val assignments = AutomationStore.listAssignments(app)
                    .groupBy({ it.packageName }, { it.labelId })
                    .mapValues { it.value.toSet() }
                val configs = AutomationStore.listConfigs(app)
                val schedules = AutomationStore.listSchedules(app)
                val master = AutomationStore.isMasterEnabled(app)
                // History rides along (cheap prefs parse, ≤50 summaries): config
                // cards show "Last backup/restore" and it must be live right after
                // a run completes — the run's finally block calls refresh().
                val history = AutomationStore.listExecutions(app)
                _uiState.update {
                    it.copy(
                        labels = labels, assignments = assignments, configs = configs,
                        schedules = schedules, masterEnabled = master, history = history
                    )
                }
            } catch (e: Exception) {
                // A storage hiccup must NEVER wedge the guard: the flag clears
                // in finally, but without a catch the in-flight data (labels,
                // configs, history) silently stopped updating with no signal.
                android.util.Log.w("AutomationViewModel", "refresh failed", e)
                _uiState.update { it.copy(error = "Refresh failed: ${e.message}") }
            } finally {
                _refreshInFlight.set(false)
            }
        }
    }

    private val _refreshInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    fun labelsFor(pkg: String): Set<String> = _uiState.value.assignments[pkg] ?: emptySet()

    fun appsForLabel(labelId: String, apps: List<AppInfo>): List<AppInfo> {
        val pkgs = _uiState.value.assignments.filter { (_, ids) -> labelId in ids }.keys
        return apps.filter { it.packageName in pkgs }.sortedBy { it.label.lowercase() }
    }

    // ---------- labels ----------

    fun createLabel(name: String, description: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            val res = AutomationStore.createLabel(getApplication(), name, description)
            withContext(Dispatchers.Main) {
                res.onSuccess { refresh() }
                    .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
            }
        }
    }

    fun renameLabel(id: String, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = AutomationStore.renameLabel(getApplication(), id, newName)
            withContext(Dispatchers.Main) {
                res.onSuccess { refresh() }
                    .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
            }
        }
    }

    fun deleteLabel(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            AutomationStore.deleteLabel(getApplication(), id)
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(selectedLabelId = null) }
                refresh()
            }
        }
    }

    fun openLabel(id: String) {
        _uiState.update { it.copy(selectedLabelId = id, mode = AutoMode.LABEL_DETAIL, error = null) }
    }

    fun toggleAssign(pkg: String, labelId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            if (labelsFor(pkg).contains(labelId)) {
                AutomationStore.unassign(app, pkg, labelId)
            } else {
                AutomationStore.assign(app, pkg, labelId)
            }
            refresh()
        }
    }

    fun bulkAssign(pkgs: List<String>, labelId: String, onDone: ((Int) -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            // One resolve + ONE persist. The per-package loop this replaced
            // rewrote the whole primary state file through a root-shell round
            // trip per package, re-serializing every assignment each time.
            val n = try {
                AutomationStore.bulkAssign(getApplication(), pkgs, labelId)
            } catch (_: Exception) {
                0
            }
            refresh()
            onDone?.let { kotlinx.coroutines.withContext(Dispatchers.Main) { it(n) } }
        }
    }

    fun bulkUnassign(pkgs: List<String>, labelId: String, onDone: ((Int) -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val n = AutomationStore.bulkUnassign(getApplication(), pkgs, labelId)
            refresh()
            onDone?.let { kotlinx.coroutines.withContext(Dispatchers.Main) { it(n) } }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    // ---------- configs ----------

    fun newConfig() {
        _uiState.update {
            it.copy(
                editingConfig = BackupConfig(
                    id = AutomationStore.newId(),
                    name = "",
                    rules = emptyList()
                ),
                mode = AutoMode.EDITOR,
                error = null
            )
        }
    }

    fun editConfig(id: String) {
        val cfg = _uiState.value.configs.find { it.id == id } ?: return
        _uiState.update { it.copy(editingConfig = cfg, mode = AutoMode.EDITOR, error = null) }
    }

    /** New restore configuration (operation preset to RESTORE, same shape). */
    fun newRestoreConfig() {
        _uiState.update {
            it.copy(
                editingRestoreConfig = RestoreConfigOperation(
                    BackupConfig(
                        id = AutomationStore.newId(),
                        name = "",
                        operation = ConfigOperation.RESTORE,
                        restoreGraceful = true
                    )
                ),
                mode = AutoMode.RESTORE_EDITOR,
                error = null
            )
        }
    }

    fun editRestoreConfig(id: String) {
        val cfg = _uiState.value.configs.find { it.id == id && it.operation == ConfigOperation.RESTORE } ?: return
        _uiState.update {
            it.copy(editingRestoreConfig = RestoreConfigOperation(cfg), mode = AutoMode.RESTORE_EDITOR, error = null)
        }
    }

    fun updateRestoreEditing(
        name: String? = null,
        description: String? = null,
        enabled: Boolean? = null,
        fallback: FallbackPolicy? = null,
        restoreGraceful: Boolean? = null,
        deviceData: Set<DeviceDataItem>? = null,
        folderPaths: List<String>? = null
    ) {
        _uiState.update { state ->
            val wrap = state.editingRestoreConfig ?: return@update state
            val cfg = wrap.config
            state.copy(
                editingRestoreConfig = RestoreConfigOperation(
                    cfg.copy(
                        name = name ?: cfg.name,
                        description = description ?: cfg.description,
                        enabled = enabled ?: cfg.enabled,
                        fallback = fallback ?: cfg.fallback,
                        restoreGraceful = restoreGraceful ?: cfg.restoreGraceful,
                        deviceData = deviceData ?: cfg.deviceData,
                        folderPaths = folderPaths ?: cfg.folderPaths,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            )
        }
    }

    fun addRestoreRule(labelId: String, components: Set<BackupComponent>, exclude: Boolean = false) {
        _uiState.update { state ->
            val wrap = state.editingRestoreConfig ?: return@update state
            val cfg = wrap.config
            val nextPrio = (cfg.rules.maxOfOrNull { it.priority } ?: 0) + 1
            state.copy(
                editingRestoreConfig = RestoreConfigOperation(
                    cfg.copy(
                        rules = cfg.rules + ConfigRule(
                            id = AutomationStore.newId(),
                            labelId = labelId,
                            priority = nextPrio,
                            components = components,
                            exclude = exclude
                        ),
                        updatedAt = System.currentTimeMillis()
                    )
                )
            )
        }
    }

    fun removeRestoreRule(ruleId: String) {
        _uiState.update { state ->
            val wrap = state.editingRestoreConfig ?: return@update state
            val cfg = wrap.config
            state.copy(
                editingRestoreConfig = RestoreConfigOperation(
                    cfg.copy(rules = cfg.rules.filter { it.id != ruleId })
                )
            )
        }
    }

    fun moveRestoreRule(ruleId: String, up: Boolean) {
        _uiState.update { state ->
            val wrap = state.editingRestoreConfig ?: return@update state
            val cfg = wrap.config
            val sorted = cfg.rules.sortedBy { it.priority }
            val idx = sorted.indexOfFirst { it.id == ruleId }
            val swapWith = if (up) idx - 1 else idx + 1
            if (idx < 0 || swapWith !in sorted.indices) return@update state
            val reordered = sorted.toMutableList()
            val a = reordered[idx].priority
            reordered[idx] = reordered[idx].copy(priority = reordered[swapWith].priority)
            reordered[swapWith] = reordered[swapWith].copy(priority = a)
            state.copy(
                editingRestoreConfig = RestoreConfigOperation(
                    cfg.copy(rules = reordered.sortedBy { it.priority })
                )
            )
        }
    }

    fun updateRestoreRule(ruleId: String, components: Set<BackupComponent>? = null, exclude: Boolean? = null) {
        _uiState.update { state ->
            val wrap = state.editingRestoreConfig ?: return@update state
            val cfg = wrap.config
            state.copy(
                editingRestoreConfig = RestoreConfigOperation(
                    cfg.copy(
                        rules = cfg.rules.map {
                            if (it.id == ruleId) it.copy(
                                components = components ?: it.components,
                                exclude = exclude ?: it.exclude
                            ) else it
                        }
                    )
                )
            )
        }
    }

    fun restoreEditingErrors(): List<String> {
        val wrap = _uiState.value.editingRestoreConfig ?: return listOf("nothing to save")
        return validateAutomationConfig(wrap.config, _uiState.value.labels.map { it.id }.toSet())
    }

    fun saveRestoreEditing() {
        val wrap = _uiState.value.editingRestoreConfig ?: return
        viewModelScope.launch(Dispatchers.IO) {
            // Mirror of saveEditing: the restore editor always persists RESTORE.
            val res = AutomationStore.saveConfig(
                getApplication(), wrap.config.copy(operation = ConfigOperation.RESTORE)
            )
            withContext(Dispatchers.Main) {
                res.onSuccess {
                    refresh()
                    setMode(AutoMode.RESTORE_CONFIGS)
                }.onFailure { e -> _uiState.update { it.copy(error = e.message) } }
            }
        }
    }

    fun duplicateRestoreConfig(id: String, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = AutomationStore.duplicateConfig(getApplication(), id, newName)
            withContext(Dispatchers.Main) {
                res.onSuccess { refresh() }
                    .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
            }
        }
    }

    fun deleteRestoreConfig(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            AutomationStore.deleteConfig(getApplication(), id)
            refresh()
        }
    }

    fun updateEditing(
        name: String? = null,
        description: String? = null,
        enabled: Boolean? = null,
        fallback: FallbackPolicy? = null,
        operation: ConfigOperation? = null,
        deviceData: Set<DeviceDataItem>? = null,
        folderPaths: List<String>? = null
    ) {
        _uiState.update { state ->
            val cfg = state.editingConfig ?: return@update state
            state.copy(
                editingConfig = cfg.copy(
                    name = name ?: cfg.name,
                    description = description ?: cfg.description,
                    enabled = enabled ?: cfg.enabled,
                    fallback = fallback ?: cfg.fallback,
                    operation = operation ?: cfg.operation,
                    deviceData = deviceData ?: cfg.deviceData,
                    folderPaths = folderPaths ?: cfg.folderPaths,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun addRule(labelId: String, components: Set<BackupComponent>, exclude: Boolean = false) {
        _uiState.update { state ->
            val cfg = state.editingConfig ?: return@update state
            val nextPrio = (cfg.rules.maxOfOrNull { it.priority } ?: 0) + 1
            state.copy(
                editingConfig = cfg.copy(
                    rules = cfg.rules + ConfigRule(
                        id = AutomationStore.newId(),
                        labelId = labelId,
                        priority = nextPrio,
                        components = components,
                        exclude = exclude
                    ),
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun removeRule(ruleId: String) {
        _uiState.update { state ->
            val cfg = state.editingConfig ?: return@update state
            state.copy(editingConfig = cfg.copy(rules = cfg.rules.filter { it.id != ruleId }))
        }
    }

    fun moveRule(ruleId: String, up: Boolean) {
        _uiState.update { state ->
            val cfg = state.editingConfig ?: return@update state
            val sorted = cfg.rules.sortedBy { it.priority }
            val idx = sorted.indexOfFirst { it.id == ruleId }
            val swapWith = if (up) idx - 1 else idx + 1
            if (idx < 0 || swapWith !in sorted.indices) return@update state
            val reordered = sorted.toMutableList()
            val a = reordered[idx].priority
            reordered[idx] = reordered[idx].copy(priority = reordered[swapWith].priority)
            reordered[swapWith] = reordered[swapWith].copy(priority = a)
            state.copy(editingConfig = cfg.copy(rules = reordered.sortedBy { it.priority }))
        }
    }

    fun updateRule(ruleId: String, components: Set<BackupComponent>? = null, exclude: Boolean? = null) {
        _uiState.update { state ->
            val cfg = state.editingConfig ?: return@update state
            state.copy(
                editingConfig = cfg.copy(
                    rules = cfg.rules.map {
                        if (it.id == ruleId) it.copy(
                            components = components ?: it.components,
                            exclude = exclude ?: it.exclude
                        ) else it
                    }
                )
            )
        }
    }

    fun editingErrors(): List<String> {
        val cfg = _uiState.value.editingConfig ?: return listOf("nothing to save")
        return validateAutomationConfig(cfg, _uiState.value.labels.map { it.id }.toSet())
    }

    /**
     * True while the editor holds unsaved changes (differs from the stored
     * config, or is brand new). Drives the editor's unsaved-changes banner.
     */
    fun editingDirty(): Boolean {
        val cfg = _uiState.value.editingConfig ?: return false
        val stored = _uiState.value.configs.find { it.id == cfg.id } ?: return true // new config
        return cfg.copy(updatedAt = 0L) != stored.copy(updatedAt = 0L)
    }

    /** Same for the restore editor. */
    fun restoreEditingDirty(): Boolean {
        val wrap = _uiState.value.editingRestoreConfig ?: return false
        val cfg = wrap.config
        val stored = _uiState.value.configs.find { it.id == cfg.id } ?: return true
        return cfg.copy(updatedAt = 0L) != stored.copy(updatedAt = 0L)
    }

    fun saveEditing() {
        val cfg = _uiState.value.editingConfig ?: return
        viewModelScope.launch(Dispatchers.IO) {
            // The backup editor always persists BACKUP: a legacy imported
            // config edited here is normalized instead of keeping a stale op.
            val res = AutomationStore.saveConfig(getApplication(), cfg.copy(operation = ConfigOperation.BACKUP))
            withContext(Dispatchers.Main) {
                res.onSuccess {
                    refresh()
                    setMode(AutoMode.CONFIGS)
                }.onFailure { e -> _uiState.update { it.copy(error = e.message) } }
            }
        }
    }

    fun duplicateConfig(id: String, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = AutomationStore.duplicateConfig(getApplication(), id, newName)
            withContext(Dispatchers.Main) {
                res.onSuccess { refresh() }
                    .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
            }
        }
    }

    fun setConfigEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            AutomationStore.setConfigEnabled(getApplication(), id, enabled)
            refresh()
        }
    }

    fun deleteConfig(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            AutomationStore.deleteConfig(getApplication(), id)
            refresh()
        }
    }

    /** Swap a config one slot with its neighbour in the Backup/Restore list. */
    fun moveConfig(id: String, up: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            AutomationStore.moveConfig(getApplication(), id, up)
            refresh()
        }
    }

    // ---------- dry-run preview ----------

    /**
     * Restore-side disk view: per-app Meta.txt entries from the backup
     * folder — the exact same inventory the Migrate tab's Restore list
     * shows. Cached in MigratorManager (AllBackups) so re-entering restore
     * screens is instant; run/ops completions clear it.
     */
    fun loadRestoreApps() {
        if (_uiState.value.isLoadingRestoreApps) return
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(isLoadingRestoreApps = true) }
            }
            try {
                val app = getApplication<Application>()
                val savedPath = AutomationRunner.resolveSavedPath(app)
                val apps = MigratorManager.fetchAppsList(app, savedPath, "AllBackups")
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(restoreApps = apps, isLoadingRestoreApps = false) }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(restoreApps = emptyList(), isLoadingRestoreApps = false, error = "Could not read backup folder: ${e.message}")
                    }
                }
            }
        }
    }

    /** Restore dry-run: disk availability + consent-aware planning. */
    fun restorePreview(
        configId: String,
        hasRoot: Boolean,
        allowPerm: Boolean,
        allowSsaid: Boolean
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val config = AutomationStore.listConfigs(app).find { it.id == configId } ?: return@launch
            val assignments = AutomationStore.listAssignments(app)
            val backupApps = MigratorManager.fetchAppsList(
                app, AutomationRunner.resolveSavedPath(app), "AllBackups"
            )
            val caps = AutomationCapability.resolve(hasRoot, allowPerm, allowSsaid)
            val plannable = backupApps.map { PlannableApp(it.packageName, it.label, it.isSystem, it.isInstalled) }
            val onDisk = backupApps.associate { it.packageName to it.availableInBackup }
            val reason: (BackupComponent) -> String =
                { c -> AutomationCapability.reasonFor(c, hasRoot, allowPerm, allowSsaid) }
            val plan = BackupPlanner.plan(
                plannable, assignments, config, caps, dryRun = true,
                unavailableReason = reason, availableOnDisk = onDisk
            )
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        restorePreviewPlan = plan,
                        restorePreviewConfigId = configId,
                        restoreApps = backupApps,
                        mode = AutoMode.RESTORE_PREVIEW
                    )
                }
            }
        }    }

    fun preview(
        configId: String,
        apps: List<AppInfo>,
        hasRoot: Boolean,
        allowPerm: Boolean,
        allowSsaid: Boolean
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val config = AutomationStore.listConfigs(app).find { it.id == configId } ?: return@launch
            val assignments = AutomationStore.listAssignments(app)
            val caps = AutomationCapability.resolve(hasRoot, allowPerm, allowSsaid)
            val plannable = apps.map { PlannableApp(it.packageName, it.label, it.isSystem, it.isInstalled) }
            val reason: (BackupComponent) -> String =
                { c -> AutomationCapability.reasonFor(c, hasRoot, allowPerm, allowSsaid) }
            val plan = BackupPlanner.plan(plannable, assignments, config, caps, dryRun = true, unavailableReason = reason)
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(previewPlan = plan, previewConfigId = configId, mode = AutoMode.PREVIEW) }
            }
        }
    }

    fun matchingCount(config: BackupConfig, apps: List<AppInfo>): Int {
        val state = _uiState.value
        val labelPkgs = state.assignments
            .filter { (_, ids) -> ids.any { id -> config.rules.any { it.labelId == id } } }
            .keys
        return apps.count { it.packageName in labelPkgs }
    }

    // ---------- execution (Phase 5 runner) ----------

    /** Title/message for the run-start notification, split per operation. */
    private fun restoreRunInfo(configName: String): Pair<String, String> =
        configName to "Restore started"

    private fun backupRunInfo(configName: String): Pair<String, String> =
        configName to "Backup started"

    /** Shared single-config execution; returns null when unavailable. */
    private suspend fun doRunConfig(
        configId: String,
        apps: List<AppInfo>,
        trigger: com.phoenix.droidvault.models.TriggerType
    ): com.phoenix.droidvault.models.ConfigExecution? {
        val app = getApplication<Application>()
        val config = AutomationStore.listConfigs(app).find { it.id == configId }
        if (config == null || !config.enabled) return null
        val plannable = apps.map { PlannableApp(it.packageName, it.label, it.isSystem, it.isInstalled) }
        withContext(Dispatchers.Main) {
            _uiState.update {
                it.copy(
                    isRunning = true,
                    runningConfigId = config.id,
                    runAction = "Running ${config.name}",
                    runStep = "Starting…",
                    runProgress = -1,
                    runSeq = it.runSeq + 1
                )
            }
        }
        android.util.Log.d(
            "AutomationCancel",
            "run start seq=${_uiState.value.runSeq} config=${config.id} op=${config.operation}"
        )
        // Start-of-run tombstone (manual-run parity with ConfigRunWorker): a
        // hard process death from here until the runner's finishExecution()
        // leaves an honest FAILED "interrupted mid-run" record in History
        // instead of a silently missing run. Any completion path removes it.
        try {
            AutomationStore.recordExecution(
                app,
                AutomationRunner.manualTombstone(config, trigger)
            )
        } catch (_: Exception) {
        }
        val (title, text) = if (config.operation == ConfigOperation.RESTORE) {
            restoreRunInfo(config.name)
        } else {
            backupRunInfo(config.name)
        }
        notifyAutomation(title, text, false, config.operation)
        val execution = AutomationRunner.execute(
            context = app,
            config = config,
            apps = plannable,
            appInfos = apps,
            trigger = trigger,
            updateProgress = { action, step, prog ->
                _uiState.update {
                    it.copy(runAction = action, runStep = step, runProgress = prog)
                }
            }
        )
        notifyAutomation(
            config.name,
            "${execution.successCount} ok, ${execution.failureCount} failed, ${execution.skippedCount} skipped",
            true,
            config.operation
        )
        return execution
    }

    fun runConfig(configId: String, apps: List<AppInfo>) {
        if (_uiState.value.isRunning) return
        // Firewall: this entry point is BACKUP ONLY. Restore configs belong to
        // the Restore Configurations screen (runRestoreConfig).
        if (_uiState.value.configs.any { it.id == configId && it.operation == ConfigOperation.RESTORE }) {
            _uiState.update {
                it.copy(error = "This is a restore configuration — use the Restore Configurations menu.")
            }
            return
        }
        runJob?.cancel()
        runJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val execution = doRunConfig(
                    configId, apps, com.phoenix.droidvault.models.TriggerType.MANUAL
                )
                withContext(Dispatchers.Main) {
                    if (execution == null) {
                        _uiState.update { it.copy(error = "Configuration unavailable") }
                    } else if (execution.result == com.phoenix.droidvault.models.ResultState.SKIPPED &&
                        execution.errorSummary.isNotEmpty()
                    ) {
                        _uiState.update { it.copy(error = execution.errorSummary) }
                        // A skipped run posted "started" but will never post "done".
                        notifyAutomation("Automation", execution.errorSummary, true)
                    } else if (execution.result == com.phoenix.droidvault.models.ResultState.CANCELLED) {
                        _uiState.update { it.copy(error = "Run cancelled — finished apps are kept") }
                        notifyAutomation("Automation", "Run cancelled", true)
                    }
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                // NOTE: must be NonCancellable — in a cancelled coroutine a plain
                // withContext(Main) throws immediately WITHOUT running the block,
                // which is exactly how isRunning got stuck true forever. The
                // notification post MUST live inside it too: after runJob.cancel()
                // the coroutine is unwinding, so a notify() outside NonCancellable
                // can be silently skipped — that is how the shade kept showing
                // "Backup started" after an in-app cancel even though this branch
                // looked correct.
                withContext(Dispatchers.Main + kotlinx.coroutines.NonCancellable) {
                    _uiState.update { it.copy(error = "Run cancelled — finished apps are kept") }
                    // Replace the stuck progress notification: nothing downstream
                    // will do it since the run never completed.
                    notifyAutomation("Automation", "Run cancelled", true)
                }
            } finally {
                withContext(Dispatchers.Main + kotlinx.coroutines.NonCancellable) {
                    _uiState.update {
                        it.copy(isRunning = false, runningConfigId = null, runProgress = 100)
                    }
                }
                refresh()
            }
        }
    }

    /** Cooperative cancel: flag + hammer via control shell, then drop the coroutine. */
    fun cancelRun() {
        val seq = _uiState.value.runSeq
        viewModelScope.launch(Dispatchers.IO) {
            val job = runJob
            val jobState = if (job == null) "no-job"
            else "active=${job.isActive} completed=${job.isCompleted} cancelled=${job.isCancelled}"
            val channel = AutomationRunner.requestCancel()
            val procs = AutomationRunner.backendProcCount()
            val killed = AutomationRunner.haltBackend()
            android.util.Log.d(
                "AutomationCancel",
                "cancel tap seq=$seq: $jobState channel=$channel backendProcs=$procs killed=$killed"
            )
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(runStep = "Cancelling… (job:$jobState procs:$procs)")
                }
            }
            // Post the terminal notification from the UI thread below — do not
            // rely on the cancelled run coroutine's unwind to do it.
            // Watchdog: recheck several times — a single check can hit a flaky
            // proc count (-1) or a teardown still in flight. Recovers only when
            // provably nothing runs (same seq, job dead, zero backend procs);
            // a job that is still active is NEVER reset here (it might be
            // between backend procs) — Force-reset remains the manual escape.
            repeat(6) { attempt ->
                kotlinx.coroutines.delay(10000)
                val cur = _uiState.value
                if (!cur.isRunning || cur.runSeq != seq) return@launch
                val nowJob = runJob
                if (nowJob != null && !nowJob.isCompleted) return@repeat
                if (AutomationRunner.backendProcCount() != 0) return@repeat
                if (attempt >= 1) {
                    android.util.Log.w("AutomationCancel", "watchdog recovered stuck seq=$seq")
                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(
                                isRunning = false,
                                runningConfigId = null,
                                runProgress = 100,
                                runStep = "",
                                error = "Recovered stuck state — check History for what actually finished"
                            )
                        }
                    }
                    notifyAutomation("Automation", "Recovered stuck state — check History", true)
                    return@launch
                }
            }
        }
        runJob?.cancel()
        _uiState.update { it.copy(runStep = "Cancelling…") }
        // Immediate shade feedback from the UI thread, BEFORE the IO job above
        // (flag/hammer can block up to 20 s; the user must see the cancel land).
        notifyAutomation("Automation", "Cancelling…", true)
    }

    /**
     * Last-resort UI reset when a run is visibly stuck: clears the running
     * state WITHOUT touching the backend (use only if Cancel did nothing —
     * check logcat AutomationCancel first). Never fabricates results.
     */
    fun forceResetRun() {
        runJob?.cancel()
        _uiState.update {
            it.copy(
                isRunning = false,
                runningConfigId = null,
                runProgress = 100,
                runStep = "",
                error = "Run state reset by user — check History for what actually finished"
            )
        }
    }

    /**
     * RESTORE execution entry point (Restore Configurations screen). Runs the
     * same AutomationRunner core as backups — identical history, notifications
     * and cancel machinery — but drives the Migrate tab's --restore path and
     * honors the restore-specific planner behavior (graceful per-app parts).
     * Backup configs are rejected here to keep the two menus firewalled.
     */
    fun runRestoreConfig(configId: String, apps: List<AppInfo>) {
        if (_uiState.value.isRunning) return
        // Mirror-image firewall of runConfig.
        if (_uiState.value.configs.any { it.id == configId && it.operation != ConfigOperation.RESTORE }) {
            _uiState.update {
                it.copy(error = "This is a backup configuration — use the Backup Configurations menu.")
            }
            return
        }
        runJob?.cancel()
        runJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val execution = doRunConfig(
                    configId, apps, com.phoenix.droidvault.models.TriggerType.MANUAL
                )
                withContext(Dispatchers.Main) {
                    if (execution == null) {
                        _uiState.update { it.copy(error = "Configuration unavailable") }
                    } else if (execution.result == com.phoenix.droidvault.models.ResultState.SKIPPED &&
                        execution.errorSummary.isNotEmpty()
                    ) {
                        _uiState.update { it.copy(error = execution.errorSummary) }
                        // A skipped run posted "started" but will never post "done".
                        notifyAutomation("Restore", execution.errorSummary, true)
                    } else if (execution.result == com.phoenix.droidvault.models.ResultState.CANCELLED) {
                        _uiState.update { it.copy(error = "Run cancelled — finished apps are kept") }
                        notifyAutomation("Restore", "Restore cancelled", true)
                    }
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Same NonCancellable contract as runConfig: in an unwinding
                // coroutine a plain withContext(Main) throws before running, and
                // a notify() outside NonCancellable can be silently skipped.
                withContext(Dispatchers.Main + kotlinx.coroutines.NonCancellable) {
                    _uiState.update { it.copy(error = "Run cancelled — finished apps are kept") }
                    notifyAutomation("Restore", "Restore cancelled", true)
                }
            } finally {
                withContext(Dispatchers.Main + kotlinx.coroutines.NonCancellable) {
                    _uiState.update {
                        it.copy(isRunning = false, runningConfigId = null, runProgress = 100)
                    }
                }
                refresh()
                // A restore changes what the backup folder offers (installs,
                // consent-gated parts) — refresh the restore screen's disk view
                // if the user is still looking at it.
                val m = _uiState.value.mode
                if (m == AutoMode.RESTORE_CONFIGS || m == AutoMode.RESTORE_PREVIEW) {
                    loadRestoreApps()
                }
            }
        }
    }

    /**
     * "Run now": fire the config behind a schedule immediately, without
     * waiting for its next slot. Uses the manual in-app path (TriggerType
     * MANUAL, live Processing screen, interactive) so the user watches it —
     * distinct from the worker's headless SCHEDULED run. The schedule itself
     * is untouched: its next scheduled firing stays armed.
     */
    fun runScheduleNow(scheduleId: String) {
        if (_uiState.value.isRunning) return
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val schedule = AutomationStore.listSchedules(app).find { it.id == scheduleId }
            if (schedule == null) {
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(error = "Schedule not found") }
                }
                return@launch
            }
            // Run-now resolves the schedule's config list at invocation time
            // (fire-time semantics): missing/disabled configs are skipped.
            val configs = AutomationStore.listConfigs(app)
                .filter { it.id in schedule.configIds && it.enabled }
            if (configs.isEmpty()) {
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(error = "Its configurations are missing or disabled") }
                }
                return@launch
            }
            val appList = try {
                MigratorManager.fetchAppsList(app, AutomationRunner.resolveSavedPath(app), "AllInstalled")
            } catch (e: Exception) {
                android.util.Log.w("AutomationViewModel", "Run now: app list fetch failed", e)
                emptyList()
            }
            // Run each config SEQUENTIALLY on the single run slot: launching
            // them in one Main pass made config #2's runJob?.cancel() abort
            // config #1 (only the LAST config actually executed). One by one,
            // waiting for isRunning to clear between them.
            for (config in configs) {
                if (_uiState.value.isRunning) {
                    // Wait for the previous run's finally{} to release the slot.
                    var waited = 0
                    while (_uiState.value.isRunning && waited < 600 * 1000) {
                        kotlinx.coroutines.delay(500)
                        waited += 500
                    }
                }
                if (_uiState.value.isRunning) break // previous run stuck: give up
                if (config.operation == ConfigOperation.RESTORE) {
                    withContext(Dispatchers.Main) { runRestoreConfig(config.id, appList) }
                } else {
                    withContext(Dispatchers.Main) { runConfig(config.id, appList) }
                }
            }
        }
    }

    /** Run-all: every enabled config that has at least one enabled schedule, in order. */
    fun runAllScheduled(apps: List<AppInfo>) {
        if (_uiState.value.isRunning) return
        runJob?.cancel()
        runJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = getApplication<Application>()
                val scheduledConfigIds = AutomationStore.listSchedules(app)
                    .filter { it.enabled }
                    .flatMap { it.configIds }
                    .distinct()
                val targets = AutomationStore.listConfigs(app)
                    .filter { it.enabled && it.id in scheduledConfigIds }
                if (targets.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(error = "No enabled schedules to run") }
                    }
                    return@launch
                }
                var ok = 0
                var failed = 0
                for (config in targets) {
                    if (!isActive) break
                    val execution = doRunConfig(
                        config.id, apps, com.phoenix.droidvault.models.TriggerType.SCHEDULED
                    )
                    if (execution == null || execution.result == com.phoenix.droidvault.models.ResultState.FAILED) {
                        failed++
                    } else {
                        ok++
                    }
                }
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(isRunning = false, runningConfigId = null, runProgress = 100)
                    }
                }
                notifyAutomation("Run all schedules", "$ok configs ok, $failed failed", true)
            } catch (_: kotlinx.coroutines.CancellationException) {
                withContext(Dispatchers.Main + kotlinx.coroutines.NonCancellable) {
                    _uiState.update { it.copy(error = "Run cancelled — finished apps are kept") }
                }
                // Replace the stuck progress notification: nothing downstream
                // will do it since the run never completed.
                notifyAutomation("Automation", "Run cancelled", true)
            } finally {
                withContext(Dispatchers.Main + kotlinx.coroutines.NonCancellable) {
                    _uiState.update {
                        it.copy(isRunning = false, runningConfigId = null, runProgress = 100)
                    }
                }
                refresh()
            }
        }
    }

    fun openHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            val items = AutomationStore.listExecutions(getApplication())
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(history = items, mode = AutoMode.HISTORY, error = null) }
            }
        }
    }

    fun loadHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            val items = AutomationStore.listExecutions(getApplication())
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(history = items) }
            }
        }
    }

    /**
     * Wipe all run history (summaries + per-app drill-down logs) after the
     * History & Transfer screen's confirm dialog. Runs recorded WHILE the
     * wipe executes keep this from being racy: listExecutions is re-read
     * under the same call, so a just-finished run that landed in prefs before
     * the clear still shows.
     */
    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            // Snapshot first so the snackbar's Undo can put everything back.
            val snapshot = AutomationStore.snapshotHistory(app)
            AutomationStore.clearHistory(app)
            val items = AutomationStore.listExecutions(app)
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        history = items,
                        // Leaving a stale drill-down open would show deleted records.
                        historyDetailId = null,
                        historyDetailApps = emptyList(),
                        historyUndoSnapshot = snapshot,
                        lastTransfer = null
                    )
                }
            }
        }
    }

    /**
     * Undo the last Clear: restores the exact pre-clear history. Runs recorded
     * between the clear and the undo are intentionally discarded (the snapshot
     * predates them) — recorded runs always keep coming, so a momentary loss
     * beats resurrecting cleared data the user asked to remove.
     */
    fun undoClearHistory() {
        val snapshot = _uiState.value.historyUndoSnapshot ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            AutomationStore.restoreHistory(app, snapshot)
            val items = AutomationStore.listExecutions(app)
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(history = items, historyUndoSnapshot = null, lastTransfer = null)
                }
            }
        }
    }

    fun consumeHistoryUndo() {
        _uiState.update { it.copy(historyUndoSnapshot = null) }
    }

    fun latestFor(configId: String, configName: String): AutomationStore.ExecutionSummary? =
        _uiState.value.history.firstOrNull {
            (it.configId.isNotEmpty() && it.configId == configId) ||
                (it.configId.isEmpty() && it.configName == configName)
        }

    /**
     * "Last backup/restore: <time> • <result>" for a config card, or null
     * when never run. Reads the persisted history so deleted configs and
     * renamed configs keep their run record.
     */
    fun lastRunLabel(configId: String, configName: String, isRestore: Boolean): String? {
        val run = latestFor(configId, configName) ?: return null
        val verb = if (isRestore) "Last restore" else "Last backup"
        val whenText = try {
            java.text.SimpleDateFormat("dd MMM, hh:mm a", java.util.Locale.getDefault())
                .format(java.util.Date(run.startedAt))
        } catch (_: Exception) {
            ""
        }
        return "$verb: $whenText • ${run.result.lowercase()}"
    }

    /** P4 drill-down: load the capped per-app log for one recorded run. */
    fun openHistoryDetail(executionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val records = AutomationStore.appRecordsFor(getApplication(), executionId)
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        historyDetailId = executionId,
                        historyDetailApps = records,
                        mode = AutoMode.HISTORY_DETAIL,
                        error = if (records.isEmpty())
                            "No per-app log kept for this run (older build or >1000 apps)"
                        else null
                    )
                }
            }
        }
    }

    fun clearHistoryDetailError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Export to the app-private cache, then share via FileProvider chooser.
     * Direct writes to shared storage (/sdcard/…) throw EPERM under scoped
     * storage — the chooser lets the user save anywhere instead.
     */
    fun exportToFile() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = getApplication<Application>()
                val json = AutomationStore.exportJson(app).toString(2)
                val dir = java.io.File(app.cacheDir, "automation-exports").apply { mkdirs() }
                // Prune older exports; the share target copies what it needs.
                dir.listFiles()?.sortedByDescending { it.lastModified() }
                    ?.drop(4)
                    ?.forEach { try { it.delete() } catch (_: Exception) {} }
                val file = java.io.File(dir, "automation-backup-${System.currentTimeMillis()}.json")
                file.writeText(json)
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    app,
                    "com.phoenix.droidvault.fileprovider",
                    file
                )
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            lastTransfer = "Export ready — pick where to save it.",
                            shareUri = uri.toString()
                        )
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(error = "Export failed: ${e.message}") }
                }
            }
        }
    }

    fun consumeShareUri() {
        _uiState.update { it.copy(shareUri = null) }
    }

    fun importFromUri(uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = getApplication<Application>()
                val text = readImportText(app, uri)
                    ?: throw IllegalArgumentException("cannot read file")
                applyImport(app, org.json.JSONObject(text))
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(error = "Import failed: ${e.message}") }
                }
            }
        }
    }

    companion object {
        const val MAX_IMPORT_BYTES = 2_000_000

        /** Bounded read: size pre-check + streaming cap (never unbounded readText). */
        fun readImportText(context: android.content.Context, uri: android.net.Uri): String? {
            val resolver = context.contentResolver
            try {
                resolver.query(
                    uri,
                    arrayOf(android.provider.OpenableColumns.SIZE),
                    null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val size = cursor.getLong(0)
                        if (size > MAX_IMPORT_BYTES) {
                            throw IllegalArgumentException("file too large (max 2 MB)")
                        }
                    }
                }
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (_: Exception) {
            }
            return resolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                val sb = StringBuilder()
                var total = 0
                val buf = CharArray(8192)
                var n: Int
                while (reader.read(buf).also { n = it } != -1) {
                    total += n
                    if (total > MAX_IMPORT_BYTES) {
                        throw IllegalArgumentException("file too large (max 2 MB)")
                    }
                    sb.append(buf, 0, n)
                }
                sb.toString()
            }
        }
    }

    private suspend fun applyImport(app: Application, json: org.json.JSONObject) {
        val res = AutomationStore.importJson(app, json)
        withContext(Dispatchers.Main) {
            res.onSuccess { summary ->
                refresh()
                _uiState.update { it.copy(lastTransfer = summary.describe()) }
            }.onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
        if (res.isSuccess) {
            // Mutations already keep the primary state file current; this is
            // just a belt-and-braces sync after bulk importJson writes.
            AutomationStore.syncPrimaryStateFile(app)
        }
    }

    /**
     * Saved vault path. Single source here; [AutomationRunner.resolveSavedPath]
     * reads the same prefs for the engine and the restore list — keep them
     * identical if either changes.
     */
    private fun savedPathOf(app: Application): String {
        val prefs = app.getSharedPreferences("droidvault_prefs", android.content.Context.MODE_PRIVATE)
        return prefs.getString(
            "base_path",
            com.phoenix.droidvault.utils.SettingsManager.getDefaultPath()
        ) ?: com.phoenix.droidvault.utils.SettingsManager.getDefaultPath()
    }    /**
     * Fresh-install recovery: offer the folder's automation state backup when
     * the local store is empty AND the saved document actually holds rows.
     * The decision (row-count gate + per-file skip memory) lives in
     * [AutomationSnapshot.shouldOfferRestore]; the skip marker is keyed to
     * the file's path+mtime so a NEW backup after the skip re-offers.
     */
    fun checkStateBackup() {
        viewModelScope.launch(Dispatchers.IO) {
            // viewModelScope is a SupervisorJob, so an exception here is NOT
            // absorbed by a sibling — it reaches the default handler and kills
            // the process. This parses automation-state.json straight out of
            // the user's vault folder, so a truncated file (interrupted write,
            // full disk, ROM flash mid-run) threw JSONException and crashed the
            // app on every entry to the Automation tab. Fail closed instead:
            // no offer, no crash.
            try {
                val app = getApplication<Application>()
                val path = savedPathOf(app)
                val hasData = AutomationStore.listLabels(app).isNotEmpty() ||
                    AutomationStore.listConfigs(app).isNotEmpty() ||
                    AutomationStore.listSchedules(app).isNotEmpty()
                val info = if (hasData) null else AutomationSnapshot.readStateBackup(app, path)
                val fileRows = if (info == null) 0 else AutomationStore.stateFileRowCount(path)
                val prefs = app.getSharedPreferences("droidvault_prefs", android.content.Context.MODE_PRIVATE)
                val declinedKey = "state_offer_declined"
                val declined = prefs.getString(declinedKey, null)
                val offer = info != null && AutomationSnapshot.shouldOfferRestore(
                    fileRows = fileRows,
                    localHasData = hasData,
                    alreadyDeclined = declined != null && declined == "${info.path}|${info.modifiedAt}"
                )
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(foundStateBackup = if (offer) info else null) }
                }
            } catch (e: Exception) {
                android.util.Log.w("AutomationViewModel", "state backup check failed", e)
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(foundStateBackup = null) }
                }
            }
        }
    }

    /**
     * Skip is remembered per backup file (path + mtime): the dialog never
     * nags again for the SAME backup, but a newly written backup re-offers.
     */
    fun dismissStateBackup() {
        val info = _uiState.value.foundStateBackup ?: return
        getApplication<Application>().getSharedPreferences("droidvault_prefs", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("state_offer_declined", "${info.path}|${info.modifiedAt}")
            .apply()
        _uiState.update { it.copy(foundStateBackup = null) }
    }

    fun restoreStateBackup() {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val json = AutomationSnapshot.loadStateBackup(app, savedPathOf(app))
            if (json == null) {
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(error = "Could not read automation backup", foundStateBackup = null)
                    }
                }
                return@launch
            }
            applyImport(app, json)
            withContext(Dispatchers.Main) {
                // A successful restore is a YES for this file — clear any
                // stale skip marker so its identity is no longer declined.
                app.getSharedPreferences("droidvault_prefs", android.content.Context.MODE_PRIVATE)
                    .edit().remove("state_offer_declined").apply()
                _uiState.update { it.copy(foundStateBackup = null) }
            }
        }
    }

    fun clearTransfer() {
        _uiState.update { it.copy(lastTransfer = null) }
    }

    // ---------- schedules ----------

    fun newSchedule(configIds: List<String>) {
        _uiState.update {
            it.copy(
                editingSchedule = AutomationSchedule(
                    id = AutomationStore.newId(),
                    configIds = configIds
                ),
                error = null
            )
        }
    }

    fun editSchedule(id: String) {
        val s = _uiState.value.schedules.find { it.id == id } ?: return
        _uiState.update { it.copy(editingSchedule = s, error = null) }
    }

    fun cancelEditingSchedule() {
        _uiState.update { it.copy(editingSchedule = null, error = null) }
    }

    fun updateEditingSchedule(s: AutomationSchedule) {
        _uiState.update { it.copy(editingSchedule = s) }
    }

    fun scheduleErrors(): List<String> {
        val s = _uiState.value.editingSchedule ?: return listOf("nothing to save")
        return validateAutomationSchedule(s, _uiState.value.configs.map { it.id }.toSet())
    }

    fun saveEditingSchedule() {
        val s = _uiState.value.editingSchedule ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val res = AutomationStore.saveSchedule(getApplication(), s)
            withContext(Dispatchers.Main) {
                res.onSuccess {
                    _uiState.update { it.copy(editingSchedule = null) }
                    refresh()
                }.onFailure { e -> _uiState.update { it.copy(error = e.message) } }
            }
        }
    }

    fun deleteSchedule(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            AutomationStore.deleteSchedule(getApplication(), id)
            refresh()
        }
    }

    fun setScheduleEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            AutomationStore.setScheduleEnabled(getApplication(), id, enabled)
            refresh()
        }
    }

    fun setMasterEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            AutomationStore.setMasterEnabled(getApplication(), enabled)
            refresh()
        }
    }

    // ---------- app detail ----------

    fun openDetail(pkg: String, apps: List<AppInfo>, from: AutoMode) {
        val app = apps.find { it.packageName == pkg } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(detailPkg = pkg, detailReturn = from, detail = null, detailLoading = true, mode = AutoMode.APP_DETAIL)
                }
            }
            val savedPath = savedPathOf(getApplication())
            val detail = AppDetailLoader.load(getApplication(), app, savedPath)
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(detail = detail, detailLoading = false) }
            }
        }
    }

    fun reloadDetail(apps: List<AppInfo>) {
        val pkg = _uiState.value.detailPkg ?: return
        val from = _uiState.value.detailReturn
        openDetail(pkg, apps, from)
    }

    fun toggleFreeze(pkg: String, apps: List<AppInfo>) {
        viewModelScope.launch(Dispatchers.IO) {
            val frozen = ToolsManager.isFrozen(getApplication(), pkg)
            ToolsManager.setFrozen(pkg, !frozen)
            withContext(Dispatchers.Main) {
                reloadDetail(apps)
            }
        }
    }

    fun launchApp(context: android.content.Context, pkg: String) {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        } catch (_: Exception) {
        }
    }

    private fun notifyAutomation(title: String, text: String, done: Boolean) {
        notifyAutomation(title, text, done, null)
    }

    /**
     * Operation-aware notification. The progress channel changes color with
     * the operation (Backup = primary, Restore = secondary); cancel/title
     * messaging is neutral so backup and restore runs share one machinery.
     */
    private fun notifyAutomation(
        title: String,
        text: String,
        done: Boolean,
        operation: com.phoenix.droidvault.models.ConfigOperation?
    ) {
        if (done) {
            com.phoenix.droidvault.utils.AutomationNotifications.notifyDone(
                getApplication(), title, text
            )
        } else {
            com.phoenix.droidvault.utils.AutomationNotifications.notifyProgress(
                getApplication(), title, text,
                operation ?: com.phoenix.droidvault.models.ConfigOperation.BACKUP
            )
        }
    }
}
