use crate::{
    archive, checksum, command, device, install, manifest, partitions, protocol, quiesce, restore,
    targets, tools, transfer, verify,
};
use std::collections::{BTreeSet, HashMap};
use std::env;
use std::fs;
use std::path::{Path, PathBuf};
use std::time::Instant;

#[derive(Clone, Copy)]
struct Components(u8);

impl Components {
    fn parse(raw: &str) -> Self {
        Self(
            raw.split_whitespace()
                .filter_map(|n| n.parse::<u8>().ok())
                .filter(|n| (1..=5).contains(n))
                .fold(0u8, |mask, id| mask | (1u8 << id)),
        )
    }
    fn has(self, id: u8) -> bool {
        self.0 & (1u8 << id) != 0
    }
}

#[derive(Default)]
struct Meta {
    app_size: String,
    data_size: String,
    media_size: String,
    /// Per-ARCHIVE sizes in KB, one per root a part writes.
    ///
    /// `DataExtSize` and `MediaOBBSize` are totals over several archives, and
    /// the restore space gate is asked about ONE archive at a time. Gating each
    /// one against the total charged it for its siblings' worth of space too —
    /// two to three times what the extraction needs — so a nearly-full device
    /// fell back to the non-reversible in-place path far earlier than it had to.
    /// Per-archive, the gate asks what it is actually about to write (§4.27).
    ///
    /// Empty means this build did not record it (an archive from before this
    /// existed), and the gate falls back to the total in that case — the
    /// over-reserving behaviour is the safe direction to keep for old backups.
    data_root_kb: String,
    user_de_kb: String,
    ext_data_kb: String,
    media_root_kb: String,
    obb_kb: String,
    ssaid: String,
    /// Whether the app was stopped while its data was read. `"1"` only when a
    /// run OBSERVED a quiet app (nothing running, or a successful force-stop);
    /// `"0"` when the app was running and could not be stopped, so the archive
    /// may hold a mid-write view of its database or preferences. Empty means no
    /// run has recorded it (an archive from before this existed) — which is not
    /// the same as "consistent", and must never be rendered as one.
    consistent: String,
}

impl Meta {
    /// The space one archive's extraction is expected to need, in KB.
    ///
    /// Per-archive when this build recorded it, the part total otherwise. Never
    /// both added: they measure the same trees, and summing them would charge
    /// the gate for twice the work.
    fn staging_kb(&self, root: &Path) -> u64 {
        let (per_archive, total) = if root == Path::new("/data/data") {
            (&self.data_root_kb, &self.data_size)
        } else if root == Path::new("/data/user_de/0") {
            (&self.user_de_kb, &self.data_size)
        } else if root == Path::new("/data/media/0/Android/data") {
            (&self.ext_data_kb, &self.data_size)
        } else if root == Path::new("/data/media/0/Android/media") {
            (&self.media_root_kb, &self.media_size)
        } else {
            (&self.obb_kb, &self.media_size)
        };
        let measured = restore::recorded_kb(per_archive);
        if measured > 0 {
            measured
        } else {
            restore::recorded_kb(total)
        }
    }

    /// Record one archive's measured size, named by the archive it backs up.
    ///
    /// Keyed on the archive's file name because that is what the restore loop
    /// iterates — one spelling, used by the backup that writes the size, the
    /// `Meta.txt` that carries it and the gate that reads it back. A key name
    /// shared between a writer and a reader is an interface, not a detail.
    fn set_archive_kb(&mut self, archive_name: &str, kb: String) {
        match archive_name {
            "Data" => self.data_root_kb = kb,
            "UserDe" => self.user_de_kb = kb,
            "ExtData" => self.ext_data_kb = kb,
            "Media" => self.media_root_kb = kb,
            "Obb" => self.obb_kb = kb,
            _ => {}
        }
    }

    /// Carry the per-archive sizes forward from a run that did not re-read them.
    ///
    /// `next` is built from `Meta::default()`, so a part this run skipped has to
    /// state explicitly that it is keeping what is already recorded. Without
    /// this, a backup that skipped every part would blank the per-archive sizes
    /// and every later restore would quietly fall back to the part total.
    fn carry_archive_kb(&mut self, from: &Meta) {
        self.data_root_kb = from.data_root_kb.clone();
        self.user_de_kb = from.user_de_kb.clone();
        self.ext_data_kb = from.ext_data_kb.clone();
        self.media_root_kb = from.media_root_kb.clone();
        self.obb_kb = from.obb_kb.clone();
    }

    fn read(dir: &Path) -> Self {
        let Ok(text) = fs::read_to_string(dir.join("Meta.txt")) else {
            return Self::default();
        };
        let mut out = Self::default();
        for line in text.lines() {
            let (key, value) = line.split_once('=').unwrap_or(("", ""));
            match key {
                "AppSize" => out.app_size = value.to_string(),
                "DataExtSize" => out.data_size = value.to_string(),
                "MediaOBBSize" => out.media_size = value.to_string(),
                "DataSize" => out.data_root_kb = value.to_string(),
                "UserDeSize" => out.user_de_kb = value.to_string(),
                "ExtDataSize" => out.ext_data_kb = value.to_string(),
                "MediaSize" => out.media_root_kb = value.to_string(),
                "ObbSize" => out.obb_kb = value.to_string(),
                "SSAID" => out.ssaid = value.to_string(),
                "Consistent" => out.consistent = value.to_string(),
                _ => {}
            }
        }
        out
    }
}

pub fn dispatch(args: &[String]) -> Result<(), String> {
    let command_name = args.first().map(String::as_str).unwrap_or_default();
    let arg = |i: usize| args.get(i).map(String::as_str).unwrap_or_default();
    match command_name {
        "--backup" => run_apps(false, arg(1), arg(2)),
        "--restore" => run_apps(true, arg(1), arg(2)),
        "--live-backup" => partitions::live_backup(arg(1), arg(2)),
        "--live-restore" => partitions::live_restore(arg(1), arg(2)),
        "--remove" => {
            tools::remove(arg(1), arg(2) == "-k");
            Ok(())
        }
        "--restore-debloat" => {
            tools::restore_debloat(arg(1), arg(2));
            Ok(())
        }
        "--systemize" => {
            tools::systemize(arg(1), arg(2), arg(3), arg(4));
            Ok(())
        }
        "--backup-wifi" => {
            device::backup_wifi(arg(1));
            Ok(())
        }
        "--restore-wifi" => {
            device::restore_wifi(arg(1));
            Ok(())
        }
        "--backup-wallpaper" => {
            device::backup_wallpaper(arg(1));
            Ok(())
        }
        "--restore-wallpaper" => {
            device::restore_wallpaper(arg(1));
            Ok(())
        }
        "--backup-bt" => {
            device::backup_bluetooth(arg(1));
            Ok(())
        }
        "--restore-bt" => {
            device::restore_bluetooth(arg(1));
            Ok(())
        }
        "--install-apps" => install_apps(arg(1)),
        "--kill-jobs" => {
            kill_jobs();
            Ok(())
        }
        "--ors" => {
            write_ors(arg(1), arg(2));
            Ok(())
        }
        "--clear-ors" => {
            clear_ors();
            Ok(())
        }
        // Archive verification. Exists because a backup that cannot be read
        // back is discovered at restore time, which is the worst moment: the
        // device may already be wiped or the ROM already flashed.
        "--verify-apps" => verify::verify_apps(arg(1)),
        "--verify-device" => verify::verify_device(arg(1)),
        // The vault index. `--check-manifest` answers "is everything still
        // here, at the size it was written?" without reading an archive, which
        // is the question a user asks far more often than the content one;
        // `--write-manifest` rebuilds it (a backup run does this on its way out).
        "--check-manifest" => manifest::check_manifest(arg(1)),
        "--write-manifest" => manifest::write_manifest(arg(1)),
        // Cross-device transfer. The archive format is plain gzip tar handled
        // by the platform tar; this only drives it, measures it, and moves the
        // results — with progress and cancellation the shell pipeline lacked.
        "--transfer-export" => {
            let expected = arg(3).parse::<u64>().unwrap_or(0);
            // `args[4..]` panicked on a short argv (range start past the end),
            // and with `panic = "abort"` that is SIGABRT in a privileged
            // process — found on device as a bare "Aborted". Slice safely.
            let entries = args.get(4..).unwrap_or(&[]).to_vec();
            transfer::transfer_export(arg(1), arg(2), expected, &entries)
        }
        "--transfer-extract" => {
            let expected = arg(3).parse::<u64>().unwrap_or(0);
            transfer::transfer_extract(arg(1), arg(2), expected)
        }
        "--transfer-survey" => transfer::transfer_survey(&args.get(1..).unwrap_or(&[]).to_vec()),
        "--transfer-split" => {
            let size = arg(3).parse::<u64>().unwrap_or(0);
            transfer::transfer_split(arg(1), arg(2), size)
        }
        "--transfer-concat-extract" => {
            let expected = arg(2).parse::<u64>().unwrap_or(0);
            let parts = args.get(3..).unwrap_or(&[]).to_vec();
            transfer::transfer_concat_extract(arg(1), expected, &parts)
        }
        "--transfer-place" => {
            let keep = arg(3) != "replace";
            let dirs = args.get(4..).unwrap_or(&[]).to_vec();
            transfer::transfer_place(arg(1), arg(2), keep, &dirs)
        }
        "--get-partitions" => {
            partitions::get_partitions();
            Ok(())
        }
        "--get-images" => {
            partitions::get_images(arg(1));
            Ok(())
        }
        "--delete-image" => {
            partitions::delete_image(arg(1), arg(2));
            Ok(())
        }
        "--version" => {
            println!("{}", env!("CARGO_PKG_VERSION"));
            Ok(())
        }
        _ => Err(format!(
            "Unknown Droid Vault engine command: {command_name}"
        )),
    }
}

