package com.phoenix.droidvault.utils

import android.content.Context
import androidx.core.content.edit
import com.phoenix.droidvault.models.AppHistoryRecord
import com.phoenix.droidvault.models.AppLabel
import com.phoenix.droidvault.models.AutomationSchedule
import com.phoenix.droidvault.models.BackupConfig
import com.phoenix.droidvault.models.CONFIG_LIST_ORDER_DEFAULT
import com.phoenix.droidvault.models.ConfigExecution
import com.phoenix.droidvault.models.ConfigRule
import com.phoenix.droidvault.models.DEST_DEFAULT_ID
import com.phoenix.droidvault.models.LabelAssignment
import com.phoenix.droidvault.models.validateAutomationSchedule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Automation state store: automation-state.json in the Droid Vault folder (or the
 * user-selected custom folder) is the PRIMARY storage for labels, assignments,
 * configs, schedules and the master toggle. SharedPreferences
 * (`droidvault_automation_prefs`) is only a best-effort CACHE/mirror:
 *
 *  - Every read resolves the section from the primary file when it exists and
 *    is parseable, and falls back to prefs only when the file is missing,
 *    unreadable, or lacks that key (partial write recovery).
 *  - Every mutation swaps the prefs cache first (durable commit), then
 *    rewrites the whole primary file atomically (tmp + mv via root shell).
 *  - Consequence: clearing app data wipes only the cache — the primary file
 *    survives and repopulates the store on next bind ([autoImportStateFromFile]
 *    / [migrateCacheToPrimary]).
 *
 * History, per-run markers and completion epochs remain prefs-only on purpose:
 * they are device telemetry / run bookkeeping, not configuration. No Room
 * (KSP removed); the dataset is tiny and validation mirrors spec §30.
 */
object AutomationStore {
    const val DEST_DEFAULT = DEST_DEFAULT_ID
    const val SCHEMA_VERSION = 2

    private const val PREFS = "droidvault_automation_prefs"
    private const val K_LABELS = "labels_v1"
    private const val K_ASSIGN = "assignments_v1"
    private const val K_CONFIGS = "configs_v1"
    private const val K_HISTORY = "history_v1"
    private const val K_SCHEDULES = "schedules_v2"

    // ---------- primary state file (automation-state.json) ----------
    // Top-level keys deliberately match the v2 export shape (exportJson /
    // AutomationSnapshot.snapshotState) so any writer produces a document the
    // loader accepts, and the ROM-survival snapshot stays interchangeable
    // with the live primary store.
    const val KEY_SCHEMA_VERSION = "schemaVersion"
    const val KEY_LABELS = "labels"
    const val KEY_ASSIGNMENTS = "assignments"
    const val KEY_CONFIGS = "configurations"
    const val KEY_SCHEDULES = "schedules"
    const val KEY_MASTER = "masterEnabled"
    /** Monotonic write counter; detected changes invalidate the read cache. */
    const val KEY_STORE_REV = "storeRev"

    private val STATE_FILE_LOCK = Any()

    /** Where the folder path lives ("droidvault_prefs" — NOT the automation prefs file). */
    private const val BASE_PATH_PREFS = "droidvault_prefs"
    private const val BASE_PATH_KEY = "base_path"

    /** @return true on success; false = failed (prefs cache stays authoritative). */
    fun writeStateFileBlocking(context: Context, folder: String, json: String): Boolean {
        return try {
            AutomationSnapshot.writeStateFileBlocking(context, folder, json)
        } catch (_: Exception) {
            false
        }
    }

    /** @return raw file content, or null when missing/unreadable/invalid path. */
    fun readStateFileBlocking(folder: String): String? {
        return try {
            AutomationSnapshot.readStateFileBlocking(folder)
        } catch (_: Exception) {
            null
        }
    }

    /** Parsed sections of the primary file; null members mean the key is absent. */
    data class ParsedState(
        val rev: Long,
        val labels: List<JSONObject>?,
        val assignments: List<JSONObject>?,
        val configs: List<JSONObject>?,
        val schedules: List<JSONObject>?,
        val masterEnabled: Boolean?
    )

