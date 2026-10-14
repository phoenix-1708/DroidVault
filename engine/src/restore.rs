//! Crash-consistent placement of a restored app's data directory.
//!
//! `archive::unpack` used to extract straight into the LIVE tree — the same
//! directory the app reads, and the only copy of its current data. A restore
//! killed mid-extract (power loss, the OOM killer, the user force-stopping the
//! app) therefore left a data directory that was neither the old data nor the
//! new: half the files from each, the previous copy gone, and nothing anywhere
//! recording that it happened.
//!
//! The engine already refuses to do this to an ARCHIVE — `archive::pack_path`
//! streams into a temp path and renames over the destination — and the same rule
//! applied to a data directory is a staging tree next to the target plus two
//! renames:
//!
//! ```text
//!   extract --> <root>/.dvstaged-<pkg>/<pkg>/…       (nothing live is touched)
//!   rename  --> <root>/<pkg> -> <root>/.dvold-<pkg>  (metadata only, same fs)
//!   rename  --> <root>/.dvstaged-<pkg>/<pkg> -> <root>/<pkg>
//!   then    --> delete .dvstaged-<pkg>, and .dvold-<pkg>
//! ```
//!
//! Every step is either a rename on the same filesystem — atomic, and free of
//! data copies — or the deletion of something already superseded.
//!
//! ## The part that is easy to miss: a replaced tree is not yet usable
//!
//! A tree created OUTSIDE the app's own directory inherits the label of the
//! parent it was made in, not the app's. Measured on the reference device
//! (Android 16): a staging tree moved into `/data/data/<pkg>` carries
//! `system_data_file` where the app needs `app_data_file:s0:c146,c257,c512,c768`
//! — and until that is corrected the app cannot read the data that was just
//! restored for it.
//!
//! `restorecon` does NOT correct it, which this file once asserted and was wrong
//! about. Measured here: `plat_file_contexts` has no `/data/data` entry at all
//! (only `/data/user` -> `system_userdir_file`), so `restorecon -R` — with or
//! without `-F` — leaves the staged tree exactly where it was and the engine
//! reported success. The label is therefore applied explicitly, from the app's
//! own data directory (see [`app_data_label`]).
//!
//! And it has to happen before the app runs, because the damage is not undone by
//! fixing the files afterwards: Chromium opens a history database it cannot read
//! by marking every row `hidden`, and Chromium's own settings survive a missing
//! profile as defaults. A restore that placed the data perfectly and labelled it
//! too late reads to the user as a successful restore that lost everything.
//!
//! Extracted files are also root-owned, and the app needs its own uid. Today
//! both fixes happen in the end-of-run queue pass (`restore_ownership`), which
//! means a run killed at app 50 leaves apps 1-49 owned by root and mislabeled —
//! a restore that reports nothing and leaves apps unable to open their own data.
//!
//! So the journal remembers the WORK, not just the files: an entry written
//! before the first rename is cleared only by the pass that fixes ownership and
//! labels. Killed anywhere in between, the next restore run finds the entry and
//! finishes the job (`Heal::CompletePending`) instead of leaving the tree
//! unusable.
//!
//! ## Cost
//!
//! Staging needs one extra tree's worth of free space while the previous copy is
//! still on disk. When the filesystem cannot afford that, placement falls back
//! to the in-place extraction the engine shipped with and says so — a restore
//! that cannot be made reversible is still a restore, but it is never reported
//! as if it were reversible. The recorded size in `Meta.txt` is the gate, and a
//! recorded 0 means "not measured", never "empty": an unmeasured size stages,
//! because the commit is what makes an optimistic guess safe (running out of
//! space rolls back rather than leaving a half-tree).

use crate::archive;
use crate::command;
use crate::protocol;
use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::OnceLock;

/// What happened to one archive's extraction.
#[derive(Debug, PartialEq, Eq)]
pub enum RestoreOutcome {
    /// Extracted to a staging tree and swapped in. The previous tree was kept
    /// until the new one was in place.
    Staged,
    /// Extracted straight into the live tree, because staging may not fit.
    InPlace,
    /// A failure after the live tree had been moved aside: it was put back.
    RolledBack(String),
    /// Nothing to roll back: the live tree was never touched.
    Failed(String),
}

impl RestoreOutcome {
    pub fn ok(&self) -> bool {
        matches!(self, RestoreOutcome::Staged | RestoreOutcome::InPlace)
    }

    /// The failure text, for the part's protocol reason. A rolled-back restore
    /// says so: the user has to know the app was left as it was, not restored.
    pub fn reason(self) -> String {
        match self {
            RestoreOutcome::Staged | RestoreOutcome::InPlace => String::new(),
            RestoreOutcome::RolledBack(reason) => format!("{reason} — previous data was put back"),
            RestoreOutcome::Failed(reason) => reason,
        }
    }
}

/// Where a staged run keeps its two trees, both siblings of the live one.
///
/// Dot-prefixed and inside the target ROOT rather than inside the app's own
/// directory: a rename must not cross filesystems, and the app's directory is
/// exactly what is being replaced.
pub fn staging_paths(root: &Path, package: &str) -> (PathBuf, PathBuf) {
    (
        root.join(format!(".dvstaged-{package}")),
        root.join(format!(".dvold-{package}")),
    )
}

