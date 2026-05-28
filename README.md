# CryptoContainer (Android 14+)

CryptoContainer is an Android 14+ app for people who need VeraCrypt containers and AESCrypt files on a stock, unrooted phone. It gives desktop VeraCrypt users a practical local workflow on Android: choose a file, enter the needed credentials, and work with the encrypted contents inside the app. Nothing depends on a cloud account or an internet connection.

## What It Does

### VeraCrypt Container Management

- **Open existing containers**: open standard or hidden VeraCrypt `.hc` containers with password, optional PIM, and optional keyfiles.
- **Create new containers**: create standard or hidden volumes with `FAT`, `exFAT`, or `NTFS` filesystem; choose encryption algorithm (AES, Serpent, Twofish, or cascades) and header hash (SHA-512, Whirlpool).
- **In-app file explorer**: browse mounted container contents with list and grid views; copy, cut, paste, rename, delete, create folders, add files, add folder trees, extract, share, and edit-in-place.
- **Multi-select operations**: select multiple files and folders simultaneously for batch copy, cut, extract, share, or delete.
- **Hidden volume support**: create and open hidden volumes; outer writable open includes optional hidden-volume protection.
- **Read-only mode**: force a read-only open to prevent accidental writes.
- **Provider-backed access**: expose mounted container contents to other Android apps through the Android document provider API while the container is open.

### AESCrypt File Operations

- **Encrypt files**: encrypt any file to AESCrypt-compatible `.aes` format using a password.
- **Decrypt files**: decrypt `.aes` files to a user-chosen output folder; restore original filename from metadata when available.
- **Multi-file encryption**: share multiple files to CryptoContainer and the app automatically ZIPs them before encrypting.
- **Result panel**: after decryption, open the output file or output folder directly from the result panel.

### Android Integration

- **Direct-open**: opening a `.hc` file from any Android app routes into the VeraCrypt screen with the container field pre-filled.
- **Direct-open `.aes`**: opening a `.aes` file routes into the AESCrypt decrypt dialog.
- **Share target**: CryptoContainer appears in the Android share sheet for any file; share targets include `Encrypt Using AESCrypt`, `Decrypt Using AESCrypt`, `Mount as VeraCrypt Container`, and (when a container is open) `Share Into Open VeraCrypt Container`.

### Session Security

- Passwords are held in memory only for the current session.
- Clipboard secrets copied by the app are auto-cleared after 15 seconds.
- Open containers are closed when the app session ends.
- An inactivity timer (10 minutes) automatically closes the open container and clears session state.

## How It Does It

**Language and framework**: Kotlin with Jetpack Compose for the UI layer. Native cryptographic and filesystem code is written in C/C++ and exposed via JNI.

**Architecture layers**:

| Layer | Components |
|---|---|
| UI | `MainActivity`, `VeraCryptScreen`, `AESCryptScreen`, `VcBrowser`, `CreationWizard` (Jetpack Compose) |
| View model / controller | `ShareViewModel`, `MountController` |
| Managers | `VeraCryptManager`, `AESCryptManager` |
| Repository | `VeraCryptRepo` / `VeraCryptRepository` |
| Service | `MountService` (foreground, `dataSync` type) |
| Provider | `VolumeProvider` (Android `DocumentsProvider`) |
| Native (JNI) | `CryptoNative.kt` + `cryptocore.cpp` (bundled VeraCrypt core, libntfs-3g, libexfat, FatFs) |
| Utilities | `ClipboardWatcher`, `InactivityTimer`, `FileHelpers`, `StringSanitizer` |

**Source layout**:

```
app/src/main/
  java/dev/alsatianconsulting/cryptocontainer/
    MainActivity.kt            — entry point; handles share/open intents and tab navigation
    CryptoContainerApp.kt      — Application subclass; Compose root
    MountController.kt         — singleton; owns VeraCryptManager, AESCryptManager, InactivityTimer
    ui/                        — Compose screen files
    manager/                   — VeraCryptManager, AESCryptManager
    repo/                      — VeraCryptRepo (JNI bridge), VeraCryptRepository (interface)
    service/                   — MountService (foreground service)
    provider/                  — VolumeProvider (DocumentsProvider)
    crypto/                    — AESCrypt (pure-Kotlin AESCrypt v3 implementation)
    model/                     — VolumeCreateOptions, VcEntry, VcFsInfo
    util/                      — ClipboardWatcher, InactivityTimer, FileHelpers, StringSanitizer
    jni/                       — CryptoNative (JNI declarations)
  cpp/
    cryptocore.cpp             — native entry points (create/open/close/list/extract/add/delete)
    CMakeLists.txt             — native build
    vc_config.h, vc_compat.h   — VeraCrypt porting headers
    exfat_mkfs_bridge.c/h      — exFAT format bridge
    vc_stubs.cpp               — VeraCrypt platform stubs
  AndroidManifest.xml
```

