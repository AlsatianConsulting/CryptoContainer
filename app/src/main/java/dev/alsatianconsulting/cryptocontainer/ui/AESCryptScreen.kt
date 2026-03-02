package dev.alsatianconsulting.cryptocontainer.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.alsatianconsulting.cryptocontainer.MountController
import dev.alsatianconsulting.cryptocontainer.manager.AESCryptOperationResult
import dev.alsatianconsulting.cryptocontainer.manager.AESCryptManager
import dev.alsatianconsulting.cryptocontainer.util.ClipboardWatcher
import dev.alsatianconsulting.cryptocontainer.util.contentDisplayName
import dev.alsatianconsulting.cryptocontainer.util.sanitizeFileName
import dev.alsatianconsulting.cryptocontainer.util.stripTrailingAesExtension
import dev.alsatianconsulting.cryptocontainer.viewmodel.ShareAction
import kotlinx.coroutines.launch

private fun guessAescryptMimeType(fileName: String): String {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    if (ext.isBlank()) return "application/octet-stream"
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
}

@Composable
fun AESCryptScreen(
    modifier: Modifier = Modifier,
    manager: AESCryptManager,
    sharedUris: List<String>,
    shareAction: ShareAction?,
    clearShared: () -> Unit
) {
    var encryptInputPath by remember { mutableStateOf("") }
    var encryptOutputDir by remember { mutableStateOf("") }
    var encryptPassword by remember { mutableStateOf("") }
    var encryptPasswordConfirm by remember { mutableStateOf("") }
    var encryptOutputName by remember { mutableStateOf("output.aes") }

    var decryptInputPath by remember { mutableStateOf("") }
    var decryptOutputDir by remember { mutableStateOf("") }
    var decryptPassword by remember { mutableStateOf("") }
    var showEncryptOptions by remember { mutableStateOf(false) }
    var showDecryptOptions by remember { mutableStateOf(false) }
    var encryptFormMessage by remember { mutableStateOf("") }
    var decryptFormMessage by remember { mutableStateOf("") }
    var sharedEncryptUris by remember { mutableStateOf<List<String>>(emptyList()) }
    var lastDecryptResult by remember { mutableStateOf<AESCryptOperationResult?>(null) }

    val scope = rememberCoroutineScope()
    val status by manager.status.collectAsState(initial = "")
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val clipboard = remember { ClipboardWatcher(context, scope) }
    val dialogMaxHeight = configuration.screenHeightDp.dp * 0.72f

    DisposableEffect(Unit) {
        onDispose { clipboard.cancel() }
    }

    fun openOutputFile(result: AESCryptOperationResult) {
        val outputUri = result.outputUri?.let(Uri::parse) ?: return
        val outputName = result.outputName ?: "decrypted.bin"
        val baseIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(outputUri, guessAescryptMimeType(outputName))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(context.contentResolver, "AESCrypt", outputUri)
        }
        try {
            val packageManager = context.packageManager
            val defaultHandler = baseIntent.resolveActivity(packageManager)
            if (defaultHandler != null && defaultHandler.packageName != context.packageName) {
                context.startActivity(Intent(baseIntent).setClassName(defaultHandler.packageName, defaultHandler.className))
                return
            }

            val candidates = packageManager.queryIntentActivities(baseIntent, PackageManager.MATCH_DEFAULT_ONLY)
                .map { it.activityInfo.packageName to it.activityInfo.name }
                .filter { (packageName, _) -> packageName != context.packageName }
                .distinct()

            when {
                candidates.isEmpty() -> throw ActivityNotFoundException()
                candidates.size == 1 -> {
                    val (packageName, className) = candidates.first()
                    context.startActivity(Intent(baseIntent).setClassName(packageName, className))
                }
                else -> {
                    val explicitIntents = candidates.map { (packageName, className) ->
                        Intent(baseIntent).setClassName(packageName, className)
                    }
                    val chooser = Intent.createChooser(explicitIntents.first(), "Open File").apply {
                        putExtra(Intent.EXTRA_INITIAL_INTENTS, explicitIntents.drop(1).toTypedArray())
                    }
                    context.startActivity(chooser)
                }
            }
        } catch (_: ActivityNotFoundException) {
            decryptFormMessage = "Open failed: no app can open this file type"
        }
    }

    fun openOutputFolder(result: AESCryptOperationResult) {
        val rawDirUri = result.outputDirectoryUri?.let(Uri::parse) ?: return
        val folderUri = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(
                rawDirUri,
                DocumentsContract.getTreeDocumentId(rawDirUri)
            )
        }.getOrElse { rawDirUri }
        val openIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(folderUri, DocumentsContract.Document.MIME_TYPE_DIR)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            clipData = ClipData.newUri(context.contentResolver, "AESCrypt Folder", folderUri)
        }
        try {
            context.startActivity(Intent.createChooser(openIntent, "Open Folder"))
        } catch (_: ActivityNotFoundException) {
            decryptFormMessage = "Open failed: no app can open this folder"
        }
    }

    fun persistTreePermission(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: SecurityException) {
        }
    }

    val pickEncryptInput = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            encryptInputPath = it.toString()
            if (encryptOutputName == "output.aes") {
                val base = sanitizeFileName(
                    stripTrailingAesExtension(contentDisplayName(context, it, "output")),
                    "output"
                )
                encryptOutputName = "$base.aes"
            }
        }
    }

    val pickDecryptInput = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            decryptInputPath = it.toString()
        }
    }

    val pickEncryptDir = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            encryptOutputDir = it.toString()
            persistTreePermission(it)
        }
    }

    val pickDecryptDir = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            decryptOutputDir = it.toString()
            persistTreePermission(it)
        }
    }

    LaunchedEffect(sharedUris, shareAction) {
        if (sharedUris.isEmpty() || shareAction == null) return@LaunchedEffect
        when (shareAction) {
            ShareAction.AES_ENCRYPT -> {
                sharedEncryptUris = sharedUris
                encryptInputPath = sharedUris.first()
                encryptFormMessage = if (sharedUris.size == 1) {
                    "Shared file ready for AESCrypt encryption."
                } else {
                    "${sharedUris.size} shared files ready for AESCrypt encryption."
                }
                if (encryptOutputName == "output.aes") {
                    if (sharedUris.size > 1) {
                        encryptOutputName = "shared-files.zip.aes"
                    } else {
                        sharedUris.firstOrNull()?.let { rawUri ->
                            val parsed = Uri.parse(rawUri)
                            val base = sanitizeFileName(
                                stripTrailingAesExtension(contentDisplayName(context, parsed, "output")),
                                "output"
                            )
                            encryptOutputName = "$base.aes"
                        }
                    }
                }
                showEncryptOptions = true
                showDecryptOptions = false
                clearShared()
            }

            ShareAction.AES_DECRYPT -> {
                decryptInputPath = sharedUris.first()
                decryptFormMessage = if (sharedUris.size > 1) {
                    "Using the first shared file for AESCrypt decryption."
                } else {
                    ""
                }
                lastDecryptResult = null
                showDecryptOptions = true
                showEncryptOptions = false
                clearShared()
            }

            else -> Unit
        }
    }

    val encryptUris = remember(encryptInputPath, sharedEncryptUris) {
        if (sharedEncryptUris.isNotEmpty()) {
            sharedEncryptUris.map(Uri::parse)
        } else {
            encryptInputPath.takeIf { it.isNotBlank() }?.let { listOf(Uri.parse(it)) } ?: emptyList()
        }
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("AESCrypt", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                encryptFormMessage = ""
                showEncryptOptions = true
            }) { Text("Encrypt File") }
            Button(onClick = {
                decryptFormMessage = ""
                showDecryptOptions = true
            }) { Text("Decrypt File") }
        }
        Text("Clipboard auto-clears after 30 seconds.", style = MaterialTheme.typography.bodySmall)
        if (status.isNotBlank()) {
            Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        }
    }

    if (showEncryptOptions) {
        AlertDialog(
            onDismissRequest = { showEncryptOptions = false },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .imePadding(),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            confirmButton = {
                Button(onClick = { showEncryptOptions = false }) { Text("Close") }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = dialogMaxHeight)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Encrypt", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = encryptInputPath,
                        onValueChange = { encryptInputPath = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Input file URI (encrypt)") }
                    )
                    Button(onClick = { pickEncryptInput.launch(arrayOf("*/*")) }) { Text("Pick File To Encrypt") }

                    OutlinedTextField(
                        value = encryptOutputDir,
                        onValueChange = { encryptOutputDir = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Encrypt output folder URI (SAF)") }
                    )
                    Button(onClick = { pickEncryptDir.launch(null) }) { Text("Pick Encrypt Output Folder") }

                    SecureTextField(
                        value = encryptPassword,
                        onValueChange = { encryptPassword = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = "Encrypt password"
                    )
                    SecureTextField(
                        value = encryptPasswordConfirm,
                        onValueChange = { encryptPasswordConfirm = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = "Confirm encrypt password"
                    )
                    OutlinedTextField(
                        value = encryptOutputName,
                        onValueChange = { encryptOutputName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Encrypted output filename") }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = {
                            encryptFormMessage = ""
                            if (encryptUris.isEmpty() || encryptOutputDir.isBlank()) {
                                encryptFormMessage = "Encrypt: choose input file and output folder."
                                return@Button
                            }
                            if (encryptPassword.isBlank()) {
                                encryptFormMessage = "Encrypt: password is required."
                                return@Button
                            }
                            if (encryptPassword != encryptPasswordConfirm) {
                                encryptFormMessage = "Encrypt: passwords do not match."
                                return@Button
                            }
                            scope.launch {
                                MountController.onActivity()
                                val result = manager.encrypt(
                                    context = context,
                                    inputUris = encryptUris,
                                    outputDirUri = Uri.parse(encryptOutputDir),
                                    outputName = encryptOutputName,
                                    password = encryptPassword.toCharArray()
                                )
                                encryptFormMessage = result.message
                                if (result.success) {
                                    sharedEncryptUris = emptyList()
                                }
                            }
                        }) { Text("Encrypt") }

                        Button(onClick = { clipboard.set(encryptPassword, "aescrypt-encrypt-password") }) {
                            Text("Copy Encrypt Password")
                        }
                    }

                    if (sharedEncryptUris.isNotEmpty()) {
                        Text(
                            if (sharedEncryptUris.size == 1) {
                                "1 shared file is queued for AESCrypt encryption."
                            } else {
                                "${sharedEncryptUris.size} shared files will be zipped together, then encrypted."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }

                    if (encryptFormMessage.isNotBlank()) {
                        Text(
                            encryptFormMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (encryptFormMessage.startsWith("Encrypt failed")) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.secondary
                            }
                        )
                    }
                }
            }
        )
    }

    if (showDecryptOptions) {
        AlertDialog(
            onDismissRequest = { showDecryptOptions = false },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .imePadding(),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { showDecryptOptions = false }) { Text("Close") }
                    Button(onClick = {
                        decryptFormMessage = ""
                        lastDecryptResult = null
                        if (decryptInputPath.isBlank() || decryptOutputDir.isBlank()) {
                            decryptFormMessage = "Decrypt: choose input file and output folder."
                            return@Button
                        }
                        if (decryptPassword.isBlank()) {
                            decryptFormMessage = "Decrypt: password is required."
                            return@Button
                        }
                        scope.launch {
                            MountController.onActivity()
                            val result = manager.decrypt(
                                context = context,
                                inputUri = Uri.parse(decryptInputPath),
                                outputDirUri = Uri.parse(decryptOutputDir),
                                password = decryptPassword.toCharArray()
                            )
                            lastDecryptResult = result.takeIf { it.success }
                            if (result.success) {
                                decryptInputPath = ""
                                decryptOutputDir = ""
                                decryptPassword = ""
                                decryptFormMessage = ""
                            } else {
                                decryptFormMessage = result.message
                            }
                        }
                    }) { Text("Decrypt") }
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = dialogMaxHeight)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Decrypt", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = decryptInputPath,
                        onValueChange = { decryptInputPath = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Encrypted input file URI (accepts any file type)") }
                    )
                    Button(onClick = { pickDecryptInput.launch(arrayOf("*/*")) }) { Text("Pick File To Decrypt") }

                    OutlinedTextField(
                        value = decryptOutputDir,
                        onValueChange = { decryptOutputDir = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Decrypt output folder URI (SAF)") }
                    )
                    Button(onClick = { pickDecryptDir.launch(null) }) { Text("Pick Decrypt Output Folder") }

                    SecureTextField(
                        value = decryptPassword,
                        onValueChange = { decryptPassword = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = "Decrypt password"
                    )

                    if (decryptFormMessage.isBlank() && status.isNotBlank()) {
                        Text(
                            status,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (status.startsWith("Decrypt failed")) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.secondary
                            }
                        )
                    }

                    lastDecryptResult?.takeIf { it.success }?.let { result ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    result.message,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text("File: ${result.outputName ?: "Unknown"}")
                                Text("Location: ${result.outputLocation ?: result.outputUri ?: "Unknown"}")
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Button(
                                        onClick = { openOutputFile(result) },
                                        enabled = result.outputUri != null
                                    ) {
                                        Text("Open File")
                                    }
                                    Button(
                                        onClick = { openOutputFolder(result) },
                                        enabled = result.outputDirectoryUri != null
                                    ) {
                                        Text("Open Folder")
                                    }
                                }
                            }
                        }
                    }

                    if (decryptFormMessage.isNotBlank()) {
                        Text(
                            decryptFormMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        )
    }
}
