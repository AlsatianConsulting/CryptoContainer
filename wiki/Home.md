# CryptoContainer Wiki

This wiki is the detailed operator reference for CryptoContainer.

Use this wiki when you need the exact behavior of a field, button, or workflow rather than the short how-to guidance in `README.md`.

## What CryptoContainer Does
- Create and open VeraCrypt containers on stock Android 14+
- Support standard and hidden VeraCrypt volumes
- Work with `FAT`, `exFAT`, and `NTFS`
- Browse mounted container contents with an in-app file explorer
- Encrypt and decrypt AESCrypt files
- Accept Android share and Android open intents for `.hc`, `.aes`, and generic files

## Important Design Choice
CryptoContainer uses a provider-backed model, not a kernel mountpoint.

That means:
- no root is required
- files are exposed through Android document/provider flows while a container is open
- some third-party apps may behave differently than they would with a desktop-style mount path

## There Is No Separate Settings Screen
CryptoContainer does not currently expose a traditional settings page.

Operational settings are instead represented directly in the active workflow screens, for example:
- `Read-only` on the VeraCrypt main screen
- volume type selection during container creation
- file system, algorithm, and hash selection during creation
- session-only password handling
- keyfile selection in the VeraCrypt flow

## Page Index
- [[Quick-Start]]
- [[Buttonology]]
- [[VeraCrypt-Main-Screen]]
- [[VeraCrypt-Create-Volume]]
- [[VeraCrypt-Explorer]]
- [[AESCrypt]]
- [[Share-and-Open-Flows]]
- [[Data-Flows]]
- [[Runtime-and-Security]]
- [[Troubleshooting]]
