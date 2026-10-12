//! Cross-device transfer engine: pack and unpack `.dvbackup.tar.gz` files.
//!
//! Why this lives here instead of in Kotlin shell strings: the transfer used to
//! be a sequence of `tar`/`cp`/`du` commands issued from Kotlin through libsu,
//! with progress polled from a second thread. Every long step had the same
//! failure modes — a probe that queued behind the work it measured, a timeout
//! standing in for a completion check, and cleanup racing live work. Doing it
//! in-process gives three things the shell version could not have:
//!
//! - **Real progress.** The process sees the bytes as they land, so extraction
//!   reports measured percentages instead of an indeterminate bar.
//! - **Direct cancellation.** The child `tar` handle is owned here, so cancel
//!   kills exactly that process — no `pkill -f` patterns that can match the
//!   wrong thing (or the control shell itself).
//! - **Renames, not copies.** Placing apps into the vault is `rename(2)`, which
//!   is atomic on one filesystem and measured at 44 ms for 400 MB on device,
//!   versus a full read+write for `cp -r`.
//!
//! No third-party crates: the transfer format is plain gzip tar handled by the
//! platform `tar`, which is already relied on everywhere else here.
//! This module only drives it, measures it, and moves the results.

use crate::{command, protocol};
use std::fs;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::time::Duration;

/// Progress emissions never claim 100 until the work is confirmed finished.
/// A bar at 100 while the command still runs is how the old export reported
/// "34.2 GB of 34.2 GB" and then sat motionless for minutes.
const MAX_RUNNING_PCT: u64 = 99;

/// How often the monitor thread reports while the child works.
const EXPORT_TICK: Duration = Duration::from_millis(400);
const EXTRACT_TICK: Duration = Duration::from_millis(1000);

/// Elapsed ceiling for the survey, the one transfer step with no byte counter.
const SURVEY_CEILING: Duration = Duration::from_secs(6 * 60 * 60);

/// Reject anything that is not a plain vault-relative name.
///
/// Kept in sync with the Kotlin-side label rules: no separators, no parent
/// references, no control characters, no protocol delimiters. An entry that
/// fails this never reaches a command line.
pub(crate) fn valid_entry(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 512
        && name != "."
        && name != ".."
        && !name.contains('/')
        && !name.contains('|')
        && !name.chars().any(char::is_control)
}

/// Absolute paths only, with no traversal and no protocol metacharacters.
/// Mirrors the vault-path gate used by the app backup path.
pub(crate) fn valid_abs(path: &str) -> bool {
    if !path.starts_with('/') || path.contains('\n') || path.contains('\r') || path.contains('|') {
        return false;
    }
    let normalized = path.trim_end_matches('/');
    if normalized.is_empty() {
        return false;
    }
    !normalized
        .split('/')
        .any(|segment| segment == ".." || segment == ".")
}

fn clamp_pct(done: u64, total: u64) -> u64 {
    if total == 0 || done == 0 {
        return 0;
    }
    (done.saturating_mul(100) / total).min(MAX_RUNNING_PCT)
}

fn progress(op: &str, done: u64, total: u64) {
    println!(
        "ACTION:TRANSFER|OP:{}|DONE:{}|TOTAL:{}|PCT:{}",
        op,
        done,
        total,
        clamp_pct(done, total)
    );
    use std::io::Write;
    let _ = std::io::stdout().flush();
}

/// Recursive byte size of a directory tree. Symlinks are not followed: a
/// symlink loop must never hang the progress monitor, and a link's own bytes
/// are not the work being measured.
fn dir_size(root: &Path) -> u64 {
    fn walk(path: &Path, total: &mut u64) {
        let Ok(meta) = fs::symlink_metadata(path) else {
            return;
        };
        if meta.file_type().is_symlink() {
            return;
        }
        if meta.is_file() {
            *total = total.saturating_add(meta.len());
            return;
        }
        if !meta.is_dir() {
            return;
        }
        let Ok(entries) = fs::read_dir(path) else {
            return;
        };
        for entry in entries.flatten() {
            walk(&entry.path(), total);
        }
    }
    let mut total = 0u64;
    walk(root, &mut total);
    total
}

fn kill_child(child: &mut Child) {
    let _ = child.kill();
    let _ = child.wait();
}

fn kill_pair(first: &mut Child, second: &mut Child) {
    kill_child(first);
    kill_child(second);
}

