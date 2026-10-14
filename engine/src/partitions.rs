use crate::command::{self, status};
use std::fs;
use std::os::unix::fs::FileTypeExt;
use std::path::{Path, PathBuf};

const BLOCK_PATHS: [&str; 3] = [
    "/dev/block/by-name",
    "/dev/block/bootdevice/by-name",
    "/dev/block/mapper",
];
const BLOCKED: [&str; 16] = [
    "system",
    "system_ext",
    "super",
    "vendor",
    "product",
    "odm",
    "oem",
    "userdata",
    "metadata",
    "persist",
    "control",
    "boot",
    "init_boot",
    "vbmeta",
    "dtbo",
    "recovery",
];

fn find_block(partition: &str) -> Option<PathBuf> {
    if partition.is_empty()
        || !partition
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
    {
        return None;
    }
    BLOCK_PATHS
        .iter()
        .map(|dir| Path::new(dir).join(partition))
        .find(|p| p.exists())
}

pub fn get_partitions() {
    let mut result = std::collections::BTreeSet::new();
    for dir in BLOCK_PATHS {
        if let Ok(entries) = fs::read_dir(dir) {
            for entry in entries.flatten() {
                let name = entry.file_name().to_string_lossy().into_owned();
                if entry.file_type().map(|t| t.is_dir()).unwrap_or(false) {
                    continue;
                }
                if BLOCKED
                    .iter()
                    .any(|blocked| name == *blocked || name.starts_with(&format!("{blocked}_")))
                {
                    continue;
                }
                result.insert(name);
            }
        }
    }
    for name in result {
        println!("{name}");
    }
}

pub fn get_images(base: &str) {
    let dir = Path::new(base).join("Partitions");
    if let Ok(entries) = fs::read_dir(dir) {
        let mut names: Vec<String> = entries
            .flatten()
            .map(|e| e.file_name().to_string_lossy().into_owned())
            .filter(|n| n.ends_with(".img"))
            .collect();
        names.sort();
        for name in names {
            println!("{name}");
        }
    }
}

pub fn delete_image(base: &str, name: &str) {
    if !name.ends_with(".img") || name.contains('/') || name.contains("..") {
        crate::protocol::error("Invalid image name");
        return;
    }
    let path = Path::new(base).join("Partitions").join(name);
    if path.is_file()
        && !fs::symlink_metadata(&path)
            .map(|m| m.file_type().is_symlink())
            .unwrap_or(true)
    {
        let _ = fs::remove_file(path);
    }
}

fn protected_backup(part: &str) -> bool {
    [
        "boot",
        "init_boot",
        "vbmeta",
        "dtbo",
        "super",
        "userdata",
        "metadata",
        "system",
        "vendor",
        "product",
        "odm",
        "oem",
    ]
    .iter()
    .any(|prefix| part == *prefix || part.starts_with(&format!("{prefix}_")))
}

fn protected_restore(part: &str) -> bool {
    [
        "super", "userdata", "metadata", "system", "vendor", "product", "odm", "oem",
    ]
    .iter()
    .any(|prefix| part == *prefix || part.starts_with(&format!("{prefix}_")))
}

