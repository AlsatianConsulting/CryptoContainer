# Buttonology

## VeraCrypt Main Screen
- `Open`: attempts to open the selected container with the entered password, optional PIM, and selected keyfiles.
- `Pick Container`: opens Android document picker for the container file.
- `Close`: closes the currently mounted container.
- `Create Volume`: opens the VeraCrypt creation workflow.
- `Explore Container`: opens the full-screen explorer for the mounted container.
- `Close Container`: closes the mounted container from the summary card.
- `Choose Keyfiles`: opens Android multi-file picker for keyfiles.
- `Change Keyfiles`: replaces the currently selected keyfile list.
- `Clear Keyfiles`: removes selected keyfiles.
- `Read-only` checkbox: forces a read-only open attempt.
- Password eye icon: shows or hides the password.

## VeraCrypt Create Volume Screen
- `Choose Output File`: selects the destination `.hc` file.
- `Standard`: creates a regular VeraCrypt volume.
- `Hidden`: creates an outer volume plus hidden volume settings.
- File system buttons: select `FAT`, `exFAT`, or `NTFS`.
- Algorithm buttons: select the VeraCrypt encryption algorithm or cascade.
- Hash buttons: select the header hash.
- `Create`: starts volume creation.
- `Cancel`: closes the creation dialog.

## VeraCrypt Explorer Top Bar
- Back arrow: closes the explorer window and returns to the VeraCrypt main screen.
- List icon: switches to list view.
- Grid icon: switches to grid view.

## VeraCrypt Explorer Location Menu (`...`)
- `Up`: move to parent folder.
- `Refresh`: reload current folder listing.
- `Add Files`: import one or more files into the current folder.
- `Add Folder`: import a folder tree into the current folder.
- `New Folder`: create a folder in the current location.
- `Paste Here`: paste the current clipboard selection into the current location.
- `Import Shared Here`: import pending Android shared files into the current location.
- `Cancel Shared Import`: clear pending shared items.
- `Clear Clipboard`: clears copy/cut clipboard state.

## VeraCrypt Explorer Item Menu (`...`)

### File Items
- `Open`: extracts to cache and opens in another app.
- `Select`: toggles selection state.
- `Rename`: renames the file.
- `Copy`: places the file into copy clipboard state.
- `Cut`: places the file into move clipboard state.
- `Edit In Place`: opens the provider URI for external editing with write-back support when the other app supports it.
- `Extract`: writes the file out to a user-selected destination.
- `Share`: shares the file out through Android.
- `Delete`: removes the file from the container.

### Folder Items
- `Open`: enters the folder.
- `Select`: toggles selection state.
- `Rename`: renames the folder.
- `Copy`: recursively copies the folder when pasted.
- `Cut`: recursively moves the folder when pasted.
- `New Folder Here`: creates a subfolder inside the selected folder.
- `Paste Here`: pastes clipboard contents into the selected folder.
- `Extract`: recursively exports the folder tree.
- `Import Shared Here`: imports pending shared files into that folder.
- `Delete`: recursively deletes the folder tree.

## VeraCrypt Explorer Selection Menu (`...` next to `X selected`)
- `Open`: available for one selected item.
- `Edit In Place`: available for one selected file.
- `Rename`: available for one selected item.
- `Copy`: copies the whole selection.
- `Cut`: cuts the whole selection.
- `Extract`: exports the selection.
- `Share`: shares selected files.
- `Delete`: deletes the selection.
- `Select All`: selects the current listing.
- `Clear Selection`: removes the current selection.

## AESCrypt Main Screen
- `Encrypt File`: opens the AESCrypt encryption dialog.
- `Decrypt File`: opens the AESCrypt decryption dialog.

## AESCrypt Encrypt Dialog
- `Pick File To Encrypt`: chooses the source file.
- `Pick Encrypt Output Folder`: chooses output folder.
- Password eye icons: show or hide password text.
- `Encrypt`: starts encryption.
- `Copy Encrypt Password`: copies the current encryption password to clipboard.
- Busy dialog `Cancel`: stops encryption in progress.
- `Close`: dismisses the dialog when idle.

## AESCrypt Decrypt Dialog
- `Pick File To Decrypt`: chooses encrypted input.
- `Pick Decrypt Output Folder`: chooses output folder.
- Password eye icon: shows or hides password text.
- `Decrypt`: starts decryption.
- `Close`: dismisses the dialog when idle.
- Result panel `Open File`: opens the decrypted file in another app.
- Result panel `Open Folder`: opens the output folder through Android.
- Busy dialog `Cancel`: stops decryption in progress.
