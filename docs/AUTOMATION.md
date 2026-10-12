# Automation: Labels + Custom Configurations — Architecture & Plan

Inspired by SwiftBackup (labels, per-app configs, schedules runnable via
`am start ... -e "cmd" "-s [ids]"`, capability matrix where only APK needs no
privilege). Adapted to THIS project (single-module Compose, no DB, no scheduler,
libsu root engine, components as Int IDs 1..5).

## Assessment: reuse / modify / new

**Reuse as-is (no engine changes):**
- Backup engine: `MigratorManager.runDynamicOperation` driven by per-app
  `activeComponents: Set<Int>` + `AppState.globalComponents`. Planner outputs
  component sets; runner maps them onto existing structures. Backup format untouched.
- Shell safety: `ShellQuote`, consent gates (`InstallerPolicy`), `FlashOps` validation.
- Persistence pattern: `SharedPreferences` + `org.json` (no Room — KSP removed;
  adding Room/WorkManager now would triple the dependency risk).

**Modify (thin):**
- `MainViewModel`: add automation entry points delegating to new runner (Phase 5).
- UI screens: labels row + configs screen (Phase 3/6), reusing Material3 patterns.

**New (`models/AutomationModels.kt`, `utils/AutomationStore.kt`,
`utils/BackupPlanner.kt`, `utils/AutomationCapability.kt`):**
- `BackupComponent` enum ↔ engine IDs: APK(1), PRIVATE_DATA(2), PERMISSIONS(3),
  MEDIA(4: media+obb+extdata), ANDROID_ID(5). No invented components.
- Labels (id/name/desc/ts), assignments (pkg↔labelId), configs, prioritized rules
  (labelId + components + exclude flag), plans, history, result/error enums,
  schema-v1 export JSON.

**Key adaptations (deviations from spec, with reasons):**
1. No Room: JSON-in-SharedPreferences store (`droidvault_automation_prefs`). Dataset is
   tiny (labels/configs); full testability without instrumentation.
2. Capabilities = ROOT/NORMAL only (ADB/Shizuku don't exist here; spec forbids
   inventing). This app requires root globally (NoRoot screen), so NORMAL → all
   components unavailable, explicitly reported — never silently skipped.
3. Consent gates are capabilities too: PERMISSIONS(3)/ANDROID_ID(5) require
   `allowPermRestore`/`allowSsaidRestore`, mirroring `effectiveComps` logic.
4. Scheduler deferred to Phase 7 (needs WorkManager dep + Doze testing). Phase 1
   ships manual Run + Dry Run + an intent entry-point design (SwiftBackup-style
   `-e cmd -s [ids]`) for Tasker/MacroDroid later.
5. Destinations: stable ID `"default"` → `savedPath` (internal/SD via folder
   picker). No fake cloud/NAS integrations.

## Determinism rules
- Highest-priority matching rule wins; one app backed up once.
- Exclusion rules beat backup rules (explicit `exclude=true` on the rule).
- No matching rule → fallback policy SKIP (default) or USE_DEFAULT (config default set).
- Dry run produces identical plan with `dryRun=true`, zero side effects.
- Backup runs refuse to start below 100 MB free at the destination
  (pre-run `df` gate → FAILED + STORAGE_FULL semantics; a `df` failure never
  blocks the run — backend ENOSPC stays the backstop). Retention note: the
  engine overwrites each app's folder in place (`Apps/<Type>/<label>`), so
  scheduled runs do NOT accumulate per-run copies — no keep-N needed; the
  growth vector is per-app size changes, bounded by app data size.

## Device-data restore idempotency contract (field-fixed 2026-09-16)
Restores MUST be idempotent. The original restore paths re-inserted every
archived row blindly, so each restore→backup cycle multiplied the data
(28 messages → 11,341 rows; the "Restoring Messages (3700)…" counter was
genuinely inserting tens of thousands of duplicate rows — the runaway
reported in the field). Now:

- **SMS**: dedup on `(address, date, body)` — pre-loaded from the provider,
  intra-archive duplicates collapse into the same set.
- **Call log**: dedup on `(number, date, duration, type)`.
- **MMS**: dedup on `(date, msg_box)` before the header insert.
- Progress honestly reports `"Restored N (M duplicates skipped)"`.

Consequences: restoring the same archive twice is a no-op; a polluted
archive (pre-fix) restores each message exactly once; archives regenerate
clean on the next backup. Run a BACKUP after repairing a duplicated
database so the stored archive matches the clean provider state.

## Folders backup contract (DeviceDataItem.FOLDERS, verified 2026-09-17)
User-picked folders ride the same device-data pipeline as Messages/Wi-Fi:

- **Sources**: one process-wide list (`BackupFolderStore`, prefs key
  `backup_folders`) shared by Settings → Backup Folders, the Migrate tab,
  and Automation config editors. Per-config selection lives in
  `BackupConfig.folderPaths` and round-trips through export/import.
