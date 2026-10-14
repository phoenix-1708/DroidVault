use std::ffi::OsStr;
use std::io;
use std::path::Path;
use std::process::{Child, Command, Output, Stdio};
use std::sync::atomic::{AtomicU32, Ordering};

pub const ZAPDOS: &str = "/data/adb/DroidVault/zapdos";
pub const TEMP_DIR: &str = "/data/local/tmp/droidvault_apps";
pub const TARGETS: &str = "/data/local/tmp/droidvault_targets.txt";
pub const CANCEL_FLAG: &str = "/data/local/tmp/droidvault_cancel";
pub const BUNDLE_JOURNAL: &str = "/data/adb/DroidVault/bund.pids";
/// In-flight restore placements (see `restore`). One line per app whose data
/// directory this process may have replaced: which tree was moved aside, and
/// which ownership fix is still owed. Cleared by the pass that applies it.
pub const RESTORE_JOURNAL: &str = "/data/adb/DroidVault/restore-journal.txt";

pub fn register_child(pid: u32, partial_output: Option<&str>) {
    use std::io::Write;
    let output = partial_output.unwrap_or("");
    if let Ok(mut journal) = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(BUNDLE_JOURNAL)
    {
        let _ = writeln!(journal, "{pid}|{output}");
    }
}

pub fn forget_children(pids: &[u32]) {
    let Ok(contents) = std::fs::read_to_string(BUNDLE_JOURNAL) else {
        return;
    };
    let retained = contents
        .lines()
        .filter(|line| {
            let pid = line.split('|').next().unwrap_or_default();
            !pids.iter().any(|candidate| candidate.to_string() == pid)
        })
        .collect::<Vec<_>>()
        .join("\n");
    if retained.is_empty() {
        let _ = std::fs::remove_file(BUNDLE_JOURNAL);
    } else {
        // Rewrite through a temp file + rename. `--kill-jobs` runs as a
        // SEPARATE engine process and reads this journal to find in-flight
        // tar/zapdos PIDs; a plain truncating `fs::write` let it observe the
        // empty truncate window, so the orphan it exists to reap was missed.
        let temp = format!("{BUNDLE_JOURNAL}.new");
        if std::fs::write(&temp, format!("{retained}\n")).is_ok() {
            let _ = std::fs::rename(&temp, BUNDLE_JOURNAL);
        } else {
            let _ = std::fs::remove_file(&temp);
        }
    }
}

pub fn output<I, S>(program: &str, args: I) -> io::Result<Output>
where
    I: IntoIterator<Item = S>,
    S: AsRef<OsStr>,
{
    Command::new(program).args(args).output()
}

pub fn status<I, S>(program: &str, args: I) -> bool
where
    I: IntoIterator<Item = S>,
    S: AsRef<OsStr>,
{
    Command::new(program)
        .args(args)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .map(|s| s.success())
        .unwrap_or(false)
}

pub fn text<I, S>(program: &str, args: I) -> String
where
    I: IntoIterator<Item = S>,
    S: AsRef<OsStr>,
{
    output(program, args)
        .map(|o| String::from_utf8_lossy(&o.stdout).into_owned())
        .unwrap_or_default()
}

pub fn child<I, S>(program: &str, args: I) -> io::Result<Child>
where
    I: IntoIterator<Item = S>,
    S: AsRef<OsStr>,
{
    Command::new(program)
        .args(args)
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
}

pub fn shell_quote(value: &str) -> String {
    format!("'{}'", value.replace('\'', "'\\''"))
}

pub fn valid_package(package: &str) -> bool {
    !package.is_empty()
        && !package.starts_with('.')
        && !package.contains("..")
        && package.contains('.')
        && package
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'.')
}

pub fn cancelled() -> bool {
    Path::new(CANCEL_FLAG).exists() || !status("pidof", ["com.phoenix.droidvault"])
}

/// How many [`checkpoint`] calls may pass before the process question is asked
/// again.
///
/// `cancelled()` spawns `pidof` — measured at ~15 ms on the reference device —
/// so asking it once PER FILE turns a walk over a few hundred entries into a few
/// hundred process spawns. That is what made a "quick" vault check of six files
/// take 120 ms, with over 100 ms of it in `pidof`; a real vault makes it seconds
/// of pure overhead. The flag file is still consulted on every call, so an
/// interactive cancel is immediate; only "the owning app is gone" is sampled.
const CANCEL_EVERY: u32 = 128;

static CHECKPOINTS: AtomicU32 = AtomicU32::new(0);

/// Cancellation check for a tight loop: cheap on every call, thorough
/// occasionally.
///
/// The first call always asks the process question, so a loop that runs a
/// handful of times (the common case for a small vault) behaves exactly like
/// `cancelled()`.
pub fn checkpoint() -> bool {
    if Path::new(CANCEL_FLAG).exists() {
        return true;
    }
    if CHECKPOINTS
        .fetch_add(1, Ordering::Relaxed)
        .is_multiple_of(CANCEL_EVERY)
    {
        return !status("pidof", ["com.phoenix.droidvault"]);
    }
    false
}
