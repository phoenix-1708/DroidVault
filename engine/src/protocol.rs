use std::io::{self, Write};

/// Make a value safe to put in a `KEY:value` protocol line.
///
/// Public because the sanitiser only works if every emitter uses it: a line
/// whose value came from the vault unescaped can carry a `|` or a newline into
/// the app's parser, and a vault is reachable from an imported
/// `.dvbackup.tar.gz`. A caller that formats its own line and forgets this is
/// the same class of bug as a reader and a writer naming a field differently
/// (§4.29) — the wire format is an interface, not a detail.
pub fn field(value: &str) -> String {
    value
        .replace('|', "/")
        .replace('\n', " ")
        .replace('\r', " ")
}

pub fn info(message: &str) {
    println!("INFO:STEP|MSG:{}", field(message));
    let _ = io::stdout().flush();
}

pub fn error(message: &str) {
    println!("ACTION:ERROR|MSG:{}", field(message));
    let _ = io::stdout().flush();
}

pub fn raw(message: &str) {
    println!("{message}");
    let _ = io::stdout().flush();
}

pub fn part(pkg: &str, name: &str, state: u8, reason: &str) {
    println!(
        "ACTION:PART|PKG:{}|PART:{}|STATE:{}|REASON:{}",
        field(pkg),
        field(name),
        state,
        field(reason)
    );
    let _ = io::stdout().flush();
}

pub fn app_start(
    restore: bool,
    pkg: &str,
    label: &str,
    version: &str,
    current: usize,
    total: usize,
    percent: u64,
    size: u64,
) {
    println!(
        "ACTION:{}_START|PKG:{}|LABEL:{}|VER:{}|CUR:{}|TOT:{}|PCT:{}|SIZE:{}|JOBS:1",
        if restore { "RESTORE" } else { "BACKUP" },
        field(pkg),
        field(label),
        field(version),
        current,
        total,
        percent.min(100),
        size
    );
    let _ = io::stdout().flush();
}

pub fn step_for_pkg(message: &str, pkg: &str, label: &str) {
    println!(
        "INFO:STEP|MSG:{}|PKG:{}|LABEL:{}|JOBS:1",
        field(message),
        field(pkg),
        field(label)
    );
    let _ = io::stdout().flush();
}

pub fn done_pkg(restore: bool, pkg: &str) {
    println!(
        "ACTION:{}_DONE|PKG:{}",
        if restore { "RESTORE" } else { "BACKUP" },
        field(pkg)
    );
    let _ = io::stdout().flush();
}

pub fn install_done(pkg: &str) {
    println!("ACTION:INSTALL_DONE|PKG:{}", field(pkg));
    let _ = io::stdout().flush();
}

pub fn install_error(pkg: &str, message: &str) {
    println!(
        "ACTION:INSTALL_ERROR|PKG:{}|MSG:{}",
        field(pkg),
        field(message)
    );
    let _ = io::stdout().flush();
}

pub fn global_done(total_kb: u64, seconds: u64) {
    println!("ACTION:GLOBAL_DONE|TOTAL:{}|TIME:{}", total_kb, seconds);
    let _ = io::stdout().flush();
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_vault_supplied_value_cannot_forge_a_field_or_split_a_line() {
        // `field()` is the only thing standing between a crafted vault and the
        // app's parser, and a vault is reachable from an imported
        // `.dvbackup.tar.gz`. LABEL, PKG, CORRUPT and the device item's NAME all
        // come out of the vault; two of them were emitted raw, so this pins the
        // rule every emitter now shares.
        assert_eq!(field("com.example|STATUS:OK"), "com.example/STATUS:OK");
        assert_eq!(field("Two\nLines"), "Two Lines");
        assert_eq!(field("Two\rLines"), "Two Lines");
        // An ordinary label is untouched: sanitising must not mangle real data.
        assert_eq!(field("Node.js"), "Node.js");
        assert_eq!(field("Some App"), "Some App");
    }

    #[test]
    fn a_separator_in_a_value_cannot_invent_a_field_the_real_line_does_not_have() {
        // The concrete attack: a label carrying `|` would otherwise append a key
        // to the line, and a key the app reads (`STATUS`, `CORRUPT`) would be one
        // it believes.
        let hostile = "Evil|CORRUPT:9";
        let line = format!("VERIFY_APP|LABEL:{}|STATUS:OK", field(hostile));
        let keys: Vec<&str> = line
            .split('|')
            .filter_map(|seg| seg.split_once(':').map(|(k, _)| k))
            .collect();
        assert_eq!(
            keys,
            vec!["LABEL", "STATUS"],
            "the forged CORRUPT key must not reach the app, and the separator must \
             not have split the line either"
        );
        assert!(
            line.contains("Evil/CORRUPT:9"),
            "the label itself is preserved, just with the separator neutralised"
        );
    }
}