fn app_base(path: &str) -> Result<PathBuf, String> {
    if !path.starts_with('/') || path.contains('\n') || path.contains('\r') || path.contains('|') {
        return Err("Invalid backup folder".into());
    }
    let normalized = path.trim_end_matches('/');
    // A traversal component escapes the vault the user actually chose and
    // makes the engine create Apps/ (and write backups) wherever it lands —
    // including /data/adb/modules. `..` is never legitimate in a persisted
    // vault path, so reject it outright instead of relying on the caller's
    // spellings to be canonical.
    if normalized
        .split('/')
        .any(|segment| segment == ".." || segment == ".")
    {
        return Err("Invalid backup folder".into());
    }
    Ok(Path::new(if normalized.is_empty() {
        "/"
    } else {
        normalized
    })
    .join("Apps"))
}

fn safe_label(label: &str) -> bool {
    !label.is_empty()
        && label.len() <= 512
        && label != "."
        && label != ".."
        && !label.contains('/')
        && !label.contains('|')
        && !label.chars().any(char::is_control)
}

fn effective_components(target: &targets::Target, global: Components) -> Components {
    target
        .components
        .as_deref()
        .map(Components::parse)
        .unwrap_or(global)
}

fn run_apps(restore: bool, raw_components: &str, base_path: &str) -> Result<(), String> {
    let base = app_base(base_path)?;
    let global = Components::parse(raw_components);
    let mut apps = targets::read(Path::new(command::TARGETS))?;
    if apps.is_empty() {
        protocol::info("No apps selected");
        protocol::global_done(0, 0);
        return Ok(());
    }
    // Largest-first order keeps the byte-based progress estimate truthful and
    // matches the established DroidVault.sh run order.
    apps.sort_by_key(|a| {
        std::cmp::Reverse(
            a.app_size
                .saturating_add(a.data_size)
                .saturating_add(a.media_size),
        )
    });
    fs::create_dir_all(&base).map_err(|e| format!("Cannot create backup folder: {e}"))?;
    let _ = fs::write(base.join(".nomedia"), "");
    fs::create_dir_all(command::TEMP_DIR)
        .map_err(|e| format!("Cannot create engine temp folder: {e}"))?;
    let _ = fs::remove_file(command::CANCEL_FLAG);
    let _ = fs::remove_file(command::BUNDLE_JOURNAL);
    let _ = fs::remove_file(Path::new(command::TEMP_DIR).join("restore_queue.list"));
    let started = Instant::now();
    let total_weight = apps
        .iter()
        .map(|app| {
            let comps = effective_components(app, global);
            25u64
                + if comps.has(3) { 5 } else { 0 }
                + if comps.has(5) { 1 } else { 0 }
                + if comps.has(1) { app.app_size } else { 0 }
                + if comps.has(2) { app.data_size } else { 0 }
                + if comps.has(4) { app.media_size } else { 0 }
        })
        .sum::<u64>()
        .max(1);
    let mut completed_weight = 0u64;

    if restore && env::var("ALLOW_VERIFIER_DISABLE").ok().as_deref() == Some("1") {
        verifier_mode(true);
    }

    // One process-list read for the whole run.
    //
    // An app that is running can write into its own data directory while tar
    // streams it (backup), and holds the old tree open while its replacement is
    // moved into place (restore), so both directions need to know what is
    // running: measured at ~20 ms for 830 processes on the reference device,
    // paid once per run rather than once per app.
    let running = quiesce::running_packages();
    // Free-space readings, one per target root per run (see
    // `restore::available_kb_cached` for why a run-scoped value is enough).
    let mut free_kb: HashMap<String, u64> = HashMap::new();
    if restore {
        // Anything a previous run was killed in the middle of is repaired
        // BEFORE this run reads the same positions. Silence here is what makes
        // a half-finished restore invisible, so the pass reports what it did.
        restore::heal_journal();
    }

    for (index, app) in apps.iter().enumerate() {
        if command::cancelled() {
            protocol::raw("ACTION:CANCELLED");
            break;
        }
        if !safe_label(&app.label) {
            protocol::error("Invalid app label in target list");
            continue;
        }
        let comps = effective_components(app, global);
        let weight = 25u64
            + if comps.has(3) { 5 } else { 0 }
            + if comps.has(5) { 1 } else { 0 }
            + if comps.has(1) { app.app_size } else { 0 }
            + if comps.has(2) { app.data_size } else { 0 }
            + if comps.has(4) { app.media_size } else { 0 };
        let percent = completed_weight.saturating_mul(100) / total_weight;
        protocol::app_start(
            restore,
            &app.package,
            &app.label,
            &app.version,
            index + 1,
            apps.len(),
            percent,
            weight,
        );
        let dir = base.join(&app.kind).join(&app.label);
        let result = if restore {
            restore_app(app, &dir, comps, &running, &mut free_kb)
        } else {
            backup_app(app, &dir, comps, &running)
        };
        if let Err(error) = result {
            if command::cancelled() {
                protocol::raw("ACTION:CANCELLED");
                break;
            }
            // ACTION:ERROR is TERMINAL for the app: MigratorManager latches it
            // with completeOnce("Operation Failed!") and the real GLOBAL_DONE
            // that follows is then swallowed. So report the failure and DO NOT
            // also emit BACKUP_DONE/RESTORE_DONE for the same package — a
            // package cannot be both errored and finished. The legacy launcher
            // returned non-zero here for the same reason.
            protocol::error(&error);
        }
        completed_weight = completed_weight.saturating_add(weight);
        let pct = completed_weight.saturating_mul(100) / total_weight;
        protocol::app_start(
            restore,
            &app.package,
            &app.label,
            &app.version,
            index + 1,
            apps.len(),
            pct,
            weight,
        );
    }

    if restore {
        if !command::cancelled() {
            restore_ownership();
        }
        if env::var("ALLOW_VERIFIER_DISABLE").ok().as_deref() == Some("1") {
            verifier_mode(false);
        }
    }
    if !command::cancelled() {
        protocol::info("Almost Done, Please Wait...");
        protocol::global_done(total_weight, started.elapsed().as_secs());
        // Index the vault as it now stands, so `--check-manifest` has something
        // to compare against. Skipped while cancelled: an index built over a run
        // that was stopped mid-write would record a half-written archive as the
        // vault's own shape. Cheap by construction — the walk reads the sidecars
        // and never an archive.
        //
        // The vault ROOT, not `base`: `app_base` hands back `<vault>/Apps`, and
        // the index covers the whole vault including Device/ and the mirrors.
        // Found on device — `rebuild(&base)` looked for `Apps/Apps/User/...`,
        // found nothing, and quietly declined to write an index after every
        // single run ("no archives found in this folder") while the vault it had
        // just written to was sitting right there.
        if let Some(vault) = base.parent() {
            manifest::rebuild(vault);
        }
    }
    Ok(())
}

/// What to do about an app's Media/OBB part.
#[derive(Debug, PartialEq, Eq)]
pub(crate) enum MediaAction {
    /// The app has neither a media nor an obb directory: there is nothing to
    /// back up, so this is a skip and never a failure.
    SkipNothingToDo,
    /// Sizes match and every expected archive is already present.
    SkipUnchanged,
    /// Pack the directories that exist.
    Pack,
}

/// Pure decision for the Media/OBB part, so "nothing to back up" can never again
/// be reported as "bundle failed".
///
/// Found on device: every app without external media showed a red
/// "Backup Media/OBB — bundle failed" on an otherwise successful run. The old
/// code packed nothing, left its `any` flag false, and reported the same state
/// it used for "the directory exists and packing it failed". Absence of a
/// source is not a failure — that is a by-design skip, not an error.
pub(crate) fn media_action(
    has_media_dir: bool,
    has_obb_dir: bool,
    missing_source_archive: bool,
    size_changed: bool,
) -> MediaAction {
    if !has_media_dir && !has_obb_dir {
        // Neither exists. Checked FIRST, before the size comparison: a stale
        // recorded size on an app that has since lost its media directory must
        // not reach the pack branch and fail.
        MediaAction::SkipNothingToDo
    } else if size_changed || missing_source_archive {
        MediaAction::Pack
    } else {
        MediaAction::SkipUnchanged
    }
}

/// What happens to a part the current selection EXCLUDES, when an archive from
/// an earlier (wider) run is still on disk.
///
/// Pure, so the rule that "a narrower re-backup never destroys an earlier
/// archive" is pinned by a test rather than only by the absence of code. The
/// rule has a safe direction and an unsafe one, and the unsafe one used to be
/// the implementation.
#[derive(Debug, PartialEq, Eq)]
pub(crate) enum RetainAction {
    /// No earlier archive exists, so there is nothing to protect and nothing to say.
    NothingKept,
    /// An earlier archive exists. It is left byte-for-byte alone and reported.
    Keep,
}

