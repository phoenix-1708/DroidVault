//! Content hashes for the vault, and the sidecar that records them.
//!
//! `partition` images have carried `<image>.sha256` since the imaging work, and
//! that pattern is what the rest of the vault now follows: hash the file with the
//! platform's `sha256sum`, write the digest next to it, and check it before the
//! data is used.
//!
//! ## Why the sidecar is coreutils' own format
//!
//! The file is written as `<digest>  <file name>`, which is exactly what
//! `sha256sum` emits and what `sha256sum -c` parses. The point is that verifying
//! a vault then needs nothing from this project:
//!
//! ```sh
//! cd /storage/emulated/0/DroidVault/Apps/User/SomeApp
//! sha256sum -c App.shift.sha256      # as root, or over MTP on a copy
//! ```
//!
//! The recorded name is the archive's BASE NAME, never an absolute path: a vault
//! is movable (Settings → move vault, a new SD card, a fresh ROM), and a
//! recorded absolute path would verify the old location and call a perfectly
//! good archive corrupt.
//!
//! ## Why a fork of `sha256sum` rather than a Rust implementation
//!
//! Measured on the reference device (UFS, 12 GB RAM): 1.15 s per GB warm and
//! 1.50 s per GB cold. A scalar SHA-256 in-process would run at roughly a
//! quarter of that and would become the slowest stage of the pack pipeline,
//! since `zapdos -1` already saturates well below it. The digest is computed
//! once, immediately after writing — while the bytes are still in page cache —
//! and a backup that skips an archive (the incremental case) never rehashes it.

use crate::command;
use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};

/// The suffix a sidecar carries. Deliberately the same `.<algo>sum`-style name
/// the partition images already use, so one rule covers the whole vault.
pub const SIDECAR_SUFFIX: &str = ".sha256";

/// Digest of a file, lowercase hex, via the platform's `sha256sum`.
///
/// `None` when the file cannot be read or the tool is missing — callers must
/// treat that as "not checked", never as "unchanged".
pub fn hash_file(path: &Path) -> Option<String> {
    let out = command::text("sha256sum", [path.to_string_lossy().into_owned()]);
    parse_sha256sum(&out)
}

/// Pure half of [`hash_file`]: the first field of `sha256sum` output.
pub fn parse_sha256sum(output: &str) -> Option<String> {
    let digest = output.split_whitespace().next()?;
    if digest.len() != 64 || !digest.bytes().all(|b| b.is_ascii_hexdigit()) {
        return None;
    }
    Some(digest.to_ascii_lowercase())
}

/// Digests for many files from ONE `sha256sum` invocation, keyed by the path as
/// passed.
///
/// Deep verify hashes every part of every app. Process start is a few
/// milliseconds on the reference device, so one spawn per file would spend
/// seconds in `fork` and `exec` alone on a vault with a couple of hundred
/// apps — measurable as nothing but overhead, since each spawn reads a few KB.
/// A file that cannot be read simply has no line, and is therefore "no digest"
/// rather than a mismatch.
pub fn hash_files(paths: &[PathBuf]) -> HashMap<String, String> {
    if paths.is_empty() {
        return HashMap::new();
    }
    let args: Vec<String> = paths
        .iter()
        .map(|path| path.to_string_lossy().into_owned())
        .collect();
    parse_sha256sum_lines(&command::text("sha256sum", args))
        .into_iter()
        .collect()
}

/// Pure half of [`hash_files`]: the `(path, digest)` pairs in `sha256sum`
/// output.
///
/// The separator is not assumed to be a single space: coreutils writes two
/// spaces for text mode and ` *` for binary mode, and a file name may itself
/// contain spaces. So the digest is taken positionally and whatever separator
/// follows it is skipped, leaving the rest of the line as the path.
pub fn parse_sha256sum_lines(output: &str) -> Vec<(String, String)> {
    let mut out = Vec::new();
    for line in output.lines() {
        // A leading backslash marks a name coreutils had to escape (a newline or
        // a backslash in it). Those cannot be mapped back to a path reliably, so
        // the file reads as unhashed instead of as some other file.
        if line.starts_with('\\') || line.len() < 66 {
            continue;
        }
        let (digest, rest) = line.split_at(64);
        if !digest.bytes().all(|b| b.is_ascii_hexdigit()) {
            continue;
        }
        let path = rest.trim_start_matches([' ', '*']);
        if path.is_empty() {
            continue;
        }
        out.push((path.to_string(), digest.to_ascii_lowercase()));
    }
    out
}

