Native build notes (work-in-progress)

- Sources pulled into `third_party/`:
  - VeraCrypt (core)
  - libfuse
  - exfat
  - ntfs-3g (read/write mount path + mkntfs create path)
- Remaining tasks:
  - Add Android NDK toolchain configuration.
  - Build static libs for arm64-v8a/x86_64.
  - Link into `cryptocore` and implement JNI in `cryptocore.cpp`.
