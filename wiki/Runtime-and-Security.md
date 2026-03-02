# Runtime And Security

## Session Behavior
- Passwords are cached only for the current app session
- Containers are closed when the app session ends
- Clipboard values copied by the app are cleared after 30 seconds

## Foreground Service
A foreground service is used while mount state is active so the app can keep the open container lifecycle stable.

## Provider Model
CryptoContainer uses Android document/provider access rather than a root-only kernel mount.

Implications:
- works on stock Android 14+
- no root required
- suitable for in-app explorer, share, open, and provider-based access
- not identical to a desktop filesystem mountpoint

## Temporary Files
The app may use app-private cache storage for:
- AESCrypt encrypt/decrypt staging
- external file open staging
- keyfile staging
- ZIP staging for multi-file AESCrypt encryption

## No Traditional Settings Screen
Operational choices are exposed directly in workflow screens instead of a standalone preferences page.

Examples:
- read-only mount toggle
- standard versus hidden volume creation
- selected filesystem, algorithm, and hash
- selected keyfiles

## Filesystem Safety Notes
- NTFS may reopen or report as read-only when safety checks require it
- exFAT and FAT are handled fully in userspace within the supported in-app workflows
