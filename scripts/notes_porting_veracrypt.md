# Porting VeraCrypt core + exFAT/NTFS to Android (plan)

## High-level steps
1) Build VeraCrypt core as static lib without platform drivers:
   - reuse code under `src/Crypto`, `src/Volume`, `src/Core` (exclude driver-specific Windows bits).
   - define `TC_NO_GUI`, `TC_WINDOWS_BOOT`, disable platform-specific code paths.
   - replace Win32 threading/IO with pthreads/posix, or stub where unused.
2) Build exfat (userspace) as static lib for Android.
3) Build ntfs-3g as static lib (read-only) for Android.
4) Implement a small block-device shim that exposes decrypted container bytes to exfat/ntfs-3g APIs (e.g., provide FILE* or custom ops using callbacks over decrypted buffer).
5) JNI surface: open -> load header, decrypt blocks on demand via shim; list entries via exfat/ntfs-3g directory traversal; read/write via filesystem lib; write-back flushed to container.
6) Package via CMake with NDK toolchain for arm64-v8a, x86_64.

## Immediate blockers
- VeraCrypt sources are Windows/Linux centric; need config header for Android and to exclude driver/UI code.
- exfat/ntfs-3g expect POSIX filesystem access; bridging to in-memory decrypted blocks requires significant adaptation.
- Time to fully implement and test is multi-day.

## Next concrete tasks
- Add Android config header and minimal stubs to satisfy VeraCrypt Common/Crypto/Volume builds.
- Enumerate and prune source files in CMake to avoid GUI/driver code.
- Build exfat and ntfs-3g as static libs with NDK; capture build flags/patches.
- Implement block-device shim (read/write sector callbacks) and map to exfat/ntfs-3g API.
- Fill JNI methods in `cryptocore.cpp` to wire shim + fs traversal.
