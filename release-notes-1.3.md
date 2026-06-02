# CryptoContainer 1.3 — Release Notes

## New Features

### USB Drive — File Import
Files can now be imported from the phone directly into a mounted VeraCrypt USB drive. An **Import** button appears in the file browser toolbar alongside Export. Supports picking multiple files at once via the system file picker. The button is automatically disabled when the drive is mounted read-only.

### USB Drive — Description
The USB Drive tab now displays a brief description clarifying it is designed for VeraCrypt full-disk encrypted USB drives and not for individual container files (which belong on the VeraCrypt tab).

## Security Improvements

### Secure Cache Deletion
Cache files are now overwritten with zeros and flushed to storage (fsync) before being deleted, rather than simply unlinked. This prevents straightforward filesystem-level recovery of decrypted content from the app's cache directory. Applies to all three sections:
- **VeraCrypt**: clears `vc-open`, `vc-extract-tree`, `vc-import-tree`, and `vc-clipboard` on container close
- **AESCrypt**: clears any residual temp files on dialog close
- **USB Drive**: clears export and import-tmp caches on lock/unmount

### Automatic Cache Clearing on Lock/Unmount
Closing or locking a container now automatically clears the section's cache. Previously the USB export cache required a manual "Clear Cache" button press.

## Compatibility

### Expanded Android Support
Minimum supported Android version lowered from **Android 14 (API 34)** to **Android 8.0 (API 26)**. All necessary API version guards were already present in the code. No functional changes required.