**Entry points**:

- `android.intent.action.MAIN` + `LAUNCHER` category → `MainActivity`
- `android.intent.action.SEND` / `SEND_MULTIPLE` with `*/*` MIME → share handling in `MainActivity`
- `android.intent.action.VIEW` for `*.hc` → VeraCrypt open flow
- `android.intent.action.VIEW` for `*.aes` → AESCrypt decrypt flow

**Data flow (open a container)**:

1. User selects container via SAF picker or share/open intent
2. `VeraCryptScreen` calls `VeraCryptManager.open()` via `MountController`
3. `VeraCryptRepo.open()` converts `CharArray` password to `ByteArray` via `charArrayToUtf8Bytes()`, zeroed in `finally`
4. JNI call into native `cryptocore.cpp` tries standard header, then hidden header
5. On success, mount state stored in app memory; `MountService` foreground service started
6. `VolumeProvider` begins serving document URIs for the open container
7. `InactivityTimer` starts a 10-minute countdown; reset on each user action

## How to Install

### Prerequisites

- **Git**: `sudo apt install git` (Ubuntu/Debian) · `brew install git` (macOS)
- **JDK 17**:
  ```bash
  sudo apt install openjdk-17-jdk   # Ubuntu/Debian
  brew install openjdk@17           # macOS
  ```
- **Android SDK + NDK** — install via Android Studio, or command-line tools:
  ```bash
  # Download commandlinetools from https://developer.android.com/studio#command-tools
  mkdir -p ~/Android/Sdk/cmdline-tools
  unzip commandlinetools-*.zip -d ~/Android/Sdk/cmdline-tools/latest
  export ANDROID_HOME=~/Android/Sdk
  export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools
  sdkmanager --licenses
  sdkmanager "platform-tools" "platforms;android-36" "build-tools;35.0.0"
  sdkmanager "ndk;29.0.14206865"
  export ANDROID_NDK_ROOT=$ANDROID_HOME/ndk/29.0.14206865
  ```
- **ADB** — included with platform-tools above; or: `sudo apt install adb`
- **Gradle** — bundled via `./gradlew`; no separate install needed

### Recommended Installation

Install the pre-built APK from the GitHub releases page.

```
adb install CryptoContainer-1.0.apk
```

A signed release APK is included in `release-assets/CryptoContainer-1.0.apk`.

### Manual Installation (build from source)

1. Clone the repository. Native third-party sources are vendored; no submodule initialization is required.
2. Set the Android SDK path:
   - Create `local.properties` in the repo root with `sdk.dir=/path/to/Android/Sdk`, or
   - Set `ANDROID_HOME=/path/to/Android/Sdk`

3. Build a debug APK:
   ```
   ANDROID_NDK_ROOT=/path/to/ndk ./gradlew :app:assembleDebug
   ```

