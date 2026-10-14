//! Archive verification.
//!
//! A backup that cannot be read back is worse than no backup, because the user
//! only finds out at restore time — after they have already wiped the device or
//! flashed a ROM. `archive_valid` existed but nothing exposed it, so nothing
//! checked. This walks the vault and reports each app's archives, which lets the
//! UI refuse a corrupt archive BEFORE a restore starts rather than half-restoring
//! an app and leaving it in a state neither the app nor the user expects.

use crate::archive;
use crate::checksum;
use crate::command;
use crate::manifest;
use crate::protocol;
use std::collections::HashMap;
use std::path::{Path, PathBuf};

/// One app's archive, as read off disk.
pub struct ArchiveEntry {
    pub label: String,
    pub pkg: String,
    pub dir: PathBuf,
    /// `(component id, file name)` in reporting order: App, Data, Media, Perm.
    pub parts: Vec<(u8, String)>,
}

/// Read every app directory under `<base>/Apps/{User,System}` that has a Meta.txt.
///
/// Meta.txt is the marker an archive directory is only considered real once it
/// exists, so a half-written directory from a killed run is skipped rather than
/// reported as corrupt.
pub fn scan_apps(base: &Path) -> Vec<ArchiveEntry> {
    let mut out = Vec::new();
    let mut roots: Vec<PathBuf> = Vec::new();
    for kind in ["User", "System"] {
        let dir = base.join("Apps").join(kind);
        let Ok(entries) = std::fs::read_dir(&dir) else {
            continue;
        };
        for entry in entries.flatten() {
            roots.push(entry.path());
        }
    }
    // Deterministic order so the report reads the same way twice.
    roots.sort();

    for dir in roots {
        let meta = dir.join("Meta.txt");
        if !meta.is_file() {
            continue;
        }
        let (label, pkg) = read_meta(&meta);
        if label.is_empty() && pkg.is_empty() {
            continue;
        }
        let mut parts = Vec::new();
        for (id, names) in [
            (1u8, ["App.shift"].as_slice()),
            (
                2,
                ["Data.shift", "ExtData.shift", "UserDe.shift"].as_slice(),
            ),
            (4, ["Media.shift", "Obb.shift"].as_slice()),
            (3, ["Permissions.txt"].as_slice()),
        ] {
            for name in names {
                if dir.join(name).is_file() {
                    parts.push((id, (*name).to_string()));
                    break; // Data has three names; report the part once.
                }
            }
        }
        out.push(ArchiveEntry {
            label: if label.is_empty() {
                dir.file_name()
                    .unwrap_or_default()
                    .to_string_lossy()
                    .into_owned()
            } else {
                label
            },
            pkg,
            dir,
            parts,
        });
    }
    out
}

/// `Meta.txt` carries `Name=` and `Package=`; a missing or malformed line yields "".
fn read_meta(path: &Path) -> (String, String) {
    let Ok(text) = std::fs::read_to_string(path) else {
        return (String::new(), String::new());
    };
    let mut label = String::new();
    let mut pkg = String::new();
    for line in text.lines() {
        if let Some(v) = line.strip_prefix("Name=") {
            label = v.trim().to_string();
        } else if let Some(v) = line.strip_prefix("Package=") {
            pkg = v.trim().to_string();
        }
    }
    (label, pkg)
}

/// How the digests recorded beside an app's files compare with the bytes on
/// disk now.
///
/// Kept apart from `corrupt` on purpose. "Not readable" and "readable but not
/// the bytes that were recorded" are different findings with different fixes:
/// the first means the archive is gone and must be backed up again, the second
/// means something wrote to a file that nothing was supposed to touch. Rolling
/// them together would also report a legacy archive (no digest recorded, see
/// `checksum::Check::Unrecorded`) as either verified or damaged, and both would
/// be wrong.
#[derive(Debug, Default, PartialEq, Eq)]
pub struct DigestTally {
    /// Recorded and equal.
    pub matched: usize,
    /// Recorded and different — files whose bytes changed under the record.
    pub mismatched: Vec<String>,
    /// No digest recorded for this file (written before sidecars existed, or by
    /// the app rather than the engine).
    pub unrecorded: usize,
    /// Digests written for the first time during this verify.
    pub backfilled: usize,
}

/// True when the index recorded a successful read-back of the exact bytes now
/// on disk.
///
/// Two digests are compared: the one the index recorded when it last read this
/// file back, and one just computed from the file. Nothing else is consulted —
/// in particular no modification time. On FUSE the mtime of a file the engine
/// rewrote through a rename is not evidence about its content: a same-size edit
/// on the reference device landed in the same second as the marker and was then
/// skipped by every later verify, so a damaged archive kept reporting as fine.
/// Hashing costs a read of the file and saves its decompression, and it is the
/// only one of the two cheap tests that can be wrong in the safe direction.
/// A path as the index names it, relative to the vault — or an empty string when
/// it is not under the vault at all, which the index cannot carry.
fn relative_path(base: &Path, path: &Path) -> String {
    path.strip_prefix(base)
        .map(|p| p.to_string_lossy().into_owned())
        .unwrap_or_default()
}

fn read_back_confirmed(
    base: &Path,
    path: &Path,
    index: &manifest::ReadBack,
    actual: Option<&str>,
) -> bool {
    match (index.read_back_digest(base, path), actual) {
        (Some(marked), Some(actual)) => marked.eq_ignore_ascii_case(actual),
        // No marker, or no digest (an unreadable file): read it, and let the
        // archive check say why it failed.
        _ => false,
    }
}