/// The staged tree for one app: `<root>/.dvstaged-<pkg>/<pkg>`.
///
/// Archive members are `<pkg>/…` relative to the root (`pack_tar_args` packs
/// with `-C <root> <pkg>`), so extraction reproduces the app's directory one
/// level inside the staging container — and THAT directory is what moves into
/// place. Keying the recovery pass off the container instead would misread a
/// swap that died between the rename and the container's removal as "the tree
/// never moved", which is the one wrong answer that loses data.
pub fn staged_tree(root: &Path, package: &str) -> PathBuf {
    staging_paths(root, package).0.join(package)
}

/// Whether this extraction can afford to keep a second copy of the tree.
pub fn should_stage(recorded_kb: u64, available_kb: u64) -> bool {
    recorded_kb == 0 || available_kb >= recorded_kb
}

/// Available space in KB on the filesystem holding `path`, from `df`.
///
/// `df -P -k` is the portable spelling: one line per filesystem, six
/// whitespace-separated columns, mount point last (so spaces inside it cannot
/// shift the column before it).
pub fn available_kb(path: &Path) -> Option<u64> {
    let out = command::text("df", ["-P", "-k", &path.to_string_lossy()]);
    parse_available_kb(&out)
}

/// Pure half of [`available_kb`], so the column choice is pinned by a test.
pub fn parse_available_kb(df_output: &str) -> Option<u64> {
    df_output
        .lines()
        .skip(1) // the "Filesystem 1024-blocks Used Available …" header
        .filter(|line| !line.trim().is_empty())
        .find_map(|line| line.split_whitespace().nth(3)?.parse::<u64>().ok())
}

/// The space a part's restore is expected to need, in KB.
///
/// Taken from `Meta.txt`, written by the backup run from the same measurement
/// the app's own "Need:" estimate uses. Reading the archive to find out would
/// cost a full decompression pass over the very file about to be extracted —
/// the mistake export already paid for once (§2.36).
pub fn recorded_kb(meta_value: &str) -> u64 {
    meta_value.trim().parse().unwrap_or(0)
}

/// One in-flight placement, as written to the journal.
///
/// `staged` is empty for an in-place extraction: nothing was moved, so there is
/// nothing to roll back, but ownership and labels ARE pending and a killed run
/// must not leave the app unable to read its own data.
#[derive(Debug, PartialEq, Eq, Clone)]
pub struct Entry {
    pub root: PathBuf,
    pub package: String,
    pub uid: String,
    pub aside: PathBuf,
    pub staged: PathBuf,
}

impl Entry {
    /// `root|package|uid|aside|staged`. Paths are ours, packages and uids are
    /// validated; `|` cannot appear in any field.
    pub fn encode(&self) -> String {
        format!(
            "{}|{}|{}|{}|{}",
            self.root.display(),
            self.package,
            self.uid,
            self.aside.display(),
            self.staged.display()
        )
    }

    pub fn decode(line: &str) -> Option<Self> {
        let mut fields = line.split('|');
        let root = PathBuf::from(fields.next()?);
        let package = fields.next()?.to_string();
        let uid = fields.next()?.to_string();
        let aside = fields.next()?;
        let staged = fields.next()?;
        if fields.next().is_some() {
            return None;
        }
        if !root.is_absolute() || !command::valid_package(&package) {
            return None;
        }
        if !uid.is_empty() && !uid.bytes().all(|b| b.is_ascii_digit()) {
            return None;
        }
        // Both tree paths may legitimately be EMPTY: an in-place extraction moves
        // nothing aside and stages nothing, but its ownership fix is still owed.
        // A non-empty one must be absolute, or it could only be a corrupted line.
        let aside = if aside.is_empty() {
            PathBuf::new()
        } else {
            PathBuf::from(aside)
        };
        let staged = if staged.is_empty() {
            PathBuf::new()
        } else {
            PathBuf::from(staged)
        };
        if !aside.as_os_str().is_empty() && !aside.is_absolute() {
            return None;
        }
        if !staged.as_os_str().is_empty() && !staged.is_absolute() {
            return None;
        }
        Some(Self {
            root,
            package,
            uid,
            aside,
            staged,
        })
    }
}

/// What a journal entry means, given what is still on disk.
///
/// The decision turns on the LIVE tree, and the first version of this got it
/// wrong in a way worth recording: keying off "is the staging tree still there"
/// reads the crash **between the two renames** as "nothing moved yet" — the
/// staging tree is still present at that point, because the rename that would
/// consume it has not happened — and discarding it there deletes the only new
/// copy while the live position stays empty and the old tree sits unreferenced
/// in the aside. That is the one outcome this whole module exists to prevent.
///
/// So: a live tree is a usable tree, whichever one it is.
///
/// - live present + staging present: the swap never happened, the live tree is
///   the OLD one, untouched. Drop the extraction debris.
/// - live present + staging gone: the swap happened (or never extracted
///   anything) — the live tree is the new one, and its ownership/labels may
///   still be owed.
/// - live missing + aside present: killed between the renames. The aside is the
///   last complete copy, so put it back; the staged tree is discarded with it.
/// - live missing + no aside: nothing can be decided safely, and nothing is
///   claimed.
#[derive(Debug, PartialEq, Eq, Clone, Copy)]
pub enum Heal {
    /// Put the aside tree back; the live position is missing or incomplete.
    RollBack,
    /// The live tree was never touched: drop the staging tree.
    DiscardStaging,
    /// The swap completed but ownership/labels may still be pending (or an
    /// aside is left over): finish the job on the live tree.
    CompletePending,
    /// Nothing of ours remains and there is nothing to finish.
    Nothing,
}