/// Where a file's sidecar lives: `<file>.sha256`.
pub fn sidecar_for(path: &Path) -> PathBuf {
    PathBuf::from(format!("{}{}", path.display(), SIDECAR_SUFFIX))
}

/// The line written into a sidecar, in `sha256sum`'s own format.
///
/// A file name containing a newline would truncate the line and produce a
/// sidecar that verifies a different file; the engine's own labels are already
/// checked for that, and a name that cannot be expressed is refused here.
pub fn sidecar_line(path: &Path, digest: &str) -> Option<String> {
    let name = path.file_name()?.to_string_lossy();
    if name.contains('\n') || name.contains('\r') {
        return None;
    }
    Some(format!("{digest}  {name}\n"))
}

/// Record a digest next to the file it describes.
///
/// Written only for a file that was just produced or just verified, so a
/// sidecar never claims a digest for something that was not read.
pub fn write_sidecar(path: &Path, digest: &str) -> bool {
    let Some(line) = sidecar_line(path, digest) else {
        return false;
    };
    fs::write(sidecar_for(path), line).is_ok()
}

/// Hash a file this process just wrote, record the digest beside it, and report
/// what landed.
///
/// ## The stale-claim rule
///
/// When no digest can be recorded — no usable `sha256sum`, or the sidecar write
/// failed — any sidecar left over from a previous version of the file is
/// REMOVED. An absent record reads as "unknown"; a record describing the
/// *previous* bytes reads as "changed", which is a report that a perfectly good
/// archive was altered. Unknown is honest, wrong is not, and the two must not be
/// confused (`Check::Unrecorded` vs `Check::Mismatch`).
///
/// This is also why a digest that already exists is never silently rewritten: a
/// mismatch is the only evidence that bytes changed under us, and overwriting it
/// would destroy the evidence rather than act on it.
pub fn record(path: &Path) -> Option<String> {
    let digest = hash_file(path);
    match digest.as_deref() {
        Some(digest) if write_sidecar(path, digest) => Some(digest.to_string()),
        _ => {
            let _ = fs::remove_file(sidecar_for(path));
            None
        }
    }
}

/// The digest a sidecar records, or `None` when absent/unreadable.
pub fn read_sidecar(path: &Path) -> Option<String> {
    let text = fs::read_to_string(sidecar_for(path)).ok()?;
    parse_sidecar(&text)
}

/// Pure half of [`read_sidecar`].
///
/// Accepts `sha256sum`'s two-field form and a bare digest, because a sidecar
/// hand-written by a user (or by a recovery script) is still evidence — but it
/// rejects anything that is not 64 hex characters, so a truncated or garbled
/// line reads as "no digest recorded" rather than as a mismatch against a
/// file that is actually fine.
pub fn parse_sidecar(text: &str) -> Option<String> {
    let line = text.lines().find(|line| !line.trim().is_empty())?;
    let digest = line.split_whitespace().next()?;
    if digest.len() != 64 || !digest.bytes().all(|b| b.is_ascii_hexdigit()) {
        return None;
    }
    Some(digest.to_ascii_lowercase())
}

/// How a file's recorded digest compares with what is on disk now.
#[derive(Debug, PartialEq, Eq, Clone, Copy)]
pub enum Check {
    /// Recorded and equal.
    Match,
    /// Recorded and different: the bytes changed since they were recorded.
    Mismatch,
    /// No digest recorded for this file (an archive from before sidecars, or a
    /// file nothing has hashed yet).
    Unrecorded,
}

/// What happened when a sidecar was compared with a digest that was already
/// established as the digest of the bytes in front of us.
#[derive(Debug, PartialEq, Eq, Clone, Copy)]
pub enum Reconcile {
    /// The record already describes these bytes.
    Agreed,
    /// Nothing usable was recorded (no sidecar, or a line that is not a digest),
    /// so one was written from these bytes.
    Backfilled,
    /// A record existed and named different bytes, so it was rewritten from the
    /// digest of the bytes in front of us.
    Rewritten,
    /// Nothing could be written. The record is left exactly as it was.
    Failed,
}