/// Result of checking one app.
pub struct Verdict {
    pub label: String,
    pub pkg: String,
    pub checked: usize,
    pub corrupt: Vec<String>,
    pub bytes_ok: u64,
    pub digest: DigestTally,
    /// Parts not decompressed because the index already recorded these exact
    /// bytes as read back successfully. Reported, never hidden: a verify that
    /// skipped most of the vault must not read like one that unpacked it.
    pub skipped: usize,
    /// Parts read in this run: `checked - skipped`, so the two counters always
    /// add up to what was examined.
    ///
    /// Kept apart from [`Verdict::read_back`], which also carries `Meta.txt` and
    /// drives the index marker. `Meta.txt` is checked because a truncated one
    /// makes the directory unrecognisable, but it is not one of the five parts,
    /// and counting it here made a repeat verify of a two-part app report
    /// "SKIPPED:2 READ:1" — a part that was never read, on a screen whose whole
    /// job is to say how much was (found on device, 2026-10-12).
    pub read_parts: usize,
    /// Parts read back successfully in this run, as `(vault-relative path,
    /// size)`. Plumbing for the index marker, not a report — it is written only
    /// after the part was actually read.
    pub read_back: Vec<(String, u64)>,
    /// Vault-relative paths whose recorded digest disagreed with the bytes on
    /// disk. Plumbing for the index too, in the other direction: a marker these
    /// files already carried has to go, or the next rebuild carries it forward
    /// (their sidecar is untouched, so nothing about the entry looks changed).
    pub refuted: Vec<String>,
    /// Parts not decompressed whose `.sha256` record was rewritten from the
    /// digest the skip proved (see `checksum::reconcile`).
    ///
    /// Reported rather than silent: the vault was carrying a record that
    /// disagreed with its own bytes, which is a thing that happened to it, and
    /// `sha256sum -c` would have failed on those files until this run.
    pub reconciled: usize,
}

impl Verdict {
    pub fn ok(&self) -> bool {
        self.corrupt.is_empty()
    }
}

/// Component 3 is a plain-text permission snapshot, not an archive.
pub const PART_PERMISSIONS: u8 = 3;

/// Check every part of one app. A part counts as corrupt when it exists but
/// cannot be read; a missing part is simply absent and is not an error.
///
/// The component id matters. `Permissions.txt` was run through the archive
/// reader, which of course rejects plain text, so EVERY app that had a
/// permission snapshot was reported corrupt — found on device as
/// `VERIFY_APP ... PARTS:3|CORRUPT:Permissions.txt|STATUS:CORRUPT` for the whole
/// vault. That is the same defect class as the "bundle failed" skip (X-05):
/// absence, or a different kind of artefact, reported as damage.
///
/// This mattered well beyond the summary line: the pre-restore integrity gate
/// reads this report, so a false "corrupt" would have refused to restore
/// anything at all.
pub fn verify_app(entry: &ArchiveEntry, base: &Path, index: &manifest::ReadBack) -> Verdict {
    // Every part plus `Meta.txt` is hashed in ONE pass, before anything is
    // decompressed. The digest is needed twice — to decide whether a part can be
    // left undecompressed, and to compare the bytes with the digest the vault
    // recorded — and computing it once is what keeps a re-verify from reading
    // the vault twice.
    let mut paths: Vec<PathBuf> = entry
        .parts
        .iter()
        .map(|(_, name)| entry.dir.join(name))
        .collect();
    let meta = entry.dir.join("Meta.txt");
    if meta.is_file() {
        paths.push(meta);
    }
    let actual = checksum::hash_files(&paths);

    let mut corrupt = Vec::new();
    let mut bytes_ok = 0u64;
    let mut readable: Vec<&String> = Vec::new();
    let mut skipped = 0usize;
    let mut reconciled = 0usize;
    let mut backfilled_skipped = 0usize;
    for (id, name) in &entry.parts {
        let path = entry.dir.join(name);
        let digest = actual.get(&path.to_string_lossy().into_owned());
        // A part whose bytes are exactly the ones the index read back is not
        // decompressed again. This is the whole point of the marker:
        // re-verifying an unchanged vault used to mean decompressing all of it.
        //
        // Note what is being skipped and what is not. The file is still read —
        // that is how the digest above exists at all. What is avoided is
        // `archive_valid`, a full zstd decompression, which on the reference
        // device costs several times a plain read of the same bytes.
        if read_back_confirmed(base, &path, index, digest.map(String::as_str)) {
            // These bytes are the ones the index read back, so the skip is
            // earned. Their `.sha256` is a SECOND record of the same digest,
            // written by a different writer, and `sha256sum -c` reads that one —
            // so a sidecar edited outside the app is invisible to the skip test,
            // and the vault would go on telling two stories about one file.
            // Rewriting it is safe for exactly the reason the skip is: the bytes
            // are already proven, so a disagreement is a stale record rather
            // than evidence of a change.
            match checksum::reconcile(&path, digest.map(String::as_str)) {
                checksum::Reconcile::Rewritten => reconciled += 1,
                checksum::Reconcile::Backfilled => backfilled_skipped += 1,
                checksum::Reconcile::Agreed | checksum::Reconcile::Failed => {}
            }
            skipped += 1;
            continue;
        }
        let ok = if *id == PART_PERMISSIONS {
            // A permission snapshot is valid when it can be opened. Its content
            // may legitimately be short, so emptiness is not damage.
            std::fs::File::open(&path).is_ok()
        } else {
            archive::archive_valid(&path)
        };
        if ok {
            if let Ok(meta) = std::fs::metadata(&path) {
                // Bytes actually read back. A skipped part is deliberately not
                // counted: nothing was read.
                bytes_ok += meta.len();
            }
            readable.push(name);
        } else {
            corrupt.push(name.clone());
        }
    }
    let (mut digest, read_back) = tally_digests(entry, &readable, base, &actual);
    digest.backfilled += backfilled_skipped;
    let refuted: Vec<String> = digest
        .mismatched
        .iter()
        .map(|name| relative_path(base, &entry.dir.join(name)))
        .filter(|path| !path.is_empty())
        .collect();
    let checked = entry.parts.len();
    Verdict {
        label: entry.label.clone(),
        pkg: entry.pkg.clone(),
        checked,
        corrupt,
        bytes_ok,
        digest,
        skipped,
        read_parts: checked - skipped,
        read_back,
        refuted,
        reconciled,
    }
}

