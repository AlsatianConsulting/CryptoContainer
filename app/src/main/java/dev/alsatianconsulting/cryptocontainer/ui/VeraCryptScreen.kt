package dev.alsatianconsulting.cryptocontainer.ui

import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.content.ActivityNotFoundException
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.alsatianconsulting.cryptocontainer.MountController
import dev.alsatianconsulting.cryptocontainer.manager.MountMode
import dev.alsatianconsulting.cryptocontainer.manager.VeraCryptManager
import dev.alsatianconsulting.cryptocontainer.model.FileSystem
import dev.alsatianconsulting.cryptocontainer.model.VcEntry
import dev.alsatianconsulting.cryptocontainer.model.VcMountWarning
import dev.alsatianconsulting.cryptocontainer.model.VolumeCreateOptions
import dev.alsatianconsulting.cryptocontainer.repo.VC_ERR_KEYFILE_IO
import dev.alsatianconsulting.cryptocontainer.repo.VeraCryptRepository
import dev.alsatianconsulting.cryptocontainer.util.contentDisplayName
import dev.alsatianconsulting.cryptocontainer.util.copyFileToUri
import dev.alsatianconsulting.cryptocontainer.util.copyFileToTree
import dev.alsatianconsulting.cryptocontainer.util.copyUriToFile
import dev.alsatianconsulting.cryptocontainer.util.sanitizeFileName
import dev.alsatianconsulting.cryptocontainer.viewmodel.ShareAction
import kotlinx.coroutines.launch

private fun describeFsOpFailure(action: String, rc: Int): String = when (rc) {
    -30 -> "$action failed: container is read-only"
    -2 -> "$action failed: file or path not found ($rc)"
    -13 -> "$action failed: permission denied ($rc)"
    -17 -> "$action failed: file already exists ($rc)"
    -21 -> "$action failed: path is a directory ($rc)"
    -22 -> "$action failed: invalid name or path ($rc)"
    -28 -> "$action failed: no space left in container ($rc)"
    -39 -> "$action failed: directory is not empty ($rc)"
    -95 -> "$action failed: unsupported operation for this filesystem ($rc)"
    else -> "$action failed ($rc)"
}

private fun guessMimeType(fileName: String): String {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    if (ext.isBlank()) return "application/octet-stream"
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
}

private fun minimumCreateSizeMb(filesystem: FileSystem): Long = when (filesystem) {
    FileSystem.EXFAT, FileSystem.FAT -> 1L
    FileSystem.NTFS -> 4L
}

private fun createFilesystemLabel(filesystem: FileSystem): String = when (filesystem) {
    FileSystem.EXFAT -> "exFAT"
    FileSystem.NTFS -> "NTFS"
    FileSystem.FAT -> "FAT"
}

private fun describeCreateFailure(rc: Int, options: VolumeCreateOptions): String = when (rc) {
    VC_ERR_KEYFILE_IO -> "Create failed: could not read one or more keyfiles."
    -22 -> {
        val base = "${createFilesystemLabel(options.filesystem)} volumes need at least " +
            "${minimumCreateSizeMb(options.filesystem)} MB"
        if ((options.hiddenSizeBytes ?: 0L) > 0L) {
            "Create failed: check password, output file, outer size, and hidden size. $base."
        } else {
            "Create failed: check password, output file, and size. $base."
        }
    }
    else -> "Create failed ($rc)"
}

private fun describeCreateProgress(options: VolumeCreateOptions): String {
    val typeLabel = if ((options.hiddenSizeBytes ?: 0L) > 0L) "hidden" else "standard"
    val sizeMb = options.sizeBytes / (1024L * 1024L)
    return "Creating $typeLabel ${createFilesystemLabel(options.filesystem)} volume (${sizeMb} MB)."
}

private fun describeOpenProgress(readOnly: Boolean, preferHidden: Boolean): String {
    val modeText = if (readOnly) "in read-only mode" else ""
    val orderText = if (preferHidden) {
        "Trying hidden first, then standard."
    } else {
        "Trying standard first, then hidden."
    }
    return listOf("Opening container", modeText.ifBlank { null }, orderText)
        .filterNotNull()
        .joinToString(" ")
        .replace("  ", " ")
        .trim()
}

private fun fallbackContainerName(rawUri: String): String {
    if (rawUri.isBlank()) return "Unknown container"
    val parsed = Uri.parse(rawUri)
    val fileLike = parsed.path?.substringAfterLast('/')?.ifBlank { null }
        ?: parsed.lastPathSegment?.substringAfterLast('/')?.ifBlank { null }
        ?: rawUri.substringAfterLast('/').ifBlank { null }
    return fileLike ?: "Unknown container"
}

private fun resolveContainerName(context: Context, rawUri: String): String {
    if (rawUri.isBlank()) return "Unknown container"
    val parsed = Uri.parse(rawUri)
    val fallback = fallbackContainerName(rawUri)
    return if (parsed.scheme == "content") {
        contentDisplayName(context, parsed, fallback)
    } else {
        fallback
    }
}

