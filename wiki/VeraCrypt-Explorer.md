# VeraCrypt Explorer

## Purpose
The VeraCrypt explorer is a full-screen browser for the currently open container.

It is designed to feel like a normal file explorer while operating against the mounted provider-backed container.

## Views
- List view
- Grid view

Switch using the top-right list/grid icons.

## Top Bar
- Back arrow: returns to the VeraCrypt main screen
- Container name display
- Current view toggle icons

## Location Pane
Shows:
- `Location`
- current path inside the container

The `...` menu contains:
- `Up`
- `Refresh`
- `Add Files`
- `Add Folder`
- `New Folder`
- `Paste Here`
- `Import Shared Here`
- `Cancel Shared Import`
- `Clear Clipboard`

## Pending Shared Import Banner
When Android shared files are waiting to be placed into the open container, the explorer shows a message telling you to navigate to the destination and choose `Import Shared Here`.

## Item Rows And Tiles
Each file or folder shows:
- name
- secondary metadata such as size/type where relevant
- selection checkbox or highlight in selection mode
- right-side three-dot menu

## Context Menus

### File Menu
- `Open`
- `Select` or `Deselect`
- `Rename`
- `Copy`
- `Cut`
- `Edit In Place`
- `Extract`
- `Share`
- `Delete`

### Folder Menu
- `Open`
- `Select` or `Deselect`
- `Rename`
- `Copy`
- `Cut`
- `New Folder Here`
- `Paste Here`
- `Extract`
- `Import Shared Here`
- `Delete`

## Multi-Selection
Long-press or select multiple items, then use the selection header.

Selection header shows:
- `X selected`
- selection overflow menu

Selection menu options:
- `Open` for one selected item
- `Edit In Place` for one selected file
- `Rename` for one selected item
- `Copy`
- `Cut`
- `Extract`
- `Share`
- `Delete`
- `Select All`
- `Clear Selection`

## Dialogs

### Rename Dialog
- field: `New name`
- buttons: `Rename`, `Cancel`

### Create New Folder Dialog
- field: `Folder name`
- buttons: `Create`, `Cancel`

## Read-Only Behavior
If the container is open read-only, write operations are disabled or blocked, including:
- add
- rename
- cut
- paste
- delete
- new folder

The explorer shows read-only messaging so this state is visible.

## External Editing
`Edit In Place` opens the file through the app's provider URI with write permission.

Whether save-back works depends on the external editor:
- apps that support provider write-back can save directly into the container
- view-only apps may only read the file