pub fn heal_decision(live: bool, aside: bool, staged: bool) -> Heal {
    if live {
        if staged {
            Heal::DiscardStaging
        } else {
            Heal::CompletePending
        }
    } else if aside {
        Heal::RollBack
    } else {
        Heal::Nothing
    }
}

/// The external-data roots, whose files belong to the app's uid but to the
/// media provider's gid rather than the app's own.
const MEDIA_ROOTS: [&str; 3] = [
    "/data/media/0/Android/data",
    "/data/media/0/Android/media",
    "/data/media/0/Android/obb",
];

/// The gid each media root expects, read once per process.
///
/// The engine is one run per process and these do not change during it, so one
/// reading replaces three `stat` forks per restored app — 189 of them on a
/// 63-app run, for a value that cannot have moved.
fn media_gids() -> &'static Vec<(String, String)> {
    static GIDS: OnceLock<Vec<(String, String)>> = OnceLock::new();
    GIDS.get_or_init(|| {
        MEDIA_ROOTS
            .iter()
            .map(|root| {
                (
                    (*root).to_string(),
                    command::text("stat", ["-c", "%g", root]).trim().to_string(),
                )
            })
            .collect()
    })
}

/// Ownership and labels for a tree that was just placed.
///
/// One implementation, used by the end-of-run queue pass AND by the journal's
/// recovery pass. Two copies of a rule like this drift, and the copy that drifts
/// silently is the one that only runs after a crash.
///
/// Restored files arrive root-owned and (through a staged placement) labeled
/// `system_data_file`, so both fixes are required before the app can open its
/// own data. See the module header and [`app_data_label`] for why `restorecon`
/// alone is not one of the fixes.
pub fn fix_ownership_and_labels(package: &str, uid: &str) {
    if !command::valid_package(package) || !uid.bytes().all(|b| b.is_ascii_digit()) {
        return;
    }
    for root in ["/data/data", "/data/user_de/0"] {
        let path = format!("{root}/{package}");
        let _ = command::status("chown", ["-hR", &format!("{uid}:{uid}"), &path]);
        apply_app_data_label(&path);
    }
    for (root, gid) in media_gids() {
        if gid.is_empty() {
            continue;
        }
        let _ = command::status(
            "chown",
            ["-hR", &format!("{uid}:{gid}"), &format!("{root}/{package}")],
        );
    }
}

/// Label a just-placed tree so the app can read it.
///
/// `restorecon` runs first because on a ROM whose `file_contexts` does map app
/// data it gets the exact answer for free. Then `chcon` unconditionally, because
/// on the reference device that first step is a silent no-op and the tree stays
/// unreadable — see [`app_data_label`].
fn apply_app_data_label(path: &str) {
    let _ = command::status("restorecon", ["-R", path]);
    let label = app_data_label(&command::text("stat", ["-c", "%C", path]));
    let _ = command::status("chcon", ["-R", &label, path]);
}

/// The label a just-placed tree must carry, given the app's own data directory's
/// context.
///
/// The app's data directory is labeled by installd at install time and is never
/// itself part of a restore, so its context is the authority — and taking it
/// verbatim keeps the per-app MLS categories, which differ per install and are
/// not something to hardcode. Anything that is not an `app_data_file` context
/// means the read failed or the platform changed its naming; a plain
/// `app_data_file` still beats leaving a restored tree unreadable, so that is
/// the fallback rather than a skip.
pub fn app_data_label(dir_context: &str) -> String {
    const FALLBACK: &str = "u:object_r:app_data_file:s0";
    let ctx = dir_context.trim();
    let Some(rest) = ctx.strip_prefix("u:object_r:app_data_file") else {
        return FALLBACK.to_string();
    };
    // A context is `type` or `type:categories`. Anything else means `stat` did
    // not answer with a context at all, and passing that to `chcon` would be a
    // command that fails in a way that looks like a permissions problem.
    if !rest.is_empty() && !rest.starts_with(':') {
        return FALLBACK.to_string();
    }
    if ctx.bytes().any(|b| b.is_ascii_whitespace()) {
        return FALLBACK.to_string();
    }
    ctx.to_string()
}

/// Clear the journal entries for a package the queue pass has just fixed.
///
/// Package-wide, and deliberately so: the end-of-run pass fixes ownership and
/// labels for EVERY root of that package, so one call answers every root's entry.
/// See [`clear_pending_root`] for the narrower rule, and why it is not this one.
pub fn clear_pending(package: &str) {
    clear_pending_at(Path::new(command::RESTORE_JOURNAL), package)
}

fn clear_pending_at(path: &Path, package: &str) {
    update_journal_at(path, |entries| {
        entries
            .into_iter()
            .filter(|e| e.package != package)
            .collect()
    })
}

