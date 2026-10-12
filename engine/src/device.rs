use crate::command::status;
use crate::protocol;
use std::fs;
use std::path::Path;
use std::thread;
use std::time::Duration;

fn copy_file(src: &Path, dst: &Path) {
    let _ = status(
        "cp",
        [
            "-f",
            src.to_string_lossy().as_ref(),
            dst.to_string_lossy().as_ref(),
        ],
    );
}

fn recreate_directory(path: &Path) {
    let _ = fs::remove_dir_all(path);
    let _ = fs::create_dir_all(path);
}

fn is_app_cache_child(path: &Path) -> bool {
    let value = path.to_string_lossy();
    [
        "/data/user/0/com.phoenix.droidvault/cache/",
        "/data/data/com.phoenix.droidvault/cache/",
    ]
    .iter()
    .any(|prefix| value.starts_with(prefix) && !value[prefix.len()..].is_empty())
        && !path
            .components()
            .any(|part| matches!(part, std::path::Component::ParentDir))
}

pub fn backup_wifi(destination: &str) {
    let dest = Path::new(destination);
    if !is_app_cache_child(dest) {
        protocol::error("Refusing unsafe Wi-Fi backup destination");
        return;
    }
    recreate_directory(dest);
    let roots = [
        Path::new("/data/misc/apexdata/com.android.wifi"),
        Path::new("/data/misc/wifi"),
    ];
    let source = roots.iter().find(|p| p.is_dir());
    if let Some(root) = source {
        if let Ok(entries) = fs::read_dir(root) {
            for entry in entries.flatten() {
                let name = entry.file_name().to_string_lossy().into_owned();
                if name.starts_with("WifiConfigStore") && name.ends_with(".xml")
                    || name == "ipconfig.txt"
                {
                    copy_file(&entry.path(), &dest.join(name));
                }
            }
        }
    }
}

fn xml_value(block: &str, tag: &str) -> Option<String> {
    let marker = format!("name=\"{tag}\"");
    let position = block.find(&marker)? + marker.len();
    let tail = &block[position..];
    let open = tail.find('>')? + 1;
    let close = tail[open..].find('<')? + open;
    Some(decode_xml(&tail[open..close]))
}

fn decode_xml(value: &str) -> String {
    value
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
}

pub fn restore_wifi(source: &str) {
    let file = Path::new(source).join("WifiConfigStore.xml");
    let Ok(xml) = fs::read_to_string(file) else {
        return;
    };
    protocol::info("Enabling Wi-Fi for restore...");
    let _ = status("svc", ["wifi", "enable"]);
    let _ = status("cmd", ["wifi", "set-wifi-enabled", "enabled"]);
    thread::sleep(Duration::from_secs(3));
    protocol::info("Restoring networks...");

    let mut start = 0;
    let mut count = 0usize;
    let mut ssids = Vec::new();
    while let Some(relative) = xml[start..].find("<Network>") {
        let block_start = start + relative;
        let Some(end_relative) = xml[block_start..].find("</Network>") else {
            break;
        };
        let block_end = block_start + end_relative + "</Network>".len();
        let block = &xml[block_start..block_end];
        start = block_end;
        let Some(ssid) = xml_value(block, "SSID").map(|v| v.trim_matches('"').to_string()) else {
            continue;
        };
        if ssid.is_empty() {
            continue;
        }
        let psk = xml_value(block, "PreSharedKey")
            .unwrap_or_default()
            .trim_matches('"')
            .to_string();
        let key_mgmt = xml_value(block, "AllowedKeyMgmt").unwrap_or_default();
        let security = if key_mgmt.contains("02") {
            "wpa2"
        } else if key_mgmt.contains("0100") || key_mgmt.contains("0001") {
            "wpa3"
        } else {
            "open"
        };
        count += 1;
        // SSIDs and passphrases contain private network details; emit only an index.
        protocol::info(&format!("Adding network {count}"));
        if security == "open" {
            let _ = status("cmd", ["wifi", "add-network", &ssid, "open"]);
        } else {
            let _ = status("cmd", ["wifi", "add-network", &ssid, security, &psk]);
        }
        ssids.push(ssid);
    }
    if !ssids.is_empty() {
        thread::sleep(Duration::from_secs(1));
        for ssid in ssids {
            let _ = status("cmd", ["wifi", "connect-network", &ssid]);
        }
    }
    protocol::info("Wi-Fi restore done");
}