private data class VcTransferResult(
    val rc: Int,
    val cleanupDeleteRc: Int? = null
)

private fun isSameOrDescendantPath(path: String, ancestorPath: String): Boolean {
    val pathNorm = path.trim('/')
    val ancestorNorm = ancestorPath.trim('/')
    if (ancestorNorm.isEmpty()) return true
    return pathNorm == ancestorNorm || pathNorm.startsWith("$ancestorNorm/")
}

private suspend fun deleteEntryRecursively(
    repo: VeraCryptRepository,
    entry: VcEntry
): Int {
    if (!entry.isDir) return repo.delete(entry.path)

    suspend fun deleteDir(dirPath: String): Int {
        val children = repo.list(dirPath)
        for (child in children) {
            val rc = if (child.isDir) deleteDir(child.path) else repo.delete(child.path)
            if (rc != 0) return rc
        }
        return repo.delete(dirPath)
    }

    return deleteDir(entry.path)
}

private fun pruneNestedEntries(entries: List<VcEntry>): List<VcEntry> {
    val sorted = entries.distinctBy { it.path }.sortedBy { it.path.length }
    return sorted.filter { candidate ->
        sorted.none { other ->
            other.path != candidate.path && isSameOrDescendantPath(candidate.path, other.path)
        }
    }
}

private suspend fun copyOrMoveFileInsideContainer(
    context: Context,
    repo: VeraCryptRepository,
    sourcePath: String,
    targetPath: String,
    move: Boolean
): VcTransferResult {
    val tempDir = context.cacheDir.resolve("vc-clipboard").apply { mkdirs() }
    val sourceName = sanitizeFileName(sourcePath.substringAfterLast('/').ifBlank { "temp.bin" }, "temp.bin")
    val temp = tempDir.resolve("${System.currentTimeMillis()}-$sourceName")
    return try {
        val extractRc = repo.extract(sourcePath, temp.absolutePath)
        if (extractRc != 0) return VcTransferResult(extractRc)
        val addRc = repo.add(targetPath, temp.absolutePath)
        if (addRc != 0) return VcTransferResult(addRc)
        if (move) {
            val deleteRc = repo.delete(sourcePath)
            if (deleteRc != 0) return VcTransferResult(0, cleanupDeleteRc = deleteRc)
        }
        VcTransferResult(0)
    } finally {
        temp.delete()
    }
}

private suspend fun copyOrMoveEntryInsideContainer(
    context: Context,
    repo: VeraCryptRepository,
    entry: VcEntry,
    targetPath: String,
    move: Boolean
): VcTransferResult {
    if (!entry.isDir) {
        return copyOrMoveFileInsideContainer(
            context = context,
            repo = repo,
            sourcePath = entry.path,
            targetPath = targetPath,
            move = move
        )
    }

    val createRootRc = repo.mkdir(targetPath)
    if (createRootRc != 0) return VcTransferResult(createRootRc)

    var cleanupDeleteRc: Int? = null
    val sourceDirs = mutableListOf(entry.path)

    suspend fun copyDirContents(sourceDir: String, destDir: String): Int {
        val children = repo.list(sourceDir).sortedWith(
            compareBy<VcEntry> { !it.isDir }.thenBy { it.path.lowercase() }
        )
        for (child in children) {
            val childName = child.path.substringAfterLast('/').ifBlank { "item" }
            val childTarget = if (destDir.isBlank()) childName else "$destDir/$childName"
            if (child.isDir) {
                val mkdirRc = repo.mkdir(childTarget)
                if (mkdirRc != 0) return mkdirRc
                sourceDirs += child.path
                val recurseRc = copyDirContents(child.path, childTarget)
                if (recurseRc != 0) return recurseRc
            } else {
                val fileResult = copyOrMoveFileInsideContainer(
                    context = context,
                    repo = repo,
                    sourcePath = child.path,
                    targetPath = childTarget,
                    move = false
                )
                if (fileResult.rc != 0) return fileResult.rc
                if (move) {
                    val deleteRc = repo.delete(child.path)
                    if (deleteRc != 0) {
                        cleanupDeleteRc = deleteRc
                        return 0
                    }
                }
            }
        }
        return 0
    }

    val copyRc = copyDirContents(entry.path, targetPath)
    if (copyRc != 0) return VcTransferResult(copyRc)
    if (cleanupDeleteRc != null) return VcTransferResult(0, cleanupDeleteRc = cleanupDeleteRc)

    if (move) {
        for (dirPath in sourceDirs.asReversed()) {
            val deleteRc = repo.delete(dirPath)
            if (deleteRc != 0) {
                cleanupDeleteRc = deleteRc
                break
            }
        }
    }

    return VcTransferResult(0, cleanupDeleteRc = cleanupDeleteRc)
}

