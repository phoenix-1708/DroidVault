//! Making an app stop writing to its data directory before that directory is
//! read (backup) or replaced (restore).
//!
//! `--backup` used to tar `/data/data/<pkg>` while the app was free to write
//! into it: a SQLite WAL mid-checkpoint, a half-renamed `shared_prefs` entry, an
//! in-flight temp file. The archive stayed *syntactically* valid — which is all
//! `archive_valid` can see — and was semantically torn, and nothing said so
//! until restore time, when the data it replaced was already gone.
//!
//! Restore had the mirror-image problem with only half a fix: a freshly
//! installed app was disabled before `archive::unpack` wrote into its tree, but
//! the common case — restoring over an app that is ALREADY installed — left the
//! app running while its data directory was replaced underneath it.
//!
//! Both need the same primitive: stop the app, or say honestly that it could
//! not be stopped.

use crate::command;
use crate::protocol;
use std::collections::BTreeSet;

/// Packages that are never stopped, whatever the run wants.
///
/// These are not apps in the sense the rest of the engine means. Stopping
/// SystemUI restarts the shell, stopping the telephony stack drops the radio,
/// and stopping ourselves kills the run that is doing the stopping. What is
/// skipped is reported (`Consistent=0`), never hidden.
pub const NEVER_STOP: [&str; 4] = [
    "android",
    "com.android.systemui",
    "com.android.phone",
    "com.phoenix.droidvault",
];

/// What happened when a package was asked to stop.
#[derive(Debug, PartialEq, Eq, Clone, Copy)]
pub enum Quiesce {
    /// Not running, so nothing had to be stopped and nothing was at risk.
    NotRunning,
    /// Was running and was stopped.
    Stopped,
    /// Was running and could not be stopped (`am` refused).
    Refused,
    /// Was running and is on [`NEVER_STOP`].
    Protected,
}

impl Quiesce {
    /// True when nothing can have been writing into the directory.
    pub fn quiet(self) -> bool {
        !matches!(self, Quiesce::Refused | Quiesce::Protected)
    }

    /// The value recorded in `Meta.txt`.
    ///
    /// Written only when a read actually happened, so an archive from an older
    /// build keeps saying nothing rather than claiming a consistency nobody
    /// checked.
    pub fn meta_value(self) -> &'static str {
        if self.quiet() { "1" } else { "0" }
    }

    pub fn label(self) -> &'static str {
        match self {
            Quiesce::NotRunning => "not-running",
            Quiesce::Stopped => "stopped",
            Quiesce::Refused => "refused",
            Quiesce::Protected => "protected",
        }
    }
}

/// What should be done about a package, given what is running right now.
#[derive(Debug, PartialEq, Eq)]
pub enum Plan {
    /// Leave it alone, with the outcome to record.
    Skip(Quiesce),
    /// Running and stoppable: force-stop it.
    Stop,
}

/// Pure decision, so "who is never stopped" and "was it even running" are pinned
/// by tests rather than by reading every call site.
///
/// The protected list is checked FIRST: a package that is somehow in both sets
/// must never reach `Plan::Stop`.
pub fn plan(package: &str, running: &BTreeSet<String>) -> Plan {
    if NEVER_STOP.contains(&package) {
        Plan::Skip(Quiesce::Protected)
    } else if running.contains(package) {
        Plan::Stop
    } else {
        Plan::Skip(Quiesce::NotRunning)
    }
}

/// Base package name of every running process, from ONE `ps`.
///
/// A `pidof` per app would be a fork per app — measured at ~22 ms on the
/// reference device, so ~1.4 s across a 63-app run — and toybox's `pidof` matches
/// the short `comm` name, which the kernel truncates to 15 characters; for
/// `com.google.android.apps.messaging` that is not a name anything can match.
/// `ps -A -o NAME` prints the full command name for the whole device in one fork
/// (~20 ms measured, 830 processes), including `:private` variants such as
/// `com.google.android.apps.messaging:rcs`, and the set is then tested in-process.
pub fn running_packages() -> BTreeSet<String> {
    parse_running(&command::text("ps", ["-A", "-o", "NAME"]))
}

/// Pure half of [`running_packages`].
///
/// `com.foo:remote` means the base package `com.foo` is running, and that is the
/// name a caller has. Kernel threads (`[kthreadd]`), the `NAME` header and bare
/// process names are dropped by the package-shape filter.
pub fn parse_running(output: &str) -> BTreeSet<String> {
    output
        .lines()
        .map(str::trim)
        .map(|line| line.split(':').next().unwrap_or(line))
        .filter(|name| command::valid_package(name))
        .map(str::to_string)
        .collect()
}

