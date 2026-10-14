//! Droid Vault's privileged operation engine.
//!
//! Android platform integration remains in Kotlin. This process is started by
//! libsu as root and owns operation planning, filesystem work, archive
//! pipelines, validation, and the line-oriented progress protocol.

use std::env;
use std::process::ExitCode;

mod archive;
mod checksum;
mod command;
mod device;
mod engine;
mod install;
mod manifest;
mod partitions;
mod protocol;
mod quiesce;
mod restore;
mod targets;
mod tools;
mod transfer;
mod verify;

fn main() -> ExitCode {
    // args_os + lossy, never args(): `args()` unwraps and therefore PANICS on
    // any non-UTF-8 argument. The vault path arrives from the filesystem (an
    // exFAT/FAT SD-card directory name is not required to be valid Unicode),
    // and with `panic = "abort"` in the release profile one stray byte killed
    // the privileged process mid-operation. The shell's `case "$1" in` never
    // cared about encoding; lossy decoding reproduces that tolerance.
    let args: Vec<String> = env::args_os()
        .skip(1)
        .map(|arg| arg.to_string_lossy().into_owned())
        .collect();
    let result = engine::dispatch(&args);
    match result {
        Ok(()) => ExitCode::SUCCESS,
        Err(error) => {
            protocol::error(&error);
            ExitCode::FAILURE
        }
    }
}
