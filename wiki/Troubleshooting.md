# Troubleshooting

## `This Action Requires Mounting A VeraCrypt Container`
Cause:
- You tried to import shared files into a VeraCrypt container when no container was open.

Fix:
1. Open the `VeraCrypt` tab.
2. Open the target container.
3. Retry the share flow.

## `Open failed: volume decrypted but no supported filesystem was detected.`
Cause:
- The container header decrypted, but the app could not detect a supported filesystem in the opened volume.

Common cases:
- invalid or incomplete hidden-volume content
- damaged filesystem inside the container
- unsupported or malformed contents

## Container Opens Read-Only
Cause:
- The selected open mode was read-only, or NTFS safety checks forced a read-only fallback.

Effect:
- add, rename, paste, new folder, and delete actions are disabled or blocked.

## AESCrypt `Decrypt failed`
Check:
- password
- output folder permission
- available storage space
- whether the input file is a valid AESCrypt-compatible file

The decrypt dialog shows the failure text inside the form.

## Another App Does Not Save Back During `Edit In Place`
Cause:
- The external app may support view-only access or may not write back through provider URIs.

Fix:
- try another editor that supports Android document/provider write access
- if needed, extract the file, edit it externally, and re-import it

## Share Chooser Does Not Show The VeraCrypt Import Option
Cause:
- `Share Into Open VeraCrypt Container` only appears when a container is already mounted.