/// How long the work may produce no new bytes before it counts as hung.
///
/// This is a bound on PROGRESS, not on elapsed time, and that distinction is
/// the whole point. The app used to cap every transfer step at a flat 60
/// minutes, which is shorter than the work itself: packing a 29.7 GB vault on
/// a POCO F6 took 67 minutes, so splitting the result was killed at the
/// 60-minute mark every single time — the engine reported a timeout, the
/// caller threw away the parts it had already written, and the user got a
/// failure toast and a single 29.7 GB file where they had asked for parts.
///
/// A timeout can only honestly answer "has this stopped making progress?".
/// A fixed duration instead answers "is this slower than I wish it were?",
/// which for slow storage is the same question asked wrongly. Five minutes
/// without a single byte moving is a hang; anything still moving is merely
/// slow, and cancelling is always one tap away.
const STALL: Duration = Duration::from_secs(300);

/// Tracks the highest byte count seen and how long it has stood still.
struct Stalled {
    high: u64,
    idle: Duration,
}

impl Stalled {
    fn new() -> Self {
        Stalled {
            high: 0,
            idle: Duration::ZERO,
        }
    }

    /// Record a measurement; true when nothing has moved for `STALL`.
    fn advance(&mut self, done: u64, tick: Duration) -> bool {
        if done > self.high {
            self.high = done;
            self.idle = Duration::ZERO;
            return false;
        }
        self.idle += tick;
        self.idle >= STALL
    }
}

/// Wait for a `cat | tar` pipeline, reporting `measure()` on `tick`.
///
/// Both children must exit on their own to count as finished: `cat` finishing
/// first only means the archive is fully fed, while `tar` may still be writing.
/// Either child failing `try_wait`, the app asking to stop, or the pipeline
/// going `STALL` with no bytes to show for it, kills both — a half-fed tar
/// left running is how partial stagings happened.
fn supervise2<F>(first: &mut Child, second: &mut Child, tick: Duration, mut measure: F) -> bool
where
    F: FnMut() -> (u64, u64, &'static str),
{
    let mut stalled = Stalled::new();
    loop {
        let a_done = match first.try_wait() {
            Ok(status) => status.is_some(),
            Err(_) => {
                kill_pair(first, second);
                return false;
            }
        };
        let b_done = match second.try_wait() {
            Ok(status) => status.is_some(),
            Err(_) => {
                kill_pair(first, second);
                return false;
            }
        };
        if a_done && b_done {
            return true;
        }
        if command::cancelled() {
            kill_pair(first, second);
            return false;
        }
        let (done, total, op) = measure();
        progress(op, done, total);
        if stalled.advance(done, tick) {
            kill_pair(first, second);
            return false;
        }
        std::thread::sleep(tick);
    }
}

/// Wait for a pipeline whose progress cannot be measured (the survey): same
/// kill semantics, no chatter. A progress line every 400 ms for a listing that
/// takes minutes would be thousands of identical lines for no information.
///
/// With no byte counter there is nothing to measure progress against, so this
/// one keeps a plain elapsed bound. It is a listing, not a copy: it does not
/// scale with archive size, and the generous ceiling is only here so a wedged
/// child cannot outlive the process.
fn supervise2_quiet(first: &mut Child, second: &mut Child) -> bool {
    let ceiling = std::time::Instant::now() + SURVEY_CEILING;
    loop {
        let a_done = match first.try_wait() {
            Ok(status) => status.is_some(),
            Err(_) => {
                kill_pair(first, second);
                return false;
            }
        };
        let b_done = match second.try_wait() {
            Ok(status) => status.is_some(),
            Err(_) => {
                kill_pair(first, second);
                return false;
            }
        };
        if a_done && b_done {
            return true;
        }
        if command::cancelled() || std::time::Instant::now() >= ceiling {
            kill_pair(first, second);
            return false;
        }
        std::thread::sleep(EXPORT_TICK);
    }
}

/// Wait for `child`, reporting `measure()` on `tick` until it exits, the app
/// asks to stop, or it goes `STALL` with nothing left to show for itself.
fn supervise<F>(child: &mut Child, tick: Duration, mut measure: F) -> bool
where
    F: FnMut() -> (u64, u64, &'static str),
{
    let mut stalled = Stalled::new();
    loop {
        match child.try_wait() {
            Ok(Some(_)) => return true,
            Ok(None) => {}
            Err(_) => {
                kill_child(child);
                return false;
            }
        }
        if command::cancelled() {
            kill_child(child);
            return false;
        }
        let (done, total, op) = measure();
        progress(op, done, total);
        if stalled.advance(done, tick) {
            kill_child(child);
            return false;
        }
        std::thread::sleep(tick);
    }
}

fn file_len(path: &Path) -> u64 {
    fs::metadata(path).map(|m| m.len()).unwrap_or(0)
}

/// True when the file really is a gzip stream, by its magic number.
///
/// Export stopped compressing (see [transfer_export]) because the payload is
/// already zstd, but every transfer written by an earlier build is a `.tar.gz`
/// and must still import. So the reader asks the BYTES rather than the
/// filename: an extension is a claim, a magic number is evidence.
///
/// This matters more than it looks. toybox `tar` auto-detects gzip when reading
/// a FILE, but on a PIPE it does not, and the split-import path reads a pipe —
/// `cat parts | tar -xf -` fails on a gzip archive with "zcat: gzclose:
/// Illegal seek", having extracted only the first member. Verified on device.
/// The flag has to be right, and the only way to know is to look.
fn is_gzip(path: &Path) -> bool {
    let Ok(mut f) = fs::File::open(path) else {
        return false;
    };
    let mut magic = [0u8; 2];
    match std::io::Read::read(&mut f, &mut magic) {
        Ok(2) => magic == [0x1f, 0x8b],
        _ => false,
    }
}

/// The `-z` flag tar needs for [path], decided by content.
fn extract_flag(path: &Path) -> &'static str {
    if is_gzip(path) { "-xzf" } else { "-xf" }
}

/// The listing flag tar needs for [path], decided by content.
fn list_flag(path: &Path) -> &'static str {
    if is_gzip(path) { "-tzvf" } else { "-tvf" }
}

