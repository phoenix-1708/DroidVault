//! The vault index: what the vault holds, and a cheap way to ask whether it
//! still holds it.
//!
//! Reading every archive back is the only way to be sure of its bytes, and it
//! costs a full decompression of the vault — measured at 1.15 s/GB warm for a
//! hash alone, several times that for `archive_valid`. That is the right cost
//! before a restore and the wrong cost for "is my vault still here?", which is
//! a question a user asks far more often.
//!
//! So the vault carries an index. It records, for every archive, device data
//! file and mirror under the vault root: a vault-RELATIVE path, the size, and
//! the digest the `.sha256` sidecar recorded when the file was written.
//!
//! ## What a check against it can and cannot say
//!
//! It can say a file is **missing**, that its **size changed**, or that
//! something is **there that the index never saw** — all without reading a byte
//! of archive. It cannot say a file's contents are intact: a same-size
//! corruption passes. That is why the summary line carries
//! `CONTENT:UNCHECKED`, always, and why `--verify-apps` remains the only thing
//! that answers the content question. An index that let a size comparison read
//! as "verified" would be the same class of lie as a run that reports success
//! for work it never did.
//!
//! ## Why it never hashes
//!
//! Building the index reads the sidecars and nothing else. Rehashing the vault
//! to build an index would add a full read of every byte to the end of every
//! backup run — for a number the index does not use to decide anything. Digests
//! enter the vault exactly once, when an archive is written (see `checksum`),
//! and a legacy archive gets one when a deep verify reads it anyway.
//!
//! ## Read-back markers
//!
//! Each entry also carries when — if ever — those exact bytes were last read
//! back successfully by a verification. A marker is only written AFTER a
//! successful read-back of the size and digest it sits next to, and
//! [`merge_verification`] clears it whenever either changes, so "those bytes have
//! been read back" is the only thing a marker can mean. A digest that disagreed
//! is never marked, and — because a tampered file keeps its sidecar and so keeps
//! a key that looks unchanged — the verification that hashed it clears the mark
//! it already had, through [`mark_read_back`]. Otherwise the finding would age
//! back into "known good" instead of standing until a real backup replaces the
//! file.
//!
//! The marker lets a repeat verification skip the *decompression* of a file it
//! has already read back — never the hash. The decision is made from the bytes on
//! disk compared against the marker's digest, and from nothing else. A
//! modification-time check would be cheaper still, and was tried: on the
//! reference device a same-size edit landed in the same second as the marker, and
//! the changed archive was skipped from then on. No timestamp this project can
//! read is worth a verdict that outlives the evidence for it.
//!
//! ## Why the paths are relative
//!
//! A vault moves: Settings → change the folder, a new SD card, a fresh ROM with
//! the same external storage. An index of absolute paths would report every file
//! missing after a move that lost nothing — the same defect as a sidecar naming
//! the old location.

use crate::checksum;
use crate::command;
use crate::protocol;
use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};

pub const FILE_NAME: &str = "vault-manifest.txt";

/// Bumped only when the format changes shape. A reader that does not know a
/// version must refuse the file rather than misread it. Version 2 added the
/// read-back marker to each entry; 1 is refused, not guessed at, and the next
/// run rebuilds it.
const HEADER: &str = "DROIDVAULT-MANIFEST|2";

/// Never indexed: an in-progress temp file is not part of the vault yet, and a
/// sidecar describes another file rather than being one.
///
/// The sidecar's own temp name is listed too. `VaultDigest.record` (the app)
/// writes `<archive>.sha256.new` and renames it over the sidecar, so a kill
/// between the two leaves exactly that file behind; the engine's own writer uses
/// a plain `fs::write` and cannot produce one. Matching only `.sha256` indexed
/// the leftover as a vault file, so it showed up in `ENTRIES` and could be
/// reported `UNLISTED` — a second record of a digest nothing reads.
const SKIP_SUFFIXES: [&str; 3] = [".tmp", checksum::SIDECAR_SUFFIX, ".sha256.new"];

/// The vault-root files that are part of the reinstall kit. Deliberately an
/// allowlist: a user is free to drop a 40 GB file at the vault root, and an
/// index that chased every stray file would walk it.
const MIRRORS: [&str; 4] = [
    "DroidVault.apk",
    "DroidVault.engine",
    "DroidVault.sh",
    "automation-state.json",
];

/// One indexed file.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Entry {
    /// Vault-relative, `/`-separated: `Apps/User/Drive/Data.shift`.
    pub path: String,
    pub size: u64,
    /// The digest the sidecar recorded, when there was one to record. `None` is
    /// "not recorded", never "empty".
    pub digest: Option<String>,
    /// Seconds since the epoch when these exact bytes were last read back
    /// successfully; 0 means never. See [`ReadBack`].
    pub verified: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Manifest {
    /// Seconds since the epoch. Zero when the writer did not know, which the
    /// check reports as an unknown age rather than as 1970.
    pub generated: u64,
    pub entries: Vec<Entry>,
}

/// How an indexed file compares with what is on disk now.
#[derive(Debug, PartialEq, Eq, Clone, Copy)]
pub enum Agreement {
    /// Present, and the same size the index recorded.
    Present,
    /// Present, different size: something rewrote it since the index was built.
    Changed,
    /// The index lists it and it is not on disk.
    Missing,
    /// On disk and not in the index — written after it, so the index cannot
    /// vouch for it either way.
    Unlisted,
}

