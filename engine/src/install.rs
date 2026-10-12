use crate::archive;
use crate::command::{TEMP_DIR, output, shell_quote, status};
use crate::protocol;
use std::fs;
use std::path::{Path, PathBuf};

fn safe_package(package: &str) -> bool {
    crate::command::valid_package(package)
}

fn apk_files(root: &Path) -> Vec<PathBuf> {
    fn visit(dir: &Path, out: &mut Vec<PathBuf>) {
        let Ok(entries) = fs::read_dir(dir) else {
            return;
        };
        for entry in entries.flatten() {
            let path = entry.path();
            let Ok(meta) = fs::symlink_metadata(&path) else {
                continue;
            };
            if meta.file_type().is_symlink() {
                continue;
            }
            if meta.is_dir() {
                visit(&path, out);
            } else if path
                .extension()
                .is_some_and(|e| e.to_string_lossy().eq_ignore_ascii_case("apk"))
            {
                out.push(path);
            }
        }
    }
    let mut result = Vec::new();
    visit(root, &mut result);
    result.sort();
    result
}

fn make_staging_readable(root: &Path) {
    let _ = status("chmod", ["755", root.to_string_lossy().as_ref()]);
    let Ok(entries) = fs::read_dir(root) else {
        return;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        if fs::symlink_metadata(&path)
            .map(|m| m.file_type().is_symlink())
            .unwrap_or(true)
        {
            continue;
        }
        if path.is_dir() {
            make_staging_readable(&path);
        } else if path
            .extension()
            .is_some_and(|ext| ext.to_string_lossy().eq_ignore_ascii_case("apk"))
        {
            let _ = status("chmod", ["644", path.to_string_lossy().as_ref()]);
        }
    }
}

fn install_from_directory(
    directory: &Path,
    package: &str,
    label: &str,
    downgrade: bool,
) -> Result<(), String> {
    if !safe_package(package) {
        return Err("Invalid package id".into());
    }
    protocol::step_for_pkg("INSTALLING", package, label);
    make_staging_readable(directory);
    let apks = apk_files(directory);
    if apks.is_empty() {
        return Err("No APKs extracted from archive".into());
    }

    let mut flags = String::from("--user 0 -i com.android.vending --install-reason 4 -r");
    if downgrade {
        flags.push_str(" -d");
    }
    flags.push_str(" -t --bypass-low-target-sdk-block");
    let create = format!("cmd package install-create {flags} 2>/dev/null");
    let session_result = output("su", ["1000", "-c", create.as_str()])
        .map(|o| String::from_utf8_lossy(&o.stdout).into_owned())
        .unwrap_or_default();
    let session = session_result
        .split(|c: char| !c.is_ascii_digit())
        .find(|s| !s.is_empty())
        .map(str::to_string);

    if let Some(session) = session {
        for (index, apk) in apks.iter().enumerate() {
            let apk_path = apk.to_string_lossy();
            if apk.strip_prefix(directory).is_err() {
                let _ = status(
                    "su",
                    [
                        "1000",
                        "-c",
                        &format!("cmd package install-abandon {session}"),
                    ],
                );
                return Err("Rejected APK path outside staging directory".into());
            }
            let command = format!(
                "cmd package install-write {session} split_{} {} >/dev/null 2>&1",
                index + 1,
                shell_quote(&apk_path)
            );
            let ok = status("su", ["1000", "-c", &command]);
            if !ok {
                let _ = status("cmd", ["package", "install-abandon", &session]);
                return Err("Could not add APK to install session".into());
            }
        }
        let commit = format!("cmd package install-commit {session} 2>&1");
        let result = output("su", ["1000", "-c", commit.as_str()])
            .or_else(|_| output("cmd", ["package", "install-commit", &session]))
            .map(|o| String::from_utf8_lossy(&o.stdout).into_owned())
            .unwrap_or_default();
        if result.to_ascii_lowercase().contains("success") {
            Ok(())
        } else {
            Err(result.trim().to_string().if_empty("Package install failed"))
        }
    } else {
        for apk in apks {
            let result = output(
                "pm",
                ["install", "-r", "-d", apk.to_string_lossy().as_ref()],
            )
            .map(|o| String::from_utf8_lossy(&o.stdout).into_owned())
            .unwrap_or_default();
            if !result.to_ascii_lowercase().contains("success") {
                return Err(result.trim().to_string().if_empty("Package install failed"));
            }
        }
        Ok(())
    }
}

trait EmptyFallback {
    fn if_empty(self, fallback: &str) -> String;
}
impl EmptyFallback for String {
    fn if_empty(self, fallback: &str) -> String {
        if self.is_empty() {
            fallback.into()
        } else {
            self
        }
    }
}

pub fn install_file(file: &str, package: &str, label: &str) -> Result<(), String> {
    let src = Path::new(file);
    if !file.starts_with("/data/local/tmp/droidvault_install/") || !src.is_file() {
        return Err("Rejected unsafe install target".into());
    }
    if !safe_package(package) {
        return Err("Invalid package id".into());
    }
    let directory = Path::new(TEMP_DIR).join(format!("install_{package}"));
    let _ = fs::remove_dir_all(&directory);
    fs::create_dir_all(&directory).map_err(|e| format!("Unable to stage package: {e}"))?;
    let _ = status("chmod", ["755", directory.to_string_lossy().as_ref()]);
    let extension = src.extension().and_then(|s| s.to_str()).unwrap_or_default();
    if extension.eq_ignore_ascii_case("apk") {
        fs::copy(src, directory.join("base.apk"))
            .map_err(|e| format!("Unable to stage APK: {e}"))?;
    } else {
        let listing = output("unzip", ["-Z1", file])
            .map_err(|_| "Unable to inspect package archive".to_string())?;
        let names = String::from_utf8_lossy(&listing.stdout);
        if names.lines().any(|name| {
            let path = Path::new(name);
            path.is_absolute()
                || path
                    .components()
                    .any(|c| matches!(c, std::path::Component::ParentDir))
        }) {
            let _ = fs::remove_dir_all(&directory);
            return Err("Rejected unsafe archive entries".into());
        }
        if !status(
            "unzip",
            ["-q", file, "-d", directory.to_string_lossy().as_ref()],
        ) {
            let _ = fs::remove_dir_all(&directory);
            return Err("Unable to extract package archive".into());
        }
    }
    let result = install_from_directory(&directory, package, label, true);
    let _ = fs::remove_dir_all(directory);
    result
}

pub fn install_backup(archive_path: &Path, package: &str, label: &str) -> Result<(), String> {
    let directory = Path::new(TEMP_DIR).join(package);
    let _ = fs::remove_dir_all(&directory);
    fs::create_dir_all(&directory).map_err(|e| format!("Unable to stage backup APKs: {e}"))?;
    let result = match archive::unpack(archive_path, &directory) {
        Ok(true) => install_from_directory(&directory, package, label, true),
        Ok(false) => Err("APK archive extraction failed".into()),
        Err(error) => Err(format!("APK archive extraction failed: {error}")),
    };
    let _ = fs::remove_dir_all(directory);
    result
}
