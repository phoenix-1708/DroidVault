# Credits & Attribution

## Upstream project

**Droid Vault is based on [ROM Shifter](https://github.com/ShivamXD6/ROM-Shifter)
by [ShivamXD6](https://github.com/ShivamXD6) and the
[Build Bytes](https://telegram.me/buildbytes) team.**

ROM Shifter is licensed GPL-3.0 and is itself the native successor to
[ROM-Shifter-Script](https://github.com/ShivamXD6/ROM-Shifter-Script). Droid
Vault is a derivative work of it, distributed under the same
[GPL-3.0](LICENSE) terms.

Since the fork, Droid Vault has been developed substantially further: a native
Rust engine, the vault export/import subsystem, scheduled automation, the
recoverable-delete quarantine, per-app component selection, and substantial
performance work.

## Bundled binaries

| Component | Origin | Licence |
|---|---|---|
| `app/src/main/jniLibs/*/libzapdos.so` | A fork of [zstd](https://github.com/facebook/zstd) by **ShivamXD6**, inherited from ROM Shifter. Prebuilt — no source in this repository. | **GPL-3.0** (via ROM Shifter) |
| [zstd](https://github.com/facebook/zstd), embedded in `libzapdos.so` | **Yann Collet & contributors** | **BSD-3-Clause OR GPL-2.0** |

`zapdos` is the compression engine the backup format depends on: every `.shift`
archive in the vault is a zstd stream written by it.

## Dependencies

| Component | Role | Licence |
|---|---|---|
| [Jetpack Compose](https://developer.android.com/jetpack/compose) | UI toolkit | Apache-2.0 |
| [libsu](https://github.com/topjohnwu/libsu) | root shell access | Apache-2.0 |
| [WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager) | scheduled runs | Apache-2.0 |
| [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | async work | Apache-2.0 |
| [Coil](https://github.com/coil-kt/coil) | image loading in the UI | Apache-2.0 |
| [org.json](https://github.com/stleary/JSON-java) | automation state encoding | Public domain |
| [Rust](https://www.rust-lang.org) + Android NDK | build-time only | MIT / Apache-2.0 |

Automation state is JSON in `SharedPreferences`, written with `org.json` —
this project does **not** use `kotlinx.serialization`.

---

If any credit here is wrong or incomplete, please open an issue.