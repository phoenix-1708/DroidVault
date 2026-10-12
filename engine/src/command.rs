use std::ffi::OsStr;
use std::io;
use std::process::{Child, Command, Output, Stdio};

pub const ZAPDOS: &str = "/data/adb/DroidVault/zapdos";
pub const TEMP_DIR: &str = "/data/local/tmp/droidvault_apps";
pub const TARGETS: &str = "/data/local/tmp/droidvault_targets.txt";
pub const CANCEL_FLAG: &str = "/data/local/tmp/droidvault_cancel";
pub const BUNDLE_JOURNAL: &str = "/data/adb/DroidVault/bund.pids";

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
    std::path::Path::new(CANCEL_FLAG).exists() || !status("pidof", ["com.phoenix.droidvault"])
}
