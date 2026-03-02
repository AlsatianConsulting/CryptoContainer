package dev.alsatianconsulting.cryptocontainer.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import dev.alsatianconsulting.cryptocontainer.MountController
import dev.alsatianconsulting.cryptocontainer.model.VcEntry
import dev.alsatianconsulting.cryptocontainer.util.sanitizeFileName
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException

class VolumeProvider : DocumentsProvider() {
    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(resolveRootProjection(projection))
        if (MountController.veraRepo.isOpen()) {
            val row = result.newRow()
            var flags = Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_RECENTS
            if (!isReadOnly()) {
                flags = flags or Root.FLAG_SUPPORTS_CREATE
            }
            row.add(Root.COLUMN_ROOT_ID, ROOT_ID)
            row.add(Root.COLUMN_DOCUMENT_ID, DOC_ROOT)
            row.add(Root.COLUMN_TITLE, "VeraCrypt")
            row.add(Root.COLUMN_FLAGS, flags)
            row.add(Root.COLUMN_AVAILABLE_BYTES, null)
        }
        return result
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        ensureOpen()
        val result = MatrixCursor(resolveDocumentProjection(projection))
        if (documentId == DOC_ROOT) {
            val row = result.newRow()
            row.add(Document.COLUMN_DOCUMENT_ID, DOC_ROOT)
            row.add(Document.COLUMN_DISPLAY_NAME, "VeraCrypt")
            row.add(Document.COLUMN_SIZE, 0L)
            row.add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
            row.add(Document.COLUMN_FLAGS, dirFlags())
            return result
        }

        val path = docIdToPath(documentId)
        val entry = statEntry(path) ?: throw FileNotFoundException("Missing: $path")
        appendEntryRow(result, entry)
        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        ensureOpen()
        val cursor = MatrixCursor(resolveDocumentProjection(projection))
        val parentPath = docIdToPath(parentDocumentId)
        val children = runBlocking { MountController.veraRepo.list(parentPath) }
        children.forEach { appendEntryRow(cursor, it) }
        return cursor
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        ensureOpen()
        if (documentId == DOC_ROOT) throw FileNotFoundException("Cannot open root")
        val path = docIdToPath(documentId)
        val writeMode = mode.contains("w")
        if (writeMode && isReadOnly()) throw FileNotFoundException("Read-only")

        val cacheDir = providerContext().cacheDir.resolve("provider").apply { mkdirs() }
        val safe = sanitizeFileName(path.replace('/', '_'), "doc.bin")
        val temp = cacheDir.resolve("${System.currentTimeMillis()}-$safe")
        val handler = Handler(Looper.getMainLooper())

