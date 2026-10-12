#!/system/bin/sh
# Compatibility entry point for older app code and ROM-survival scripts.
# Backup, restore, and root-operation logic lives in the Rust engine.
export PATH="/system/bin:/vendor/bin:/system/xbin:/sbin:/data/adb/magisk:/data/adb/ksu/bin:/data/adb/ap/bin:$PATH"
ENGINE="/data/adb/DroidVault/droidvault-engine"
if [ ! -x "$ENGINE" ]; then
    echo "ACTION:ERROR|MSG:Droid Vault Rust engine is missing or not executable"
    exit 1
fi
exec "$ENGINE" "$@"