/// Bring the record beside a file back in line with bytes whose digest is ALREADY
/// known good.
///
/// ## Only for a caller that has proven these bytes
///
/// The digest passed in must come from a caller that has established these are
/// the bytes the vault recorded — a read-back marker that matches them, or a hash
/// just taken while the file was read back. That is what makes a rewrite safe:
/// **a disagreeing record cannot be evidence of a change here**, because the
/// evidence of a change is the marker disagreeing with the bytes, and it did not.
///
/// ## Why the vault needs it
///
/// One file has two records of one digest: the `.sha256` sidecar every writer
/// produces, and the index's read-back marker. The engine's skip test reads the
/// MARKER (bytes decide, never a timestamp), while `sha256sum -c` — which this
/// module documents as verifying a vault without this project — reads the
/// SIDECAR. Both are written together, so a disagreement means one was edited
/// outside the app; left alone, the vault tells two different stories about the
/// same file, and the user is told to trust whichever one they happened to ask.
/// Verified on the reference device: a sidecar set to all zeros was skipped as
/// unchanged while `sha256sum -c` failed on the archive.
///
/// Never called on a mismatch finding: a file whose bytes changed keeps the
/// record that proves it (see [`record`]).
pub fn reconcile(path: &Path, actual: Option<&str>) -> Reconcile {
    let Some(actual) = actual else {
        return Reconcile::Failed;
    };
    match compare(read_sidecar(path).as_deref(), Some(actual)) {
        Check::Match => Reconcile::Agreed,
        Check::Mismatch => {
            if write_sidecar(path, actual) {
                Reconcile::Rewritten
            } else {
                Reconcile::Failed
            }
        }
        Check::Unrecorded => {
            if write_sidecar(path, actual) {
                Reconcile::Backfilled
            } else {
                Reconcile::Failed
            }
        }
    }
}

