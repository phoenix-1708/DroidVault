package com.phoenix.droidvault.utils

import com.phoenix.droidvault.models.AppHistoryRecord
import com.phoenix.droidvault.models.AppLabel
import com.phoenix.droidvault.models.AutomationSchedule
import com.phoenix.droidvault.models.BackupComponent
import com.phoenix.droidvault.models.BackupConfig
import com.phoenix.droidvault.models.CONFIG_LIST_ORDER_DEFAULT
import com.phoenix.droidvault.models.ConfigOperation
import com.phoenix.droidvault.models.DeviceDataItem
import com.phoenix.droidvault.models.ConfigRule
import com.phoenix.droidvault.models.FallbackPolicy
import com.phoenix.droidvault.models.LabelAssignment
import com.phoenix.droidvault.models.ResultState
import com.phoenix.droidvault.models.ScheduleType
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Pure JSON codecs for automation models (no Context). Shared by
 * [AutomationStore] (persistence) and export/import; unit-tested on JVM.
 */
object AutomationCodec {

    fun newId(): String = UUID.randomUUID().toString()

    // ---------- per-app history logs (drill-down; unit-tested roundtrip) ----------

    /** Cap on per-app records persisted per execution: 1000-app runs stay bounded. */
    const val MAX_LOGGED_APPS = 200

    fun appRecordTo(r: AppHistoryRecord): JSONObject = JSONObject()
        .put("pkg", r.packageName).put("app", r.appName)
        .put("ruleId", r.ruleId ?: "")
        .put("requested", componentsTo(r.requested))
        .put("processed", componentsTo(r.processed))
        .put("result", r.result.name).put("reason", r.reason)
        .put("durMs", r.durationMs)
        .put("deviceItem", r.deviceItem?.name ?: "")

    fun appRecordFrom(o: JSONObject): AppHistoryRecord = AppHistoryRecord(
        packageName = o.optString("pkg"),
        appName = o.optString("app"),
        ruleId = o.optString("ruleId").ifEmpty { null },
        requested = componentsFrom(o.optJSONArray("requested")),
        processed = componentsFrom(o.optJSONArray("processed")),
        result = try {
            ResultState.valueOf(o.optString("result", "SUCCESS"))
        } catch (_: Exception) {
            ResultState.FAILED
        },
        reason = o.optString("reason", ""),
        durationMs = o.optLong("durMs", 0L),
        deviceItem = DeviceDataItem.fromName(o.optString("deviceItem"))
    )

    /** Serialize per-app records, hard-capped at [MAX_LOGGED_APPS]. */
    fun appRecordsTo(records: List<AppHistoryRecord>): JSONArray {
        val capped = records.take(MAX_LOGGED_APPS)
        return JSONArray(capped.map(::appRecordTo))
    }