pub fn live_backup(partition: &str, base: &str) -> Result<(), String> {
    if protected_backup(partition) {
        return Err(format!(
            "Refusing to backup protected partition: {partition}"
        ));
    }
    let block =
        find_block(partition).ok_or_else(|| format!("Block device not found: {partition}"))?;
    let meta = fs::metadata(&block).map_err(|_| format!("Block device not found: {partition}"))?;
    if !meta.file_type().is_block_device() {
        return Err(format!("Not a block device: {partition}"));
    }
    let dir = Path::new(base).join("Partitions");
    fs::create_dir_all(&dir).map_err(|e| format!("Cannot create image folder: {e}"))?;
    let image = dir.join(format!("{partition}_backup.img"));

    // STAGE, then rename. `dd of=<final>` truncates the destination at open(2),
    // so a failed or ENOSPC read destroyed the PREVIOUS good image on the way to
    // reporting "Backup failed" — and left the truncated file behind, still
    // listed by `--get-images` and still offered by the Flash tab. This is
    // invariant 23 at the one place where being wrong is a brick: a partial
    // image written to `boot` is usually an unbootable phone.
    let staged = dir.join(format!("{partition}_backup.img.tmp"));
    let _ = fs::remove_file(&staged);
    if !status(
        "dd",
        [
            format!("if={}", block.display()),
            format!("of={}", staged.display()),
            "bs=4M".into(),
            "status=none".into(),
        ],
    ) || !status("sync", std::iter::empty::<&str>())
    {
        let _ = fs::remove_file(&staged);
        return Err(format!("Backup failed: {partition}"));
    }

    // And the staged file must be the WHOLE partition before it is allowed to
    // become the live image. A short read is the failure `dd` reports as success
    // on some block devices, and it is the one that used to reach a flash.
    let written = fs::metadata(&staged).map(|m| m.len()).unwrap_or(0);
    let expected = meta.len();
    if expected > 0 && written != expected {
        let _ = fs::remove_file(&staged);
        return Err(format!(
            "Backup incomplete: {partition} wrote {written} of {expected} bytes"
        ));
    }
    if fs::rename(&staged, &image).is_err() {
        let _ = fs::remove_file(&staged);
        return Err(format!(
            "Backup failed: could not place the image for {partition}"
        ));
    }

    // The digest is recorded after the swap, and only now: a sidecar written for
    // a file that never landed is the record of an image that does not exist.
    let digest = command::text("sha256sum", [image.to_string_lossy().as_ref()]);
    if let Some(hash) = digest.split_whitespace().next() {
        let _ = fs::write(format!("{}.sha256", image.display()), format!("{hash}\n"));
    }
    println!("LIVE_BACKUP_OK|PART:{partition}");
    Ok(())
}

/**
 * Refuse to flash an image whose recorded SHA-256 does not match, or whose
 * checksum file exists but is unreadable.
 *
 * live_backup writes `<image>.sha256` next to every image it produces, but
 * nothing ever consumed it: an image truncated by an interrupted `dd` (power
 * cut, full disk, cancelled run) still listed under --get-images and was
 * flashed as-is — a partial image written straight to a boot partition is
 * usually a hard brick. An image with NO checksum file predates this gate
 * (or was copied in by hand), so it is allowed through rather than making
 * every older backup unusable; a checksum file that exists must match.
 */
/// Whether the recorded evidence permits flashing an image.
///
/// Pure, so the rule can be tested without a block device: this is the decision
/// that stands between a truncated image and a bricked phone.
///
/// The rule that matters is the first branch. "No digest recorded" is NOT
/// permission to flash — it is the signature of an interrupted `dd`, because the
/// digest is only written after a successful read. Treating an absent record as
/// a pass is the same class of bug as §2.50's `restorecon`: the safety net that
/// was assumed rather than checked, and it fails open on exactly the input it
/// was written for.
pub fn flash_gate(sidecar: Option<&str>, digest_matches: bool) -> Result<(), &'static str> {
    let Some(recorded) = sidecar else {
        return Err(
            "no checksum recorded — an image interrupted mid-write has none, and it \
             cannot be distinguished from a good one by its bytes alone. Re-create \
             the backup.",
        );
    };
    let expected = recorded.split_whitespace().next().unwrap_or_default();
    if expected.is_empty() {
        return Err("checksum file is empty");
    }
    if !expected.bytes().all(|b| b.is_ascii_hexdigit()) || expected.len() != 64 {
        return Err("checksum file is malformed");
    }
    if !digest_matches {
        return Err("image checksum mismatch — refusing to flash a possibly truncated image");
    }
    Ok(())
}

