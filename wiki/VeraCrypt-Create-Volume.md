# VeraCrypt Create Volume

## Purpose
This dialog creates a new VeraCrypt container file on user-selected storage.

## Fields And Selectors

### Container File
- Selected with `Choose Output File`
- This is the destination `.hc` file to create

### Volume Type
- `Standard`
- `Hidden`

If `Hidden` is selected, hidden-volume fields are revealed.

### Size Fields
- `Size (MB)` for standard volumes
- `Outer size (MB)` for hidden-volume creation
- `Hidden size (MB)` for the hidden section

### Password Fields
- Standard/outer `Password`
- Standard/outer `PIM (optional)`
- Hidden `Hidden password`
- Hidden `Hidden PIM (optional)`

### File System
Available buttons:
- `FAT`
- `exFAT`
- `NTFS`

Selection styling:
- selected option: solid orange fill with dark text
- unselected option: orange outline with transparent fill

### Algorithm
Available choices depend on the bundled VeraCrypt set and include:
- `AES`
- `Serpent`
- `Twofish`
- common VeraCrypt cascades

### Hash
Common options include:
- `SHA-512`
- `Whirlpool`

### Keyfiles
- outer keyfiles can be selected
- hidden keyfiles can be selected separately
- if hidden keyfiles are not provided, hidden creation can reuse outer keyfiles

## Buttons
- `Choose Output File`: select where the container file will be written
- `Create`: begin creation
- `Cancel`: dismiss the dialog

## Hidden Volume Behavior
When `Hidden` is selected, the dialog exposes:
- hidden size
- hidden password
- hidden PIM
- hidden keyfiles

Creation flow formats both the outer filesystem and the hidden filesystem.

## Validation Rules
The dialog validates at least:
- output file selected
- password present
- minimum size for the selected filesystem
- hidden size is smaller than outer size

## Progress And Cancel
During create, the app shows a modal progress dialog with:
- spinner
- operation text
- filesystem and size summary
- `Cancel`

If canceled, the create operation returns `Create canceled`.
