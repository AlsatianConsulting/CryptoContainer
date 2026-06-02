package dev.alsatianconsulting.cryptocontainer.ui

import android.content.Intent
import android.hardware.usb.UsbDevice
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.alsatianconsulting.cryptocontainer.usb.UsbDriveManager
import dev.alsatianconsulting.cryptocontainer.usb.UsbDriveState
import dev.alsatianconsulting.cryptocontainer.util.contentDisplayName
import dev.alsatianconsulting.cryptocontainer.util.copyUriToFile
import dev.alsatianconsulting.cryptocontainer.util.sanitizeFileName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun UsbDriveScreen(
    modifier: Modifier = Modifier,
    usbDriveManager: UsbDriveManager
) {
    val state by usbDriveManager.state.collectAsState()
    val scope  = rememberCoroutineScope()
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "USB VeraCrypt Drive",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        HorizontalDivider()

        Text(
            text  = "Designed for VeraCrypt full-disk encrypted USB drives. " +
                    "Not for individual container files — use the VeraCrypt tab for those.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        when (val s = state) {
            is UsbDriveState.Idle -> IdleCard()

            is UsbDriveState.DeviceAttached -> {
                DeviceAttachedCard(
                    device        = s.device,
                    name          = s.name,
                    hasPermission = usbDriveManager.hasPermission(s.device),
                    onRequestPermission = { usbDriveManager.requestPermission(s.device) },
                    onMount = { password, pim, hidden, readOnly ->
                        scope.launch {
                            usbDriveManager.mount(s.device, password, pim, hidden, readOnly)
                        }
                    }
                )
            }

            is UsbDriveState.Unlocking -> {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("Decrypting VeraCrypt header…")
                    }
                }
            }

            is UsbDriveState.Mounted -> {
                MountedCard(
                    label      = s.label,
                    blockCount = s.blockCount,
                    blockSize  = s.blockSize,
                    onLock     = { usbDriveManager.close() }
                )
                HorizontalDivider()
                UsbFileBrowser(
                    usbDriveManager = usbDriveManager,
                    cacheDir        = context.cacheDir,
                    isReadOnly      = usbDriveManager.isReadOnly()
                )
            }

            is UsbDriveState.Error -> {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text  = "Error",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(s.message)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { usbDriveManager.close() }) { Text("Dismiss") }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun IdleCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text  = "No USB drive connected",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text  = "Plug in a USB mass-storage device containing a VeraCrypt volume.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DeviceAttachedCard(
    device: UsbDevice,
    name: String,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onMount: (password: CharArray, pim: Int, hidden: Boolean, readOnly: Boolean) -> Unit
) {
    var showUnlockDialog by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("USB Drive Detected", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text("Name: $name", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Vendor/Product: 0x${device.vendorId.toString(16)} / 0x${device.productId.toString(16)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            if (!hasPermission) {
                Button(onClick = onRequestPermission, modifier = Modifier.fillMaxWidth()) {
                    Text("Grant USB Permission")
                }
            } else {
                Button(onClick = { showUnlockDialog = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Unlock VeraCrypt Volume")
                }
            }
        }
    }

    if (showUnlockDialog) {
        UnlockDialog(
            onDismiss = { showUnlockDialog = false },
            onConfirm = { password, pim, hidden, readOnly ->
                showUnlockDialog = false
                onMount(password, pim, hidden, readOnly)
            }
        )
    }
}

@Composable
private fun UnlockDialog(
    onDismiss: () -> Unit,
    onConfirm: (password: CharArray, pim: Int, hidden: Boolean, readOnly: Boolean) -> Unit
) {
    var passwordText by remember { mutableStateOf("") }
    var pimText      by remember { mutableStateOf("") }
    var hidden       by remember { mutableStateOf(false) }
    var readOnly     by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title   = { Text("Unlock USB VeraCrypt Drive") },
        text    = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SecureTextField(
                    value         = passwordText,
                    onValueChange = { passwordText = it },
                    label         = "Password",
                    modifier      = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value         = pimText,
                    onValueChange = { pimText = it.filter { c -> c.isDigit() } },
                    label         = { Text("PIM (0 = default)") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth()
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Hidden volume")
                    Switch(checked = hidden, onCheckedChange = { hidden = it })
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Read-only")
                    Switch(checked = readOnly, onCheckedChange = { readOnly = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val pim = pimText.toIntOrNull() ?: 0
                    onConfirm(passwordText.toCharArray(), pim, hidden, readOnly)
                    passwordText = ""
                },
                enabled = passwordText.isNotEmpty()
            ) { Text("Unlock") }
        },
        dismissButton = {
            Button(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun MountedCard(
    label: String,
    blockCount: Long,
    blockSize: Int,
    onLock: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Mounted: $label", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text  = "${blockCount * blockSize / 1_048_576} MiB · $blockSize-byte sectors",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onLock,
                colors  = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Lock / Unmount") }
        }
    }
}

// ─── File browser ─────────────────────────────────────────────────────────────

@Composable
private fun UsbFileBrowser(
    usbDriveManager: UsbDriveManager,
    cacheDir: File,
    isReadOnly: Boolean
) {
    var currentPath by remember { mutableStateOf("/") }
    var entries     by remember { mutableStateOf<List<String>>(emptyList()) }
    var selected    by remember { mutableStateOf<Set<String>>(emptySet()) }
    var statusMsg   by remember { mutableStateOf<String?>(null) }
    var exporting   by remember { mutableStateOf(false) }
    var importing   by remember { mutableStateOf(false) }
    val scope       = rememberCoroutineScope()
    val context     = LocalContext.current

    val exportDir    = remember(cacheDir) { File(cacheDir, "usb-export") }
    val importTmpDir = remember(cacheDir) { File(cacheDir, "usb-import-tmp") }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            importing = true
            statusMsg = "Importing ${uris.size} file(s)…"
            importTmpDir.mkdirs()

            var successCount = 0
            var failCount    = 0

            for (uri in uris) {
                val displayName = contentDisplayName(context, uri, "file")
                val safeName    = sanitizeFileName(displayName, "file")
                val tmpFile     = File(importTmpDir, "import-${System.currentTimeMillis()}-$safeName")

                val copied = withContext(Dispatchers.IO) { copyUriToFile(context, uri, tmpFile) }
                if (copied) {
                    val destPath = if (currentPath == "/") "/$safeName" else "$currentPath/$safeName"
                    val rc       = withContext(Dispatchers.IO) {
                        usbDriveManager.writeFile(destPath, tmpFile.absolutePath)
                    }
                    withContext(Dispatchers.IO) { tmpFile.delete() }
                    if (rc == 0) successCount++ else failCount++
                } else {
                    failCount++
                }
            }

            importing = false
            statusMsg = when {
                failCount == 0    -> "Imported $successCount file(s)"
                successCount == 0 -> "Import failed for all ${uris.size} file(s)"
                else              -> "Imported $successCount, failed $failCount"
            }

            entries = usbDriveManager.list(currentPath).toList()
        }
    }

    // Reset selection when directory changes
    androidx.compose.runtime.LaunchedEffect(currentPath) {
        entries  = usbDriveManager.list(currentPath).toList()
        selected = emptySet()
    }

    val fileEntries = entries.filter { !it.endsWith("/") }
    val allSelected = fileEntries.isNotEmpty() && selected.containsAll(fileEntries)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {

        // ── Navigation row ────────────────────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (currentPath != "/") {
                Button(
                    onClick = {
                        val parent = currentPath.substringBeforeLast('/', "/")
                        currentPath = if (parent.isEmpty()) "/" else parent
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("← Back") }
            }
            Text(
                text     = currentPath,
                style    = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(3f)
            )
        }

        // ── Toolbar: Export Selected + Import Files + Clear Cache ────────────
        val busy = exporting || importing
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    scope.launch {
                        exporting = true
                        statusMsg = "Exporting ${selected.size} file(s)…"
                        exportDir.mkdirs()

                        val uris = ArrayList<android.net.Uri>()
                        var failCount = 0
                        for (name in selected) {
                            val fullPath = if (currentPath == "/") "/$name" else "$currentPath/$name"
                            val safeName = sanitizeFileName(name.trimEnd('/'), "file")
                            val dest = File(exportDir, safeName)
                            val rc = withContext(Dispatchers.IO) {
                                usbDriveManager.readFile(fullPath, dest.absolutePath)
                            }
                            if (rc == 0) {
                                uris += FileProvider.getUriForFile(
                                    context, "${context.packageName}.fileprovider", dest
                                )
                            } else {
                                failCount++
                            }
                        }

                        exporting = false
                        statusMsg = when {
                            uris.isEmpty()   -> "Export failed for all ${selected.size} file(s)"
                            failCount > 0    -> "Exported ${uris.size}, failed $failCount"
                            else             -> "Exported ${uris.size} file(s)"
                        }

                        if (uris.isNotEmpty()) {
                            val intent = if (uris.size == 1) {
                                Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uris[0], context.contentResolver.getType(uris[0]) ?: "*/*")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            } else {
                                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                    type = "*/*"
                                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            }
                            context.startActivity(Intent.createChooser(intent, "Open with").apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                        }
                    }
                },
                enabled  = selected.isNotEmpty() && !busy,
                modifier = Modifier.weight(1f)
            ) {
                Text(if (selected.isEmpty()) "Export" else "Export (${selected.size})")
            }

            Button(
                onClick  = { importLauncher.launch("*/*") },
                enabled  = !isReadOnly && !busy,
                modifier = Modifier.weight(1f)
            ) {
                Text(if (importing) "Importing…" else "Import")
            }
        }

        OutlinedButton(
            onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        exportDir.deleteRecursively()
                        exportDir.mkdirs()
                    }
                    statusMsg = "Export cache cleared"
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Clear Cache") }

        // ── Status message ────────────────────────────────────────────────────
        statusMsg?.let { msg ->
            Text(
                text  = msg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // ── File / directory listing ──────────────────────────────────────────
        if (fileEntries.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked         = allSelected,
                    onCheckedChange = { checked ->
                        selected = if (checked) fileEntries.toSet() else emptySet()
                    }
                )
                Text(
                    text  = if (allSelected) "Deselect all" else "Select all",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HorizontalDivider()
        }

        entries.forEach { name ->
            val fullPath = if (currentPath == "/") "/$name" else "$currentPath/$name"
            val isDir    = name.endsWith("/")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isDir) {
                    Spacer(Modifier.padding(start = 12.dp))
                    Text(
                        text     = name,
                        style    = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = { currentPath = fullPath.trimEnd('/') }) { Text("Open") }
                } else {
                    Checkbox(
                        checked         = name in selected,
                        onCheckedChange = { checked ->
                            selected = if (checked) selected + name else selected - name
                        }
                    )
                    Text(
                        text     = name,
                        style    = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