/// Pure verdict for one path.
///
/// `recorded` is the size the index holds (`None` = not indexed at all),
/// `actual` the size on disk (`None` = not there).
pub fn agreement(recorded: Option<u64>, actual: Option<u64>) -> Agreement {
    match (recorded, actual) {
        (None, _) => Agreement::Unlisted,
        (Some(_), None) => Agreement::Missing,
        (Some(recorded), Some(actual)) if recorded == actual => Agreement::Present,
        (Some(_), Some(_)) => Agreement::Changed,
    }
}

fn now_epoch() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

pub fn path_in(base: &Path) -> PathBuf {
    base.join(FILE_NAME)
}

/// A path is indexable only if it survives the line format intact.
///
/// `|` separates fields and a newline ends the line, so a file whose name
/// contains either cannot be expressed. Names are refused rather than escaped:
/// a manifest that silently renames a file would have the check look for the
/// renamed one. (App labels are already rejected for these characters when a
/// backup starts; this is the second line of defence for a hand-made folder.)
fn indexable(path: &str) -> bool {
    !path.is_empty()
        && !path.contains('|')
        && !path.contains('\n')
        && !path.contains('\r')
        && !path.starts_with('/')
        // Per COMPONENT, and that is the whole traversal defence: a relative path
        // escapes only through a component that is exactly `..`, and every `..`
        // starts with `.`. There used to be a `!path.contains("..")` here as
        // well, which added nothing against traversal and rejected legal labels:
        // §6.9 refuses only dot-ONLY names, so `foo..bar` is a perfectly good
        // label — and that test silently excluded the whole app directory from
        // the index, so `--check-manifest` could never report it missing or
        // changed.
        && !path.split('/').any(|part| part.starts_with('.'))
}

fn skippable(name: &str) -> bool {
    name.starts_with('.') || SKIP_SUFFIXES.iter().any(|suffix| name.ends_with(suffix))
}

/// Index every regular file directly inside `dir`, as `<area>/<name>`.
fn index_flat(out: &mut BTreeMap<String, Entry>, dir: &Path, area: &str) {
    let Ok(entries) = fs::read_dir(dir) else {
        return;
    };
    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().into_owned();
        if skippable(&name) {
            continue;
        }
        let Ok(meta) = entry.metadata() else {
            continue;
        };
        if !meta.is_file() {
            // A subdirectory here is user data — `Device/Folders` holds the
            // folders the user asked to back up — not a DroidVault archive.
            continue;
        }
        let path = format!("{area}/{name}");
        if !indexable(&path) {
            continue;
        }
        out.insert(
            path.clone(),
            Entry {
                path,
                size: meta.len(),
                digest: None,
                verified: 0,
            },
        );
    }
}

/// Every app directory that holds a `Meta.txt`.
///
/// The same rule `verify::scan_apps` uses: Meta.txt is what makes a directory a
/// backup, so a half-written directory from a killed run is not indexed as one.
fn index_apps(out: &mut BTreeMap<String, Entry>, base: &Path) {
    for kind in ["User", "System"] {
        let root = base.join("Apps").join(kind);
        let Ok(dirs) = fs::read_dir(&root) else {
            continue;
        };
        for dir in dirs.flatten() {
            let app = dir.path();
            if !app.join("Meta.txt").is_file() {
                continue;
            }
            let area = format!("Apps/{kind}/{}", dir.file_name().to_string_lossy());
            index_flat(out, &app, &area);
        }
    }
}

/// What is in the vault right now: paths and sizes, nothing read.
///
/// This is the whole of a quick check — no archive is opened, so it costs a
/// `readdir` per directory and a `stat` per file.
pub fn scan(base: &Path) -> Vec<Entry> {
    let mut out: BTreeMap<String, Entry> = BTreeMap::new();
    index_apps(&mut out, base);
    index_flat(&mut out, &base.join("Device"), "Device");
    for name in MIRRORS {
        let file = base.join(name);
        let Ok(meta) = fs::metadata(&file) else {
            continue;
        };
        if !meta.is_file() {
            continue;
        }
        out.insert(
            name.to_string(),
            Entry {
                path: name.to_string(),
                size: meta.len(),
                digest: None,
                verified: 0,
            },
        );
    }
    out.into_values().collect()
}

/// The index: what is in the vault, plus the digest each file's sidecar holds.
///
/// Reads sidecars, never archives. Read-back markers come from
/// [`merge_verification`] and [`mark_read_back`], never from here: a walk
/// cannot know that a file was ever read, and must not pretend one was.
pub fn build(base: &Path) -> Manifest {
    let mut entries = scan(base);
    for entry in &mut entries {
        entry.digest = checksum::read_sidecar(&base.join(&entry.path));
    }
    Manifest {
        generated: now_epoch(),
        entries,
    }
}

/// Serialise. `END` carries the count so a truncated index is detectable: a
/// file cut short mid-list would otherwise read as a slightly smaller vault,
/// and everything missing from it would check out as unlisted rather than gone.
pub fn to_text(manifest: &Manifest) -> String {
    let mut out = String::with_capacity(64 + manifest.entries.len() * 96);
    out.push_str(HEADER);
    out.push('\n');
    out.push_str(&format!("GENERATED|{}\n", manifest.generated));
    for entry in &manifest.entries {
        out.push_str(&format!(
            "ENTRY|{}|{}|{}|{}\n",
            entry.path,
            entry.size,
            entry.digest.as_deref().unwrap_or(""),
            entry.verified
        ));
    }
    out.push_str(&format!("END|{}\n", manifest.entries.len()));
    out
}