/// Compare each readable file's recorded digest with a digest of the bytes on
/// disk, and record one for a file that has none.
///
/// Meta.txt is included although it is not one of the five parts: it carries the
/// label, the package and the recorded sizes, and a truncated one is the
/// difference between "this app can be restored" and a directory the scanner no
/// longer recognises as a backup at all. It is checked here without joining the
/// part count, so a per-part progress figure still adds up.
/// ## What becomes evidence for the next run
///
/// A file that was read back successfully — its digest matched what the vault
/// recorded, or it had no record and one was written from these very bytes — is
/// reported as `read_back`. Only those become index markers. A digest that
/// DISAGREED never does: marking it would let the finding age out of the next
/// report instead of standing until a real backup replaces the file.
fn tally_digests(
    entry: &ArchiveEntry,
    readable: &[&String],
    base: &Path,
    actual: &HashMap<String, String>,
) -> (DigestTally, Vec<(String, u64)>) {
    // The caller already hashed this whole app in one `sha256sum` — a spawn per
    // file is pure overhead when each file read is the expensive part (see
    // `checksum::hash_files`), and hashing here a second time would double the
    // read cost of every verify for nothing.
    let mut paths: Vec<PathBuf> = readable.iter().map(|name| entry.dir.join(name)).collect();
    let meta = entry.dir.join("Meta.txt");
    if meta.is_file() {
        paths.push(meta);
    }
    let mut tally = DigestTally::default();
    let mut read_back = Vec::new();
    for path in &paths {
        let name = path
            .file_name()
            .map(|n| n.to_string_lossy().into_owned())
            .unwrap_or_default();
        let key = path.to_string_lossy().into_owned();
        let recorded = checksum::read_sidecar(path);
        let good =
            match checksum::compare(recorded.as_deref(), actual.get(&key).map(String::as_str)) {
                checksum::Check::Match => {
                    tally.matched += 1;
                    true
                }
                checksum::Check::Mismatch => {
                    tally.mismatched.push(name);
                    false
                }
                checksum::Check::Unrecorded => {
                    tally.unrecorded += 1;
                    // Back-fill only when nothing is recorded. The bytes have just
                    // been read, so an archive from before sidecars existed gains a
                    // digest at no extra cost — and a digest that already disagreed
                    // is never overwritten, because it is the only evidence that the
                    // bytes changed. `good` follows the write: a digest that could
                    // not be recorded is not evidence about anything.
                    match actual.get(&key) {
                        Some(digest) => {
                            let wrote = checksum::write_sidecar(path, digest);
                            tally.backfilled += usize::from(wrote);
                            wrote
                        }
                        None => false,
                    }
                }
            };
        if good {
            // Relative to the vault, because the index moves with the vault.
            let relative = path
                .strip_prefix(base)
                .map(|p| p.to_string_lossy().into_owned());
            if let (Ok(relative), Ok(metadata)) = (relative, std::fs::metadata(path)) {
                read_back.push((relative, metadata.len()));
            }
        }
    }
    (tally, read_back)
}

/// CLI entry: `--verify-apps <vault path>`.
///
/// Reports one line per app and a summary, so the caller can show real results
/// rather than a bare pass/fail for the whole vault.
pub fn verify_apps(base_path: &str) -> Result<(), String> {
    if !base_path.starts_with('/') || base_path.contains('\n') || base_path.contains('\r') {
        return Err("Invalid backup folder".into());
    }
    let base = PathBuf::from(base_path);
    let apps = scan_apps(&base);
    if apps.is_empty() {
        protocol::info("No app backups found in this folder");
        protocol::raw("VERIFY_DONE|TOTAL:0|OK:0|CORRUPT:0|BYTES:0");
        return Ok(());
    }

    let total = apps.len();
    // What the index already knows was read back. No index, or an unreadable
    // one, simply means nothing is skipped — never that something may be
    // assumed.
    let indexed = manifest::read(&base).unwrap_or_default();
    let known = manifest::ReadBack::from_manifest(&indexed);
    // One verdict per app, kept as they are produced. The summary used to call
    // `verify_app` a SECOND time over every app, which read every archive in the
    // vault twice — on the one path where the cost is a full decompression of
    // the whole vault, and on the path that gates a restore.
    let mut read_back: Vec<(String, u64)> = Vec::new();
    let mut refuted: Vec<String> = Vec::new();
    let mut verdicts: Vec<Verdict> = Vec::with_capacity(total);
    for (index, entry) in apps.iter().enumerate() {
        let verdict = verify_app(entry, &base, &known);
        read_back.extend(verdict.read_back.iter().cloned());
        refuted.extend(verdict.refuted.iter().cloned());
        if !verdict.digest.mismatched.is_empty() {
            protocol::info(&format!(
                "{}: recorded digest does not match the bytes on disk ({})",
                verdict.label,
                verdict.digest.mismatched.join(", ")
            ));
        }
        protocol::raw(&app_line(index, total, &verdict));
        verdicts.push(verdict);
        // Cancelling a long verify is pointless work, so stop rather than keep
        // streaming results the caller has abandoned.
        //
        // `checkpoint`, not `cancelled`: the full check spawns `pidof`, and one
        // spawn per app is a few seconds of pure overhead over a real vault
        // (measured ~15 ms each).
        if command::checkpoint() {
            protocol::raw("VERIFY_DONE|TOTAL:0|OK:0|CORRUPT:0|BYTES:0|CANCELLED:1");
            return Ok(());
        }
    }

    let ok = verdicts.iter().filter(|v| v.ok()).count();
    let hash_changed: usize = verdicts.iter().map(|v| v.digest.mismatched.len()).sum();
    let hash_unrecorded: usize = verdicts.iter().map(|v| v.digest.unrecorded).sum();
    let hash_backfilled: usize = verdicts.iter().map(|v| v.digest.backfilled).sum();
    let reconciled: usize = verdicts.iter().map(|v| v.reconciled).sum();
    let skipped: usize = verdicts.iter().map(|v| v.skipped).sum();
    let read: usize = verdicts.iter().map(|v| v.read_parts).sum();
    // Record what was read back — so the next verification of an unchanged vault
    // does not have to read it again — and clear the marker on what this run
    // refuted. Best-effort: the report above is already complete, and an index
    // that could not be written costs the next run time, not correctness.
    //
    // The rebuild runs for a refutation alone, too: an app whose every part was
    // hashed and found changed marks nothing, and skipping the write there would
    // leave exactly the stale markers this is here to remove.
    let indexed = (!read_back.is_empty() || !refuted.is_empty())
        && manifest::mark_read_back(&base, &read_back, &refuted);
    // `CORRUPT` counts archives that cannot be READ. A digest that disagrees is
    // reported as its own number rather than folded in: the archive in front of
    // us still reads back, and what changed is the bytes, which is a different
    // thing to tell the user and a different thing to do about it.
    protocol::raw(&format!(
        "VERIFY_DONE|TOTAL:{total}|OK:{ok}|CORRUPT:{}|BYTES:0|HASH_CHANGED:{hash_changed}|HASH_UNRECORDED:{hash_unrecorded}|HASH_BACKFILLED:{hash_backfilled}|HASH_RECONCILED:{reconciled}|SKIPPED:{skipped}|READ:{read}|MARKED:{}|INDEX:{}",
        total - ok,
        read_back.len(),
        if indexed { "updated" } else { "not-updated" }
    ));
    Ok(())
}