/// Pack vault-relative `entries` into `dest_tmp`, **uncompressed**.
///
/// This was `tar -czf`, and it was the single largest cost in an export:
/// measured on the reference device, packing a 29.7 GB vault took **19m52s**,
/// of which the compression itself was the overwhelming majority. Measured
/// directly on that vault's own contents:
///
/// | | |
/// |---|---|
/// | `gzip -6` on a 2.29 GB `.shift` | 70 s, output 99.2% of input |
/// | `gzip -1` on the same file | 63 s, output 99.3% of input |
/// | the device's own storage | ~480 MB/s (split), ~1.8 GB/s (raw write) |
///
/// gzip was running at roughly **32 MB/s** — a fifteenth of what the disk
/// could do — to save **0.7%**. The reason is that there was nothing left to
/// compress: every `.shift` in the vault is ALREADY a zstd stream (magic
/// `28 b5 2f fd`, written by `zapdos` during backup). This was compressing
/// compressed data.
///
/// Adding a faster codec — zstd, parallel gzip, a JNI library — would have
/// bought a faster way to do the wrong thing. The measured win is in not
/// compressing at all, and it is the whole 19 minutes.
///
/// The trade is real and deliberate: the archive is ~0.7% larger on the wire,
/// which on a 30 GB vault is ~200 MB, against ~20 minutes of the user's time.
/// For a file that exists to be moved to another phone, time dominates.
///
/// **Reading is unaffected** — `transfer_extract` sniffs the gzip magic and
/// passes `-z` only for archives that really are compressed, so every transfer
/// written by an older build still imports. Verified on device: an archive
/// written before this change extracts unchanged.
///
/// `expected_bytes` is the summed `du` of the carried trees, supplied by the
/// caller for the progress denominator. The archive is written to the
/// caller-chosen temp name so placement (and its atomic rename) stays the
/// caller's decision; a partial file is removed here on failure or cancel.
pub(crate) fn transfer_export(
    vault: &str,
    dest_tmp: &str,
    expected_bytes: u64,
    entries: &[String],
) -> Result<(), String> {
    if !valid_abs(vault) {
        return Err("Invalid vault folder".into());
    }
    if !valid_abs(dest_tmp) {
        return Err("Invalid staging path".into());
    }
    if entries.is_empty() || entries.iter().any(|e| !valid_entry(e)) {
        return Err("Invalid archive entries".into());
    }
    let vault_path = Path::new(vault);
    let dest_path = Path::new(dest_tmp);
    if !vault_path.is_dir() {
        return Err("Vault folder not found".into());
    }
    if let Some(parent) = dest_path.parent() {
        fs::create_dir_all(parent).map_err(|e| format!("Cannot create staging folder: {e}"))?;
    }
    let _ = fs::remove_file(dest_path);
    let _ = fs::remove_file(command::CANCEL_FLAG);

    let mut child = Command::new("tar")
        .args(["-cf"])
        .arg(dest_tmp)
        .args(["-C", vault])
        .args(entries)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|e| format!("Cannot start archive writer: {e}"))?;

    let dest = dest_path.to_path_buf();
    let exited = supervise(&mut child, EXPORT_TICK, || {
        (file_len(&dest), expected_bytes, "EXPORT")
    });
    if !exited {
        let _ = fs::remove_file(dest_path);
        if command::cancelled() {
            protocol::raw("ACTION:CANCELLED");
            return Ok(());
        }
        // A stall, not a timeout: the engine watches the byte counter and gives
        // up only once nothing has moved for STALL. Report it as what it is,
        // so the phone shows a hang rather than a mystery.
        return Err("Archiving stopped making progress".into());
    }
    let status = child
        .wait()
        .map_err(|e| format!("Archive writer failed: {e}"))?;
    if !status.success() {
        let _ = fs::remove_file(dest_path);
        return Err("Archive writer failed".into());
    }
    let bytes = file_len(dest_path);
    if bytes == 0 {
        let _ = fs::remove_file(dest_path);
        return Err("Archive writer produced an empty file".into());
    }
    println!(
        "ACTION:TRANSFER_DONE|OP:EXPORT|PATH:{}|BYTES:{bytes}",
        dest_tmp.replace('|', "/")
    );
    use std::io::Write;
    let _ = std::io::stdout().flush();
    Ok(())
}

