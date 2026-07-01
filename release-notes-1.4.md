# CryptoContainer 1.4 — Release Notes

## New Features

### TrueCrypt Container Support
CryptoContainer can now open legacy **TrueCrypt** volumes in addition to VeraCrypt. When a password is entered, the VeraCrypt format is tried first; if it is rejected, the volume is retried in TrueCrypt mode automatically. Both standard and hidden volumes are attempted. (TrueCrypt has no PIM, so the TrueCrypt fallback applies only when no PIM is set.)

### Mounted Containers Visible to Other Apps
Open containers now appear in the system file picker (Storage Access Framework) of *other* apps — not just the stock Files app. When a volume is mounted or closed, CryptoContainer notifies DocumentsUI to re-query its roots, so a mounted container shows up immediately when another app presents an Open or Save dialog.

### Reliable Share-to-Import From Any App
Sharing a file **into** CryptoContainer from any app now reliably brings up the **Choose Share Action** dialog (Encrypt with AESCrypt, Decrypt with AESCrypt, Mount as VeraCrypt Container, or — when a container is open — Share Into Open VeraCrypt Container).

Previously this worked only from the stock Files app. Other apps deliver shared items differently: some use `ACTION_SEND` with the file in `clipData` rather than `EXTRA_STREAM`, and some (e.g. email clients opening an attachment such as a PDF) use `ACTION_VIEW` with the file in the intent data. CryptoContainer now handles all of these paths instead of silently opening to the main window.

### USB Drive Import
Files can be imported directly from the phone into a mounted VeraCrypt USB drive via an **Import** button in the file browser toolbar, with multi-select support. The button is disabled when the drive is mounted read-only.

## Compatibility

- Android 8.0 (API 26) and newer.
- ABIs: `arm64-v8a` and `x86_64` (Google Play AAB compatibility).
- 16 KB memory page size supported.

## Build

- versionName **1.4** / versionCode **11**
- targetSdk 36 / compileSdk 36 / minSdk 26