4. Install:
   ```
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

### Files to Edit or Create

| File | Purpose |
|---|---|
| `local.properties` | Set `sdk.dir=` to your Android SDK path |
| `keystore.properties` | Release signing (see `keystore.properties.example`) |

### Additional Install Modes

**Release APK build**:
```
ANDROID_NDK_ROOT=/path/to/ndk ./gradlew :app:assembleRelease
```

**Release AAB (Play bundle)**:
```
ANDROID_NDK_ROOT=/path/to/ndk ./gradlew :app:bundleRelease
```

Release signing properties (`keystore.properties` or environment variables):

| Property key | Environment variable | Purpose |
|---|---|---|
| `storeFile` | `CC_KEYSTORE_FILE` | Path to keystore file |
| `storePassword` | `CC_KEYSTORE_PASSWORD` | Keystore password |
| `keyAlias` | `CC_KEY_ALIAS` | Key alias |
| `keyPassword` | `CC_KEY_PASSWORD` | Key password (defaults to `storePassword` if absent) |

For PKCS12 keystores, `keyPassword` usually matches `storePassword`.

## How to Use It

### First Run

1. Launch CryptoContainer. Two tabs appear: `VeraCrypt` and `AESCrypt`.
2. Grant notification permission when prompted (required for the foreground service that keeps containers open).
3. No additional setup is required.

### Opening an Existing VeraCrypt Container

1. Open the `VeraCrypt` tab.
2. Tap `Pick Container` (or open a `.hc` file from another app's file manager).
3. Enter `Password`.
4. Enter `PIM (optional)` if your volume uses one. Leave blank for PIM 0.
5. Add `Keyfiles (optional)` if your volume requires them.
6. Tap `Open`.
7. When the `Current Container` card appears, tap `Explore Container`.

### Creating a New VeraCrypt Container

1. Open the `VeraCrypt` tab.
2. Tap `Create Volume`.
3. Tap `Choose Output File` and select a destination.
4. Choose `Standard` or `Hidden`.
5. Enter size, password, optional PIM, and optional keyfiles.
6. Choose filesystem (`FAT`, `exFAT`, `NTFS`), algorithm, and hash.
7. For hidden volumes, fill in the hidden-volume section.
8. Tap `Create`.

### Encrypting a File with AESCrypt

1. Open the `AESCrypt` tab.
2. Tap `Encrypt File`.
3. Pick the input file.
4. Pick the output folder.
5. Enter and confirm the password.
6. Set the output filename if needed.
7. Tap `Encrypt`.

### Decrypting an AESCrypt File

1. Open the `AESCrypt` tab.
2. Tap `Decrypt File`.
3. Pick the `.aes` file.
4. Pick the output folder.
5. Enter the password.
6. Tap `Decrypt`.
7. Use `Open File` or `Open Folder` from the result panel.

### Sharing Files Into CryptoContainer

Share one or more files from any Android app to `CryptoContainer`, then choose:
- `Encrypt Using AESCrypt`
- `Decrypt Using AESCrypt`
- `Mount as VeraCrypt Container`
- `Share Into Open VeraCrypt Container` (only appears when a container is already mounted)

## Configuration

CryptoContainer does not have a traditional settings screen. All operational options are presented inline in the relevant workflow screens.

| Option | Location | Description |
|---|---|---|
| Read-only mount | VeraCrypt main screen checkbox | Forces a read-only open attempt |
| Volume type | Create Volume dialog | Standard or Hidden |
| Filesystem | Create Volume dialog | FAT, exFAT, or NTFS |
| Encryption algorithm | Create Volume dialog | AES, Serpent, Twofish, or cascades |
| Header hash | Create Volume dialog | SHA-512, Whirlpool |
| Keyfiles | VeraCrypt main screen | Choose, change, or clear keyfiles per session |
| PIM | VeraCrypt main screen | Optional per-volume VeraCrypt PIM |
| Output filename | AESCrypt encrypt dialog | Name of the `.aes` output file |

**Inactivity timeout**: containers are automatically closed after 10 minutes of inactivity. This value is set in `MountController.kt` (`timeoutMs = 10 * 60 * 1000L`) and is not currently configurable from the UI.

**Clipboard clear delay**: sensitive values copied to clipboard are cleared after 15 seconds. This is set in `ClipboardWatcher.kt` and is not currently configurable from the UI.

## Data Storage and Exports

| Data | Location | Notes |
|---|---|---|
| VeraCrypt containers | User-selected location (SAF) | `.hc` files; created and managed by the user |
| AESCrypt output | User-selected folder (SAF) | `.aes` encrypted files |
| AESCrypt decrypted output | User-selected folder (SAF) | Plaintext files |
| Temporary/staging files | App-private cache (`context.cacheDir`) | Removed after operation completes or session ends |
| Session credentials | In-memory only | Passwords, PIM, keyfiles; never persisted to disk |
| App preferences | Android DataStore (app-private) | Used by Compose state; no sensitive data stored |

**Privacy implications**: no data is transmitted off-device. VeraCrypt containers and AESCrypt files are stored only where the user chooses to save them. Session credentials are never written to disk and are cleared when the container is closed or the app session ends.

## Screenshots

CryptoContainer enforces `FLAG_SECURE` on all windows, which prevents Android's screenshot system (including ADB `screencap`) from capturing the app's content. This is intentional — a security-focused container app should never allow the OS or connected tools to silently capture plaintext file listings or container contents.

To take screenshots for documentation or Play Store submissions, use a physical device with screen recording hardware, a device-level screen capture tool with root access, or the Android emulator (which does not enforce `FLAG_SECURE`).

Screenshots are stored in `docs/screenshots/` when available. Key screens to document:

| Screen | Filename |
|---|---|
| VeraCrypt tab — container open form | `docs/screenshots/veracrypt-main.png` |
| VeraCrypt tab — Create Volume | `docs/screenshots/veracrypt-create.png` |
| Container Explorer — file list | `docs/screenshots/explorer-list.png` |
| AESCrypt tab — encrypt form | `docs/screenshots/aescrypt-encrypt.png` |
| AESCrypt tab — decrypt form | `docs/screenshots/aescrypt-decrypt.png` |
## About

CryptoContainer does not have a dedicated About screen. The app name, version, and publisher information are defined in `app/build.gradle.kts` and `app/src/main/res/values/strings.xml`.

The standard Alsatian Consulting About content for this app is:

```
CryptoContainer
Version 1.1