/// Expand `archive` into `staging`.
///
/// `expected_bytes` is the archive's uncompressed member total from the single
/// survey listing, reused here so the survey is not run twice. Progress is the
/// staging tree's measured growth against it — real bytes, not a timer.
pub(crate) fn transfer_extract(
    archive: &str,
    staging: &str,
    expected_bytes: u64,
) -> Result<(), String> {
    if !valid_abs(archive) {
        return Err("Invalid transfer file".into());
    }
    if !valid_abs(staging) {
        return Err("Invalid staging folder".into());
    }
    let archive_path = Path::new(archive);
    if !archive_path.is_file() || file_len(archive_path) == 0 {
        return Err("Transfer file not found".into());
    }
    let staging_path = Path::new(staging);
    if staging_path.exists() {
        fs::remove_dir_all(staging_path).map_err(|e| format!("Cannot clear staging: {e}"))?;
    }
    fs::create_dir_all(staging_path).map_err(|e| format!("Cannot create staging: {e}"))?;
    let _ = fs::remove_file(command::CANCEL_FLAG);

    let mut child = Command::new("tar")
        .arg(extract_flag(Path::new(archive)))
        .arg(archive)
        .args(["-C", staging])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|e| format!("Cannot start archive reader: {e}"))?;

    let staging_buf = staging_path.to_path_buf();
    let exited = supervise(&mut child, EXTRACT_TICK, || {
        (dir_size(&staging_buf), expected_bytes, "EXTRACT")
    });
    if !exited {
        let _ = fs::remove_dir_all(staging_path);
        if command::cancelled() {
            protocol::raw("ACTION:CANCELLED");
            return Ok(());
        }
        return Err("Unpacking stopped making progress".into());
    }
    let status = child
        .wait()
        .map_err(|e| format!("Archive reader failed: {e}"))?;
    if !status.success() {
        let _ = fs::remove_dir_all(staging_path);
        return Err("Archive reader failed".into());
    }
    let bytes = dir_size(staging_path);
    println!("ACTION:TRANSFER_DONE|OP:EXTRACT|BYTES:{bytes}");
    use std::io::Write;
    let _ = std::io::stdout().flush();
    Ok(())
}

/// Outcome of placing one app directory.
#[derive(Debug, PartialEq, Eq)]
pub(crate) enum PlaceState {
    Added,
    Kept,
    Failed,
}

/// Move one staged app into the vault.
///
/// Same filesystem, so this is `rename(2)` — atomic and, measured on device,
/// 400 MB in 44 ms. A cross-device fallback copies and removes, so a vault on
/// a different mount still works, just slower. `keep_destination` decides what
/// happens when the destination already exists; a kept archive is reported,
/// never deleted.
pub(crate) fn place_app(
    staging_apps: &Path,
    vault_apps: &Path,
    dir: &str,
    keep_destination: bool,
) -> PlaceState {
    if !valid_entry(dir) || dir.is_empty() {
        return PlaceState::Failed;
    }
    let (src, side) = {
        let user = staging_apps.join("User").join(dir);
        if user.is_dir() {
            (user, "User")
        } else {
            let system = staging_apps.join("System").join(dir);
            if system.is_dir() {
                (system, "System")
            } else {
                return PlaceState::Failed;
            }
        }
    };
    let dest = vault_apps.join(side).join(dir);
    if keep_destination && dest.exists() {
        return PlaceState::Kept;
    }
    if let Some(parent) = dest.parent()
        && fs::create_dir_all(parent).is_err()
    {
        return PlaceState::Failed;
    }
    // The destination may hold an older archive being replaced, or a partial
    // one from a killed run. Remove it first so a failed rename below cannot
    // leave a merged old+new tree that verifies as damaged.
    if dest.exists() && fs::remove_dir_all(&dest).is_err() {
        return PlaceState::Failed;
    }
    match fs::rename(&src, &dest) {
        Ok(()) => PlaceState::Added,
        Err(_) => {
            // Different mount: fall back to copy + remove. Slower, but a vault
            // on adopted storage must still work.
            if copy_dir(&src, &dest).is_ok() {
                let _ = fs::remove_dir_all(&src);
                PlaceState::Added
            } else {
                let _ = fs::remove_dir_all(&dest);
                PlaceState::Failed
            }
        }
    }
}