@Composable
fun VeraCryptScreen(
    modifier: Modifier = Modifier,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    manager: VeraCryptManager,
    sharedUris: List<String>,
    shareAction: ShareAction?,
    clearShared: () -> Unit
) {
    val volumeState by manager.volumeState.collectAsState(initial = null)
    val (uri, setUri) = remember { mutableStateOf("") }
    val (password, setPassword) = remember { mutableStateOf("") }
    val (keyfileUris, setKeyfileUris) = remember { mutableStateOf<List<String>>(emptyList()) }
    val (pim, setPim) = remember { mutableStateOf("") }
    val (readOnly, setReadOnly) = remember { mutableStateOf(false) }
    val showCreate = remember { mutableStateOf(false) }
    val showOuterWriteWarning = remember { mutableStateOf(false) }
    val opMessage = remember { mutableStateOf("") }
    val createInProgress = remember { mutableStateOf(false) }
    val createProgressMessage = remember { mutableStateOf("") }
    val openInProgress = remember { mutableStateOf(false) }
    val openProgressMessage = remember { mutableStateOf("") }
    val pendingExtract = remember { mutableStateOf<VcEntry?>(null) }
    val pendingExtractEntries = remember { mutableStateOf<List<VcEntry>>(emptyList()) }
    val pendingAddDir = remember { mutableStateOf("") }
    val vcClipboardEntries = remember { mutableStateOf<List<VcEntry>>(emptyList()) }
    val vcClipboardIsCut = remember { mutableStateOf(false) }
    val showExplorer = remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val repo = MountController.veraRepo
    val fsInfo by repo.fsInfo.collectAsState(initial = null)

    suspend fun importUrisIntoDirectory(
        sources: List<Uri>,
        dir: String,
        actionLabel: String
    ): String {
        var addedCount = 0
        var failedCount = 0
        var firstFailure: String? = null

        sources.distinctBy(Uri::toString).forEach { source ->
            val name = sanitizeFileName(contentDisplayName(context, source, "import.bin"), "import.bin")
            val targetPath = if (dir.isBlank()) name else "$dir/$name"
            val temp = context.cacheDir.resolve("vc-import-${System.currentTimeMillis()}-$name")
            try {
                if (!copyUriToFile(context, source, temp)) {
                    failedCount += 1
                    if (firstFailure == null) {
                        firstFailure = "$name: cannot read source"
                    }
                    return@forEach
                }
                val rc = repo.add(targetPath, temp.absolutePath)
                if (rc == 0) {
                    addedCount += 1
                } else {
                    failedCount += 1
                    if (firstFailure == null) {
                        firstFailure = "$name: " +
                            describeFsOpFailure(actionLabel, rc).removePrefix("$actionLabel failed: ")
                    }
                }
            } finally {
                temp.delete()
            }
        }

        return when {
            addedCount > 0 && failedCount == 0 -> {
                repo.refresh(dir)
                if (addedCount == 1) "Added 1 file" else "Added $addedCount files"
            }
            addedCount > 0 -> {
                repo.refresh(dir)
                "Added $addedCount files, $failedCount failed"
            }
            else -> {
                "$actionLabel failed: ${firstFailure ?: "no files could be imported"}"
            }
        }
    }

    fun setClipboardEntries(entries: List<VcEntry>, cut: Boolean) {
        val topLevel = pruneNestedEntries(entries)
        vcClipboardEntries.value = topLevel
        vcClipboardIsCut.value = cut
        opMessage.value = when (topLevel.size) {
            0 -> "Clipboard cleared"
            1 -> "${if (cut) "Cut" else "Copied"} ${topLevel.first().path} to clipboard"
            else -> "${if (cut) "Cut" else "Copied"} ${topLevel.size} items to clipboard"
        }
    }

    fun clearClipboardEntriesCoveredBy(entry: VcEntry) {
        val remaining = vcClipboardEntries.value.filterNot { clip ->
            isSameOrDescendantPath(clip.path, entry.path)
        }
        vcClipboardEntries.value = remaining
        if (remaining.isEmpty()) {
            vcClipboardIsCut.value = false
        }
    }

    fun startCreateVolume(options: VolumeCreateOptions) {
        createInProgress.value = true
        createProgressMessage.value = describeCreateProgress(options)
        showCreate.value = false
        scope.launch {
            val rc = manager.create(context, options)
            createInProgress.value = false
            opMessage.value = if (rc == 0) {
                "Volume created"
            } else {
                describeCreateFailure(rc, options)
            }
        }
    }

    @Composable
    fun OpenVolumeProgressDialog() {
        if (openInProgress.value) {
            AlertDialog(
                onDismissRequest = {},
                confirmButton = {},
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Text(
                            "Opening VeraCrypt Volume",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            openProgressMessage.value.ifBlank { "Opening volume..." },
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "Hidden-volume detection can take longer than a standard open.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            )
        }
    }

    @Composable
    fun CreateVolumeDialogs() {
        if (showCreate.value) {
            AlertDialog(
                onDismissRequest = {
                    if (!createInProgress.value) {
                        showCreate.value = false
                    }
                },
                confirmButton = {},
                text = {
                    CreationWizard(
                        onCreate = { opts -> startCreateVolume(opts) },
                        onCancel = { showCreate.value = false }
                    )
                }
            )
        }

        if (createInProgress.value) {
            AlertDialog(
                onDismissRequest = {},
                confirmButton = {},
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Text(
                            "Creating VeraCrypt Volume",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            createProgressMessage.value.ifBlank { "Creating volume..." },
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "The app is still working. Larger volumes can take a while.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            )
        }
    }

    LaunchedEffect(volumeState?.uri, volumeState?.hidden, volumeState?.mounted) {
        showExplorer.value = false
        if (volumeState != null) {
            scrollState.animateScrollTo(0)
        }
    }

    LaunchedEffect(sharedUris, shareAction, volumeState?.uri, volumeState?.mounted) {
        if (sharedUris.isEmpty() || shareAction == null) return@LaunchedEffect

        when (shareAction) {
            ShareAction.VERACRYPT_IMPORT -> {
                if (volumeState == null) {
                    opMessage.value = "This Action Requires Mounting A VeraCrypt Container"
                    clearShared()
                    return@LaunchedEffect
                }

                showExplorer.value = true
                MountController.onActivity()
                opMessage.value = importUrisIntoDirectory(
                    sources = sharedUris.map(Uri::parse),
                    dir = "",
                    actionLabel = "Shared import"
                )
                clearShared()
            }

            ShareAction.VERACRYPT_CONTAINER_FILE -> {
                val firstUri = Uri.parse(sharedUris.first())
                setUri(firstUri.toString())
                try {
                    context.contentResolver.takePersistableUriPermission(
                        firstUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: SecurityException) {
                }
                opMessage.value = if (sharedUris.size > 1) {
                    "Using the first shared file as the VeraCrypt container file"
                } else {
                    "Shared VeraCrypt container file selected"
                }
                clearShared()
                scrollState.animateScrollTo(0)
            }

            else -> Unit
        }
    }

    val pickContainer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        picked?.let {
            setUri(it.toString())
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: SecurityException) {
            }
        }
    }

    val pickKeyfiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked ->
        if (picked.isEmpty()) return@rememberLauncherForActivityResult
        picked.forEach { selected ->
            try {
                context.contentResolver.takePersistableUriPermission(
                    selected,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
            }
        }
        setKeyfileUris(picked.map(Uri::toString).distinct())
    }

    val pickExtractDestination = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { picked ->
        val entry = pendingExtract.value
        if (picked == null || entry == null) return@rememberLauncherForActivityResult
        scope.launch {
            MountController.onActivity()
            val temp = context.cacheDir.resolve("vc-extract-${System.currentTimeMillis()}.bin")
            val rc = repo.extract(entry.path, temp.absolutePath)
            opMessage.value = if (rc == 0 && copyFileToUri(context, temp, picked)) {
                "Extracted ${entry.path}"
            } else {
                "Extract failed ($rc)"
            }
            temp.delete()
        }
    }

    val pickExtractFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { picked ->
        val entries = pendingExtractEntries.value
        if (picked == null || entries.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            MountController.onActivity()
            var extractedCount = 0
            var failedCount = 0
            var firstFailure: String? = null

            entries.distinctBy { it.path }.forEach { entry ->
                if (entry.isDir) {
                    failedCount += 1
                    if (firstFailure == null) {
                        firstFailure = "${entry.path}: folders are not supported for bulk extract"
                    }
                    return@forEach
                }
                val name = sanitizeFileName(
                    entry.path.substringAfterLast('/').ifBlank { "extracted.bin" },
                    "extracted.bin"
                )
                val temp = context.cacheDir.resolve("vc-extract-${System.currentTimeMillis()}-$name")
                try {
                    val rc = repo.extract(entry.path, temp.absolutePath)
                    if (rc != 0) {
                        failedCount += 1
                        if (firstFailure == null) {
                            firstFailure = "${entry.path}: ${describeFsOpFailure("Extract", rc)}"
                        }
                        return@forEach
                    }
                    val out = copyFileToTree(context, temp, picked, name, guessMimeType(name))
                    if (out != null) {
                        extractedCount += 1
                    } else {
                        failedCount += 1
                        if (firstFailure == null) {
                            firstFailure = "${entry.path}: could not write destination file"
                        }
                    }
                } finally {
                    temp.delete()
                }
            }

            opMessage.value = when {
                extractedCount > 0 && failedCount == 0 ->
                    if (extractedCount == 1) "Extracted 1 file" else "Extracted $extractedCount files"
                extractedCount > 0 ->
                    "Extracted $extractedCount files, $failedCount failed"
                else ->
                    "Extract failed: ${firstFailure ?: "no files could be extracted"}"
            }
            pendingExtractEntries.value = emptyList()
        }
    }

    val pickAddSource = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked ->
        if (picked.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            MountController.onActivity()
            opMessage.value = importUrisIntoDirectory(
                sources = picked,
                dir = pendingAddDir.value,
                actionLabel = "Add"
            )
        }
    }

    fun openSelectedVolume() {
        if (uri.isBlank()) {
            opMessage.value = "Select container URI first"
            return
        }
        val targetUri = uri
        val preferHidden = manager.preferredHiddenFor(targetUri) == true
        MountController.onActivity()
        onStartService()
        openInProgress.value = true
        openProgressMessage.value = describeOpenProgress(readOnly, preferHidden)
        scope.launch {
            try {
                val passwordValue = password
                val pimValue = pim.toIntOrNull() ?: 0
                val parsedUri = Uri.parse(targetUri)
                suspend fun tryOpen(hidden: Boolean): Int = repo.open(
                    context = context,
                    uri = parsedUri,
                    password = passwordValue,
                    pim = pimValue,
                    hidden = hidden,
                    readOnly = readOnly,
                    keyfileUris = keyfileUris,
                    protectionPassword = null,
                    protectionPim = 0
                )

                var openedAsHidden = false
                var rc = if (preferHidden) {
                    val hiddenRc = tryOpen(hidden = true)
                    if (hiddenRc == 0) {
                        openedAsHidden = true
                        0
                    } else if (hiddenRc == -1001) {
                        tryOpen(hidden = false)
                    } else {
                        hiddenRc
                    }
                } else {
                    val standardRc = tryOpen(hidden = false)
                    if (standardRc == 0) {
                        0
                    } else if (standardRc == -1001) {
                        val hiddenRc = tryOpen(hidden = true)
                        openedAsHidden = hiddenRc == 0
                        hiddenRc
                    } else {
                        standardRc
                    }
                }

                if (rc == 0) {
                    val openedInfo = repo.fsInfo.value
                    val safetyMessage = when (openedInfo?.mountWarning) {
                        VcMountWarning.NTFS_HIBERNATED_FALLBACK_READONLY ->
                            "NTFS safety fallback: opened read-only (hibernated/fast-startup state)"
                        VcMountWarning.NTFS_UNCLEAN_FALLBACK_READONLY ->
                            "NTFS safety fallback: opened read-only (unclean journal state)"
                        else -> null
                    }
                    manager.markMounted(
                        volumeUri = targetUri,
                        hidden = openedAsHidden,
                        readOnly = openedInfo?.readOnly ?: readOnly,
                        mode = MountMode.Full,
                        mountPoint = null,
                        message = safetyMessage ?: "Provider mode (cross-app via DocumentsProvider)"
                    )
                    opMessage.value = safetyMessage ?: "Opened"
                } else {
                    manager.unmount()
                    onStopService()
                    opMessage.value = when (rc) {
                        -1001 -> "Open failed: incorrect password/PIM (tried standard and hidden)"
                        VC_ERR_KEYFILE_IO -> "Open failed: could not read one or more keyfiles."
                        -1 -> "Open failed: volume decrypted but no supported filesystem was detected."
                        else -> "Open failed ($rc)"
                    }
                }
            } finally {
                openInProgress.value = false
            }
        }
    }

    @Composable
    fun ExplorerSection(state: dev.alsatianconsulting.cryptocontainer.manager.VolumeState, effectiveReadOnly: Boolean) {
        VcBrowser(
            modifier = modifier.fillMaxSize(),
            containerName = resolveContainerName(context, state.uri),
            volumeTypeLabel = if (state.hidden) "Hidden" else "Standard",
            repo = repo,
            readOnly = effectiveReadOnly,
            clipboardEntries = vcClipboardEntries.value,
            clipboardIsCut = vcClipboardIsCut.value,
            statusMessage = opMessage.value.takeIf { it.isNotBlank() },
            onBack = {
                showExplorer.value = false
                scope.launch { scrollState.animateScrollTo(0) }
            },
            onCopyToClipboard = { entries ->
                setClipboardEntries(entries, cut = false)
            },
            onCutToClipboard = { entries ->
                setClipboardEntries(entries, cut = true)
            },
            onClearClipboard = {
                vcClipboardEntries.value = emptyList()
                vcClipboardIsCut.value = false
                opMessage.value = "Clipboard cleared"
            },
            onPasteInto = { dir ->
                scope.launch {
                    MountController.onActivity()
                    val clips = pruneNestedEntries(vcClipboardEntries.value)
                    val isCut = vcClipboardIsCut.value
                    if (clips.isEmpty()) {
                        opMessage.value = "Paste failed: clipboard is empty"
                        return@launch
                    }
                    val existingTargets = repo.list(dir).map { it.path }.toMutableSet()
                    var pastedCount = 0
                    var failedCount = 0
                    var firstFailure: String? = null
                    var cleanupFailure: String? = null
                    val movedEntries = mutableListOf<VcEntry>()
                    val sourceParentsToRefresh = linkedSetOf<String>()

                    clips.forEach { clip ->
                        val name = sanitizeFileName(
                            clip.path.substringAfterLast('/').ifBlank { "pasted.bin" },
                            "pasted.bin"
                        )
                        val targetPath = if (dir.isBlank()) name else "$dir/$name"
                        when {
                            targetPath == clip.path -> {
                                failedCount += 1
                                if (firstFailure == null) {
                                    firstFailure = if (isCut) {
                                        "${clip.path}: item is already in this folder"
                                    } else {
                                        "${clip.path}: target is the same file"
                                    }
                                }
                            }
                            clip.isDir && (dir == clip.path || dir.startsWith("${clip.path}/")) -> {
                                failedCount += 1
                                if (firstFailure == null) {
                                    firstFailure = "${clip.path}: cannot paste a folder into itself"
                                }
                            }
                            targetPath in existingTargets -> {
                                failedCount += 1
                                if (firstFailure == null) {
                                    firstFailure = "${clip.path}: target already exists"
                                }
                            }
                            else -> {
                                val result = copyOrMoveEntryInsideContainer(
                                    context = context,
                                    repo = repo,
                                    entry = clip,
                                    targetPath = targetPath,
                                    move = isCut
                                )
                                if (result.rc != 0) {
                                    failedCount += 1
                                    if (firstFailure == null) {
                                        firstFailure = "${clip.path}: ${describeFsOpFailure("Paste", result.rc)}"
                                    }
                                } else {
                                    pastedCount += 1
                                    existingTargets += targetPath
                                    movedEntries += clip
                                    if (isCut) {
                                        sourceParentsToRefresh += clip.path.substringBeforeLast('/', "")
                                    }
                                    if (result.cleanupDeleteRc != null && result.cleanupDeleteRc != 0 && cleanupFailure == null) {
                                        cleanupFailure = describeFsOpFailure("Delete", result.cleanupDeleteRc)
                                    }
                                }
                            }
                        }
                    }

                    repo.refresh(dir)
                    sourceParentsToRefresh
                        .filter { it != dir }
                        .forEach { repo.refresh(it) }

                    if (isCut && movedEntries.isNotEmpty()) {
                        val remaining = vcClipboardEntries.value.filter { original ->
                            movedEntries.none { moved -> moved.path == original.path }
                        }
                        vcClipboardEntries.value = remaining
                        if (remaining.isEmpty()) {
                            vcClipboardIsCut.value = false
                        }
                    }

                    opMessage.value = when {
                        pastedCount > 0 && failedCount == 0 -> {
                            val base = if (pastedCount == 1) {
                                if (isCut) "Moved 1 item" else "Copied 1 item"
                            } else {
                                if (isCut) "Moved $pastedCount items" else "Copied $pastedCount items"
                            }
                            if (cleanupFailure != null) "$base, but cleanup failed: $cleanupFailure" else base
                        }
                        pastedCount > 0 -> {
                            "${if (isCut) "Moved" else "Copied"} $pastedCount items, $failedCount failed"
                        }
                        else -> {
                            "Paste failed: ${firstFailure ?: "no items could be pasted"}"
                        }
                    }
                }
            },
            onExtract = { entry ->
                pendingExtract.value = entry
                val defaultName = entry.path.substringAfterLast('/').ifBlank { "extracted.bin" }
                pickExtractDestination.launch(defaultName)
            },
            onExtractMany = { entries ->
                pendingExtractEntries.value = entries
                pickExtractFolder.launch(null)
            },
            onDelete = { entry ->
                scope.launch {
                    val rc = if (entry.isDir) {
                        deleteEntryRecursively(repo, entry)
                    } else {
                        repo.delete(entry.path)
                    }
                    val parent = entry.path.substringBeforeLast('/', "")
                    opMessage.value = if (rc == 0) {
                        clearClipboardEntriesCoveredBy(entry)
                        repo.refresh(parent)
                        "Deleted ${entry.path}"
                    } else {
                        describeFsOpFailure("Delete", rc)
                    }
                }
            },
            onDeleteMany = { selectedEntries ->
                scope.launch {
                    MountController.onActivity()
                    val topLevelEntries = pruneNestedEntries(selectedEntries)
                    var deletedCount = 0
                    var failedCount = 0
                    var firstFailure: String? = null
                    val parentsToRefresh = linkedSetOf<String>()
                    topLevelEntries.forEach { entry ->
                        val rc = if (entry.isDir) {
                            deleteEntryRecursively(repo, entry)
                        } else {
                            repo.delete(entry.path)
                        }
                        if (rc == 0) {
                            deletedCount += 1
                            parentsToRefresh += entry.path.substringBeforeLast('/', "")
                            clearClipboardEntriesCoveredBy(entry)
                        } else {
                            failedCount += 1
                            if (firstFailure == null) {
                                firstFailure = "${entry.path}: ${describeFsOpFailure("Delete", rc)}"
                            }
                        }
                    }
                    parentsToRefresh.forEach { repo.refresh(it) }
                    opMessage.value = when {
                        deletedCount > 0 && failedCount == 0 ->
                            if (deletedCount == 1) "Deleted 1 item" else "Deleted $deletedCount items"
                        deletedCount > 0 ->
                            "Deleted $deletedCount items, $failedCount failed"
                        else ->
                            firstFailure ?: "Delete failed"
                    }
                }
            },
            onRename = { entry, requestedName ->
                scope.launch {
                    MountController.onActivity()
                    val trimmed = requestedName.trim()
                    if (trimmed.isBlank()) {
                        opMessage.value = "Rename failed: name is empty"
                        return@launch
                    }
                    val parent = entry.path.substringBeforeLast('/', "")
                    val safeName = sanitizeFileName(trimmed, entry.path.substringAfterLast('/').ifBlank { "item" })
                    val targetPath = if (parent.isBlank()) safeName else "$parent/$safeName"
                    if (targetPath == entry.path) {
                        opMessage.value = "Rename skipped: same filename"
                        return@launch
                    }
                    if (repo.list(parent).any { it.path == targetPath }) {
                        opMessage.value = "Rename failed: target already exists"
                        return@launch
                    }
                    val result = copyOrMoveEntryInsideContainer(
                        context = context,
                        repo = repo,
                        entry = entry,
                        targetPath = targetPath,
                        move = true
                    )
                    if (result.rc != 0) {
                        opMessage.value = describeFsOpFailure("Rename", result.rc)
                        return@launch
                    }
                    repo.refresh(parent)
                    if (result.cleanupDeleteRc != null && result.cleanupDeleteRc != 0) {
                        opMessage.value = "Rename copied, but cleanup failed: " +
                            describeFsOpFailure("Delete", result.cleanupDeleteRc)
                    } else {
                        clearClipboardEntriesCoveredBy(entry)
                        opMessage.value = "Renamed to $safeName"
                    }
                }
            },
            onCreateFolder = { dir, requestedName ->
                scope.launch {
                    MountController.onActivity()
                    val trimmed = requestedName.trim()
                    if (trimmed.isBlank()) {
                        opMessage.value = "Create folder failed: folder name is empty"
                        return@launch
                    }
                    val safeName = sanitizeFileName(trimmed, "New Folder")
                    val targetPath = if (dir.isBlank()) safeName else "$dir/$safeName"
                    if (repo.list(dir).any { it.path == targetPath }) {
                        opMessage.value = "Create folder failed: target already exists"
                        return@launch
                    }
                    val rc = repo.mkdir(targetPath)
                    opMessage.value = if (rc == 0) {
                        repo.refresh(dir)
                        "Folder created: /$targetPath"
                    } else {
                        describeFsOpFailure("Create folder", rc)
                    }
                }
            },
            onAddHere = { dir ->
                pendingAddDir.value = dir
                pickAddSource.launch(arrayOf("*/*"))
            },
            onOpen = { entry ->
                scope.launch {
                    MountController.onActivity()
                    val name = sanitizeFileName(
                        entry.path.substringAfterLast('/').ifBlank { "open.bin" },
                        "open.bin"
                    )
                    val openDir = context.cacheDir.resolve("vc-open").apply { mkdirs() }
                    val temp = openDir.resolve("${System.currentTimeMillis()}-$name")
                    val rc = repo.extract(entry.path, temp.absolutePath)
                    if (rc != 0) {
                        opMessage.value = describeFsOpFailure("Open", rc)
                        temp.delete()
                        return@launch
                    }
                    val fileUri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        temp
                    )
                    val mimeType = guessMimeType(name)
                    val openIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(fileUri, mimeType)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        clipData = ClipData.newUri(context.contentResolver, "VeraCrypt", fileUri)
                    }
                    try {
                        context.startActivity(Intent.createChooser(openIntent, "Open VeraCrypt File"))
                    } catch (_: ActivityNotFoundException) {
                        opMessage.value = "Open failed: no app can open this file type"
                    }
                }
            },
            onShareEntries = { entries ->
                val fileEntries = entries
                    .filterNot { it.isDir }
                    .distinctBy { it.path }
                if (fileEntries.isEmpty()) {
                    opMessage.value = "Share failed: select one or more files"
                } else {
                    val uris = ArrayList(fileEntries.map { entry ->
                        DocumentsContract.buildDocumentUri(
                            "${context.packageName}.volumeprovider",
                            entry.path.trim('/').ifEmpty { "root" }
                        )
                    })
                    val clipData = ClipData.newUri(context.contentResolver, "VeraCrypt", uris.first())
                    uris.drop(1).forEach { clipData.addItem(ClipData.Item(it)) }
                    val shareIntent = if (uris.size == 1) {
                        Intent(Intent.ACTION_SEND).apply {
                            type = guessMimeType(fileEntries.first().path.substringAfterLast('/'))
                            putExtra(Intent.EXTRA_STREAM, uris.first())
                        }
                    } else {
                        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                            type = "*/*"
                            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                        }
                    }.apply {
                        this.clipData = clipData
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Share VeraCrypt File"))
                }
            }
        )
    }

    val currentState = volumeState
    if (showExplorer.value && currentState != null) {
        ExplorerSection(currentState, fsInfo?.readOnly ?: currentState.readOnly)
        CreateVolumeDialogs()
        return
    }

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("VeraCrypt", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        volumeState?.let { state ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Current Container", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Name: ${resolveContainerName(context, state.uri)}")
                    Text("Type: ${if (state.hidden) "Hidden" else "Standard"}")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { showExplorer.value = true }) { Text("Explore Container") }
                        Button(
                            onClick = {
                                MountController.onActivity()
                                scope.launch {
                                    repo.close()
                                    manager.unmount()
                                    opMessage.value = "Closed"
                                }
                                onStopService()
                            },
                            enabled = !openInProgress.value && !createInProgress.value
                        ) { Text("Close Container") }
                    }
                }
            }
        }
        OutlinedTextField(
            value = uri,
            onValueChange = setUri,
            label = { Text("Container File") },
            modifier = Modifier.fillMaxWidth()
        )
        SecureTextField(
            value = password,
            onValueChange = setPassword,
            label = "Password",
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = pim,
            onValueChange = { setPim(it.filter { ch -> ch.isDigit() }) },
            label = { Text("PIM (optional)") },
            modifier = Modifier.fillMaxWidth()
        )
        KeyfilePickerField(
            label = "Keyfiles (optional)",
            selectedUris = keyfileUris,
            onPick = { pickKeyfiles.launch(arrayOf("*/*")) },
            onClear = { setKeyfileUris(emptyList()) },
            helperText = "Selected keyfiles are applied to both standard and hidden open attempts."
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                if (!readOnly) {
                    showOuterWriteWarning.value = true
                } else {
                    openSelectedVolume()
                }
            }, enabled = !openInProgress.value && !createInProgress.value) { Text("Open") }
            Button(
                onClick = { pickContainer.launch(arrayOf("*/*")) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                enabled = !openInProgress.value && !createInProgress.value
            ) { Text("Pick Container") }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Switch(checked = readOnly, onCheckedChange = setReadOnly)
            Text("Read-only")
        }
        Text(
            "Open will try the entered Password/PIM as both standard and hidden volume credentials.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                MountController.onActivity()
                scope.launch {
                    repo.close()
                    manager.unmount()
                    opMessage.value = "Closed"
                }
                onStopService()
            }, enabled = volumeState != null && !openInProgress.value && !createInProgress.value) { Text("Close") }
            Button(
                onClick = { showCreate.value = true },
                enabled = !createInProgress.value && !openInProgress.value
            ) { Text("Create Volume") }
        }

        if (volumeState != null) {
            val state = volumeState!!
            val effectiveReadOnly = fsInfo?.readOnly ?: state.readOnly
            HorizontalDivider()
            Text("Status: ${state.message ?: "Mounted"}")
            Text("Mode: " + when (state.mode) {
                MountMode.Full -> "Full"
                MountMode.Limited -> "Limited"
            })
            Text("Read-only: $effectiveReadOnly")
            Text("Mount point: ${state.mountPoint ?: "N/A"}")
            fsInfo?.let {
                val label = when (it.type) {
                    dev.alsatianconsulting.cryptocontainer.model.VcFsType.EXFAT -> "exFAT"
                    dev.alsatianconsulting.cryptocontainer.model.VcFsType.NTFS -> "NTFS"
                    dev.alsatianconsulting.cryptocontainer.model.VcFsType.FAT -> "FAT"
                    else -> "Unknown"
                }
                val ro = if (it.readOnly) " (read-only)" else ""
                Text("Filesystem: $label$ro")
                when (it.mountWarning) {
                    VcMountWarning.NTFS_HIBERNATED_FALLBACK_READONLY ->
                        Text("Safety: read-only fallback due to NTFS hibernation/fast-startup state", color = MaterialTheme.colorScheme.secondary)
                    VcMountWarning.NTFS_UNCLEAN_FALLBACK_READONLY ->
                        Text("Safety: read-only fallback due to unclean NTFS journal state", color = MaterialTheme.colorScheme.secondary)
                    VcMountWarning.NONE -> {}
                }
            }
        }

        if (opMessage.value.isNotBlank()) {
            Text(opMessage.value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        }
    }

    CreateVolumeDialogs()
    OpenVolumeProgressDialog()

    if (showOuterWriteWarning.value) {
        AlertDialog(
            onDismissRequest = { showOuterWriteWarning.value = false },
            confirmButton = {
                Button(onClick = {
                    showOuterWriteWarning.value = false
                    openSelectedVolume()
                }) { Text("Open Writable") }
            },
            dismissButton = {
                Button(onClick = { showOuterWriteWarning.value = false }) { Text("Cancel") }
            },
            text = {
                Text(
                    "Opening an outer volume in writable mode without hidden protection can " +
                        "overwrite hidden-volume data. Use read-only if you are not sure this is not an outer volume."
                )
            }
        )
    }
}
