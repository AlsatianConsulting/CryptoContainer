package dev.alsatianconsulting.cryptocontainer.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.alsatianconsulting.cryptocontainer.model.VcEntry
import dev.alsatianconsulting.cryptocontainer.repo.VeraCryptRepository
import kotlinx.coroutines.launch

private enum class ExplorerViewMode { List, Grid }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VcBrowser(
    modifier: Modifier = Modifier,
    containerName: String,
    volumeTypeLabel: String,
    repo: VeraCryptRepository,
    readOnly: Boolean,
    clipboardEntries: List<VcEntry>,
    clipboardIsCut: Boolean,
    pendingSharedImportCount: Int,
    statusMessage: String?,
    onBack: () -> Unit,
    onCopyToClipboard: (List<VcEntry>) -> Unit,
    onCutToClipboard: (List<VcEntry>) -> Unit,
    onClearClipboard: () -> Unit,
    onImportSharedHere: (String) -> Unit,
    onClearPendingSharedImport: () -> Unit,
    onPasteInto: (String) -> Unit,
    onExtract: (VcEntry) -> Unit,
    onExtractMany: (List<VcEntry>) -> Unit,
    onDelete: (VcEntry) -> Unit,
    onDeleteMany: (List<VcEntry>) -> Unit,
    onRename: (VcEntry, String) -> Unit,
    onCreateFolder: (String, String) -> Unit,
    onAddHere: (String) -> Unit,
    onAddFolderHere: (String) -> Unit,
    onOpen: (VcEntry) -> Unit,
    onEditInPlace: (VcEntry) -> Unit,
    onShareEntries: (List<VcEntry>) -> Unit
) {
    val entries by repo.entries.collectAsState()
    val scope = rememberCoroutineScope()
    val currentPath = remember { mutableStateOf("") }
    val renameTarget = remember { mutableStateOf<VcEntry?>(null) }
    val renameText = remember { mutableStateOf("") }
    val newFolderParentPath = remember { mutableStateOf<String?>(null) }
    val newFolderName = remember { mutableStateOf("") }
    val openMenuForPath = remember { mutableStateOf<String?>(null) }
    val locationMenuExpanded = remember { mutableStateOf(false) }
    val selectionMenuExpanded = remember { mutableStateOf(false) }
    val viewMode = remember { mutableStateOf(ExplorerViewMode.List) }
    val selectedPaths = remember { mutableStateOf(setOf<String>()) }

    fun displayName(path: String): String = path.substringAfterLast('/').ifBlank { path }

    fun clearSelection() {
        selectedPaths.value = emptySet()
        openMenuForPath.value = null
    }

    fun refresh(path: String = currentPath.value) {
        scope.launch { repo.refresh(path) }
    }

    fun navigateTo(path: String) {
        currentPath.value = path
        clearSelection()
        refresh(path)
    }

    fun openEntry(entry: VcEntry) {
        if (entry.isDir) {
            navigateTo(entry.path)
        } else {
            onOpen(entry)
        }
    }

    fun toggleSelection(entry: VcEntry) {
        val updated = selectedPaths.value.toMutableSet()
        if (!updated.add(entry.path)) {
            updated.remove(entry.path)
        }
        selectedPaths.value = updated.toSet()
        if (openMenuForPath.value == entry.path) {
            openMenuForPath.value = null
        }
    }

    fun handleEntryTap(entry: VcEntry) {
        if (selectedPaths.value.isNotEmpty()) {
            toggleSelection(entry)
        } else {
            openEntry(entry)
        }
    }

    LaunchedEffect(Unit) {
        refresh("")
    }

    LaunchedEffect(entries) {
        val visiblePaths = entries.map { it.path }.toSet()
        val remaining = selectedPaths.value.filter { it in visiblePaths }.toSet()
        if (remaining != selectedPaths.value) {
            selectedPaths.value = remaining
        }
    }

    val visibleEntries = entries.sortedWith(
        compareBy<VcEntry> { !it.isDir }.thenBy { displayName(it.path).lowercase() }
    )
    val selectionMode = selectedPaths.value.isNotEmpty()
    val selectedEntries = visibleEntries.filter { it.path in selectedPaths.value }
    val selectedEntry = selectedEntries.singleOrNull()
    val selectedFileEntry = selectedEntry?.takeIf { !it.isDir }
    val selectedFilesOnly = selectedEntries.isNotEmpty() && selectedEntries.all { !it.isDir }
    val hasClipboardItems = clipboardEntries.isNotEmpty()
    val hasPendingSharedImport = pendingSharedImportCount > 0
    val currentLocation = if (currentPath.value.isBlank()) "/" else "/${currentPath.value}"

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Column {
                    Text(containerName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "$volumeTypeLabel container" + if (readOnly) " • read-only" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
            Row {
                IconButton(onClick = { viewMode.value = ExplorerViewMode.List }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ViewList,
                        contentDescription = "List view",
                        tint = if (viewMode.value == ExplorerViewMode.List) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.secondary
                        }
                    )
                }
                IconButton(onClick = { viewMode.value = ExplorerViewMode.Grid }) {
                    Icon(
                        Icons.Filled.GridView,
                        contentDescription = "Grid view",
                        tint = if (viewMode.value == ExplorerViewMode.Grid) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.secondary
                        }
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Location",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Box {
                        IconButton(onClick = { locationMenuExpanded.value = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Location menu")
                        }
                        DropdownMenu(
                            expanded = locationMenuExpanded.value,
                            onDismissRequest = { locationMenuExpanded.value = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Up") },
                                enabled = currentPath.value.isNotEmpty(),
                                onClick = {
                                    locationMenuExpanded.value = false
                                    val parent = currentPath.value.substringBeforeLast('/', "")
                                    navigateTo(parent)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Refresh") },
                                onClick = {
                                    locationMenuExpanded.value = false
                                    refresh()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Add Files") },
                                enabled = !readOnly,
                                onClick = {
                                    locationMenuExpanded.value = false
                                    onAddHere(currentPath.value)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Add Folder") },
                                enabled = !readOnly,
                                onClick = {
                                    locationMenuExpanded.value = false
                                    onAddFolderHere(currentPath.value)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("New Folder") },
                                enabled = !readOnly,
                                onClick = {
                                    locationMenuExpanded.value = false
                                    newFolderParentPath.value = currentPath.value
                                    newFolderName.value = ""
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Paste Here") },
                                enabled = !readOnly && hasClipboardItems,
                                onClick = {
                                    locationMenuExpanded.value = false
                                    onPasteInto(currentPath.value)
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (pendingSharedImportCount == 1) {
                                            "Import 1 Shared Item Here"
                                        } else {
                                            "Import $pendingSharedImportCount Shared Items Here"
                                        }
                                    )
                                },
                                enabled = !readOnly && hasPendingSharedImport,
                                onClick = {
                                    locationMenuExpanded.value = false
                                    onImportSharedHere(currentPath.value)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Cancel Shared Import") },
                                enabled = hasPendingSharedImport,
                                onClick = {
                                    locationMenuExpanded.value = false
                                    onClearPendingSharedImport()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Clear Clipboard") },
                                enabled = hasClipboardItems,
                                onClick = {
                                    locationMenuExpanded.value = false
                                    onClearClipboard()
                                }
                            )
                        }
                    }
                }
                Text(currentLocation, style = MaterialTheme.typography.bodyLarge)
            }
        }

        if (selectionMode) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${selectedEntries.size} selected",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Box {
                            IconButton(onClick = { selectionMenuExpanded.value = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "Selection menu")
                            }
                            DropdownMenu(
                                expanded = selectionMenuExpanded.value,
                                onDismissRequest = { selectionMenuExpanded.value = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Open") },
                                    enabled = selectedEntry != null,
                                    onClick = {
                                        selectionMenuExpanded.value = false
                                        selectedEntry?.let { openEntry(it) }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Edit In Place") },
                                    enabled = selectedFileEntry != null && !readOnly,
                                    onClick = {
                                        selectionMenuExpanded.value = false
                                        selectedFileEntry?.let(onEditInPlace)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Rename") },
                                    enabled = selectedEntry != null && !readOnly,
                                    onClick = {
                                        selectionMenuExpanded.value = false
                                        selectedEntry?.let { entry ->
                                            renameTarget.value = entry
                                            renameText.value = displayName(entry.path)
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Copy") },
                                    enabled = selectedEntries.isNotEmpty(),
                                    onClick = {
                                        selectionMenuExpanded.value = false
                                        onCopyToClipboard(selectedEntries)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Cut") },
                                    enabled = !readOnly && selectedEntries.isNotEmpty(),
                                    onClick = {
                                        selectionMenuExpanded.value = false
                                        onCutToClipboard(selectedEntries)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Extract") },
                                    enabled = selectedEntries.isNotEmpty(),
                                    onClick = {
                                        selectionMenuExpanded.value = false
                                        onExtractMany(selectedEntries)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Share") },
                                    enabled = selectedFilesOnly,
                                    onClick = {
                                        selectionMenuExpanded.value = false
                                        onShareEntries(selectedEntries)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete") },
                                    enabled = !readOnly && selectedEntries.isNotEmpty(),
                                    onClick = {
                                        selectionMenuExpanded.value = false
                                        onDeleteMany(selectedEntries)
                                        clearSelection()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Select All") },
                                    enabled = visibleEntries.isNotEmpty(),
                                    onClick = {
                                        selectionMenuExpanded.value = false
                                        selectedPaths.value = visibleEntries.map { it.path }.toSet()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Clear Selection") },
                                    onClick = {
                                        selectionMenuExpanded.value = false
                                        clearSelection()
                                    }
                                )
                            }
                        }
                    }
                    Text(
                        "Tap more items or long-press to adjust the selection.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }

        if (hasClipboardItems) {
            Text(
                if (clipboardEntries.size == 1) {
                    "${if (clipboardIsCut) "Cut" else "Copy"}: ${displayName(clipboardEntries.first().path)}"
                } else {
                    "${if (clipboardIsCut) "Cut" else "Copy"}: ${clipboardEntries.size} items"
                },
                color = MaterialTheme.colorScheme.secondary
            )
        }

        if (hasPendingSharedImport) {
            Text(
                if (pendingSharedImportCount == 1) {
                    "1 shared item is pending import. Navigate to a folder and choose Import Shared Here."
                } else {
                    "$pendingSharedImportCount shared items are pending import. Navigate to a folder and choose Import Shared Here."
                },
                color = MaterialTheme.colorScheme.secondary
            )
        }

        if (statusMessage != null && statusMessage.isNotBlank()) {
            Text(statusMessage, color = MaterialTheme.colorScheme.secondary)
        }

        if (readOnly) {
            Text(
                "This container is read-only. File modifications are disabled.",
                color = MaterialTheme.colorScheme.secondary
            )
        }

        HorizontalDivider()

        if (viewMode.value == ExplorerViewMode.List) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (visibleEntries.isEmpty()) {
                    item {
                        Text(
                            "This folder is empty.",
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                } else {
                    items(visibleEntries, key = { it.path }) { entry ->
                        val isMenuOpen = openMenuForPath.value == entry.path
                        val isSelected = entry.path in selectedPaths.value
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isSelected) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                    } else {
                                        MaterialTheme.colorScheme.background
                                    }
                                )
                                .combinedClickable(
                                    onClick = { handleEntryTap(entry) },
                                    onLongClick = { toggleSelection(entry) }
                                )
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (selectionMode) {
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = { toggleSelection(entry) }
                                    )
                                }
                                Icon(
                                    if (entry.isDir) Icons.Filled.Folder else Icons.Filled.Description,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Column {
                                    Text(
                                        displayName(entry.path),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        if (entry.isDir) "Folder" else "File",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                            }
                            Box {
                                IconButton(onClick = { openMenuForPath.value = entry.path }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                                }
                                EntryMenu(
                                    expanded = isMenuOpen,
                                    readOnly = readOnly,
                                    hasClipboardItems = hasClipboardItems,
                                    entry = entry,
                                    isSelected = isSelected,
                                    onDismiss = { openMenuForPath.value = null },
                                    onOpen = { openEntry(entry) },
                                    onToggleSelection = { toggleSelection(entry) },
                                    onRename = {
                                        renameTarget.value = entry
                                        renameText.value = displayName(entry.path)
                                    },
                                    onCopy = { onCopyToClipboard(listOf(entry)) },
                                    onCut = { onCutToClipboard(listOf(entry)) },
                                    onEditInPlace = { onEditInPlace(entry) },
                                    onExtract = { onExtract(entry) },
                                    onShare = { onShareEntries(listOf(entry)) },
                                    onDelete = { onDelete(entry) },
                                    onNewFolderHere = {
                                        newFolderParentPath.value = entry.path
                                        newFolderName.value = ""
                                    },
                                    onPasteHere = { onPasteInto(entry.path) },
                                    onImportSharedHere = { onImportSharedHere(entry.path) },
                                    hasPendingSharedImport = hasPendingSharedImport
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 148.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (visibleEntries.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            "This folder is empty.",
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                } else {
                    gridItems(visibleEntries, key = { it.path }) { entry ->
                        val isMenuOpen = openMenuForPath.value == entry.path
                        val isSelected = entry.path in selectedPaths.value
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { handleEntryTap(entry) },
                                    onLongClick = { toggleSelection(entry) }
                                ),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                }
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        if (selectionMode) {
                                            Checkbox(
                                                checked = isSelected,
                                                onCheckedChange = { toggleSelection(entry) }
                                            )
                                        }
                                        Icon(
                                            if (entry.isDir) Icons.Filled.Folder else Icons.Filled.Description,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Box {
                                        IconButton(onClick = { openMenuForPath.value = entry.path }) {
                                            Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                                        }
                                        EntryMenu(
                                            expanded = isMenuOpen,
                                            readOnly = readOnly,
                                            hasClipboardItems = hasClipboardItems,
                                            entry = entry,
                                            isSelected = isSelected,
                                            onDismiss = { openMenuForPath.value = null },
                                            onOpen = { openEntry(entry) },
                                            onToggleSelection = { toggleSelection(entry) },
                                            onRename = {
                                                renameTarget.value = entry
                                                renameText.value = displayName(entry.path)
                                            },
                                            onCopy = { onCopyToClipboard(listOf(entry)) },
                                            onCut = { onCutToClipboard(listOf(entry)) },
                                            onEditInPlace = { onEditInPlace(entry) },
                                            onExtract = { onExtract(entry) },
                                            onShare = { onShareEntries(listOf(entry)) },
                                            onDelete = { onDelete(entry) },
                                            onNewFolderHere = {
                                                newFolderParentPath.value = entry.path
                                                newFolderName.value = ""
                                            },
                                            onPasteHere = { onPasteInto(entry.path) },
                                            onImportSharedHere = { onImportSharedHere(entry.path) },
                                            hasPendingSharedImport = hasPendingSharedImport
                                        )
                                    }
                                }
                                Text(
                                    displayName(entry.path),
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    if (entry.isDir) "Folder" else "File",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (renameTarget.value != null) {
        val target = renameTarget.value!!
        AlertDialog(
            onDismissRequest = { renameTarget.value = null },
            confirmButton = {
                Button(onClick = {
                    onRename(target, renameText.value)
                    renameTarget.value = null
                }) { Text("Rename") }
            },
            dismissButton = {
                Button(onClick = { renameTarget.value = null }) { Text("Cancel") }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Rename")
                    OutlinedTextField(
                        value = renameText.value,
                        onValueChange = { renameText.value = it },
                        label = { Text("New name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        )
    }

    if (newFolderParentPath.value != null) {
        val parent = newFolderParentPath.value ?: ""
        AlertDialog(
            onDismissRequest = { newFolderParentPath.value = null },
            confirmButton = {
                Button(onClick = {
                    onCreateFolder(parent, newFolderName.value)
                    newFolderParentPath.value = null
                }) { Text("Create") }
            },
            dismissButton = {
                Button(onClick = { newFolderParentPath.value = null }) { Text("Cancel") }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Create New Folder")
                    OutlinedTextField(
                        value = newFolderName.value,
                        onValueChange = { newFolderName.value = it },
                        label = { Text("Folder name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Location: /$parent",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        )
    }
}

@Composable
private fun EntryMenu(
    expanded: Boolean,
    readOnly: Boolean,
    hasClipboardItems: Boolean,
    entry: VcEntry,
    isSelected: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onToggleSelection: () -> Unit,
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onEditInPlace: () -> Unit,
    onExtract: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onNewFolderHere: () -> Unit,
    onPasteHere: () -> Unit,
    onImportSharedHere: () -> Unit,
    hasPendingSharedImport: Boolean
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Open") },
            onClick = {
                onDismiss()
                onOpen()
            }
        )
        DropdownMenuItem(
            text = { Text(if (isSelected) "Deselect" else "Select") },
            onClick = {
                onDismiss()
                onToggleSelection()
            }
        )
        DropdownMenuItem(
            text = { Text("Rename") },
            enabled = !readOnly,
            onClick = {
                onDismiss()
                onRename()
            }
        )
        DropdownMenuItem(
            text = { Text("Copy") },
            onClick = {
                onDismiss()
                onCopy()
            }
        )
        DropdownMenuItem(
            text = { Text("Cut") },
            enabled = !readOnly,
            onClick = {
                onDismiss()
                onCut()
            }
        )
        if (entry.isDir) {
            DropdownMenuItem(
                text = { Text("New Folder Here") },
                enabled = !readOnly,
                onClick = {
                    onDismiss()
                    onNewFolderHere()
                }
            )
            DropdownMenuItem(
                text = { Text("Paste Here") },
                enabled = !readOnly && hasClipboardItems,
                onClick = {
                    onDismiss()
                    onPasteHere()
                }
            )
            DropdownMenuItem(
                text = { Text("Extract") },
                onClick = {
                    onDismiss()
                    onExtract()
                }
            )
            DropdownMenuItem(
                text = { Text("Import Shared Here") },
                enabled = !readOnly && hasPendingSharedImport,
                onClick = {
                    onDismiss()
                    onImportSharedHere()
                }
            )
        } else {
            DropdownMenuItem(
                text = { Text("Edit In Place") },
                enabled = !readOnly,
                onClick = {
                    onDismiss()
                    onEditInPlace()
                }
            )
            DropdownMenuItem(
                text = { Text("Extract") },
                onClick = {
                    onDismiss()
                    onExtract()
                }
            )
            DropdownMenuItem(
                text = { Text("Share") },
                onClick = {
                    onDismiss()
                    onShare()
                }
            )
        }
        DropdownMenuItem(
            text = { Text("Delete") },
            enabled = !readOnly,
            onClick = {
                onDismiss()
                onDelete()
            }
        )
    }
}