fn copy_dir(src: &Path, dest: &Path) -> std::io::Result<()> {
    fs::create_dir_all(dest)?;
    for entry in fs::read_dir(src)? {
        let entry = entry?;
        let ty = entry.file_type()?;
        let to = dest.join(entry.file_name());
        if ty.is_symlink() {
            continue;
        } else if ty.is_dir() {
            copy_dir(&entry.path(), &to)?;
        } else if ty.is_file() {
            fs::copy(entry.path(), to)?;
        }
    }
    Ok(())
}

/// Place every staged app named in `dirs` into the vault, reporting each one.
///
/// `keep_destination` is the KEEP_DESTINATION default: an existing archive is
/// reported as kept and never touched. The plan (which dirs, and the merge
/// mode) stays with the Kotlin caller — this only moves what it is told to.
pub(crate) fn transfer_place(
    staging: &str,
    vault: &str,
    keep_destination: bool,
    dirs: &[String],
) -> Result<(), String> {
    if !valid_abs(staging) {
        return Err("Invalid staging folder".into());
    }
    if !valid_abs(vault) {
        return Err("Invalid vault folder".into());
    }
    if dirs.is_empty() {
        // An empty list is a NORMAL outcome, not a failure: every app the
        // transfer carried was already in the vault, so the planner admitted
        // none of them. Returning Err here logged a red ACTION:ERROR on a
        // perfectly healthy import — found on device, where a re-import of a
        // transfer whose apps were all present reported an error while the
        // import itself succeeded with `ADDED:0 KEPT:2 FAILED:0`.
        //
        // Reporting zero of everything is the honest summary, and it lets the
        // app tell "nothing needed doing" from "the placement failed".
        println!("ACTION:TRANSFER_DONE|OP:PLACE|ADDED:0|KEPT:0|FAILED:0");
        use std::io::Write;
        let _ = std::io::stdout().flush();
        return Ok(());
    }
    let staging_apps = PathBuf::from(staging).join("Apps");
    let vault_apps = PathBuf::from(vault).join("Apps");
    let mut added = 0u64;
    let mut kept = 0u64;
    let mut failed = 0u64;
    for dir in dirs {
        if command::cancelled() {
            protocol::raw("ACTION:CANCELLED");
            return Ok(());
        }
        let state = place_app(&staging_apps, &vault_apps, dir, keep_destination);
        let word = match state {
            PlaceState::Added => {
                added += 1;
                "added"
            }
            PlaceState::Kept => {
                kept += 1;
                "kept"
            }
            PlaceState::Failed => {
                failed += 1;
                "failed"
            }
        };
        println!(
            "ACTION:TRANSFER_FILE|OP:PLACE|NAME:{}|STATE:{word}",
            dir.replace('|', "/")
        );
        use std::io::Write;
        let _ = std::io::stdout().flush();
    }
    println!("ACTION:TRANSFER_DONE|OP:PLACE|ADDED:{added}|KEPT:{kept}|FAILED:{failed}");
    use std::io::Write;
    let _ = std::io::stdout().flush();
    Ok(())
}

