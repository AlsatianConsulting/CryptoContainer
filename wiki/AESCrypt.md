# AESCrypt

## Purpose
The AESCrypt tab handles local file encryption and decryption using AESCrypt-compatible `.aes` files.

## Main Screen
Buttons:
- `Encrypt File`
- `Decrypt File`

Status text can appear below the buttons, and clipboard secrets copied by the app auto-clear after 30 seconds.

## Encrypt Dialog

### Fields
- `Input file URI (encrypt)`
- `Encrypt output folder URI (SAF)`
- `Encrypt password`
- `Confirm encrypt password`
- `Encrypted output filename`

### Buttons
- `Pick File To Encrypt`
- `Pick Encrypt Output Folder`
- `Encrypt`
- `Copy Encrypt Password`
- `Close`

### Behavior
- single-file encrypt writes one `.aes` output
- multi-file shared encrypt first creates a ZIP bundle, then encrypts that ZIP
- default bundled name is `shared-files.zip.aes`

### Busy Dialog
While running, the app shows:
- spinner
- progress text
- `Cancel`

## Decrypt Dialog

### Fields
- `Encrypted input file URI (accepts any file type)`
- `Decrypt output folder URI (SAF)`
- `Decrypt password`

### Buttons
- `Pick File To Decrypt`
- `Pick Decrypt Output Folder`
- `Decrypt`
- `Close`

### Success Panel
After successful decrypt, the form fields clear and the result panel shows:
- output file name
- output location
- `Open File`
- `Open Folder`

### Failure Handling
Failure text appears inside the decrypt dialog itself.

### Busy Dialog
While running, the app shows:
- spinner
- progress text
- `Cancel`

## Filename Handling
- If the encrypted file carries original-name metadata, that filename is restored
- If not, the app strips `.aes` from the input filename where possible
- Example:
  - `README.txt.aes` -> `README.txt`

## External Open Behavior
- opening a `.aes` file from another app launches CryptoContainer and opens the decrypt dialog
- the shared/opened `.aes` file URI is prefilled into the decrypt form