        return if (writeMode) {
            runBlocking {
                // Best-effort prefill when editing an existing file.
                MountController.veraRepo.extract(path, temp.absolutePath)
            }
            val access = ParcelFileDescriptor.parseMode(mode)
            ParcelFileDescriptor.open(temp, access, handler) { e ->
                try {
                    if (e == null) {
                        runBlocking {
                            MountController.veraRepo.add(path, temp.absolutePath)
                            MountController.veraRepo.refresh(parentPath(path))
                        }
                    }
                } finally {
                    temp.delete()
                }
            }
        } else {
            val rc = runBlocking { MountController.veraRepo.extract(path, temp.absolutePath) }
            if (rc != 0) throw FileNotFoundException("Read failed: $rc")
            ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY, handler) {
                temp.delete()
            }
        }
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        ensureOpen()
        if (isReadOnly()) throw FileNotFoundException("Read-only")
        val parent = docIdToPath(parentDocumentId)
        val safeName = sanitizeFileName(displayName, "new.bin")
        val path = if (parent.isEmpty()) safeName else "$parent/$safeName"
        if (mimeType == Document.MIME_TYPE_DIR) {
            val rc = runBlocking {
                val mkdirRc = MountController.veraRepo.mkdir(path)
                if (mkdirRc == 0) {
                    MountController.veraRepo.refresh(parent)
                }
                mkdirRc
            }
            if (rc != 0) throw FileNotFoundException("Create dir failed: $rc")
            return pathToDocId(path)
        }

        val temp = File.createTempFile("vc-create-", ".tmp", providerContext().cacheDir)
        try {
            val rc = runBlocking {
                val createRc = MountController.veraRepo.add(path, temp.absolutePath)
                if (createRc == 0) {
                    MountController.veraRepo.refresh(parent)
                }
                createRc
            }
            if (rc != 0) throw FileNotFoundException("Create failed: $rc")
            return pathToDocId(path)
        } finally {
            temp.delete()
        }
    }

    override fun deleteDocument(documentId: String) {
        ensureOpen()
        if (documentId == DOC_ROOT) throw FileNotFoundException("Cannot delete root")
        if (isReadOnly()) throw FileNotFoundException("Read-only")
        val path = docIdToPath(documentId)
        val rc = runBlocking {
            val deleteRc = MountController.veraRepo.delete(path)
            if (deleteRc == 0) {
                MountController.veraRepo.refresh(parentPath(path))
            }
            deleteRc
        }
        if (rc != 0) throw FileNotFoundException("Delete failed: $rc")
    }

    override fun getDocumentType(documentId: String): String {
        return if (documentId == DOC_ROOT || documentId.endsWith("/")) {
            Document.MIME_TYPE_DIR
        } else {
            val path = docIdToPath(documentId)
            val entry = statEntry(path)
            if (entry?.isDir == true) Document.MIME_TYPE_DIR else "application/octet-stream"
        }
    }

    override fun isChildDocument(parentDocumentId: String?, documentId: String?): Boolean {
        if (parentDocumentId == null || documentId == null) return false
        if (parentDocumentId == DOC_ROOT) return documentId != DOC_ROOT
        val parent = docIdToPath(parentDocumentId)
        val child = docIdToPath(documentId)
        return child == parent || child.startsWith("$parent/")
    }

    private fun resolveRootProjection(projection: Array<out String>?): Array<String> = projection?.let {
        Array(it.size) { idx -> it[idx] }
    } ?: arrayOf(
        Root.COLUMN_ROOT_ID,
        Root.COLUMN_DOCUMENT_ID,
        Root.COLUMN_TITLE,
        Root.COLUMN_FLAGS,
        Root.COLUMN_AVAILABLE_BYTES
    )

    private fun resolveDocumentProjection(projection: Array<out String>?): Array<String> = projection?.let {
        Array(it.size) { idx -> it[idx] }
    } ?: arrayOf(
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_SIZE,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_FLAGS
    )

    private fun appendEntryRow(cursor: MatrixCursor, entry: VcEntry) {
        val row = cursor.newRow()
        val docId = pathToDocId(entry.path)
        row.add(Document.COLUMN_DOCUMENT_ID, docId)
        row.add(Document.COLUMN_DISPLAY_NAME, displayName(entry.path))
        row.add(Document.COLUMN_SIZE, entry.size)
        row.add(Document.COLUMN_MIME_TYPE, if (entry.isDir) Document.MIME_TYPE_DIR else "application/octet-stream")
        row.add(Document.COLUMN_FLAGS, if (entry.isDir) dirFlags() else fileFlags())
    }

    private fun dirFlags(): Int {
        var flags = Document.FLAG_DIR_PREFERS_LAST_MODIFIED
        if (!isReadOnly()) {
            flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE or Document.FLAG_SUPPORTS_DELETE
        }
        return flags
    }

    private fun fileFlags(): Int {
        var flags = 0
        if (!isReadOnly()) {
            flags = flags or Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE
        }
        return flags
    }

    private fun statEntry(path: String): VcEntry? {
        val parent = parentPath(path)
        val name = displayName(path)
        val list = runBlocking { MountController.veraRepo.list(parent) }
        return list.firstOrNull { displayName(it.path) == name }
    }

    private fun ensureOpen() {
        if (!MountController.veraRepo.isOpen()) throw FileNotFoundException("No open container")
    }

    private fun providerContext() = context ?: throw IllegalStateException("Provider context unavailable")

    private fun isReadOnly(): Boolean = MountController.vera.volumeState.value?.readOnly ?: true

    private fun displayName(path: String): String = path.substringAfterLast('/').ifBlank { path }

    private fun pathToDocId(path: String): String {
        val normalized = path.trim('/').trim()
        return if (normalized.isEmpty()) DOC_ROOT else normalized
    }

    private fun docIdToPath(documentId: String): String {
        if (documentId == DOC_ROOT) return ""
        return documentId.trim('/').trim()
    }

    private fun parentPath(path: String): String = path.substringBeforeLast('/', "")

    companion object {
        private const val ROOT_ID = "vc-root"
        private const val DOC_ROOT = "root"
    }
}