/// Clear the journal entry for ONE root of one package.
///
/// ## Why this is not [`clear_pending`]
///
/// An app restore places five roots under one package in a single run
/// (`/data/data`, `/data/user_de/0`, and `/data/media/0/Android/{data,media,
/// obb}`), and each one keeps its journal entry until ownership and labels are
/// fixed for it. A failure inside `restore_into` answers for the single root it
/// was working on — the others may already be placed and still pending.
///
/// Clearing the whole package there erased those siblings' entries too, and the
/// end-of-run pass that would have answered them is skipped whenever the run is
/// cancelled (`engine.rs`: `restore_ownership()` is gated on
/// `!command::cancelled()`, which is also true once the app process is gone). The
/// result is a restored tree that is root-owned and labelled `system_data_file`,
/// a part already reported as done, and no record anywhere that could repair it
/// on the next run — the exact outcome the journal exists to prevent.
pub fn clear_pending_root(root: &Path, package: &str) {
    clear_pending_root_at(Path::new(command::RESTORE_JOURNAL), root, package)
}

fn clear_pending_root_at(path: &Path, root: &Path, package: &str) {
    update_journal_at(path, |entries| {
        entries
            .into_iter()
            .filter(|e| !(e.package == package && e.root == root))
            .collect()
    })
}

/// Run the recovery pass for a previous run that was killed mid-restore.
///
/// Called before any app is touched, so a tree is repaired before a fresh
/// restore reads the same position. Reported per package: a repair the user
/// cannot see is the same class of silence as the failure it repairs.
pub fn heal_journal() -> usize {
    let path = Path::new(command::RESTORE_JOURNAL);
    let Ok(text) = fs::read_to_string(path) else {
        return 0;
    };
    let mut repaired = 0;
    let mut outstanding: Vec<Entry> = Vec::new();
    for line in text.lines().filter(|line| !line.trim().is_empty()) {
        let Some(entry) = Entry::decode(line) else {
            // Unreadable line: drop it rather than act on a guess.
            continue;
        };
        let live_path = entry.root.join(&entry.package);
        let live = live_path.exists();
        let aside = entry.aside.exists();
        let staged = entry.staged.exists();
        match heal_decision(live, aside, staged) {
            Heal::RollBack => {
                // The live position is empty or holds the half-written result:
                // put the known-good tree back. The staged tree, if one was
                // extracted, is now debris — the aside is the complete copy.
                let _ = fs::remove_dir_all(&live_path);
                if entry.staged.exists() {
                    let container = entry.staged.parent().unwrap_or(&entry.staged);
                    let _ = fs::remove_dir_all(container);
                }
                match fs::rename(&entry.aside, &live_path) {
                    Ok(()) => {
                        protocol::raw(&format!(
                            "NOTE:RESTORE|rolled-back|{}|an interrupted restore was undone",
                            entry.package
                        ));
                        repaired += 1;
                    }
                    Err(error) => {
                        protocol::raw(&format!(
                            "NOTE:RESTORE|failed|{}|could not put the previous data back: {error}",
                            entry.package
                        ));
                        // Keep the entry: the aside is still the only copy.
                        outstanding.push(entry);
                    }
                }
            }
            Heal::DiscardStaging => {
                let container = entry.staged.parent().unwrap_or(&entry.staged);
                let _ = fs::remove_dir_all(container);
                repaired += 1;
            }
            Heal::CompletePending => {
                // The swap landed, but the pass that makes the tree usable may
                // never have run.
                if aside {
                    let _ = fs::remove_dir_all(&entry.aside);
                }
                fix_ownership_and_labels(&entry.package, &entry.uid);
                protocol::raw(&format!(
                    "NOTE:RESTORE|completed|{}|ownership and labels finished after an interrupted run",
                    entry.package
                ));
                repaired += 1;
            }
            Heal::Nothing => {}
        }
    }
    write_journal(&outstanding);
    repaired
}

/// The journal path is threaded through rather than read from the constant inside
/// each helper, so the recovery and clearing rules can be tested against a temp
/// file. Those rules decide whether an interrupted restore is repairable, and a
/// test that had to write to `/data/adb` to exercise them would never be written
/// — which is how a rule this consequential ended up untested.
fn update_journal_at(path: &Path, transform: impl FnOnce(Vec<Entry>) -> Vec<Entry>) {
    let entries = match fs::read_to_string(path) {
        Ok(text) => text
            .lines()
            .filter_map(Entry::decode)
            .collect::<Vec<Entry>>(),
        Err(_) => Vec::new(),
    };
    write_journal_at(path, &transform(entries));
}

fn write_journal(entries: &[Entry]) {
    write_journal_at(Path::new(command::RESTORE_JOURNAL), entries)
}

fn write_journal_at(path: &Path, entries: &[Entry]) {
    if entries.is_empty() {
        let _ = fs::remove_file(path);
        return;
    }
    let body: Vec<String> = entries.iter().map(Entry::encode).collect();
    let _ = fs::write(path, format!("{}\n", body.join("\n")));
}

fn journal_add(entry: &Entry) -> bool {
    journal_add_at(Path::new(command::RESTORE_JOURNAL), entry)
}

fn journal_add_at(path: &Path, entry: &Entry) -> bool {
    use std::io::Write;
    if let Some(parent) = path.parent() {
        let _ = fs::create_dir_all(parent);
    }
    match fs::OpenOptions::new().create(true).append(true).open(path) {
        Ok(mut file) => file
            .write_all(format!("{}\n", entry.encode()).as_bytes())
            .is_ok(),
        Err(_) => false,
    }
}

