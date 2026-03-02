package dev.alsatianconsulting.cryptocontainer.manager

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import dev.alsatianconsulting.cryptocontainer.crypto.AESCrypt
import dev.alsatianconsulting.cryptocontainer.util.contentDisplayName
import dev.alsatianconsulting.cryptocontainer.util.describeUriLocation
import dev.alsatianconsulting.cryptocontainer.util.guessMimeTypeFromName
import dev.alsatianconsulting.cryptocontainer.util.sanitizeFileName
import dev.alsatianconsulting.cryptocontainer.util.stripTrailingAesExtension
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class AESCryptOperationResult(
    val success: Boolean,
    val message: String,
    val outputUri: String? = null,
    val outputName: String? = null,
    val outputLocation: String? = null,
    val outputDirectoryUri: String? = null
)

class AESCryptManager {
    private val _status = MutableSharedFlow<String>()
    val status = _status.asSharedFlow()
    private val cancelRequested = AtomicBoolean(false)

    fun requestCancel() {
        cancelRequested.set(true)
    }

    fun clearCancel() {
        cancelRequested.set(false)
    }

    private fun ensureNotCanceled() {
        if (cancelRequested.get()) {
            throw CancellationException("Operation canceled")
        }
    }

    suspend fun decrypt(
        context: Context,
        inputUri: Uri,
        outputDirUri: Uri,
        password: CharArray
    ): AESCryptOperationResult = withContext(Dispatchers.IO) {
        _status.emit("Decrypting...")
        val tempIn = context.cacheDir.resolve("aes-in-${System.currentTimeMillis()}.aes")
        val tempOutDir = context.cacheDir.resolve("aes-out-${System.currentTimeMillis()}").apply { mkdirs() }
        val result = try {
            ensureNotCanceled()
            copyUriToFileCancellable(context, inputUri, tempIn, "Cannot read encrypted input")
            val fallbackName = sanitizeFileName(
                stripTrailingAesExtension(contentDisplayName(context, inputUri, "decrypted.bin")),
                "decrypted.bin"
            )
            val candidate = AESCrypt.decryptFile(
                inputPath = tempIn.absolutePath,
                outputDir = tempOutDir.absolutePath,
                password = password,
                fallbackOriginalFileName = fallbackName,
                checkCanceled = ::ensureNotCanceled
            )
            ensureNotCanceled()
            val copiedUri = copyFileToTreeCancellable(
                context = context,
                source = candidate,
                treeUri = outputDirUri,
                displayName = candidate.name
            )
            if (copiedUri != null) {
                AESCryptOperationResult(
                    success = true,
                    message = "Decrypted successfully",
                    outputUri = copiedUri.toString(),
                    outputName = candidate.name,
                    outputLocation = describeUriLocation(copiedUri),
                    outputDirectoryUri = outputDirUri.toString()
                )
            } else {
                AESCryptOperationResult(
                    success = false,
                    message = "Decrypt failed: unable to save output"
                )
            }
        } catch (_: CancellationException) {
            AESCryptOperationResult(
                success = false,
                message = "Decrypt canceled"
            )
        } catch (t: Throwable) {
            Log.e("AESCryptManager", "decrypt failed", t)
            AESCryptOperationResult(
                success = false,
                message = "Decrypt failed: ${t.message ?: "unknown error"}"
            )
        } finally {
            clearCancel()
            password.fill('\u0000')
            tempIn.delete()
            tempOutDir.deleteRecursively()
        }
        _status.emit(result.message)
        result
    }

    suspend fun encrypt(
        context: Context,
        inputUris: List<Uri>,
        outputDirUri: Uri,
        outputName: String,
        password: CharArray
    ): AESCryptOperationResult = withContext(Dispatchers.IO) {
        _status.emit("Encrypting...")
        val distinctInputs = inputUris.distinct()
        val input = distinctInputs.firstOrNull()
        if (input == null) {
            val message = "No input selected"
            _status.emit(message)
            password.fill('\u0000')
            return@withContext AESCryptOperationResult(success = false, message = message)
        }
        val result = try {
            ensureNotCanceled()
            if (distinctInputs.size > 1) {
                encryptBundledZip(
                    context = context,
                    inputUris = distinctInputs,
                    outputDirUri = outputDirUri,
                    outputName = outputName,
                    password = password
                )
            } else {
                val sourceUri = distinctInputs.first()
                val inputName = sanitizeFileName(
                    contentDisplayName(context, sourceUri, "input-1.bin"),
                    "input-1.bin"
                )
                val tempIn = context.cacheDir.resolve("aes-enc-in-${System.currentTimeMillis()}-$inputName")
                val targetName = sanitizeFileName(
                    when {
                        outputName.isBlank() -> "$inputName.aes"
                        outputName.endsWith(".aes", ignoreCase = true) -> outputName
                        else -> "$outputName.aes"
                    },
                    "$inputName.aes"
                )
                val tempOut = context.cacheDir.resolve("aes-enc-out-${System.currentTimeMillis()}-$targetName")
                try {
                    copyUriToFileCancellable(context, sourceUri, tempIn, "$inputName: cannot read plaintext input")
                    ensureNotCanceled()
                    AESCrypt.encryptFiles(
                        inputs = listOf(tempIn.absolutePath),
                        outputPath = tempOut.absolutePath,
                        password = password,
                        originalFileName = inputName,
                        checkCanceled = ::ensureNotCanceled
                    )
                    ensureNotCanceled()
                    val copiedUri = copyFileToTreeCancellable(
                        context = context,
                        source = tempOut,
                        treeUri = outputDirUri,
                        displayName = targetName
                    )
                    if (copiedUri != null) {
                        AESCryptOperationResult(
                            success = true,
                            message = "Encrypted successfully",
                            outputUri = copiedUri.toString(),
                            outputName = targetName,
                            outputLocation = describeUriLocation(copiedUri)
                        )
                    } else {
                        AESCryptOperationResult(
                            success = false,
                            message = "Encrypt failed: $inputName: unable to save output"
                        )
                    }
                } finally {
                    tempIn.delete()
                    tempOut.delete()
                }
            }
        } catch (_: CancellationException) {
            AESCryptOperationResult(
                success = false,
                message = "Encrypt canceled"
            )
        } catch (t: Throwable) {
            Log.e("AESCryptManager", "encrypt failed", t)
            AESCryptOperationResult(
                success = false,
                message = "Encrypt failed: ${t.message ?: "unknown error"}"
            )
        } finally {
            clearCancel()
            password.fill('\u0000')
        }
        _status.emit(result.message)
        result
    }