    fun appRecordsFrom(arr: JSONArray?): List<AppHistoryRecord> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull {
            try {
                appRecordFrom(arr.getJSONObject(it))
            } catch (_: Exception) {
                null
            }
        }
    }

    fun labelTo(l: AppLabel): JSONObject = JSONObject()
        .put("id", l.id).put("name", l.name).put("description", l.description)
        .put("createdAt", l.createdAt).put("updatedAt", l.updatedAt)

    fun labelFrom(o: JSONObject): AppLabel = AppLabel(
        id = o.optString("id", newId()),
        name = o.optString("name"),
        description = o.optString("description", ""),
        createdAt = o.optLong("createdAt", System.currentTimeMillis()),
        updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
    )

    /**
     * labelName duplicates the human key so imports survive fresh installs
     * where UUIDs differ. Additive; old files lack it (fallback below).
     */
    fun assignmentTo(a: LabelAssignment, labelName: String = ""): JSONObject = JSONObject()
        .put("packageName", a.packageName).put("labelId", a.labelId)
        .put("labelName", labelName)
        .put("createdAt", a.createdAt)

    fun assignmentFrom(o: JSONObject): LabelAssignment = LabelAssignment(
        packageName = o.optString("packageName"),
        labelId = o.optString("labelId"),
        createdAt = o.optLong("createdAt", System.currentTimeMillis()),
        // Round-trip the name hint — assignmentTo writes it precisely so the
        // relink paths can re-resolve labelIds after a reinstall.
        labelName = o.optString("labelName")
    )

    fun componentsTo(set: Set<BackupComponent>): JSONArray =
        JSONArray(set.map { it.name })

    fun componentsFrom(arr: JSONArray?): Set<BackupComponent> {
        if (arr == null) return emptySet()
        return (0 until arr.length()).mapNotNull {
            try {
                BackupComponent.valueOf(arr.getString(it))
            } catch (_: Exception) {
                // Unknown future component: ignore for forward tolerance.
                null
            }
        }.toSet()
    }

    fun ruleTo(r: ConfigRule): JSONObject = JSONObject()
        .put("id", r.id).put("labelId", r.labelId).put("priority", r.priority)
        .put("components", componentsTo(r.components)).put("exclude", r.exclude)
        // Name rides along on EVERY persisted row (not just exports): the
        // reinstall self-heal relinks dangling rules by this exact name.
        .put("labelName", r.labelName)

    fun ruleTo(r: ConfigRule, labelName: String): JSONObject = JSONObject()
        .put("id", r.id).put("labelId", r.labelId).put("labelName", labelName)
        .put("priority", r.priority)
        .put("components", componentsTo(r.components)).put("exclude", r.exclude)

    fun ruleFrom(o: JSONObject): ConfigRule = ConfigRule(
        id = o.optString("id", newId()),
        labelId = o.optString("labelId"),
        priority = o.optInt("priority", 999),
        components = componentsFrom(o.optJSONArray("components")),
        exclude = o.optBoolean("exclude", false),
        labelName = o.optString("labelName", "")
    )

    fun configTo(c: BackupConfig): JSONObject = configTo(c, emptyMap())

    fun configTo(c: BackupConfig, labelNames: Map<String, String>): JSONObject = JSONObject()
        .put("id", c.id).put("name", c.name).put("description", c.description)
        .put("enabled", c.enabled)
        .put("operation", c.operation.name)
        .put("rules", JSONArray(c.rules.map { ruleTo(it, labelNames[it.labelId] ?: "") }))
        .put("fallback", c.fallback.name)
        .put("defaultComponents", componentsTo(c.defaultComponents))
        .put("restoreGraceful", c.restoreGraceful)
        .put("destinationId", c.destinationId)
        .put("deviceData", JSONArray(c.deviceData.map { it.name }))
        .put("folderPaths", JSONArray(c.folderPaths))
        .put("createdAt", c.createdAt).put("updatedAt", c.updatedAt)
        .put("listOrder", c.listOrder)

    @Suppress("USELESS_ELVIS")
    fun configFrom(o: JSONObject): BackupConfig {
        val rules = o.optJSONArray("rules")?.let { arr ->
            (0 until arr.length()).map { ruleFrom(arr.getJSONObject(it)) }
        } ?: emptyList()
        val fallback = try {
            FallbackPolicy.valueOf(o.optString("fallback", "SKIP"))
        } catch (_: Exception) {
            FallbackPolicy.SKIP
        }
        val operation = try {
            ConfigOperation.valueOf(o.optString("operation", "BACKUP"))
        } catch (_: Exception) {
            ConfigOperation.BACKUP
        }
        return BackupConfig(
            id = o.optString("id", newId()),
            name = o.optString("name"),
            description = o.optString("description", ""),
            enabled = o.optBoolean("enabled", true),
            operation = operation,
            rules = rules,
            fallback = fallback,
            defaultComponents = componentsFrom(o.optJSONArray("defaultComponents")),
            restoreGraceful = o.optBoolean("restoreGraceful", true),
            destinationId = o.optString("destinationId", AutomationStore.DEST_DEFAULT),
            deviceData = o.optJSONArray("deviceData")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    DeviceDataItem.fromName(arr.optString(i))
                }.toSet()
            } ?: emptySet(),
            folderPaths = o.optJSONArray("folderPaths")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val p = arr.optString(i)
                    if (BackupConfigFolders.isValidPath(p)) p else null
                }.distinct()
            } ?: emptyList(),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
            listOrder = o.optLong("listOrder", CONFIG_LIST_ORDER_DEFAULT)
        )
    }

    fun scheduleTo(s: AutomationSchedule): JSONObject = JSONObject()
        .put("id", s.id)
        // configIds is the source of truth (multi-config schedules); the
        // singular key stays for older readers (pre-1.6.18 primary files and
        // v1/v2 exports) that only know one config per schedule.
        .put("configId", s.configIds.firstOrNull() ?: "")
        .put("configIds", JSONArray(s.configIds))
        .put("enabled", s.enabled)
        .put("name", s.name)
        .put("type", s.type.name)
        .put("hour", s.hour).put("minute", s.minute)
        .put("triggerAtMillis", s.triggerAtMillis)
        .put("intervalMinutes", s.intervalMinutes)
        .put("weekdays", JSONArray(s.weekdays.toList()))
        .put("requiresCharging", s.requiresCharging)
        .put("requiresUnmetered", s.requiresUnmetered)
        .put("requiresBatteryNotLow", s.requiresBatteryNotLow)
        .put("createdAt", s.createdAt).put("updatedAt", s.updatedAt)

    fun scheduleFrom(o: JSONObject): AutomationSchedule {
        val type = try {
            ScheduleType.valueOf(o.optString("type", "DAILY"))
        } catch (_: Exception) {
            ScheduleType.DAILY
        }
        val days = o.optJSONArray("weekdays")?.let { arr ->
            (0 until arr.length()).mapNotNull {
                val d = arr.optInt(it, -1)
                if (d in 1..7) d else null
            }.toSet()
        } ?: emptySet()
        val configIds = o.optJSONArray("configIds")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optString(i).takeIf { it.isNotBlank() }
            }
        } ?: listOf(o.optString("configId")).filter { it.isNotBlank() }
        return AutomationSchedule(
            id = o.optString("id", newId()),
            configIds = configIds,
            name = o.optString("name", ""),
            enabled = o.optBoolean("enabled", true),
            type = type,
            hour = o.optInt("hour", 2),
            minute = o.optInt("minute", 0),
            triggerAtMillis = o.optLong("triggerAtMillis", 0L),
            intervalMinutes = o.optLong("intervalMinutes", 24 * 60),
            weekdays = days,
            requiresCharging = o.optBoolean("requiresCharging", false),
            requiresUnmetered = o.optBoolean("requiresUnmetered", false),
            requiresBatteryNotLow = o.optBoolean("requiresBatteryNotLow", false),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
        )
    }

    /**
     * Pure import remapping (unit-tested). UUIDs are meaningless across installs,
     * so resolution is name-first, id-fallback, drop-with-reason last.
     */
    data class ResolvedAssignment(
        val packageName: String,
        val labelId: String,
        val droppedReason: String? = null
    )

    fun resolveAssignment(
        o: JSONObject,
        nameToId: Map<String, String>,
        idSet: Set<String>
    ): ResolvedAssignment {
        val pkg = o.optString("packageName")
        val name = o.optString("labelName")
        val id = o.optString("labelId")
        if (pkg.isBlank()) return ResolvedAssignment("", "", "blank package")
        val byName = nameToId[name.lowercase()]
        if (byName != null) return ResolvedAssignment(pkg, byName)
        if (id.isNotEmpty() && id in idSet) return ResolvedAssignment(pkg, id)
        return ResolvedAssignment(pkg, "", "unknown label '${if (name.isNotEmpty()) name else id}'")
    }

    data class ResolvedRules(
        val rules: List<ConfigRule>,
        val missingLabels: List<String>
    )

    fun resolveRules(
        rules: List<ConfigRule>,
        labelNames: Map<String, String>,
        nameToId: Map<String, String>,
        idSet: Set<String>
    ): ResolvedRules {
        val kept = mutableListOf<ConfigRule>()
        val missing = mutableListOf<String>()
        rules.forEach { r ->
            val name = labelNames[r.labelId] ?: r.labelId
            val resolved = nameToId[name.lowercase()] ?: if (r.labelId in idSet) r.labelId else null
            if (resolved != null) {
                kept.add(r.copy(id = newId(), labelId = resolved))
            } else if (name !in missing) {
                missing.add(name)
            }
        }
        return ResolvedRules(kept, missing)
    }
}