pub fn backup_wallpaper(destination: &str) {
    let dest = Path::new(destination);
    if !is_app_cache_child(dest) {
        protocol::error("Refusing unsafe wallpaper backup destination");
        return;
    }
    recreate_directory(dest);
    copy_prefix(Path::new("/data/system/users/0"), "wallpaper", dest);
}

pub fn restore_wallpaper(source: &str) {
    let from = Path::new(source);
    let to = Path::new("/data/system/users/0");
    if let Ok(entries) = fs::read_dir(from) {
        for entry in entries.flatten() {
            let name = entry.file_name().to_string_lossy().into_owned();
            let src = entry.path();
            if name.starts_with("wallpaper") && regular_no_symlink(&src) {
                copy_file(&src, &to.join(name));
            }
        }
    }
    normalize_prefix(to, "wallpaper", "system:system", "600", "system_data_file");
    let fallback = to.join("wallpaper_info.xml.fallback");
    let primary = to.join("wallpaper_info.xml");
    if fallback.is_file() && !primary.exists() {
        let _ = fs::rename(fallback, primary);
    }
}

pub fn backup_bluetooth(destination: &str) {
    let dest = Path::new(destination);
    if !is_app_cache_child(dest) {
        protocol::error("Refusing unsafe Bluetooth backup destination");
        return;
    }
    recreate_directory(dest);
    let roots = [
        "/data/misc/bluedroid/bt_config.conf",
        "/data/misc/bluetooth/bt_config.conf",
    ];
    if let Some(src) = roots.iter().map(Path::new).find(|p| p.is_file()) {
        copy_file(src, &dest.join("bt_config.conf"));
    }
}

pub fn restore_bluetooth(source: &str) {
    let src = Path::new(source).join("bt_config.conf");
    if !regular_no_symlink(&src) {
        return;
    }
    let dirs = ["/data/misc/bluedroid", "/data/misc/bluetooth"];
    let Some(dir) = dirs.iter().map(Path::new).find(|p| p.is_dir()) else {
        return;
    };
    let target = dir.join("bt_config.conf");
    protocol::info("Stopping Bluetooth...");
    let _ = status("svc", ["bluetooth", "disable"]);
    let _ = status("cmd", ["bluetooth_manager", "disable"]);
    thread::sleep(Duration::from_secs(1));
    copy_file(&src, &target);
    let _ = status(
        "chown",
        ["bluetooth:bluetooth", target.to_string_lossy().as_ref()],
    );
    let _ = status("chmod", ["660", target.to_string_lossy().as_ref()]);
    let _ = status("restorecon", [target.to_string_lossy().as_ref()]);
    thread::sleep(Duration::from_secs(1));
    protocol::info("Starting Bluetooth...");
    let _ = status("svc", ["bluetooth", "enable"]);
    let _ = status("cmd", ["bluetooth_manager", "enable"]);
}

fn regular_no_symlink(path: &Path) -> bool {
    fs::symlink_metadata(path)
        .map(|m| m.is_file() && !m.file_type().is_symlink())
        .unwrap_or(false)
}

fn copy_prefix(root: &Path, prefix: &str, destination: &Path) {
    if let Ok(entries) = fs::read_dir(root) {
        for entry in entries.flatten() {
            let name = entry.file_name().to_string_lossy().into_owned();
            if name.starts_with(prefix) {
                let _ = fs::copy(entry.path(), destination.join(name));
            }
        }
    }
}

fn normalize_prefix(root: &Path, prefix: &str, owner: &str, mode: &str, _context: &str) {
    if let Ok(entries) = fs::read_dir(root) {
        for entry in entries.flatten() {
            let path = entry.path();
            let name = entry.file_name().to_string_lossy().into_owned();
            if name.starts_with(prefix) && regular_no_symlink(&path) {
                let _ = status("chown", [owner, path.to_string_lossy().as_ref()]);
                let _ = status("chmod", [mode, path.to_string_lossy().as_ref()]);
                let _ = status("restorecon", [path.to_string_lossy().as_ref()]);
            }
        }
    }
}