    /** Pure parse (unit-testable): null when the text is not a valid state file. */
    fun parseStateFile(text: String?): ParsedState? {
        if (text.isNullOrBlank()) return null
        return try {
            val o = JSONObject(text)
            val version = o.optInt(KEY_SCHEMA_VERSION, 0)
            if (version < 1 || version > SCHEMA_VERSION) return null
            ParsedState(
                rev = o.optLong(KEY_STORE_REV, 0L),
                labels = jsonArrayToList(o.optJSONArray(KEY_LABELS)).ifEmpty { null },
                assignments = jsonArrayToList(o.optJSONArray(KEY_ASSIGNMENTS)).ifEmpty { null },
                configs = jsonArrayToList(o.optJSONArray(KEY_CONFIGS)).ifEmpty { null },
                schedules = jsonArrayToList(o.optJSONArray(KEY_SCHEDULES)).ifEmpty { null },
                masterEnabled = if (o.has(KEY_MASTER)) o.optBoolean(KEY_MASTER, true) else null
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun jsonArrayToList(arr: JSONArray?): List<JSONObject> {
        if (arr == null) return emptyList()
        return try {
            (0 until arr.length()).mapNotNull { i ->
                try {
                    arr.getJSONObject(i)
                } catch (_: Exception) {
                    null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ---------- labels ----------

    /** Pure prefs-section parser (unit-testable): tolerant of corrupt rows. */
    fun parsePrefArray(raw: String?): List<JSONObject> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                try {
                    arr.getJSONObject(i)
                } catch (_: Exception) {
                    null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun prefRaw(context: Context, key: String): String? =
        try {
            prefs(context).getString(key, null)
        } catch (_: Exception) {
            null
        }

    fun listLabels(context: Context): List<AppLabel> =
        resolveSection(
            prefJson = prefRaw(context, K_LABELS),
            primaryObjs = loadPrimary(folderPath(context))?.labels,
            fromPrimary = AutomationCodec::labelFrom,
            parsePref = { raw -> parsePrefArray(raw).map(AutomationCodec::labelFrom) }
        ).sortedBy { it.name.lowercase() }

    /**
     * Structural characters banned in label names: labels become directories
     * in the vault folder ("$BACKUP_BASE/$type/$label") and fields in the
     * engine's pipe-delimited TARGETS file. '/' would traverse outside the
     * vault, '|' would shift the TARGETS parse, newlines would inject rows.
     */
    private val LABEL_FORBIDDEN = Regex("[/|\\r\\n\\t]")

    /** Returns null + reason when invalid; use [lastError] pattern via Result. */
    fun createLabel(context: Context, name: String, description: String = ""): Result<AppLabel> {
        val clean = name.trim()
        if (clean.isEmpty()) return Result.failure(IllegalArgumentException("empty name"))
        if (clean.length > 64) return Result.failure(IllegalArgumentException("name too long"))
        if (LABEL_FORBIDDEN.containsMatchIn(clean)) {
            return Result.failure(IllegalArgumentException("name cannot contain / | or line breaks"))
        }
        if (clean.all { it == '.' }) return Result.failure(IllegalArgumentException("invalid name"))
        if (listLabels(context).any { it.name.equals(clean, ignoreCase = true) }) {
            return Result.failure(IllegalArgumentException("duplicate name"))
        }
        val label = AppLabel(newId(), clean, description.trim().take(256))
        persistLabels(context, listLabels(context) + label)
        return Result.success(label)
    }

    fun renameLabel(context: Context, id: String, newName: String): Result<AppLabel> {
        val clean = newName.trim()
        if (clean.isEmpty()) return Result.failure(IllegalArgumentException("empty name"))
        val labels = listLabels(context)
        if (labels.any { it.id != id && it.name.equals(clean, ignoreCase = true) }) {
            return Result.failure(IllegalArgumentException("duplicate name"))
        }
        val current = labels.find { it.id == id }
            ?: return Result.failure(IllegalArgumentException("unknown label"))
        val updated = current.copy(name = clean, updatedAt = System.currentTimeMillis())
        persistLabels(context, labels.map { if (it.id == id) updated else it })
        // Rules join on labelId (stable here), but their embedded labelName
        // rows must follow the rename — persistConfigs re-embeds names from
        // the live label list.
        persistConfigs(context, listConfigs(context))
        return Result.success(updated)
    }

    /**
     * Self-heal for rule→label references that no longer resolve — the
     * field-observed reinstall bug ("rule 1: unknown label" on the config
     * editor): rules carry the label UUID of the previous install, while the
     * labels come back from the ROM-survival state file (or an import) with
     * fresh UUIDs. Every persisted rule row embeds its label's name (see
     * [persistConfigs]), so the repair is an EXACT by-name remap — no
     * placeholders, no guessing. A healthy store reads two lists and writes
     * nothing.
     *
     * Runs on every store re-bind — AutomationViewModel.refresh() and
     * [autoImportStateFromFile] step 7 — so folder changes and imports heal
     * too. Pure core in [relinkRulesToLabels] (unit-tested).
     */
    fun relinkDanglingRules(context: Context) {
        val labels = listLabels(context)
        if (labels.isEmpty()) return
        val configs = listConfigs(context)
        var changed = false
        val relinked = configs.map { cfg ->
            val next = relinkRulesToLabels(cfg.rules, labels)
            if (next !== cfg.rules) {
                changed = true
                cfg.copy(rules = next, updatedAt = System.currentTimeMillis())
            } else cfg
        }
        if (changed) persistConfigs(context, relinked)
        // Assignments need the same heal: their rows carry the label's CURRENT
        // name, but the labelId is a local UUID a reinstall re-issues. Without
        // this, backfilled assignments point at dead UUIDs and silently stop
        // matching rules — a restore run then reports "nothing to process"
        // even though labels, configs and backups are all present (observed
        // in the field after uninstall→reinstall on a kept vault folder).
        val assignments = listAssignments(context)
        val relinkedAssignments = relinkAssignmentsToLabels(assignments, labels)
        if (relinkedAssignments !== assignments) {
            persistAssignments(context, relinkedAssignments)
        }
    }

    /**
     * Pure relink core for assignment rows (same contract as
     * [relinkRulesToLabels]): remap rows whose labelId is unknown onto
     * [labels] by recorded name (case-insensitive, first match wins); rows
     * that already resolve — and dangling rows with no name hint — pass
     * through untouched. Returns the SAME list instance when nothing changed.
     */
    fun relinkAssignmentsToLabels(
        assignments: List<LabelAssignment>,
        labels: List<AppLabel>
    ): List<LabelAssignment> {
        if (assignments.isEmpty() || labels.isEmpty()) return assignments
        val knownIds = labels.map { it.id }.toSet()
        val byName = HashMap<String, String>()
        labels.forEach { byName.putIfAbsent(it.name.lowercase().trim(), it.id) }
        var changed = false
        val next = assignments.map { a ->
            if (a.labelId in knownIds) return@map a
            val resolved = byName[a.labelName.trim().lowercase()]
            if (resolved != null) {
                changed = true
                a.copy(labelId = resolved)
            } else a
        }
        return if (changed) next else assignments
    }

    /**
     * Pure relink core (unit-testable): remap rules whose labelId is unknown
     * onto [labels] by recorded name (case-insensitive, first match wins).
     * Rules that already resolve — and dangling rules with no name hint —
     * pass through untouched. Returns the SAME list instance when nothing
     * changed (cheap caller check).
     */
    fun relinkRulesToLabels(
        rules: List<ConfigRule>,
        labels: List<AppLabel>
    ): List<ConfigRule> {
        if (rules.isEmpty() || labels.isEmpty()) return rules
        val knownIds = labels.map { it.id }.toSet()
        val byName = HashMap<String, String>()
        labels.forEach { byName.putIfAbsent(it.name.lowercase().trim(), it.id) }
        var changed = false
        val next = rules.map { r ->
            if (r.labelId in knownIds) return@map r
            val resolved = byName[r.labelName.trim().lowercase()]
            if (resolved != null) {
                changed = true
                r.copy(labelId = resolved)
            } else r
        }
        return if (changed) next else rules
    }

    fun deleteLabel(context: Context, id: String) {
        persistLabels(context, listLabels(context).filter { it.id != id })
        // Cascade: assignments + rules referencing the label.
        persistAssignments(context, listAssignments(context).filter { it.labelId != id })
        persistConfigs(
            context,
            listConfigs(context).map { cfg ->
                cfg.copy(
                    rules = cfg.rules.filter { it.labelId != id },
                    updatedAt = System.currentTimeMillis()
                )
            }
        )
    }

    // ---------- assignments ----------

    fun listAssignments(context: Context): List<LabelAssignment> =
        resolveSection(
            prefJson = prefRaw(context, K_ASSIGN),
            primaryObjs = loadPrimary(folderPath(context))?.assignments,
            fromPrimary = AutomationCodec::assignmentFrom,
            parsePref = { raw -> parsePrefArray(raw).map(AutomationCodec::assignmentFrom) }
        )

    fun labelsFor(context: Context, packageName: String): Set<String> =
        listAssignments(context).filter { it.packageName == packageName }.map { it.labelId }.toSet()

    fun assign(context: Context, packageName: String, labelId: String): Result<Unit> {
        ShellQuote.requirePackage(packageName)
        if (listLabels(context).none { it.id == labelId }) {
            return Result.failure(IllegalArgumentException("unknown label"))
        }
        val current = listAssignments(context)
        if (current.any { it.packageName == packageName && it.labelId == labelId }) {
            return Result.success(Unit)
        }
        persistAssignments(context, current + LabelAssignment(packageName, labelId))
        return Result.success(Unit)
    }

    fun unassign(context: Context, packageName: String, labelId: String) {
        persistAssignments(
            context,
            listAssignments(context).filter { !(it.packageName == packageName && it.labelId == labelId) }
        )
    }

    /**
     * Assign many packages in ONE read + ONE persist.
     *
     * `bulkAssign` used to call [assign] per package, and each of those read the
     * whole assignment list and rewrote the whole primary state file through a
     * root-shell round trip — N full re-serializations of a list that can hold
     * tens of thousands of rows. The single-row [assign] is still correct on its
     * own; it is only the bulk path that needs to resolve the set first, which
     * is exactly what the import path already does.
     */
    fun bulkAssign(context: Context, packageNames: List<String>, labelId: String): Int {
        if (listLabels(context).none { it.id == labelId }) {
            throw IllegalArgumentException("unknown label")
        }
        val targets = packageNames.distinct()
        val current = listAssignments(context)
        val existing = current.filter { it.labelId == labelId }.map { it.packageName }.toHashSet()
        val additions = targets.filterNot { it in existing }
            .map { LabelAssignment(it, labelId) }
        if (additions.isEmpty()) return 0
        // Preserve the caller's per-package validation: a bad package name must
        // still fail loudly rather than reach the persisted file.
        for (p in targets) ShellQuote.requirePackage(p)
        persistAssignments(context, current + additions)
        return additions.size
    }

    /** Remove many packages from one label in a single read + persist. */
    fun bulkUnassign(context: Context, packageNames: List<String>, labelId: String): Int {
        val targets = packageNames.toHashSet()
        val current = listAssignments(context)
        val kept = current.filterNot { it.packageName in targets && it.labelId == labelId }
        val removed = current.size - kept.size
        if (removed == 0) return 0
        persistAssignments(context, kept)
        return removed
    }

    fun appsForLabel(context: Context, labelId: String): List<String> =
        listAssignments(context).filter { it.labelId == labelId }.map { it.packageName }.distinct().sorted()

    // ---------- configs ----------

    /**
     * Ordered by the user's arrangement ([BackupConfig.listOrder]); entries
     * never arranged sort alphabetically after the ordered ones — so the
     * list is stable and pre-existing data keeps its familiar order.
     */
    fun listConfigs(context: Context): List<BackupConfig> =
        resolveSection(
            prefJson = prefRaw(context, K_CONFIGS),
            primaryObjs = loadPrimary(folderPath(context))?.configs,
            fromPrimary = AutomationCodec::configFrom,
            parsePref = { raw -> parsePrefArray(raw).map(AutomationCodec::configFrom) }
        ).sortedWith(
            compareBy({ it.listOrder }, { it.name.lowercase() })
        )

    fun validateConfig(context: Context, config: BackupConfig): List<String> =
        validateAutomationConfig(config, listLabels(context).map { it.id }.toSet())

    fun saveConfig(context: Context, config: BackupConfig): Result<BackupConfig> {
        if (config.name.isBlank()) return Result.failure(IllegalArgumentException("name required"))
        val errors = validateConfig(context, config)
        if (errors.isNotEmpty()) return Result.failure(IllegalArgumentException(errors.joinToString("; ")))
        val normalized = config.copy(
            rules = config.rules.sortedWith(compareBy({ it.priority }, { it.id })),
            updatedAt = System.currentTimeMillis()
        )
        val current = listConfigs(context)
        persistConfigs(
            context,
            if (current.any { it.id == normalized.id }) {
                current.map { if (it.id == normalized.id) normalized else it }
            } else {
                current + normalized
            }
        )
        return Result.success(normalized)
    }

    fun duplicateConfig(context: Context, id: String, newName: String): Result<BackupConfig> {
        val src = listConfigs(context).find { it.id == id }
            ?: return Result.failure(IllegalArgumentException("unknown config"))
        // Deep copy: brand-new IDs so rule objects are never shared.
        val copy = src.copy(
            id = newId(),
            name = newName.trim().ifEmpty { "${src.name} copy" },
            rules = src.rules.map { it.copy(id = newId()) },
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            // A copy starts unarranged → sorts alphabetically at the bottom,
            // instead of colliding with its source's position.
            listOrder = CONFIG_LIST_ORDER_DEFAULT
        )
        return saveConfig(context, copy)
    }

    fun deleteConfig(context: Context, id: String) {
        persistConfigs(context, listConfigs(context).filter { it.id != id })
        // Multi-config schedules: PRUNE the deleted config from each schedule
        // instead of deleting it outright — a schedule with other configs keeps
        // running the survivors (fire-time resolution: "run what is still
        // available"). Only a schedule left with zero configs is removed
        // (cancel its WorkManager job so the Schedules screen never shows
        // ghosts).
        val affected = listSchedules(context).filter { id in it.configIds }
        if (affected.isEmpty()) return
        val removeIds = mutableSetOf<String>()
        val updated = mutableListOf<AutomationSchedule>()
        affected.forEach { s ->
            val remaining = s.configIds - id
            if (remaining.isEmpty()) removeIds += s.id else updated += s.copy(configIds = remaining)
        }
        var schedules = listSchedules(context)
        if (updated.isNotEmpty()) {
            schedules = schedules.map { s -> updated.find { it.id == s.id } ?: s }
            persistSchedules(context, schedules)
            updated.forEach { AutomationScheduler.reschedule(context, it) }
        }
        if (removeIds.isNotEmpty()) {
            removeIds.forEach { AutomationScheduler.cancel(context, it) }
            persistSchedules(context, listSchedules(context).filter { it.id !in removeIds })
        }
    }

    /**
     * Sync hook for Settings-side folder removals: drop [path] from EVERY
     * persisted config's folderPaths (backup + restore) and re-write the
     * primary file. Keeps the invariant "config selections ⊆ shared registry"
     * at the source, so a folder deleted in Settings disappears from every
     * editor immediately and can never re-offer a dead path.
     * Returns the number of configs that referenced the path.
     */
    fun pruneRemovedBackupFolder(context: Context, path: String): Int {
        val configs = listConfigs(context)
        val affected = configs.count { path in it.folderPaths }
        if (affected == 0) return 0
        // Null-safe (audit A-28): a folder could be removed from the store
        // between the count above and this prune.
        val pruned = pruneConfigsFolder(configs, path) ?: return 0
        persistConfigs(context, pruned)
        return affected
    }

    /**
     * Move [id] one slot up or down within its operation group (Backup and
     * Restore lists order independently). At the list edge this is a no-op.
     * The whole same-operation group is renumbered 1..n in the new order:
     * idempotent, always consistent, and untouched groups (the other
     * operation) keep their alphabetical/DEFAULT order. Timestamps are left
     * alone so "last run/save" stays honest.
     */
    fun moveConfig(context: Context, id: String, up: Boolean): Result<Unit> {
        val next = reorderedConfigs(listConfigs(context), id, up)
            ?: return Result.failure(IllegalArgumentException("unknown config"))
        persistConfigs(context, next)
        return Result.success(Unit)
    }

    fun setConfigEnabled(context: Context, id: String, enabled: Boolean) {
        persistConfigs(
            context,
            listConfigs(context).map {
                if (it.id == id) it.copy(enabled = enabled, updatedAt = System.currentTimeMillis()) else it
            }
        )
    }

    // ---------- schedules ----------

    fun listSchedules(context: Context): List<AutomationSchedule> =
        resolveSection(
            prefJson = prefRaw(context, K_SCHEDULES),
            primaryObjs = loadPrimary(folderPath(context))?.schedules,
            fromPrimary = AutomationCodec::scheduleFrom,
            parsePref = { raw -> parsePrefArray(raw).map(AutomationCodec::scheduleFrom) }
        ).sortedBy { it.configIds.firstOrNull() ?: "" }

    fun saveSchedule(context: Context, schedule: AutomationSchedule): Result<AutomationSchedule> {
        val errors = validateAutomationSchedule(schedule, listConfigs(context).map { it.id }.toSet())
        if (errors.isNotEmpty()) return Result.failure(IllegalArgumentException(errors.joinToString("; ")))
        val normalized = schedule.copy(updatedAt = System.currentTimeMillis())
        val current = listSchedules(context)
        persistSchedules(
            context,
            if (current.any { it.id == normalized.id }) {
                current.map { if (it.id == normalized.id) normalized else it }
            } else {
                current + normalized
            }
        )
        AutomationScheduler.reschedule(context, normalized)
        return Result.success(normalized)
    }

    fun deleteSchedule(context: Context, id: String) {
        AutomationScheduler.cancel(context, id)
        persistSchedules(context, listSchedules(context).filter { it.id != id })
    }

    fun setScheduleEnabled(context: Context, id: String, enabled: Boolean) {
        val current = listSchedules(context).find { it.id == id } ?: return
        saveSchedule(context, current.copy(enabled = enabled))
    }

    /**
     * Persist enabled=false WITHOUT touching the scheduler. Used by the worker
     * after a ONCE schedule fires: the normal toggle goes through
     * [AutomationScheduler.reschedule], whose first act is cancelUniqueWork on
     * the request that is currently RUNNING — WorkManager would stop this very
     * worker and turn a completed run into a phantom "Cancelled" record. A
     * finished ONCE request is discarded by WorkManager on its own, so nothing
     * needs cancelling; the disabled flag just keeps rescheduleAll from
     * re-arming a spent schedule.
     */
    fun disableSchedule(context: Context, id: String) {
        val current = listSchedules(context)
        if (current.none { it.id == id }) return
        persistSchedules(
            context,
            current.map {
                if (it.id == id) it.copy(enabled = false, updatedAt = System.currentTimeMillis()) else it
            }
        )
    }

    // ---------- durable dedup marker ----------
    // The kill at role release can land ~100 ms after finishExecution's
    // apply() — observed in the field as a record lost despite
    // record-before-handback. The history write is therefore forced to disk
    // (commit, not apply) before the release, and a durable per-config
    // completion epoch lets a queued sibling run skip work that already
    // completed (schedule ids are unique, so per-schedule markers cannot see
    // across schedules).
    private const val K_CONFIG_DONE = "config_done_"

    fun markConfigCompleted(context: Context, configId: String, atMillis: Long) {
        try {
            prefs(context).edit {
                putLong("$K_CONFIG_DONE$configId", atMillis)
                commit()
            }
        } catch (_: Exception) {
        }
    }

    /** Completion epoch of the config's last run (0 = never). */
    fun configCompletedAt(context: Context, configId: String): Long =
        try {
            prefs(context).getLong("$K_CONFIG_DONE$configId", 0L)
        } catch (_: Exception) {
            0L
        }

    /** Removes the per-schedule spent marker once its run truly completed. */
    fun clearSpent(context: Context, id: String) {
        try {
            prefs(context).edit { remove("$K_SPENT_PREFIX$id") }
        } catch (_: Exception) {
        }
    }
    // A one-shot is disabled the moment it FIRES (see ConfigRunWorker). If the
    // process then dies mid-run, WorkManager retries into the enabled=false
    // gate and exits silently — the run is lost with no history. The spent
    // marker lets the worker's own retry still execute, while every other
    // path (user toggle, UI edits) keeps the gate.
    private const val K_SPENT_PREFIX = "spent_once_"

    // ---------- per-work-unit run epoch (audit A-04) ----------
    // A worker retry after a process death must finish only the configs that
    // did NOT complete in the earlier attempt. The epoch captured when the
    // work unit first started is reused by its retries; each config's durable
    // completion timestamp is compared against it. Unlike the 60 s
    // suppression window, this survives exponential backoff retries that land
    // minutes or hours later.
    private const val K_RUN_EPOCH_PREFIX = "run_epoch_"

    /**
     * Captures the epoch of this work unit ([newEpoch] = first attempt) or
     * reuses the one captured by the earlier attempt of the same unit.
     */
    fun runEpoch(context: Context, scheduleId: String, newEpoch: Boolean): Long {
        val key = "$K_RUN_EPOCH_PREFIX$scheduleId"
        return try {
            val p = prefs(context)
            if (newEpoch || !p.contains(key)) {
                val now = System.currentTimeMillis()
                p.edit {
                    putLong(key, now)
                    commit()
                }
                now
            } else {
                p.getLong(key, 0L).takeIf { it > 0 } ?: System.currentTimeMillis()
            }
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }

    /** Retires the epoch once the whole work unit completed (success or stop). */
    fun clearRunEpoch(context: Context, scheduleId: String) {
        try {
            prefs(context).edit { remove("$K_RUN_EPOCH_PREFIX$scheduleId") }
        } catch (_: Exception) {
        }
    }

    fun markSpent(context: Context, id: String) {
        try {
            prefs(context).edit {
                putBoolean("$K_SPENT_PREFIX$id", true)
                // Garbage-collect markers of schedules that no longer exist.
                val live = listSchedules(context).map { it.id }.toSet()
                prefs(context).all.keys
                    .filter { it.startsWith(K_SPENT_PREFIX) && it.removePrefix(K_SPENT_PREFIX) !in live }
                    .forEach { remove(it) }
            }
        } catch (_: Exception) {
        }
    }

    fun isSpent(context: Context, id: String): Boolean =
        try {
            prefs(context).getBoolean("$K_SPENT_PREFIX$id", false)
        } catch (_: Exception) {
            false
        }

    // ---------- per-schedule completion markers ----------
    // A spent one-shot whose first attempt died mid-run is completed by its
    // WorkManager retry; a concurrent sibling worker (another schedule for the
    // same config, or a retry that raced a manual run) must NOT execute the
    // same work twice — double restores duplicate messages/call logs.
    private const val K_DONE_PREFIX = "done_once_"

    fun markScheduleCompleted(context: Context, id: String) {
        try {
            prefs(context).edit {
                putLong("$K_DONE_PREFIX$id", System.currentTimeMillis())
                val live = listSchedules(context).map { it.id }.toSet()
                prefs(context).all.keys
                    .filter { it.startsWith(K_DONE_PREFIX) && it.removePrefix(K_DONE_PREFIX) !in live }
                    .forEach { remove(it) }
                commit()
            }
        } catch (_: Exception) {
        }
    }

    /** True when this schedule already completed a run at or after [sinceMillis]. */
    fun isScheduleCompletedSince(context: Context, id: String, sinceMillis: Long): Boolean =
        try {
            prefs(context).getLong("$K_DONE_PREFIX$id", 0L) >= sinceMillis
        } catch (_: Exception) {
            false
        }

    // ---------- history (capped at 50 executions) ----------

    data class ExecutionSummary(
        val id: String,
        val configId: String,
        val configName: String,
        val trigger: String,
        val startedAt: Long,
        val result: String,
        val success: Int,
        val failed: Int,
        val skipped: Int,
        val errorSummary: String,
        /** BACKUP for pre-operation entries (older builds stored no op). */
        val operation: String = "BACKUP"
    )

    fun listExecutions(context: Context): List<ExecutionSummary> =
        readArray(context, K_HISTORY).mapNotNull {
            try {
                ExecutionSummary(
                    id = it.optString("id"),
                    // Pre-configId entries match by name via latestExecutionFor fallback.
                    configId = it.optString("configId", ""),
                    configName = it.optString("configName"),
                    trigger = it.optString("trigger", "MANUAL"),
                    startedAt = it.optLong("startedAt", 0L),
                    result = it.optString("result", "SUCCESS"),
                    success = it.optInt("success", 0),
                    failed = it.optInt("failed", 0),
                    skipped = it.optInt("skipped", 0),
                    errorSummary = it.optString("errorSummary", ""),
                    operation = it.optString("operation", "BACKUP")
                )
            } catch (_: Exception) {
                null
            }
        }

    /** Latest run for a config (id match, name fallback for pre-configId entries). */
    fun latestExecutionFor(context: Context, configId: String, configName: String): ExecutionSummary? =
        listExecutions(context).firstOrNull {
            (it.configId.isNotEmpty() && it.configId == configId) ||
                (it.configId.isEmpty() && it.configName == configName)
        }

    private const val K_MASTER = "schedules_master_enabled"

    fun isMasterEnabled(context: Context): Boolean =
        resolveMaster(
            prefEnabled = prefs(context).getBoolean(K_MASTER, true),
            primaryMaster = loadPrimary(folderPath(context))?.masterEnabled
        )

    fun setMasterEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(K_MASTER, enabled) }
        // Primary file must mirror the toggle (it survives app-data clears).
        try {
            writePrimaryStateFile(context)
        } catch (_: Exception) {
        }
        if (enabled) {
            AutomationScheduler.rescheduleAll(context)
        } else {
            AutomationScheduler.cancelAll(context)
        }
    }

    fun recordExecution(context: Context, execution: ConfigExecution) {
        val arr = readArray(context, K_HISTORY).toMutableList()
        arr.add(0, executionTo(execution))
        while (arr.size > 50) arr.removeAt(arr.size - 1)
        // Durability: this is the record-before-handback contract — the SMS
        // role release can SIGKILL us ~100 ms after this call, losing an
        // async apply(). commit() makes the record survive.
        prefs(context).edit {
            putString(K_HISTORY, JSONArray(arr).toString())
            commit()
        }
        // Per-app drill-down: separate key, capped, kept for the same 50-entry
        // horizon. Failure here must never fail the run.
        try {
            prefs(context).edit {
                putString(logKey(execution.id), AutomationCodec.appRecordsTo(execution.apps).toString())
                commit()
            }
            pruneAppLogs(context)
        } catch (_: Exception) {
        }
    }

    /**
     * Tombstone records the worker writes at run START ("interrupted mid-run")
     * are superseded once the run completes and records its real execution.
     * Called on every completion path so a healthy run never leaves the
     * interrupted stub behind — only a genuinely dead process keeps it.
     */
    fun removeInterruptedTombstones(context: Context, configId: String) {
        try {
            val arr = readArray(context, K_HISTORY).toMutableList()
            val keep = arr.filterNot { item ->
                item.optString("configId") == configId &&
                    item.optString("errorSummary").startsWith("interrupted mid-run")
            }
            if (keep.size != arr.size) {
                writeArray(context, K_HISTORY, JSONArray(keep))
                arr.filter { it.optString("configId") == configId &&
                    it.optString("errorSummary").startsWith("interrupted mid-run") }.forEach {
                    prefs(context).edit { remove(logKey(it.optString("id"))) }
                }
            }
        } catch (_: Exception) {
        }
    }

    private const val K_APPLOG_PREFIX = "exec_apps_"

    private fun logKey(executionId: String) = "$K_APPLOG_PREFIX$executionId"

    /**
     * User-initiated wipe of ALL run history: summaries, per-app drill-down
     * logs, and the pending handback marker is NOT touched here (unrelated
     * pref file). Schedules/configs/labels are unaffected. Clearing history is
     * cosmetic — nothing else keys off these records.
     */
    fun clearHistory(context: Context) {
        writeArray(context, K_HISTORY, JSONArray())
        try {
            val keys = prefs(context).all.keys.filter { it.startsWith(K_APPLOG_PREFIX) }
            keys.forEach { key -> prefs(context).edit { remove(key) } }
        } catch (_: Exception) {
        }
    }

    /**
     * Exact pre-clear history JSON for the UI's Undo affordance. Call BEFORE
     * [clearHistory]; feed back to [restoreHistory] to undo.
     */
    fun snapshotHistory(context: Context): String =
        prefs(context).getString(K_HISTORY, "[]") ?: "[]"

    /**
     * Undo a clear: restore the snapshot verbatim and prune per-app logs to
     * exactly the restored set (a run recorded after the clear is kept only
     * if the snapshot is written — it is not: the snapshot predates it, so a
     * post-clear run's drill-down is correctly dropped as orphaned).
     */
    fun restoreHistory(context: Context, snapshot: String) {
        try {
            val arr = JSONArray(snapshot)
            val validIds = (0 until arr.length()).mapNotNull {
                try {
                    arr.getJSONObject(it).optString("id")
                } catch (_: Exception) {
                    null
                }
            }.toSet()
            writeArray(context, K_HISTORY, arr)
            val keys = prefs(context).all.keys.filter { it.startsWith(K_APPLOG_PREFIX) }
            keys.forEach { key ->
                if (key.removePrefix(K_APPLOG_PREFIX) !in validIds) {
                    prefs(context).edit { remove(key) }
                }
            }
        } catch (_: Exception) {
        }
    }

    /** Per-app records for one execution (empty when absent/older than the cap). */
    fun appRecordsFor(context: Context, executionId: String): List<AppHistoryRecord> {
        val raw = prefs(context).getString(logKey(executionId), null) ?: return emptyList()
        return try {
            // recordExecution writes the bare array; tolerate a wrapped
            // {"apps":[...]} shape too so no persisted log becomes unreadable.
            val arr = if (raw.trimStart().startsWith("{")) {
                JSONObject(raw).optJSONArray("apps")
            } else {
                JSONArray(raw)
            }
            AutomationCodec.appRecordsFrom(arr)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Keep at most [HISTORY_LIMIT] per-app logs, matching the summary horizon. */
    private const val HISTORY_LIMIT = 50

    private fun pruneAppLogs(context: Context) {
        val validIds = listExecutions(context).map { it.id }.toSet()
        val keys = prefs(context).all.keys.filter { it.startsWith(K_APPLOG_PREFIX) }
        keys.forEach { key ->
            if (key.removePrefix(K_APPLOG_PREFIX) !in validIds) {
                prefs(context).edit { remove(key) }
            }
        }
    }

    // ---------- export / import (schema v1; never secrets/backups) ----------

    fun exportJson(context: Context): JSONObject {
        // Schedules reference configs by NAME so imports resolve on any device.
        val configNames = listConfigs(context).associateBy({ it.id }, { it.name })
        val labelNames = listLabels(context).associateBy({ it.id }, { it.name })
        return JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("labels", JSONArray(listLabels(context).map(AutomationCodec::labelTo)))
            .put(
                "assignments",
                JSONArray(
                    listAssignments(context).map {
                        AutomationCodec.assignmentTo(it, labelNames[it.labelId] ?: "")
                    }
                )
            )
            .put(
                "configurations",
                JSONArray(listConfigs(context).map { AutomationCodec.configTo(it, labelNames) })
            )
            .put(
                "schedules",
                JSONArray(
                    listSchedules(context).map {
                        // Exports carry config NAMES (import resolution is
                        // name-first on any device); each entry remapped.
                        AutomationCodec.scheduleTo(
                            it.copy(configIds = it.configIds.map { cid -> configNames[cid] ?: cid })
                        )
                    }
                )
            )
            // Schedules reference configs by name; the master toggle state
            // rides along so a restored snapshot re-arms everything exactly
            // as it was. History is intentionally excluded: it is device
            // telemetry, not configuration.
            .put("masterEnabled", isMasterEnabled(context))
    }

    /** Import size guards: hostile files must fail fast, not OOM/ANR. */
    const val MAX_IMPORT_LABELS = 500
    const val MAX_IMPORT_CONFIGS = 100
    const val MAX_IMPORT_ASSIGNMENTS = 20_000
    const val MAX_IMPORT_SCHEDULES = 200
    const val MAX_IMPORT_RULES = 100

    fun importJson(context: Context, json: JSONObject): Result<ImportSummary> {
        return try {
            val version = json.optInt("schemaVersion", 0)
            // Migrate: v1 exports have no schedules; v2 adds them.
            if (version != 1 && version != SCHEMA_VERSION) {
                return Result.failure(IllegalArgumentException("unsupported schema: $version"))
            }
            val labelsArr = json.optJSONArray("labels")
            val configsArr = json.optJSONArray("configurations")
            val assignsArr = json.optJSONArray("assignments")
            val schedulesArr = json.optJSONArray("schedules")
            if ((labelsArr?.length() ?: 0) > MAX_IMPORT_LABELS ||
                (configsArr?.length() ?: 0) > MAX_IMPORT_CONFIGS ||
                (assignsArr?.length() ?: 0) > MAX_IMPORT_ASSIGNMENTS ||
                (schedulesArr?.length() ?: 0) > MAX_IMPORT_SCHEDULES
            ) {
                return Result.failure(IllegalArgumentException("import too large"))
            }
            var labels = 0
            var configs = 0
            var assignments = 0
            var skippedAssignments = 0
            val failedConfigs = mutableListOf<String>()
            // Snapshot reads once: per-item listLabels() reparses were O(n²).
            var knownLabels = listLabels(context)
            // Incoming id->name map for rule resolution (export always carries both).
            val incomingLabelNames = mutableMapOf<String, String>()
            labelsArr?.let { arr ->
                for (i in 0 until arr.length()) {
                    try {
                        val o = arr.getJSONObject(i)
                        incomingLabelNames[o.optString("id")] = o.optString("name")
                    } catch (_: Exception) {
                    }
                }
            }
            labelsArr?.let { arr ->
                val acc = knownLabels.toMutableList()
                for (i in 0 until arr.length()) {
                    try {
                        val incoming = AutomationCodec.labelFrom(arr.getJSONObject(i))
                        if (incoming.name.isBlank() || incoming.name.length > 64) continue
                        if (acc.none { it.name.equals(incoming.name, ignoreCase = true) }) {
                            acc.add(
                                incoming.copy(
                                    id = newId(),
                                    description = incoming.description.take(256)
                                )
                            )
                            labels++
                        }
                    } catch (_: Exception) {
                    }
                }
                persistLabels(context, acc)
                knownLabels = acc
            }
            // Re-resolve label names -> ids for assignments/configs after merge.
            val byName = knownLabels.associateBy({ it.name.lowercase() }, { it.id })
            val currentIds = knownLabels.map { it.id }.toSet()
            assignsArr?.let { arr ->
                // Bulk: one resolved set + ONE persist, instead of per-row
                // assign() calls — each of those rewrites the whole primary
                // file through a root-shell round trip, so a large restore
                // serialized thousands of atomic writes (minutes of I/O).
                val acc = listAssignments(context).toMutableList()
                for (i in 0 until arr.length()) {
                    try {
                        val resolved = AutomationCodec.resolveAssignment(arr.getJSONObject(i), byName, currentIds)
                        if (resolved.droppedReason != null) {
                            skippedAssignments++
                            continue
                        }
                        try {
                            ShellQuote.requirePackage(resolved.packageName)
                        } catch (_: Exception) {
                            skippedAssignments++
                            continue
                        }
                        if (acc.any { it.packageName == resolved.packageName && it.labelId == resolved.labelId }) {
                            // Already present: assign() treated dups as success.
                            assignments++
                            continue
                        }
                        acc.add(LabelAssignment(resolved.packageName, resolved.labelId))
                        assignments++
                    } catch (_: Exception) {
                        skippedAssignments++
                    }
                }
                if (assignments > 0 || acc.size != listAssignments(context).size) {
                    persistAssignments(context, acc)
                }
            }
            configsArr?.let { arr ->
                for (i in 0 until arr.length()) {
                    try {
                        val incoming = AutomationCodec.configFrom(arr.getJSONObject(i))
                        val resolved = AutomationCodec.resolveRules(
                            incoming.rules.take(MAX_IMPORT_RULES), incomingLabelNames, byName, currentIds
                        )
                        if (resolved.rules.isEmpty()) {
                            failedConfigs.add(
                                incoming.name.ifEmpty { "unnamed" } +
                                    " (missing labels: ${resolved.missingLabels.joinToString(", ").ifEmpty { "none" }})"
                            )
                            continue
                        }
                        // Fresh id, remapped rules, and no foreign ordering:
                        // imports join the bottom of the local arrangement.
                        // Folder selections are clamped to the SHARED registry
                        // (Settings is the master) — the resolver prunes at run
                        // time anyway; this keeps the editor honest at import.
                        val remapped = incoming.copy(
                            id = newId(),
                            rules = resolved.rules,
                            folderPaths = BackupConfigFolders.resolveAgainstStore(
                                incoming.folderPaths, BackupFolderStore.folders.value
                            ),
                            listOrder = CONFIG_LIST_ORDER_DEFAULT
                        )
                        if (saveConfig(context, remapped).isSuccess) configs++
                        else failedConfigs.add(incoming.name.ifEmpty { "unnamed" })
                    } catch (_: Exception) {
                        failedConfigs.add("unreadable entry")
                    }
                }
            }
            var schedules = 0
            // v1 exports carry no schedules array; v2 does (by config name).
            val configByName = listConfigs(context).associateBy({ it.name.lowercase() }, { it.id })
            schedulesArr?.let { arr ->
                for (i in 0 until arr.length()) {
                    try {
                        val incoming = AutomationCodec.scheduleFrom(arr.getJSONObject(i))
                        // Name → id per entry; entries that resolve to nothing
                        // on THIS device are dropped (a missing config name can
                        // never become available). A schedule with no surviving
                        // entry is not imported.
                        val resolved = incoming.configIds.mapNotNull { cid ->
                            configByName[cid.lowercase()] ?: cid.takeIf { it in configByName.values }
                        }.distinct()
                        if (resolved.isEmpty()) continue
                        val remapped = incoming.copy(id = newId(), configIds = resolved)
                        if (saveSchedule(context, remapped).isSuccess) schedules++
                    } catch (_: Exception) {
                    }
                }
            }
            // Restore the master toggle last: it reconciles the whole arm state
            // (on → rescheduleAll, off → cancelAll), so the just-imported
            // schedules end up armed exactly as the snapshot had them.
            if (json.has("masterEnabled")) {
                setMasterEnabled(context, json.optBoolean("masterEnabled", true))
            }
            Result.success(ImportSummary(labels, configs, assignments, schedules, skippedAssignments, failedConfigs))
        } catch (e: Exception) {
            Result.failure(IllegalArgumentException("invalid import: ${e.message}"))
        }
    }

    data class ImportSummary(
        val labels: Int,
        val configs: Int,
        val assignments: Int,
        val schedules: Int = 0,
        val skippedAssignments: Int = 0,
        val failedConfigs: List<String> = emptyList()
    ) {
        fun describe(): String {
            fun n(count: Int, one: String, many: String) =
                "$count ${if (count == 1) one else many}"
            val base = "Imported " + listOf(
                n(labels, "label", "labels"),
                n(configs, "config", "configs"),
                n(assignments, "assignment", "assignments")
            ).joinToString(", ")
            val extra = buildList {
                if (schedules > 0) add(n(schedules, "schedule", "schedules"))
                if (skippedAssignments > 0) {
                    add("${n(skippedAssignments, "assignment", "assignments")} skipped")
                }
                if (failedConfigs.isNotEmpty()) add("failed: ${failedConfigs.joinToString("; ")}")
            }
            return if (extra.isEmpty()) base else "$base (${extra.joinToString(", ")})"
        }
    }

    fun newId(): String = UUID.randomUUID().toString()

    /**
     * Bind the store to [folderPath] (the Droid Vault folder / selected custom
     * folder). Runs at startup and after every folder change. With the state
     * file as PRIMARY storage this is a reconciliation, not an import:
     *
     *  1. Folder changed (force): invalidate the read cache — subsequent
     *     list*() calls resolve sections from the NEW folder's file.
     *  2. One-time migration: if the folder has no state file yet but the
     *     prefs cache holds data (pre-refactor installs), write the primary
     *     file FROM prefs. Runs exactly once per folder (marker pref).
     *  3. Recovery: if the prefs cache is EMPTY but the file has data
     *     (app-data cleared, fresh reinstall on a kept folder), backfill the
     *     prefs cache from the file so marker GCs see live schedules.
     *  4. Convergence: a PARTIALLY initialized cache (some sections written,
     *     others never touched — e.g. the pre-fix clobber left configs in
     *     prefs while labels were file-only) adopts the file's rows for its
     *     never-initialized sections, so the cache ends up complete.
     *
     * Never imports-merge — the primary file IS the store now.
     */
    suspend fun autoImportStateFromFile(context: Context, folderPath: String, force: Boolean = false) =
        withContext(Dispatchers.IO) {
            try {
                val p = prefs(context)
                if (force || p.getString(K_AUTO_IMPORT, null) != folderPath) {
                    p.edit { putString(K_AUTO_IMPORT, folderPath) }
                    invalidatePrimaryCache()
                }
                // Detection MUST read the RAW prefs cache, not list*(): the
                // list readers resolve primary-first, so on a fresh install
                // (empty cache, populated file) they report data that prefs
                // does not hold — which made the backfill branch unreachable
                // and let the next mutation clobber the file from an empty
                // cache (observed in the field as labels lost on reinstall).
                fun rawSectionHasRows(key: String): Boolean =
                    parsePrefArray(prefRaw(context, key)).isNotEmpty()
                // K_ASSIGN counts too: a cache holding ONLY assignment rows
                // (the exact partial state the convergence step below fixes)
                // must not read as "empty" — the recovery branch used to fire
                // on it and backfill-overwrite the assignments with the file's
                // stale/empty section, destroying the only copy (observed in
                // the field as assignments lost after a reinstall).
                val prefData = rawSectionHasRows(K_LABELS) ||
                    rawSectionHasRows(K_CONFIGS) || rawSectionHasRows(K_SCHEDULES) ||
                    rawSectionHasRows(K_ASSIGN)
                // "Has no rows" is NOT the same as "was never written". Recovery
                // used to be gated on `!prefData`, so a user who deliberately
                // deleted every label and config — leaving the sections present
                // but empty — made the cache read as lost, and the app restored
                // the deleted rows from the vault file and announced "Automation
                // data restored". That is invariant 3 broken from the other side:
                // a section the user emptied is user intent.
                //
                // This is also why the notice looked random: it fires on any
                // app-data clear or reinstall (the legitimate case) AND whenever
                // automation data was emptied in a normal session (the bug).
                val prefInitialized = RECOVERY_SECTIONS.any { prefRaw(context, it) != null }
                val parsed = loadPrimary(folderPath)
                val fileData = parsed?.let {
                    it.labels != null || it.configs != null || it.schedules != null ||
                        it.assignments != null || it.masterEnabled != null
                } ?: false
                when {
                    // 2. Legacy migration: prefs data, no file yet → promote.
                    prefData && !fileData ->
                        try {
                            writePrimaryStateFile(context)
                        } catch (_: Exception) {
                        }
                    // 3. Recovery: file has data, cache NEVER written → backfill.
                    shouldRecoverFromFile(prefInitialized, fileData) -> try {
                        val state = parsed
                        if (state != null) {
                            backfillCacheFromPrimary(context, state)
                            // WorkManager's database is app-private and is
                            // lost with an uninstall or clean ROM install.
                            // The schedule definitions survived in the vault
                            // file, so recreate their requests after recovery.
                            // The per-schedule workers still resolve current
                            // configs by id at fire time.
                            if (isMasterEnabled(context)) {
                                AutomationScheduler.rescheduleAll(context)
                            } else {
                                AutomationScheduler.cancelAll(context)
                            }
                            // Post-onboarding hint: a fresh install (or an app-data
                            // clear) just recovered real rows from the vault folder.
                            // Park a one-time notice the main screen surfaces with
                            // a jump to the Auto tab. Zero-row files (shell docs)
                            // announce nothing.
                            val rows = (state.labels?.size ?: 0) + (state.assignments?.size ?: 0) +
                                (state.configs?.size ?: 0) + (state.schedules?.size ?: 0)
                            if (rows > 0) {
                                val hint = org.json.JSONObject()
                                    .put("labels", state.labels?.size ?: 0)
                                    .put("assignments", state.assignments?.size ?: 0)
                                    .put("configs", state.configs?.size ?: 0)
                                    .put("schedules", state.schedules?.size ?: 0)
                                    .put("armed", isMasterEnabled(context))
                                    .put("at", System.currentTimeMillis())
                                prefs(context).edit { putString(K_RESTORE_HINT, hint.toString()) }
                                // Push it to the UI as well as to prefs: a screen
                                // that composed before this point never re-reads.
                                publishRestoreHint(
                                    RestoreHint(
                                        labels = state.labels?.size ?: 0,
                                        assignments = state.assignments?.size ?: 0,
                                        configs = state.configs?.size ?: 0,
                                        schedules = state.schedules?.size ?: 0,
                                        at = System.currentTimeMillis(),
                                        armed = isMasterEnabled(context)
                                    )
                                )
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
                // 4. Convergence: fill cache sections the file holds rows for
                // but prefs never initialized. Initialized sections (even
                // deliberately-empty "[]") are left alone — the write-side
                // merge treats them as user intent.
                parsed?.let { doc ->
                    if (prefRaw(context, K_LABELS) == null && doc.labels != null)
                        writeArray(context, K_LABELS, JSONArray(doc.labels))
                    if (prefRaw(context, K_ASSIGN) == null && doc.assignments != null)
                        writeArray(context, K_ASSIGN, JSONArray(doc.assignments))
                    if (prefRaw(context, K_CONFIGS) == null && doc.configs != null)
                        writeArray(context, K_CONFIGS, JSONArray(doc.configs))
                    if (prefRaw(context, K_SCHEDULES) == null && doc.schedules != null)
                        writeArray(context, K_SCHEDULES, JSONArray(doc.schedules))
                }
                // 5. Normalize: pre-refactor primary files were written by
                // snapshotState (export-shaped: schedule configId held the
                // config NAME). Ids are the join key on-device — remap any
                // name references once, in place.
                try {
                    normalizeScheduleConfigRefs(context)
                } catch (_: Exception) {
                }
                // 6. Reinstall self-heal: config rules persist the label UUID
                // of the install that wrote them; the state file brings the
                // labels back (possibly under fresh UUIDs). Every rule row
                // embeds its label's name, so relinking is an exact by-name
                // remap — no-op on healthy stores (reads only, zero writes).
                try {
                    relinkDanglingRules(context)
                } catch (_: Exception) {
                }
                // 7. FOLDERS reconciliation — Settings' shared registry
                // (BackupFolderStore) is the SINGLE source of truth; config
                // selections are always its subset. Two directions:
                //   a) Reinstall recovery: the registry lives in droidvault_prefs
                //      which an uninstall wipes, while resolveAgainstStore()
                //      prunes every FOLDERS-enabled config's selection against
                //      it. ONLY when the registry is EMPTY (the wipe signature;
                //      a user with folders can never hit this) do we re-seed
                //      it from the file's configured folder paths. A live
                //      registry is never re-added to, so deliberate removals
                //      survive every bind.
                //   b) Prune-on-read: paths still referenced by persisted
                //      configs but absent from the (non-empty) registry were
                //      deleted in Settings — drop them from the configs too,
                //      so editors can never re-offer a deleted folder.
                try {
                    val configuredPaths = parsed?.configs
                        ?.flatMap { cfg ->
                            cfg.optJSONArray("folderPaths")?.let { arr ->
                                (0 until arr.length()).map { i -> arr.optString(i) }
                            } ?: emptyList()
                        }
                        .orEmpty()
                    if (BackupFolderStore.folders.value.isEmpty()) {
                        configuredPaths.takeIf { it.isNotEmpty() }
                            ?.let { BackupFolderStore.ensureRegistered(context, it) }
                    } else {
                        val storePaths = BackupFolderStore.folders.value.toSet()
                        configuredPaths.filter { it !in storePaths }.distinct()
                            .forEach { stalePath ->
                                pruneRemovedBackupFolder(context, stalePath)
                            }
                    }
                } catch (_: Exception) {
                }
            } catch (_: Exception) {
            }
        }

    /** One-time migration marker (per folder path). */
    private const val K_AUTO_IMPORT = "auto_import_path"

    /** Sections whose recovery is gated on "never written" rather than "empty". */
    private val RECOVERY_SECTIONS = listOf(K_LABELS, K_CONFIGS, K_SCHEDULES, K_ASSIGN)

    /**
     * Should the cache be backfilled from the vault file?
     *
     * True only when the file has data and the cache was never initialized.
     * Deliberately NOT `fileData && !hasRows`: an empty-but-present section is a
     * user who deleted their data, not a user who lost it, and overwriting it
     * resurrects what they removed while telling them it was restored.
     *
     * Pure so the distinction is pinned by tests rather than re-derived.
     */
    internal fun shouldRecoverFromFile(prefInitialized: Boolean, fileData: Boolean): Boolean =
        fileData && !prefInitialized

    /** One-time "your automation data was restored" notice (post-onboarding). */
    private const val K_RESTORE_HINT = "restored_hint_v1"

    /** What the recovery backfill brought back (for the main-screen notice). */
    data class RestoreHint(
        val labels: Int,
        val assignments: Int,
        val configs: Int,
        val schedules: Int,
        val at: Long,
        /**
         * Whether automation is switched ON after recovery.
         *
         * Recovery deliberately calls `cancelAll()` when the master switch was
         * off, so the schedule DEFINITIONS come back while nothing is armed. The
         * old notice said "Everything is back the way it was" regardless, which is
         * true about the data and false about the one thing a user relying on
         * unattended backups would care about: their schedules will not run.
         */
        val armed: Boolean = false
    ) {
        val totalRows: Int get() = labels + assignments + configs + schedules
    }

    /**
     * The pending restore notice, observable.
     *
     * The notice used to be read exactly once, by a `LaunchedEffect(Unit)` in
     * MainScreen. But recovery runs asynchronously — measured on device, the
     * store finished recovering and wrote the hint at T+1.2s, while the screen
     * had already composed at T+0.4s and read `null`. The read was never
     * repeated, so the dialog simply never appeared.
     *
     * That is the reported "a popup appears at random": whether the user saw it
     * came down to a race between two independent coroutines, and it was
     * reported as randomness because nothing about the timing is visible to the
     * user. Publishing instead of polling removes the ordering entirely — the
     * screen shows the notice whenever the recovery happens to finish, before
     * or after it composed.
     */
    private val _restoreHint = MutableStateFlow<RestoreHint?>(null)
    val restoreHint: StateFlow<RestoreHint?> = _restoreHint.asStateFlow()

    /** Pending restore notice, null when none. Does not consume it. */
    fun peekRestoreHint(context: Context): RestoreHint? = try {
        val raw = prefs(context).getString(K_RESTORE_HINT, null)
        val hint = raw?.let {
            val o = org.json.JSONObject(it)
            RestoreHint(
                labels = o.optInt("labels"),
                assignments = o.optInt("assignments"),
                configs = o.optInt("configs"),
                schedules = o.optInt("schedules"),
                at = o.optLong("at"),
                armed = o.optBoolean("armed", false)
            )
        }
        _restoreHint.value = hint
        hint
    } catch (_: Exception) {
        null
    }

    /** Record a freshly produced notice so an already-composed screen sees it. */
    private fun publishRestoreHint(hint: RestoreHint?) {
        _restoreHint.value = hint
    }

    /** Consume (dismiss) the pending restore notice. */
    fun clearRestoreHint(context: Context) {
        try {
            prefs(context).edit { remove(K_RESTORE_HINT) }
            _restoreHint.value = null
        } catch (_: Exception) {
        }
    }

    /**
     * Restore the prefs cache from a parsed primary document (app-data-clear
     * recovery). Marker prefs are pruned to the restored schedule set, since
     * stale spent/done markers for vanished schedules would otherwise linger.
     */
    private fun backfillCacheFromPrimary(context: Context, parsed: ParsedState) {
        // Per-section null-guard: NEVER overwrite a section the cache already
        // initialized (even "[]" = deliberate empty). Recovery exists to fill
        // an empty cache; the write-side merge contract says initialized
        // sections are user intent. With the truly-empty cache this branch is
        // gated on, every guard passes and behavior is unchanged.
        if (prefRaw(context, K_LABELS) == null) parsed.labels?.let { writeArray(context, K_LABELS, JSONArray(it)) }
        if (prefRaw(context, K_ASSIGN) == null) parsed.assignments?.let { writeArray(context, K_ASSIGN, JSONArray(it)) }
        if (prefRaw(context, K_CONFIGS) == null) parsed.configs?.let { writeArray(context, K_CONFIGS, JSONArray(it)) }
        if (prefRaw(context, K_SCHEDULES) == null) parsed.schedules?.let { writeArray(context, K_SCHEDULES, JSONArray(it)) }
        if (!prefs(context).contains(K_MASTER)) parsed.masterEnabled?.let { prefs(context).edit { putBoolean(K_MASTER, it) } }
        val live = try {
            listSchedules(context).map { it.id }.toSet()
        } catch (_: Exception) {
            null
        }
        if (live != null) {
            try {
                val p = prefs(context)
                val stale = p.all.keys.filter {
                    (it.startsWith(K_SPENT_PREFIX) || it.startsWith(K_DONE_PREFIX)) &&
                        it.removePrefix(K_SPENT_PREFIX).removePrefix(K_DONE_PREFIX) !in live
                }
                stale.forEach { key -> p.edit { remove(key) } }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * One-time normalization of legacy primary files: [AutomationSnapshot.snapshotState]
     * (the pre-refactor writer) exported schedules with configId holding the
     * config NAME. Remap name → id in place when the reference resolves;
     * unknown references are left untouched.
     */
    private fun normalizeScheduleConfigRefs(context: Context) {
        val configs = listConfigs(context)
        if (configs.isEmpty()) return
        val byId = configs.associateBy { it.id }
        val byName = configs.associateBy({ it.name.lowercase() }, { it.id })
        val schedules = listSchedules(context)
        if (schedules.isEmpty()) return
        var changed = false
        val remapped = schedules.map { s ->
            val next = s.configIds.map { cid ->
                if (byId.containsKey(cid)) cid
                else byName[cid.lowercase()] ?: cid
            }
            if (next != s.configIds) {
                changed = true
                s.copy(configIds = next)
            } else s
        }
        if (changed) persistSchedules(context, remapped)
    }

    // ---------- persistence plumbing ----------

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Droid Vault folder (primary store location). Read fresh from
     * `droidvault_prefs` on every call — folder selection may change at runtime
     * (Settings move, onboarding pick, auto-detect).
     */
    fun folderPath(context: Context): String {
        val def = SettingsManager.getDefaultPath()
        return try {
            context.getSharedPreferences(BASE_PATH_PREFS, Context.MODE_PRIVATE)
                .getString(BASE_PATH_KEY, def) ?: def
        } catch (_: Exception) {
            def
        }
    }

    /** Monotonic revision bumped on every successful primary write. */
    @Volatile
    private var storeRev: Long = 0

    /**
     * True when the last primary-file write failed. While set, reads prefer the
     * prefs cache (see [resolveSection]) so a failed write cannot resurrect
     * stale on-disk rows over the user's newer edit. Cleared by the next
     * successful write.
     */
    @Volatile
    private var primaryFileStale: Boolean = false

    @Volatile
    private var cachedFolder: String? = null

    @Volatile
    private var cachedParsed: ParsedState? = null

    @Volatile
    private var cachedAt: Long = 0L

    private fun cacheValid(folder: String, now: Long): Boolean {
        val c = cachedParsed ?: return false
        return cachedFolder == folder && c.rev == storeRev && now - cachedAt < CACHE_TTL_MS
    }

    private const val CACHE_TTL_MS = 2000L

    /**
     * Load + parse the primary file for [folder] with a tiny TTL cache so
     * bursts of reads (one UI refresh = up to five section reads) hit the
     * shell once. Null when missing/unreadable — callers then fall back to
     * the prefs cache.
     */
    private fun loadPrimary(folder: String): ParsedState? {
        val now = System.currentTimeMillis()
        if (cacheValid(folder, now)) return cachedParsed
        val parsed = parseStateFile(readStateFileBlocking(folder))
        synchronized(STATE_FILE_LOCK) {
            // Double-check inside the lock (audit A-18): a concurrent reader
            // may have installed a fresher parse — or a writer invalidated the
            // cache — while this thread was reading/parsing the file. Never
            // overwrite a newer result with this stale one.
            if (cacheValid(folder, System.currentTimeMillis())) return cachedParsed
            cachedFolder = folder
            cachedParsed = parsed
            cachedAt = System.currentTimeMillis()
        }
        return parsed
    }

    /**
     * Total row count across all sections of the primary state file at
     * [folderPath]. 0 when the file is missing, unparsable, or a
     * fresh-install shell (all sections null/empty). Powers the
     * restore-offer decision — see [AutomationSnapshot.shouldOfferRestore].
     */
    fun stateFileRowCount(folderPath: String): Int {
        val parsed = loadPrimary(folderPath) ?: return 0
        return listOf(parsed.labels, parsed.assignments, parsed.configs, parsed.schedules)
            .sumOf { it?.size ?: 0 }
    }

    private fun invalidatePrimaryCache() {
        synchronized(STATE_FILE_LOCK) {
            cachedFolder = null
            cachedParsed = null
            cachedAt = 0L
        }
    }

    /**
     * Section resolution: PRIMARY file first, prefs fallback.
     * [prefJson] is the cached section; [primaryObjs] are the file's rows;
     * [toDomain]/[fromDomain] convert between them.
     */
    private inline fun <reified T> resolveSection(
        prefJson: String?,
        primaryObjs: List<JSONObject>?,
        fromPrimary: (JSONObject) -> T,
        parsePref: (String?) -> List<T>
    ): List<T> {
        // While the file is known-stale (the last primary write failed), the
        // prefs cache is the newer of the two and MUST win. Otherwise the read
        // path hands back the stale on-disk rows and a just-saved rename/config
        // silently reverts a couple of seconds later — the opposite of what the
        // write path's "prefs stay authoritative on failure" contract claims.
        if (primaryObjs != null && !primaryFileStale) {
            return try {
                primaryObjs.mapNotNull { o ->
                    try {
                        fromPrimary(o)
                    } catch (_: Exception) {
                        null
                    }
                }
            } catch (_: Exception) {
                parsePref(prefJson)
            }
        }
        // Key absent from the file (fresh store, or a legacy export lacking
        // the section), or the file lost the last write: fall back to the prefs
        // cache so a recent mutation is not silently hidden.
        return try {
            parsePref(prefJson)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun resolveMaster(prefEnabled: Boolean, primaryMaster: Boolean?): Boolean =
        primaryMaster ?: prefEnabled

    // ---------- persistence plumbing (legacy prefs helpers) ----------

    private fun readArray(context: Context, key: String): List<JSONObject> {
        val raw = prefs(context).getString(key, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getJSONObject(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeArray(context: Context, key: String, arr: JSONArray) {
        // commit(), not apply(): history writes are low-frequency and must
        // survive a SIGKILL that can land immediately after (record-before-
        // handback: the SMS role release kills the process ~100 ms later).
        prefs(context).edit { putString(key, arr.toString()); commit() }
    }

    private fun persistLabels(context: Context, labels: List<AppLabel>) {
        writeArray(context, K_LABELS, JSONArray(labels.map(AutomationCodec::labelTo)))
        writePrimaryStateFile(context)
    }

    private fun persistAssignments(context: Context, items: List<LabelAssignment>) {
        // Embed the label's CURRENT name in every row: labelId is a local UUID
        // a reinstall re-issues, the name is the stable join key.
        val names = listLabels(context).associateBy({ it.id }, { it.name })
        writeArray(
            context, K_ASSIGN,
            JSONArray(items.map { AutomationCodec.assignmentTo(it, names[it.labelId] ?: "") })
        )
        writePrimaryStateFile(context)
    }

    private fun persistConfigs(context: Context, configs: List<BackupConfig>) {
        // Same as [persistAssignments]: every rule row carries its label's
        // CURRENT name so relinkDanglingRules() can repair rule→label joins
        // exactly after a reinstall re-issued the UUIDs.
        val names = listLabels(context).associateBy({ it.id }, { it.name })
        writeArray(context, K_CONFIGS, JSONArray(configs.map { AutomationCodec.configTo(it, names) }))
        writePrimaryStateFile(context)
    }

    private fun persistSchedules(context: Context, schedules: List<AutomationSchedule>) {
        writeArray(context, K_SCHEDULES, JSONArray(schedules.map(AutomationCodec::scheduleTo)))
        writePrimaryStateFile(context)
    }

    /**
     * Per-schedule spent/done markers only mutate prefs (they are run
     * bookkeeping, not configuration), but they must not roll the primary
     * file back to a stale schedule set — so markSpent/markScheduleCompleted
     * GC markers WITHOUT rewriting the state file.
     */

    /**
     * Public sync hook: rewrite the primary file from the prefs cache now.
     * Used by run-completion paths (AutomationRunner / DeviceManager /
     * MainViewModel) that previously called [AutomationSnapshot.snapshotState]
     * — the export-shaped document is NOT interchangeable with the live
     * primary store (export remaps schedule configIds to names), so those
     * call sites must serialize the id-based working set instead.
     */
    fun syncPrimaryStateFile(context: Context): Boolean =
        try {
            writePrimaryStateFile(context)
            true
        } catch (_: Exception) {
            false
        }

    /**
     * Rewrite the WHOLE primary state file from the current store. Prefs
     * remain the assembled working set (mutations commit there first), but
     * each section falls back to the LIVE file's rows when the prefs cache
     * was never initialized for it — the anti-clobber contract that keeps a
     * fresh install (empty cache, populated file) from erasing labels on its
     * first mutation. On write failure the prefs cache stays authoritative
     * and the next mutation (or the periodic snapshot paths) retries.
     */
    private fun writePrimaryStateFile(context: Context) {
        // Read the live document BEFORE writing: it is the merge base for
        // sections this process has never touched.
        val current = loadPrimary(folderPath(context))
        val json = buildStateJson(context, current)
        val folder = folderPath(context)
        val ok = writeStateFileBlocking(context, folder, json)
        if (ok) {
            storeRev += 1
            primaryFileStale = false
            invalidatePrimaryCache()
        } else {
            // Mark the file stale so reads fall back to the (newer) prefs cache
            // instead of handing back the pre-edit on-disk rows. The next
            // successful write clears this.
            primaryFileStale = true
        }
    }

    /**
     * Per-section write resolution (pure, unit-testable): the prefs cache
     * wins once it has been initialized — [writeArray] always leaves a
     * string, even "[]" for a DELIBERATE empty set, so an empty cache
     * legitimately deletes; a null cache (never written) adopts the primary
     * file's rows so untouched sections survive fresh installs and
     * app-data clears.
     */
    fun resolveSectionForWrite(prefRawValue: String?, primaryRows: List<JSONObject>?): List<JSONObject> =
        when {
            prefRawValue != null -> parsePrefArray(prefRawValue)
            primaryRows != null -> primaryRows
            else -> emptyList()
        }

    /**
     * Build the primary state document from the prefs cache merged over the
     * live file ([primary]; null when the file is missing/unparseable). Same
     * shape as [exportJson] minus the by-name remapping of schedule
     * configIds: the primary file stays on THIS device, where ids are the
     * join key.
     */
    fun buildStateJson(context: Context, primary: ParsedState? = null): String {
        val p = prefs(context)
        val master = if (p.contains(K_MASTER)) p.getBoolean(K_MASTER, true)
        else primary?.masterEnabled ?: true
        val o = JSONObject()
            .put(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
            .put(KEY_STORE_REV, storeRev)
            .put(KEY_LABELS, JSONArray(resolveSectionForWrite(prefRaw(context, K_LABELS), primary?.labels)))
            .put(KEY_ASSIGNMENTS, JSONArray(resolveSectionForWrite(prefRaw(context, K_ASSIGN), primary?.assignments)))
            .put(KEY_CONFIGS, JSONArray(resolveSectionForWrite(prefRaw(context, K_CONFIGS), primary?.configs)))
            .put(KEY_SCHEDULES, JSONArray(resolveSectionForWrite(prefRaw(context, K_SCHEDULES), primary?.schedules)))
            .put(KEY_MASTER, master)
        return o.toString()
    }

    private fun executionTo(e: ConfigExecution): JSONObject = JSONObject()
        .put("id", e.id).put("configId", e.configId).put("configName", e.configName)
        .put("trigger", e.trigger.name).put("startedAt", e.startedAt)
        .put("finishedAt", e.finishedAt).put("result", e.result.name)
        .put("errorSummary", e.errorSummary)
        .put("success", e.successCount).put("failed", e.failureCount)
        .put("skipped", e.skippedCount)
        .put("operation", e.operation.name)
        .put("deviceData", JSONArray(e.apps.mapNotNull { it.deviceItem?.name }))
}

/**
 * Pure reorder for the config list (unit-testable, no Context): returns the
 * full list with [id]'s same-operation group renumbered 1..n after swapping
 * the config one slot up/down, or null if [id] is unknown. Already at the
 * requested edge → list returned unchanged (positions renumbered). The other
 * operation group is passed through untouched.
 */
fun reorderedConfigs(
    configs: List<BackupConfig>,
    id: String,
    up: Boolean
): List<BackupConfig>? {
    val target = configs.find { it.id == id } ?: return null
    // Swap against the order the user actually SEES (listOrder, then name) —
    // never raw storage order, which drifts from display order after the
    // first move and would pair the config with the wrong neighbour.
    val siblings = configs.filter { it.operation == target.operation }
        .sortedWith(compareBy({ it.listOrder }, { it.name.lowercase() }))
    val idx = siblings.indexOfFirst { it.id == id }
    val swapIdx = if (up) idx - 1 else idx + 1
    val reordered = if (swapIdx in siblings.indices) {
        siblings.toMutableList().apply {
            val tmp = this[idx]; this[idx] = this[swapIdx]; this[swapIdx] = tmp
        }
    } else siblings
    val positions = reordered.mapIndexed { i, c -> c.id to (i + 1L) }.toMap()
    return configs.map { c -> positions[c.id]?.let { c.copy(listOrder = it) } ?: c }
}

/**
 * Pure prune core (unit-testable, no Context): drop [removedPath] from every
 * config's folderPaths; null when no config referenced it (cheap no-op check
 * for callers, mirroring [relinkRulesToLabels]'s same-instance contract).
 */
fun pruneConfigsFolder(
    configs: List<BackupConfig>,
    removedPath: String
): List<BackupConfig>? {
    if (configs.none { removedPath in it.folderPaths }) return null
    return configs.map { cfg ->
        if (removedPath in cfg.folderPaths) {
            cfg.copy(folderPaths = cfg.folderPaths - removedPath)
        } else cfg
    }
}

/** Pure config validation (unit-testable): config against known label IDs. */
fun validateAutomationConfig(config: BackupConfig, labelIds: Set<String>): List<String> {
    val errors = mutableListOf<String>()
    if (config.name.isBlank()) errors.add("name required")
    // A config that carries device data may legitimately have no app rules —
    // it just processes the selected device data on every run.
    if (config.rules.isEmpty() && config.deviceData.isEmpty())
        errors.add("at least one rule (or device data item) required")
    config.rules.forEachIndexed { i, rule ->
        if (rule.labelId !in labelIds) errors.add("rule ${i + 1}: unknown label")
        if (!rule.exclude && rule.components.isEmpty()) errors.add("rule ${i + 1}: no components")
    }
    val prios = config.rules.map { it.priority }
    if (prios.size != prios.toSet().size) errors.add("duplicate priorities")
    return errors
}
