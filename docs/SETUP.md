# Setting up an automated release

The release workflow builds and publishes the APKs when you push a `v*` tag.
This page covers the one-time setup it needs.

## 1. Repository secrets

The signing key is **not** in the repository, and must never be: anyone with the
repo could then sign a build the platform accepts as an update to every real
install. Add four secrets under
**Settings → Secrets and variables → Actions → New repository secret**:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 keystore/droidvault-release.jks` |
| `KEYSTORE_PASSWORD` | your keystore password |
| `KEYSTORE_ALIAS` | the key alias (`droidvault`) |
| `KEY_PASSWORD` | the key password |

The workflow refuses to run if `KEYSTORE_BASE64` is missing, rather than falling
back to the debug key and publishing an APK that cannot update a real install.

### Verifying the secrets before you rely on them

```sh
base64 -w0 keystore/droidvault-release.jks > /tmp/k.b64
# copy that into KEYSTORE_BASE64, then check it round-trips:
base64 -d /tmp/k.b64 | sha256sum
sha256sum keystore/droidvault-release.jks
```

The two hashes must match. A secret that was pasted with a line break in it
decodes to a corrupt keystore and fails at signing, which is a confusing way to
discover a copy-paste problem.

## 2. Publishing a release

```sh
git tag v1.0.0
git push origin v1.0.0
```

The tag is the single source of truth for what gets built, so a release cannot
end up built from a different commit than the tag says. You can also run the
workflow manually from the Actions tab and give it an existing tag.

## 3. What gets published

Two APKs, one per architecture, each carrying only its own native engine:

- `arm64-v8a-release.apk` — most modern phones
- `armeabi-v7a-release.apk` — older 32-bit devices

The build also asserts, before publishing, that each APK is signed with the
release key and contains the `zapdos` and `droidvault_engine` libraries for its
own ABI. A split that lost its native library would install fine and then fail
on first run, as root, on someone's phone.

## 4. Re-running a failed release

Push a new tag, or run the workflow manually with the same tag. Assets are
replaced rather than duplicated.

Note that a release build is unsigned-per-run but **version-specific**: Android
refuses to install a build whose `versionCode` is lower than the one installed.
Bump it in `app/build.gradle.kts` for every release.