/// Sum the uncompressed member sizes of a transfer without extracting it.
///
/// Streams `cat parts | tar -tzvf -` and adds the size column in-process, so
/// the listing is computed once and never written to disk. This is the one
/// pass both the space gate and the progress label use — an earlier version
/// ran it twice, at ~3 minutes per pass on a large archive.
pub(crate) fn transfer_survey(parts: &[String]) -> Result<(), String> {
    if parts.is_empty() || parts.iter().any(|p| !valid_abs(p)) {
        return Err("Invalid transfer parts".into());
    }
    for part in parts {
        if !Path::new(part).is_file() {
            return Err(format!("Missing transfer part: {}", part.replace('|', "/")));
        }
    }
    let _ = std::fs::remove_file(command::CANCEL_FLAG);

    let mut cat = Command::new("cat")
        .args(parts)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|e| format!("Cannot start archive reader: {e}"))?;
    let cat_out = match cat.stdout.take() {
        Some(out) => out,
        None => {
            kill_child(&mut cat);
            return Err("Archive reader failed".into());
        }
    };
    // Read from a PIPE, so auto-detection does not apply — see [is_gzip].
    let mut tar = match Command::new("tar")
        .arg(list_flag(Path::new(&parts[0])))
        .arg("-")
        .stdin(Stdio::from(cat_out))
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
    {
        Ok(child) => child,
        Err(error) => {
            kill_child(&mut cat);
            return Err(format!("Cannot list archive contents: {error}"));
        }
    };
    // The listing is read on its own thread because it blocks: joining it from
    // the supervisor would make cancellation wait out the whole listing. When
    // the children are killed the pipe closes and the read ends by itself.
    // The closure cannot panic (no indexing, no unwrap), so joining it cannot
    // abort this privileged process either.
    let tar_out = match tar.stdout.take() {
        Some(out) => out,
        None => {
            kill_pair(&mut cat, &mut tar);
            return Err("Archive reader failed".into());
        }
    };
    let reader = std::thread::spawn(move || {
        use std::io::Read;
        let mut tar_out = tar_out;
        let mut buf = Vec::new();
        let _ = tar_out.read_to_end(&mut buf);
        buf
    });

    if !supervise2_quiet(&mut cat, &mut tar) {
        let _ = reader.join();
        if command::cancelled() {
            protocol::raw("ACTION:CANCELLED");
            return Ok(());
        }
        return Err("Could not read what is inside the transfer file".into());
    }
    let cat_ok = cat.wait().map(|s| s.success()).unwrap_or(false);
    let tar_ok = tar.wait().map(|s| s.success()).unwrap_or(false);
    let listing = match reader.join() {
        Ok(buf) => buf,
        Err(_) => Vec::new(),
    };
    if !cat_ok || !tar_ok {
        return Err("Could not read what is inside the transfer file".into());
    }
    let text = String::from_utf8_lossy(&listing);
    let mut total: u64 = 0;
    for line in text.lines() {
        let size = line
            .split_whitespace()
            .nth(2)
            .and_then(|f| f.parse::<u64>().ok());
        total = total.saturating_add(size.unwrap_or(0));
    }
    println!("ACTION:TRANSFER_DONE|OP:SURVEY|BYTES:{total}");
    use std::io::Write;
    let _ = std::io::stdout().flush();
    Ok(())
}

/// Expand transfer parts straight into `staging` with no intermediate file.
///
/// `cat parts | tar -xzf - -C staging` streams the concatenation, so split
/// parts never need reassembling: a 34 GB transfer in 4 GB parts imports with
/// the same peak disk as the single file, and a part that never arrives is
/// caught by the continuity check before a byte is written, not mid-extract.
pub(crate) fn transfer_concat_extract(
    staging: &str,
    expected_bytes: u64,
    parts: &[String],
) -> Result<(), String> {
    if !valid_abs(staging) {
        return Err("Invalid staging folder".into());
    }
    if parts.is_empty() || parts.iter().any(|p| !valid_abs(p)) {
        return Err("Invalid transfer parts".into());
    }
    for part in parts {
        if !Path::new(part).is_file() {
            return Err(format!("Missing transfer part: {}", part.replace('|', "/")));
        }
    }
    let staging_path = Path::new(staging);
    if staging_path.exists() {
        fs::remove_dir_all(staging_path).map_err(|e| format!("Cannot clear staging: {e}"))?;
    }
    fs::create_dir_all(staging_path).map_err(|e| format!("Cannot create staging: {e}"))?;
    let _ = std::fs::remove_file(command::CANCEL_FLAG);

    let mut cat = Command::new("cat")
        .args(parts)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|e| format!("Cannot start archive reader: {e}"))?;
    let cat_out = match cat.stdout.take() {
        Some(out) => out,
        None => {
            kill_child(&mut cat);
            return Err("Archive reader failed".into());
        }
    };
    let mut tar = match Command::new("tar")
        .arg(extract_flag(Path::new(&parts[0])))
        .arg("-")
        .args(["-C", staging])
        .stdin(Stdio::from(cat_out))
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
    {
        Ok(child) => child,
        Err(error) => {
            kill_child(&mut cat);
            return Err(format!("Cannot start archive extractor: {error}"));
        }
    };

    let staging_buf = staging_path.to_path_buf();
    let exited = supervise2(&mut cat, &mut tar, EXTRACT_TICK, || {
        (dir_size(&staging_buf), expected_bytes, "EXTRACT")
    });
    if !exited {
        let _ = fs::remove_dir_all(staging_path);
        if command::cancelled() {
            protocol::raw("ACTION:CANCELLED");
            return Ok(());
        }
        return Err("Unpacking stopped making progress".into());
    }
    let cat_ok = cat.wait().map(|s| s.success()).unwrap_or(false);
    let tar_ok = tar.wait().map(|s| s.success()).unwrap_or(false);
    if !cat_ok || !tar_ok {
        let _ = fs::remove_dir_all(staging_path);
        return Err("Archive reader failed".into());
    }
    let bytes = dir_size(staging_path);
    println!("ACTION:TRANSFER_DONE|OP:EXTRACT|BYTES:{bytes}");
    use std::io::Write;
    let _ = std::io::stdout().flush();
    Ok(())
}