/// Parse an index, refusing anything that does not read cleanly.
///
/// Every failure is an error rather than a best-effort reading: a manifest that
/// cannot be trusted says nothing useful, and returning the entries it did
/// understand would make a damaged index look like a smaller vault.
pub fn parse(text: &str) -> Result<Manifest, String> {
    let mut lines = text.lines().filter(|line| !line.trim().is_empty());
    match lines.next() {
        Some(HEADER) => {}
        Some(other) => {
            return Err(format!(
                "Unrecognised vault index header: {}",
                other.chars().take(32).collect::<String>()
            ));
        }
        None => return Err("Vault index is empty".into()),
    }

    let mut manifest = Manifest::default();
    let mut declared: Option<usize> = None;
    for line in lines {
        let mut fields = line.split('|');
        match fields.next().unwrap_or_default() {
            "GENERATED" => {
                manifest.generated = fields.next().unwrap_or("0").parse().unwrap_or(0);
            }
            "ENTRY" => {
                let path = fields.next().unwrap_or_default().to_string();
                let size = fields
                    .next()
                    .unwrap_or("0")
                    .parse::<u64>()
                    .map_err(|_| format!("Vault index has an unreadable size for {path}"))?;
                let digest = fields.next().unwrap_or_default();
                let verified = fields.next().unwrap_or("0").parse::<u64>().unwrap_or(0);
                if fields.next().is_some() {
                    // More fields than the format has: the path itself carried a
                    // separator, so the entry does not describe the file it names.
                    return Err(format!("Vault index entry is malformed: {path}"));
                }
                if !indexable(&path) {
                    return Err(format!("Vault index names an unusable path: {path}"));
                }
                manifest.entries.push(Entry {
                    path,
                    size,
                    digest: checksum::parse_sidecar(digest),
                    verified,
                });
            }
            "END" => {
                declared = Some(fields.next().unwrap_or("0").parse().unwrap_or(0));
            }
            other => return Err(format!("Unrecognised vault index line: {other}")),
        }
    }

    match declared {
        None => Err("Vault index is truncated (no end line)".into()),
        Some(count) if count != manifest.entries.len() => Err(format!(
            "Vault index lists {count} files but carries {}",
            manifest.entries.len()
        )),
        Some(_) => Ok(manifest),
    }
}

pub fn read(base: &Path) -> Option<Manifest> {
    let text = fs::read_to_string(path_in(base)).ok()?;
    match parse(&text) {
        Ok(manifest) => Some(manifest),
        Err(reason) => {
            protocol::info(&format!("Vault index could not be read: {reason}"));
            None
        }
    }
}

/// Write the index, atomically.
///
/// Temp file then rename, like every other writer here: a streaming write into
/// the final name would truncate the previous index before knowing the new one
/// exists, and leave a half-written one behind an interruption.
pub fn write(base: &Path, manifest: &Manifest) -> bool {
    if fs::create_dir_all(base).is_err() {
        return false;
    }
    let final_path = path_in(base);
    let temp = PathBuf::from(format!("{}.new", final_path.display()));
    let text = to_text(manifest);
    let written = fs::File::create(&temp)
        .and_then(|mut file| file.write_all(text.as_bytes()))
        .is_ok();
    if !written {
        let _ = fs::remove_file(&temp);
        return false;
    }
    match fs::rename(&temp, &final_path) {
        Ok(()) => true,
        Err(_) => {
            let _ = fs::remove_file(&temp);
            false
        }
    }
}

/// Rebuild the index for a vault that was just written to.
///
/// Best-effort on purpose: a backup that backed up real data must not be
/// reported as failed because an index file could not be refreshed, and the
/// next run rebuilds it.
pub fn rebuild(base: &Path) {
    if let Err(reason) = rebuild_index(base, &[], &[]) {
        protocol::info(&format!("Vault index not updated: {reason}"));
    }
}

