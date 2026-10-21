//! The vault's archive vocabulary: what an archive is called, and how an
//! archive written under the old name is still found.
//!
//! ## Why the extension moved
//!
//! A vault archive used to be `*.shift`, a name inherited from the project
//! this app was forked from. It is now `*.vault` — the archive belongs to the
//! vault, and the vault is called Droid Vault.
//!
//! ## The rule that matters: an old archive is still an archive
//!
//! The extension is a **wire format**, not a label. Every archive already on
//! a user's card was written as `*.shift`, and an app that stopped looking
//! for those would report an empty vault and back up nothing — the user's
//! backups would be invisible rather than missing.
//!
//! So the name is resolved, never assumed:
//!
//!  - a WRITE always uses the new name;
//!  - a READ resolves the new name first and the old one second;
//!  - and a one-time migration renames the archives already on disk, so the
//!    vault never holds two copies of the same thing.
//!
//! The read fallback is what makes the migration safe: if it does not run
//! (no root yet, a card that cannot be written), the old archives are still
//! found, still restored, and still verified. The migration is a tidy-up,
//! not a dependency.

use std::path::{Path, PathBuf};

/// The extension a vault archive carries.
pub const ARCHIVE_EXT: &str = "vault";

/// The extension the app used before, and still reads.
pub const LEGACY_ARCHIVE_EXT: &str = "shift";

/// The file name an archive is written as.
pub fn archive_name(base: &str) -> String {
    format!("{base}.{ARCHIVE_EXT}")
}

/// The file names an archive may have, newest first.
pub fn archive_names(base: &str) -> Vec<String> {
    vec![archive_name(base), format!("{base}.{LEGACY_ARCHIVE_EXT}")]
}

/// The archive for this base that is actually on disk, newest first.
///
/// `None` when neither name is there. Every read path goes through this, so
/// an archive written under the old name is found exactly as a new one is.
pub fn existing_archive(dir: &Path, base: &str) -> Option<PathBuf> {
    archive_names(base)
        .into_iter()
        .map(|name| dir.join(name))
        .find(|path| path.is_file())
}

/// Whether an archive for this base exists, under either name.
pub fn has_archive(dir: &Path, base: &str) -> bool {
    existing_archive(dir, base).is_some()
}

/// The sidecar that carries an archive's digest.
pub fn sidecar_name(archive: &Path) -> PathBuf {
    let mut name = archive.as_os_str().to_os_string();
    name.push(".sha256");
    PathBuf::from(name)
}

/// Whether a file name is an archive, under either extension.
///
/// The vault index and the transfer packer both filter on this, and a filter
/// that only knew the new name would drop every legacy archive from the
/// index and out of an export.
pub fn is_archive_name(name: &str) -> bool {
    name.ends_with(&format!(".{ARCHIVE_EXT}")) || name.ends_with(&format!(".{LEGACY_ARCHIVE_EXT}"))
}

/// Whether a file name is an archive's digest sidecar, under either extension.
///
/// The sidecar is named after the archive it describes, so the stem has to be
/// an archive name too: `App.vault.sha256` is one, and `notes.sha256` is not.
pub fn is_sidecar_name(name: &str) -> bool {
    [".sha256", ".sha256.new"].iter().any(|suffix| {
        name.strip_suffix(suffix)
            .map(|stem| is_archive_name(stem))
            .unwrap_or(false)
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_new_archive_is_written_with_the_new_name() {
        assert_eq!(archive_name("App"), "App.vault");
        assert_eq!(archive_name("Messages"), "Messages.vault");
    }

    #[test]
    fn the_old_name_is_still_resolved() {
        let dir = tempdir("vault-names");
        std::fs::create_dir_all(&dir).unwrap();
        // Only the old name on disk: it is found.
        std::fs::write(dir.join("App.shift"), b"x").unwrap();
        let found = existing_archive(&dir, "App").unwrap();
        assert!(found.ends_with("App.shift"), "got {:?}", found);
        // The new name wins when both are there.
        std::fs::write(dir.join("App.vault"), b"x").unwrap();
        let found = existing_archive(&dir, "App").unwrap();
        assert!(found.ends_with("App.vault"), "got {:?}", found);
        // A base with neither is absent.
        assert!(existing_archive(&dir, "Data").is_none());
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn both_names_are_archives() {
        assert!(is_archive_name("App.vault"));
        assert!(is_archive_name("App.shift"));
        assert!(!is_archive_name("Meta.txt"));
        assert!(!is_archive_name("automation-state.json"));
        assert!(is_sidecar_name("App.vault.sha256"));
        assert!(is_sidecar_name("App.shift.sha256"));
        assert!(is_sidecar_name("App.shift.sha256.new"));
        assert!(!is_sidecar_name("App.vault"));
    }

    fn tempdir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("dv-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        dir
    }
}