/// Compare a digest read off disk with the one the vault recorded.
///
/// Pure, so the verdict rule is pinned: an absent record is `Unrecorded` and
/// never `Match`. Reporting "verified" for a file whose digest nobody ever
/// wrote down is the same class of lie as reporting a backup that never ran.
pub fn compare(recorded: Option<&str>, actual: Option<&str>) -> Check {
    match (recorded, actual) {
        (None, _) => Check::Unrecorded,
        (Some(_), None) => Check::Unrecorded,
        (Some(recorded), Some(actual)) => {
            if recorded.eq_ignore_ascii_case(actual) {
                Check::Match
            } else {
                Check::Mismatch
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const DIGEST: &str = "88bcf1a0503cacce3afe1fc3ba1b2e8710b41a424cf1137de3ccd05906bccfdc";

    #[test]
    fn sha256sum_output_parsing() {
        assert_eq!(
            parse_sha256sum(&format!("{DIGEST}  /vault/Apps/User/App/Data.shift\n")),
            Some(DIGEST.to_string())
        );
        assert_eq!(parse_sha256sum(""), None);
        assert_eq!(parse_sha256sum("sha256sum: no such file\n"), None);
        // A short or non-hex token is not a digest, whatever it is.
        assert_eq!(parse_sha256sum(&format!("{}  short", &DIGEST[..63])), None);
        assert_eq!(
            parse_sha256sum(&format!("{}  upper", DIGEST.replacen('a', "z", 1))),
            None
        );
    }

    #[test]
    fn sidecar_line_is_the_format_sha256sum_consumes() {
        let line = sidecar_line(Path::new("/vault/Apps/User/Some App/Data.shift"), DIGEST).unwrap();
        assert_eq!(line, format!("{DIGEST}  Data.shift\n"));
        // Two spaces: `sha256sum -c` splits on the first field and treats the
        // rest as the file name, so a single space would still work, but the
        // canonical form is what every other tool emits.
        assert!(line.contains("  "));
    }

    #[test]
    fn a_name_that_cannot_be_expressed_is_refused() {
        assert_eq!(
            sidecar_line(Path::new("/vault/odd\nname.shift"), DIGEST),
            None
        );
    }

    #[test]
    fn sidecar_parsing_accepts_both_spellings_and_rejects_junk() {
        assert_eq!(
            parse_sidecar(&format!("{DIGEST}  Data.shift\n")),
            Some(DIGEST.to_string())
        );
        assert_eq!(parse_sidecar(DIGEST), Some(DIGEST.to_string()));
        assert_eq!(
            parse_sidecar("\n\n"),
            None,
            "an empty sidecar records nothing"
        );
        assert_eq!(parse_sidecar("not-a-digest  Data.shift\n"), None);
        // Uppercase hex is the same digest.
        assert_eq!(
            parse_sidecar(&DIGEST.to_uppercase()),
            Some(DIGEST.to_string())
        );
    }

    #[test]
    fn a_stale_record_is_rewritten_from_bytes_that_were_proven() {
        let dir = std::env::temp_dir().join(format!("dv-reconcile-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        let file = dir.join("Messages.shift");
        fs::write(&file, b"some bytes").unwrap();
        let digest = hash_file(&file).expect("sha256sum on the build host");

        // No record at all: one is written from the bytes.
        assert_eq!(reconcile(&file, Some(&digest)), Reconcile::Backfilled);
        assert_eq!(read_sidecar(&file), Some(digest.clone()));
        // Agreeing is not a write.
        assert_eq!(reconcile(&file, Some(&digest)), Reconcile::Agreed);

        // A record edited outside the app — same length, other digest.
        let other = "0".repeat(64);
        assert!(write_sidecar(&file, &other));
        assert_eq!(reconcile(&file, Some(&digest)), Reconcile::Rewritten);
        assert_eq!(
            read_sidecar(&file),
            Some(digest.clone()),
            "the record has to describe the bytes that were proven, or the vault \
             tells `sha256sum -c` a different story from the one it tells itself"
        );

        // A caller with no digest writes nothing: absence of evidence is not
        // licence to invent a record.
        assert!(write_sidecar(&file, &other));
        assert_eq!(reconcile(&file, None), Reconcile::Failed);
        assert_eq!(
            read_sidecar(&file),
            Some(other.clone()),
            "nothing was proven, so nothing is rewritten"
        );
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn unrecorded_never_reads_as_a_match() {
        assert_eq!(compare(None, Some(DIGEST)), Check::Unrecorded);
        assert_eq!(compare(None, None), Check::Unrecorded);
        assert_eq!(compare(Some(DIGEST), None), Check::Unrecorded);
        assert_eq!(compare(Some(DIGEST), Some(DIGEST)), Check::Match);
        assert_eq!(
            compare(Some(DIGEST), Some("00")),
            Check::Mismatch,
            "a different digest is a mismatch, not a missing record"
        );
    }

    #[test]
    fn batch_parsing_keeps_every_name_intact() {
        let second = "00bcf1a0503cacce3afe1fc3ba1b2e8710b41a424cf1137de3ccd05906bccfdc";
        let output = format!(
            "{DIGEST}  /vault/Apps/User/Some App/App.shift\n{second}  /vault/Device/Messages.shift\n"
        );
        let pairs = parse_sha256sum_lines(&output);
        assert_eq!(pairs.len(), 2);
        // A label with a space in it must not be split into two paths.
        assert_eq!(pairs[0].0, "/vault/Apps/User/Some App/App.shift");
        assert_eq!(pairs[0].1, DIGEST);
        assert_eq!(pairs[1].0, "/vault/Device/Messages.shift");
        // Binary-mode separator.
        assert_eq!(
            parse_sha256sum_lines(&format!("{DIGEST} *Data.shift\n"))[0].0,
            "Data.shift"
        );
        // A failed read prints a diagnostic on stderr and no line here, so the
        // file is simply absent from the map.
        assert!(parse_sha256sum_lines("sha256sum: x: Permission denied\n").is_empty());
        // An escaped name cannot be mapped back; it is not guessed at.
        assert!(parse_sha256sum_lines(&format!("\\{DIGEST}  odd\\nname\n")).is_empty());
    }

    #[test]
    fn sidecar_paths_sit_next_to_their_archive() {
        assert_eq!(
            sidecar_for(Path::new("/vault/Device/Messages.shift")),
            PathBuf::from("/vault/Device/Messages.shift.sha256")
        );
        // The partition images already use this suffix, so one rule covers both.
        assert_eq!(
            sidecar_for(Path::new("/sdcard/boot.img")),
            PathBuf::from("/sdcard/boot.img.sha256")
        );
    }
}
