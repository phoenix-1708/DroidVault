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

/// The `cmd wifi add-network` security for one `AllowedKeyMgmt` value, or `None`
/// when the value cannot be read and the network must NOT be guessed at.
///
/// `WifiConfigStore.xml` does not store a plain integer. AOSP's
/// `WifiConfigStoreUtils.writeBitSet` encodes the `KeyMgmt` bitmask as a hex
/// string — one character per four bits, most-significant nibble first — and
/// base64s the result. So a WPA2-PSK network (bit 1, value 2) is stored as the
/// single character `2`, WPA3-SAE (bit 3, value 8) as `8`, and OWE as `800`.
///
/// The code this replaces substring-matched `"02"` and `"0100"` against that
/// string, which matched none of them. The consequence was not a mislabel: an
/// unrecognised value fell through to `"open"`, and `cmd wifi add-network <ssid>
/// open` then added the user's secured network as an unprotected one under the
/// same SSID. Every WPA2 and WPA3 network on the device was a candidate for that.
///
/// Decoding is by hex position, so it is total over the real encoding. Anything
/// that is not hex, or carries bits outside the ones defined here, is `None`.
pub fn classify_key_mgmt(value: &str) -> Option<&'static str> {
    let raw = value.trim();
    // base64 of a hex string: the platform writes it, but a hand-edited or
    // older file can hold the hex directly.
    let hex = decode_base64_if_needed(raw).unwrap_or_else(|| raw.to_string());
    if hex.is_empty() {
        // No key management recorded. An empty bitmask is AOSP's OWNER_ABUNDONED
        // / "nothing configured", which must never become an open network.
        return None;
    }
    if !hex.bytes().all(|b| b.is_ascii_hexdigit()) {
        return None;
    }
    // A real `KeyMgmt` bitmask fits in four nibbles. A longer one is not a
    // bitmask, and indexing it into a u32 shifts past the width — a panic in the
    // middle of a Wi-Fi restore. Refuse it rather than wrap it.
    if hex.len() > 8 {
        return None;
    }
    // Character i covers bits (n-1-i)*4 ..= (n-i)*4, most significant nibble first.
    let n = hex.len();
    let mut mask: u32 = 0;
    for (i, c) in hex.chars().enumerate() {
        let v = c.to_digit(16).unwrap_or(0);
        mask |= v << ((n - 1 - i) * 4);
    }

    // WifiConfiguration.KeyMgmt bit positions.
    const PSK: u32 = 1 << 1; // 2   WPA-PSK / WPA2-Personal
    const EAP: u32 = 1 << 2; // 4   WPA-EAP
    const SAE: u32 = 1 << 3; // 8   WPA3-Personal
    const OWE: u32 = 1 << 11; // 2048 Enhanced Open (encrypted, no PSK)
    const FT_PSK: u32 = 1 << 4;
    const SAE_TCP: u32 = 1 << 13;
    const DPP: u32 = 1 << 14;

    let has = |m: u32| mask & m != 0;
    let secured = has(PSK)
        || has(EAP)
        || has(SAE)
        || has(FT_PSK)
        || has(SAE_TCP)
        || has(DPP)
        || has(PASSPOINT_R1)
        || has(OSEN);
    let known = secured || has(OWE);
    if !known {
        return None;
    }
    // WPA3 wins when the network offers it, so a transition-mode network is
    // restored at the strength it was saved at.
    if has(SAE) || has(SAE_TCP) || has(DPP) {
        Some("wpa3")
    } else if has(PSK) || has(FT_PSK) {
        Some("wpa2")
    } else if has(OWE) {
        // Enhanced Open: encrypted, but not with a passphrase. `cmd wifi` has no
        // mode for it, so it cannot be restored safely — say so rather than
        // adding it as open.
        None
    } else {
        Some("eap")
    }
}

const OSEN: u32 = 1 << 6; // 64
const PASSPOINT_R1: u32 = 1 << 16; // 65536

/// A value safe to log: it is a configuration field, not a secret, but it is
/// still vault-supplied text so it is truncated and stripped of control chars.
fn describe_key_mgmt(value: &str) -> String {
    let clean: String = value
        .trim()
        .chars()
        .filter(|c| !c.is_control())
        .take(24)
        .collect();
    if clean.is_empty() {
        "empty".to_string()
    } else {
        clean
    }
}

