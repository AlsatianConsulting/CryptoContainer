package dev.alsatianconsulting.cryptocontainer.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.alsatianconsulting.cryptocontainer.model.Algorithm
import dev.alsatianconsulting.cryptocontainer.model.FileSystem
import dev.alsatianconsulting.cryptocontainer.model.Hash
import dev.alsatianconsulting.cryptocontainer.model.VolumeCreateOptions
import dev.alsatianconsulting.cryptocontainer.ui.theme.BrandOrange
import androidx.compose.ui.platform.LocalContext

private const val MEBIBYTE = 1024L * 1024L
private const val VC_TOTAL_HEADERS_BYTES = 4L * 64L * 1024L
private const val VC_MIN_FAT_FS_BYTES = 9L * 4096L
private const val VC_MIN_NTFS_FS_BYTES = 884L * 4096L
private const val VC_MIN_EXFAT_FS_BYTES = 42L * 4096L
private const val VC_MIN_HIDDEN_BYTES = VC_MIN_FAT_FS_BYTES + 4096L

private fun minimumHostBytesFor(filesystem: FileSystem): Long = VC_TOTAL_HEADERS_BYTES + when (filesystem) {
    FileSystem.EXFAT -> VC_MIN_EXFAT_FS_BYTES
    FileSystem.NTFS -> VC_MIN_NTFS_FS_BYTES
    FileSystem.FAT -> VC_MIN_FAT_FS_BYTES
}

private fun minimumHostMegabytesFor(filesystem: FileSystem): Long =
    ((minimumHostBytesFor(filesystem) + MEBIBYTE - 1) / MEBIBYTE).coerceAtLeast(1L)

private fun minimumHiddenMegabytes(): Long =
    ((VC_MIN_HIDDEN_BYTES + MEBIBYTE - 1) / MEBIBYTE).coerceAtLeast(1L)