/// What the recovery record being unwritable means for a placement.
///
/// Pure, and taking BOTH write results as arguments rather than reading them from
/// inside, so the rule can actually be tested: the caller reaches this only when
/// `RESTORE_JOURNAL` is unwritable, which on a test host happens for *every*
/// call (there is no `/data/adb` to write to), so a test of the surrounding code
/// path cannot tell a refusal from the `archive::unpack` failure that follows it
/// and passes either way.
///
/// Refuses rather than extracting. In place would work, and `restore_ownership`
/// would fix the tree if the run finished — but a run that is killed leaves the
/// app root-owned with nothing written down to repair it, which is the state the
/// journal exists to prevent. Refusing is recoverable (fix the directory, retry);
/// a mislabelled app is not.
///
/// The first write is the staged entry and the second the in-place fallback. Only
/// when BOTH fail is there no record at all; one surviving is enough for the
/// pending-ownership fix, which is all an in-place extraction ever needed it for.
fn journal_verdict(staged_written: bool, inline_written: bool) -> Option<RestoreOutcome> {
    if staged_written || inline_written {
        None
    } else {
        Some(RestoreOutcome::Failed(
            "cannot record a restore journal entry, so this data cannot be restored safely".into(),
        ))
    }
}

/// What to report when the staged tree could not be swapped into place.
///
/// Pure, so the verdict rule is pinned: a rollback is a claim that something was
/// put back, and it is only true when a live tree was moved aside. On a freshly
/// installed app there is nothing at `live`, so nothing was moved and nothing was
/// restored — reporting `RolledBack` there told the user "previous data was put
/// back" about an app that had no previous data, which is §4.22's rule stated
/// plainly: a verdict describes the work, not what the code would have done.
fn placement_failed(had_live: bool, reason: &str) -> RestoreOutcome {
    if had_live {
        RestoreOutcome::RolledBack(reason.to_string())
    } else {
        RestoreOutcome::Failed(reason.to_string())
    }
}

/// Extract one archive into `root`, leaving the tree it replaces recoverable.
///
/// Returns the placement outcome; ownership and labels remain pending until the
/// run's queue pass fixes them (which is also what clears the journal entry).
pub fn restore_into(
    root: &Path,
    package: &str,
    uid: &str,
    archive: &Path,
    recorded_kb: u64,
    available_kb: u64,
) -> RestoreOutcome {
    let (container, aside) = staging_paths(root, package);
    let staged = staged_tree(root, package);
    let live = root.join(package);

    // Leftovers from a killed run for this app, in the only order that is safe:
    // a stray aside next to a live tree is a superseded copy nothing is using,
    // and a stray container is extraction debris. Both go before the journal is
    // written for THIS attempt, so the entry that follows describes it alone.
    if aside.exists() && live.exists() {
        let _ = fs::remove_dir_all(&aside);
    }
    let _ = fs::remove_dir_all(&container);

    if !should_stage(recorded_kb, available_kb) {
        // Honest degradation: the filesystem cannot hold a second copy, so this
        // extract is not reversible. The engine's original behaviour, and the
        // journal still records the pending ownership fix.
        let entry = Entry {
            root: root.to_path_buf(),
            package: package.to_string(),
            uid: uid.to_string(),
            aside: PathBuf::new(),
            staged: PathBuf::new(),
        };
        let recorded = journal_add(&entry);
        if let Some(outcome) = journal_verdict(recorded, recorded) {
            return outcome;
        }
        return match archive::unpack(archive, root) {
            Ok(true) => RestoreOutcome::InPlace,
            Ok(false) => RestoreOutcome::Failed("archive extraction failed".into()),
            Err(error) => RestoreOutcome::Failed(error.to_string()),
        };
    }

    let entry = Entry {
        root: root.to_path_buf(),
        package: package.to_string(),
        uid: uid.to_string(),
        aside: aside.clone(),
        staged: staged.clone(),
    };
    // Recorded BEFORE the first rename, so a process killed at any point after
    // this leaves an entry describing what is in flight.
    let staged_recorded = journal_add(&entry);
    if !staged_recorded {
        // No journal means no recovery for a swap, so take the path whose
        // failure mode we already understand instead of one we cannot repair.
        let _ = fs::remove_dir_all(&container);
        let inline = Entry {
            root: root.to_path_buf(),
            package: package.to_string(),
            uid: uid.to_string(),
            aside: PathBuf::new(),
            staged: PathBuf::new(),
        };
        // The STAGED entry's own result is passed through as well as the
        // inline's, not just the inline's: `journal_verdict` has to be able to
        // see that both writes failed, and passing a literal in place of the
        // first one is precisely the bug — it makes the rule untestable and
        // unreachable. Both writes failing leaves NO record at all, and an
        // in-place entry still owes an ownership fix, so this refuses rather
        // than extracting.
        let inline_recorded = journal_add(&inline);
        if let Some(outcome) = journal_verdict(staged_recorded, inline_recorded) {
            let _ = fs::remove_dir_all(&container);
            return outcome;
        }
        return match archive::unpack(archive, root) {
            Ok(true) => RestoreOutcome::InPlace,
            Ok(false) => RestoreOutcome::Failed("archive extraction failed".into()),
            Err(error) => RestoreOutcome::Failed(error.to_string()),
        };
    }

    let abandon = |reason: &str| {
        let _ = fs::remove_dir_all(&container);
        clear_pending_root(root, package);
        RestoreOutcome::Failed(reason.to_string())
    };

    match archive::unpack(archive, &container) {
        Ok(true) => {}
        Ok(false) => return abandon("archive extraction failed"),
        Err(error) => return abandon(&error.to_string()),
    }
    if !staged.is_dir() {
        return abandon("archive held no directory for this app");
    }

    let had_live = live.exists();
    if had_live && fs::rename(&live, &aside).is_err() {
        return abandon("could not move the current data aside");
    }
    if fs::rename(&staged, &live).is_err() {
        // Put the previous tree back: this is the failure staging exists for.
        // The app is left exactly as it was, not half-restored.
        if had_live {
            let _ = fs::rename(&aside, &live);
        }
        let _ = fs::remove_dir_all(&container);
        clear_pending_root(root, package);
        return placement_failed(had_live, "could not place the restored data");
    }
    // The new tree is live. Only now may the old one go. The journal entry
    // stays: ownership and labels are still pending, and that is exactly what a
    // killed run must not lose.
    let _ = fs::remove_dir_all(&container);
    if had_live {
        let _ = fs::remove_dir_all(&aside);
    }
    RestoreOutcome::Staged
}