/// Part id as the engine numbers them, kept here so the call site cannot drift.
pub(crate) fn retained_part(wanted: bool, earlier_archive_exists: bool) -> RetainAction {
    if wanted || !earlier_archive_exists {
        // Selected: the normal pack/skip logic below owns it. Not selected with
        // nothing on disk: there was never anything to lose.
        RetainAction::NothingKept
    } else {
        RetainAction::Keep
    }
}

fn backup_app(
    app: &targets::Target,
    dir: &Path,
    comps: Components,
    running: &BTreeSet<String>,
) -> Result<(), String> {
    fs::create_dir_all(dir).map_err(|e| format!("Cannot create app backup folder: {e}"))?;
    let old = Meta::read(dir);

    // A narrower re-backup must NOT destroy what a wider one saved.
    //
    // This used to unlink the archives the current selection excludes, on the
    // reasoning that a stale Data.shift would otherwise be offered for restore.
    // But "stale" is not "absent": unticking Media & OBB and backing up again
    // silently destroyed a multi-GB media archive that may have been the only
    // copy anywhere, with no confirmation, no quarantine and no undo — and a
    // scheduled rule listing fewer parts than a previous manual full backup did
    // the same unattended at 3am. Losing data you can see is never the right
    // default for an option that only says "don't back this up again".
    //
    // So excluded parts are left exactly as they are and reported as kept. The
    // restore picker intersects availability per part, and the pre-restore gate
    // verifies readability, so a leftover archive is offered only when it is
    // genuinely there and readable. Deleting a backup stays what the user does
    // deliberately, from Manage backups, where it goes through the trash.
    let excluded: [(&str, &[&str]); 3] = [
        ("Data", &["Data.shift", "UserDe.shift", "ExtData.shift"]),
        ("Perm", &["Permissions.txt"]),
        ("Media", &["Media.shift", "Obb.shift"]),
    ];
    for (part, names) in excluded {
        let wanted = match part {
            "Data" => comps.has(2),
            "Perm" => comps.has(3),
            _ => comps.has(4),
        };
        let exists = names.iter().any(|n| dir.join(n).is_file());
        if retained_part(wanted, exists) == RetainAction::Keep {
            keep_files(&app.package, part, dir, names);
        }
    }

    let mut next = Meta::default();
    // Whether this app was stopped before its data was read. `None` means this
    // run read nothing (every part was already up to date), and the caller then
    // keeps whatever the archive that HOLDS the data recorded.
    let mut consistency: Option<quiesce::Quiesce> = None;
    if comps.has(1) {
        let dest = dir.join("App.shift");
        if old.app_size != app.app_size.to_string() || !dest.exists() {
            protocol::part(&app.package, "App", 1, "");
            let apk_dir = Path::new(&app.apk_path);
            let root = if apk_dir.is_dir() {
                apk_dir
            } else {
                apk_dir.parent().unwrap_or(Path::new("/"))
            };
            let apks = fs::read_dir(root)
                .ok()
                .into_iter()
                .flatten()
                .flatten()
                .map(|e| e.path())
                .filter(|p| {
                    p.extension()
                        .is_some_and(|e| e.to_string_lossy().eq_ignore_ascii_case("apk"))
                })
                .collect::<Vec<_>>();
            match archive::pack_files(&apks, &dest) {
                Ok(packed) if packed.wrote() && archive::archive_valid(&dest) => {
                    next.app_size = app.app_size.to_string();
                    protocol::part(&app.package, "App", 2, "");
                }
                _ => {
                    // The sidecar goes with the archive it describes. Leaving it
                    // behind would make the next quick verify report a file that
                    // is not there as merely changed.
                    let _ = fs::remove_file(checksum::sidecar_for(&dest));
                    let _ = fs::remove_file(&dest);
                    protocol::part(&app.package, "App", 4, "APK archive failed validation");
                }
            }
        } else {
            next.app_size = old.app_size.clone();
            protocol::part(&app.package, "App", 3, "already backed up");
        }
    }

    if comps.has(2) {
        let archives = [
            ("/data/data", "Data"),
            ("/data/user_de/0", "UserDe"),
            ("/data/media/0/Android/data", "ExtData"),
        ];
        let missing_source_archive = archives.iter().any(|(root, name)| {
            Path::new(root).join(&app.package).is_dir()
                && !dir.join(format!("{name}.shift")).is_file()
        });
        if old.data_size != app.data_size.to_string() || missing_source_archive {
            // About to read the app's data: stop it first if it is running, or
            // record honestly that the archive may hold a mid-write view.
            quiesce::ensure_quiet(&app.package, running, &mut consistency);
            protocol::part(&app.package, "Data", 1, "");
            let mut any = false;
            for (root, name) in archives {
                let source = Path::new(root).join(&app.package);
                if source.is_dir() {
                    let output = dir.join(format!("{name}.shift"));
                    if matches!(
                        archive::pack_path(Path::new(root), Path::new(&app.package), &output),
                        Ok(packed) if packed.wrote()
                    ) {
                        any = true;
                        // After the pack, so the tree is still in page cache. This
                        // is what the restore space gate charges for THIS archive
                        // rather than for the whole part.
                        next.set_archive_kb(name, archive::tree_kb(&source).to_string());
                    }
                }
            }
            if any {
                next.data_size = app.data_size.to_string();
                protocol::part(&app.package, "Data", 2, "");
            } else {
                protocol::part(&app.package, "Data", 4, "bundle failed");
            }
        } else {
            next.data_size = old.data_size.clone();
            next.carry_archive_kb(&old);
            protocol::part(&app.package, "Data", 3, "already backed up");
        }
    }

    if comps.has(3) {
        protocol::part(&app.package, "Perm", 1, "");
        let permissions = get_permissions(&app.package);
        let snapshot = dir.join("Permissions.txt");
        if fs::write(&snapshot, permissions).is_ok() {
            // Small, engine-written and restore-critical: a digest here is what
            // lets a deep verify tell a truncated snapshot from a shorter
            // permission list.
            checksum::record(&snapshot);
            protocol::part(&app.package, "Perm", 2, "");
        } else {
            protocol::part(&app.package, "Perm", 4, "permission snapshot write failed");
        }
    }

    if comps.has(4) {
        let archives = [
            ("/data/media/0/Android/media", "Media"),
            ("/data/media/0/Android/obb", "Obb"),
        ];
        let missing_source_archive = archives.iter().any(|(root, name)| {
            Path::new(root).join(&app.package).is_dir()
                && !dir.join(format!("{name}.shift")).is_file()
        });
        let has_media_dir = Path::new(archives[0].0).join(&app.package).is_dir();
        let has_obb_dir = Path::new(archives[1].0).join(&app.package).is_dir();
        let present: Vec<_> = archives
            .iter()
            .zip([has_media_dir, has_obb_dir])
            .filter(|(_, exists)| *exists)
            .map(|(entry, _)| *entry)
            .collect();

        match media_action(
            has_media_dir,
            has_obb_dir,
            missing_source_archive,
            old.media_size != app.media_size.to_string(),
        ) {
            // Nothing to back up is a by-design SKIP, not a failure. Record 0
            // so the next run compares 0 == 0 and stays a skip, and so media
            // added later still trips the size check and gets packed.
            MediaAction::SkipNothingToDo => {
                next.media_size = "0".to_string();
                protocol::part(&app.package, "Media", 3, "no external media");
            }
            MediaAction::SkipUnchanged => {
                next.media_size = old.media_size.clone();
                next.carry_archive_kb(&old);
                protocol::part(&app.package, "Media", 3, "already backed up");
            }
            MediaAction::Pack => {
                // Same rule as Data, and needed independently: Media & OBB can be
                // selected without Data, and the app writes there too.
                quiesce::ensure_quiet(&app.package, running, &mut consistency);
                protocol::part(&app.package, "Media", 1, "");
                let mut any = false;
                for (root, name) in present {
                    let source = Path::new(root).join(&app.package);
                    let output = dir.join(format!("{name}.shift"));
                    if matches!(
                        archive::pack_path(Path::new(root), Path::new(&app.package), &output),
                        Ok(packed) if packed.wrote()
                    ) {
                        any = true;
                        next.set_archive_kb(name, archive::tree_kb(&source).to_string());
                    }
                }
                if any {
                    next.media_size = app.media_size.to_string();
                    protocol::part(&app.package, "Media", 2, "");
                } else {
                    // Real failure: the directory exists and packing it failed.
                    protocol::part(&app.package, "Media", 4, "bundle failed");
                }
            }
        }
    }

    if comps.has(5) {
        if let Some(id) = read_ssaid(&app.package) {
            next.ssaid = id;
            protocol::part(&app.package, "AndID", 2, "");
        } else {
            protocol::part(&app.package, "AndID", 3, "no SSAID");
        }
    }
    // Only a run that READ data may speak for its consistency. A backup that
    // skipped every part keeps the flag the archive that holds the data already
    // carries, instead of claiming a quiesce it never performed.
    next.consistent = match consistency {
        Some(outcome) => outcome.meta_value().to_string(),
        None => old.consistent.clone(),
    };
    write_meta(app, dir, &next)?;
    protocol::done_pkg(false, &app.package);
    Ok(())
}

