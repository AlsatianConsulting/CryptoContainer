# Play Store Listing

## App Name
CryptoContainer

## Short Description
Open, create, and manage VeraCrypt containers and AESCrypt files.

## Full Description
CryptoContainer is an Android 14+ app for working with encrypted files and containers on stock, unrooted devices.

Use CryptoContainer to create and open VeraCrypt volumes, browse their contents, add or extract files, and share files into an already open container. The app also supports AESCrypt file encryption and decryption, including direct open and Android share flows.

VeraCrypt features:
- Create standard and hidden volumes
- Open existing `.hc` containers
- Support for `FAT`, `exFAT`, and `NTFS`
- Support for VeraCrypt default algorithms and common hash options
- In-app file explorer with list and grid views
- Copy, cut, paste, rename, delete, extract, share, and new folder actions
- Multi-select for files and folders
- Session-only password handling and optional keyfiles

AESCrypt features:
- Encrypt files into `.aes`
- Decrypt `.aes` files to a folder you choose
- Preserve original output filenames when possible
- Open `.aes` files directly in CryptoContainer
- Encrypt multiple shared files by bundling them into one ZIP before encryption

Designed for stock Android:
- No root required
- Files stay on-device unless you explicitly share them
- Clipboard secrets auto-clear after 30 seconds
- Mounted VeraCrypt containers are closed when the app session ends

CryptoContainer is intended for users who need practical encrypted container and file workflows on Android without relying on kernel mounts or root-only features.

## Suggested Release Notes
- Initial public release
- VeraCrypt create/open/explore/edit support
- Hidden volume support
- AESCrypt encrypt/decrypt support
- Android share and direct-open integration for `.hc` and `.aes`

## Screenshot Suggestions
- VeraCrypt main screen with container summary
- VeraCrypt explorer in list view
- VeraCrypt explorer in grid view
- Create Volume flow
- AESCrypt encrypt dialog
- AESCrypt decrypt result with Open File / Open Folder
