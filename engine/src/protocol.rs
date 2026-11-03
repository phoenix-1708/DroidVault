use std::collections::HashMap;
use std::io::{self, Write};
use std::sync::{Mutex, OnceLock};
use std::time::Instant;

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

/// When a stage started, so a run can say afterwards where its seconds went.
///
/// A run used to report progress and nothing at all about elapsed time, so a
/// twelve-minute restore and a three-minute one left the same record — every
/// performance question about this engine had to be answered by reading code
/// instead of the log. These lines are measurements only: the app never parses
/// `NOTE:` into behaviour, so a missing or malformed one cannot change what a
/// run does, which is what makes it safe to measure the same code paths that
/// decide what runs.
///
/// Keyed by (package, stage). The engine drives one app at a time from one
/// thread, so the map needs to survive calls rather than races; the Mutex is
/// there because a `OnceLock` needs interior mutability, and a poisoned lock
/// drops the measurement rather than the run.
fn starts() -> &'static Mutex<HashMap<(String, String), Instant>> {
    static STARTS: OnceLock<Mutex<HashMap<(String, String), Instant>>> = OnceLock::new();
    STARTS.get_or_init(|| Mutex::new(HashMap::new()))
}

/// The stage `app_start` opened and `done_pkg` closes — one per app.
const APP_STAGE: &str = "\u{0}app";
/// Pending until the app's first part or install begins, which is the only
/// honest place to stop charging time to setup.
const SETUP_STAGE: &str = "\u{0}setup";
/// Opened by the `INSTALLING` step, which is a restore leg with no part events.
const INSTALL_STAGE: &str = "Install";

fn started(pkg: &str, stage: &str) {
    if let Ok(mut map) = starts().lock() {
        map.insert((pkg.to_string(), stage.to_string()), Instant::now());
    }
}

fn clear_pkg(pkg: &str) {
    if let Ok(mut map) = starts().lock() {
        map.retain(|(owner, _), _| owner != pkg);
    }
}

fn elapsed_ms(pkg: &str, stage: &str) -> Option<u128> {
    let mut map = starts().lock().ok()?;
    map.remove(&(pkg.to_string(), stage.to_string()))
        .map(|at| at.elapsed().as_millis())
}

/// One stage's elapsed time. Split out so its shape is testable without a run.
pub fn timing_line(pkg: &str, stage: &str, state: u8, ms: u128) -> String {
    format!(
        "NOTE:TIMING|PKG:{}|STAGE:{}|STATE:{}|MS:{}",
        field(pkg),
        field(stage),
        state,
        ms
    )
}

fn emit_timing(pkg: &str, stage: &str, state: u8) {
    if let Some(ms) = elapsed_ms(pkg, stage) {
        raw(&timing_line(pkg, stage, state, ms));
    }
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
    // Timing hangs off the part events themselves rather than off the backup and
    // restore loops, because those loops emit parts from a dozen places: a hook
    // at each would be a dozen chances to measure twelve of thirteen legs.
    if state == 1 {
        // Whichever of install-or-first-part arrives first ends the setup
        // charge, and only the first one may: a later part is not setup.
        if let Some(ms) = elapsed_ms(pkg, SETUP_STAGE) {
            raw(&timing_line(pkg, "SETUP", 2, ms));
        }
        started(pkg, name);
    } else {
        emit_timing(pkg, name, state);
    }
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
    // A package name is unique inside a vault, but one engine process can run a
    // restore and then a verification: a start left behind by an abandoned app
    // would measure the next run's first part from the previous run's clock.
    clear_pkg(pkg);
    started(pkg, APP_STAGE);
    started(pkg, SETUP_STAGE);
}

pub fn step_for_pkg(message: &str, pkg: &str, label: &str) {
    println!(
        "INFO:STEP|MSG:{}|PKG:{}|LABEL:{}|JOBS:1",
        field(message),
        field(pkg),
        field(label)
    );
    let _ = io::stdout().flush();
    // Install is a leg in its own right on a restore, and it is the leg that
    // decides whether a restore feels slow: it unpacks the APK, stages it, and
    // waits on the package manager. It emits no part events, so without a start
    // here its seconds would land in the app's total unattributed.
    if message == "INSTALLING" {
        started(pkg, INSTALL_STAGE);
    }
}

pub fn done_pkg(restore: bool, pkg: &str) {
    println!(
        "ACTION:{}_DONE|PKG:{}",
        if restore { "RESTORE" } else { "BACKUP" },
        field(pkg)
    );
    let _ = io::stdout().flush();
    emit_timing(pkg, APP_STAGE, 2);
    // A part that started and never reported a terminal state (an error path, a
    // skipped leg) must not leave its start behind: the next app in the vault
    // could share the package name, and its first part would then be measured
    // from the previous app's clock.
    clear_pkg(pkg);
}

pub fn install_done(pkg: &str) {
    println!("ACTION:INSTALL_DONE|PKG:{}", field(pkg));
    let _ = io::stdout().flush();
    emit_timing(pkg, INSTALL_STAGE, 2);
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

    #[test]
    fn a_timing_line_carries_its_stage_and_survives_a_hostile_package_name() {
        // These lines are read by a human and by nothing else, so the risk is not
        // a crash: it is a line that cannot be attributed. A package name out of
        // the vault splits the line and the measurement lands in the log naming
        // a stage nobody can find.
        let line = timing_line("com.example|STAGE:Bogus", "Data", 2, 1234);
        assert_eq!(
            line,
            "NOTE:TIMING|PKG:com.example/STAGE:Bogus|STAGE:Data|STATE:2|MS:1234"
        );
        let keys: Vec<&str> = line
            .split('|')
            .filter_map(|seg| seg.split_once(':').map(|(k, _)| k))
            .collect();
        // `NOTE:TIMING` is the line's own header, so it contributes one key.
        assert_eq!(keys, vec!["NOTE", "PKG", "STAGE", "STATE", "MS"]);
    }

    #[test]
    fn a_stage_is_measured_once_and_reports_its_own_elapsed_time() {
        // The measure hangs off part events, and a part can be reported more than
        // once (three data roots). A stage that kept its start would report the
        // second root's duration as the whole part's, which is the shape of
        // arithmetic this project has already shipped once (§4.30).
        started("com.example.measure", "Data");
        let first = elapsed_ms("com.example.measure", "Data");
        assert!(first.is_some(), "a started stage must be measurable");
        assert!(
            elapsed_ms("com.example.measure", "Data").is_none(),
            "the second terminal event must not re-report the first clock"
        );
    }
}