fn restore_app(
    app: &targets::Target,
    dir: &Path,
    comps: Components,
    running: &BTreeSet<String>,
    free_kb: &mut HashMap<String, u64>,
) -> Result<(), String> {
    fs::create_dir_all(command::TEMP_DIR).map_err(|e| e.to_string())?;
    let app_archive = dir.join("App.shift");
    if comps.has(1)
        && app_archive.is_file()
        && !tools::package_installed(&app.package, &app.version_code)
    {
        match install::install_backup(&app_archive, &app.package, &app.label) {
            Ok(()) => protocol::install_done(&app.package),
            Err(error) => protocol::install_error(&app.package, &error),
        }
        // Quiesce a freshly installed app BEFORE its data directories are
        // overwritten below. Without this the app starts against the new
        // install, writes into /data/data/<pkg> concurrently with archive::unpack
        // restoring into the same tree, and the user gets an interleaved mix
        // of old and new data files. restore_ownership() re-enables it once
        // ownership/relabeling is finished, so this only spans the unpack.
        // The legacy launcher did the same (`cmd package disable "$pkg"`
        // after the install block).
        if tools::package_installed(&app.package, &app.version_code) {
            let _ = command::status("cmd", ["package", "disable", &app.package]);
        }
    }
    let uid = command::text(
        "stat",
        ["-c", "%u", format!("/data/data/{}", app.package).as_str()],
    )
    .trim()
    .to_string();
    let installed = !uid.is_empty();
    let mut queue = String::new();
    if installed {
        queue.push_str(&format!("{}|{}\n", app.package, uid));
    }
    let meta = Meta::read(dir);

    if !installed {
        if comps.has(2) {
            protocol::part(
                &app.package,
                "Data",
                3,
                "app not installed — installation failed or was skipped",
            );
        }
        if comps.has(4) {
            protocol::part(
                &app.package,
                "Media",
                3,
                "app not installed — installation failed or was skipped",
            );
        }
    } else {
        for cache in [
            format!("/data/data/{}/cache", app.package),
            format!("/data/data/{}/code_cache", app.package),
            format!("/data/user_de/0/{}/cache", app.package),
            format!("/data/user_de/0/{}/code_cache", app.package),
        ] {
            let path = Path::new(&cache);
            if path.is_dir()
                && !fs::symlink_metadata(path)
                    .map(|m| m.file_type().is_symlink())
                    .unwrap_or(true)
            {
                let _ = fs::remove_dir_all(path);
            }
        }
        let restore_sets = [
            (2, "Data", "/data/data", "Data.shift"),
            (2, "Data", "/data/user_de/0", "UserDe.shift"),
            (2, "Data", "/data/media/0/Android/data", "ExtData.shift"),
            (4, "Media", "/data/media/0/Android/media", "Media.shift"),
            (4, "Media", "/data/media/0/Android/obb", "Obb.shift"),
        ];
        // Once per app, before the first tree is replaced: an app that is
        // running holds the old data open and writes into the new tree while it
        // is placed — the interleaved mix the fresh-install path already guards
        // against with `cmd package disable`, on the path that did not.
        let mut quiet: Option<quiesce::Quiesce> = None;
        for (component, display, target, archive_name) in restore_sets {
            if !comps.has(component) {
                continue;
            }
            let archive_path = dir.join(archive_name);
            if !archive_path.is_file() {
                continue;
            }
            protocol::part(&app.package, display, 1, "");
            quiesce::ensure_quiet(&app.package, running, &mut quiet);
            // THIS archive's recorded size gates THIS archive's staging decision;
            // the run's own free-space reading is read once per root and reused
            // (restore::available_kb_cached).
            //
            // Per-archive, because the part totals cover several roots: gating
            // `Data.shift` on `DataExtSize` charged it for `/data/user_de/0` as
            // well, and `ExtData.shift` — which neither total accounts for at
            // all — was charged against `DataExtSize` too. Every Data archive
            // asked for two to three times what it was about to write, so a
            // nearly-full device fell back to the non-reversible in-place path
            // long before it had to. A gate must budget what the code actually
            // writes (§4.27).
            let needed = meta.staging_kb(Path::new(target));
            let available = restore::available_kb_cached(free_kb, Path::new(target), needed);
            let outcome = restore::restore_into(
                Path::new(target),
                &app.package,
                &uid,
                &archive_path,
                needed,
                available,
            );
            if command::cancelled() {
                return Err("operation cancelled".into());
            }
            if outcome.ok() {
                // The part's own states stay exactly as they were: the app
                // renders a success with an empty reason, and which placement
                // was used belongs in the log, not in the progress caption.
                protocol::raw(&format!(
                    "NOTE:RESTORE|{}|{}|{display}",
                    match outcome {
                        restore::RestoreOutcome::Staged => "staged",
                        _ => "inplace",
                    },
                    app.package
                ));
                protocol::part(&app.package, display, 2, "");
            } else {
                protocol::part(&app.package, display, 4, &outcome.reason());
            }
        }
        if comps.has(2)
            && !["Data.shift", "UserDe.shift", "ExtData.shift"]
                .iter()
                .any(|n| dir.join(n).is_file())
        {
            protocol::part(&app.package, "Data", 3, "no data in backup");
        }
        if comps.has(4)
            && !["Media.shift", "Obb.shift"]
                .iter()
                .any(|n| dir.join(n).is_file())
        {
            protocol::part(&app.package, "Media", 3, "no media in backup");
        }
    }

    if comps.has(5) {
        if meta.ssaid.is_empty() {
            protocol::part(&app.package, "AndID", 3, "no SSAID in backup");
        } else if env::var("ALLOW_SSAID").ok().as_deref() != Some("1") {
            protocol::info("SSAID restore skipped (consent off)");
            protocol::part(&app.package, "AndID", 3, "consent off");
        } else {
            protocol::part(&app.package, "AndID", 1, "");
            if write_ssaid(&app.package, &meta.ssaid) {
                protocol::part(&app.package, "AndID", 2, "");
            } else {
                protocol::part(&app.package, "AndID", 4, "not applied");
            }
        }
    }
    if comps.has(3) {
        let permissions = dir.join("Permissions.txt");
        if permissions.is_file() {
            protocol::part(&app.package, "Perm", 1, "");
            if set_permissions(&app.package, &permissions, &uid) {
                protocol::part(&app.package, "Perm", 2, "");
            } else {
                protocol::part(&app.package, "Perm", 3, "consent off or grant failed");
            }
        } else {
            protocol::part(&app.package, "Perm", 3, "no permissions in backup");
        }
    }
    if !queue.is_empty() {
        let path = Path::new(command::TEMP_DIR).join("restore_queue.list");
        use std::io::Write;
        let mut file = fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(path)
            .map_err(|e| e.to_string())?;
        file.write_all(queue.as_bytes())
            .map_err(|e| e.to_string())?;
    }
    protocol::done_pkg(true, &app.package);
    Ok(())
}

/// Report an excluded part that is still on disk, and is being kept.
///
/// State 3 is the engine's "not changed / nothing to do" state, which the app
/// already renders as a quiet chip rather than a failure. Naming the files in
/// the reason is what makes it honest: the user can see that their earlier
/// archive is still there instead of assuming it was thrown away.
fn keep_files(pkg: &str, part: &str, dir: &Path, names: &[&str]) {
    let kept: Vec<&str> = names
        .iter()
        .copied()
        .filter(|n| dir.join(n).is_file())
        .collect();
    if kept.is_empty() {
        return;
    }
    protocol::part(
        pkg,
        part,
        3,
        &format!("kept from an earlier backup: {}", kept.join(", ")),
    );
}

fn write_meta(app: &targets::Target, dir: &Path, meta: &Meta) -> Result<(), String> {
    let mut text = format!(
        "Name={}\nPackage={}\nVersion={}\nVersionCode={}\nAppSize={}\nDataExtSize={}\nMediaOBBSize={}\n",
        app.label,
        app.package,
        app.version,
        app.version_code,
        meta.app_size,
        meta.data_size,
        meta.media_size
    );
    if !meta.ssaid.is_empty() {
        text.push_str(&format!("SSAID={}\n", meta.ssaid));
    }
    // Per-archive sizes, written under the names the restore gate reads them by
    // and the app already greps for (`DataSize`, `ExtDataSize`, `MediaSize`,
    // `ObbSize` — plus `UserDeSize`, which the app's grep list is extended to
    // match). A writer that does not emit what its reader looks for is a silent
    // contract break: the reader's per-archive fallback could never fire, because
    // the combined field is always present.
    for (key, value) in [
        ("DataSize", &meta.data_root_kb),
        ("UserDeSize", &meta.user_de_kb),
        ("ExtDataSize", &meta.ext_data_kb),
        ("MediaSize", &meta.media_root_kb),
        ("ObbSize", &meta.obb_kb),
    ] {
        if !value.is_empty() {
            text.push_str(&format!("{key}={value}\n"));
        }
    }
    if !meta.consistent.is_empty() {
        text.push_str(&format!("Consistent={}\n", meta.consistent));
    }
    if app.kind == "System" {
        let dump = command::text("dumpsys", ["package", &app.package]);
        if let Some(path) = dump
            .lines()
            .find_map(|line| line.trim().strip_prefix("codePath="))
        {
            if path.starts_with("/system/")
                || path.starts_with("/product/")
                || path.starts_with("/vendor/")
                || path.starts_with("/oem/")
                || path.starts_with("/odm/")
            {
                text.push_str(&format!("SysPath={path}\n"));
            }
        }
    }
    // Streamed into a temp path and renamed over the destination, like every
    // other writer in this engine (§4.23). Writing in place was the one exception
    // left, and it is the worst possible one here: `verify::scan_apps` skips a
    // directory whose `Meta.txt` lost both `Name=` and `Package=`, so a kill
    // mid-write made the app vanish from every `--verify-apps` row and from the
    // index walk — and the digest recorded on the next line could never catch it,
    // because the app is no longer scanned at all.
    let path = dir.join("Meta.txt");
    let temp = dir.join("Meta.txt.tmp");
    fs::write(&temp, text).map_err(|e| format!("Cannot write backup metadata: {e}"))?;
    if let Err(e) = fs::rename(&temp, &path) {
        let _ = fs::remove_file(&temp);
        return Err(format!("Cannot write backup metadata: {e}"));
    }
    // Meta.txt names the archive and carries the recorded sizes the restore
    // space gate reads, so it is worth a digest like any other engine-written
    // file.
    checksum::record(&path);
    Ok(())
}

