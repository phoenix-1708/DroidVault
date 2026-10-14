use crate::command::{self, output, status, valid_package};
use std::fs;
use std::path::{Path, PathBuf};

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

pub fn restore_debloat(package: &str, vault: &str) {
    if !valid_package(package) {
        println!("RESTORE_FAILED|PKG:{package}");
        return;
    }
    // Ask the platform to bring it back, then ASK IT WHETHER IT DID.
    //
    // The exit code is not the answer, and neither is the word "Success".
    // Measured on device, restoring a debloated app that was archived rather than
    // uninstalled: `install-existing` correctly failed with NameNotFoundException,
    // and `request-unarchive` then printed **"Success"** and exited **0** while
    // the package stayed uninstalled for the user — `pm list packages --user 0`
    // still did not list it. The first version of this trusted the exit status
    // and reported RESTORED for an app that was not there, and the screen said
    // "Restore Complete! Successfully restored 1 app."
    //
    // So the verb decides HOW to try; `installed_and_enabled` decides whether it
    // worked, and a verb that did not work simply falls through to the next one.
    let _ = status("cmd", ["package", "install-existing", package]);
    if !installed_and_enabled(package) {
        let _ = status("cmd", ["package", "request-unarchive", package]);
    }
    if installed_and_enabled(package) {
        println!("RESTORED|PKG:{package}");
        return;
    }
    // Neither re-install verb could help. That is what a debloat with Keep Data
    // off does: `remove` runs `cmd package uninstall <pkg>` with no `--user 0`,
    // which uninstalls for every user and deletes /data, so the package is absent
    // from `pm list packages` and `dumpsys package` entirely (measured).
    //
    // The screen still offers "Restore Apps", so something has to answer it. The
    // app's own archive is the thing that can: `App.shift` holds the APK and its
    // splits under `data/app/<install>/<pkg>-<hash>/`, so the package can be put
    // back without a system copy of it existing anywhere.
    match reinstall_from_vault(package, vault) {
        Ok(()) => println!("RESTORED|PKG:{package}"),
        Err(reason) => println!("RESTORE_FAILED|PKG:{package}|MSG:{reason}"),
    }
}

/// The vault's `Apps` directory, rejecting a path that is not absolute or that
/// escapes it. Same rules as the engine's own `app_base`, because this argument
/// arrives from the app and is used to build a path we then unpack into.
fn vault_apps(vault: &str) -> Option<PathBuf> {
    if !vault.starts_with('/')
        || vault.contains('\n')
        || vault.contains('\r')
        || vault.contains('|')
        || vault
            .split('/')
            .any(|segment| segment == ".." || segment == ".")
    {
        return None;
    }
    Some(Path::new(vault.trim_end_matches('/')).join("Apps"))
}

/// Is [package] actually installed AND enabled for user 0?
///
/// The only question worth asking after a restore. An exit status is not it:
/// `request-unarchive` answers "Success" and exits 0 for a package it did not
/// install (measured on device). `pm list packages -e` asks the question
/// directly, and `-e` is the part that matters — a reinstall can leave the
/// package installed but disabled, which is installed in every sense a user
/// cares about and unlaunchable in the one that counts.
fn installed_and_enabled(package: &str) -> bool {
    output("pm", ["list", "packages", "-e", "--user", "0", package])
        .map(|result| {
            String::from_utf8_lossy(&result.stdout).contains(&format!("package:{package}"))
        })
        .unwrap_or(false)
}

/// The archive directory whose `Meta.txt` records `package`, searched under
/// both `User` and `System` — the same two places a backup can land, and an app
/// can be debloated out of either.
///
/// Matched on `Package=` and not on the directory name: the name is the display
/// label, which the user may have changed and which two apps can share.
fn find_app_dir(apps: &Path, package: &str) -> Option<PathBuf> {
    for kind in ["User", "System"] {
        let Ok(entries) = fs::read_dir(apps.join(kind)) else {
            continue;
        };
        for entry in entries.flatten() {
            let meta = entry.path().join("Meta.txt");
            let Ok(text) = fs::read_to_string(&meta) else {
                continue;
            };
            let recorded = text.lines().find_map(|line| {
                line.strip_prefix("Package=")
                    .map(|value| value.trim().to_string())
            });
            if recorded.as_deref() == Some(package) {
                return Some(entry.path());
            }
        }
    }
    None
}

