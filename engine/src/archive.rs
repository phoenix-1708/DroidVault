use crate::checksum;
use crate::command::{ZAPDOS, child, text};
use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::time::Duration;

/// A tree's size in KB, measured the way the recorded part sizes were.
///
/// `du -sk`, deliberately, and not an apparent-size sum: the app measures
/// `DataExtSize`/`MediaOBBSize` with `du`, so a per-archive figure taken any
/// other way would not add up to the total it was split out of, and the restore
/// space gate would be reasoning from two different measures at once.
///
/// Taken after the pack rather than before, so the tree it walks is the one
/// `tar` has just read and is still in page cache.
pub fn tree_kb(path: &Path) -> u64 {
    parse_du_kb(&text(
        "du",
        ["-sk".to_string(), path.to_string_lossy().into_owned()],
    ))
}

/// Pure half of [`tree_kb`], so the column choice is pinned by a test.
///
/// `du -sk <path>` prints `<kib>\t<path>`; a path with spaces in it must not
/// shift the column, so the FIRST whitespace field is taken and nothing after it
/// is read. `0` means "not measured" rather than "empty" — the same convention
/// the restore gate already reads `Meta.txt` through.
pub fn parse_du_kb(output: &str) -> u64 {
    output
        .lines()
        .filter(|line| !line.trim().is_empty())
        .find_map(|line| line.split_whitespace().next())
        .and_then(|field| field.parse::<u64>().ok())
        .unwrap_or(0)
}

/// What a pack attempt produced.
///
/// Three outcomes rather than a bool, because "written" and "written with a
/// digest" are different facts: a device without `sha256sum` can still produce a
/// usable archive, and reporting that as a failed pack would be a lie in the
/// other direction. `Nothing` is the only failure.
#[derive(Debug, PartialEq, Eq)]
pub enum Packed {
    /// Written, and its digest recorded in the `.sha256` sidecar beside it.
    Hashed(String),
    /// Written, but no digest could be computed (no usable `sha256sum`).
    Unhashed,
    /// Nothing usable was produced: the pipeline failed or emitted no bytes.
    Nothing,
}

impl Packed {
    pub fn wrote(&self) -> bool {
        !matches!(self, Packed::Nothing)
    }
}

/// Promote the finished temp file into place and record its digest.
///
/// The rename comes first and the hash immediately after: a rename inside one
/// directory touches no bytes, so the freshly written data is still in page
/// cache either way (measured 1.15 s/GB warm against 1.50 s/GB cold on the
/// reference device), and hashing after the rename means a digest can never be
/// recorded for a file that was not promoted.
fn promote(temp: &Path, destination: &Path) -> io::Result<Packed> {
    fs::rename(temp, destination)?;
    Ok(match checksum::record(destination) {
        Some(digest) => Packed::Hashed(digest),
        None => Packed::Unhashed,
    })
}

fn wait_pipeline(
    first: &mut std::process::Child,
    second: &mut std::process::Child,
    partial_output: Option<&Path>,
) -> io::Result<(bool, bool)> {
    let pids = [first.id(), second.id()];
    let partial = partial_output.map(|path| path.to_string_lossy().into_owned());
    for pid in pids {
        crate::command::register_child(pid, partial.as_deref());
    }
    let mut first_status = None;
    let mut second_status = None;
    loop {
        if first_status.is_none() {
            match first.try_wait() {
                Ok(status) => first_status = status,
                Err(error) => {
                    let _ = first.kill();
                    let _ = second.kill();
                    let _ = first.wait();
                    let _ = second.wait();
                    crate::command::forget_children(&pids);
                    return Err(error);
                }
            }
        }
        if second_status.is_none() {
            match second.try_wait() {
                Ok(status) => second_status = status,
                Err(error) => {
                    let _ = first.kill();
                    let _ = second.kill();
                    let _ = first.wait();
                    let _ = second.wait();
                    crate::command::forget_children(&pids);
                    return Err(error);
                }
            }
        }
        if first_status.is_some() && second_status.is_some() {
            break;
        }
        if crate::command::cancelled() {
            let _ = first.kill();
            let _ = second.kill();
            let _ = first.wait();
            let _ = second.wait();
            crate::command::forget_children(&pids);
            return Err(io::Error::other("operation cancelled"));
        }
        std::thread::sleep(Duration::from_secs(1));
    }
    crate::command::forget_children(&pids);
    Ok((
        first_status.is_some_and(|s| s.success()),
        second_status.is_some_and(|s| s.success()),
    ))
}

