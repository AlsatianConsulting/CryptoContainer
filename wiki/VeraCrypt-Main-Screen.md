# VeraCrypt Main Screen

## Purpose
The VeraCrypt main screen is the control surface for:
- opening an existing container
- closing the current container
- selecting keyfiles
- forcing read-only mode
- starting the create-volume workflow
- entering the explorer after a successful mount

## Fields

### Container File
- Type: editable text field
- Purpose: stores the URI/path of the container file
- Typical source:
  - `Pick Container`
  - Android `Open with` on a `.hc` file
  - Android share flow choosing `Mount as VeraCrypt Container`

### Password
- Type: masked password field
- Purpose: primary VeraCrypt password
- Behavior:
  - hidden by default
  - eye icon toggles visible/plain text
  - used for both standard and hidden header attempts

### PIM (optional)
- Type: numeric/freeform text field
- Purpose: VeraCrypt PIM when required
- If empty, open uses PIM `0`

### Keyfiles (optional)
- Type: selected list of Android URIs
- Purpose: VeraCrypt keyfiles staged through app cache before native open/create
- Buttons:
  - `Choose Keyfiles`
  - `Change Keyfiles`
  - `Clear Keyfiles`

### Read-only
- Type: checkbox
- Purpose: forces a read-only open attempt
- Use this when you want a safety-first open or you do not want writes allowed

## Buttons

### Open
Attempts to open the currently selected container.

Order of operations:
1. standard header attempt
2. hidden header attempt if needed
3. mount state stored in the app session
4. current container card appears on success

### Pick Container
Opens Android document picker to select the container file.

### Close
Closes the currently mounted container from the main control row.

### Create Volume
Opens the `Create VeraCrypt Volume` dialog.

## Current Container Card
Visible only while a container is open.

Shows:
- `Name`
- `Type: Standard` or `Type: Hidden`

Buttons:
- `Explore Container`
- `Close Container`

## Status Area
When a container is mounted, the screen also shows mount-state information such as:
- status message
- mount mode label
- read-only flag
- provider-style mount point text
- filesystem label
- safety fallback notes for NTFS when applicable

## Common Messages
- `Shared VeraCrypt container file selected`
- `Open failed: volume decrypted but no supported filesystem was detected.`
- `Closed`
- `Open canceled`

## Notes
- CryptoContainer is provider-backed and does not expose a kernel mountpoint.
- Mounted containers are closed when the app session ends.
- Passwords are cached for the session only.