/// Per-app SSAID store. Android 14+ holds it as ABX (magic `ABX\0`), not text.
const SSAID_PATH: &str = "/data/system/users/0/settings_ssaid.xml";

/// Does this file carry the ABX magic?
///
/// Cheaper and clearer than "try to read it and see if it fails": on ABX the
/// bytes are not UTF-8 at all (0xFFFF appears in the length fields), so the old
/// `read_to_string` could only ever fail.
fn ssaid_is_abx(path: &Path) -> bool {
    fs::read(path)
        .map(|b| b.starts_with(b"ABX"))
        .unwrap_or(false)
}

/// The store as plain XML, whichever encoding it is stored in.
///
/// `abx2xml` ships in `/system/bin` from Android 14 onwards. Android 13 and
/// earlier store plain XML, which still works.
fn read_ssaid_xml(path: &Path) -> Option<String> {
    if ssaid_is_abx(path) {
        let text = command::text("abx2xml", [path.to_string_lossy().as_ref(), "-"]);
        if text.contains("<setting") {
            return Some(text);
        }
        return None;
    }
    fs::read_to_string(path).ok()
}

/// The app's uid, which the store records as the element's `name`.
fn ssaid_uid(package: &str) -> Option<String> {
    let out = command::text("pm", ["list", "packages", "-U", package]);
    out.lines()
        .find(|l| l.contains(&format!("package:{package}")))
        .and_then(|l| l.split("uid:").nth(1))
        .map(|s| s.trim().to_string())
}

/// Value of `name="…"` on one `<setting>` line.
fn ssaid_attr(line: &str, name: &str) -> Option<String> {
    let marker = format!("{name}=\"");
    let start = line.find(&marker)? + marker.len();
    let end = line[start..].find('"')? + start;
    Some(line[start..end].to_string())
}

/// The SSAID recorded for `package`, if the OS has one for it.
///
/// Attribute order is NOT guaranteed — Android 16 emits `value` before
/// `package` — so both attributes are looked up independently on the matched
/// element rather than by slicing after one of them.
fn read_ssaid(package: &str) -> Option<String> {
    if !command::valid_package(package) {
        return None;
    }
    ssaid_from_text(&read_ssaid_xml(Path::new(SSAID_PATH))?, package)
}

/// The SSAID for `package` in already-decoded XML. Pure, so the parsing — the
/// part the ABX change actually got wrong — is tested without a device.
fn ssaid_from_text(xml: &str, package: &str) -> Option<String> {
    xml.lines()
        .filter(|l| l.contains("<setting"))
        .find(|l| ssaid_attr(l, "package").as_deref() == Some(package))
        .and_then(|l| ssaid_attr(l, "value"))
        .filter(|v| !v.is_empty())
}

fn write_ssaid(package: &str, value: &str) -> bool {
    if !command::valid_package(package)
        || value.is_empty()
        || !value.bytes().all(|b| b.is_ascii_hexdigit())
    {
        return false;
    }
    let path = Path::new(SSAID_PATH);
    let abx = ssaid_is_abx(path);
    // Decode to plain XML first. On Android 14+ the file is ABX, so the old
    // `fs::read_to_string` could not succeed — it happened to fail CLOSED, which
    // is why nothing was corrupted, but it also meant a correct backup could
    // never be restored. Reading as text and writing back would have destroyed
    // the ABX framing had the read ever succeeded.
    let Some(original) = read_ssaid_xml(path) else {
        return false;
    };
    let mut found = false;
    let mut body: Vec<String> = original
        .lines()
        .map(|line| {
            if ssaid_attr(line, "package").as_deref() == Some(package) {
                found = true;
                replace_attribute(
                    &replace_attribute(line, "value", value),
                    "defaultValue",
                    value,
                )
            } else {
                line.to_string()
            }
        })
        .collect();

    if !found {
        // There is no existing entry, and on a real restore that is the NORMAL
        // case: the app is being installed, so the platform has not assigned it
        // an SSAID yet. The old code could only ever REPLACE, so it reported
        // "not applied" for every genuine restore — found on device:
        // `PART:AndID|STATE:4|REASON:not applied` immediately after the APK was
        // reinstalled.
        let Some(uid) = ssaid_uid(package) else {
            return false;
        };
        let next_id = original
            .lines()
            .filter_map(|l| ssaid_attr(l, "id"))
            .filter_map(|v| v.parse::<i64>().ok())
            .max()
            .unwrap_or(0)
            + 1;
        let entry = format!(
            "  <setting id=\"{next_id}\" name=\"{uid}\" value=\"{value}\" package=\"{package}\"              defaultValue=\"{value}\" defaultSysSet=\"false\" tag=\"null\" />"
        );
        // Keep the element inside <settings>: an element appended after the
        // closing tag is silently dropped, which would look like another
        // "not applied".
        match body.iter().rposition(|l| l.trim() == "</settings>") {
            Some(at) => body.insert(at, entry),
            None => body.push(entry),
        }
    }
    let rewritten = body.join("\n");

    // Stage, then swap in. A half-written system settings file is worse than a
    // failed restore, so the live file is only ever replaced by a completed
    // re-encode.
    //
    // The re-encode is staged in the target's OWN directory and finished with a
    // rename, never copied over the destination. This is invariant 23, and the
    // copy here broke it in the worst possible place: `fs::copy` opens the
    // destination O_TRUNC, so the original `settings_ssaid.xml` is destroyed at
    // the START of the copy, not the end — measured on the reference device,
    // a killed copy leaves the live file short and the old bytes already gone.
    // The backup could not save it, because it is only read when `fs::copy`
    // RETURNS an error: a SIGKILL or a power loss in that window returns
    // nothing, so no code path ever runs the restore. The result is a truncated
    // ABX settings file, which SettingsProvider cannot parse — for EVERY app's
    // SSAID, from one interrupted write of one app's.
    //
    // A rename is atomic within one filesystem, and `/data/local/tmp` and
    // `/data/system` are the same device (measured: both dev=65070) — but
    // staging in the target directory does not rely on that, and keeps the
    // rename inside the directory the file lives in.
    let pid = std::process::id();
    let dir = path.parent().unwrap_or(Path::new("/data/system/users/0"));
    let staged = dir.join(format!(".settings_ssaid.{pid}.tmp"));
    let xml_tmp = format!("/data/local/tmp/ssaid_{pid}.xml");
    let encoded = if abx {
        fs::write(&xml_tmp, format!("{rewritten}\n")).is_ok()
            && command::status(
                "xml2abx",
                [&xml_tmp, &staged.to_string_lossy().into_owned()],
            )
    } else {
        fs::write(&staged, format!("{rewritten}\n")).is_ok()
    };
    if !encoded {
        let _ = fs::remove_file(&xml_tmp);
        let _ = fs::remove_file(&staged);
        return false;
    }
    // Ownership and mode must match the original or SettingsProvider will
    // refuse to parse its own file. Set on the STAGED file, before it becomes
    // live, so the live file is never briefly wrong.
    let _ = command::status(
        "chown",
        ["system:system", staged.to_string_lossy().as_ref()],
    );
    let _ = command::status("chmod", ["600", staged.to_string_lossy().as_ref()]);
    let _ = command::status("restorecon", [staged.to_string_lossy().as_ref()]);
    // The whole point: one atomic act. Before it the old file is intact and
    // complete; after it the new one is. There is no state in between where
    // SettingsProvider can read a partial file.
    if fs::rename(&staged, path).is_err() {
        let _ = fs::remove_file(&xml_tmp);
        let _ = fs::remove_file(&staged);
        return false;
    }
    let _ = fs::remove_file(&xml_tmp);
    true
}

fn replace_attribute(line: &str, name: &str, value: &str) -> String {
    let marker = format!("{name}=\"");
    let Some(start) = line.find(&marker).map(|i| i + marker.len()) else {
        return line.to_string();
    };
    let Some(relative_end) = line[start..].find('"') else {
        return line.to_string();
    };
    format!(
        "{}{}{}",
        &line[..start],
        value,
        &line[start + relative_end..]
    )
}

