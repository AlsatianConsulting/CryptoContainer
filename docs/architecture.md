# CryptoContainer Architecture (Android 14+)

## Goals
- VeraCrypt: create/open/mount hidden or standard volumes; exFAT and NTFS, with optional read-only open mode; AES/Serpent/Twofish (+ cascades), SHA-512/Whirlpool.
- AESCrypt: decrypt/encrypt .aes files (v3 header); no size limits.
- Stock devices (unrooted). Session-only password cache; auto-clear clipboard in 30s; unmount + wipe on app close or service stop. Distribution via Google Play and sideload; licensing compliance (GPL for ntfs-3g/exfat, VeraCrypt license) required.

## Layers
- **UI (Jetpack Compose)**: two tabs (VeraCrypt, AESCrypt); foreground service status chip; mount/extract views; per-volume actions (mount/unmount/add/extract/share).
- **ForegroundService + Notification**: owns lifecycle of mounts/long ops; stops on task removal, triggers unmount.
- **Storage Abstraction**
  - `VolumeProvider` (`DocumentsProvider`) is backed directly by mounted-in-process native handles (no kernel mountpoint). Apps can access files through provider URIs while the container is open.
  - In-app browser remains available for copy-in/copy-out, delete, and share even without a system-visible mountpoint.
  - NTFS containers support read/write and can also be opened read-only with explicit labeling.
  - SAF integration for pickers and persisted URI permissions.
- **Native Core (JNI)**
  - Bundled VeraCrypt core + `libexfat` + `libntfs-3g` (+ `mkntfs` bridge). JNI exposes create/open/close/list/extract/add/delete and filesystem detection.
  - exFAT and NTFS create are implemented, including optional hidden header creation and separate hidden credentials.
  - Outer writable open supports optional hidden-volume protection password/PIM.
  - Key buffers zeroized; no disk caching of secrets; temp files in internal storage wiped on completion.
- **Security**
  - Session memory cache only; cleared on service stop/app exit. Optional inactivity timeout. Clipboard cleared after 30s (auto wipe timer).
  - Requires device lock for notification actions; blocks screenshots on sensitive screens.

## Main Components
- `CryptoContainerApp` (Compose root) with tabs and navigation.
- `MountService` (foreground) managing active mounts and timeouts.
- `VeraCryptManager` (Kotlin) wrapping JNI; exposes flows for status/progress.
- `VolumeProvider` (DocumentsProvider) exposing open container entries to other apps through SAF URIs; shows read-only state from current mount info.
- `AESCryptManager` handling .aes encrypt/decrypt streaming via JNI or pure Kotlin crypto.
- Share intents (`SEND`/`SEND_MULTIPLE`) are consumed by `MainActivity` and routed to AESCrypt/VeraCrypt flows.

## Data Flow
1) User selects container via SAF → `VeraCryptRepo.open()` → starts `MountService` → native open from file descriptor → `VolumeProvider` serves entries/files for cross-app access while open.
2) Create volume: UI collects params → JNI create → format exFAT or NTFS in userspace (no root) → optional hidden volume inner size.
3) AESCrypt decrypt: pick `.aes` → stream decrypt to target SAF folder; encrypt path(s) → outputs `.aes` → share/save.

## Error/Degrade
- NTFS write/delete while mounted read-only: block and return read-only error.
- NTFS open in writable mode that fails due hibernated/unclean state automatically reopens read-only with a safety notice.
- Outer writable open without hidden protection: explicit warning dialog.
- Large ops: progress + cancel; keep foreground notification.

## Work Items
- Optional FUSE export path for environments where DocumentsProvider routing is insufficient.
- Broader tests: SAF/provider operations, error mapping, zeroization coverage, and longer NTFS stress matrices (including hidden-volume paths).
- Host validation pipeline scripts now live under `scripts/validation/` for Android stress + artifact export + optional Windows `chkdsk`.
- Release packaging/compliance pipeline for Play + sideload distributions.