/// The hex behind AOSP's base64-of-hex `BitSet` encoding, or None if this is not
/// base64. Kept small and strict: anything that does not decode to hex-only text
/// is left to the caller's own hex path.
fn decode_base64_if_needed(value: &str) -> Option<String> {
    if value.is_empty()
        || !value
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"+/=".contains(&b))
    {
        return None;
    }
    const TABLE: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let bytes = value.as_bytes();
    let mut out = Vec::new();
    let mut acc: u32 = 0;
    let mut bits = 0u32;
    for b in bytes {
        if *b == b'=' {
            break;
        }
        let idx = TABLE.iter().position(|t| *t == *b)? as u32;
        acc = (acc << 6) | idx;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push(((acc >> bits) & 0xFF) as u8);
        }
    }
    let text = String::from_utf8(out).ok()?;
    if text.is_empty() {
        return None;
    }
    // Only accept it if it really is hex — otherwise it was not this encoding.
    if text.bytes().all(|b| b.is_ascii_hexdigit()) {
        Some(text)
    } else {
        None
    }
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
        let security = classify_key_mgmt(&key_mgmt);
        count += 1;
        // SSIDs and passphrases contain private network details; emit only an index.
        protocol::info(&format!("Adding network {count}"));
        match security {
            Some(sec) if sec == "open" => {
                let _ = status("cmd", ["wifi", "add-network", &ssid, "open"]);
            }
            Some(sec) => {
                let _ = status("cmd", ["wifi", "add-network", &ssid, sec, &psk]);
            }
            // Fail CLOSED. This used to fall through to `open`, which meant a
            // network whose key management this code could not read was added as
            // an OPEN network with the user's own SSID: the device would then
            // prefer it, and the home network's protection was quietly gone. A
            // network we cannot classify is not a network to guess about — it is
            // a reason to skip it and say so.
            None => {
                let detail = describe_key_mgmt(&key_mgmt);
                protocol::info(&format!(
                    "Skipping network {count}: key management not understood ({detail}), \
                     not adding it as open"
                ));
                continue;
            }
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

#[cfg(test)]
mod wifi_security_tests {
    use super::{classify_key_mgmt, describe_key_mgmt};

    /// The encoding AOSP's `WifiConfigStoreUtils.writeBitSet` produces: a hex
    /// string, one character per four bits, MSB first, base64'd.
    fn encode(bits: u32) -> String {
        let n = ((u32::BITS as usize - bits.leading_zeros() as usize + 3) / 4).max(1);
        let hex: String = (0..n)
            .rev()
            .map(|i| std::char::from_digit((bits >> (i * 4)) & 0xF, 16).unwrap())
            .collect();
        base64(hex.as_bytes())
    }

    fn base64(input: &[u8]) -> String {
        const T: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        let mut out = String::new();
        for chunk in input.chunks(3) {
            let b = [
                chunk[0],
                *chunk.get(1).unwrap_or(&0),
                *chunk.get(2).unwrap_or(&0),
            ];
            let n = ((b[0] as u32) << 16) | ((b[1] as u32) << 8) | b[2] as u32;
            out.push(T[((n >> 18) & 63) as usize] as char);
            out.push(T[((n >> 12) & 63) as usize] as char);
            out.push(if chunk.len() > 1 {
                T[((n >> 6) & 63) as usize] as char
            } else {
                '='
            });
            out.push(if chunk.len() > 2 {
                T[(n & 63) as usize] as char
            } else {
                '='
            });
        }
        out
    }

    // WifiConfiguration.KeyMgmt bits.
    const PSK: u32 = 1 << 1;
    const EAP: u32 = 1 << 2;
    const SAE: u32 = 1 << 3;
    const FT_PSK: u32 = 1 << 4;
    const OSEN: u32 = 1 << 6;
    const OWE: u32 = 1 << 11;
    const SAE_TCP: u32 = 1 << 13;
    const DPP: u32 = 1 << 14;
    const PASSPOINT_R1: u32 = 1 << 16;

    #[test]
    fn a_wpa2_home_network_is_restored_as_wpa2_and_never_as_open() {
        // THE finding. The old code substring-matched "02" against AOSP's
        // base64-of-hex bitmask, which never contains it, so a WPA2 network fell
        // through to "open" and was added to the device as an UNPROTECTED
        // network under the user's own SSID.
        assert_eq!(
            classify_key_mgmt(&encode(PSK)),
            Some("wpa2"),
            "the most common network on any home device must come back secured"
        );
    }

    #[test]
    fn every_secured_key_management_is_restored_secured() {
        for (name, bits) in [
            ("WPA2-PSK", PSK),
            ("WPA2-EAP", EAP),
            ("WPA3-SAE", SAE),
            ("FT-PSK", FT_PSK),
            ("SAE over TCP", SAE_TCP),
            ("DPP", DPP),
            ("Passpoint R1", PASSPOINT_R1),
            ("OSEN", OSEN),
        ] {
            assert_ne!(
                classify_key_mgmt(&encode(bits)),
                Some("open"),
                "{name} must never be restored as an open network"
            );
        }
    }

    /// A `KeyMgmt` bit this build cannot translate, so it cannot be restored
    /// faithfully. Every one of these is a SECURED network type — which is why
    /// the answer must be "skip", never "add with whatever credential type we
    /// happened to pick".
    ///
    /// Bits from AOSP `WifiConfiguration.KeyMgmt`: WEP=1<<0, FT_EAP=1<<5,
    /// PPK=1<<7, SAE_EXTEND_KEY=1<<8, FILS_SHA256=1<<9, FILS_SHA1=1<<10,
    /// OWE_TCP=1<<12, EAP_SUITE_B_192=1<<15, PASSPOINT_R2=1<<17.
    #[test]
    fn a_secured_network_this_build_cannot_translate_is_skipped_not_guessed() {
        for (name, bits) in [
            ("WEP", 1u32 << 0),
            ("FT-EAP", 1 << 5),
            ("PPK", 1 << 7),
            ("SAE extended key", 1 << 8),
            ("FILS SHA256", 1 << 9),
            ("FILS SHA1", 1 << 10),
            ("OWE over TCP", 1 << 12),
            ("EAP Suite-B 192", 1 << 15),
            ("Passpoint R2", 1 << 17),
        ] {
            assert_eq!(
                classify_key_mgmt(&encode(bits)),
                None,
                "{name} is a secured network this build cannot express; adding it \
                 under some other credential type is the same guess as adding it open"
            );
        }
    }

    #[test]
    fn nothing_that_cannot_be_read_becomes_an_open_network() {
        // Fail closed. Every one of these used to reach `cmd wifi add-network
        // <ssid> open`.
        for junk in [
            "",
            "   ",
            "\n",
            "not base64 at all",
            "%%%%",
            "zz",
            "!!!",
            "0x02",
            "@@@@",
            "AAAA====",
            "0000000000000000000000000000000",
        ] {
            // Asserted as None, not merely "not open". Anything other than None
            // reaches `cmd wifi add-network` and attaches a credential type this
            // network never had; only None means the caller skips it. Asserting
            // `!= Some("open")` here is what let a mutation that guessed "eap"
            // pass as correct.
            assert_eq!(
                classify_key_mgmt(junk),
                None,
                "unreadable key management {junk:?} must be SKIPPED, not restored \
                 under any credential type"
            );
        }
    }

    #[test]
    fn wpa3_is_kept_when_the_network_offers_it() {
        // Transition mode saves PSK|SAE; restoring it at WPA2 would silently
        // drop WPA3 the user had.
        assert_eq!(classify_key_mgmt(&encode(PSK | SAE)), Some("wpa3"));
        assert_eq!(classify_key_mgmt(&encode(PSK)), Some("wpa2"));
    }

    #[test]
    fn enhanced_open_is_not_silently_recreated_as_open() {
        // OWE is encrypted with no passphrase. `cmd wifi` cannot express it, so
        // the only safe answer is to skip it — adding it as open would undo the
        // encryption the user's network actually has.
        assert_eq!(
            classify_key_mgmt(&encode(OWE)),
            None,
            "an Enhanced Open network must be skipped, not added as open"
        );
    }

    #[test]
    fn hex_is_accepted_as_well_as_the_base64_the_platform_writes() {
        // A hand-edited or older file may hold the hex directly.
        assert_eq!(classify_key_mgmt("2"), Some("wpa2"));
        assert_eq!(classify_key_mgmt("8"), Some("wpa3"));
        assert_eq!(classify_key_mgmt("a"), Some("wpa3"));
    }

    #[test]
    fn a_logged_value_cannot_carry_control_characters() {
        // The value is vault-supplied text and ends up in a log line.
        for junk in ["\u{1b}[31mred", "a\nb", "x\ty", "\u{0}"] {
            let d = describe_key_mgmt(junk);
            assert!(
                !d.chars().any(|c| c.is_control()),
                "a control character reached a log line: {d:?}"
            );
            assert!(
                d.len() <= 24,
                "an unbounded value reached a log line: {d:?}"
            );
        }
    }
}