fn verify_image_checksum(image: &Path) -> Result<(), String> {
    let checksum_file = PathBuf::from(format!("{}.sha256", image.display()));
    let recorded = fs::read_to_string(&checksum_file).ok();
    // The rule itself lives in flash_gate; this only gathers the evidence, so
    // there is one implementation and it is the tested one.
    let actual = match &recorded {
        Some(_) => command::text("sha256sum", [image.to_string_lossy().as_ref()])
            .split_whitespace()
            .next()
            .unwrap_or_default()
            .to_string(),
        None => String::new(),
    };
    let expected = recorded
        .as_deref()
        .and_then(|r| r.split_whitespace().next())
        .unwrap_or_default();
    flash_gate(recorded.as_deref(), actual.eq_ignore_ascii_case(expected))
        .map_err(|why| format!("{why} ({})", image.display()))
}

pub fn live_restore(partition: &str, image: &str) -> Result<(), String> {
    if protected_restore(partition) {
        return Err(format!(
            "Refusing to flash protected partition: {partition} (use recovery)"
        ));
    }
    // Checksum gate BEFORE any block-device write: a mismatch must never
    // reach dd, since a truncated image written to a boot partition bricks
    // the device.
    verify_image_checksum(Path::new(image))?;
    let block =
        find_block(partition).ok_or_else(|| format!("Block device not found: {partition}"))?;
    if !fs::metadata(&block)
        .map(|m| m.file_type().is_block_device())
        .unwrap_or(false)
    {
        return Err(format!("Not a block device: {partition}"));
    }
    let img = Path::new(image);
    if !img.is_file()
        || img
            .extension()
            .is_none_or(|ext| !ext.to_string_lossy().eq_ignore_ascii_case("img"))
    {
        return Err("Only an existing .img file can be flashed".into());
    }
    if !status(
        "dd",
        [
            format!("if={image}"),
            format!("of={}", block.display()),
            "bs=4M".into(),
            "status=none".into(),
        ],
    ) || !status("sync", std::iter::empty::<&str>())
    {
        return Err(format!("Flash failed: {partition}"));
    }
    println!("LIVE_RESTORE_OK|PART:{partition}");
    Ok(())
}

#[cfg(test)]
mod image_safety_tests {
    use super::flash_gate;

    const GOOD: &str = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    #[test]
    fn an_image_with_no_recorded_digest_is_never_flashed() {
        // THE finding. The digest is written only after `dd && sync` succeed, so
        // a missing sidecar is the SIGNATURE of an interrupted read. The gate
        // used to return Ok(()) here — waving through the one image nobody could
        // prove was safe, which is the truncated one.
        assert!(
            flash_gate(None, true).is_err(),
            "an unverifiable image must not be flashable"
        );
        // Even when the bytes claim to match, which they will if the digest was
        // computed over the same truncated file.
        assert!(flash_gate(None, false).is_err());
    }

    #[test]
    fn a_changed_image_is_refused() {
        let sidecar = format!("{GOOD}  boot_backup.img\n");
        assert!(
            flash_gate(Some(&sidecar), false).is_err(),
            "an image whose bytes no longer match its record must not be flashed"
        );
        assert!(flash_gate(Some(&sidecar), true).is_ok());
    }

    #[test]
    fn a_malformed_or_empty_record_is_refused() {
        for bad in [
            "",
            "   ",
            "\n",
            "not hex",
            &"z".repeat(64),
            &"a".repeat(63),
            &"a".repeat(65),
        ] {
            assert!(
                flash_gate(Some(bad), true).is_err(),
                "a record that is not a 64-char digest must not authorise a flash: {bad:?}"
            );
        }
    }

    #[test]
    fn the_error_says_how_to_recover() {
        // A refusal the user cannot act on is a dead end; the fix is one action.
        let why = flash_gate(None, true).unwrap_err();
        assert!(
            why.contains("Re-create") || why.contains("re-create"),
            "the refusal must tell the user what to do: {why}"
        );
    }
}
