<!--suppress HtmlDeprecatedAttribute -->
<div align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.webp" alt="Droid Vault Icon" width="96"/>
  <h1>⚡ Droid Vault</h1>
  <p><b>Offline, root-based backup, restore and ROM migration for Android.</b></p>

  [![Author](https://img.shields.io/badge/Author-phoenix--1708-blue?style=for-the-badge)]()
  [![Version](https://img.shields.io/badge/version-1.0.0-informational?style=for-the-badge)]()
  ![Android](https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)
  ![Root](https://img.shields.io/badge/Root-ff0000?style=for-the-badge&logo=superuser&logoColor=white)
  ![Magisk](https://img.shields.io/badge/Magisk-8A2BE2?style=for-the-badge&logo=magisk&logoColor=white)
  ![KernelSU](https://img.shields.io/badge/KernelSU-000000?style=for-the-badge&logo=linux&logoColor=white)
  ![APatch](https://img.shields.io/badge/APatch-FF6B00?style=for-the-badge&logo=apatch&logoColor=white)

  <p><b>⚠️ Work in progress — expect rough edges.</b></p>
</div>

<br>

Droid Vault backs up and restores **apps and their private data**, **SMS/MMS**,
**call logs**, **contacts**, **Wi-Fi, wallpaper, Bluetooth** and folders you
choose — then gets out of the way. It exists to make moving between ROMs (and
between phones) survivable, with no cloud account and **no internet permission
at all**.

Everything stays on the device, in one folder you control.

---

## 📑 Contents

- [Status](#-status--work-in-progress)
- [Why Droid Vault](#-why-droid-vault)
- [Features](#-features)
- [Requirements](#-requirements)
- [Installation](#-installation)
- [Building from source](#-building-from-source)
- [The vault](#-the-vault)
- [How it works](#-how-it-works)
- [Safety guarantees](#-safety-guarantees)
- [Credits](#credits)
- [Disclaimer](#-disclaimer)
- [Author](#-author)

---

## 🚧 Status: work in progress

**This project is actively being developed and is not finished.**

- There is **no stable release** and no release cadence yet. Treat any build as
  a preview.
- Features are added and changed without notice, and **the backup format may
  still change**. Keep your own copy of anything you care about.
- Testing has been concentrated on **one device** (a Xiaomi POCO F6 / HyperOS)
  on **arm64-v8a**. Other devices, ROMs and ABIs are untested — the project
  ships `armeabi-v7a` and `x86_64` builds, but do not expect them to be
  exercised.
- Bugs are expected. Please [open an issue](https://github.com/phoenix-1708/DroidVault/issues)
  with what you did, what you expected, and what happened. **Field reports are
  the most valuable thing you can contribute.**

Every bug fixed since the fork was driven by a field report rather than a
theory, and each one is recorded with the failure that motivated it.

---

## 💡 Why Droid Vault?

There are plenty of great backup apps out there, and they all do their best.
What I wanted was a **single, unified, lightweight app** that handles everything
actually needed when hopping between ROMs — without the unnecessary bloat.

I didn't want three separate apps to back up my data, manage my partitions, and
debloat. Droid Vault combines a modern Kotlin/Jetpack Compose UI with a **Rust
engine** that runs as root, so the heavy work is native and the vault stays on
the device.

It started as a fork of [ROM Shifter](https://github.com/ShivamXD6/ROM-Shifter)
by **ShivamXD6** — see [Credits](#credits) — and has been developed a long way
past it since.

---

## ✨ Features

### ⚡ Flash

*Everything needed to flash, install, and restore device partitions.*

- **Auto Flash Wizard** — guided flashing
- **Install Batch Apps** — batch sideload with a verifier-disable consent gate
- **Backup / Restore / Flash Partitions** — partition imaging and restore
- **ORS generation** — generate and apply recovery scripts

### 🔄 Migrate

*Move apps and personal or ROM data between ROMs — or between phones.*

- **Backup Apps** — full private data, not just APKs. Selectable per app, per
  component: **App · Data · Permissions · Media & OBB · Android ID**
- **Restore Apps** — the picker only ever offers what the archive actually holds
- **Device Data** — SMS/MMS, call logs, contacts, Wi-Fi, wallpaper, Bluetooth
- **Custom folders** — back up anything else you nominate
- **Manage Backups** — with a recoverable trash, so a delete is a rename first
- **Export / Import** — move the whole vault to another phone as a single file

> App caches are not backed up. Apps recreate their own caches when needed.

### 🛠️ Tools

*Utilities for a rooted system.*

- **Debloat / Restore Apps** — remove or bring back system apps
- **Systemize / De-Systemize** — run system apps as regular apps and back

### 🤖 Auto

*Scheduled backup and restore, running headless.*

- Named **configurations** — a selection of apps plus device data plus a schedule
- Trigger types: **once · daily · weekly · interval**
- External automation support via a signature-gated trigger activity, so another
  app or script can start a run — see [docs/AUTOMATION.md](docs/AUTOMATION.md)
- Runs even when the app is not open
- State survives a reinstall: the vault holds a durable `automation-state.json`
  that is re-imported automatically
- External automation support — see [docs/AUTOMATION.md](docs/AUTOMATION.md)

### ⚙️ Settings

- Backup **size estimate** that reflects what a run will actually rewrite
- Vault health check, and per-app verification before restore
- Preferences, consent gates, and scheduling options

---

## 📋 Requirements

| | |
|---|---|
| **Root** | Required. **Magisk**, **KernelSU**, or **APatch** (and forks) |
| **Android** | 10 (API 29) and newer |
| **Storage** | Enough free space for the backup, plus room for one working copy during export |
| **Network** | **None.** The app declares no `INTERNET` permission |

> **Root is not optional.** This app uses it to reach app private data, which no
> unrooted app can read. Only install it on a device you own and have backed up
> elsewhere.

---

## 📥 Installation

1. **Sideload the APK.** Grab it from the
   [releases page](https://github.com/phoenix-1708/DroidVault/releases) and pick the
   file for your device: `arm64-v8a-release.apk` for most modern phones,
   `armeabi-v7a-release.apk` for older 32-bit devices. There is no Play Store
   listing.
2. **Open the app** and grant root access when prompted.
3. **Complete the setup wizard** and choose your vault folder — by default
   `/storage/emulated/0/DroidVault`.

> Installing a build signed with a different key than the one already on the
> device requires uninstalling first, which **deletes the app's preferences**.
> The vault lives outside app data and is unaffected. After reinstalling, redo
> the root grant and the onboarding — Magisk's per-UID grant is dropped with the
> uninstall.

---

## 🛠️ Building from source

You need **JDK 21 or newer** to run Gradle (the project targets JDK 17 bytecode),
the **Android SDK with API 37**, and — only for the native engine — **Rust** and
the **Android NDK**.

```sh
git clone https://github.com/phoenix-1708/DroidVault.git
cd DroidVault
```

### 1. Point Gradle at your SDK

Create `local.properties` (this file is gitignored and must never be committed):

```properties
sdk.dir=/path/to/Android/sdk
```

### 2. Point the build at your NDK

The Rust engine is cross-compiled for three ABIs. The build finds the NDK in
this order, and **tells you in plain words if it cannot**:

1. `-Pdroidvault.ndkPath=/path/to/android-ndk`, or `droidvault.ndkPath=` in
   `gradle.properties`
2. `ANDROID_NDK_HOME` or `ANDROID_NDK_ROOT` in the environment
3. `ndk.dir=` in `local.properties`

```sh
export ANDROID_NDK_HOME=/path/to/android-ndk
```

> **Only the native engine needs the NDK.** Everything else — including the full
> unit test suite — builds without one.

### 3. Build

```sh
sh gradlew testDebugUnitTest     # unit tests
sh gradlew assembleDebug         # debug APK
sh gradlew assembleRelease       # release APKs, one per ABI (R8 + shrinking)
cd engine && cargo test && cargo fmt --check && cd ..
```

Use `sh gradlew`, not `./gradlew` — the wrapper is not marked executable in the
repository.

Outputs:

| Artifact | Path |
|---|---|
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` |
| Release APKs | `app/build/outputs/apk/release/app-<abi>-release.apk` |

Release builds are split per ABI (`arm64-v8a`, `armeabi-v7a`), each carrying
only its own engine.

### 4. Publishing a release

Pushing a `v*` tag builds and publishes both APKs to a GitHub Release — see
[docs/SETUP.md](docs/SETUP.md) for the one-time secrets setup.

### 5. Signing a local release

Release signing reads `keystore.properties` at the repository root, falling back
to the `KEYSTORE_PASSWORD` environment variable. **Neither is committed, and
both are gitignored.**

```properties
storeFile=keystore/droidvault-release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

With neither present the release build falls back to the **debug key** and
prints a loud warning. **Do not distribute such a build** — a debug-signed APK
cannot update a release-signed install.

### A note on the bundled `zapdos` binary

`app/src/main/jniLibs/*/libzapdos.so` is a prebuilt native binary inherited from
the upstream project. It is deliberately **not** gitignored, because it has no
source in this repository and the build cannot regenerate it. Its full
provenance is documented in [CREDITS.md](CREDITS.md).

---

## 🗄️ The vault

Everything lives in one folder — this is the thing to preserve across a ROM
flash:

```
/storage/emulated/0/DroidVault/
├── Apps/User/<label>/        per-app archives (.shift files)
├── Apps/System/<label>/
├── Device/                    Messages, CallLogs, Contacts, Wi-Fi, Wallpaper, Bluetooth, Folders
├── DroidVault.apk             the app's own APK mirror — reinstall kit
├── DroidVault.engine          engine + launcher mirrors
├── automation-state.json      durable automation state (chmod 600)
└── .trash/                    quarantine for deleted backups, recoverable for 7 days
```

---

## ⚙️ How it works

```
Jetpack Compose UI  (single activity)
        │
   MainViewModel / AutomationViewModel
        │
   ShellEngine (root shell, persistent, event stream)
        │
   droidvault-engine (Rust)  +  zapdos (zstd)
        │
   /storage/emulated/0/DroidVault/
```

The engine emits a line-based `ACTION:` / `INFO:` protocol, which the app parses
into typed events. Work is **single-flight** across the whole process: manual and
scheduled runs share one gate, so two runs can never fight over the same
archives.

---

## 🛡️ Safety guarantees

These are the rules the codebase is built around, and they are enforced by tests
where a test can enforce them:

- **Deleting is a rename.** Destructive actions move archives into `.trash`
  first. Erasing for good is a separate, explicit step.
- **A backup archive is never truncated in place.** Every writer streams to a
  temporary path and renames over the destination, so an interrupted run cannot
  destroy the last good copy.
- **Verdicts describe the work, not the plan.** A run is reported complete only
  from the engine's own per-part outcome.
- **Verify before restore.** The pre-restore gate blocks a restore whose
  archives were reported damaged, and that result survives the dialog.
- **No internet, ever.** There is no `INTERNET` permission, and adding one would
  be a deliberate breaking change.

---

## Credits

**Droid Vault is based on
[ROM Shifter](https://github.com/ShivamXD6/ROM-Shifter) by
[ShivamXD6](https://github.com/ShivamXD6) and the
[Build Bytes](https://telegram.me/buildbytes) team, licensed GPL-3.0.**

Droid Vault is a derivative work and carries the same GPL-3.0 licence. See
[CREDITS.md](CREDITS.md) for the full attribution chain.

---

## ⚠️ Disclaimer

**This project is vibe coded.**

It was built iteratively with AI assistance, and it says so plainly. That has
real consequences you should weigh before trusting it with your data:

- The code has **not** been reviewed by a professional cryptographer, security
  auditor, or Android engineer.
- It has been tested on **one device**, by **one person**, on **one ROM**.
- **Do not treat it as audited, certified, or production-ready.** It is a work in
  progress.
- It performs privileged, destructive-capable operations as **root**. Root
  access is the entire premise; bugs here have real consequences for your device
  and your data.
- **Keep an independent backup.** Do not make Droid Vault your only copy of
  anything. If you are unsure whether a device is in a state where losing your
  backups would hurt, it is.
- **No warranty.** Provided "as is", without warranty of any kind, to the
  maximum extent permitted by law. The authors are not liable for any damage
  arising from its use.

**You are responsible for what you do with your device.** Flash at your own
risk, back up first, and read what you are signing.

---

## 👤 Author

Developed and maintained by [**phoenix-1708**](https://github.com/phoenix-1708).

Distributed under the **GPL-3.0** licence — see [LICENSE](LICENSE).
Attribution for upstream and bundled components is recorded in
[CREDITS.md](CREDITS.md).

---

<div align="center">
  <sub>Based on ROM Shifter by <a href="https://github.com/ShivamXD6">ShivamXD6</a> · Droid Vault by <a href="https://github.com/phoenix-1708">phoenix-1708</a> · GPL-3.0</sub>
</div>