/// Stop `package` so whatever is read next cannot be racing a live write.
pub fn stop(package: &str, running: &BTreeSet<String>) -> Quiesce {
    match plan(package, running) {
        Plan::Skip(outcome) => outcome,
        Plan::Stop => {
            if command::status("am", ["force-stop", package]) {
                Quiesce::Stopped
            } else {
                Quiesce::Refused
            }
        }
    }
}

/// Stop `package` at most once per app, and report what happened.
///
/// `slot` is the caller's record for this app. The FIRST read wins: a later part
/// of the same app must not overwrite a refusal with a fresh "not running" and
/// turn an unquiet snapshot into a clean-looking one.
pub fn ensure_quiet(
    package: &str,
    running: &BTreeSet<String>,
    slot: &mut Option<Quiesce>,
) -> Quiesce {
    if let Some(existing) = *slot {
        return existing;
    }
    let outcome = stop(package, running);
    // A quiet app says nothing: one line per app per run would be noise on a
    // 63-app backup, and the common case is "was not running anyway".
    //
    // A bare line rather than `INFO:STEP`: the protocol's `MSG:` field is
    // rendered as the live progress caption, so announcing housekeeping through
    // it would rewrite the user's step text mid-run. `EngineEvent.RawLog` reaches
    // the diagnostics log and is ignored by the progress tracker.
    if outcome != Quiesce::NotRunning {
        protocol::raw(&format!("NOTE:QUIESCE|{package}|{}", outcome.label()));
    }
    *slot = Some(outcome);
    outcome
}

#[cfg(test)]
mod tests {
    use super::*;

    fn running(names: &[&str]) -> BTreeSet<String> {
        names.iter().map(|name| name.to_string()).collect()
    }

    #[test]
    fn ps_parse_keeps_packages_and_drops_everything_else() {
        let set = parse_running(
            "NAME\ninit\n[kthreadd]\ncom.foo\ncom.google.android.apps.messaging:rcs\n\
             com.phoenix.droidvault\n\nNAME\n",
        );
        assert!(set.contains("com.foo"));
        assert!(set.contains("com.google.android.apps.messaging"));
        assert!(set.contains("com.phoenix.droidvault"));
        assert!(!set.contains("init"), "bare process name is not a package");
        assert!(
            !set.contains("[kthreadd]"),
            "kernel thread is not a package"
        );
        assert!(!set.contains("NAME"), "the ps header is not a package");
        assert_eq!(set.len(), 3);
    }

    #[test]
    fn a_private_process_variant_means_the_base_package_is_running() {
        let set = parse_running("NAME\ncom.foo:remote\n");
        assert_eq!(plan("com.foo", &set), Plan::Stop);
    }

    #[test]
    fn an_idle_app_is_left_alone() {
        assert_eq!(
            plan("com.foo", &running(&["com.bar"])),
            Plan::Skip(Quiesce::NotRunning)
        );
    }

    #[test]
    fn a_running_app_is_stopped() {
        assert_eq!(
            plan("com.foo", &running(&["com.foo", "com.bar"])),
            Plan::Stop
        );
    }

    /// SystemUI, the telephony stack, the framework and this app itself must
    /// never be stopped — even if the process list says they are running.
    #[test]
    fn the_never_stop_list_wins() {
        assert_eq!(NEVER_STOP.len(), 4);
        for package in NEVER_STOP {
            assert_eq!(
                plan(package, &running(&[package])),
                Plan::Skip(Quiesce::Protected),
                "{package} must never be stopped"
            );
        }
    }

    #[test]
    fn only_an_observed_quiet_state_is_recorded_as_consistent() {
        assert_eq!(Quiesce::NotRunning.meta_value(), "1");
        assert_eq!(Quiesce::Stopped.meta_value(), "1");
        assert_eq!(Quiesce::Refused.meta_value(), "0");
        assert_eq!(Quiesce::Protected.meta_value(), "0");
        assert!(Quiesce::Stopped.quiet());
        assert!(!Quiesce::Refused.quiet());
        assert!(!Quiesce::Protected.quiet());
    }

    #[test]
    fn the_first_read_decides_and_a_later_part_cannot_clean_it_up() {
        // A refusal recorded by the Data part must survive the Media part
        // finding the app stopped or absent: the snapshot is still unquiet.
        let mut slot = Some(Quiesce::Refused);
        let outcome = ensure_quiet("com.foo", &running(&[]), &mut slot);
        assert_eq!(outcome, Quiesce::Refused);
        assert_eq!(slot, Some(Quiesce::Refused));
    }
}