@Composable
fun CreationWizard(
    modifier: Modifier = Modifier,
    onCreate: (VolumeCreateOptions) -> Unit,
    onCancel: () -> Unit
) {
    var uri by remember { mutableStateOf("") }
    var size by remember { mutableStateOf("512") }
    var createHiddenVolume by remember { mutableStateOf(false) }
    var hiddenSize by remember { mutableStateOf("") }
    var filesystem by remember { mutableStateOf(FileSystem.EXFAT) }
    var algorithm by remember { mutableStateOf(Algorithm.AES) }
    var hash by remember { mutableStateOf(Hash.SHA512) }
    var password by remember { mutableStateOf("") }
    var keyfileUris by remember { mutableStateOf<List<String>>(emptyList()) }
    var pim by remember { mutableStateOf("") }
    var hiddenPassword by remember { mutableStateOf("") }
    var hiddenKeyfileUris by remember { mutableStateOf<List<String>>(emptyList()) }
    var hiddenPim by remember { mutableStateOf("") }
    val sizeMb = size.toLongOrNull() ?: 0L
    val hiddenSizeMb = hiddenSize.toLongOrNull() ?: 0L
    val minimumSizeMb = minimumHostMegabytesFor(filesystem)
    val minimumHiddenMb = minimumHiddenMegabytes()
    val validationError = when {
        uri.isBlank() -> "Choose an output file."
        sizeMb <= 0L -> "Enter a volume size."
        sizeMb < minimumSizeMb -> "${filesystem.displayLabel()} volumes must be at least ${minimumSizeMb} MB."
        password.isBlank() -> "Enter a password."
        createHiddenVolume && hiddenSizeMb <= 0L -> "Enter a hidden volume size."
        createHiddenVolume && hiddenSizeMb < minimumHiddenMb ->
            "Hidden volumes must be at least ${minimumHiddenMb} MB."
        createHiddenVolume && hiddenSizeMb >= sizeMb ->
            "Hidden size must be smaller than the outer volume size."
        else -> null
    }
    val context = LocalContext.current
    fun persistReadPermission(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
        }
    }
    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { picked ->
        picked?.let {
            uri = it.toString()
            try {
                context.contentResolver.takePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: SecurityException) { }
        }
    }
    val pickOuterKeyfiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked ->
        if (picked.isEmpty()) return@rememberLauncherForActivityResult
        picked.forEach(::persistReadPermission)
        keyfileUris = picked.map(Uri::toString).distinct()
    }
    val pickHiddenKeyfiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked ->
        if (picked.isEmpty()) return@rememberLauncherForActivityResult
        picked.forEach(::persistReadPermission)
        hiddenKeyfileUris = picked.map(Uri::toString).distinct()
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Create VeraCrypt Volume", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        OutlinedTextField(
            value = uri,
            onValueChange = { uri = it },
            label = { Text("Container File") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { createDoc.launch("container.hc") },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
        ) { Text("Choose Output File") }

        Text("Volume Type", style = MaterialTheme.typography.bodyMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SelectableOptionButton(
                text = "Standard",
                selected = !createHiddenVolume,
                onClick = { createHiddenVolume = false },
                modifier = Modifier.weight(1f)
            )
            SelectableOptionButton(
                text = "Hidden",
                selected = createHiddenVolume,
                onClick = { createHiddenVolume = true },
                modifier = Modifier.weight(1f)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = size,
                onValueChange = { size = it.filter { ch -> ch.isDigit() } },
                label = {
                    Text(if (createHiddenVolume) "Outer size (MB)" else "Size (MB)")
                },
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            "Minimum size for ${filesystem.displayLabel()}: ${minimumSizeMb} MB",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )

        SecureTextField(
            value = password,
            onValueChange = { password = it },
            label = "Password",
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = pim,
            onValueChange = { pim = it.filter { ch -> ch.isDigit() } },
            label = { Text("PIM (optional)") },
            modifier = Modifier.fillMaxWidth()
        )
        KeyfilePickerField(
            label = "Outer Keyfiles",
            selectedUris = keyfileUris,
            onPick = { pickOuterKeyfiles.launch(arrayOf("*/*")) },
            onClear = { keyfileUris = emptyList() },
            helperText = "Selected keyfiles are combined with the outer password when the volume is created."
        )
        if (createHiddenVolume) {
            Text("Hidden Volume Settings", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = hiddenSize,
                onValueChange = { hiddenSize = it.filter { ch -> ch.isDigit() } },
                label = { Text("Hidden size (MB)") },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Minimum hidden size: ${minimumHiddenMb} MB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
            SecureTextField(
                value = hiddenPassword,
                onValueChange = { hiddenPassword = it },
                label = "Hidden Password (optional)",
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = hiddenPim,
                onValueChange = { hiddenPim = it.filter { ch -> ch.isDigit() } },
                label = { Text("Hidden PIM (optional)") },
                modifier = Modifier.fillMaxWidth()
            )
            KeyfilePickerField(
                label = "Hidden Keyfiles",
                selectedUris = hiddenKeyfileUris,
                onPick = { pickHiddenKeyfiles.launch(arrayOf("*/*")) },
                onClear = { hiddenKeyfileUris = emptyList() },
                helperText = "If left empty, the hidden volume will reuse the outer keyfiles."
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val exfatSelected = filesystem == FileSystem.EXFAT
            SelectableOptionButton(
                text = "exFAT",
                selected = exfatSelected,
                onClick = { filesystem = FileSystem.EXFAT },
                modifier = Modifier.weight(1f),
                compact = true
            )

            val ntfsSelected = filesystem == FileSystem.NTFS
            SelectableOptionButton(
                text = "NTFS",
                selected = ntfsSelected,
                onClick = { filesystem = FileSystem.NTFS },
                modifier = Modifier.weight(1f),
                compact = true
            )

            val fatSelected = filesystem == FileSystem.FAT
            SelectableOptionButton(
                text = "FAT",
                selected = fatSelected,
                onClick = { filesystem = FileSystem.FAT },
                modifier = Modifier.weight(1f),
                compact = true
            )
        }
        Text("Algorithm", style = MaterialTheme.typography.bodyMedium)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Algorithm.entries.forEach { option ->
                SelectableOptionButton(
                    text = option.displayLabel(),
                    selected = algorithm == option,
                    onClick = { algorithm = option },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        if (filesystem == FileSystem.NTFS) {
            Text(
                "NTFS supports create/open in-app; writable if you open without Read-only enabled.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
        } else if (filesystem == FileSystem.FAT) {
            Text(
                "FAT create/open is supported in-app (FAT12/16/32 via userspace FAT backend).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }

        Text("Hash", style = MaterialTheme.typography.bodyMedium)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Hash.entries.forEach { option ->
                SelectableOptionButton(
                    text = option.displayLabel(),
                    selected = hash == option,
                    onClick = { hash = option },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (validationError != null) {
            Text(
                validationError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                val opts = VolumeCreateOptions(
                    containerUri = uri.ifBlank { "" },
                    sizeBytes = sizeMb * MEBIBYTE,
                    filesystem = filesystem,
                    algorithm = algorithm,
                    hash = hash,
                    password = password.toCharArray(),
                    keyfileUris = keyfileUris,
                    pim = pim.toIntOrNull(),
                    hiddenSizeBytes = if (createHiddenVolume) {
                        hiddenSizeMb * MEBIBYTE
                    } else {
                        null
                    },
                    hiddenPassword = if (createHiddenVolume) hiddenPassword.ifBlank { null }?.toCharArray() else null,
                    hiddenKeyfileUris = if (createHiddenVolume) hiddenKeyfileUris else emptyList(),
                    hiddenPim = if (createHiddenVolume) hiddenPim.toIntOrNull() else null
                )
                onCreate(opts)
            },
                enabled = validationError == null
            ) { Text("Create") }
            Button(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun SelectableOptionButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        border = if (selected) null else BorderStroke(1.dp, BrandOrange),
        contentPadding = if (compact) {
            PaddingValues(horizontal = 6.dp, vertical = 8.dp)
        } else {
            ButtonDefaults.ContentPadding
        },
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) BrandOrange else Color.Transparent,
            contentColor = if (selected) Color.Black else BrandOrange
        )
    ) {
        Text(
            text = text,
            style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
            maxLines = 1
        )
    }
}

private fun Algorithm.displayLabel(): String = when (this) {
    Algorithm.AES -> "AES"
    Algorithm.SERPENT -> "Serpent"
    Algorithm.TWOFISH -> "Twofish"
    Algorithm.AES_SERPENT -> "AES-Serpent"
    Algorithm.SERPENT_AES -> "Serpent-AES"
    Algorithm.SERPENT_TWOFISH_AES -> "Serpent-Twofish-AES"
    Algorithm.TWOFISH_SERPENT -> "Twofish-Serpent"
}

private fun Hash.displayLabel(): String = when (this) {
    Hash.SHA512 -> "SHA-512"
    Hash.WHIRLPOOL -> "Whirlpool"
}

private fun FileSystem.displayLabel(): String = when (this) {
    FileSystem.EXFAT -> "exFAT"
    FileSystem.NTFS -> "NTFS"
    FileSystem.FAT -> "FAT"
}
