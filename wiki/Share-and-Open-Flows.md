# Share And Open Flows

## Opening A `.hc` File
When another app opens a `.hc` file with CryptoContainer:
1. `MainActivity` receives an Android `VIEW` intent
2. CryptoContainer switches to the `VeraCrypt` tab
3. `Container File` is populated with the shared/opened URI
4. The user can immediately enter password, PIM, and keyfiles and tap `Open`

## Opening A `.aes` File
When another app opens a `.aes` file with CryptoContainer:
1. `MainActivity` receives an Android `VIEW` intent
2. CryptoContainer switches to the `AESCrypt` tab
3. The decrypt dialog opens automatically
4. The encrypted input field is prefilled with the shared/opened URI

## Generic Android Share
When another app shares one or more files to CryptoContainer, the app shows `Choose Share Action`.

Base choices:
- `Encrypt Using AESCrypt`
- `Decrypt Using AESCrypt`
- `Mount as VeraCrypt Container`

Additional choice when a VeraCrypt container is already mounted:
- `Share Into Open VeraCrypt Container`

## Share Into Open VeraCrypt Container
1. User shares files into CryptoContainer
2. User chooses `Share Into Open VeraCrypt Container`
3. Shared items are queued
4. VeraCrypt explorer is opened
5. User navigates to the destination folder
6. User selects `Import Shared Here`
7. Files are imported into that folder

If no container is mounted, the app blocks the import and shows:
- `This Action Requires Mounting A VeraCrypt Container`

## Share Into AESCrypt Encrypt
- shared file becomes the encrypt input
- if multiple files are shared, the app zips them first, then encrypts the ZIP

## Share Into AESCrypt Decrypt
- first shared file becomes the decrypt input
- decrypt output still requires the user to choose the output folder
