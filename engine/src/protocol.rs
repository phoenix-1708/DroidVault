use std::io::{self, Write};

fn field(value: &str) -> String {
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