/// The one rebuild path: a run's last step, `--write-manifest`, and every
/// verification go through here, so there is a single place that decides what a
/// read-back marker survives and a single place that refuses to overwrite a good
/// index with an empty one. Two writers with two copies of that rule is how the
/// automation and manual paths drifted apart before.
fn rebuild_index(
    base: &Path,
    read_back: &[(String, u64)],
    refuted: &[String],
) -> Result<Manifest, String> {
    let mut fresh = build(base);
    if fresh.entries.is_empty() {
        // An empty scan means the walk found nothing, which is far more likely
        // to be the wrong folder than an emptied vault.
        return Err("no archives found in this folder".into());
    }
    if let Some(previous) = read(base) {
        merge_verification(&previous, &mut fresh);
    }
    if !refuted.is_empty() {
        // A tampered archive keeps its sidecar, so its `(path, size, digest)` key
        // is exactly the one the marker was written against and
        // `merge_verification` carries the marker forward — it cannot tell that
        // file apart from an unchanged one, because the only thing that can is
        // reading the bytes. The verification that HASHED it can tell, and this
        // is where it says so: a marker meaning "these bytes were read back"
        // must not survive the run that proved they are not the recorded bytes.
        //
        // Found on device: after a same-size edit the index still listed the
        // part as VERIFIED, and `--check-manifest` reported the vault as fully
        // known-good while every `--verify-apps` flagged the same file.
        let refuted: BTreeSet<&str> = refuted.iter().map(String::as_str).collect();
        for entry in &mut fresh.entries {
            if refuted.contains(entry.path.as_str()) {
                entry.verified = 0;
            }
        }
    }
    if !read_back.is_empty() {
        let now = now_epoch();
        // Keyed on the size the VERIFIER saw, so a file that changed between the
        // read and this write cannot inherit a marker describing bytes nobody
        // looked at.
        let marks: BTreeSet<(&str, u64)> = read_back
            .iter()
            .map(|(path, size)| (path.as_str(), *size))
            .collect();
        for entry in &mut fresh.entries {
            if marks.contains(&(entry.path.as_str(), entry.size)) && entry.digest.is_some() {
                entry.verified = now;
            }
        }
    }
    if !write(base, &fresh) {
        return Err("could not be written".into());
    }
    Ok(fresh)
}

/// Carry read-back markers forward onto the entries that still describe the same
/// bytes.
///
/// A marker says "this exact size and digest were read back successfully", so it
/// survives only while both are identical. A rewritten archive, a new size, or a
/// digest that is no longer recorded all start unverified: the evidence that
/// existed was about different bytes.
pub fn merge_verification(previous: &Manifest, next: &mut Manifest) {
    let mut carried: BTreeMap<(&str, u64, Option<&str>), u64> = BTreeMap::new();
    for entry in &previous.entries {
        if entry.verified == 0 || entry.digest.is_none() {
            continue;
        }
        carried.insert(
            (entry.path.as_str(), entry.size, entry.digest.as_deref()),
            entry.verified,
        );
    }
    for entry in &mut next.entries {
        entry.verified = carried
            .get(&(entry.path.as_str(), entry.size, entry.digest.as_deref()))
            .copied()
            .unwrap_or(0);
    }
}

/// Record that these `(vault-relative path, size)` files were read back
/// successfully, just now — and clear the marker on every path in `refuted`,
/// which a verification hashed and found to disagree with the digest the vault
/// recorded.
///
/// Both directions are needed in one call, because they are one decision. Marking
/// only the good news leaves a stale marker on a file whose bytes changed: a
/// tampered archive also keeps its sidecar, so nothing about its index entry
/// looks different, and the next rebuild carries the old marker forward.
///
/// Best-effort: a verification that read every byte and found them good must not
/// be reported as failed because an index could not be refreshed. It never
/// invents a digest — that still comes from the sidecar, as always — and it
/// never marks a file whose digest is unknown, because "we read a file with no
/// recorded digest" is not evidence about the bytes the vault claims to hold.
pub fn mark_read_back(base: &Path, read_back: &[(String, u64)], refuted: &[String]) -> bool {
    rebuild_index(base, read_back, refuted).is_ok()
}

/// CLI entry: `--write-manifest <vault path>`.
pub fn write_manifest(base_path: &str) -> Result<(), String> {
    let base = vault_root(base_path)?;
    let manifest = rebuild_index(&base, &[], &[])?;
    let unhashed = manifest
        .entries
        .iter()
        .filter(|entry| entry.digest.is_none())
        .count();
    protocol::raw(&format!(
        "MANIFEST_BUILT|ENTRIES:{}|UNHASHED:{unhashed}",
        manifest.entries.len()
    ));
    Ok(())
}

/// The read-back markers, as a lookup for a verification deciding what it still
/// has to read.
#[derive(Default)]
pub struct ReadBack<'a> {
    entries: BTreeMap<&'a str, &'a Entry>,
}

impl<'a> ReadBack<'a> {
    pub fn from_manifest(manifest: &'a Manifest) -> Self {
        Self {
            entries: manifest
                .entries
                .iter()
                .map(|entry| (entry.path.as_str(), entry))
                .collect(),
        }
    }

    /// How many entries carry read-back evidence at all.
    pub fn marked(&self) -> usize {
        self.entries.values().filter(|e| e.verified > 0).count()
    }

    /// The digest of the bytes this index recorded as read back for `path`.
    ///
    /// `None` — the default — means "never read back", and every caller must
    /// read the file in full. Only a digest recorded AFTER a successful read-back
    /// is returned, so a caller comparing it with the bytes on disk is comparing
    /// against evidence rather than against a claim. Nothing here consults a
    /// modification time: a timestamp decides nothing about whether bytes were
    /// examined, and the one place a stale timestamp was trusted, a changed
    /// archive was skipped forever.
    pub fn read_back_digest(&self, base: &Path, path: &Path) -> Option<&'a str> {
        let relative = path.strip_prefix(base).ok()?;
        let entry = self.entries.get(relative.to_string_lossy().as_ref())?;
        if entry.verified == 0 {
            return None;
        }
        entry.digest.as_deref()
    }
}

