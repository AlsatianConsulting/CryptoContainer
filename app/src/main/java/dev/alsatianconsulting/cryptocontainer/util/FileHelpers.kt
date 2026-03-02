package dev.alsatianconsulting.cryptocontainer.util

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import java.io.File

fun copyUriToFile(context: Context, uri: Uri, dest: File): Boolean {
    dest.parentFile?.mkdirs()
    val input = context.contentResolver.openInputStream(uri) ?: return false
    input.use {
        dest.outputStream().use { output ->
            it.copyTo(output)
        }
    }
    return true
}

fun copyFileToUri(context: Context, source: File, uri: Uri): Boolean {
    if (!source.exists() || !source.isFile) return false
    context.contentResolver.openOutputStream(uri, "w")?.use { output ->
        source.inputStream().use { input ->
            input.copyTo(output)
        }
    } ?: return false
    return true
}

fun stageUrisToCacheFiles(
    context: Context,
    uriStrings: List<String>,
    cacheSubdir: String,
    fallbackPrefix: String
): List<File>? {
    if (uriStrings.isEmpty()) return emptyList()

    val stagedFiles = mutableListOf<File>()
    val targetDir = context.cacheDir.resolve(cacheSubdir).apply { mkdirs() }

    try {
        uriStrings.distinct().forEachIndexed { index, rawUri ->
            val uri = Uri.parse(rawUri)
            val fallbackName = "$fallbackPrefix-${index + 1}.bin"
            val safeName = sanitizeFileName(contentDisplayName(context, uri, fallbackName), fallbackName)
            val target = targetDir.resolve("${System.currentTimeMillis()}-$index-$safeName")
            if (!copyUriToFile(context, uri, target)) {
                deleteTempFiles(stagedFiles)
                return null
            }
            stagedFiles += target
        }
    } catch (_: Throwable) {
        deleteTempFiles(stagedFiles)
        return null
    }

    return stagedFiles
}

fun deleteTempFiles(files: Iterable<File>) {
    files.forEach { file ->
        try {
            file.delete()
        } catch (_: Throwable) {
        }
    }
}

fun copyFileToTree(
    context: Context,
    source: File,
    treeUri: Uri,
    displayName: String,
    mimeType: String = "application/octet-stream"
): Uri? {
    val parent = DocumentFile.fromTreeUri(context, treeUri) ?: return null
    val existing = parent.findFile(displayName)
    val target = existing ?: parent.createFile(mimeType, displayName) ?: return null
    return if (copyFileToUri(context, source, target.uri)) target.uri else null
}

fun contentDisplayName(context: Context, uri: Uri, fallback: String): String {
    if (uri.scheme == "file") {
        return uri.lastPathSegment ?: fallback
    }
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) {
                val value = cursor.getString(index)
                if (!value.isNullOrBlank()) return value
            }
        }
    }
    return uri.lastPathSegment ?: fallback
}

fun stripTrailingAesExtension(fileName: String): String {
    return if (fileName.endsWith(".aes", ignoreCase = true)) {
        fileName.dropLast(4)
    } else {
        fileName
    }
}

fun describeUriLocation(uri: Uri): String {
    if (uri.scheme == "file") {
        return uri.path ?: uri.toString()
    }

    val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
    if (!documentId.isNullOrBlank()) {
        return documentId
    }

    val treeId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
    if (!treeId.isNullOrBlank()) {
        return treeId
    }

    return uri.toString()
}
