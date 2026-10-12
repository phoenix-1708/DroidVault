# STABILITY — gates, consents, and what remains

## Fail-closed consents (all default OFF)
| Key (`droidvault_consent_prefs`) | Effect when ON | Backend gate |
|---|---|---|
| `allow_fast_install` | verifier-disable + Play-source spoof + dexopt assume-verified | Rust engine `ALLOW_VERIFIER_DISABLE` in `--install-apps`/`--restore` |
| `allow_ssaid_restore` | Android ID cloning via `settings_ssaid.xml` | Rust SSAID writer requires `ALLOW_SSAID=1`; Kotlin strips comp 5 |
| `allow_perm_restore` | silent `grant`/`appops set` restore | Rust permission writer requires `ALLOW_PERM=1`; Kotlin strips comp 3 |

Kotlin passes `InstallerPolicy.shellEnv(ctx)` as env prefix to every
`--backup/--restore/--install-apps` invocation. UI must expose three explicit
switches calling `MainViewModel.setFastInstallAllowed/setSsaidRestoreAllowed/setPermRestoreAllowed`.

## Platform kill on SMS role handoff (verified on-device 2026-09-08)
- Returning the default-SMS role revokes our role-granted permissions and the
  system kills our process (`Killing ...: permissions revoked`) — even in the
  foreground. Only the Messages restore path takes the SMS role, hence only it
  "closes".
- Mitigations: `closeForSmsHandoff` finishes the task first (kill lands
  invisibly); the handoff is ONE atomic root invocation
  (`DeviceManager.releaseSmsRoleTo`, survives our death); `pending_sms_restore`
  pref + `healPendingSmsHandoff()` on launch completes/verifies via
  `Settings.Secure` (Telephony API throws without READ_SMS after the kill).
  Verified: stuck-as-default → cold start → default restored, flag cleared.

## P0 done
- `ShellQuote` everywhere, `dd` allowlist, manifest narrowed, SHA-256 backend, bounded parallelism, cancellable `ShellEngine`.
- Staging rule (learned on-device): anything `install-write` (UID 1000) must read is **755/711 dirs + 644 files**. 700/600 staging = "Unable to open file" = silent failed installs. Private-only data (targets, caches) stays 600/700.
- Phase A: `DeviceManager` quoting + perm allowlist, installer zip-slip guard + `while read` loops + quote-escaping, guarded `rm -rf`, `SETPERM`/`CHANID` pkg validation (sync, tracked).

## Phase B done
- Dead deps removed: `graphics-path`, `ksp`/`serialization` plugin aliases. `icons-extended` KEPT — compiler-verified: most `Icons.Default.*` used (Storage, Palette, SystemUpdate, DeleteSweep, …) resolve from the extended artifact in this BOM. Coil 2.7.0 kept intentionally (see below).
- `versionCode`: static bump per release, verified against the engine version gate in `BackendInstaller`. Releases are built by GitHub Actions on a `v*` tag — see [SETUP.md](SETUP.md).

## Split in progress
- `FlashOps` created — a validated facade over `FlashManager` and the shell.
- Preferences are still read directly from `SharedPreferences` at the call sites; there is no repository layer to migrate to.

## Release checklist
- [ ] Tests pass: `sh gradlew testDebugUnitTest` and `cd engine && cargo test`
- [ ] Bump `versionCode` in `app/build.gradle.kts` on every release (backend engine version gate in `BackendInstaller` compares it).
- [ ] Coil2 (`io.coil-kt:coil-compose:2.7.0`) kept intentionally; Coil3 migration (`io.coil-kt.coil3:coil-compose:3.6.x`, `coil.compose` -> `coil3.compose`) is a separate UI-regression task.
- [ ] `libsu 6.0.0` resolves through JitPack (see `settings.gradle.kts`): pin a commit SHA and enable dependency verification before calling the dependency set stable.
