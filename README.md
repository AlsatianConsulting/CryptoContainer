# CryptoContainer (Android 14+)

CryptoContainer is an Android app for working with VeraCrypt containers and AESCrypt files on stock, unrooted Android 14+ devices.

It supports local, on-device workflows for:
- creating VeraCrypt containers
- opening standard and hidden VeraCrypt containers
- browsing, adding, extracting, renaming, copying, cutting, pasting, sharing, and deleting container contents
- encrypting files with AESCrypt
- decrypting AESCrypt files back to user-selected folders

## Quick Start

### Open an existing VeraCrypt container
1. Open the `VeraCrypt` tab.
2. Set `Container File` with `Pick Container` or by opening a `.hc` file from another app.
3. Enter `Password`.
4. Enter `PIM (optional)` if your volume uses one.
5. Add `Keyfiles (optional)` if your volume requires them.
6. Tap `Open`.
7. When the `Current Container` card appears, tap `Explore Container`.

### Create a new VeraCrypt container
1. Open the `VeraCrypt` tab.
2. Tap `Create Volume`.
3. Tap `Choose Output File` and select where the new container will be written.
4. Choose `Standard` or `Hidden`.
5. Enter the size, password, and optional PIM.
6. Choose the file system, algorithm, and hash.
7. If creating a hidden volume, fill in the hidden-volume section.
8. Tap `Create`.

### Encrypt a file with AESCrypt
1. Open the `AESCrypt` tab.
2. Tap `Encrypt File`.
3. Pick the input file.
4. Pick the output folder.
5. Enter the password and confirmation password.
6. Set the output filename if needed.
7. Tap `Encrypt`.

### Decrypt an AESCrypt file
1. Open the `AESCrypt` tab.
2. Tap `Decrypt File`.
3. Pick the `.aes` file.
4. Pick the output folder.
5. Enter the password.
6. Tap `Decrypt`.
7. On success, use `Open File` or `Open Folder` from the result panel.

## VeraCrypt Guide

### VeraCrypt Main Screen
- `Container File`: path or URI of the container to open.
- `Password`: password for standard or hidden open.
- `PIM (optional)`: optional VeraCrypt PIM.
- `Keyfiles (optional)`: add one or more VeraCrypt keyfiles.
- `Open`: tries the entered credentials against standard and hidden headers.
- `Pick Container`: opens Android file picker for the container file.
- `Read-only`: forces a read-only open attempt.
- `Close`: closes the currently mounted container.
- `Create Volume`: opens the volume creation dialog.

### Current Container Card
When a container is open, the app shows:
- `Name`
- `Type` (`Standard` or `Hidden`)
- `Explore Container`
- `Close Container`

### VeraCrypt Explorer Buttonology
- Top-left back arrow: leaves the explorer window.
- Top-right list icon: list view.
- Top-right grid icon: grid view.
- `Location` menu (`...`):
  - `Up`
  - `Refresh`
  - `Add Files`
  - `Add Folder`
  - `New Folder`
  - `Paste Here`
  - `Import Shared Here`
  - `Cancel Shared Import`
  - `Clear Clipboard`
- Item `...` menu:
  - files: `Open`, `Select`, `Rename`, `Copy`, `Cut`, `Edit In Place`, `Extract`, `Share`, `Delete`
  - folders: `Open`, `Select`, `Rename`, `Copy`, `Cut`, `New Folder Here`, `Paste Here`, `Extract`, `Import Shared Here`, `Delete`
- Selection bar `...` menu:
  - `Open` for a single selection
  - `Edit In Place` for a single file selection
  - `Rename` for a single selection
  - `Copy`
  - `Cut`
  - `Extract`
  - `Share`
  - `Delete`
  - `Select All`
  - `Clear Selection`

### Share Into An Open VeraCrypt Container
1. Share a file from another app to `CryptoContainer`.
2. Choose `Share Into Open VeraCrypt Container` if a container is already mounted.
3. The VeraCrypt explorer opens.
4. Navigate to the destination folder.
5. Use `Import Shared Here` from the location menu or folder menu.