    private fun encryptBundledZip(
        context: Context,
        inputUris: List<Uri>,
        outputDirUri: Uri,
        outputName: String,
        password: CharArray
    ): AESCryptOperationResult {
        val bundlePlainName = bundledZipPlainName(outputName)
        val encryptedName = sanitizeFileName("$bundlePlainName.aes", "shared-files.zip.aes")
        val tempZip = context.cacheDir.resolve("aes-enc-bundle-${System.currentTimeMillis()}-$bundlePlainName")
        val tempOut = context.cacheDir.resolve("aes-enc-out-${System.currentTimeMillis()}-$encryptedName")

        try {
            zipUrisToFile(context, inputUris, tempZip)
            AESCrypt.encryptFiles(
                inputs = listOf(tempZip.absolutePath),
                outputPath = tempOut.absolutePath,
                password = password,
                originalFileName = bundlePlainName,
                checkCanceled = ::ensureNotCanceled
            )
            ensureNotCanceled()
            val copiedUri = copyFileToTreeCancellable(
                context = context,
                source = tempOut,
                treeUri = outputDirUri,
                displayName = encryptedName
            )
            return if (copiedUri != null) {
                AESCryptOperationResult(
                    success = true,
                    message = "Encrypted ${inputUris.size} files into ZIP successfully",
                    outputUri = copiedUri.toString(),
                    outputName = encryptedName,
                    outputLocation = describeUriLocation(copiedUri)
                )
            } else {
                AESCryptOperationResult(
                    success = false,
                    message = "Encrypt failed: unable to save output"
                )
            }
        } finally {
            tempZip.delete()
            tempOut.delete()
        }
    }

    private fun bundledZipPlainName(outputName: String): String {
        val requested = sanitizeFileName(outputName.trim(), "shared-files.zip.aes")
        val withoutAes = stripTrailingAesExtension(requested)
        val zipName = if (withoutAes.endsWith(".zip", ignoreCase = true)) {
            withoutAes
        } else {
            "$withoutAes.zip"
        }
        return sanitizeFileName(zipName, "shared-files.zip")
    }

    private fun zipUrisToFile(context: Context, inputUris: List<Uri>, outputZip: File) {
        outputZip.parentFile?.mkdirs()
        val usedNames = mutableSetOf<String>()
        ZipOutputStream(outputZip.outputStream().buffered()).use { zip ->
            inputUris.forEachIndexed { index, uri ->
                ensureNotCanceled()
                val fallbackName = "input-${index + 1}.bin"
                val entryName = uniqueZipEntryName(
                    sanitizeFileName(contentDisplayName(context, uri, fallbackName), fallbackName),
                    usedNames
                )
                zip.putNextEntry(ZipEntry(entryName))
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        ensureNotCanceled()
                        val read = input.read(buffer)
                        if (read < 0) break
                        zip.write(buffer, 0, read)
                    }
                } ?: throw IllegalStateException("$entryName: cannot read plaintext input")
                zip.closeEntry()
            }
        }
    }

    private fun copyUriToFileCancellable(context: Context, uri: Uri, dest: File, failureMessage: String) {
        dest.parentFile?.mkdirs()
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    ensureNotCanceled()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
            }
        } ?: throw IllegalStateException(failureMessage)
    }

    private fun copyFileToTreeCancellable(
        context: Context,
        source: File,
        treeUri: Uri,
        displayName: String
    ): Uri? {
        ensureNotCanceled()
        val parent = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val target = parent.findFile(displayName)
            ?: parent.createFile(guessMimeTypeFromName(displayName), displayName)
            ?: return null
        context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
            source.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    ensureNotCanceled()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
            }
        } ?: return null
        return target.uri
    }

    private fun uniqueZipEntryName(candidate: String, usedNames: MutableSet<String>): String {
        if (usedNames.add(candidate.lowercase())) return candidate

        val dotIndex = candidate.lastIndexOf('.')
        val base = if (dotIndex > 0) candidate.substring(0, dotIndex) else candidate
        val suffix = if (dotIndex > 0) candidate.substring(dotIndex) else ""
        var counter = 2
        while (true) {
            val next = "$base ($counter)$suffix"
            if (usedNames.add(next.lowercase())) return next
            counter += 1
        }
    }
}
