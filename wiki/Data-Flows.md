# Data Flows

## VeraCrypt Open Flow
1. User selects a container file
2. User enters password, optional PIM, and optional keyfiles
3. App stages keyfiles if needed
4. App starts open work on a background thread
5. Native layer tries standard header
6. Native layer tries hidden header if needed
7. If successful, mount state is stored in app memory
8. `Current Container` card appears
9. `VolumeProvider` can serve document URIs while the volume remains open

## VeraCrypt Create Flow
1. User chooses output file
2. User selects standard or hidden mode
3. User enters size, password, optional PIM, and optional keyfiles
4. User selects file system, algorithm, and hash
5. App validates the request
6. Create runs on a background thread
7. Native layer creates the outer volume
8. Native layer formats the selected filesystem
9. If hidden mode is selected, hidden headers and hidden filesystem are created too
10. Result is written to the chosen output file

## VeraCrypt Explorer File Open Flow
1. User taps `Open` on a file
2. App extracts the file to app cache if needed for external viewing
3. App sends `ACTION_VIEW` to Android with MIME type and URI permissions
4. Another app opens the file

## VeraCrypt Edit-In-Place Flow
1. User chooses `Edit In Place`
2. App builds provider URI for the file inside the container
3. App sends `ACTION_EDIT` with read/write permissions
4. External editor reads and writes through the provider when supported
5. Changes are reflected in the open container

## VeraCrypt Import File Flow
1. User chooses `Add Files`
2. Android picker returns one or more source URIs
3. App copies file contents into the selected folder in the container
4. Explorer refreshes current folder listing

## VeraCrypt Import Folder Flow
1. User chooses `Add Folder`
2. Android tree picker returns a source folder
3. App walks the tree recursively
4. Folders are created inside the container as needed
5. Files are copied into the matching container paths

## VeraCrypt Extract Flow
1. User chooses `Extract`
2. For single files, user chooses a document output target
3. For folders or multi-item selections, user chooses a tree/folder destination
4. App walks the selected entries recursively
5. Files are written out through SAF

## AESCrypt Encrypt Flow
1. User chooses source file or shared files
2. User selects output folder
3. User enters password and confirmation
4. App stages source file(s) in private storage if needed
5. For multi-file share, app creates a ZIP first
6. App encrypts using AESCrypt-compatible format
7. Output is written to the selected destination folder

## AESCrypt Decrypt Flow
1. User chooses input `.aes` file or opens it from another app
2. User selects output folder
3. User enters password
4. App stages encrypted input if needed
5. App decrypts into private output staging
6. App restores original filename when metadata exists, or strips `.aes` from source filename
7. Output is copied to the user-selected folder
8. Result panel shows output name and location

## Close / App Exit Flow
1. User taps `Close` or `Close Container`, or app session ends
2. Open mount state is cleared
3. Provider-backed access stops for that volume
4. Session-only password cache is cleared when the app closes
