//! Archive verification.
//!
//! A backup that cannot be read back is worse than no backup, because the user
//! only finds out at restore time — after they have already wiped the device or
//! flashed a ROM. `archive_valid` existed but nothing exposed it, so nothing
//! checked. This walks the vault and reports each app's archives, which lets the
//! UI refuse a corrupt archive BEFORE a restore starts rather than half-restoring
//! an app and leaving it in a state neither the app nor the user expects.

use crate::archive;
use crate::command;
use crate::protocol;
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

/// Result of checking one app.
pub struct Verdict {
    pub label: String,
    pub pkg: String,
    pub checked: usize,
    pub corrupt: Vec<String>,
    pub bytes_ok: u64,
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
pub fn verify_app(entry: &ArchiveEntry) -> Verdict {
    let mut corrupt = Vec::new();
    let mut bytes_ok = 0u64;
    for (id, name) in &entry.parts {
        let path = entry.dir.join(name);
        let readable = if *id == PART_PERMISSIONS {
            // A permission snapshot is valid when it can be opened. Its content
            // may legitimately be short, so emptiness is not damage.
            std::fs::File::open(&path).is_ok()
        } else {
            archive::archive_valid(&path)
        };
        if readable {
            if let Ok(meta) = std::fs::metadata(&path) {
                bytes_ok += meta.len();
            }
        } else {
            corrupt.push(name.clone());
        }
    }
    Verdict {
        label: entry.label.clone(),
        pkg: entry.pkg.clone(),
        checked: entry.parts.len(),
        corrupt,
        bytes_ok,
    }
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
    for (index, entry) in apps.iter().enumerate() {
        let verdict = verify_app(entry);
        protocol::raw(&format!(
            "VERIFY_APP|INDEX:{}|TOTAL:{}|LABEL:{}|PKG:{}|PARTS:{}|CORRUPT:{}|BYTES:{}|STATUS:{}",
            index + 1,
            total,
            verdict.label,
            verdict.pkg,
            verdict.checked,
            verdict.corrupt.join(","),
            verdict.bytes_ok,
            if verdict.ok() { "OK" } else { "CORRUPT" }
        ));
        // Cancelling a long verify is pointless work, so stop rather than keep
        // streaming results the caller has abandoned.
        if command::cancelled() {
            protocol::raw("VERIFY_DONE|TOTAL:0|OK:0|CORRUPT:0|BYTES:0|CANCELLED:1");
            return Ok(());
        }
    }

    let ok = apps
        .iter()
        .map(|e| verify_app(e).corrupt.is_empty())
        .filter(|v| *v)
        .count();
    protocol::raw(&format!(
        "VERIFY_DONE|TOTAL:{total}|OK:{}|CORRUPT:{}|BYTES:0",
        ok,
        total - ok
    ));
    Ok(())
}

/// CLI entry: `--verify-device <vault path>` — the device-data archives.
///
/// These are plain `.shift` files at the folder root, not per-app directories.
pub fn verify_device(base_path: &str) -> Result<(), String> {
    if !base_path.starts_with('/') || base_path.contains('\n') || base_path.contains('\r') {
        return Err("Invalid backup folder".into());
    }
    let dir = PathBuf::from(base_path).join("Device");
    let mut total = 0usize;
    let mut ok = 0usize;
    let mut bytes = 0u64;
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
    for name in names {
        total += 1;
        let path = dir.join(&name);
        if archive::archive_valid(&path) {
            ok += 1;
            if let Ok(meta) = std::fs::metadata(&path) {
                bytes += meta.len();
            }
            protocol::raw(&format!("VERIFY_DEVICE_ITEM|NAME:{name}|STATUS:OK"));
        } else {
            protocol::raw(&format!("VERIFY_DEVICE_ITEM|NAME:{name}|STATUS:CORRUPT"));
        }
        if command::cancelled() {
            protocol::raw("VERIFY_DEVICE_DONE|TOTAL:0|OK:0|CORRUPT:0|BYTES:0|CANCELLED:1");
            return Ok(());
        }
    }
    protocol::raw(&format!(
        "VERIFY_DEVICE_DONE|TOTAL:{total}|OK:{ok}|CORRUPT:{}|BYTES:{bytes}",
        total - ok
    ));
    Ok(())
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
        let v = verify_app(&entry);
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
        let v = verify_app(&entry);
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
        let v = verify_app(&entry);
        assert!(v.ok(), "expected healthy, got {:?}", v.corrupt);
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
        let v = verify_app(&entry);
        assert!(
            v.corrupt.iter().any(|c| c == "App.shift"),
            "a bogus .shift must still be reported corrupt, got {:?}",
            v.corrupt
        );
    }
}