/// Exclusion patterns applied when bundling an app's data directories.
///
/// tar matches an exclude pattern against the END of a member name, so these
/// MUST be anchored to the member prefix. The unanchored `--exclude=lib`
/// spelling this replaced silently dropped every nested `lib`, `cache`,
/// `code_cache`, `Backup_*`, `.ota` and `no_backup` directory from each app's
/// archive while Meta.txt still recorded the full size.
const EXCLUDED_SUBTREES: [&str; 6] = [
    "cache",
    "code_cache",
    "lib",
    ".ota",
    "no_backup",
    "Backup_*",
];

/// Build the argv for the tar half of a bundle. Split out so the anchoring is
/// testable without spawning tar.
fn pack_tar_args(source_root: &Path, relative: &Path) -> Vec<String> {
    let prefix = relative.to_string_lossy();
    let mut args: Vec<String> = EXCLUDED_SUBTREES
        .iter()
        .map(|pattern| format!("--exclude={prefix}/{pattern}"))
        .collect();
    args.push("-cpf".into());
    args.push("-".into());
    args.push("-C".into());
    args.push(source_root.to_string_lossy().into_owned());
    args.push(relative.to_string_lossy().into_owned());
    args
}

pub fn pack_path(source_root: &Path, relative: &Path, destination: &Path) -> io::Result<Packed> {
    if let Some(parent) = destination.parent() {
        fs::create_dir_all(parent)?;
    }
    let temp = PathBuf::from(format!("{}.tmp", destination.display()));
    let _ = fs::remove_file(&temp);
    let tar = Command::new("tar")
        .args(pack_tar_args(source_root, relative))
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()?;
    let mut tar = tar;
    let tar_stdout = tar
        .stdout
        .take()
        .ok_or_else(|| io::Error::other("tar stdout unavailable"))?;
    let mut zstd = match Command::new(ZAPDOS)
        .args(["-1", "-f", "-q", "-o"])
        .arg(&temp)
        .stdin(Stdio::from(tar_stdout))
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
    {
        Ok(child) => child,
        Err(error) => {
            let _ = tar.kill();
            let _ = tar.wait();
            let _ = fs::remove_file(&temp);
            return Err(error);
        }
    };
    let (tar_ok, zstd_ok) = match wait_pipeline(&mut tar, &mut zstd, Some(&temp)) {
        Ok(statuses) => statuses,
        Err(error) => {
            let _ = fs::remove_file(&temp);
            return Err(error);
        }
    };
    if tar_ok && zstd_ok && fs::metadata(&temp).map(|m| m.len() > 0).unwrap_or(false) {
        return promote(&temp, destination);
    }
    let _ = fs::remove_file(temp);
    Ok(Packed::Nothing)
}

pub fn pack_files(files: &[PathBuf], destination: &Path) -> io::Result<Packed> {
    if files.is_empty() {
        return Ok(Packed::Nothing);
    }
    if let Some(parent) = destination.parent() {
        fs::create_dir_all(parent)?;
    }
    let temp = PathBuf::from(format!("{}.tmp", destination.display()));
    let _ = fs::remove_file(&temp);
    let mut args: Vec<String> = vec!["-cpf".into(), "-".into(), "-C".into(), "/".into()];
    for file in files {
        let path = file.strip_prefix("/").unwrap_or(file);
        args.push(path.to_string_lossy().into_owned());
    }
    let tar = Command::new("tar")
        .args(&args)
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()?;
    let mut tar = tar;
    let tar_stdout = tar
        .stdout
        .take()
        .ok_or_else(|| io::Error::other("tar stdout unavailable"))?;
    let mut zstd = match Command::new(ZAPDOS)
        .args(["-1", "-f", "-q", "-o"])
        .arg(&temp)
        .stdin(Stdio::from(tar_stdout))
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
    {
        Ok(child) => child,
        Err(error) => {
            let _ = tar.kill();
            let _ = tar.wait();
            let _ = fs::remove_file(&temp);
            return Err(error);
        }
    };
    let (tar_ok, zstd_ok) = match wait_pipeline(&mut tar, &mut zstd, Some(&temp)) {
        Ok(statuses) => statuses,
        Err(error) => {
            let _ = fs::remove_file(&temp);
            return Err(error);
        }
    };
    if tar_ok && zstd_ok && fs::metadata(&temp).map(|m| m.len() > 0).unwrap_or(false) {
        return promote(&temp, destination);
    }
    let _ = fs::remove_file(temp);
    Ok(Packed::Nothing)
}