/// CLI entry: `--verify-device <vault path>` — the device-data archives.
///
/// These are `.shift` files under `Device/`, not per-app directories, and they
/// are the only copy of SMS, calls and contacts on the device — so they get the
/// same two questions the app archives get: does it read back, and are these the
/// bytes the vault recorded.
///
/// The writer here is the app, not this engine (see `DeviceManager.packArchive`),
/// and the digest is only ever trusted as far as its writer maintains it: both
/// sides write the sidecar at the same moment as the archive, and the engine only
/// back-fills one for a file that has none.
pub fn verify_device(base_path: &str) -> Result<(), String> {
    if !base_path.starts_with('/') || base_path.contains('\n') || base_path.contains('\r') {
        return Err("Invalid backup folder".into());
    }
    let base = PathBuf::from(base_path);
    let dir = base.join("Device");
    let mut total = 0usize;
    let mut ok = 0usize;
    let mut bytes = 0u64;
    let mut skipped = 0usize;
    let mut changed: Vec<String> = Vec::new();
    let mut refuted: Vec<String> = Vec::new();
    let mut unrecorded = 0usize;
    let mut backfilled = 0usize;
    let mut reconciled = 0usize;
    let Ok(entries) = std::fs::read_dir(&dir) else {
        protocol::info("No device data archive folder found");
        protocol::raw("VERIFY_DEVICE_DONE|TOTAL:0|OK:0|CORRUPT:0|BYTES:0");
        return Ok(());
    };
    let mut names: Vec<String> = entries
        .flatten()
        .map(|e| e.file_name().to_string_lossy().into_owned())
        .filter(|n| n.ends_with(".shift"))
        .collect();
    names.sort();
    let indexed = manifest::read(&base).unwrap_or_default();
    let known = manifest::ReadBack::from_manifest(&indexed);
    let mut read_back: Vec<(String, u64)> = Vec::new();
    for name in names {
        total += 1;
        let path = dir.join(&name);
        // Hashed once, then used twice: to decide whether the archive has to be
        // decompressed at all, and to compare against the digest the vault
        // recorded. See `read_back_confirmed` — bytes decide, never a timestamp.
        let actual = checksum::hash_file(&path);
        if read_back_confirmed(&base, &path, &known, actual.as_deref()) {
            ok += 1;
            skipped += 1;
            // The same two-records-one-digest rule as the app half: the marker
            // earns the skip, and a `.sha256` that disagrees with bytes the
            // marker just vouched for is a stale record — rewritten from them,
            // or `sha256sum -c` keeps failing on an archive this app calls fine.
            let reconciled_now = match checksum::reconcile(&path, actual.as_deref()) {
                checksum::Reconcile::Rewritten => {
                    reconciled += 1;
                    true
                }
                checksum::Reconcile::Backfilled => {
                    backfilled += 1;
                    true
                }
                checksum::Reconcile::Agreed | checksum::Reconcile::Failed => false,
            };
            protocol::raw(&format!(
                "VERIFY_DEVICE_ITEM|NAME:{name}|STATUS:OK|SKIPPED:1|RECONCILED:{}",
                usize::from(reconciled_now)
            ));
            continue;
        }
        if archive::archive_valid(&path) {
            ok += 1;
            let size = std::fs::metadata(&path).ok().map(|m| m.len());
            if let Some(size) = size {
                bytes += size;
            }
            let recorded = checksum::read_sidecar(&path);
            let verdict = match checksum::compare(recorded.as_deref(), actual.as_deref()) {
                checksum::Check::Match => "ok",
                checksum::Check::Mismatch => {
                    changed.push(name.clone());
                    refuted.push(relative_path(&base, &path));
                    "changed"
                }
                checksum::Check::Unrecorded => {
                    unrecorded += 1;
                    match actual.as_deref() {
                        Some(digest) if checksum::write_sidecar(&path, digest) => {
                            backfilled += 1;
                            "recorded"
                        }
                        _ => "unrecorded",
                    }
                }
            };
            if (verdict == "ok" || verdict == "recorded")
                && let (Ok(relative), Some(size)) = (path.strip_prefix(&base), size)
            {
                read_back.push((relative.to_string_lossy().into_owned(), size));
            }
            protocol::raw(&device_item_line(&name, true, verdict));
        } else {
            protocol::raw(&device_item_line(&name, false, "unrecorded"));
        }
        if command::checkpoint() {
            protocol::raw("VERIFY_DEVICE_DONE|TOTAL:0|OK:0|CORRUPT:0|BYTES:0|CANCELLED:1");
            return Ok(());
        }
    }
    let indexed = (!read_back.is_empty() || !refuted.is_empty())
        && manifest::mark_read_back(&base, &read_back, &refuted);
    protocol::raw(&format!(
        "VERIFY_DEVICE_DONE|TOTAL:{total}|OK:{ok}|CORRUPT:{}|BYTES:{bytes}|HASH_CHANGED:{}|HASH_MISMATCH:{}|HASH_UNRECORDED:{unrecorded}|HASH_BACKFILLED:{backfilled}|HASH_RECONCILED:{reconciled}|SKIPPED:{skipped}|READ:{}|INDEX:{}",
        total - ok,
        changed.len(),
        changed.join(","),
        read_back.len(),
        if indexed { "updated" } else { "not-updated" }
    ));
    Ok(())
}
/// The `VERIFY_APP` line for one app.
///
/// Pure and separate from the emission, because the escaping rule cannot be
/// tested at the call site: `protocol::field` is correct in isolation, and the
/// bug this guards against is a caller that simply does not call it. Every
/// vault-sourced value — the label and package `read_meta` found, the part names
/// off the directory listing — goes through it, because a vault is reachable
/// from an imported `.dvbackup.tar.gz` and a `|` in a label forges fields while
/// a newline splits the line into a junk row the app still counts.
fn app_line(index: usize, total: usize, verdict: &Verdict) -> String {
    format!(
        "VERIFY_APP|INDEX:{}|TOTAL:{}|LABEL:{}|PKG:{}|PARTS:{}|CORRUPT:{}|BYTES:{}|STATUS:{}|HASHED:{}|HASH_CHANGED:{}|HASH_UNRECORDED:{}|HASH_MISMATCH:{}|SKIPPED:{}|READ:{}|RECONCILED:{}",
        index + 1,
        total,
        protocol::field(&verdict.label),
        protocol::field(&verdict.pkg),
        verdict.checked,
        protocol::field(&verdict.corrupt.join(",")),
        verdict.bytes_ok,
        if verdict.ok() { "OK" } else { "CORRUPT" },
        verdict.digest.matched,
        verdict.digest.mismatched.len(),
        verdict.digest.unrecorded,
        protocol::field(&verdict.digest.mismatched.join(", ")),
        verdict.skipped,
        verdict.read_parts,
        verdict.reconciled
    )
}