fn get_permissions(package: &str) -> String {
    let run_allow: BTreeSet<&str> = "POST_NOTIFICATIONS ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION ACCESS_BACKGROUND_LOCATION CAMERA RECORD_AUDIO READ_CONTACTS WRITE_CONTACTS READ_CALENDAR WRITE_CALENDAR READ_CALL_LOG WRITE_CALL_LOG READ_PHONE_STATE READ_PHONE_NUMBERS CALL_PHONE READ_SMS RECEIVE_SMS RECEIVE_MMS RECEIVE_WAP_PUSH SEND_SMS READ_EXTERNAL_STORAGE WRITE_EXTERNAL_STORAGE ACCESS_MEDIA_LOCATION BLUETOOTH_SCAN BLUETOOTH_CONNECT BLUETOOTH_ADVERTISE UWB_RANGING NEARBY_WIFI_DEVICES READ_MEDIA_AUDIO READ_MEDIA_IMAGES READ_MEDIA_VIDEO READ_MEDIA_VISUAL_USER_SELECTED BODY_SENSORS BODY_SENSORS_BACKGROUND ACTIVITY_RECOGNITION ACCESS_LOCAL_NETWORK".split_whitespace().collect();
    let op_allow: BTreeSet<&str> = "SYSTEM_ALERT_WINDOW WRITE_SETTINGS REQUEST_INSTALL_PACKAGES MANAGE_EXTERNAL_STORAGE PICTURE_IN_PICTURE RUN_IN_BACKGROUND RUN_ANY_IN_BACKGROUND AUTO_REVOKE_PERMISSIONS_IF_UNUSED USE_FULL_SCREEN_INTENT SCHEDULE_EXACT_ALARM GET_USAGE_STATS LEGACY_STORAGE".split_whitespace().collect();
    let dump = command::text("dumpsys", ["package", package]);
    let mut result = String::new();
    let mut in_runtime = false;
    for line in dump.lines() {
        if line.contains("runtime permissions:") {
            in_runtime = true;
            continue;
        }
        if in_runtime
            && (line.contains("requested permissions:") || line.contains("install permissions:"))
        {
            in_runtime = false;
        }
        if in_runtime && line.contains("granted=true") {
            let name = line
                .split_whitespace()
                .next()
                .unwrap_or_default()
                .trim_end_matches(':');
            if let Some(perm) = name.strip_prefix("android.permission.") {
                if run_allow.contains(perm) {
                    result.push_str(&format!("PERM:{perm}=true\n"));
                }
            }
        }
    }
    let appops = command::text("cmd", ["appops", "get", package]);
    for line in appops.lines() {
        let line = line
            .trim()
            .strip_prefix("Uid mode:")
            .unwrap_or(line.trim())
            .trim();
        if line.is_empty() {
            continue;
        }
        let Some((name, mode)) = line.split_once(':') else {
            continue;
        };
        let name = name.trim();
        let mode = mode.trim().split(';').next().unwrap_or_default().trim();
        if (run_allow.contains(name) || op_allow.contains(name))
            && (mode.starts_with("allow") || mode.starts_with("foreground"))
        {
            result.push_str(&format!("APPOP:{name}={mode}\n"));
        }
    }
    result
}

fn set_permissions(package: &str, file: &Path, uid: &str) -> bool {
    if env::var("ALLOW_PERM").ok().as_deref() != Some("1") || !command::valid_package(package) {
        return false;
    }
    let Ok(text) = fs::read_to_string(file) else {
        return false;
    };
    let valid_uid = !uid.is_empty() && uid.bytes().all(|b| b.is_ascii_digit());
    let mut any = false;
    for line in text.lines() {
        if let Some(perm) = line.strip_prefix("PERM:").and_then(|v| v.split('=').next()) {
            if perm
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'.')
            {
                let permission = if perm.starts_with("android.permission.") {
                    perm.to_string()
                } else {
                    format!("android.permission.{perm}")
                };
                any |= command::status("cmd", ["package", "grant", package, permission.as_str()]);
            }
        } else if let Some(value) = line.strip_prefix("APPOP:") {
            let Some((op, mode)) = value.split_once('=') else {
                continue;
            };
            if !op.bytes().all(|b| b.is_ascii_uppercase() || b == b'_')
                || !["allow", "foreground", "deny", "ignore", "default"].contains(&mode)
            {
                continue;
            }
            let args: Vec<String> = if valid_uid {
                vec![
                    "appops".into(),
                    "set".into(),
                    "--uid".into(),
                    uid.into(),
                    op.into(),
                    mode.into(),
                ]
            } else {
                vec![
                    "appops".into(),
                    "set".into(),
                    package.into(),
                    op.into(),
                    mode.into(),
                ]
            };
            any |= command::status("cmd", &args);
        }
    }
    any
}

fn restore_ownership() {
    let queue = Path::new(command::TEMP_DIR).join("restore_queue.list");
    let Ok(lines) = fs::read_to_string(&queue) else {
        return;
    };
    for line in lines.lines() {
        if command::cancelled() {
            protocol::raw("ACTION:CANCELLED");
            break;
        }
        let Some((package, uid)) = line.split_once('|') else {
            continue;
        };
        if !command::valid_package(package) || !uid.bytes().all(|b| b.is_ascii_digit()) {
            continue;
        }
        // Ownership and labels live in ONE implementation, shared with the
        // journal's recovery pass: two copies of this rule drift, and the copy
        // that drifts silently is the one that only runs after a crash.
        restore::fix_ownership_and_labels(package, uid);
        let _ = command::status(
            "rm",
            [
                "-f",
                &format!("/data/data/{package}/databases/com.google.android.datatransport.events"),
                &format!(
                    "/data/data/{package}/databases/com.google.android.datatransport.events-journal"
                ),
                &format!("/data/data/{package}/no_backup/com.google.android.gms.appid-no-backup"),
                &format!("/data/data/{package}/shared_prefs/com.google.android.gms.appid.xml"),
                &format!(
                    "/data/data/{package}/shared_prefs/com.google.android.gms.measurement.prefs.xml"
                ),
            ],
        );
        let _ = command::status("cmd", ["package", "enable", package]);
        // The tree is now usable, so this package's journal entry has served its
        // purpose. Until this line runs, a killed run leaves the entry behind and
        // the next run finishes the fix instead of leaving root-owned,
        // mislabeled data the app cannot read.
        restore::clear_pending(package);
    }
    let _ = fs::remove_file(queue);
}

fn verifier_mode(enable_fast: bool) {
    if enable_fast {
        let _ = command::status("cmd", ["package", "disable", "com.android.vending"]);
        for (key, val) in [
            ("verifier_verify_adb_installs", "0"),
            ("package_verifier_enable", "0"),
        ] {
            let _ = command::status("settings", ["put", "global", key, val]);
        }
        for (key, val) in [
            ("pm.dexopt.install", "assume-verified"),
            ("pm.dexopt.install-bulk", "assume-verified"),
            ("pm.dexopt.install-bulk-downgraded", "skip"),
        ] {
            let _ = command::status("setprop", [key, val]);
        }
    } else {
        let _ = command::status("cmd", ["package", "enable", "com.android.vending"]);
        for (key, val) in [
            ("verifier_verify_adb_installs", "1"),
            ("package_verifier_enable", "1"),
        ] {
            let _ = command::status("settings", ["put", "global", key, val]);
        }
        for (key, val) in [
            ("pm.dexopt.install", "speed-profile"),
            ("pm.dexopt.install-bulk", "speed-profile"),
            ("pm.dexopt.install-bulk-downgraded", "verify"),
        ] {
            let _ = command::status("setprop", [key, val]);
        }
    }
}

fn install_apps(target_file: &str) -> Result<(), String> {
    let path = Path::new(target_file);
    if !path.is_file() {
        return Ok(());
    }
    let _ = fs::remove_file(command::CANCEL_FLAG);
    let _ = fs::remove_file(command::BUNDLE_JOURNAL);
    let fast = env::var("ALLOW_VERIFIER_DISABLE").ok().as_deref() == Some("1");
    if fast {
        protocol::info("Fast-install ON (verifier disabled by user consent)");
        verifier_mode(true);
    } else {
        protocol::info("Safe-install ON (verifier left enabled)");
    }
    let input =
        fs::read_to_string(path).map_err(|e| format!("Cannot read install targets: {e}"))?;
    for line in input.lines() {
        if command::cancelled() {
            protocol::raw("ACTION:CANCELLED");
            break;
        }
        let mut fields = line.split('|');
        let file = fields.next().unwrap_or_default();
        let package = fields.next().unwrap_or_default();
        let label = fields.next().unwrap_or_default();
        if file.is_empty() {
            continue;
        }
        match install::install_file(file, package, label) {
            Ok(()) => protocol::install_done(package),
            Err(error) => protocol::install_error(package, &error),
        }
    }
    if fast {
        verifier_mode(false);
    }
    // Gate on cancelled(), not on the flag file alone: the loop above breaks on
    // a death-cancellation too (cancelled() is true when EITHER the flag exists
    // OR the app process is gone). Testing only the flag reported GLOBAL_DONE
    // after the user swiped the app away, which the UI settles on as success.
    if !command::cancelled() {
        println!("ACTION:GLOBAL_DONE");
    }
    Ok(())
}

