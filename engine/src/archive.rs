use crate::command::{ZAPDOS, child};
use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::time::Duration;

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

pub fn pack_path(source_root: &Path, relative: &Path, destination: &Path) -> io::Result<bool> {
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
        fs::rename(&temp, destination)?;
        Ok(true)
    } else {
        let _ = fs::remove_file(temp);
        Ok(false)
    }
}

pub fn pack_files(files: &[PathBuf], destination: &Path) -> io::Result<bool> {
    if files.is_empty() {
        return Ok(false);
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
        fs::rename(&temp, destination)?;
        Ok(true)
    } else {
        let _ = fs::remove_file(temp);
        Ok(false)
    }
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
}