/// CLI entry: `--check-manifest <vault path>` — presence and size, no content.
pub fn check_manifest(base_path: &str) -> Result<(), String> {
    let base = vault_root(base_path)?;
    let Some(manifest) = read(&base) else {
        protocol::info("No vault index in this folder yet");
        protocol::raw(
            "MANIFEST_DONE|STATUS:absent|ENTRIES:0|PRESENT:0|CHANGED:0|MISSING:0|UNLISTED:0|UNHASHED:0|VERIFIED:0|AGE:0|CONTENT:UNCHECKED",
        );
        return Ok(());
    };

    // Looked up by path rather than searched for: a linear scan per entry is
    // quadratic in the number of files, and the entries are already sorted.
    let on_disk = scan(&base);
    let sizes: BTreeMap<&str, u64> = on_disk
        .iter()
        .map(|entry| (entry.path.as_str(), entry.size))
        .collect();
    let indexed: BTreeSet<&str> = manifest
        .entries
        .iter()
        .map(|entry| entry.path.as_str())
        .collect();
    let mut present = 0usize;
    let mut changed = 0usize;
    let mut missing = 0usize;
    let mut cancelled = false;
    for entry in &manifest.entries {
        let actual = sizes.get(entry.path.as_str()).copied();
        let verdict = match agreement(Some(entry.size), actual) {
            Agreement::Present => {
                present += 1;
                "PRESENT"
            }
            Agreement::Changed => {
                changed += 1;
                "CHANGED"
            }
            _ => {
                missing += 1;
                "MISSING"
            }
        };
        protocol::raw(&format!(
            "MANIFEST_ITEM|PATH:{}|SIZE:{}|RECORDED:{}|STATUS:{verdict}",
            entry.path,
            actual.map(|size| size.to_string()).unwrap_or_default(),
            entry.size
        ));
        if command::checkpoint() {
            cancelled = true;
            break;
        }
    }

    // Anything on disk the index does not know about. Not a failure — a run may
    // legitimately have added archives after it — but it is the difference
    // between "everything here is accounted for" and "there is also this".
    let mut unlisted = 0usize;
    if !cancelled {
        for candidate in &on_disk {
            if indexed.contains(candidate.path.as_str()) {
                continue;
            }
            unlisted += 1;
            protocol::raw(&format!(
                "MANIFEST_ITEM|PATH:{}|SIZE:{}|RECORDED:|STATUS:UNLISTED",
                candidate.path, candidate.size
            ));
        }
    } else {
        unlisted = on_disk.len();
    }

    let unhashed = manifest
        .entries
        .iter()
        .filter(|entry| entry.digest.is_none())
        .count();
    // How much of the vault has read-back evidence behind it. Reported because
    // it is the difference between "this file is here" and "these bytes were
    // read back once and have not changed since".
    let verified = ReadBack::from_manifest(&manifest).marked();
    let age = if manifest.generated == 0 {
        0
    } else {
        now_epoch().saturating_sub(manifest.generated)
    };
    protocol::raw(&format!(
        "MANIFEST_DONE|STATUS:{}|ENTRIES:{}|PRESENT:{present}|CHANGED:{changed}|MISSING:{missing}|UNLISTED:{unlisted}|UNHASHED:{unhashed}|VERIFIED:{verified}|AGE:{age}|CONTENT:UNCHECKED{}",
        if cancelled { "cancelled" } else { "checked" },
        manifest.entries.len(),
        if cancelled { "|CANCELLED:1" } else { "" }
    ));
    Ok(())
}

/// A vault path from the caller, checked the way every other verb checks one.
fn vault_root(base_path: &str) -> Result<PathBuf, String> {
    if !base_path.starts_with('/') || base_path.contains('\n') || base_path.contains('\r') {
        return Err("Invalid backup folder".into());
    }
    Ok(PathBuf::from(base_path))
}

#[cfg(test)]
mod tests {
    use super::*;

    const DIGEST: &str = "88bcf1a0503cacce3afe1fc3ba1b2e8710b41a424cf1137de3ccd05906bccfdc";