pub fn unpack(archive: &Path, destination: &Path) -> io::Result<bool> {
    fs::create_dir_all(destination)?;
    let archive_arg = archive.to_string_lossy().into_owned();
    let decoder = child(ZAPDOS, ["-d", "-q", "-c", archive_arg.as_str()])?;
    let mut decoder = decoder;
    let stdout = decoder
        .stdout
        .take()
        .ok_or_else(|| io::Error::other("decoder stdout unavailable"))?;
    let mut tar = match Command::new("tar")
        .args(["-pxf", "-"])
        .arg("-C")
        .arg(destination)
        .stdin(Stdio::from(stdout))
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
    {
        Ok(child) => child,
        Err(error) => {
            let _ = decoder.kill();
            let _ = decoder.wait();
            return Err(error);
        }
    };
    let (tar_ok, decoder_ok) = match wait_pipeline(&mut decoder, &mut tar, None) {
        Ok(statuses) => statuses,
        Err(error) => return Err(error),
    };
    Ok(tar_ok && decoder_ok)
}

pub fn archive_valid(archive: &Path) -> bool {
    let archive_arg = archive.to_string_lossy().into_owned();
    let Ok(decoder) = child(ZAPDOS, ["-d", "-q", "-c", archive_arg.as_str()]) else {
        return false;
    };
    let mut decoder = decoder;
    // Both early returns below already own a live `zapdos` child. Returning
    // without killing and reaping it leaves a zombie (and a held pipe) for the
    // engine's whole lifetime; wait_pipeline gets this right, so match it.
    let Some(stdout) = decoder.stdout.take() else {
        let _ = decoder.kill();
        let _ = decoder.wait();
        return false;
    };
    let Ok(mut tar) = Command::new("tar")
        .args(["-tf", "-"])
        .stdin(Stdio::from(stdout))
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
    else {
        let _ = decoder.kill();
        let _ = decoder.wait();
        return false;
    };
    tar.wait().map(|s| s.success()).unwrap_or(false)
        && decoder.wait().map(|s| s.success()).unwrap_or(false)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// tar matches an exclude pattern against the END of a member name, so an
    /// unanchored `--exclude=lib` also matches `com.foo/sub/lib` and silently
    /// dropped real app data. Every pattern must carry the member prefix.
    #[test]
    fn pack_excludes_are_anchored_to_the_member() {
        let args = pack_tar_args(Path::new("/data/data"), Path::new("com.foo"));
        for pattern in EXCLUDED_SUBTREES {
            let expected = format!("--exclude=com.foo/{pattern}");
            assert!(
                args.contains(&expected),
                "missing anchored exclude {expected}; got {args:?}"
            );
        }
        // No unanchored spelling may survive.
        for pattern in EXCLUDED_SUBTREES {
            assert!(
                !args.contains(&format!("--exclude={pattern}")),
                "unanchored exclude --exclude={pattern} would drop nested data"
            );
        }
        assert_eq!(args.last().unwrap(), "com.foo");
        assert!(args.contains(&"-cpf".to_string()));
    }

    #[test]
    fn excluded_subtrees_cover_the_legacy_set() {
        // The patterns ported from the legacy launcher's BUNDAPP().
        assert_eq!(
            EXCLUDED_SUBTREES,
            [
                "cache",
                "code_cache",
                "lib",
                ".ota",
                "no_backup",
                "Backup_*"
            ]
        );
    }

    #[test]
    fn du_output_parses_to_kilobytes() {
        // `du -sk <path>` prints `<kib>\t<path>`, and the path is whatever the app
        // is called — so the FIRST field is taken and nothing after it is read.
        assert_eq!(parse_du_kb("2048\t/data/data/com.foo\n"), 2048);
        assert_eq!(
            parse_du_kb("512\t/data/media/0/Android/data/com.foo bar\n"),
            512,
            "a label with a space must not shift the column"
        );
        // du walks a tree and prints one line per argument; with one argument
        // there is one line, and a missing/unreadable tree is 0 — "not measured",
        // never a claim that the tree was empty.
        assert_eq!(parse_du_kb(""), 0);
        assert_eq!(parse_du_kb("du: cannot access '/data/data/com.foo'\n"), 0);
        assert_eq!(parse_du_kb("\n"), 0);
    }
}