If no container is mounted, the app shows:
- `This Action Requires Mounting A VeraCrypt Container`

## AESCrypt Guide

### AESCrypt Main Screen
- `Encrypt File`: opens the encryption dialog.
- `Decrypt File`: opens the decryption dialog.

### Encrypt Dialog Buttonology
- `Pick File To Encrypt`: choose the source file.
- `Pick Encrypt Output Folder`: choose the destination folder.
- `Encrypt password`: password field with show/hide eye.
- `Confirm encrypt password`: confirmation field.
- `Encrypted output filename`: name of the `.aes` output.
- `Encrypt`: start encryption.
- `Copy Encrypt Password`: copies the current encrypt password to clipboard and auto-clears after 30 seconds.
- Busy dialog `Cancel`: cancels encryption in progress.

### Decrypt Dialog Buttonology
- `Pick File To Decrypt`: choose the encrypted input file.
- `Pick Decrypt Output Folder`: choose the destination folder.
- `Decrypt password`: password field with show/hide eye.
- `Close`: dismiss the dialog.
- `Decrypt`: start decryption.
- Success panel:
  - `Open File`
  - `Open Folder`
- Busy dialog `Cancel`: cancels decryption in progress.

### Sharing Files Into AESCrypt
- Share one file to `CryptoContainer`, then choose:
  - `Encrypt Using AESCrypt`
  - `Decrypt Using AESCrypt`
- If multiple files are shared to `Encrypt Using AESCrypt`, the app first creates a ZIP and then encrypts the ZIP.

## External Open / Share Behavior
- Opening a `.hc` file routes into `VeraCrypt` and populates `Container File`.
- Opening a `.aes` file routes into the AESCrypt decrypt dialog.
- Generic Android share offers:
  - `Encrypt Using AESCrypt`
  - `Decrypt Using AESCrypt`
  - `Mount as VeraCrypt Container`
- If a VeraCrypt container is already open, the share chooser also offers:
  - `Share Into Open VeraCrypt Container`

## Detailed Documentation
- Architecture: `docs/architecture.md`
- Play Store listing copy: `docs/play-store-listing.md`
- Privacy policy: `docs/privacy-policy.md`
- Google Play data safety notes: `docs/data-safety.md`
- Release notes: `docs/release-notes-1.0.0.md`
- GitHub wiki source pages: `wiki/`

## Build
- Native third-party sources are vendored in this repo; no submodule initialization is required.
- Build prerequisites:
  - Android SDK Platform 34
  - Android Build Tools compatible with AGP/Gradle in this repo
  - Android NDK `29.0.14206865`
  - Java 17
- Configure the Android SDK path using one of:
  - `local.properties` with `sdk.dir=/path/to/Android/Sdk`
  - `ANDROID_HOME=/path/to/Android/Sdk`
- Configure release signing using one of:
  - `keystore.properties` in the repo root
  - environment variables
- Supported signing properties:
  - `storeFile` or `CC_KEYSTORE_FILE`
  - `storePassword` or `CC_KEYSTORE_PASSWORD`
  - `keyAlias` or `CC_KEY_ALIAS`
  - `keyPassword` or `CC_KEY_PASSWORD`
- For `PKCS12` keystores, `keyPassword` usually matches `storePassword`.
- Example debug build:
  - `ANDROID_NDK_ROOT=/path/to/ndk ./gradlew :app:assembleDebug`
- Example release build:
  - `ANDROID_NDK_ROOT=/path/to/ndk ./gradlew :app:assembleRelease`
  - `ANDROID_NDK_ROOT=/path/to/ndk ./gradlew :app:bundleRelease`
- Unit tests:
  - `./gradlew :app:testDebugUnitTest`

## Licensing Considerations
- VeraCrypt licensing obligations apply to the bundled port.
- NTFS and exFAT libraries may carry GPL/LGPL obligations depending on packaging and linkage strategy.
- Distribution packages should include the required notices and source/binary offer where applicable.
