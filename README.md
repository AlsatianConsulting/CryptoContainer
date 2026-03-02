# CryptoContainer (Android 14+)

Android app for working with VeraCrypt volumes and AESCrypt files on stock, unrooted devices. The app uses user-space filesystem access, session-only credential caching, and auto-clears copied secrets from the clipboard after 30 seconds.

Distribution target: Google Play and sideload/F-Droid style installs. Native licensing obligations still apply for bundled components such as VeraCrypt, NTFS, and exFAT libraries.

## VeraCrypt

The VeraCrypt section is focused on creating, opening, exploring, editing, and closing containers without root.

### Container Creation
- Create standard or hidden VeraCrypt volumes.
- Choose `FAT`, `exFAT`, or `NTFS` when creating a volume.
- Hidden-volume creation supports separate hidden size, password, PIM, and keyfiles.
- Supports VeraCrypt default algorithm families:
  - `AES`
  - `Serpent`
  - `Twofish`
  - cascades such as `AES-Twofish`, `AES-Twofish-Serpent`, `Serpent-AES`, `Serpent-Twofish-AES`, and `Twofish-Serpent`
- Supports standard VeraCrypt hash selections including `SHA-512` and `Whirlpool`.
- Passwords, PIM values, and keyfiles are supported during create/open flows.

### Container Open / Mount
- Open an existing VeraCrypt container from a SAF-picked file or from Android "Open with".
- Directly opening a `.hc` file routes into the VeraCrypt section and populates `Container File`.
- Open supports standard and hidden volumes.
- The app will try the entered credentials against both standard and hidden headers.
- Mounted containers show a summary card with:
  - container name
  - type (`Standard` or `Hidden`)
  - `Explore Container`
  - `Close Container`
- Mounted containers are closed when the app/session is terminated.

### Container Explorer
- Full-screen in-app explorer styled like a standard file browser.
- List and grid view modes.
- Context menu behind three-dot overflow actions.
- Multi-select support for files and folders.
- File and folder operations inside the mounted container:
  - open
  - rename
  - copy
  - cut
  - paste
  - delete
  - extract
  - share
  - create new folder
- Add one or many files from Android storage into the current folder.
- Share files out of the mounted container to other Android apps.

### Cross-App / Share Behavior
- Share files from another app into an already mounted VeraCrypt container.
- If no VeraCrypt container is mounted, the app shows:
  - `This Action Requires Mounting A VeraCrypt Container`
- Sharing into a mounted container opens the VeraCrypt explorer window for that container.
- `VolumeProvider` exposes the mounted container through Android's document APIs while open.

### Filesystem Notes
- `exFAT`: create/open/read/write/delete supported in-app.
- `FAT`: create/open/read/write/delete supported in-app.
- `NTFS`: user-space create/open/read/write supported without root.
- NTFS can fall back to read-only mode when journal/hibernation safety checks require it, and the UI indicates that state clearly.

## AESCrypt

The AESCrypt section is focused on encrypting and decrypting files through Android document pickers and Android share flows.

### Encrypt
- `Encrypt File` opens the encryption form.
- Pick a source file, output folder, password, confirmation password, and output filename.
- Sharing a file into the app with `Encrypt Using AESCrypt` opens the encrypt form and populates the input file path.
- Encrypt output is written to a user-selected folder.

### Decrypt
- `Decrypt File` opens the decryption form.
- Pick any encrypted input file, enter the password, and choose the output folder.
- Directly opening a `.aes` file routes into the AESCrypt decrypt flow.
- On successful decrypt, the form clears and the result panel shows:
  - output file name
  - saved location
  - `Open File`
  - `Open Folder`
- `Open File` prefers the external/default app for the decrypted file type rather than routing back into CryptoContainer.

### Filename Handling
- Decrypt restores the original plaintext filename when the encrypted file carries AESCrypt filename metadata.
- For staged or shared `.aes` files without embedded name metadata, the app falls back to the original shared filename with the `.aes` suffix removed.
- This preserves names such as `README.txt.aes` -> `README.txt`.

### Multiple Files
- When multiple files are shared into `Encrypt Using AESCrypt`, the app first creates a ZIP bundle, then encrypts that ZIP as one AESCrypt file.
- The default output name for this path is `shared-files.zip.aes`.

## Share / Open Integration
- Generic Android share flow offers:
  - `Mount as VeraCrypt Container`
  - `Encrypt Using AESCrypt`
  - `Decrypt Using AESCrypt`
- Opening a `.hc` file from another app auto-routes into VeraCrypt.
- Opening a `.aes` file from another app auto-routes into AESCrypt decrypt.

## Runtime Behavior
- Foreground service is used while mount state is active.
- Clipboard copies of secrets auto-clear after 30 seconds.
- Passwords are cached only for the current app session.
- No root is required for the supported flows in this app.

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
- Example debug build:
  - `ANDROID_NDK_ROOT=/path/to/ndk ./gradlew :app:assembleDebug`
- Example release build:
  - `ANDROID_NDK_ROOT=/path/to/ndk ./gradlew :app:assembleRelease`
- Unit tests:
  - `./gradlew :app:testDebugUnitTest`

## Licensing Considerations
- VeraCrypt licensing obligations apply to the bundled port.
- NTFS and exFAT libraries may carry GPL/LGPL obligations depending on packaging and linkage strategy.
- Distribution packages should include the required notices and source/binary offer where applicable.
