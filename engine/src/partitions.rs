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
    if !status(
        "dd",
        [
            format!("if={}", block.display()),
            format!("of={}", image.display()),
            "bs=4M".into(),
            "status=none".into(),
        ],
    ) || !status("sync", std::iter::empty::<&str>())
    {
        return Err(format!("Backup failed: {partition}"));
    }
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
fn verify_image_checksum(image: &Path) -> Result<(), String> {
    let checksum_file = PathBuf::from(format!("{}.sha256", image.display()));
    let Ok(recorded) = fs::read_to_string(&checksum_file) else {
        return Ok(());
    };
    let Some(expected) = recorded.split_whitespace().next() else {
        return Err(format!(
            "Checksum file is empty: {}",
            checksum_file.display()
        ));
    };
    if !expected.bytes().all(|b| b.is_ascii_hexdigit()) || expected.len() != 64 {
        return Err(format!(
            "Checksum file is malformed: {}",
            checksum_file.display()
        ));
    }
    let actual = command::text("sha256sum", [image.to_string_lossy().as_ref()]);
    let actual = actual.split_whitespace().next().unwrap_or_default();
    if !actual.eq_ignore_ascii_case(expected) {
        return Err(format!(
            "Image checksum mismatch — refusing to flash a possibly truncated image ({})",
            image.display()
        ));
    }
    Ok(())
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