/// The `VERIFY_DEVICE_ITEM` line for one device archive.
///
/// Pure for the same reason as [`app_line`]: `name` is a `read_dir` listing, so
/// it is whatever the vault holds, and BOTH branches have to escape it — the
/// corrupt branch was the second one to emit it raw, which is the argument for
/// building the line in one place instead of at each call site.
///
/// `hash` is the engine's own word for the digest comparison: `"ok"`,
/// `"recorded"`, `"changed"` or `"unrecorded"`. The app reads only `changed`
/// and treats anything else as unknown, so the spelling has to stay as it was.
fn device_item_line(name: &str, ok: bool, hash: &str) -> String {
    format!(
        "VERIFY_DEVICE_ITEM|NAME:{}|STATUS:{}|HASH:{}",
        protocol::field(name),
        if ok { "OK" } else { "CORRUPT" },
        hash
    )
}

#[cfg(test)]
mod tests {
    use super::{scan_apps, verify_app};

    /// Unique temp dir per test; no tempfile dependency needed.
    ///
    /// `std::env::temp_dir()`, NOT a device path: these run on the build host,
    /// which cannot write to /data/local/tmp.
    fn tempdir(tag: &str) -> String {
        let nanos = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_nanos())
            .unwrap_or(0);
        std::env::temp_dir()
            .join(format!("dvtest-{tag}-{nanos}"))
            .to_string_lossy()
            .into_owned()
    }

    use super::*;
    use std::fs;

    fn tmp(tag: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("dv-verify-{tag}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn reads_name_and_package_from_meta() {
        let base = tmp("meta");
        let app = base.join("Apps/User/Drive");
        fs::create_dir_all(&app).unwrap();
        fs::write(
            app.join("Meta.txt"),
            "Name=Drive\nPackage=com.google.android.apps.drive\nVersion=1.2\nSSAID=deadbeef\n",
        )
        .unwrap();

        let found = scan_apps(&base);
        assert_eq!(found.len(), 1);
        assert_eq!(found[0].label, "Drive");
        assert_eq!(found[0].pkg, "com.google.android.apps.drive");
        let _ = fs::remove_dir_all(&base);
    }

    #[test]
    fn a_directory_without_meta_is_not_reported() {
        // A half-written directory from a killed run must be skipped, not
        // reported as a corrupt archive: it was never a backup to begin with.
        let base = tmp("nometa");
        let app = base.join("Apps/User/Partial");
        fs::create_dir_all(&app).unwrap();
        fs::write(app.join("App.shift"), b"not really an archive").unwrap();

        assert!(scan_apps(&base).is_empty());
        let _ = fs::remove_dir_all(&base);
    }

    #[test]
    fn the_three_data_names_collapse_to_one_part() {
        // Data.shift, ExtData.shift and UserDe.shift are one logical part.
        // Reporting three would inflate the part count and make a per-part
        // progress bar claim 5/5 for a 3-part backup.
        let base = tmp("data");
        let app = base.join("Apps/System/App");
        fs::create_dir_all(&app).unwrap();
        fs::write(app.join("Meta.txt"), "Name=App\nPackage=com.x\n").unwrap();
        for name in ["Data.shift", "ExtData.shift", "UserDe.shift"] {
            fs::write(app.join(name), b"x").unwrap();
        }

        let found = scan_apps(&base);
        assert_eq!(found[0].parts.len(), 1);
        assert_eq!(found[0].parts[0].0, 2);
        let _ = fs::remove_dir_all(&base);
    }

    #[test]
    fn parts_are_reported_in_canonical_order() {
        // App, Data, Media, Perm — Permissions last because it is metadata, not
        // bulk data. A different order here would disagree with the run order.
        let base = tmp("order");
        let app = base.join("Apps/User/App");
        fs::create_dir_all(&app).unwrap();
        fs::write(app.join("Meta.txt"), "Name=App\nPackage=com.x\n").unwrap();
        for name in ["Permissions.txt", "Media.shift", "Data.shift", "App.shift"] {
            fs::write(app.join(name), b"x").unwrap();
        }

        let ids: Vec<u8> = scan_apps(&base)[0]
            .parts
            .iter()
            .map(|(id, _)| *id)
            .collect();
        assert_eq!(ids, vec![1, 2, 4, 3]);
        let _ = fs::remove_dir_all(&base);
    }

    #[test]
    fn a_missing_part_is_absent_not_corrupt() {
        // Only files that EXIST are checked. An app backed up APK-only must not
        // be reported as having a broken Data part.
        let base = tmp("absent");
        let app = base.join("Apps/User/App");
        fs::create_dir_all(&app).unwrap();
        fs::write(app.join("Meta.txt"), "Name=App\nPackage=com.x\n").unwrap();
        fs::write(app.join("App.shift"), b"x").unwrap();

        let found = scan_apps(&base);
        assert_eq!(found[0].parts.len(), 1);
        let _ = fs::remove_dir_all(&base);
    }

    #[test]
    fn meta_with_no_name_falls_back_to_the_directory() {
        let base = tmp("noname");
        let app = base.join("Apps/User/Weird App");
        fs::create_dir_all(&app).unwrap();
        fs::write(app.join("Meta.txt"), "Package=com.x\n").unwrap();

        assert_eq!(scan_apps(&base)[0].label, "Weird App");
        let _ = fs::remove_dir_all(&base);
    }

    #[test]
    fn a_relative_base_path_is_rejected() {
        // Same guard as app_base(): a relative path escapes the vault.
        assert!(verify_apps("relative/path").is_err());
        assert!(verify_apps("bad\npath").is_err());
        assert!(verify_device("relative").is_err());
    }

    // --- the false "corrupt" that read as damage ---------------------------

    #[test]
    fn a_permission_snapshot_is_not_judged_as_an_archive() {
        // Found on device: every app with a Permissions.txt came back
        // "STATUS:CORRUPT", because a plain text file cannot be read by the
        // archive reader. `verify_app` must branch on the component id.
        let base = std::path::PathBuf::from(tempdir("perm-snapshot"));
        let dir = base.join("Apps/User/T");
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("Meta.txt"), "Name=T\nPackage=com.t\n").unwrap();
        std::fs::write(
            dir.join("Permissions.txt"),
            "android.permission.CAMERA\nandroid.permission.INTERNET\n",
        )
        .unwrap();
        let entry = scan_apps(&base)
            .into_iter()
            .next()
            .expect("Meta.txt should make this a real archive dir");
        let v = verify_app(&entry, &base, &Default::default());
        assert!(
            !v.corrupt.iter().any(|c| c == "Permissions.txt"),
            "plain-text permission snapshot reported corrupt: {:?}",
            v.corrupt
        );
        assert!(v.ok(), "app should be healthy, got {:?}", v.corrupt);
    }

    #[test]
    fn a_snapshot_that_is_not_a_real_file_is_absent_not_corrupt() {
        // Why the fix cannot silently skip the check: `scan_apps` only records
        // a part when `is_file()` is true, so anything it hands `verify_app` is
        // openable. A dangling symlink is therefore never recorded at all —
        // absent, not damage. Pinned because it is what makes the id-based
        // branch in `verify_app` a strict improvement over the old behaviour.
        let base = std::path::PathBuf::from(tempdir("perm-dangling"));
        let dir = base.join("Apps/User/T");
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("Meta.txt"), "Name=T\nPackage=com.t\n").unwrap();
        std::os::unix::fs::symlink("/nonexistent/nope", dir.join("Permissions.txt")).unwrap();
        let entry = scan_apps(&base)
            .into_iter()
            .next()
            .expect("scan should see the dir");
        assert!(
            !entry.parts.iter().any(|(_, n)| n == "Permissions.txt"),
            "a dangling symlink must not be recorded as a part"
        );
        let v = verify_app(&entry, &base, &Default::default());
        assert!(
            v.corrupt.is_empty(),
            "absent is not damage, got {:?}",
            v.corrupt
        );
    }

    #[test]
    fn an_app_with_a_snapshot_and_an_archive_is_healthy() {
        // The exact on-device shape: App.shift present, Permissions.txt
        // present. Both readable means healthy.
        //
        // The archive half of this only means something on a device: placing a
        // real `.shift` needs `zapdos`, so on the build host `pack_files`
        // produces nothing and the app is checked as snapshot-only. It is left
        // as it is rather than padded with an assertion that would pass on both
        // for different reasons — see `a_marked_part_is_not_read_again_...` for
        // a version that proves its own preconditions either way.
        let base = std::path::PathBuf::from(tempdir("healthy"));
        let dir = base.join("Apps/User/T");
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("Meta.txt"), "Name=T\nPackage=com.t\n").unwrap();
        std::fs::write(dir.join("Permissions.txt"), "android.permission.CAMERA\n").unwrap();
        // A minimal real archive via the crate's own writer: pack one file
        // under Apps/User/T so the archive root matches what pack_path uses.
        let src = base.join("Apps/User/T");
        archive::pack_files(&[src.join("Meta.txt")], &dir.join("App.shift")).ok();
        let entry = scan_apps(&base)
            .into_iter()
            .next()
            .expect("scan should see the dir");
        let v = verify_app(&entry, &base, &Default::default());
        assert!(v.ok(), "expected healthy, got {:?}", v.corrupt);
    }

    // --- what a repeat verify may skip -------------------------------------

    /// A part that can be "read back" on the build host.
    ///
    /// The permission snapshot is the one part judged by opening it rather than
    /// by running the archive reader, and the reader needs `zapdos`, which only
    /// exists on a device. Using it keeps this test honest on both: an
    /// `App.shift` here would be a real archive on the device and a file
    /// `pack_files` could not even produce on the host, which is how the
    /// neighbouring archive tests came to pass without checking anything.
    /// Two records of one digest must not be able to disagree.
    ///
    /// The skip test reads the index's read-back marker; `sha256sum -c` — which
    /// `checksum` advertises as verifying a vault without this project — reads
    /// the `.sha256` beside the archive. Verified on the reference device: a
    /// sidecar set to all zeros was skipped as unchanged and the app called the
    /// archive fine, while `sha256sum -c` failed on the same file.
    #[test]
    fn a_device_record_that_disagrees_with_the_marker_is_rewritten() {
        let base = PathBuf::from(tempdir("device-reconcile"));
        let dir = base.join("Device");
        fs::create_dir_all(&dir).unwrap();
        let archive = dir.join("Messages.shift");
        // A skip never looks inside the file, so its contents need not be an
        // archive here: the marker is what decides, and that is the point.
        fs::write(&archive, b"whatever the vault holds").unwrap();
        let digest = checksum::hash_file(&archive).expect("sha256sum on the build host");
        assert!(checksum::write_sidecar(&archive, &digest));

        // The index is built while the two records agree, and every entry is
        // marked read back — which is what makes the next run skip it.
        let mut index = manifest::build(&base);
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_secs();
        for mark in &mut index.entries {
            mark.verified = now;
        }
        assert!(manifest::write(&base, &index));

        // Now the record beside the archive is edited outside the app.
        let wrong = "0".repeat(64);
        assert!(checksum::write_sidecar(&archive, &wrong));

        verify_device(base.to_str().unwrap()).expect("verify runs");

        assert_eq!(
            checksum::read_sidecar(&archive).as_deref(),
            Some(digest.as_str()),
            "the marker proved which bytes these are, so the stale record is \
             rewritten from them: otherwise this app calls the archive fine while \
             `sha256sum -c` fails on the same file"
        );
        let _ = fs::remove_dir_all(&base);
    }

    #[test]
    fn a_skipped_part_reconciles_its_record_and_a_changed_one_keeps_it() {
        let base = PathBuf::from(tempdir("reconcile-part"));
        let dir = base.join("Apps/User/T");
        fs::create_dir_all(&dir).unwrap();
        fs::write(dir.join("Meta.txt"), "Name=T\nPackage=com.t\n").unwrap();
        let snapshot = dir.join("Permissions.txt");
        fs::write(&snapshot, "android.permission.CAMERA\n").unwrap();
        let digest = checksum::hash_file(&snapshot).expect("sha256sum on the build host");
        assert!(checksum::write_sidecar(&snapshot, &digest));
        let entry = scan_apps(&base)
            .into_iter()
            .next()
            .expect("scan should see the dir");

        let mut index = manifest::build(&base);
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_secs();
        for mark in &mut index.entries {
            mark.verified = now;
        }
        assert!(manifest::write(&base, &index));
        let known = manifest::ReadBack::from_manifest(&index);

        // The record is edited outside the app. The bytes are untouched.
        assert!(checksum::write_sidecar(&snapshot, &"0".repeat(64)));
        let skip = verify_app(&entry, &base, &known);
        assert_eq!(
            skip.skipped, 1,
            "the marker still earns the skip: the bytes are what decides"
        );
        assert_eq!(skip.reconciled, 1, "and the record is brought back in line");
        assert_eq!(
            checksum::read_sidecar(&snapshot).as_deref(),
            Some(digest.as_str())
        );
        assert!(
            skip.ok() && skip.digest.mismatched.is_empty(),
            "reconciling a stale record is not a finding: these are the bytes the \
             vault recorded"
        );

        // Now the BYTES change, same length, while the record keeps describing
        // what was written. That record is the evidence of the change, so the run
        // must leave it exactly as it is.
        fs::write(&snapshot, b"android.permission.CAMERB\n").unwrap();
        let second = verify_app(&entry, &base, &known);
        assert_eq!(second.skipped, 0, "a changed part is always read");
        assert_eq!(
            second.digest.mismatched,
            vec!["Permissions.txt".to_string()]
        );
        assert_eq!(second.reconciled, 0);
        assert_eq!(
            checksum::read_sidecar(&snapshot).as_deref(),
            Some(digest.as_str()),
            "the record is what proves the bytes changed, and overwriting it would \
             erase the finding instead of reporting it"
        );
        let _ = fs::remove_dir_all(&base);
    }

    #[test]
    fn a_marked_part_is_not_read_again_and_an_unmarked_one_is() {
        let base = std::path::PathBuf::from(tempdir("skip-verify"));
        let dir = base.join("Apps/User/T");
        fs::create_dir_all(&dir).unwrap();
        fs::write(dir.join("Meta.txt"), "Name=T\nPackage=com.t\n").unwrap();
        let snapshot = dir.join("Permissions.txt");
        fs::write(&snapshot, "android.permission.CAMERA\n").unwrap();
        let digest = checksum::hash_file(&snapshot).expect("sha256sum on the build host");
        assert!(checksum::write_sidecar(&snapshot, &digest));
        let entry = scan_apps(&base)
            .into_iter()
            .next()
            .expect("scan should see the dir");
        assert_eq!(entry.parts.len(), 1, "the snapshot is the part here");

        // Nothing recorded yet: every part is read, and the read is evidence.
        let first = verify_app(&entry, &base, &Default::default());
        assert!(first.ok(), "expected healthy, got {:?}", first.corrupt);
        assert_eq!(first.skipped, 0);
        assert_eq!(first.checked, 1, "a part that is never seen proves nothing");
        assert_eq!(first.read_parts, 1, "one part, and it was read");
        assert_eq!(first.read_back.len(), 2, "the snapshot and Meta.txt");
        assert_eq!(first.digest.matched, 1);

        // An index that records exactly these bytes as read back.
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_secs();
        let mut index = manifest::build(&base);
        for mark in &mut index.entries {
            mark.verified = now;
        }
        assert!(manifest::write(&base, &index));
        let known = manifest::ReadBack::from_manifest(&index);

        let second = verify_app(&entry, &base, &known);
        assert_eq!(
            second.skipped, 1,
            "unchanged since it was read back, so it is not read again"
        );
        assert_eq!(
            second.read_parts, 0,
            "and the report says no part was read, not one: Meta.txt is checked \
             but is not a part (the two counters must add up to `checked`)"
        );
        assert_eq!(second.skipped + second.read_parts, second.checked);
        assert!(second.ok(), "skipping is not damage");
        assert!(
            first.bytes_ok > 0 && second.bytes_ok == 0,
            "the first verify read {0} bytes and the second read none: {1}",
            first.bytes_ok,
            second.bytes_ok
        );

        // A modification time is not evidence, so changing one changes nothing:
        // the bytes are what the marker is compared against.
        let snapshot_file = fs::File::options().write(true).open(&snapshot).unwrap();
        let future = std::time::SystemTime::now() + std::time::Duration::from_secs(3600);
        snapshot_file
            .set_times(std::fs::FileTimes::new().set_modified(future))
            .unwrap();
        let still = verify_app(&entry, &base, &known);
        assert_eq!(
            still.skipped, 1,
            "same bytes, later timestamp: still the same file, still skippable"
        );

        // Something that DID change is read again, and is not marked.
        //
        // A one-character edit that keeps the size, with the modification time
        // set back to what it was: exactly the shape that made a timestamp-based
        // skip report a damaged archive as fine from then on, and the reason this
        // test used to be unable to catch it — it only ever changed the bytes.
        let stamp = fs::metadata(&snapshot).unwrap().modified().unwrap();
        fs::write(&snapshot, b"android.permission.CAMERB\n").unwrap();
        fs::File::options()
            .write(true)
            .open(&snapshot)
            .unwrap()
            .set_times(std::fs::FileTimes::new().set_modified(stamp))
            .unwrap();
        assert_eq!(
            fs::metadata(&snapshot).unwrap().modified().unwrap(),
            stamp,
            "the edit is invisible to anything that reads timestamps"
        );
        let third = verify_app(&entry, &base, &known);
        assert_eq!(third.skipped, 0, "a changed part is always read");
        assert_eq!(third.read_parts, 1, "and it is reported as read");
        assert_eq!(
            third.digest.mismatched,
            vec!["Permissions.txt".to_string()],
            "and the change is reported against the recorded digest"
        );
        assert!(
            third.read_back.is_empty()
                || !third
                    .read_back
                    .iter()
                    .any(|(p, _)| p.ends_with("Permissions.txt")),
            "a mismatch must never become evidence for the next run"
        );
        assert_eq!(
            third.refuted,
            vec!["Apps/User/T/Permissions.txt".to_string()],
            "and the index is told which path it must stop vouching for"
        );

        // The index must stop vouching for it. A marker that survived this run
        // would say those bytes had been read back, on the strength of a run
        // that proved they are not the bytes the vault recorded — and because a
        // tampered file keeps its sidecar, nothing else about the entry looks
        // different, so the next rebuild would carry it forward forever.
        assert!(
            manifest::mark_read_back(&base, &third.read_back, &third.refuted),
            "the index should be rebuilt from what this verify found"
        );
        let after = manifest::read(&base).expect("an index");
        let part = after
            .entries
            .iter()
            .find(|entry| entry.path.ends_with("Permissions.txt"))
            .expect("the part is indexed");
        assert_eq!(part.verified, 0, "a refuted part cannot stay verified");
        assert!(
            !manifest::ReadBack::from_manifest(&after)
                .read_back_digest(&base, &snapshot)
                .is_some(),
            "nor can it be skipped on the next run"
        );
        assert_eq!(
            verify_app(&entry, &base, &manifest::ReadBack::from_manifest(&after)).skipped,
            0,
            "the finding stands until a real backup replaces the file"
        );
        let _ = fs::remove_dir_all(&base);
    }

    #[test]
    fn a_real_shift_archive_still_has_to_validate() {
        // The fix must not weaken the archives it was meant to leave alone.
        let base = std::path::PathBuf::from(tempdir("bad-archive"));
        let dir = base.join("Apps/User/T");
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("Meta.txt"), "Name=T\nPackage=com.t\n").unwrap();
        std::fs::write(dir.join("App.shift"), b"not an archive at all").unwrap();
        let entry = scan_apps(&base)
            .into_iter()
            .next()
            .expect("scan should see the dir");
        let v = verify_app(&entry, &base, &Default::default());
        assert!(
            v.corrupt.iter().any(|c| c == "App.shift"),
            "a bogus .shift must still be reported corrupt, got {:?}",
            v.corrupt
        );
    }

    /// A verdict with every field the report line reads, so a test sets only what
    /// it is actually about.
    fn verdict(label: &str, pkg: &str) -> Verdict {
        Verdict {
            label: label.into(),
            pkg: pkg.into(),
            checked: 0,
            corrupt: vec![],
            bytes_ok: 0,
            digest: DigestTally::default(),
            skipped: 0,
            read_parts: 0,
            read_back: vec![],
            refuted: vec![],
            reconciled: 0,
        }
    }

    #[test]
    fn a_hostile_vault_label_cannot_forge_or_split_a_verify_line() {
        // The escaping rule is only as good as the CALL, and a pure test of
        // `protocol::field` cannot see the call — so the line is built by a
        // function this test can call. A vault is reachable from an imported
        // `.dvbackup.tar.gz`, so LABEL and PKG are attacker-controlled.
        let hostile = Verdict {
            checked: 2,
            corrupt: vec!["Data.shift|STATUS:OK".into()],
            ..verdict("Evil\nPKG:com.evil|STATUS:OK", "com.evil|STATUS:OK")
        };
        let line = app_line(0, 1, &hostile);
        assert_eq!(
            line.lines().count(),
            1,
            "a newline in a vault value must not split the line into two rows"
        );
        let keys: Vec<&str> = line
            .split('|')
            .filter_map(|seg| seg.split_once(':').map(|(k, _)| k))
            .collect();
        // Exactly the keys the real line has — no forged STATUS, no second PKG.
        assert_eq!(
            keys,
            vec![
                "INDEX",
                "TOTAL",
                "LABEL",
                "PKG",
                "PARTS",
                "CORRUPT",
                "BYTES",
                "STATUS",
                "HASHED",
                "HASH_CHANGED",
                "HASH_UNRECORDED",
                "HASH_MISMATCH",
                "SKIPPED",
                "READ",
                "RECONCILED"
            ]
        );
        // The label survives as data, just neutralised.
        assert!(line.contains("Evil PKG:com.evil/STATUS:OK"));
    }

    #[test]
    fn an_ordinary_app_still_reports_exactly_what_it_did() {
        // The refactor must not have changed a normal line: same keys, same
        // order, same spellings the app's parser reads.
        let ordinary = Verdict {
            checked: 5,
            corrupt: vec!["Data.shift".into()],
            bytes_ok: 4096,
            skipped: 2,
            read_parts: 3,
            ..verdict("Some App", "com.example.app")
        };
        let line = app_line(0, 3, &ordinary);
        assert!(line.starts_with("VERIFY_APP|INDEX:1|TOTAL:3|LABEL:Some App|PKG:com.example.app|"));
        assert!(line.contains("|PARTS:5|CORRUPT:Data.shift|BYTES:4096|STATUS:CORRUPT|"));
        assert!(line.contains("|SKIPPED:2|READ:3|"));
    }

    #[test]
    fn both_device_item_branches_escape_the_archive_name() {
        // The corrupt branch was the second one to emit `name` raw. Both go
        // through the same builder now, so a third caller cannot reintroduce it.
        for (ok, hash, expected) in [
            (true, "ok", "|STATUS:OK|HASH:ok"),
            (true, "recorded", "|STATUS:OK|HASH:recorded"),
            (true, "changed", "|STATUS:OK|HASH:changed"),
            (true, "unrecorded", "|STATUS:OK|HASH:unrecorded"),
            (false, "unrecorded", "|STATUS:CORRUPT|HASH:unrecorded"),
        ] {
            let line = device_item_line("Messages\n.shift|STATUS:OK", ok, hash);
            assert_eq!(line.lines().count(), 1, "a newline must not split the line");
            assert!(line.contains(expected), "got {line}");
            assert!(!line.contains("\n"));
        }
        // And the app's spelling of an archive with no oddities is untouched.
        assert_eq!(
            device_item_line("Messages.shift", true, "ok"),
            "VERIFY_DEVICE_ITEM|NAME:Messages.shift|STATUS:OK|HASH:ok"
        );
    }
}
