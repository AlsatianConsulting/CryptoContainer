# Release Notes 1.2

## New: USB VeraCrypt Drive Support

CryptoContainer now unlocks and browses VeraCrypt volumes directly on USB flash drives — no PC, no root, no mounted filesystem required.

- **Direct USB access** via Android USB Host API with a built-in SCSI mass-storage driver
- **Automatic device detection** — the app switches to the USB tab and prompts for permission as soon as a drive is connected
- **Unlock dialog** with password, PIM, hidden-volume toggle, and read-only toggle
- **In-app file browser** — navigate directories, select files with checkboxes, and export to any app via the Android share sheet
- **Multi-file export** with Select All / Deselect All and an export count indicator
- **Export cache management** — a Clear Cache button wipes previously exported files from local storage
- **Lock / Unmount** returns the drive to the detected state so it can be re-unlocked without replug

## Security Fixes

Seven issues identified in an internal audit have been resolved:

- **Path traversal in DocumentsProvider** — document IDs containing `..` or `.` components are now rejected, preventing escapes outside the mounted volume root
- **Path traversal in USB export** — filenames returned by the volume listing are sanitized before being written to the export cache directory
- **Path traversal in C++ layer** — `normalizePath()` now resolves `..` and `.` components rather than prepending `/` only
- **SCSI LBA field overflow** — READ(10) and WRITE(10) commands previously truncated the 64-bit sector address to 32 bits silently; drives larger than 2 TB now receive a clear error instead of reading the wrong sectors
- **Predictable temp file names** — AESCrypt operation temp files now use `File.createTempFile()` (OS-generated random suffix) instead of `System.currentTimeMillis()`
- **AESCrypt extension denial-of-service** — malformed `.aes` files with many large extension fields could allocate unbounded memory; total extension bytes are now capped at 65535
- **Clipboard clear on API 28+** — the 15-second clipboard auto-clear now calls `clearPrimaryClip()` on Android 9 and above instead of replacing the clip with an empty string

## Notes

- USB drive support requires Android USB Host permission, which is requested automatically on device attach
- The USB driver implements SCSI Bulk-Only Transport and is compatible with standard USB mass-storage devices (flash drives, SD card readers); drives that require custom drivers are not supported
- Passwords are never written to disk and are zeroed in memory immediately after use