    fn tmp(tag: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("dv-manifest-{tag}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn touch(path: &Path, contents: &[u8]) {
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        fs::write(path, contents).unwrap();
    }

    #[test]
    fn the_walk_indexes_archives_device_data_and_mirrors() {
        let base = tmp("walk");
        touch(&base.join("Apps/User/Drive/Meta.txt"), b"Name=Drive\n");
        touch(&base.join("Apps/User/Drive/App.shift"), b"archive");
        touch(&base.join("Apps/System/Gms/Meta.txt"), b"Name=Gms\n");
        touch(&base.join("Apps/System/Gms/Data.shift"), b"archive");
        // A System directory with no Meta.txt is not a backup (see verify), so it
        // must not be indexed as one either.
        touch(&base.join("Apps/System/Orphan/Data.shift"), b"archive");
        touch(&base.join("Device/Messages.shift"), b"archive");
        touch(&base.join("DroidVault.apk"), b"apk");
        touch(&base.join("automation-state.json"), b"{}");

        let paths: Vec<String> = scan(&base).into_iter().map(|entry| entry.path).collect();
        assert_eq!(
            paths,
            vec![
                "Apps/System/Gms/Data.shift".to_string(),
                "Apps/System/Gms/Meta.txt".to_string(),
                "Apps/User/Drive/App.shift".to_string(),
                "Apps/User/Drive/Meta.txt".to_string(),
                "Device/Messages.shift".to_string(),
                "DroidVault.apk".to_string(),
                "automation-state.json".to_string(),
            ]
        );
        let _ = fs::remove_dir_all(&base);
    }

    #[test]
    fn a_sidecar_temp_left_by_a_kill_is_not_a_vault_file() {
        // `VaultDigest.record` writes `<archive>.sha256.new` and renames it over
        // the sidecar. A kill between the two leaves exactly that file, and it
        // matched neither skip suffix — so it was indexed, counted in `ENTRIES`,
        // and could be reported UNLISTED: a second record of a digest that
        // nothing reads, standing in for a file the user never lost.
        assert!(skippable("App.shift.sha256.new"));
        assert!(skippable("Messages.shift.sha256"));
        assert!(skippable("App.shift.tmp"));
        // The archive itself is still indexed — only its records are skipped.
        assert!(!skippable("App.shift"));
    }

    #[test]
    fn a_legal_label_containing_two_dots_is_still_indexed() {
        // §6.9 refuses only dot-ONLY names, so `foo..bar` is a valid app label.
        // `indexable` also rejected any path CONTAINING `..`, which silently
        // excluded the whole app directory from the index — so `--check-manifest`
        // could never report one of its archives missing or changed.
        assert!(indexable("Apps/User/foo..bar/Data.shift"));
        assert!(indexable("Apps/User/Node.js/Meta.txt"));
        // Traversal is still refused, by the per-component rule: `..` is a
        // component that starts with a dot.
        assert!(!indexable("Apps/User/../evil/Data.shift"));
        assert!(!indexable("../etc/passwd"));
        assert!(!indexable("Apps/User/.."));
        assert!(!indexable("Apps/User/.hidden/Data.shift"));
    }

    #[test]
    fn the_walk_ignores_what_is_not_the_vault() {
        let base = tmp("skip");
        touch(&base.join("Apps/User/Drive/Meta.txt"), b"Name=Drive\n");
        touch(&base.join("Apps/User/Drive/App.shift"), b"archive");
        // In-flight temp: not part of the vault yet.
        touch(&base.join("Apps/User/Drive/Data.shift.tmp"), b"half");
        // A sidecar describes another file; it is not one.
        touch(&base.join("Apps/User/Drive/App.shift.sha256"), b"deadbeef");
        // Half-written directory from a killed run: not a backup (see verify).
        touch(&base.join("Apps/User/Partial/App.shift"), b"archive");
        // User data (folders to back up), not an archive of ours.
        touch(&base.join("Device/Folders/DCIM/photo.jpg"), b"jpeg");
        // A quarantine batch is not the good copy.
        touch(&base.join(".trash/b1/Device/Messages.shift"), b"archive");
        // A user's stray file at the vault root.
        touch(&base.join("notes.txt"), b"hello");
        touch(&base.join(".nomedia"), b"");
        touch(&base.join("vault-manifest.txt"), b"old index");

        let paths: Vec<String> = scan(&base).into_iter().map(|entry| entry.path).collect();
        assert_eq!(
            paths,
            vec![
                "Apps/User/Drive/App.shift".to_string(),
                "Apps/User/Drive/Meta.txt".to_string(),
            ]
        );
        let _ = fs::remove_dir_all(&base);
    }

    #[test]
    fn the_digest_comes_from_the_sidecar_and_only_when_one_exists() {
        let base = tmp("sidecar");
        touch(&base.join("Apps/User/Drive/Meta.txt"), b"Name=Drive\n");
        touch(&base.join("Apps/User/Drive/App.shift"), b"archive");
        touch(
            &base.join("Apps/User/Drive/App.shift.sha256"),
            format!("{DIGEST}  App.shift\n").as_bytes(),
        );
        touch(&base.join("Apps/User/Drive/Data.shift"), b"archive");
        touch(
            &base.join("Apps/User/Drive/Data.shift.sha256"),
            b"not a digest at all\n",
        );

        let built = build(&base);
        let digest = |path: &str| {
            built
                .entries
                .iter()
                .find(|entry| entry.path == path)
                .unwrap()
                .digest
                .clone()
        };
        assert_eq!(
            digest("Apps/User/Drive/App.shift"),
            Some(DIGEST.to_string())
        );
        assert_eq!(
            digest("Apps/User/Drive/Data.shift"),
            None,
            "a garbled sidecar records nothing rather than a wrong digest"
        );
        assert_eq!(digest("Apps/User/Drive/Meta.txt"), None);
        let _ = fs::remove_dir_all(&base);
    }

    #[test]
    fn a_written_index_reads_back_identically() {
        let manifest = Manifest {
            generated: 1_700_000_000,
            entries: vec![
                Entry {
                    path: "Apps/User/Drive/App.shift".into(),
                    size: 4096,
                    digest: Some(DIGEST.into()),
                    verified: 1_700_000_100,
                },
                Entry {
                    path: "Device/Messages.shift".into(),
                    size: 12,
                    digest: None,
                    verified: 0,
                },
            ],
        };
        let parsed = parse(&to_text(&manifest)).unwrap();
        assert_eq!(parsed, manifest);
        // An index written before markers existed is refused rather than read
        // as if every entry had been read back.
        let v1 = to_text(&manifest).replace(HEADER, "DROIDVAULT-MANIFEST|1");
        assert!(parse(&v1).is_err());
    }

    #[test]
    fn a_marker_survives_only_while_it_describes_the_same_bytes() {
        let base = tmp("merge");
        touch(&base.join("Apps/User/Drive/Meta.txt"), b"Name=Drive\n");
        touch(&base.join("Apps/User/Drive/App.shift"), b"archive");
        touch(
            &base.join("Apps/User/Drive/App.shift.sha256"),
            format!("{DIGEST}  App.shift\n").as_bytes(),
        );
        let mut first = build(&base);
        first.entries[0].verified = 1_700_000_000;
        assert!(write(&base, &first));

        // Unchanged: the marker is still about these bytes.
        let mut same = build(&base);
        merge_verification(&first, &mut same);
        assert_eq!(same.entries[0].verified, 1_700_000_000);

        // A rewrite replaces the sidecar too: same size, different digest, so
        // the marker describes nothing that is still there.
        touch(
            &base.join("Apps/User/Drive/App.shift.sha256"),
            format!("{}  App.shift\n", DIGEST.replace('a', "b")).as_bytes(),
        );
        let mut rewritten = build(&base);
        merge_verification(&first, &mut rewritten);
        assert_eq!(rewritten.entries[0].verified, 0);

        // Sidecar deleted: nothing records those bytes any more.
        let _ = fs::remove_file(base.join("Apps/User/Drive/App.shift.sha256"));
        let mut unrecorded = build(&base);
        merge_verification(&first, &mut unrecorded);
        assert_eq!(unrecorded.entries[0].verified, 0);

        // A file with no recorded digest is never marked, whoever asks: "we
        // read a file whose bytes nothing recorded" is not evidence about the
        // bytes the vault claims to hold.
        let name = build(&base).entries[0].path.clone();
        let size = build(&base).entries[0].size;
        mark_read_back(&base, &[(name.clone(), size)], &[]);
        let reread = read(&base).unwrap();
        assert_eq!(
            reread
                .entries
                .iter()
                .find(|entry| entry.path == name)
                .unwrap()
                .verified,
            0,
            "an unrecorded digest cannot be marked as read back"
        );
        let _ = fs::remove_dir_all(&base);
    }

    #[test]
    fn a_read_back_digest_is_evidence_and_anything_else_is_not() {
        let base = tmp("skipevidence");
        let file = base.join("Apps/User/Drive/App.shift");
        touch(&base.join("Apps/User/Drive/Meta.txt"), b"Name=Drive\n");
        touch(&file, b"archive");
        touch(
            &base.join("Apps/User/Drive/App.shift.sha256"),
            format!("{DIGEST}  App.shift\n").as_bytes(),
        );

        // No index at all: nothing has been read back, so nothing is known.
        assert_eq!(
            ReadBack::default().read_back_digest(&base, &file),
            None,
            "no index can never vouch for a file"
        );

        // An entry with no marker carries no evidence, whatever digest it holds.
        let unmarked = build(&base);
        assert_eq!(ReadBack::from_manifest(&unmarked).marked(), 0);
        assert_eq!(
            ReadBack::from_manifest(&unmarked).read_back_digest(&base, &file),
            None
        );

        // Marked: the digest is the one a successful read-back was recorded
        // against, so a caller can compare the bytes it just hashed with it.
        let mut marked = build(&base);
        marked.entries[0].verified = now_epoch();
        let marked = ReadBack::from_manifest(&marked);
        assert_eq!(marked.marked(), 1);
        assert_eq!(marked.read_back_digest(&base, &file), Some(DIGEST));

        // A path outside the vault, or one the index never saw, has no evidence.
        assert_eq!(
            marked.read_back_digest(&base, Path::new("/etc/hosts")),
            None
        );
        assert_eq!(
            marked.read_back_digest(&base, &base.join("Apps/User/Drive/Meta.txt")),
            None,
            "an unmarked file in the same directory is still unproven"
        );
        let _ = fs::remove_dir_all(&base);
    }

    #[test]
    fn a_read_back_records_a_marker_and_never_invents_a_digest() {
        let base = tmp("markreadback");
        touch(&base.join("Apps/User/Drive/Meta.txt"), b"Name=Drive\n");
        touch(&base.join("Apps/User/Drive/App.shift"), b"archive");
        touch(
            &base.join("Apps/User/Drive/App.shift.sha256"),
            format!("{DIGEST}  App.shift\n").as_bytes(),
        );
        let path = "Apps/User/Drive/App.shift".to_string();
        let size = fs::metadata(base.join(&path)).unwrap().len();
        assert!(mark_read_back(&base, &[(path.clone(), size)], &[]));
        let after = read(&base).unwrap();
        let entry = after
            .entries
            .iter()
            .find(|entry| entry.path == path)
            .unwrap();
        assert!(entry.verified > 0, "a read-back must leave a marker");
        assert_eq!(entry.digest.as_deref(), Some(DIGEST));

        // A size the verifier did not see is not marked.
        assert!(mark_read_back(&base, &[(path.clone(), size + 1)], &[]));
        assert_eq!(read(&base).unwrap().entries[0].verified, entry.verified);

        // And a marker on a verification with nothing to mark is not lost.
        assert!(mark_read_back(&base, &[], &[]));
        assert_eq!(read(&base).unwrap().entries[0].verified, entry.verified);

        // A refuted path loses its marker even though nothing about the entry
        // changed: a tampered archive keeps its sidecar, so `merge_verification`
        // keys it as unchanged bytes and would carry the marker forward.
        assert!(mark_read_back(&base, &[], &[path.clone()]));
        let cleared = read(&base).unwrap();
        let cleared = cleared
            .entries
            .iter()
            .find(|entry| entry.path == path)
            .unwrap();
        assert_eq!(cleared.verified, 0, "a refuted file cannot stay verified");
        // The digest is not touched: it is the record the mismatch was measured
        // against, and deleting it would turn a known-bad archive into an
        // unknown one, which is the report the user cannot act on.
        assert_eq!(cleared.digest.as_deref(), Some(DIGEST));
        let _ = fs::remove_dir_all(&base);
    }

    #[test]
    fn a_truncated_index_is_refused_rather_than_read_as_a_smaller_vault() {
        let text = to_text(&Manifest {
            generated: 1,
            entries: vec![
                Entry {
                    path: "Device/Messages.shift".into(),
                    size: 12,
                    digest: None,
                    verified: 0,
                },
                Entry {
                    path: "Device/CallLogs.shift".into(),
                    size: 12,
                    digest: None,
                    verified: 0,
                },
            ],
        });
        let cut = text.rsplit_once("ENTRY").unwrap().0;
        let error = parse(cut).unwrap_err();
        assert!(
            error.contains("truncated"),
            "expected a truncation error, got {error}"
        );
        // Even with the end line present, a count that disagrees is a refusal.
        let lying = text.replace("END|2", "END|3");
        assert!(parse(&lying).is_err());
    }

    #[test]
    fn an_index_that_names_what_cannot_be_named_is_refused() {
        // A path carrying the field separator would describe a different file.
        let text = format!("{HEADER}\nGENERATED|1\nENTRY|Device/a|b.shift|12|\nEND|1\n");
        assert!(parse(&text).is_err());
        // A relative path is required: the vault moves.
        let text =
            format!("{HEADER}\nGENERATED|1\nENTRY|/storage/emulated/0/DroidVault/x|12|\nEND|1\n");
        assert!(parse(&text).is_err());
        let text = format!("{HEADER}\nGENERATED|1\nENTRY|Apps/../secrets|12|\nEND|1\n");
        assert!(parse(&text).is_err());
        let text = format!("{HEADER}\nGENERATED|1\nENTRY|Device/Messages.shift|notasize|\nEND|1\n");
        assert!(parse(&text).is_err());
        assert!(parse("").is_err());
        assert!(parse("SOMETHING ELSE|1\nEND|0\n").is_err());
    }

    #[test]
    fn the_agreement_verdicts_are_pinned() {
        assert_eq!(agreement(Some(10), Some(10)), Agreement::Present);
        assert_eq!(agreement(Some(10), Some(11)), Agreement::Changed);
        assert_eq!(agreement(Some(10), None), Agreement::Missing);
        assert_eq!(agreement(None, Some(10)), Agreement::Unlisted);
        assert_eq!(
            agreement(None, None),
            Agreement::Unlisted,
            "a file the index never saw is not a missing file"
        );
    }

    #[test]
    fn the_check_reports_presence_not_content() {
        let base = tmp("check");
        touch(&base.join("Apps/User/Drive/Meta.txt"), b"Name=Drive\n");
        touch(&base.join("Apps/User/Drive/App.shift"), b"archive");
        touch(&base.join("Device/Messages.shift"), b"messages");
        let manifest = build(&base);
        // Same sizes, rewritten bytes: a size check cannot see it, and must not
        // claim to have.
        let rewritten = build(&base);
        assert_eq!(manifest.entries, rewritten.entries);
        assert!(write(&base, &manifest));
        let read_back = read(&base).unwrap();
        assert_eq!(read_back.entries, manifest.entries);

        for entry in &read_back.entries {
            let now = fs::metadata(base.join(&entry.path)).unwrap().len();
            assert_eq!(agreement(Some(entry.size), Some(now)), Agreement::Present);
        }
        // A grown archive is caught.
        fs::write(base.join("Device/Messages.shift"), b"messages and more").unwrap();
        let now = fs::metadata(base.join("Device/Messages.shift"))
            .unwrap()
            .len();
        assert_eq!(agreement(Some(8), Some(now)), Agreement::Changed);
        // A deleted one is caught.
        fs::remove_file(base.join("Apps/User/Drive/App.shift")).unwrap();
        assert_eq!(agreement(Some(7), None), Agreement::Missing);
        let _ = fs::remove_dir_all(&base);
    }

    #[test]
    fn writing_an_index_never_truncates_the_previous_one() {
        let base = tmp("atomic");
        touch(&base.join("Device/Messages.shift"), b"messages");
        let good = build(&base);
        assert!(write(&base, &good));

        // A rebuild that finds nothing must leave the good index alone.
        let empty = tmp("atomic-empty");
        assert_eq!(scan(&empty).len(), 0);
        rebuild(&empty);
        assert!(
            !path_in(&empty).exists(),
            "an empty scan must not write an index over nothing"
        );
        rebuild(&base);
        let after = read(&base).unwrap();
        assert_eq!(after.entries, good.entries);
        assert!(after.generated >= good.generated);
        let _ = fs::remove_dir_all(&base);
        let _ = fs::remove_dir_all(&empty);
    }
}