/// Every `*.apk` under `dir`, `base.apk` first. Split APKs are meaningless
/// without their base, and `pm install` requires the base to lead.
fn collect_apks(dir: &Path, out: &mut Vec<PathBuf>) {
    let Ok(entries) = fs::read_dir(dir) else {
        return;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        if path.is_dir() {
            collect_apks(&path, out);
        } else if path.extension().is_some_and(|ext| ext == "apk") {
            out.push(path);
        }
    }
    out.sort_by_key(|path| {
        if path.file_name().is_some_and(|name| name == "base.apk") {
            (0, path.clone())
        } else {
            (1, path.clone())
        }
    });
}

fn reinstall_from_vault(package: &str, vault: &str) -> Result<(), String> {
    let apps = vault_apps(vault).ok_or_else(|| "Invalid backup folder".to_string())?;
    let dir = find_app_dir(&apps, package)
        .ok_or_else(|| "No backup of this app in the vault".to_string())?;
    let archive = dir.join("App.shift");
    if !archive.is_file() {
        return Err("The backup holds no app package to reinstall".into());
    }

    // Staged outside the vault: this is scratch space for one install, and the
    // vault holds the only good copy of these bytes.
    //
    // The stage has to OUTLIVE the install. Removing it as soon as the APK paths
    // are collected leaves those paths naming files that are already gone, and
    // `pm install` answers "Unable to open file" for every one — a restore that
    // reports failure while having done everything right up to the install.
    // Cleanup belongs after the install has been attempted, not after the paths
    // have been read out of them.
    let staging = Path::new("/data/local/tmp/droidvault-reinstall");
    let _ = fs::remove_dir_all(staging);
    crate::archive::unpack(&archive, staging)
        .map_err(|error| format!("Could not read the backup: {error}"))?;

    let mut apks = Vec::new();
    collect_apks(staging, &mut apks);
    if apks.is_empty() {
        let _ = fs::remove_dir_all(staging);
        return Err("The backup holds no app package to reinstall".into());
    }

    let mut args: Vec<String> = vec![
        "install".into(),
        "-r".into(),
        "--user".into(),
        "0".into(),
        "-g".into(),
    ];
    args.extend(apks.iter().map(|path| path.to_string_lossy().into_owned()));
    let installed = match output("pm", &args) {
        Ok(result) if result.status.success() => Ok(()),
        Ok(result) => {
            let message = String::from_utf8_lossy(&result.stderr)
                .lines()
                .next()
                .unwrap_or("install failed")
                .replace(['|', '\n'], " ");
            Err(format!("install refused: {message}"))
        }
        Err(error) => Err(format!("install failed: {error}")),
    };
    let _ = fs::remove_dir_all(staging);
    installed?;

    // `pm install` exits 0 for a package that comes back DISABLED: the package
    // manager keeps a disabled state across a reinstall, so an app that was
    // frozen, archived or uninstalled is put on disk and still cannot run. That
    // is the worst shape this can take — the app reported "Successfully
    // restored 2 apps" while both sat disabled and unlaunchable (measured on
    // device immediately after a real restore). So: enable it, then confirm it
    // is really there AND enabled before saying RESTORED.
    //
    // `pm list packages -e` is the check, because it answers "is this enabled"
    // directly instead of parsing a `dumpsys package` line that differs per
    // Android version.
    let _ = status("pm", ["enable", package]);
    if !installed_and_enabled(package) {
        return Err("installed, but Android still has it disabled".into());
    }
    Ok(())
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

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    fn scratch(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("dv-tools-{name}"));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn write_app(apps: &Path, kind: &str, dir_name: &str, meta: &str) -> PathBuf {
        let dir = apps.join(kind).join(dir_name);
        fs::create_dir_all(&dir).unwrap();
        fs::write(dir.join("Meta.txt"), meta).unwrap();
        dir
    }

    // A vault path arrives from the app and is used to build a directory we then
    // unpack into, so anything that is not plainly an absolute path inside a
    // chosen vault must be refused rather than normalised.
    #[test]
    fn a_vault_path_that_escapes_or_is_not_absolute_is_refused() {
        for bad in [
            "relative/vault",
            "/storage/../etc",
            "/storage/./DroidVault",
            "/storage/DroidVault\n/x",
            "/storage/pipe|here",
            "",
        ] {
            assert!(
                vault_apps(bad).is_none(),
                "should have refused {bad:?} but returned {:?}",
                vault_apps(bad)
            );
        }
    }

    #[test]
    fn a_plain_absolute_vault_path_resolves_to_its_apps_directory() {
        assert_eq!(
            vault_apps("/storage/emulated/0/DroidVault/"),
            Some(PathBuf::from("/storage/emulated/0/DroidVault/Apps"))
        );
        // A space is a legitimate directory name and must not be refused.
        assert_eq!(
            vault_apps("/storage/emulated/0/My Vault"),
            Some(PathBuf::from("/storage/emulated/0/My Vault/Apps"))
        );
    }

    // The archive directory is keyed on the DISPLAY LABEL, which the user can
    // change and two apps can share — so the match is on the recorded package,
    // and a directory whose Meta.txt names a different package is never used.
    #[test]
    fn the_app_directory_is_found_by_package_not_by_folder_name() {
        let root = scratch("find");
        let apps = root.join("Apps");
        write_app(
            &apps,
            "User",
            "Old Label",
            "Name=Old Label\nPackage=com.example.target\n",
        );
        let dir = find_app_dir(&apps, "com.example.target");
        assert_eq!(dir, Some(apps.join("User/Old Label")));
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn a_directory_naming_another_package_is_not_taken_for_this_one() {
        let root = scratch("mismatch");
        let apps = root.join("Apps");
        write_app(
            &apps,
            "User",
            "Decoy",
            "Name=Decoy\nPackage=com.example.other\n",
        );
        assert_eq!(find_app_dir(&apps, "com.example.target"), None);
        let _ = fs::remove_dir_all(&root);
    }

    // An app debloated out of System lands its archive under System, not User,
    // and the restore must find it there or it restores nothing.
    #[test]
    fn a_system_app_archive_is_found_under_system() {
        let root = scratch("sys");
        let apps = root.join("Apps");
        write_app(
            &apps,
            "System",
            "Verifier",
            "Name=Verifier\nPackage=com.example.sys\n",
        );
        assert_eq!(
            find_app_dir(&apps, "com.example.sys"),
            Some(apps.join("System/Verifier"))
        );
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn a_missing_or_unreadable_apps_directory_finds_nothing_rather_than_failing() {
        let root = scratch("absent");
        assert_eq!(find_app_dir(&root.join("nope"), "com.example.target"), None);
        let _ = fs::remove_dir_all(&root);
    }

    // Splits are meaningless without their base, and `pm install` needs the base
    // to lead — so base.apk is hoisted out of lexicographic order.
    //
    // The splits below are named so they sort BEFORE "base.apk" ("a" < "b").
    // Real splits happen to be `split_config.*`, which already sorts after
    // base.apk — so a test written only against that naming passes even with the
    // hoisting deleted, and did, twice, until this was mutation-checked. The
    // rule is "base first regardless of the split's name", so the test uses names
    // that would break it.
    #[test]
    fn the_base_apk_leads_the_split_apks() {
        let root = scratch("apks");
        let install = root.join("data/app/abc/com.example-target-xyz");
        fs::create_dir_all(&install).unwrap();
        for name in [
            "aaa_config.arm64_v8a.apk",
            "aaa_config.en.apk",
            "base.apk",
            "resources.arsc",
        ] {
            fs::write(install.join(name), b"x").unwrap();
        }
        let mut found = Vec::new();
        collect_apks(&root, &mut found);
        let names: Vec<String> = found
            .iter()
            .map(|p| p.file_name().unwrap().to_string_lossy().into_owned())
            .collect();
        assert_eq!(
            names[0], "base.apk",
            "base.apk must come first, got {names:?}"
        );
        assert_eq!(names.len(), 3, "only apks are collected, got {names:?}");
        assert!(
            names.iter().all(|n| n.ends_with(".apk")),
            "non-apk members leaked in: {names:?}"
        );
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn an_archive_with_no_apk_in_it_collects_nothing() {
        let root = scratch("noapk");
        fs::write(root.join("Meta.txt"), "Package=com.example.target\n").unwrap();
        let mut found = Vec::new();
        collect_apks(&root, &mut found);
        assert!(found.is_empty());
        let _ = fs::remove_dir_all(&root);
    }
}
