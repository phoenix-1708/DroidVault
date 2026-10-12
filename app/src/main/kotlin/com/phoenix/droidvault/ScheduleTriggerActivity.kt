package com.phoenix.droidvault
import android.widget.Toast
import com.phoenix.droidvault.ui.safeToast

import android.app.Activity
import android.os.Bundle
import androidx.core.content.edit
import com.phoenix.droidvault.models.AutomationSchedule
import com.phoenix.droidvault.models.ScheduleType
import com.phoenix.droidvault.utils.AutomationStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Headless entry point for automation (root shell / Tasker-with-root / ADB):
 *
 *   su -c am start -n com.phoenix.droidvault/.ScheduleTriggerActivity \
 *     -e config_id <id> [--el delay_sec 60]
 *
 * Creates a one-time schedule (default: in 60 s) through the normal validated
 * path, so it is enqueued, constrained, executed, and recorded like any other.
 * The requested delay is used verbatim (only clamped to the documented
 * bounds) — scripters rely on exact timing for their own verification.
 * No UI; finishes immediately.
 *
 * Security model:
 * 1. Manifest gate: android:permission TRIGGER_AUTOMATION (signature-level).
 *    The platform checks the STARTING caller's uid against it, so foreign apps
 *    are denied before onCreate runs. The activity runs in our own process, so
 *    no in-process check could ever identify the caller — the manifest
 *    attribute is the only real gate, and the system enforces it.
 * 2. Root-shell callers keep working (`su -c am start ...`): uid 0 passes the
 *    platform component-permission check. Plain `adb shell am start` (uid 2000)
 *    and foreign apps without our signing key are denied by design — the
 *    root-shell command is the documented interface (docs/AUTOMATION.md).
 * 3. Abuse bounds remain: every requested config must exist and be enabled,
 *    delay clamped, one trigger per requested config set per minute (persisted).
 */
class ScheduleTriggerActivity : Activity() {

    companion object {
        private const val MIN_DELAY_SEC = 10L
        private const val MAX_DELAY_SEC = 24 * 60 * 60L
        private const val RATE_LIMIT_MS = 60 * 1000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // `config_ids` (comma-separated, runs in the given order) or the legacy
        // single-config `config_id` — both still documented in AUTOMATION.md.
        val configIds = intent.getStringExtra("config_ids")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
            .ifEmpty {
                listOfNotNull(intent.getStringExtra("config_id")?.trim()?.takeIf { it.isNotEmpty() })
            }
        if (configIds.isEmpty()) {
            toast("config_id missing")
            finish()
            return
        }
        // Verbatim delay (bounds-clamped only): WorkManager shortens any
        // remaining sub-2s window itself and otherwise never coalesces a
        // fresh one-shot, so the documented "fires delay_sec later" holds.
        val delaySec = intent.getLongExtra("delay_sec", 60).coerceIn(MIN_DELAY_SEC, MAX_DELAY_SEC)
        CoroutineScope(Dispatchers.IO).launch {
            val prefs = getSharedPreferences("droidvault_automation_prefs", MODE_PRIVATE)
            val rateKey = configIds.joinToString(",")
            val lastKey = "last_trigger_$rateKey"
            val now = System.currentTimeMillis()
            if (now - prefs.getLong(lastKey, 0L) < RATE_LIMIT_MS) {
                toast("rate limited: one trigger per minute per config set")
                finish()
                return@launch
            }
            // Strict on purpose: this is a scriptable interface, so a typo in
            // one id must not silently downgrade the trigger to a partial set.
            val configs = AutomationStore.listConfigs(this@ScheduleTriggerActivity)
            val invalid = configIds.filter { id -> configs.find { it.id == id }?.enabled != true }
            if (invalid.isNotEmpty()) {
                toast("unknown or disabled config(s): ${invalid.joinToString(",")}")
                finish()
                return@launch
            }
            val schedule = AutomationSchedule(
                id = AutomationStore.newId(),
                configIds = configIds,
                type = ScheduleType.ONCE,
                triggerAtMillis = System.currentTimeMillis() + delaySec * 1000
            )
            val res = AutomationStore.saveSchedule(this@ScheduleTriggerActivity, schedule)
            runOnUiThread {
                res.onSuccess {
                    getSharedPreferences("droidvault_automation_prefs", MODE_PRIVATE)
                        .edit()
                        .putLong(lastKey, System.currentTimeMillis())
                        .apply()
                    toast("Scheduled ${configIds.size} config(s) in ${delaySec}s")
                }.onFailure {
                    toast("Schedule failed: ${it.message}")
                }
                finish()
            }
        }
    }

    private fun toast(msg: String) {
        runOnUiThread { this.safeToast( msg, Toast.LENGTH_LONG) }
    }
}