/// Split `file` into fixed-size pieces named `<prefix>.part-aa`, `<prefix>.part-ab`, …
///
/// toybox `split` has no numeric-suffix flag, so suffixes are two letters
/// (`-a` is accepted but the default 2 is what the importer expects). Fixed
/// length matters: `part-aa`…`part-zz` sort correctly with a plain string
/// sort, which is what the import streams in order. The original is left in
/// place — the caller deletes it only after verifying every part.
pub(crate) fn transfer_split(file: &str, prefix: &str, bytes_per_part: u64) -> Result<(), String> {
    if !valid_abs(file) {
        return Err("Invalid transfer file".into());
    }
    if !valid_abs(prefix) {
        return Err("Invalid part prefix".into());
    }
    if bytes_per_part < 1024 * 1024 {
        return Err("Part size too small".into());
    }
    let file_path = Path::new(file);
    let total = file_len(file_path);
    if total == 0 {
        return Err("Transfer file not found".into());
    }
    let _ = std::fs::remove_file(command::CANCEL_FLAG);

    let mut child = Command::new("split")
        .args(["-b", &bytes_per_part.to_string(), file, prefix])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|e| format!("Cannot start file splitter: {e}"))?;

    // Suffixes are appended straight onto the prefix by split itself.
    let parent = Path::new(prefix).parent().unwrap_or(Path::new("/"));
    let stem = prefix.to_string();
    let exited = supervise(&mut child, EXPORT_TICK, || {
        let mut done = 0u64;
        if let Ok(entries) = fs::read_dir(parent) {
            for entry in entries.flatten() {
                let path = entry.path();
                if path.to_string_lossy().starts_with(&stem) && path.is_file() {
                    done = done.saturating_add(file_len(&path));
                }
            }
        }
        (done, total, "SPLIT")
    });
    if !exited {
        if command::cancelled() {
            protocol::raw("ACTION:CANCELLED");
            return Ok(());
        }
        return Err("Splitting stopped making progress".into());
    }
    let status = child
        .wait()
        .map_err(|e| format!("File splitter failed: {e}"))?;
    if !status.success() {
        return Err("File splitter failed".into());
    }
    // Count what actually landed. A short count here means split stopped early
    // and the set is incomplete — the caller verifies byte sums before
    // deleting the original, so this number is advisory, not trusted.
    let mut parts = 0u64;
    if let Ok(entries) = fs::read_dir(parent) {
        for entry in entries.flatten() {
            let path = entry.path();
            if path.to_string_lossy().starts_with(&stem) && path.is_file() {
                parts += 1;
            }
        }
    }
    println!("ACTION:TRANSFER_DONE|OP:SPLIT|PARTS:{parts}|BYTES:{total}");
    use std::io::Write;
    let _ = std::io::stdout().flush();
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn entries_reject_separators_and_parents() {
        assert!(valid_entry("Apps"));
        assert!(valid_entry(".export-meta"));
        assert!(valid_entry("DroidVault.apk"));
        assert!(!valid_entry(""));
        assert!(!valid_entry("."));
        assert!(!valid_entry(".."));
        assert!(!valid_entry("Apps/User"));
        assert!(!valid_entry("a|b"));
        assert!(!valid_entry("a\nb"));
    }

    #[test]
    fn abs_paths_reject_traversal_and_metacharacters() {
        assert!(valid_abs("/storage/emulated/0/DroidVault"));
        assert!(!valid_abs("relative/path"));
        assert!(!valid_abs("/a/../b"));
        assert!(!valid_abs("/a/./b"));
        assert!(!valid_abs("/a|b"));
        assert!(!valid_abs("/a\nb"));
    }

    /// A transfer written by an older build is gzip and must still import; one
    /// written now is a plain tar and must not be fed to `-z`. Reading the file
    /// name cannot tell those apart, and toybox tar cannot auto-detect on a
    /// pipe — which is exactly how the split importer reads.
    #[test]
    fn transfer_format_is_read_from_the_bytes_not_the_name() {
        let dir = std::env::temp_dir().join("dvfmt");
        let _ = fs::create_dir_all(&dir);

        let gz = dir.join("old.dvbackup.tar.gz");
        fs::write(&gz, [0x1f, 0x8b, 0x08, 0x00, 0, 0, 0, 0, 0, 3]).unwrap();
        assert!(is_gzip(&gz), "gzip magic 1f 8b must be recognised");

        let plain = dir.join("new.dvbackup.tar");
        fs::write(&plain, [0x75, 0x73, 0x74, 0x61, 0x72, 0, 0, 0]).unwrap();
        assert!(
            !is_gzip(&plain),
            "a tar header must not be mistaken for gzip"
        );

        // Empty and truncated files are not gzip. Guessing "yes" on a zero
        // byte file would make `-z` fail a perfectly good empty archive.
        let empty = dir.join("empty.dvbackup.tar");
        fs::write(&empty, b"").unwrap();
        assert!(!is_gzip(&empty));
        let short = dir.join("short.dvbackup.tar");
        fs::write(&short, [0x1f]).unwrap();
        assert!(!is_gzip(&short));

        // The flag actually handed to tar follows from it.
        assert_eq!(extract_flag(&gz), "-xzf");
        assert_eq!(extract_flag(&plain), "-xf");
        assert_eq!(list_flag(&gz), "-tzvf");
        assert_eq!(list_flag(&plain), "-tvf");

        // A missing file must not be reported as gzip; it is reported as not
        // gzip and the caller fails on the missing file instead.
        assert!(!is_gzip(&dir.join("nope.tar")));

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn progress_never_claims_100_while_running() {
        assert_eq!(clamp_pct(100, 100), 99);
        assert_eq!(clamp_pct(0, 100), 0);
        assert_eq!(clamp_pct(50, 0), 0);
        assert_eq!(clamp_pct(1, 100), 1);
    }

    #[test]
    fn place_keeps_destination_when_asked() {
        let base = std::env::temp_dir().join(format!("dv_place_{}", std::process::id()));
        let staging = base.join("staging").join("Apps").join("User").join("Demo");
        let dest = base.join("vault").join("Apps").join("User").join("Demo");
        fs::create_dir_all(&staging).unwrap();
        fs::write(staging.join("App.shift"), b"x").unwrap();
        fs::create_dir_all(&dest).unwrap();
        fs::write(dest.join("App.shift"), b"old").unwrap();
        let state = place_app(
            &base.join("staging").join("Apps"),
            &base.join("vault").join("Apps"),
            "Demo",
            true,
        );
        assert_eq!(state, PlaceState::Kept);
        assert_eq!(fs::read(dest.join("App.shift")).unwrap(), b"old");
        let _ = fs::remove_dir_all(&base);
    }

    #[test]
    fn place_renames_onto_the_vault() {
        let base = std::env::temp_dir().join(format!("dv_place2_{}", std::process::id()));
        let staging = base
            .join("staging")
            .join("Apps")
            .join("System")
            .join("Demo");
        fs::create_dir_all(&staging).unwrap();
        fs::write(staging.join("App.shift"), b"new").unwrap();
        let state = place_app(
            &base.join("staging").join("Apps"),
            &base.join("vault").join("Apps"),
            "Demo",
            true,
        );
        assert_eq!(state, PlaceState::Added);
        assert!(
            !base
                .join("staging")
                .join("Apps")
                .join("System")
                .join("Demo")
                .exists()
        );
        assert_eq!(
            fs::read(
                base.join("vault")
                    .join("Apps")
                    .join("System")
                    .join("Demo")
                    .join("App.shift")
            )
            .unwrap(),
            b"new"
        );
        let _ = fs::remove_dir_all(&base);
    }

    #[test]
    fn place_reports_missing_source_as_failed() {
        let base = std::env::temp_dir().join(format!("dv_place3_{}", std::process::id()));
        let state = place_app(
            &base.join("staging").join("Apps"),
            &base.join("vault").join("Apps"),
            "Ghost",
            true,
        );
        assert_eq!(state, PlaceState::Failed);
        let _ = fs::remove_dir_all(&base);
    }

    #[test]
    fn split_rejects_absurd_part_sizes() {
        // Below 1 MB is never a sane part for a multi-GB transfer; it would
        // produce thousands of files. Refused, not clamped — silently
        // producing 34,000 parts would be worse than an error.
        assert!(transfer_split("/nope.tgz", "/nope.part-", 512).is_err());
    }

    #[test]
    fn survey_and_extract_reject_empty_part_lists() {
        assert!(transfer_survey(&[]).is_err());
        assert!(transfer_concat_extract("/tmp/dv_x", 1, &[]).is_err());
    }

    #[test]
    fn place_rejects_dot_segments() {
        // valid_entry already covers these, but the place path takes raw dir
        // names from an archive listing — assert the gate it actually passes.
        assert!(!valid_entry(".."));
        assert!(!valid_entry("a/b"));
        assert!(!valid_entry(""));
    }

    #[test]
    fn dir_size_counts_files_and_ignores_missing() {
        let base = std::env::temp_dir().join(format!("dv_size_{}", std::process::id()));
        fs::create_dir_all(base.join("sub")).unwrap();
        fs::write(base.join("a"), vec![0u8; 100]).unwrap();
        fs::write(base.join("sub").join("b"), vec![0u8; 50]).unwrap();
        assert_eq!(dir_size(&base), 150);
        assert_eq!(dir_size(&base.join("nope")), 0);
        let _ = fs::remove_dir_all(&base);
    }
}
