use crate::command::valid_package;
use std::fs;
use std::path::Path;

#[derive(Clone, Debug)]
pub struct Target {
    pub package: String,
    pub label: String,
    pub version: String,
    pub version_code: String,
    pub kind: String,
    pub apk_path: String,
    pub app_size: u64,
    pub data_size: u64,
    pub media_size: u64,
    pub components: Option<String>,
}

pub fn read(path: &Path) -> Result<Vec<Target>, String> {
    let input = fs::read_to_string(path)
        .map_err(|_| format!("Targets file not found at {}", path.display()))?;
    let mut targets = Vec::new();
    for raw_line in input.lines().filter(|line| !line.trim().is_empty()) {
        let line = raw_line.trim_end_matches('\r');
        let mut fields = line.split('|');
        let package = fields.next().unwrap_or_default().to_string();
        let label = fields.next().unwrap_or_default().to_string();
        let version = fields.next().unwrap_or_default().to_string();
        let version_code = fields.next().unwrap_or_default().to_string();
        let kind = fields.next().unwrap_or("User").to_string();
        let apk_path = fields.next().unwrap_or_default().to_string();
        let app_size = fields.next().unwrap_or("0").parse().unwrap_or(0);
        let data_size = fields.next().unwrap_or("0").parse().unwrap_or(0);
        let media_size = fields.next().unwrap_or("0").parse().unwrap_or(0);
        let components = fields.next().filter(|v| !v.is_empty()).map(str::to_string);
        if !valid_package(&package) {
            return Err(format!("Invalid package in target list: {package}"));
        }
        if label.is_empty() || label.contains('/') || label.contains('|') || label.contains('\n') {
            return Err("Invalid label in target list".into());
        }
        if kind != "User" && kind != "System" {
            return Err("Invalid app type in target list".into());
        }
        if !apk_path.is_empty()
            && (!apk_path.starts_with('/')
                || apk_path.contains('\n')
                || apk_path.contains('\r')
                || apk_path.contains('|'))
        {
            return Err("Invalid APK source path in target list".into());
        }
        targets.push(Target {
            package,
            label,
            version,
            version_code,
            kind,
            apk_path,
            app_size,
            data_size,
            media_size,
            components,
        });
    }
    Ok(targets)
}
