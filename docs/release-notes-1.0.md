# Release Notes 1.0

CryptoContainer 1.0 is the first public release of the Android app.

Highlights:

- Create and open VeraCrypt standard and hidden volumes
- Support `FAT`, `exFAT`, and `NTFS` filesystems
- Explore mounted VeraCrypt containers with file and folder actions
- Add, extract, rename, copy, cut, paste, and delete files and folders in-app
- Multi-select support in the VeraCrypt explorer
- Open compatible container files in external Android apps
- AESCrypt encrypt and decrypt support
- Multi-file AESCrypt encrypt flow with ZIP-then-encrypt behavior
- Direct open handling for `.hc` and `.aes`
- Android share integration for VeraCrypt and AESCrypt flows
- Signed Android release build for sideload distribution

Key notes:

- VeraCrypt access uses Android provider-based access on stock Android 14 with no root requirement
- Passwords are session-only and cleared on app close
- Mounted containers are closed when the app terminates
