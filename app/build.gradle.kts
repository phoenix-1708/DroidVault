import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing, in priority order:
//   1. keystore.properties at the repo root (gitignored) — local builds
//   2. KEYSTORE_* environment variables — CI, where the key is a secret and
//      never touches the working tree
// Without either, release falls back to the debug key and the warning below
// makes that LOUD rather than producing a silently unsigned artifact.
//
// The environment path exists because a CI release cannot have a keystore on
// disk, and writing one to disk during a build is what puts the signing key
// where a later step — a test, a cache write, a crash dump — can pick it up.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val envStoreFile = System.getenv("KEYSTORE_FILE")
val hasReleaseKeystore =
    keystoreProps.getProperty("storeFile") != null || envStoreFile != null

android {
    namespace = "com.phoenix.droidvault"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.phoenix.droidvault"
        minSdk = 29
        targetSdk = 37
        // Backend engine APK-version gate (BackendInstaller): bump on every
        // release. MUST stay >= every build ever shipped; the local updater
        // compares this value with the installed version to decide whether
        // the launcher, Rust engine, and compression helper need re-deploying.
        // NOTE: keep this comment in sync with the actual updater — the old
        // text claimed a remote GitHub-tag comparison that never existed.
        // 200007: hardened DroidVault.sh (VALID_PKG gate on DO_BACKUP/
        // DO_RESTORE/READID, awk-based SSAID lookup, quoted job count,
        // printf '%b' format string).
        // 200008: in-app diagnostics logger (Settings: live view/export),
        // vault-index fast -printf + AllBackups cache + batched SSAID grep,
        // restore byte-progress via root du polling.
        // 200009: restore parts reflect actual backup contents (APK-only
        // restore no longer shows 5/5 with grayed parts), engine prunes
        // stale artifacts from narrower re-backups, verbose (V) logging.
        // 200010: app icons in App Labels assigned-cards list; device-data
        // item rings complete transitionally (no end-of-run burst).
        // 200012: whole-project UI/UX refinement. Semantic accent design system
        // (ui/theme/Accents.kt: DvRole palette, light+dark, contrast-locked by
        // AccentContrastTest), DvIconBadge replaces 13 duplicated primary-tinted
        // circle containers, bottom-nav Migrate label fixed at layout level
        // (icon above label, bar grows with font scale), About capability chips
        // given real background separation and readable light-theme icons.
        // No new features or claims; colours and layout only.
        // 200013: follow-up fixes to the S19 design system. About hero logo was an
        // untinted raster PNG and vanished on the light theme's surface; the
        // Info "i" control sat on surfaceContainerHigh, which equals the card
        // behind it and therefore had no edge in either theme; App Labels got
        // its own "Labels" section in the Automation tab; DvIconBadge's neutral
        // fallback no longer uses the invisible primary.copy(alpha = 0.15f)
        // treatment. Also: Gradle local build cache + heap tuning.
        // 200014: navigation state survival + predictive-back preview fidelity.
        // showSettings/showAbout (and the Settings sheet/field state) are now
        // rememberSaveable, so a system theme change no longer dumps the user
        // out of Settings/About onto a main tab; the predictive-back preview
        // layer receives the frozen overlay flags instead of hardcoded false,
        // so pulling back on About/Settings previews the real destination
        // chrome (no "main tab flash" on commit); MainActivity re-runs launch
        // effects (ZIP deep link) only on a real launch, not on config-driven
        // recreation; onNewIntent deep links unaffected.
        // 200015: predictive-back preview now renders the back DESTINATION.
        // 200014 forwarded the live showSettings/showAbout flags into the
        // frozen preview layer, so dragging back from About drew About behind
        // About (the 200013 build before it had hardcoded false, which drew the
        // Flash/Migrate/Tools/Auto tabs behind Settings). Back is strictly
        // inward here — About -> Settings -> tabs — so the preview is
        // showSettings = showAbout, showAbout = false. The About -> Settings
        // commit also became instant instead of a 220/160 ms cross-fade: the
        // gesture layer has already animated that move, and the fade replayed
        // About on top of the destination after the animation ended.
        // 200016: Messages backup/restore no longer takes the default-SMS role.
        // Reading and writing the SMS store required android.app.role.SMS;
        // handing the role back revoked the platform-granted READ_SMS /
        // WRITE_SMS app-ops and the platform killed the process, which is why
        // every Device Data restore of Messages ended with the app vanishing.
        // Root removes the need for the role: reads go through a root-made
        // snapshot of mmssms.db opened with the platform SQLite (field-verified
        // on Android 16), writes go back through TelephonyProvider as root
        // (measured ~41 ms/row), and a restore that cannot read the store now
        // REFUSES instead of running without dedupe. Scheduled automation runs
        // still take the role - removal there is tracked separately.
        // 200017: fix restore writing nothing. The generated insert script read
        // the row's date/type/flags from "$4".."$9" AFTER `set -- content insert`,
        // which REPLACES the positional parameters, so every row in a batch was
        // sent with --bind date:l:content://sms. `content insert` exits 0 even
        // when it rejects a row (it prints usage instead), so the run reported
        // "Restored N SMS" for messages that were never written - field-verified
        // with a 5-message restore that restored nothing. Arguments are now
        // copied into named variables before `set --`, and success is decided by
        // the CLI's OUTPUT rather than its exit code. buildScript takes a plain
        // SmsRow so the generator is unit-testable (SmsRestoreScriptTest).
        // 200018: fix restore dropping every message whose text contains a
        // colon. The `content` CLI parses a binding with
        // splitWithEscaping(arg, ':') and rejects the row unless that yields
        // exactly three pieces ("[ERROR] Binding not well formed"), so bodies
        // like "Your NVIDIA verification code is: 153856" and "[Uber] Your
        // code: 0569" were never inserted while the run reported success -
        // 18 of the 55 messages in the field archive carry a colon, which is
        // exactly the reported symptom (delete a couple of OTP texts, restore,
        // nothing comes back). Values are now escaped for that parser
        // (backslash doubled first, then colons), and a restore ends by
        // READING THE STORE BACK instead of trusting the CLI's exit code, so
        // the reported count is the number of messages the store holds.
        // The same release fixes a second rejection nobody could see: b64 of
        // an empty field is the empty string, and the shell DROPS an unquoted
        // empty argument, so a row whose service_center is NULL (every inbound
        // SMS) shifted its date_sent into --bind sub_id:i: and died with
        // "[ERROR] For input string". Those arguments are now quoted.
        // 200019: diagnostics for the dedupe, which is where a restore can look
        // like it worked and do nothing. logcat now carries the size of the
        // store signature set, every signature skipped as a duplicate, and the
        // processed/duplicates/staged split for the archive.
        // 200020: Migrate and Automation now behave the SAME way. The
        // automation runner no longer takes the default-SMS role for a
        // Messages restore — the root path made it redundant, and releasing
        // it still kills the process (the manual path dropped it in 200016),
        // so the pre-run card, the kill-prone release and the runner-side
        // pending marker are gone from the run path (the startup heal stays,
        // for cards an older build left behind). DeviceManager now reports
        // per-item failures to BOTH callers through onItemFailure: a Messages
        // restore that refuses (store unreadable) or writes only part of the
        // archive, and a Messages backup that emits no `sms` array, are
        // recorded as FAILED in automation history and named in the Migrate
        // tab's completion message instead of reading as success. The refusal
        // path also drains the JSON array it never read — leaving the reader
        // parked on it aborted the whole run with a parse error.
        // 200021: deep audit of BOTH device-data pipelines (they share
        // DeviceManager.runOperation). Three ways to report success for work
        // that never happened, not hitting the two tabs equally:
        //  - a run refused by the busy gate just RETURNED, so Migrate printed
        //    "Restore Complete!" and automation recorded every selected item
        //    as SUCCESS for a run that did nothing. It now throws
        //    DeviceDataRefused (title + detail — the shape the app phase's
        //    refusal already uses) and both callers report it.
        //  - a restore whose archive extracted to nothing (missing/corrupt
        //    .shift) was judged by the shell's exit code, but tar|zapdos exits
        //    0 on EMPTY input (verified on-device) — so a Messages restore
        //    reported clean while writing nothing. Every item now checks the
        //    output it needs (messages.json, calls.json, Contacts.vcf, Wi-Fi/,
        //    ...) and reports one shared reason string through onItemFailure
        //    (AutomationRunner.missingRestoreReason), which both tabs already
        //    consume: Migrate folds it into its completion message, automation
        //    records the item FAILED.
        //  - the engine freshness gate lived only on the automation side and
        //    the Migrate APP phase; a manual device-data run could pack or
        //    extract with a stale/missing engine. It now guards the shared
        //    pipeline, so all three run paths fail closed identically.
        // Backups check each archive write (packArchive, zapdos's status —
        // non-zero on ENOSPC / missing binary), Folders reports its own archive
        // failures instead of notifying only, Erase refuses while a run holds
        // the gate (it deletes the very files automation reads), and the erase
        // dialog refreshes the backup list its Folders row is derived from.
        // 200022: dashboard-first adaptive Compose home and the Rust root
        // engine. Gradle cross-compiles Arm64/Arm32 from engine/ with the
        // configured NDK, packages the executables as native assets, and the
        // installer deploys and SHA-256 verifies both engine and launcher.
        // 200023: deduplicated pager tab animations, stopped progress events
        // from invalidating the shell, fixed same-path vault confirmation on
        // first install, and re-armed preserved schedules after clean reinstall.
        // 200024: confirmed root-level exit, faster back transitions, non-animated
        // app-list rows, and return-home behavior for successful dashboard actions.
        // 200025: retry root detection on return from root manager, remove duplicate
        // predictive-back shells, and request notification permission explicitly.
        // 200026: gap audit of the 200022 Rust engine rework. One CRITICAL fix:
        // tar matches an UNANCHORED --exclude=lib / cache / code_cache /
        // Backup_* / .ota / no_backup against any trailing path component at
        // any depth, so every nested directory with one of those names was
        // silently dropped from each app's data archive while Meta.txt still
        // recorded the full size — unrecoverable backup loss with no visible
        // symptom. Patterns are anchored to the member prefix again (port of
        // the legacy launcher's BUNDAPP). Also: restore disables a freshly
        // installed app before overwriting its data dir (legacy did; without
        // it the app wrote into /data/data concurrently with the extract); a
        // per-app data-bundle failure is a PART event again instead of a
        // terminal ACTION:ERROR that failed the whole run over one app; a
        // failed app no longer emits BACKUP_DONE alongside ACTION:ERROR;
        // non-UTF-8 argv no longer aborts the privileged process (panic=abort);
        // '..' is rejected in the vault path; --install-apps gates GLOBAL_DONE
        // on cancelled() not just the flag file; the PID journal is rewritten
        // atomically so a concurrent --kill-jobs cannot read a torn file;
        // archive_valid no longer leaks a zapdos child; partition images are
        // checksum-verified before dd. Kotlin: the run-gate refusal and the
        // stale-engine abort release ProgressTracker ownership (they returned
        // ahead of the finally that ends the run, permanently wedging the
        // Migrate tab and leaking the byte poller); checkRootAccess runs its
        // heal + completion hook on DENIAL too (a denial left Settings'
        // "Re-grant" permanently disabled); batch install reports the shell's
        // own exit status instead of "Installation Complete / Installed 0";
        // the Tools progress panel reads live state so Debloat/Systemize are
        // not stuck at 0%; exit confirmation now also covers a submenu run;
        // vault move, folder-archive edit and device-backup delete refuse
        // while the gate is held; the delete toast reports real failures.
        // x86_64 now builds and packages the engine too — it shipped
        // libzapdos.so with no engine, so every emulator operation failed
        // closed. MainActivity is singleTop so a deep link no longer creates a
        // second instance with a second ViewModel and root probe.
        // 200028: responsiveness pass. Correctness first:
        //  - MigratorManager's app-list caches were keyed on `type` alone, so a
        //    different vault path or mode was served the previous answer. After
        //    a vault move, refreshPathBoundState() re-read everything while the
        //    cache still described the old folder, and onBasePathChanged()
        //    emptied the restore list then refilled it from that stale cache.
        //    Keyed on path+type+mode now, and cleared on both paths.
        //  - bulkAssign/bulkUnassign called assign()/unassign() per package, and
        //    each of those rewrote the whole primary state file through a root
        //    round trip (N full re-serializations). One resolve + one persist.
        // Performance:
        //  - the byte-progress poller forked a root `du` PER PART every 500 ms;
        //    libsu serialises jobs per session, so each fork also delayed the
        //    backup's own stdout. Now one `du` per tick.
        //  - addBackupFolder/removeBackupFolder did prefs fsync + two blocking
        //    root execs on the UI thread (ANR-class on a cold su). Off-thread,
        //    result delivered by callback.
        //  - refreshVaultContents ran 5+ forks and hashed ~1.7 MB on every Home
        //    entry AND every Settings open; now one batched stat plus a 30 s TTL
        //    with an in-flight guard (forced refresh after a repair/move).
        //  - Migrate/Settings/About collected the RAW uiState, so every engine
        //    tick recomposed the whole app list / settings tree / About screen.
        //    They now use the deduplicated shellUiState; only the Migrate
        //    progress panel subscribes to the raw flow.
        //  - ProcessingScreen's 1 Hz clock invalidated the whole screen; scoped
        //    into RunningBody, its only reader.
        //  - two infinite transitions (logo pulse, refresh spinner) kept the
        //    frame clock hot for the app's whole lifetime while idle.
        //  - removed an animation restart: the progress icon was keyed on a
        //    value that flipped as the step text changed, restarting a 400 ms
        //    scale+fade mid-run.
        //  - per-recomposition allocations: constant chip maps, ColorFilter per
        //    row, memoised finished-apps list, keyed history list.
        //  - ShellEngine compiled a Regex per backend line and DroidVaultLogger
        //    built a SimpleDateFormat per line; both are hot on a 63-app run.
        // 200029: Home-first navigation and an editable quick-access row.
        //  - The app now ALWAYS opens on Home. It restored `last_selected_tab_v2`
        //    before, so a launch after a background schedule dropped the user
        //    into whatever submenu the last run left behind — mid-flow, with no
        //    indication of where they were. The persisted key is kept (a
        //    mid-session restore still works) but is no longer a launch target.
        //  - Declining the exit confirmation returns to Home instead of leaving
        //    the user parked on whichever tab raised it.
        //  - "Quick access" is no longer two hard-coded cards. The Home dashboard
        //    carries a user-editable, ordered set of 15 destinations covering
        //    app backup/restore/manage/debloat/systemize, device-data both
        //    directions, partition tools, utilities, and five Automation
        //    submenus. Persisted as a comma-joined id list in prefs; unknown ids
        //    are dropped rather than throwing, so a shortcut retired in a later
        //    build degrades to "not shown". The last remaining shortcut cannot
        //    be removed, so the row is never a dead zone.
        //  - Device-data and Automation shortcuts route across tabs via an
        //    explicit one-shot request consumed exactly once, so a dismissed
        //    dialog does not reappear on the next visit.
        // 200030: dashboard rework.
        //  - The full-width "Root access connected" card and the two big
        //    Back up apps / Restore buttons are replaced by one compact Overview
        //    card in the style of a storage/root status panel: free space of
        //    total with a used bar and percentage, then root status with the
        //    manager's own version string (`magisk -v` / `magisk -V`, i.e.
        //    "30.7.5:MAGISK:R (30750)"). Storage comes from StatFs on the VAULT
        //    path — one syscall instead of a `df` root fork, and it reports the
        //    filesystem backups actually land on. The bar only turns red at >=90%
        //    used, so a restore's temporary writes do not flash a false alarm.
        //    Non-Magisk managers are detected by their marker directory and
        //    reported honestly rather than borrowing Magisk's format.
        //    The two primary actions were NOT dropped: Back up apps and Restore
        //    apps are now in the default quick-access row, since a backup app
        //    whose front door cannot start a backup has regressed.
        //  - Quick-access cards reorder by long-press drag, on both axes. The
        //    dragged card follows the finger, lifts and scales; every other card
        //    springs to the slot it would occupy once the drop lands, so the row
        //    parts to make room. The reorder is committed to the store only on
        //    release, so a cancelled gesture leaves the persisted order alone.
        //    Column geometry is measured from the real row width, so a tablet or
        //    rail window reorders correctly instead of assuming phone columns.
        //  - The last card no longer hides under the floating navigation bar.
        //    That bar is an overlay aligned to the bottom of the Box, not a
        //    Scaffold bottomBar, so scrolling content passed underneath it; the
        //    page now reserves the bar's height plus the system gesture inset.
        // 200031: per-app component page and back navigation.
        //  - Backup Apps and Restore Apps no longer ask for the app and its parts
        //    in two places. Tapping an app row opens a page listing every part
        //    with a checkbox, its real size and its total; the trailing checkbox
        //    still toggles selection, so bulk selection is one tap and precise
        //    per-app choice is one page. The two used to fight over a single tap
        //    target and no screen showed what one app would actually get.
        //  - A part that cannot be used says WHY: "Not in this backup" and
        //    "Turn on in Settings → Restore options" are different problems with
        //    different fixes, so they are different messages rather than a
        //    silently disabled row.
        //  - The component chips are now the DEFAULT for newly selected apps
        //    instead of a live filter over the ticked ones. Changing a chip used
        //    to overwrite every per-app choice silently; an explicit "Apply to
        //    all" does that now, and is lit only when they already agree.
        //  - Selecting an app materialises its part set instead of leaving it
        //    null. Null meant "fall back to the global default" in every
        //    consumer, so a per-app edit was invisible to the size estimate and
        //    could be discarded by any code path that re-read the default.
        //  - The run button's "N Selected" and "Need:" figures used the raw
        //    ticked-row count while the run filtered system apps out, so a
        //    system app ticked with the filter off produced a live button that
        //    quietly processed one app — and a free-space guard that passed on a
        //    selection that then failed for space. Both now use the runnable set.
        //  - Back is three steps instead of one: it pops submenus, then returns
        //    to Home from any other top-level tab, and only then asks to exit.
        //    Previously the first Back from any tab popped the quit dialog, so
        //    leaving the app meant confirming a quit the user never asked for.
        //  - The component page closes on relist if its app vanished, and on
        //    tab change, instead of lingering as an overlay over another tab.
        // 200032: transition fix and a single-space component picker.
        //  - Switching tabs no longer flashes a tab the user never visited. A
        //    HorizontalPager animates a multi-page jump by scrolling THROUGH
        //    every page between, so Back/Tools -> Home swept past Backups and
        //    FLASH mid-transition. Adjacent moves (and every swipe) still
        //    animate; a multi-page jump now cuts, because those only ever come
        //    from the nav bar, Back or a Home shortcut, never from a gesture.
        //  - The five component options plus their action lived in a horizontal
        //    scroller, so on a phone the last three were only reachable by
        //    swiping a row with nothing to suggest there was more. They are now
        //    one wrapping card, every part visible at once, each pill keeping
        //    its name. Selected state shows a check as well as a colour.
        //  - An "i" button on the Backup Apps and Restore Apps screens explains
        //    the whole model: what each part contains, that the pills are the
        //    default for FUTURE selections, that Apply all is the one explicit
        //    way to push it onto apps already chosen, that per-app choices live
        //    on the app's own page, and that Permissions and Android ID are
        //    consent-gated. None of that was discoverable before.
        // 3.0.0 — stable.
//  Reliability fixes carried forward from 2.2.x:
//   - Restore Apps listed every INSTALLED app on a vault with no backups. The
//     app list is shared by the Auto tab and the Migrate submenus; an empty
//     (and correct) backup list is indistinguishable from "not loaded yet", so
//     Auto's launch effect and its three-attempt self-heal replaced it. Restore
//     therefore offered to restore archives that did not exist. ensureInstalledApps
//     now refuses while a vault-sourced mode owns the list.
//   - Switching tabs flashed a tab the user never visited: a HorizontalPager
//     animates a multi-page jump by scrolling through every page between, so
//     Back/Tools -> Home swept past Backups and Flash. Adjacent moves and swipes
//     still animate; a multi-page jump now cuts.
//   - The nav bar is an overlay, not a Scaffold bottomBar, so nothing reserved
//     its space and each screen guessed a hard-coded clearance. The shell now
//     MEASURES the real bar (including the gesture inset) and pads every page
//     once. Migrate's "N Selected / Backup Apps" footer had been sitting
//     underneath it entirely.
//   - Picking an app and picking what to back up were two decisions in two
//     places, and the component chips rendered as five identical UNLABELLED
//     circles. Tapping an app now opens a page listing every part with its own
//     size, total, and a reason when a part cannot be used.
//   - The run button's count and free-space guard described a different
//     selection than the run: raw ticked rows versus a run that filters system
//     apps out, so a system app ticked with the filter off produced a live
//     button reading "3 Selected" that processed one app.
//   - Changing a component chip silently discarded per-app choices. The chips
//     are now the default for FUTURE selections; "Apply all" is the one
//     explicit way to push it onto apps already chosen.
//   - Five more chip rows (label filters, schedule configurations, schedule
//     type, weekday picker, label assignment) were unbounded horizontal
//     scrollers, so the items past the edge were unreachable with no hint that
//     anything was there. All now wrap.
//   - The selection banner read "Apps already backed up with previous
//     components" on every selection, including for apps never backed up.
//   - The size estimate existed in three copies; all share one function.
//   - Empty Restore/Manage lists said "Pull down to refresh", sending users to
//     retry something that cannot succeed when the vault holds no backups.
// 3.1.0 — archive verification.
        //  A backup that cannot be read back is worse than no backup, because the
        //  user only finds out at restore time — after a wipe or a ROM flash. The
        //  engine has had `archive_valid` all along but nothing ever called it.
        //  - New engine verbs `--verify-apps` and `--verify-device` walk the vault
        //    and read every archive back through zapdos + tar, reporting per app
        //    and a summary. It costs seconds, not the minutes a re-backup would.
        //  - "Verify backups" on the Manage screen, with a per-app report. A bare
        //    pass/fail is useless when the remedy (re-backup those apps) is
        //    per-app.
        //  - A restore REFUSES to start when a selected app's archive is known to
        //    be unreadable, naming the damaged apps. Only a conclusive corrupt
        //    result blocks: an empty vault, a cancelled run or an engine that
        //    would not start all return "unknown", and treating that as "damaged"
        //    would make Restore permanently dead on a fresh install.
        //  - Cancelling a verify stops it and says the result is incomplete,
        //    rather than reporting a partial pass.
// 3.5.0 — Migrate/Automation linkage audit, backup health on Home, cross-device
// transfer. NOT YET DEVICE-VERIFIED (the reference device went unreachable).
//  - L-01: a scheduled restore silently dropped Permissions and Android ID even
//    with consent granted. buildState never set the consent flags, so they took
//    their false defaults and runDynamicOperation's second gate cancelled out the
//    planner's correct decision. Not a permission hole — fail-closed is safe —
//    but a silent failure reporting success for work that never happened.
//  - L-02: scheduled restores had no archive-integrity gate. The rule lived only
//    in the manual Migrate flow; automation checked the archive FILE existed and
//    never that it could be READ, so a known-damaged backup was still restored
//    from, headlessly. Now a shared, tested restoreBlockReason used by both.
//  - Backup health on Home. Reports the last verification and offers a check; it
//    never verifies on entry (that reads every archive). Unknown is a real state:
//    null, cancelled and errored results are never shown as healthy.
//  - Cross-device transfer as one .dvbackup.tar.gz via Downloads — no INTERNET
//    permission by design. Carries Apps and Device, never the per-install
//    mirrors. Export stages then moves; import extracts to staging and copies
//    per directory, never over the live vault. KEEP_DESTINATION is the default.
//  - X-10: the verifier reported EVERY app as damaged because it ran the
//    plain-text Permissions.txt through the archive reader. The new L-02 gate
//    reads that report, so it would have refused every restore.
//  - X-11: export stored ABSOLUTE tar members, so import extracted to
//    staging/<absolute path> and would find no apps.
//  - X-12: the SAF copy was renamed "transfer-import", dropping the extension,
//    so the importer's own looksLikeTransferFile guard rejected the user's file.
//  - Device-verified: backup health in all three states, and a full transfer
//    round trip that kept an existing archive rather than clobbering it.
//  - Device-verified: L-01 (Permissions survives into the engine seeds with
//    consent granted) and L-02 (a truncated App.shift is refused with the gate's
//    own message, the app is not reinstalled). Fired via the Auto tab's Run
//    restore, which is the scheduler's AutomationRunner.execute core.
//  - X-13 PART-FIXED: the SSAID store is ABX ("ABX\0", not UTF-8) on Android
//    14+, so the literal package="..." marker never matched and NO backup ever
//    recorded an SSAID. read_ssaid now decodes with /system/bin/abx2xml and
//    parses attribute-order-independently (Android 16 emits value BEFORE
//    package). Verified on device: a backup now records
//    SSAID=5a8123ff4de50d3c, matching abx2xml exactly.
//  - write_ssaid was worse than ineffective — it read the binary file as text
//    and would have rewritten a SYSTEM settings file as text. It only avoided
//    corrupting anything because the bytes are invalid UTF-8. Now decodes,
//    patches, re-encodes with xml2abx, and swaps in only a complete file.
//    Round trip proven safe on the live file (28 settings, system:system 600).
//    The engine's own SSAID restore is NOT yet observed on device.
//  - X-14 OPEN: a restore skipped the UNINSTALLED app that had a backup and
//    re-restored the installed one instead — backwards for restore, whose whole
//    purpose is reinstalling. Needs its own investigation.
//  - G-01: an inherited Gradle configuration cache made builds report ANOTHER
//    directory's warnings and hide 3 of ours for a whole session. --rerun-tasks
//    and --no-build-cache do not clear it; only deleting
//    .gradle/configuration-cache does. Always confirm a warning's path names
//    this tree.
//  - G-03/G-04/G-06: 4 dead functions removed (3 theme aliases, openUriSafely),
//    3 deprecated icons migrated to their AutoMirrored forms, 2 unnecessary
//    safe-calls and 1 always-true condition removed.
//  - G-02/G-07: fully severed from the pre-rename ROM-Shifter tree. Deleted every
//    build artefact carrying that path and DISABLED the Gradle configuration
//    cache, which had survived the rename and kept resolving source roots to the
//    old directory — green builds reporting on the wrong files. The local build
//    cache is content-addressed and is what keeps release builds fast, so it is
//    untouched.
//  - 38 new unit tests; 434 Kotlin + 27 Rust total.
// 3.4.1 — fresh-install automation recovery, made reliable.// 3.4.1 — fresh-install automation recovery, made reliable.
//  A follow-up to the "Automation data restored" report. It was never random:
//  recovery ran ONLY when the Auto tab bound, so a fresh install sitting on the
//  onboarding carousel recovered nothing at all — verified, the automation prefs
//  file did not exist after 45 seconds.
//  - Recovery now also runs from MainViewModel.init, where the saved path is
//    already resolved, so it does not depend on navigation.
//  - The notice travels on a StateFlow the screen collects, replacing a one-shot
//    LaunchedEffect(Unit) that raced the work producing it (measured: screen
//    composed at T+0.4s, store published at T+1.2s).
//  - Corrects a wrong earlier finding: "recovery never fires" was an artefact of
//    a diagnostic whose grep could not match the `_v1` prefs key suffixes.
//    Recovery had been working; the NOTICE was the unreliable part.
// 3.4.0 — field-report fix batch (7 reports, all reproduced and fixed).
//  - CRASH: `Toast.makeText` from a Dispatchers.IO worker throws NPE on
//    Android 11+ ("Can't toast on a thread that has not called
//    Looper.prepare()"). It fired on the undo path, and because the work is
//    durable the app came back with the undo applied — reading as "it worked but
//    it also crashed". The same crash dropped navigation state, which is why
//    Manage sometimes jumped to Home after a delete. All 22 call sites now go
//    through `Context.safeToast`, which posts to the main Looper.
//  - Manage no longer returns to the overview after a delete: the destination
//    of a deletion is the screen showing how to undo it, and the trash card
//    holding the just-deleted backups lives there.
//  - The trash names what it holds. It said "1 app" with no name, and two
//    identical rows were indistinguishable.
//  - The trash dialog closes itself when the last batch is purged instead of
//    leaving an empty explainer popup behind.
//  - "No external media" is now a by-design skip. Every app without an
//    /data/media directory reported "bundle failed" in red on otherwise
//    successful runs, because "nothing to pack" and "packing failed" shared one
//    branch.
//  - A clean engine exit is no longer reported as an unqualified success. A run
//    with failed parts says "Partially completed" and names the reason.
//  - Automation recovery no longer fires on deliberately emptied data. The gate
//    was "cache has no rows", true both for a fresh install and for a user who
//    deleted everything; the second case resurrected their deletions from the
//    vault file and announced it as a recovery. Gated on "never written" now.
//  - The trash card now refreshes right after a delete, so "Review" appears
//    immediately instead of only after re-entering Manage.
//  - 15 new unit tests (9 Rust), 395 Kotlin + 18 Rust total.
// 3.3.0 — incremental backup: skip what did not change.
//  The Rust engine already skipped a part whose recorded size still matched the
//  live size, but that was invisible from the UI and the size estimate ignored
//  it entirely: a nightly re-backup of 63 unchanged apps claimed "Need: 38 GB"
//  and then wrote almost nothing.
//  - "Need:" now counts only the parts a run will actually re-write, so the
//    figure matches what lands on disk.
//  - A "Changed only" chip selects just the apps whose backup would change,
//    which is what a nightly or post-ROM-flash run actually wants.
//  - Nothing-recorded is always treated as CHANGED. Claiming a part is
//    unchanged when it is not would silently not back data up, so every
//    "we cannot tell" case resolves to writing it again.
//  - Permissions and Android ID are always rewritten: both are single small
//    files read fresh from the system, and skipping them risks restoring a
//    stale snapshot.
//  - Fixed an operator-precedence bug in the new estimate that silently dropped
//    the Android ID term whenever permissions were also selected.
// 3.2.0 — undo for destructive operations.
        //  `rm -rf` on a backup archive was the one action here with no recovery
        //  path: everything else can be re-derived from the device, but a deleted
        //  backup is gone until the next full backup of every app.
        //  - Manage's "Delete Apps" now RENAMES archives into <vault>/.trash
        //    instead of erasing them. A rename on the same filesystem is
        //    instant and frees the same space, so reversibility costs nothing.
        //  - A "Recently deleted" card in Manage lists what can be put back, with
        //    per-batch size and age.
        //  - Emptying a batch is the one irreversible action left, and it asks
        //    for confirmation naming what will be lost. A trash that can never be
        //    emptied would just be a slower leak.
        //  - Batches self-purge after 7 days of AGE. They deliberately do NOT
        //    self-purge at a batch COUNT: deleting an 11th app used to erase the
        //    first one, typically seconds after it was quarantined, while every
        //    surface still advertised "recoverable for 7 days".
        //    A batch cap belongs on the explicit "Empty trash", where the
        //    eviction is what the user asked for.
        //  - If NOTHING could be moved, the run reports failure. Claiming a
        //    deletion that did not happen is the exact failure class this app has
        //    already shipped once, in deleteDeviceBackups.
        //  - The manifest is written then renamed, so a kill mid-write cannot
        //    orphan every quarantined archive.
        // 3.5.4 (300009) — real percentage progress for Export and Import.
        //  - The transfer progress is real measured bytes, not a timer: export
        //    counts the staging file, import counts each app's actual size.
        //    Bar caps at 99 until the command exits, never regresses, and an
        //    unknown total reads 0 rather than 100.
        //  - Three bugs found by watching the bar on the device, all of which
        //    had produced a plausible-looking 0: Kotlin's split("\s+") splits on
        //    LITERAL text not regex; libsu serialises commands per session so a
        //    shell stat queued behind the running tar; and a root-created staging
        //    file carries a SELinux label the app cannot read.
        // 3.5.3 (300008) — two field-reported bugs, both about silence.
        //  - Export set no running state, took no gate and wrote to no progress
        //    surface, so packing a ~31 GB vault looked like a dead button; and it
        //    staged into /data/local/tmp with no space check, growing to 33.5 GB
        //    and failing on a disk with ~31 GB free. Now gated on free space,
        //    staged app-private, and visibly running.
        //  - The two delete paths READ the run gate but never held it, so the
        //    trash index read-modify-write could race: two concurrent deletes,
        //    second writer drops the first batch's entry, archives stranded in
        //    .trash with no manifest row and no way back.
        //  - "Working in another area" now names the holder; Export/Import/Erase
        //    chips reflect the run state instead of a hard-coded false.
        // 3.5.2 (300007) — stability, part two. A narrower re-backup no longer
        // destroys what a wider one saved, plus a beginner pass over the UI.
        // The full 3.5.2 note continues below.
        // 3.5.2 detail — a narrower re-backup no longer destroys what a wider
        // one saved. The engine used to unlink the archives the current part
        // selection excluded (Data.shift, Media.shift, Permissions.txt, ...), so
        // unticking a part and backing up again silently deleted data that might
        // have been the only copy anywhere — with no confirmation, no quarantine
        // and no undo, and unattended whenever a scheduled rule listed fewer
        // parts than the previous manual run. Excluded parts are now KEPT and
        // reported as kept; deleting a backup stays a deliberate act from Manage
        // backups, where it goes through the trash.
        // 3.5.1 (300006) — stability release. Audit pass across crash paths,
        // state/honesty and data safety. The fixes that matter most are all the
        // same class: the app telling the user something untrue about their
        // backups.  - A CANCELLED or FAILED run marked every queued app DONE, so
        //    cancelling a 63-app backup after 5 rendered 63 green ticks.
        //  - A failed run drew a green "Success" tick: the check substring-matched
        //    "Error", and the real strings are "Operation Failed!"/"Deletion Failed!".
        //  - Deleting backups claimed "N GB freed" for what is a rename.
        //  - Home's health verdict said "all backups readable" when the check only
        //    ever runs --verify-apps; messages, calls and folders are not covered.
        //  - Scheduled-run history recorded SUCCESS for every app from the PLAN,
        //    never from the engine, so an unattended partial failure was durable.
        //  - The restore notice promised "everything is back" while recovery may
        //    have deliberately left automation disarmed, and omitted assignments.
        //  - De-Systemize crashed the process for any non-Latin app label.
        //  - Incoming MMS were never restored (.toUri() precedence) while the
        //    restore reported success.
        //  - Folders.shift was erased with `rm -f` and no dialog; the same archive
        //    is quarantined by the other delete path.
        //  - Device-data archives were written in place over the only good copy.
        //  - Tree/document URI callbacks NPE'd on platform types, incl. one that
        //    runs in the ViewModel field initialiser (every launch).
        //  - Duplicate LazyColumn keys crashed on unresolvable URIs and on a
        //    label-changed archive; an index-based lazy list crashed on Clear.
        //  - Deleting the last Home shortcut failed silently.
        //  - Onboarding led with "The Ultimate Android app..." and never said the
        //    archives are unencrypted.
        // 3.5.5: the transfer pipeline runs natively. Import never completed on
        // any real file (bare 2 s join, then rm -rf of live staging), carried only
        // Apps+Device (no configurations, no reinstall kit), and died on cache
        // eviction, a 1024x unit slip ("3.8 TB"), and an unselectable picker.
        // Progress was part-counts wearing byte labels ("24.6 GB done" over an
        // untouched media copy), a frozen copy label, a 0%-at-0-B read phase, an
        // ETA that only grew, and an export whose cross-filesystem mv ran minutes
        // past 100% to 97% disk. engine/src/transfer.rs now packs, extracts with
        // measured percentages, and places by rename; Cancel exists in every
        // phase. Verified: 107-app import, export cancel, 4.2 GB round trip.
        // 1.0.3 (300018) — a finding says how old it is, and the two records of
        // one digest agree again.
        //  - Home's health row states how long a finding has stood ("Found 3 days
        //    ago") and which run has been over the vault since ("an app backup has
        //    run since, so these may already be replaced"), so a finding that a
        //    later run may already have repaired no longer reads as something that
        //    just happened. The age belongs to the FINDING: it is carried forward
        //    per archive while that archive keeps failing, and starts now when it
        //    fails for the first time.
        //  - The check dialog states the same age, and a result read back from
        //    disk says which launch observed it.
        //  - A `.sha256` edited outside the app is rewritten from the bytes the
        //    index read back — the skip path already has the digest — so
        //    `sha256sum -c` stops failing on an archive this app calls fine. The
        //    run reports how many records it repaired, and the dialog says so.
        // 1.0.2 (300017) — the verdict outlives the process.
        //  - A check result lived only in memory, so a restart — or a low-memory
        //    kill in the background — took a known-damaged archive with it: the
        //    Home row went back to "not checked yet" and the pre-restore gate had
        //    nothing to refuse with. The last conclusive report is written down in
        //    app-private prefs and read back at startup, matched to the vault
        //    folder it was taken in.
        //  - What comes back is marked as read from disk, and a verdict that this
        //    launch never observed does not show good news: the row reads "Checked
        //    before this launch" and offers the check again. A FINDING is not
        //    withdrawn — it still reads as damaged and still blocks the restore,
        //    which is the whole reason to keep it.
        //  - A run that observed nothing (cancelled, errored, empty vault) no
        //    longer replaces what is stored, so a failed check cannot quietly
        //    clear a finding the user has already been shown.
        //  - The app-archives and device-archives halves of one verdict are merged
        //    by one tested function now. The hand-written merge dropped the device
        //    half's digest mismatches, byte count, read/skipped counts and its
        //    cancelled flag, so a device archive whose bytes had changed was
        //    reported as healthy.
        //  - A check no longer withdraws the finding while it is still measuring
        //    it. `verifyVault` held a null verdict for its whole run, and that
        //    value is the only thing the pre-restore gate reads — so a
        //    confirmed-damaged archive was restorable by the very control the
        //    user had just pressed to confirm it, for as long as the check took.
        //  - A verdict is re-read when the vault folder changes (move vault,
        //    auto-detect). The stored record was path-matched, but the in-memory
        //    copy was not, so the card and both gates went on describing the
        //    folder the user had left. The re-read is a LOAD, so a folder with no
        //    verdict of its own reads as "not checked yet".
        //  - A refused restore is written to the diagnostics log, not only
        //    toasted: a 2-second toast is not a record, and "why did Restore do
        //    nothing" is exactly what a field report asks.
        // Verified on device: check, restart, tamper, restart, repair, re-check;
        // and for the three above — a restored finding surviving a restart with
        // its archive names, a device restore refused INSIDE a running check's
        // own window, and the refusal line in the log.
        // 1.0.1 (300016) — a check that could not see a damaged archive.
        //  - "Check" left a part unread when the vault index had already read it
        //    back, and decided that from a modification time. On FUSE a same-size
        //    edit lands in the same second as the marker, so a damaged archive was
        //    reported as fine — and kept being reported as fine on every later
        //    check, on the one screen whose job is to warn before a restore. The
        //    decision is now made from the bytes: the part is hashed and compared
        //    with the digest the index recorded. That still removes the expensive
        //    half (decompressing the archive) and cannot hide a change.
        //  - A part whose digest disagreed kept its read-back marker, because a
        //    tampered archive keeps its sidecar and therefore keeps the very key
        //    the marker was written against. The index went on calling that file
        //    verified while every check flagged it. A refuted path now loses its
        //    marker, and the finding stands until a real backup replaces it.
        //  - The report's "read now" counter included Meta.txt, which is not one
        //    of the five parts: a repeat check of a two-part app said "2 parts
        //    unchanged, 1 read now". Read and skipped now add up to the parts
        //    that were examined.
        //  - A verdict no longer outlives the vault it describes. The Home health
        //    line kept saying "all readable" after a run had written new archives,
        //    because nothing recorded that the report was taken before it. Every
        //    report now carries the run-gate epoch its bytes were read at, and a
        //    healthy verdict from before the last run is withdrawn rather than
        //    repeated — a damaged one is kept, because it still gates the restore.
        //    The Manage banner's tap no longer deletes the result it exists to
        //    show, and the Manage check now covers device archives like Home's.
        // Verified on device: tamper, re-check, tamper again, device archives;
        // and for the verdict age — check on Home, a gated run, the card reads
        // "Backups changed since the last check", check again reads healthy; the
        // Manage banner reopens the result instead of deleting it.
        // 1.0.0 (300015) — first public release.
        //
        // versionName is 1.0.0 — the first public release. versionCode stays
        // high on purpose: it is an opaque install-ordering number nobody sees,
        // while 3.x builds are ALREADY sideloaded onto a test device. Code 1
        // would be a DOWNGRADE there — Android refuses to install over a higher
        // code without `adb install -d`, and uninstalling first instead drops
        // Magisk's per-UID grant and every app preference. A high code installs
        // cleanly everywhere, so the release is testable with neither footgun.
        // The per-ABI release APKs are produced by ABI splits: one APK per
        // architecture, each carrying only its own engine. A single universal
        // APK shipped all four ABIs, so a phone downloaded an engine for x86 it
        // would never run.
        // 2.0.0 (400000) — stable. Debloat and restore work, and a run can no
        // longer cancel itself.
        //
        // versionCode 400000, NOT the 200000 the "200000+ series for 2.x" rule
        // suggests. versionCode must strictly INCREASE for `pm install -r` to
        // accept an upgrade, and the 300000 band was already taken — v1.0.0
        // published at 300015, with local builds on to 300019. So a 2.0.0 built
        // at 200000 was refused on a real device with
        // INSTALL_FAILED_VERSION_DOWNGRADE, and every published user would have
        // had to uninstall first, losing their preferences and scheduled jobs.
        // 400000 starts the 2.x band above everything 1.x ever published. (2.64)
        //
        // Everything in 1.0.4 below is included here, plus the behaviour a user
        // actually feels: the restore list finds what debloat removed, a restore
        // only says "done" when the app is really installed and launchable, and
        // the irreversible debloat asks first. Breaking changes for anyone
        // scripting the engine: `--restore-debloat` now takes the vault path as
        // a second argument, and is only honest when `pm list packages -e`
        // confirms the package.
        // 1.0.4 (300019) — debloat and restore actually work, and a run can no
        // longer cancel itself.
        //  - Debloat's Restore tab could never list an app the debloat had just
        //    removed: it drew rows from the package manager's uninstalled set,
        //    which `cmd package uninstall` (Keep Data off) had just emptied. The
        //    universe is the vault now; the filter is "not installed", which is
        //    Canta's split and was never the bug. Reported on device with an
        //    empty list. (2.56)
        //  - Nothing could undo a removed app. `restore_debloat` tried only
        //    install-existing/request-unarchive, both NameNotFoundException for
        //    a package PackageManager has forgotten. It now reinstalls from the
        //    app's own App.shift — and a restore is only reported successful once
        //    `pm list packages -e` confirms the package is installed AND enabled.
        //    `pm install` exits 0 for a package that comes back disabled, and
        //    `request-unarchive` prints "Success" while installing nothing. (2.56, 2.59)
        //  - The irreversible debloat was the DEFAULT and had no dialog. Keep
        //    Data now defaults to the reversible path and the permanent one is
        //    confirmed as irreversible. (2.56)
        //  - A finished engine command cancelled the next one. `awaitClose` runs
        //    on every exit including ordinary completion, and called
        //    stopEngineBlocking() unconditionally — which touches the engine's
        //    cancel flag. The next command in the same run then reported
        //    ACTION:CANCELLED for work it never began. Measured as an import
        //    that extracted all 482,592,475 bytes and said "Import cancelled". (2.57)
        //  - An import wrote archives the screen never showed: it cleared the
        //    cache but never re-read the app list. (2.58)
        //  - A system app could be ticked, drawn checked, and still not backed
        //    up — the list and the run used two different visibility rules. One
        //    predicate now serves both. This is the half of the vault a ROM
        //    flash destroys first. (2.60)
        //  - Partition imaging took no run gate at all, so a dd could run beside
        //    a backup and that backup's watchdog would pkill through it. (2.62)
        //  - A Settings probe that timed out rendered as a failed check, telling
        //    the user their engine was stale while it was current. Three-state
        //    now: match / mismatch / no answer. (2.63)
        //  - Automation's Add-rule sheet showed an empty Label row and a dead
        //    button with no explanation. (2.61)
        //  - Plus: the two verdicts, the content/permission/SSAID consent
        //    handlers, a self-healed engine deployment, and the Wi-Fi credential
        //    classifier that used to default unrecognised networks to "open".
        //    All prior sections in AUDIT.md stand.
        // Verified on device (POCO F6, Android 16, debug build): debloat →
        // restore round trip for a permanently removed app, the irreversible
        // confirmation, the Canta list, a 482 MB export/import round trip with
        // all 21 archives byte-identical (sha256) afterwards, the run gate
        // refusing a second operation in 58 ms, and a system-app backup.
        // 3.6.3 (300014) — signing hygiene, grammar, and a tab that says what it does.
        //  - Added a build-failing guard: signing material must never be staged
        //    for commit. Verified by force-staging the keystore, which failed
        //    the build and named both files.
        //  - "Backups" tab renamed to "Migrate". The tab also restores, manages
        //    and transfers, so a label naming one direction was wrong on
        //    arrival. Matches MigratorMode/DvRole.Migrate and upstream.
        //  - One plural rule, in one place. Six hand-written versions had each
        //    shipped "1 apps" or "All 1 backups readable" at some point; all now
        //    go through AppParts.count, guarded against regression.
        // 3.6.2 (300013) — found by running the release build on a real device.
        //  - Checking the DEVICE archives with none present reported "No app
        //    backups found". The engine had already said which check it ran —
        //    VERIFY_DEVICE_DONE vs VERIFY_DONE — and the parser matched both on
        //    one branch and discarded the difference, so the summary guessed
        //    "apps" and described something it never examined. The report now
        //    carries the kind it was given.
        // 3.6.1 (300012) — the pre-publication audit. One real bug, and the
        // publishing hygiene that came with it.
        //  - A full backup or restore inherited a FLAT 15-minute timeout, which
        //    ShellEngine enforces with future.cancel(true) — an outright SIGTERM
        //    of the engine mid-write. Any vault big enough to need more than a
        //    quarter hour was killed part-way and reported as a timeout. Both
        //    that bound and the outer one now scale with the bytes the run can
        //    write, and the timeout message names the bound that
        //    actually applied instead of a hardcoded "30 min".
        //  - The NDK path was hardcoded to the maintainer's home directory,
        //    which leaked the username into the repository and failed to build
        //    anywhere else. It resolves from a property, the environment, or
        //    local.properties, and says so in words when none is set.
        //  - Device serial, Wi-Fi ADB addresses and home paths redacted from
        //    the docs; build outputs that landed in the source tree are ignored.
        // 3.6.0: transfers stop recompressing what is already compressed, and
        // stop writing the same archive three times.
        //  - A 30 GB export took ~30 minutes. Every .shift in the vault is
        //    already a zstd stream, so `tar -czf` was gzipping zstd at ~32 MB/s
        //    to save 0.7% — 19m52s of the run. It is now an uncompressed tar.
        //  - The pack was then COPIED 30 GB into Downloads to land one rename
        //    from where it started, at 54 MB/s because it queued behind the
        //    pack's own writeback. It is now written in place and renamed.
        //  - The import survey existed only to learn an uncompressed size that
        //    a plain tar already states; skipped for uncompressed transfers.
        //  - Each export gets its own folder, named for when it started.
        //  - Split checks compared `du` against `stat` and deleted every good
        //    part over a 28 MB discrepancy (2.31).
        //  - The importer could not find the file the exporter had just
        //    written: it guessed the folder, and guessed first (2.33).
        //  - The import space gate demanded three copies of an operation that
        //    makes one, refusing a 29.7 GB import with 71 GB free (2.35).
        //  - A flat 60-minute watchdog killed the device mid-transfer (2.32).
        //  - An interrupted export could leave its archive behind forever (2.37).
        // Verified on device: 30 GB split export 30 min -> 3m07s; import
        // ~8 min -> ~2m45s; parts, manifest and Check all verified; archives
        // written by earlier builds still import unchanged.
        // 3.0.0 (500000) — WITHDRAWN, superseded by 3.0.1. It shipped a UI in
// which every container resolved its text colour to the accent blue (§2.90).
// Manage Backups, diagnostics, the log
        // viewer, and a UI that finally reads as one app.
        // versionCode 500000 — a NEW major-version band, ABOVE every code ever
        // published: 300015 (1.0.0), the 300018/300019 1.x builds, and 400000
        // (2.0.0). `pm install -r` refuses a downgrade with
        // INSTALL_FAILED_VERSION_DOWNGRADE, and the only way past that is to
        // uninstall first, which drops the user's preferences, their scheduled
        // jobs, and Magisk's per-UID grant. A major-version band is only worth
        // starting above every code ever shipped — the "200000+ series for 2.x"
        // rule put 2.0.0 BELOW what 1.x had already published, and the install
        // was refused on a real device (§2.64).
        //  - Buttons you could miss now have a full-size tap area. The small
        //    arrow and bin buttons were 28 to 40dp to touch - well under the 48dp
        //    a fingertip needs - and looked small because their icons were small,
        //    which is not a reason to shrink the button around them.
        //  - Screen readers can now name every button. One control on the flash
        //    order - the one that removes an action - announced nothing at all.
        //  - The settings self-check can no longer freeze the screen if its
        //    threading is ever tidied up "to match" the three callbacks beside it.
        //  - "Nothing here" now looks the same wherever it appears, and in the
        //    app's own colours rather than a tint that meant nothing. Run history
        //    no longer paints its empty state in a shade of the wallpaper.
        //  - Two shortcut icons now match what they do. "Back up device data"
        //    and "Restore device data" wore the same picture, so you could not
        //    tell them apart; and "Erase Native" had a phone-settings icon
        //    instead of anything that meant erasing.
        //  - Bigger text now works properly. At a large font size the bottom bar
        //    used to cut the last letter off "Migrate", the Home buttons showed
        //    "Back up a..." instead of their names, and the backup-health line
        //    lost the end of its sentence — which is the part telling you what to
        //    do about it.
        //  - The Home screen no longer has an empty band across the middle of
        //    it. Editing a shortcut is a button in the top bar next to Settings,
        //    where every other app puts its edit button, instead of a small
        //    pencil floating on its own above a gap.
        //  - Every animation now shares one set of timings. The app had fourteen
        //    different ones — 130 and 150 and 180 were all "a short settle" — so
        //    the motion felt hand-tuned per screen rather than consistent. Sizes
        //    are unchanged for almost every animation, so nothing feels different;
        //    they just all move at the same rhythm now.
        //  - A spinner can no longer look like it has stopped mid-turn, and a
        //    screen you are leaving now slides away instead of being dragged off.
        //  - The whole app now reads from one type scale. Seven of Material's
        //    fifteen text styles were never defined, so the app's own text used
        //    its weights and roughly 230 other places fell back to Google's —
        //    which is why stepping down a list changed weight far more than
        //    size. All fifteen are defined now and every one states every value.
        //  - No text size changed. Headings are slightly lighter and titles are
        //    a touch bolder in the hierarchy, but the app still fits on screen
        //    exactly where it did.
        //  - Card titles stopped looking like links. Text inside a card took its
        //    colour from the accent blue instead of the normal reading colour,
        //    so "Back up apps" and "Magisk 30.7.5" read as hyperlinks. This was
        //    always broken but invisible, because the wallpaper palette that
        //    used to supply that blue was a pale near-white.
        //  - Droid Vault's own colours are now the default; your wallpaper's
        //    palette is still available in Settings → Theme.
        //  - Manage Backups gained a Device data card: every device archive
        //    with its size, date and health, and a per-item delete that goes
        //    through the recoverable trash. Deleting a device backup is as
        //    irreversible as deleting an app one, and it is often the only copy.
        //  - A row can no longer say a vault is empty because a read failed.
        //    "0 of 7 backed up" was what a renamed vault folder produced: the
        //    read was wrapped in runCatching and the failure arrived as an
        //    empty list. Unreadable is now its own state with a reason.
        //  - The action chips wrap instead of scrolling sideways. Six controls
        //    were rendered as a row you had to scroll to read, with names that
        //    appeared for three seconds and then vanished.
        //  - Export deletes moved to Manage Backups, through the trash, so
        //    there is one place destructive actions live.
        //  - The Import page's exports now show their size as the headline and an
        //    Import button, instead of looking like static text you have to guess
        //    is tappable. A green, amber or red dot says whether each one is
        //    actually restorable.
        //  - If you choose to split an export, the sheet now says WHY it needs
        //    twice the room — it keeps the whole file until every piece is
        //    checked — instead of just quoting a number that looks like a bug.
        //  - Importing and exporting now ask first, and say what will happen.
        //    - Importing a backup used to start from one tap of a row and was
        //      your only chance to notice it was the wrong row.
        //    - The sheet tells you the size, the space it needs, where the files
        //      come from and go, how many pieces are present, and the two things
        //      it used to say nothing about: your existing backups are kept and
        //      nothing is overwritten, and the export file is left where it is.
        //    - "Find it somewhere else…" asks too — it was the one way to start
        //      the same restore with nothing said first.
        //    - If there is not enough room the button is off and it tells you
        //      how much more to free, instead of failing minutes in.
        //  - Exporting asks first too, and the one-file-or-parts choice moved
        //    into that same sheet — so you confirm with the decision already
        //    made, and the space figure updates as you change it.
        //  - The Import page explains what it is about to do, and when it has no
        //    exports it says what is actually true about your backups instead of
        //    telling you to go and do something on another tab. Someone who has
        //    just flashed their phone is usually standing on that page, and the
        //    vault is still exactly where it was.
        //  - "Find it somewhere else…" is a link rather than a second button, so
        //    the empty state has one obvious action instead of two equal ones.
        //  - The diagnostics log records what happened, with counts, names and
        //    sizes — trash moves, refusals, restore gates, transfer verdicts.
        //    Findings name the archive they are about instead of reporting a
        //    count. Eleven messages went only to logcat, which is stripped from
        //    release builds, so a field report could not carry them.
        //  - The Live log can be filtered by severity and searched, colours each
        //    line by level, and keeps your scroll position when you scroll up
        //    to read — previously it snapped back to the newest line every time
        //    one arrived, which made reading during a run impossible.
        //  - The status line now says what each number is: "82.0 KB in today's
        //    log · 6 lines since launch", not "82.00 KB on disk · 6 lines in
        //    memory", which read as a contradiction.
        //  - Copy on an empty log no longer claims it copied one line.
        // Verified on device: the level filter, the search, the scroll position
        // holding across 82 arriving lines and resuming at the tail, the new
        // wording, and the whole sheet at a 2x font.
        // Verified for the release as a whole: a 30 GB round trip — backup of
        // two large apps, export split into 15 parts, the vault quarantined then
        // erased permanently, and import back to ADDED:2|KEPT:0|FAILED:0 —
        // with 0 ERROR/FATAL across 1,414 log lines, on a build byte-identical to
        // the installed one. Every phase of the UI work measured on the device:
        // the type scale and palette flip, both blocked empty states, all
        // thirteen released touch targets at 1x and 2x, the Home layout at both
        // font scales, and the full tab sweep.
        // 3.0.1 (500001) — fixes the 3.0.0 heading colour. Withdrawn and
        // re-released rather than re-tagged, because anyone who installed the
        // broken 3.0.0 is already on versionCode 500000 and an upgrade has to be
        // unambiguous.
        //  - Headings stopped rendering in the accent blue. Every card, sheet and
        //    dialog in the app was drawing its title in the link colour instead
        //    of the reading colour, so "Back up apps", "Backup Configurations"
        //    and every other navigation title looked like a hyperlink. Fixed in
        //    the palette, for both dark and light.
        //  - Nothing else changed. No colour the eye can separate moved: only
        //    the two roles that were sharing a value with the card tone were
        //    nudged three units off it, which is what lets each container pick
        //    the right text colour.
        versionCode = 500001
        versionName = "3.0.1"
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.getProperty("storeFile") != null) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            } else if (envStoreFile != null) {
                storeFile = file(envStoreFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEYSTORE_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
                // isNullOrBlank, NOT `== null`: a GitHub secret that is set but
                // empty arrives as "" and reads as present, so a null check passes
                // and signing proceeds with blank credentials — failing later,
                // inside the signing task, with an error about the keystore
                // rather than about the missing secret.
                if (storePassword.isNullOrBlank() || keyAlias.isNullOrBlank() ||
                    keyPassword.isNullOrBlank()
                ) {
                    throw GradleException(
                        "KEYSTORE_FILE is set but KEYSTORE_PASSWORD / KEYSTORE_ALIAS / " +
                            "KEY_PASSWORD are not. Refusing to build: a half-configured " +
                            "release key would either fail obscurely or fall back to the " +
                            "debug key and ship an APK that cannot update a real install."
                    )
                }
            } else {
                initWith(getByName("debug"))
            }
        }
    }

    // P5: make debug-signed local releases LOUD, not silent. A debug-signed
    // "release" APK cannot update an installed release-signed build and must
    // never be distributed as one.
    tasks.matching { it.name == "assembleRelease" }.configureEach {
        val properlySigned = hasReleaseKeystore
        doLast {
            if (!properlySigned) {
                logger.warn(
                    "⚠️  assembleRelease is DEBUG-SIGNED (no keystore.properties, no KEYSTORE_* env). " +
                        "Do NOT ship this APK; use it for local testing only."
                )
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            isUniversalApk = false
            include("armeabi-v7a", "arm64-v8a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
    androidResources {
        @Suppress("UnstableApiUsage")
        localeFilters += "en"
    }
}

// Cross-compile the privileged operation engine only as part of an app build.
// Keeping these tasks attached to preBuild means Gradle's existing incremental
// behavior handles the Rust sources, while no native compilation is done during
// ordinary Kotlin editing or source inspection.
val rustEngineDir = rootProject.file("engine")
val rustEngineInputs = fileTree(rustEngineDir) {
    include("Cargo.toml", "Cargo.lock", "src/**/*.rs")
}
/**
 * Where the Android NDK lives, resolved for whoever cloned this.
 *
 * It used to fall back to a hard-coded path into the maintainer's own home
 * directory. That
 * leaks the maintainer's username into a published repository and, worse, fails
 * on every other machine with a message pointing at a directory that does not
 * exist there. Resolution order, most explicit first:
 *
 *   1. `-Pdroidvault.ndkPath=...`, or `droidvault.ndkPath` in gradle.properties
 *   2. `ANDROID_NDK_HOME` / `ANDROID_NDK_ROOT`
 *   3. `ndk.dir` in local.properties, which the SDK tooling also honours
 *
 * Only the Rust engine tasks need this. `./gradlew testDebugUnitTest` does not,
 * so a contributor can run the entire test suite without an NDK installed.
 */
val sdkRoot: String? =
    System.getenv("ANDROID_SDK_ROOT")
        ?: rootProject.file("local.properties").takeIf { it.isFile }
            ?.let { propsFile ->
                val props = Properties()
                propsFile.inputStream().use { props.load(it) }
                props.getProperty("sdk.dir")
            }

/** The highest-versioned NDK under the SDK root, if any. */
/** Negative / zero / positive, ordering NDK versions by component. */
fun compareNdkVersions(a: List<Int>, b: List<Int>): Int {
    for (i in 0 until maxOf(a.size, b.size)) {
        val av = a.getOrElse(i) { 0 }
        val bv = b.getOrElse(i) { 0 }
        if (av != bv) return av - bv
    }
    return 0
}

fun newestNdkInSdk(root: String?): String? {
    if (root.isNullOrBlank()) return null
    // Project.file(), not java.io.File: `java` is shadowed in this script, and
    // `file(root, "ndk")` resolves to an overload that does not take a String
    // first argument. resolve() keeps both operands Strings.
    val ndkDir = rootProject.file(root).resolve("ndk")
    if (!ndkDir.isDirectory) return null
    // Version-ordered, not lexicographic: "9.0.0" sorts above "30.0.16248370"
    // as a string, and picking that would hand the build an ancient NDK.
    val candidates = ndkDir.listFiles()?.filter { it.isDirectory } ?: return null
    var best: String? = null
    var bestKey: List<Int>? = null
    for (c in candidates) {
        val key: List<Int> = c.name.split('.').map { it.toIntOrNull() ?: 0 }
        if (bestKey == null || compareNdkVersions(key, bestKey) > 0) {
            bestKey = key
            best = c.absolutePath
        }
    }
    return best
}

val ndkRoot: String = providers.gradleProperty("droidvault.ndkPath").orNull
    ?: System.getenv("ANDROID_NDK_HOME")
    ?: System.getenv("ANDROID_NDK_ROOT")
    ?: rootProject.file("local.properties")
        .takeIf { it.isFile }
        ?.let { propsFile ->
            val props = Properties()
            propsFile.inputStream().use { props.load(it) }
            props.getProperty("ndk.dir")
        }
    // Last, and deliberately: a CI runner installs the NDK into the SDK root
    // and exports nothing. Without this the engine cross-compile fails with
    // "none was found" on a machine that plainly has one.
    ?: newestNdkInSdk(sdkRoot)
    ?: ""
val ndkHostBin = file(
    if (ndkRoot.isBlank()) "no-ndk-configured"
    else "$ndkRoot/toolchains/llvm/prebuilt/linux-x86_64/bin"
)
val ndkPresent = ndkHostBin.isDirectory
val ndkAbsentMessage = """
    |Droid Vault's native engine needs the Android NDK, and none was found.
    |Set one of:
    |  -P droidvault.ndkPath=/path/to/android-ndk   (or droidvault.ndkPath= in gradle.properties)
    |  ANDROID_NDK_HOME=/path/to/android-ndk
    |  ndk.dir=/path/to/android-ndk  in local.properties
    |Kotlin-only work does not need it: `./gradlew testDebugUnitTest` runs without an NDK.
""".trimMargin()

// Binaries packaged as lib*.so so Android's installer extracts them into
// nativeLibraryDir, but they are executables, not JNI libraries.
fun registerRustEngineTask(
    taskName: String,
    target: String,
    abi: String,
    linker: String,
) = tasks.register<Exec>(taskName) {
    val packagedBinary = file("src/main/jniLibs/$abi/libdroidvault_engine.so")
    val cargoBinary = file("$rustEngineDir/target/$target/release/droidvault-engine")
    inputs.files(rustEngineInputs)
    doFirst {
        if (!ndkPresent) {
            throw GradleException(ndkAbsentMessage)
        }
    }
    outputs.file(packagedBinary)
    workingDir(rustEngineDir)
    environment(
        "CARGO_TARGET_${target.uppercase().replace('-', '_')}_LINKER",
        file("$ndkHostBin/$linker").absolutePath,
    )
    commandLine("cargo", "build", "--locked", "--release", "--target", target)
    doLast {
        packagedBinary.parentFile.mkdirs()
        cargoBinary.copyTo(packagedBinary, overwrite = true)
    }
}

// Real devices are arm64/arm32; x86_64 is the emulator ABI. It was missing a
// build task while still shipping libzapdos.so for it, so an emulator got the
// compressor but never the engine and BackendInstaller aborted at
// "Rust engine binary is not bundled" — every operation failed closed.
val buildRustEngineArm64 = registerRustEngineTask(
    "buildRustEngineArm64", "aarch64-linux-android", "arm64-v8a", "aarch64-linux-android29-clang"
)
val buildRustEngineArm32 = registerRustEngineTask(
    "buildRustEngineArm32", "armv7-linux-androideabi", "armeabi-v7a", "armv7a-linux-androideabi29-clang"
)
val buildRustEngineX64 = registerRustEngineTask(
    "buildRustEngineX64", "x86_64-linux-android", "x86_64", "x86_64-linux-android29-clang"
)
// Keep the native compiles serial on this 7 GB development laptop. Parallel
// Gradle workers compiling Rust competed for RAM and CPU; Cargo still reuses
// its target cache between ABIs.
buildRustEngineArm32.configure { dependsOn(buildRustEngineArm64) }
buildRustEngineX64.configure { dependsOn(buildRustEngineArm32) }
tasks.named("preBuild").configure {
    dependsOn(buildRustEngineX64)
}

dependencies {
    implementation(libs.libsu.core)
    implementation(libs.libsu.io)
    implementation(libs.androidx.core.splashscreen)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.work.runtime.ktx)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.json.java)
}