- **Archive**: `Device/Folders.shift` — zapdos-compressed tar of
  `folders_manifest.json` + staged copies (`F0`, `F1`, … = folder contents,
  unique names so duplicate basenames cannot collide).
- **Manifest wins on restore**: each entry is copied back to its recorded
  absolute path. When the caller passes a folder subset (Migrate dialog
  checkboxes / a config's list), only those entries restore; an empty
  selection restores everything in the archive.
- **Android/data & Android/obb**: every path is read and written through
  the root shell — no MANAGE_EXTERNAL_STORAGE, works identically for
  Migrate-tab runs and headless scheduled runs.
- **Staging ownership**: staging dirs are created app-side (java.io), never
  by root mkdir — a root-owned dir broke the manifest write with EACCES
  (caught by the on-device E2E). Hardlink staging (`cp -al`) is O(1); real
  copy is the FUSE fallback.
- **Honesty**: FOLDERS backup with zero paths = SKIPPED with a reason;
  missing `Folders.shift` on restore = SKIPPED like every other item.

## SMS default-app handoff contract (verified on device 2026-09-16)
Releasing the default-SMS role revokes the role-granted SMS permissions and
the **platform SIGKILLs our process** (`ActivityManager: Killing …: permissions
revoked`). Every ordering and durability decision below flows from that fact:

1. **Record-before-handback.** History (`recordExecution`, now `commit()`),
   the terminal shade card, tombstone cleanup, and the per-config completion
   marker are all durably on disk BEFORE `handBackSmsRole()` is invoked. An
   async `apply()` can be lost to the kill ~100 ms later — field-verified.
2. **Pending marker is durable.** `pending_sms_restore` is written with
   `commit()` before the release; if the kill lands mid-release the startup
   heal (`healPendingSmsHandoff`) closes the loop: it verifies the role
   already returned, posts the confirmation card, and clears the marker.
3. **Capture validates installation.** A stale `sms_default_application`
   setting can name an UNINSTALLED package (de-Googled ROMs); both the runner
   and the Migrate flow share `sanitizeCapturedDefault`/`pickSmsFallback`
   (unit-tested in `SmsRoleDecisionTest`) so the role is never handed to a
   ghost package — AOSP Messaging is the always-present tail.
4. **Incoming SMS is persisted while we hold the role.** `SmsReceiver` parses
   the delivered PDUs and writes ONE `content://sms` row through a single
   root shell invocation (the su daemon completes it even if we die). MMS
   PDUs are parked under `/data/local/tmp` for the heal to salvage.
5. **A spent one-shot's retry still runs.** The worker disables the schedule
   when it fires and records a spent marker; a mid-run death + WorkManager
   retry would otherwise hit the enabled gate and vanish silently.
6. **Runs serialize and dedup.** `AutomationRunner.execute` holds a mutex;
   a scheduled run queued behind a just-completed run of the same config is
   recorded as SKIPPED (durable `config_done_<id>` marker + 60 s window).
7. **Interrupted runs leave a tombstone.** The worker writes a FAILED
   "interrupted mid-run" record at START; any terminal record of the config
   supersedes it. A silent death is therefore impossible — History always
   tells the truth.

## Phases
1. ✅ Foundation: models, store, planner, capability (this change) + unit tests.
2. ✅ Labels assignment/filter/bulk (Auto tab).
3. ✅ Config rules/priority/exclusions UI.
4. ✅ Planner + capability + Dry Run UI preview.
5. ✅ `AutomationRunner`: plan → existing `runDynamicOperation` + history + notifications.
6. ✅ Restore configs: a dedicated "Restore Configurations" menu (mirrors "Backup
   Configurations") rather than an operation toggle inside the backup editor.
   Restore configs are BackupConfigs with operation = RESTORE — same labels,
   rules, schedules, export/import and history. The planner is operation-aware:
   restore previews/runs feed `availableOnDisk` (the AllBackups Meta.txt
   inventory, exactly what the Migrate tab's restore list shows), skip apps
   with no backup on device, include uninstalled apps (restore = install), and
   with `restoreGraceful` (default on) restore only the parts the backup
   actually holds — the same graceful behavior as the Migrate tab. runConfig /
   runRestoreConfig firewall each other so a backup config can never be run
   from the restore menu and vice versa.
7. ✅ Scheduler (WorkManager): ONCE/DAILY/WEEKLY/INTERVAL + charging/unmetered/
   battery conditions + `ScheduleTriggerActivity` intent API.
   Trigger from a root shell (signature-permission gated since the P1 audit
   hardening — plain foreign apps are denied, uid 0 passes):
   `su -c am start -n com.phoenix.droidvault/.ScheduleTriggerActivity -e config_id <id>`
   Multi-config (since 1.6.18): pass `-e config_ids <id1,id2,...>` to schedule one
   run over several configurations in the given order; every listed id must exist
   and be enabled (the trigger fails loudly rather than running a partial set).
   Proven on-device 2026-09-09: trigger → `dumpsys jobscheduler` shows
   `ConfigRunWorker` → headless run → `App.shift` written → history recorded.
8. ✅ History/logs/notifications (viewer, per-run summaries, channels).
9. ✅ Import/export (v2 schema, schedules included, secrets never exported).
   Import resolves by NAME (UUIDs differ across installs); partial imports report
   skipped/failed items instead of silent 0,0,0.
10. ✅ ROM-survival: every run (automation AND manual Migrate) mirrors the
    full self-recovery set into the Droid Vault folder (root shell; scoped storage
    blocks direct writes): `automation-state.json` (configs + labels +
    schedules + master toggle), hash-gated `DroidVault.apk`, and the
    deployed Droid Vault backend. Snapshots run BEFORE the SMS handback —
    the platform kill at role release would otherwise cut them off. Fresh
    installs with a state file skip template seeding and offer one-tap restore
    (import re-arms schedules via `saveSchedule` and restores the master toggle).
10. ✅ Scheduler UX (master toggle, run-all, numbered cards, last-run, per-option guides).
11. ✅ Migrator integration (label chips on rows, label filter, `filterLabelId`).
12. ✅ Templates (APK / APK+DATA / FULL labels + configs seeded once on first run).
13. ✅ Perf pass: `AllInstalled` fetch cache (invalidated on backup/restore completion),
    `animateItem` removed from 300-row list, bouncy springs → NoBouncy/Medium,
    tab pill morph → 200 ms tween.
14. ✅ Cancellation: cooperative cancel flag (`/data/local/tmp/droidvault_cancel`,
    checked between apps AND polled mid-bundle with kill, never mid-archive) +
    `ACTION:CANCELLED` event + `runJob` handle + Cancel buttons + worker stop
    mapping. Dedicated control shell (never queues behind busy main shell) +
    pkill hammer for wedged runs. Backend self-abandons when the app process is
    gone (force-stop can't recall root shells — platform reality). History records
    CANCELLED honestly; finished apps are kept.
    Pre-touched flags are cleared at operation start (stale flags never cancel).
    Scheduled runs get the same affordance headlessly: the worker posts an
    ongoing notification with a Cancel action (`RunCancelReceiver`, not
    exported) that flips the same flag + kill hammer from the shade.
15. ✅ SMS default-app handoff transparency ("never surprised" contract):
    automated restores take the default-SMS role via root — the user now gets
    a HIGH-importance card BEFORE the takeover ("Droid Vault is temporarily
    your default SMS app"), and after the run either a confirmation card
    ("Default SMS app restored") or a persistent actionable card with a
    one-tap Confirm (`SmsHandbackReceiver`, not exported) when the handback
    could not be verified. Ordering is load-bearing (verified on device,
    2026-09-16): releasing the role revokes our role-granted SMS permissions
    and the PLATFORM KILLS the process ("Killing …: permissions revoked"),
    so `execute()` records history + settles the shade BEFORE calling
    `handBackSmsRole()`; the release itself is one root invocation that
    completes in the su daemon even if we die, and the startup
    `healPendingSmsHandoff()` confirms + clears any leftover marker. The
    missing-archive guard runs BEFORE the takeover: an all-archives-missing
    restore fails honestly (per-item SKIPPED reasons in history) without the
    role dance at all — the previous order turned that run into a process
    death with no history record.
16. ✅ Release hardening: `ScheduleTriggerActivity` now requires the
    signature-level `com.phoenix.droidvault.permission.TRIGGER_AUTOMATION`
    (root shell still works: uid 0 passes the platform check; foreign apps are
    denied). R8 keeps added for manifest-reflective classes (worker/receivers/
    activities). `versionCode` floored at 168000 (>= last GitHub CI build).
    Debug-signed `assembleRelease` prints a loud warning instead of silently
    shipping.
17. ✅ History cleaning: "Clear" in History & Transfer wipes all run summaries
    AND their per-app drill-down logs (`AutomationStore.clearHistory`) behind
    a confirm dialog; schedules/configs/labels and backups on disk are
    untouched. Schedules survive the wipe by design — the marker is cosmetic.
15. ✅ Per-run transparency: every execution persists a capped per-app log
    (`exec_apps_<id>` pref, max 200 records × components) alongside the summary;
    tapping a history card opens the drill-down (result + processed components +
    reason per app). PARTIAL_SUCCESS is now debuggable. Device-data items land
    in the same log as their own records (`deviceItem` marker) — a scheduled
    restore over an empty backup folder shows per-item SKIPPED with the exact
    archive name, never a false success (verified on device 2026-09-16:
    FAILED, 4 skipped, "no device-data backups found in the backup folder").