/// Free space for `root`, read once per run.
///
/// Cached because each reading is a fork, and the answer moves by about one old
/// tree per app — the aside is deleted as soon as its tree is superseded — so a
/// value taken at the start of a run stays a usable gate for its duration.
pub fn available_kb_cached(cache: &mut HashMap<String, u64>, root: &Path, recorded_kb: u64) -> u64 {
    // A recorded size of 0 short-circuits the gate, so the fork is skipped for
    // the archives that cannot use the answer.
    if recorded_kb == 0 {
        return 0;
    }
    let key = root.to_string_lossy().into_owned();
    if let Some(value) = cache.get(&key) {
        return *value;
    }
    let value = available_kb(root).unwrap_or(0);
    cache.insert(key, value);
    value
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn df_parsing_takes_the_available_column() {
        let out = "Filesystem       1024-blocks      Used Available Capacity Mounted on\n\
                   /dev/block/dm-46   238049244 138945788  99103456      59% /mnt/pass_through/0/emulated\n";
        assert_eq!(parse_available_kb(out), Some(99_103_456));
    }

    #[test]
    fn df_parsing_survives_a_header_only_and_junk() {
        assert_eq!(parse_available_kb(""), None);
        assert_eq!(
            parse_available_kb("Filesystem 1024-blocks Used Available Capacity Mounted on\n"),
            None
        );
        assert_eq!(parse_available_kb("garbage\n"), None);
    }

    #[test]
    fn a_mount_point_with_spaces_does_not_shift_the_column() {
        // -P keeps the mount point last on the line, so its spaces are harmless.
        let out = "Filesystem 1024-blocks Used Available Capacity Mounted on\n\
                   /dev/fuse 1024 0 4096 0% /mnt/media with spaces\n";
        assert_eq!(parse_available_kb(out), Some(4096));
    }

    #[test]
    fn staging_paths_are_siblings_of_the_live_tree() {
        let root = Path::new("/data/data");
        let (container, aside) = staging_paths(root, "com.foo");
        assert_eq!(container, Path::new("/data/data/.dvstaged-com.foo"));
        assert_eq!(aside, Path::new("/data/data/.dvold-com.foo"));
        assert_eq!(
            staged_tree(root, "com.foo"),
            Path::new("/data/data/.dvstaged-com.foo/com.foo")
        );
        // Both stay siblings: a rename that crossed a filesystem would silently
        // become a copy and stop being atomic.
        assert_eq!(container.parent(), Some(root));
        assert_eq!(aside.parent(), Some(root));
    }

    #[test]
    fn an_unmeasured_size_stages_but_a_known_one_must_fit() {
        assert!(should_stage(0, 0), "not measured is not zero");
        assert!(should_stage(1024, 1024), "exactly enough fits");
        assert!(should_stage(1024, 2048));
        assert!(!should_stage(4096, 1024), "known-not-to-fit goes in place");
    }

    #[test]
    fn recorded_sizes_parse_and_junk_is_unmeasured() {
        assert_eq!(recorded_kb("2378"), 2378);
        assert_eq!(recorded_kb(" 40 "), 40);
        assert_eq!(recorded_kb(""), 0);
        assert_eq!(recorded_kb("-"), 0);
        assert_eq!(recorded_kb("12 MB"), 0);
    }

    fn entry() -> Entry {
        Entry {
            root: PathBuf::from("/data/data"),
            package: "com.foo".into(),
            uid: "10123".into(),
            aside: PathBuf::from("/data/data/.dvold-com.foo"),
            staged: PathBuf::from("/data/data/.dvstaged-com.foo/com.foo"),
        }
    }

    #[test]
    fn journal_lines_round_trip() {
        assert_eq!(Entry::decode(&entry().encode()), Some(entry()));
    }

    #[test]
    fn an_in_place_entry_round_trips_with_no_staging_path() {
        let in_place = Entry {
            aside: PathBuf::new(),
            staged: PathBuf::new(),
            ..entry()
        };
        assert_eq!(Entry::decode(&in_place.encode()), Some(in_place));
    }

    #[test]
    fn journal_lines_that_cannot_be_acted_on_are_dropped() {
        assert_eq!(Entry::decode(""), None);
        assert_eq!(
            Entry::decode("/data/data|com.foo|10123|/a"),
            None,
            "too few"
        );
        assert_eq!(
            Entry::decode("/data/data|com.foo|10123|/a|/b|extra"),
            None,
            "too many"
        );
        assert_eq!(
            Entry::decode("relative|com.foo|10123|/a|/b"),
            None,
            "a relative root cannot be acted on"
        );
        assert_eq!(
            Entry::decode("/data/data|not a package|10123|/a|/b"),
            None,
            "the package must be valid before anything is moved"
        );
        assert_eq!(
            Entry::decode("/data/data|com.foo|not-a-uid|/a|/b"),
            None,
            "a non-numeric uid cannot be chowned to"
        );
        assert_eq!(
            Entry::decode("/data/data|com.foo|10123|relative|/b"),
            None,
            "an aside must be absolute"
        );
    }

    /// Killed during extraction: the live tree was never touched, and the
    /// staging tree is debris.
    #[test]
    fn a_live_tree_plus_staging_is_debris() {
        assert_eq!(heal_decision(true, false, true), Heal::DiscardStaging);
    }

    /// Killed after placing: the live tree IS the restored one, so rolling back
    /// here would undo a good restore. Finish the pending fix instead.
    #[test]
    fn a_live_tree_with_no_staging_is_the_placed_one() {
        assert_eq!(heal_decision(true, true, false), Heal::CompletePending);
        // The aside was already deleted; ownership is still owed.
        assert_eq!(heal_decision(true, false, false), Heal::CompletePending);
    }

    /// **The case that must never be misread.** A run killed between the two
    /// renames has an EMPTY live position while the staging tree is still
    /// present (the rename that consumes it has not happened). Reading that as
    /// "nothing moved yet" deletes the only new copy and leaves the app with no
    /// data directory at all.
    #[test]
    fn killed_between_the_renames_rolls_back_never_discards() {
        assert_eq!(heal_decision(false, true, true), Heal::RollBack);
        assert_eq!(heal_decision(false, true, false), Heal::RollBack);
        for aside in [true, false] {
            assert_ne!(
                heal_decision(false, aside, true),
                Heal::DiscardStaging,
                "a missing live tree must never be read as 'the swap did not happen' (aside={aside})"
            );
        }
    }

    #[test]
    fn nothing_is_claimed_when_nothing_can_be_decided() {
        assert_eq!(heal_decision(false, false, false), Heal::Nothing);
        assert_eq!(heal_decision(false, false, true), Heal::Nothing);
    }

    // --- the journal is keyed on (root, package), not on package alone --------

    /// A journal file in a temp dir, plus the entries written to it.
    fn journal_fixture(tag: &str, entries: &[Entry]) -> std::path::PathBuf {
        let path = std::env::temp_dir().join(format!(
            "dv-journal-{tag}-{}-{:?}",
            std::process::id(),
            std::thread::current().id()
        ));
        let _ = fs::remove_file(&path);
        for entry in entries {
            assert!(journal_add_at(&path, entry), "fixture must be writable");
        }
        path
    }

    fn journal_entries(path: &Path) -> Vec<Entry> {
        fs::read_to_string(path)
            .map(|text| text.lines().filter_map(Entry::decode).collect())
            .unwrap_or_default()
    }

    fn entry_for(root: &str, package: &str) -> Entry {
        Entry {
            root: PathBuf::from(root),
            package: package.to_string(),
            uid: "10123".into(),
            aside: PathBuf::from(format!("{root}/.dvold-{package}")),
            staged: PathBuf::from(format!("{root}/.dvstaged-{package}")),
        }
    }

    #[test]
    fn a_failed_root_does_not_erase_a_sibling_roots_pending_record() {
        // One app restore places five roots under one package in a single run, and
        // each keeps its entry until ownership and labels are fixed FOR IT. The
        // end-of-run pass that answers those is skipped whenever the run is
        // cancelled, so a failure clearing the whole package left already-placed
        // roots unrecorded: root-owned, mislabelled, reported as done, and with
        // nothing left for `heal_journal` to repair.
        let path = journal_fixture(
            "sibling",
            &[
                entry_for("/data/data", "com.foo"),
                entry_for("/data/user_de/0", "com.foo"),
            ],
        );
        clear_pending_root_at(&path, Path::new("/data/user_de/0"), "com.foo");

        let left = journal_entries(&path);
        assert_eq!(left.len(), 1, "only the failing root's entry may go");
        assert_eq!(
            left[0].root,
            PathBuf::from("/data/data"),
            "the already-placed root's pending ownership record must survive"
        );
        assert_eq!(left[0].package, "com.foo");

        // A different package is never touched, and neither is a different root
        // of the same package.
        let path = journal_fixture(
            "scope",
            &[
                entry_for("/data/data", "com.foo"),
                entry_for("/data/user_de/0", "com.foo"),
                entry_for("/data/data", "com.bar"),
            ],
        );
        clear_pending_root_at(&path, Path::new("/data/media/0/Android/obb"), "com.foo");
        assert_eq!(
            journal_entries(&path).len(),
            3,
            "a root that was never worked on must clear nothing"
        );
        clear_pending_root_at(&path, Path::new("/data/data"), "com.bar");
        let left = journal_entries(&path);
        assert_eq!(left.len(), 2);
        assert!(
            left.iter().all(|e| e.package == "com.foo"),
            "another package's entries are not this root's to clear"
        );
        let _ = fs::remove_file(&path);
    }

    #[test]
    fn the_queue_pass_still_clears_every_root_of_its_package() {
        // The package-wide clear is still right where it is used: the end-of-run
        // pass fixes ownership and labels for EVERY root of that package, so one
        // call answers all of them. Pinning it keeps the narrower rule from being
        // "simplified" back into this one.
        let path = journal_fixture(
            "queue",
            &[
                entry_for("/data/data", "com.foo"),
                entry_for("/data/user_de/0", "com.foo"),
                entry_for("/data/media/0/Android/obb", "com.foo"),
                entry_for("/data/data", "com.bar"),
            ],
        );
        clear_pending_at(&path, "com.foo");
        let left = journal_entries(&path);
        assert_eq!(left.len(), 1);
        assert_eq!(left[0].package, "com.bar");
        let _ = fs::remove_file(&path);
    }

    #[test]
    fn a_rollback_is_only_claimed_when_something_was_put_back() {
        // `reason()` renders `RolledBack` as "… previous data was put back". On a
        // freshly installed app there is no live tree to move aside, so nothing
        // was put back and the claim is false — the user would read it as
        // reassurance about data that never existed.
        let rolled = placement_failed(true, "could not place the restored data");
        assert_eq!(
            rolled,
            RestoreOutcome::RolledBack("could not place the restored data".into())
        );
        assert!(!rolled.ok());
        assert!(rolled.reason().contains("previous data was put back"));

        let fresh = placement_failed(false, "could not place the restored data");
        assert_eq!(
            fresh,
            RestoreOutcome::Failed("could not place the restored data".into())
        );
        assert!(!fresh.ok());
        assert_eq!(
            fresh.reason(),
            "could not place the restored data",
            "a failure with nothing to roll back must not claim a rollback"
        );
    }

    #[test]
    fn a_journal_that_cannot_be_written_at_all_refuses_rather_than_extracting() {
        // The second `journal_add` result used to be discarded, so an unwritable
        // journal produced an in-place extract with NO record — and an in-place
        // entry still owes an ownership fix, so a run killed after that point
        // left the app root-owned with nothing to repair it.
        let refused = journal_verdict(false, false).expect("both writes failed");
        assert!(
            !refused.ok(),
            "nothing may be reported as restored when no record of it exists"
        );
        assert!(
            matches!(refused, RestoreOutcome::Failed(_)),
            "a refusal is Failed, not RolledBack — nothing was put back"
        );
        let reason = refused.reason();
        assert!(
            reason.contains("journal"),
            "the user is told what is actually wrong, not 'extraction failed': {reason}"
        );
        assert!(!reason.contains("put back"), "no rollback may be claimed");

        // ONE surviving write is enough: an in-place extraction only ever needed
        // the record for its pending-ownership fix, and losing that would refuse
        // restores on a device whose journal directory is merely awkward rather
        // than unusable.
        for (staged, inline) in [(true, false), (false, true), (true, true)] {
            assert_eq!(
                journal_verdict(staged, inline),
                None,
                "a surviving entry is a record, so the extraction may proceed                  (staged={staged}, inline={inline})"
            );
        }
    }

    #[test]
    fn the_label_comes_from_the_apps_own_directory_categories_included() {
        // Measured on the reference device: the app's data dir reads
        // `u:object_r:app_data_file:s0:c146,c257,c512,c768`, and those categories
        // are per-install. Hardcoding them would be wrong on the next ROM; taking
        // the directory's own context cannot be.
        assert_eq!(
            app_data_label("u:object_r:app_data_file:s0:c146,c257,c512,c768\n"),
            "u:object_r:app_data_file:s0:c146,c257,c512,c768",
            "the app's own context is the authority, verbatim"
        );
        assert_eq!(
            app_data_label("u:object_r:app_data_file:s0"),
            "u:object_r:app_data_file:s0"
        );
        // `type:categories` is the other legal shape — categories with no
        // sensitivity is still a context, not garbage.
        assert_eq!(
            app_data_label("u:object_r:app_data_file:c146,c257,c512,c768"),
            "u:object_r:app_data_file:c146,c257,c512,c768"
        );
    }

    #[test]
    fn a_non_app_data_context_falls_back_rather_than_becoming_a_chcon_argument() {
        // The exact context a staged tree arrives with. Passing THIS to chcon
        // would leave the app unable to read its own data, silently.
        assert_eq!(
            app_data_label("u:object_r:system_data_file:s0"),
            "u:object_r:app_data_file:s0"
        );
        // stat failing (empty), a `chcon` error on stdout, or a stat format this
        // build does not know — all must land on the fallback, not pass through.
        for junk in [
            "",
            "   ",
            "\n",
            "stat: bad format",
            "app_data_file",
            "app_data_fileX:s0",
            // Passes a bare prefix check but is not `type[:categories]`, which is
            // the only way that guard earns its keep.
            "u:object_r:app_data_fileX:s0",
            "u:object_r:app_data_files:s0",
            "u:object_r:app_data_file_",
        ] {
            assert_eq!(
                app_data_label(junk),
                "u:object_r:app_data_file:s0",
                "garbage from stat must never become the label ({junk:?})"
            );
        }
        // Two words is a command that fails like a permissions error, which is
        // the one diagnosis nobody would think to check.
        assert_eq!(
            app_data_label("u:object_r:app_data_file:s0 c146"),
            "u:object_r:app_data_file:s0"
        );
    }
}
