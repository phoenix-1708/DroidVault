use crate::command::{self, output, status, valid_package};
use std::fs;
use std::path::Path;

pub fn remove(package: &str, keep_data: bool) {
    if !valid_package(package) {
        println!("REMOVE_FAILED|PKG:{package}");
        return;
    }
    let ok = if keep_data {
        status("cmd", ["package", "archive", package])
            || status(
                "cmd",
                ["package", "uninstall", "--user", "0", "-k", package],
            )
    } else {
        status("cmd", ["package", "uninstall", package])
            || status("cmd", ["package", "uninstall", "--user", "0", package])
    };
    println!(
        "{}|PKG:{package}",
        if ok { "REMOVED" } else { "REMOVE_FAILED" }
    );
}

pub fn restore_debloat(package: &str) {
    if !valid_package(package) {
        println!("RESTORE_FAILED|PKG:{package}");
        return;
    }
    let ok = status("cmd", ["package", "install-existing", package])
        || status("cmd", ["package", "request-unarchive", package]);
    println!(
        "{}|PKG:{package}",
        if ok { "RESTORED" } else { "RESTORE_FAILED" }
    );
}

pub fn systemize(package: &str, label: &str, version: &str, version_code: &str) {
    if !valid_package(package) {
        println!("SYSTEMIZE_FAILED|PKG:{package}|MSG:Invalid package id");
        return;
    }
    let safe_label: String = label
        .chars()
        .filter(|c| c.is_ascii_alphanumeric() || *c == '_')
        .take(64)
        .collect();
    if safe_label.is_empty() {
        println!("SYSTEMIZE_FAILED|PKG:{package}|MSG:Invalid app label");
        return;
    }
    let module = Path::new("/data/adb/modules/DroidVault");
    let update = Path::new("/data/adb/modules_update/DroidVault");
    let prop = format!(
        "id=DroidVault\nname=Droid Vault Module\nversion={version}\nversionCode={version_code}\nauthor=Droid Vault\ndescription=Used for some system dependent features such as Systemizer\n"
    );
    for dir in [module, update] {
        if fs::create_dir_all(dir).is_err() {
            println!("SYSTEMIZE_FAILED|PKG:{package}|MSG:Module folder creation failed");
            return;
        }
        let file = dir.join("module.prop");
        if fs::write(&file, &prop).is_err() {
            println!("SYSTEMIZE_FAILED|PKG:{package}|MSG:Module metadata write failed");
            return;
        }
        let _ = command::status("chmod", ["644", file.to_string_lossy().as_ref()]);
    }
    let package_path = command::text("cmd", ["package", "path", package]);
    let source = package_path
        .lines()
        .find_map(|line| line.strip_prefix("package:"))
        .and_then(|p| Path::new(p).parent());
    let Some(source) = source else {
        println!("SYSTEMIZE_FAILED|PKG:{package}|MSG:APK path not found");
        return;
    };
    let target = update.join("system/product/priv-app").join(safe_label);
    if fs::create_dir_all(&target).is_err() {
        println!("SYSTEMIZE_FAILED|PKG:{package}|MSG:Target folder creation failed");
        return;
    }
    let mut copied = false;
    if let Ok(entries) = fs::read_dir(source) {
        for entry in entries.flatten() {
            let path = entry.path();
            if path
                .extension()
                .is_some_and(|e| e.to_string_lossy().eq_ignore_ascii_case("apk"))
            {
                if fs::copy(&path, target.join(entry.file_name())).is_ok() {
                    copied = true;
                }
            }
        }
    }
    if copied {
        let _ = command::status("chmod", ["755", target.to_string_lossy().as_ref()]);
        if let Ok(entries) = fs::read_dir(&target) {
            for entry in entries.flatten() {
                let _ = command::status("chmod", ["644", entry.path().to_string_lossy().as_ref()]);
            }
        }
        println!("SYSTEMIZED|PKG:{package}");
    } else {
        println!("SYSTEMIZE_FAILED|PKG:{package}|MSG:APK copy failed");
    }
}

pub fn package_installed(package: &str, version_code: &str) -> bool {
    if !valid_package(package) {
        return false;
    }
    let path = command::text("cmd", ["package", "path", package]);
    if path.trim().is_empty() {
        return false;
    }
    if version_code.is_empty() {
        return true;
    }
    let dump = output("dumpsys", ["package", package])
        .map(|o| String::from_utf8_lossy(&o.stdout).into_owned())
        .unwrap_or_default();
    dump.lines()
        .find(|line| line.contains("versionCode="))
        .and_then(|line| line.split("versionCode=").nth(1))
        .and_then(|rest| rest.split(|c: char| !c.is_ascii_digit()).next())
        .is_some_and(|found| found == version_code)
}
