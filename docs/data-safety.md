# Google Play Data Safety Notes

## High-Level Answers

- Data collected: `No`
- Data shared with third parties: `No`
- App functionality requires on-device file access chosen by the user: `Yes`
- Data is processed locally on-device: `Yes`
- Encryption in transit: `Not applicable` for core local workflows
- Data deletion request: `Not applicable` because the app does not run user accounts or store user data on a remote server

## Rationale

CryptoContainer does not operate a cloud account system, analytics pipeline, advertising SDK, or remote storage backend.

The app processes:
- user-selected files
- user-selected folders
- user-selected VeraCrypt containers
- AESCrypt files
- passwords, PIM values, and keyfiles provided by the user

This processing happens locally on the device to provide the requested encryption, decryption, open, import, export, and share operations.

## What the App Does With User Data

- Reads files or folders only after direct user selection or Android share/open intents
- Writes output files only to app cache or user-selected destinations
- Keeps session credentials only for the active app session
- Clears clipboard secrets copied by the app after 30 seconds

## What the App Does Not Do

- No analytics collection
- No advertising ID use
- No location collection
- No contacts collection
- No remote account creation
- No background upload of files or metadata
- No sale or sharing of user data

## Review Notes For Store Submission

If Google Play asks whether the app handles files, the correct explanation is that file access is user-directed and required for the app’s core encrypted container and encrypted file workflows. Files are not collected for profiling, advertising, or server-side storage.