fn kill_jobs() {
    let path = Path::new(command::BUNDLE_JOURNAL);
    let Ok(lines) = fs::read_to_string(path) else {
        return;
    };
    for line in lines.lines() {
        let mut fields = line.splitn(2, '|');
        let Some(pid) = fields
            .next()
            .filter(|p| !p.is_empty() && p.bytes().all(|b| b.is_ascii_digit()))
        else {
            continue;
        };
        let process = fs::read(format!("/proc/{pid}/cmdline"))
            .map(|bytes| String::from_utf8_lossy(&bytes).into_owned())
            .unwrap_or_default();
        let is_archive_worker = process.starts_with("tar\0")
            || process.starts_with("/system/bin/tar\0")
            || process.starts_with("toybox\0tar\0")
            || process.starts_with("/system/bin/toybox\0tar\0")
            || process.starts_with("busybox\0tar\0")
            || process.contains("/data/adb/DroidVault/zapdos");
        if is_archive_worker && command::status("kill", [pid]) {
            println!("killed:{pid}");
        }
        if let Some(output) = fields
            .next()
            .filter(|p| p.ends_with(".shift") || p.ends_with(".shift.tmp"))
        {
            let _ = fs::remove_file(output);
        }
    }
    let _ = fs::remove_file(path);
}

/// Where recovery looks for the open-recovery script, on every ROM this app has
/// met. Named once because writing and REMOVING must agree: a script cleared at
/// three addresses and written at four would leave the destructive one behind.
const ORS_LOCATIONS: [&str; 3] = [
    "/cache/recovery",
    "/data/cache/recovery",
    "/metadata/recovery",
];

/// Remove every `openrecoveryscript` we may have written.
///
/// This is the other half of `--ors`, and it is the half that was missing. A
/// script holding `format data` / `wipe data` / `install` / `reboot` survived
/// the user backing out of the wizard, leaving the Flash tab, and the process
/// dying — and then fired on the NEXT recovery boot for any reason whatsoever,
/// with nothing on screen saying it was armed. The wizard arms a destructive
/// action; everything that walks back from it must disarm it.
pub fn clear_ors() -> bool {
    let mut cleared = false;
    for location in ORS_LOCATIONS {
        let path = Path::new(location).join("openrecoveryscript");
        if path.exists() && fs::remove_file(&path).is_ok() {
            cleared = true;
        }
    }
    if cleared {
        let _ = command::status("sync", std::iter::empty::<&str>());
    }
    cleared
}

fn write_ors(script: &str, reboot: &str) {
    let _ = command::status("mount", ["-o", "rw,remount", "/cache"]);
    let _ = command::status("mount", ["-o", "rw,remount", "/metadata"]);
    let mut wrote_any = false;
    for location in ORS_LOCATIONS {
        let _ = fs::create_dir_all(location);
        let mut content = script.to_string();
        if !reboot.is_empty() && reboot != "none" {
            content.push_str(&format!("\nreboot {reboot}"));
        }
        let path = Path::new(location).join("openrecoveryscript");
        if fs::write(&path, content).is_ok() {
            let _ = command::status("chmod", ["644", path.to_string_lossy().as_ref()]);
            wrote_any = true;
        }
    }
    // Say so when nothing landed. Every write here used to be `let _ =`, so the
    // engine exited 0 and the wizard advanced to "Everything is prepared" with
    // no script on disk — recovery would boot and do nothing, or worse, run a
    // STALE script from an earlier pass (invariant 22).
    if !wrote_any {
        protocol::error(
            "Could not write the recovery script. Check that /cache and /metadata \
             are mounted, then try again.",
        );
    }
}

#[cfg(test)]
mod tests {
    use super::{MediaAction, Meta, app_base, media_action};
    use std::path::Path;

    // --- the space gate asks about ONE archive, not the whole part ----------

    #[test]
    fn each_archive_is_gated_against_its_own_size() {
        // The part totals cover several roots. Charging every Data archive the
        // `DataExtSize` total asked each of them for its siblings' space too, so
        // a nearly-full device fell back to the non-reversible in-place path far
        // earlier than it had to — and `ExtData.shift`, which NEITHER total
        // accounts for, was charged against `DataExtSize` regardless.
        let meta = Meta {
            data_size: "100000".into(),
            media_size: "80000".into(),
            data_root_kb: "60000".into(),
            user_de_kb: "30000".into(),
            ext_data_kb: "4000".into(),
            media_root_kb: "70000".into(),
            obb_kb: "9000".into(),
            ..Meta::default()
        };
        assert_eq!(meta.staging_kb(Path::new("/data/data")), 60_000);
        assert_eq!(meta.staging_kb(Path::new("/data/user_de/0")), 30_000);
        assert_eq!(
            meta.staging_kb(Path::new("/data/media/0/Android/data")),
            4_000,
            "ExtData is its own archive and neither part total describes it"
        );
        assert_eq!(
            meta.staging_kb(Path::new("/data/media/0/Android/media")),
            70_000
        );
        assert_eq!(
            meta.staging_kb(Path::new("/data/media/0/Android/obb")),
            9_000
        );
        // The per-archive figures are a split of the totals, so what the gate
        // charges across a whole part must not exceed the total it came from —
        // the property that makes "per-archive" a split rather than a second
        // charge on top.
        let data_charged: u64 = [
            "/data/data",
            "/data/user_de/0",
            "/data/media/0/Android/data",
        ]
        .iter()
        .map(|root| meta.staging_kb(Path::new(root)))
        .sum();
        let media_charged: u64 = ["/data/media/0/Android/media", "/data/media/0/Android/obb"]
            .iter()
            .map(|root| meta.staging_kb(Path::new(root)))
            .sum();
        assert_eq!(data_charged, 94_000);
        assert!(
            data_charged <= 100_000,
            "the Data part must not be charged twice"
        );
        assert!(
            media_charged <= 80_000,
            "the Media part must not be charged twice"
        );
    }

    #[test]
    fn an_archive_from_before_per_archive_sizes_falls_back_to_the_part_total() {
        // Every existing backup records only the combined fields. The fallback
        // keeps the old over-reserving behaviour for those, which is the safe
        // direction to be wrong in, rather than charging nothing and staging
        // into a full disk.
        let meta = Meta {
            data_size: "100000".into(),
            media_size: "80000".into(),
            ..Meta::default()
        };
        assert_eq!(meta.staging_kb(Path::new("/data/data")), 100_000);
        assert_eq!(meta.staging_kb(Path::new("/data/user_de/0")), 100_000);
        assert_eq!(
            meta.staging_kb(Path::new("/data/media/0/Android/media")),
            80_000
        );
        // A recorded 0 is "not measured", not "empty" — so it falls back too
        // rather than charging the extraction nothing (§4.39).
        let measured_zero = Meta {
            data_size: "100000".into(),
            data_root_kb: "0".into(),
            ..Meta::default()
        };
        assert_eq!(measured_zero.staging_kb(Path::new("/data/data")), 100_000);
        // And a Meta.txt with no sizes at all is 0, which `should_stage` reads
        // as "stage anyway" — the pre-existing unmeasured rule, unchanged.
        assert_eq!(Meta::default().staging_kb(Path::new("/data/data")), 0);
    }