(c) Alsatian Consulting, LLC 2026

GitHub:
https://github.com/AlsatianConsulting

Website:
https://www.alsatian.consulting
```

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `This Action Requires Mounting A VeraCrypt Container` | Tried to import shared files when no container is open | Open the VeraCrypt tab, open a container, then retry the share flow |
| `Open failed: volume decrypted but no supported filesystem was detected.` | Header decrypted but filesystem inside is unsupported, damaged, or the wrong password/PIM was used | Verify password and PIM; check that the container is a valid VeraCrypt volume |
| Container opens read-only unexpectedly | NTFS safety check forced read-only fallback (e.g., unclean NTFS state) | This is expected behavior; use a clean NTFS container or explicitly tick Read-only |
| AESCrypt `Decrypt failed` | Wrong password, invalid `.aes` file, insufficient storage, or output folder permission denied | Check password, verify the file is a valid AESCrypt file, check free space and folder permissions |
| `Edit In Place` changes are not saved | The external editor app does not support Android document provider write-back | Extract the file, edit it externally, and re-import it |
| `Share Into Open VeraCrypt Container` does not appear in share sheet | No container is currently mounted | Open a container first, then re-share the file |
| Build fails with NDK not found | `ANDROID_NDK_ROOT` is not set or points to the wrong NDK | Set `ANDROID_NDK_ROOT=/path/to/ndk/29.0.14206865` before running Gradle |

## Testing and Validation

Detailed test procedures, connected-device playbooks, serial numbers, and release-gate notes are intentionally kept out of this README. Use the repository test scripts, CI configuration, or project continuity notes for validation details, and avoid committing device-specific evidence or identifiers to public documentation.

## Project Layout

```
CryptoContainer-dev/
  app/
    build.gradle.kts           — app module build; versionCode=3, versionName=1.1
    src/main/
      AndroidManifest.xml
      java/dev/alsatianconsulting/cryptocontainer/
        MainActivity.kt
        CryptoContainerApp.kt
        MountController.kt
        ui/                    — Compose screens (VeraCryptScreen, AESCryptScreen, VcBrowser, CreationWizard)
        manager/               — VeraCryptManager, AESCryptManager
        repo/                  — VeraCryptRepo, VeraCryptRepository
        service/               — MountService, MountNotificationChannel
        provider/              — VolumeProvider
        crypto/                — AESCrypt
        model/                 — VolumeCreateOptions, VcEntry, VcFsInfo
        util/                  — ClipboardWatcher, InactivityTimer, FileHelpers, StringSanitizer
        jni/                   — CryptoNative
      cpp/                     — Native C/C++ (cryptocore.cpp, CMakeLists.txt, VeraCrypt stubs, exFAT bridge)
    src/test/                  — JVM/Robolectric unit tests
    src/androidTest/           — On-device instrumentation tests
  third_party/                 — Vendored: VeraCrypt, ntfs-3g, exfat, FatFs
  docs/
    architecture.md
    privacy-policy.md
    play-store-listing.md
    security-audit.md
    data-safety.md
    release-notes-1.0.0.md
    wiki/                      — docs/wiki/ (this repo's docs wiki pages)
  wiki/                        — GitHub wiki source pages
  continuity/                  — Security audit narrative and going-forward rules
  scripts/                     — Build and validation scripts
  release-assets/              — CryptoContainer-1.0.apk
  build.gradle.kts             — Root build
  settings.gradle.kts
  gradle.properties
  LICENSE                      — Apache License 2.0 (original code)
  LICENSES/                    — Third-party license copies
  NOTICE                       — Multi-license notice
```

## Known Limitations

- **No traditional settings screen**: operational options are inline in workflow screens; the inactivity timeout (10 min) and clipboard clear delay (15 s) are not user-configurable from the UI.
- **ARM64 only**: the native library is built for `arm64-v8a` only; x86/x86_64 emulators are not supported.
- **Android 14+ required**: `minSdk = 34`. Older Android versions are not supported.
- **Compose TextField password storage**: Jetpack Compose's text field API requires `String`; passwords pass through `String` objects at the UI layer before being converted to `CharArray` at the first non-UI boundary. This is an acknowledged platform limitation (see `continuity/03-items-reviewed-not-changed.md`).
- **Edit In Place depends on external app support**: `Edit In Place` writes through the Android document provider; whether changes are saved back depends on the external editor's support for provider write-back.
- **No FUSE export path**: the app uses Android's `DocumentsProvider` model rather than a FUSE kernel mount, so it is not identical to a desktop-style mount path. Some third-party apps that expect a filesystem path may behave differently.
- **No x86/x86_64 emulator support**: native libraries are arm64 only.

## Privacy and Security

**What stays on device**:
- All container and file operations run locally.
- No analytics, advertising, or crash reporting SDKs are included.
- No account or internet connection is required.

**What leaves the device**:
- Nothing, unless the user explicitly shares a file to another app using Android share actions. After a file is shared to another app, that app's privacy practices apply.

**Sensitive data handling**:
- Passwords are passed as `CharArray` throughout the app (never `String` after the UI boundary) and are zeroed in `finally` blocks immediately after use.
- Session credentials are never written to disk.
- Clipboard entries containing sensitive values are cleared after 15 seconds with a neutral (empty) label.
- `android:allowBackup="false"` is set in the manifest; app data is excluded from Android backups.

**Permissions used**:

| Permission | Purpose |
|---|---|
| `POST_NOTIFICATIONS` | Show foreground service notification while a container is open |
| `FOREGROUND_SERVICE` | Run `MountService` as a foreground service |
| `FOREGROUND_SERVICE_DATA_SYNC` | Foreground service type for data operations |

No location, contacts, microphone, camera, or network permissions are requested.

**Third-party components**: VeraCrypt core, ntfs-3g, libexfat, and FatFs are bundled as vendored native code. These are local cryptographic/filesystem libraries and are not remote data collectors. See `NOTICE` and `LICENSES/` for license details.

## Release Notes

### Version 1.1 (versionCode 3)

Current release. `versionName = "1.1"`, `versionCode = 3` in `app/build.gradle.kts`.

PLACEHOLDER--No release-notes-1.1.md file was found in docs/. The docs/ directory contains release-notes-1.0.md and release-notes-1.0.0.md but no notes file for version 1.1. Add docs/release-notes-1.1.md and link it here.--END PLACEHOLDER

### Version 1.0.0 (versionCode 1)

See `docs/release-notes-1.0.0.md`.

- First public release of CryptoContainer
- Create and open standard or hidden VeraCrypt volumes
- Support for `FAT`, `exFAT`, and `NTFS`
- In-app VeraCrypt explorer with file and folder actions
- Multi-select and multi-file import workflows
- AESCrypt encrypt and decrypt support
- Direct-open handling for `.hc` and `.aes`
- Android share integration for VeraCrypt and AESCrypt
- Signed release APK and Play bundle support

**Release artifact**: `release-assets/CryptoContainer-1.0.apk`

## License

Copyright Alsatian Consulting, LLC

Original project code is licensed under the Apache License 2.0. See [LICENSE](LICENSE).

This repository also includes bundled third-party components (VeraCrypt, ntfs-3g, libexfat, FatFs) under their own upstream licenses. See [NOTICE](NOTICE) and the [LICENSES/](LICENSES/) directory for details.

Distribution packages must include the required notices and source/binary offers where applicable for GPL-licensed components.