    #[test]
    fn per_archive_sizes_survive_a_meta_round_trip() {
        // A writer that does not emit what its reader looks for is a silent
        // contract break, and this one was already half-built: the app's grep
        // listed `DataSize`/`ExtDataSize`/`MediaSize`/`ObbSize` while the engine
        // wrote none of them, so the reader's per-archive fallback could never
        // fire.
        let dir = std::env::temp_dir().join(format!("dv-meta-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let text = "Name=Demo\nPackage=com.demo\nAppSize=100\nDataExtSize=300\nMediaOBBSize=200\nDataSize=150\nUserDeSize=150\nExtDataSize=7\nMediaSize=180\nObbSize=20\n";
        std::fs::write(dir.join("Meta.txt"), text).unwrap();

        let meta = Meta::read(&dir);
        assert_eq!(meta.data_root_kb, "150");
        assert_eq!(meta.user_de_kb, "150");
        assert_eq!(meta.ext_data_kb, "7");
        assert_eq!(meta.media_root_kb, "180");
        assert_eq!(meta.obb_kb, "20");
        // The combined fields still win where they are the better answer, and the
        // reader's own fallback arithmetic still reproduces the totals.
        assert_eq!(
            meta.data_root_kb.parse::<u64>().unwrap() + meta.ext_data_kb.parse::<u64>().unwrap(),
            157,
            "the app reconstructs DataExtSize as DataSize + ExtDataSize"
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    // --- the vault root is not the app base --------------------------------

    #[test]
    fn the_app_base_is_the_vaults_apps_directory() {
        // `run_apps` works in `<vault>/Apps`, and anything that covers the WHOLE
        // vault has to step back one level. Getting this wrong is silent:
        // `manifest::rebuild(<vault>/Apps)` looks for `Apps/Apps/User/...`, finds
        // nothing, and declines to write an index — which is what shipped for one
        // build and only device evidence caught.
        let base = app_base("/storage/emulated/0/DroidVault").unwrap();
        assert_eq!(base, Path::new("/storage/emulated/0/DroidVault/Apps"));
        assert_eq!(
            base.parent().unwrap(),
            Path::new("/storage/emulated/0/DroidVault"),
            "the vault root the index is written into"
        );
        // A trailing slash and a bare root must both land in the same place.
        assert_eq!(app_base("/v/").unwrap(), Path::new("/v/Apps"));
        assert_eq!(app_base("/").unwrap().parent().unwrap(), Path::new("/"));
    }

    // --- the bug: absence reported as failure ------------------------------

    #[test]
    fn an_app_with_no_media_at_all_is_a_skip_not_a_failure() {
        // The exact field report: no /data/media/.../media/<pkg> and no
        // obb/<pkg>, yet the run showed "Backup Media/OBB — bundle failed".
        assert_eq!(
            media_action(false, false, false, false),
            MediaAction::SkipNothingToDo
        );
    }

    #[test]
    fn no_media_wins_even_when_the_recorded_size_disagrees() {
        // An app that HAD media, was backed up, and has since had it removed:
        // the stale recorded size must not drag it into the pack branch.
        assert_eq!(
            media_action(false, false, false, true),
            MediaAction::SkipNothingToDo
        );
    }

    #[test]
    fn no_media_wins_even_when_an_archive_is_missing() {
        // missing_source_archive is computed from directory existence, so it
        // cannot be true here — but the ordering must hold regardless, or the
        // flag becomes a way back into the failing path.
        assert_eq!(
            media_action(false, false, true, true),
            MediaAction::SkipNothingToDo
        );
    }

    // --- real packing ----------------------------------------------------

    #[test]
    fn a_size_change_with_media_present_packs() {
        assert_eq!(media_action(true, false, false, true), MediaAction::Pack);
    }

    #[test]
    fn an_obb_only_app_packs_when_its_size_changed() {
        assert_eq!(media_action(false, true, false, true), MediaAction::Pack);
    }

    #[test]
    fn a_missing_archive_packs_even_when_sizes_match() {
        assert_eq!(media_action(true, false, true, false), MediaAction::Pack);
    }

    // --- unchanged -------------------------------------------------------

    #[test]
    fn an_unchanged_app_with_media_is_skipped() {
        assert_eq!(
            media_action(true, true, false, false),
            MediaAction::SkipUnchanged
        );
    }

    #[test]
    fn an_unchanged_app_with_only_media_is_skipped() {
        assert_eq!(
            media_action(true, false, false, false),
            MediaAction::SkipUnchanged
        );
    }

    #[test]
    fn skip_states_are_the_only_ones_that_are_not_pack() {
        // Nothing-to-do and unchanged are both skips, and neither may ever be
        // represented as a failure state in the protocol.
        assert_ne!(media_action(false, false, false, false), MediaAction::Pack);
        assert_ne!(media_action(true, true, false, false), MediaAction::Pack);
    }

    // --- SSAID parsing (ABX era) -------------------------------------------
    // Found on device: `/data/system/users/0/settings_ssaid.xml` starts with the
    // literal magic `ABX\0` on Android 14+, and contains 0xFFFF bytes, so it is
    // not UTF-8 at all. The old reader searched the raw bytes for
    // `package="pkg"` and could never match, so no backup ever recorded an
    // SSAID. Decoding is the platform's own `abx2xml`.

    const ABX_SAMPLE: &str = r#"<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<settings version="-1">
  <setting id="0" name="userkey" value="E2D0E969468BE8E1F03A2B7DE24EE860770924D149C3ED3CAA9B5268604DC8A3" package="android" defaultValue="E2D0E969468BE8E1F03A2B7DE24EE860770924D149C3ED3CAA9B5268604DC8A3" defaultSysSet="true" tag="null" />
  <setting id="4" name="10064" value="4f793f7874b50c8f" package="com.android.camera" defaultValue="4f793f7874b50c8f" defaultSysSet="true" tag="null" />
  <setting id="9" name="10245" value="5a8123ff4de50d3c" package="com.myairtelapp" defaultValue="5a8123ff4de50d3c" defaultSysSet="false" tag="null" />
</settings>"#;

    #[test]
    fn ssaid_attr_reads_a_named_attribute() {
        let line = "  <setting id=\"9\" name=\"10245\" value=\"5a8123ff4de50d3c\" package=\"com.myairtelapp\" />";
        assert_eq!(
            super::ssaid_attr(line, "package").as_deref(),
            Some("com.myairtelapp")
        );
        assert_eq!(
            super::ssaid_attr(line, "value").as_deref(),
            Some("5a8123ff4de50d3c")
        );
        assert_eq!(super::ssaid_attr(line, "missing"), None);
    }

    #[test]
    fn ssaid_is_found_regardless_of_attribute_order() {
        // Android 16 emits value BEFORE package. A reader that sliced after one
        // attribute would break the moment the platform reordered them.
        let value_first = super::ssaid_from_text(ABX_SAMPLE, "com.myairtelapp");
        let package_first = super::ssaid_from_text(
            &ABX_SAMPLE.replace(
                "value=\"5a8123ff4de50d3c\" package=\"com.myairtelapp\"",
                "package=\"com.myairtelapp\" value=\"5a8123ff4de50d3c\"",
            ),
            "com.myairtelapp",
        );
        assert_eq!(value_first.as_deref(), Some("5a8123ff4de50d3c"));
        assert_eq!(package_first.as_deref(), Some("5a8123ff4de50d3c"));
    }

    #[test]
    fn an_app_with_no_ssaid_yields_none() {
        // The common case, and the reason a backup must report "no SSAID"
        // rather than inventing one.
        assert_eq!(super::ssaid_from_text(ABX_SAMPLE, "com.not.present"), None);
    }

    #[test]
    fn the_platform_userkey_is_never_mistaken_for_an_app() {
        // The 64-hex `android` entry must never be handed out as a per-app ID.
        assert_eq!(
            super::ssaid_from_text(ABX_SAMPLE, "android").map(|v| v.len()),
            Some(64)
        );
    }

    #[test]
    fn a_prefix_of_another_package_does_not_match() {
        // Substring matching would let "com.myairte" pick up com.myairtelapp.
        assert_eq!(super::ssaid_from_text(ABX_SAMPLE, "com.myairte"), None);
        assert_eq!(super::ssaid_from_text(ABX_SAMPLE, "myairtel"), None);
    }
}

#[cfg(test)]
mod retention_tests {
    use super::*;

    /// The bug this pins: a narrower re-backup used to unlink the archives the
    /// new selection excluded, so unticking a part and backing up again destroyed
    /// data the user could otherwise still restore — unattended when a scheduled
    /// rule listed fewer parts than the last manual run.
    #[test]
    fn excluded_part_with_an_earlier_archive_is_kept() {
        assert_eq!(retained_part(false, true), RetainAction::Keep);
    }

    #[test]
    fn excluded_part_with_nothing_on_disk_says_nothing() {
        // Nothing to protect. Reporting "kept" here would be noise on a first
        // backup, and every app's first backup has nothing to keep.
        assert_eq!(retained_part(false, false), RetainAction::NothingKept);
    }

    /// Filesystem-level proof, because the pure test alone cannot catch someone
    /// reintroducing an unlink next to `keep_files`. An earlier attempt to prove
    /// this on-device was inconclusive: the engine's own `cancelled()` guard
    /// returns true unless the app process is visible, so a run driven from adb
    /// reports ACTION:CANCELLED before reaching backup_app and every file survives
    /// for the wrong reason.
    #[test]
    fn keep_files_leaves_earlier_archives_byte_for_byte() {
        let tmp = std::env::temp_dir().join(format!("dv_keep_{}", std::process::id()));
        fs::create_dir_all(&tmp).expect("temp dir");
        for name in ["Data.shift", "UserDe.shift", "ExtData.shift"] {
            fs::write(tmp.join(name), b"precious earlier backup").expect("seed");
        }
        keep_files(
            "com.x",
            "Data",
            &tmp,
            &["Data.shift", "UserDe.shift", "ExtData.shift"],
        );
        for name in ["Data.shift", "UserDe.shift", "ExtData.shift"] {
            assert_eq!(
                fs::read(tmp.join(name)).expect("still there"),
                b"precious earlier backup",
                "{name} must survive an excluded re-backup"
            );
        }
        let _ = fs::remove_dir_all(&tmp);
    }

    #[test]
    fn keep_files_is_silent_when_nothing_was_there() {
        let tmp = std::env::temp_dir().join(format!("dv_keep_empty_{}", std::process::id()));
        fs::create_dir_all(&tmp).expect("temp dir");
        // Must not panic and must not create anything: reporting a kept archive
        // that does not exist would be the same dishonesty in the other direction.
        keep_files("com.x", "Media", &tmp, &["Media.shift"]);
        assert!(!tmp.join("Media.shift").exists());
        assert_eq!(fs::read_dir(&tmp).expect("readable").count(), 0);
        let _ = fs::remove_dir_all(&tmp);
    }

    #[test]
    fn selected_part_is_left_to_the_normal_logic() {
        // A selected part must never be treated as "kept from an earlier backup":
        // it either packs or reports unchanged, and conflating the two would hide
        // a genuinely skipped part behind a reassuring message.
        assert_eq!(retained_part(true, true), RetainAction::NothingKept);
        assert_eq!(retained_part(true, false), RetainAction::NothingKept);
    }
}
