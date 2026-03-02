package dev.alsatianconsulting.cryptocontainer.crypto

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.KeyParameter

/**
 * AESCrypt v3 implementation compatible with the official AES Crypt CLI.
 *
 * This also retains a fallback reader for the app's older non-standard v2
 * format so users can still decrypt files created by previous builds.
 */
object AESCrypt {
    private const val STREAM_VERSION_V3: Byte = 0x03
    private const val LEGACY_VERSION_V2: Byte = 0x02
    private const val RESERVED: Byte = 0x00
    private const val DEFAULT_ITERATIONS = 300_000
    private const val IV_LEN = 16
    private const val AES_KEY_LEN = 32
    private const val SESSION_DATA_LEN = 48
    private const val HMAC_LEN = 32
    private const val BUFFER_SIZE = 8192
    private const val CREATED_BY = "CryptoContainer"
    private const val ORIGINAL_FILENAME_EXTENSION = "urn:dev.alsatianconsulting.cryptocontainer:original-filename"

    fun encryptFiles(
        inputs: List<String>,
        outputPath: String,
        password: CharArray,
        originalFileName: String = File(inputs.first()).name,
        checkCanceled: () -> Unit = {}
    ) {
        if (inputs.isEmpty()) throw IllegalArgumentException("No input files")

        val inputFile = File(inputs.first())
        val outputFile = File(outputPath)
        outputFile.parentFile?.mkdirs()
        val safeOriginalFileName = sanitizeStoredFileName(originalFileName, inputFile.name)
        checkCanceled()

        val passwordBytes = password.concatToString().toByteArray(StandardCharsets.UTF_8)
        val publicIv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val sessionIv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val sessionKey = ByteArray(AES_KEY_LEN).also { SecureRandom().nextBytes(it) }
        val derivedKey = deriveV3Key(passwordBytes, publicIv, DEFAULT_ITERATIONS)

        try {
            FileInputStream(inputFile).use { input ->
                FileOutputStream(outputFile).use { output ->
                    output.write(byteArrayOf('A'.code.toByte(), 'E'.code.toByte(), 'S'.code.toByte(), STREAM_VERSION_V3, RESERVED))
                    writeExtension(output, "CREATED_BY", CREATED_BY)
                    writeExtension(output, ORIGINAL_FILENAME_EXTENSION, safeOriginalFileName)
                    output.write(byteArrayOf(0x00, 0x00))
                    writeInt32Be(output, DEFAULT_ITERATIONS)
                    output.write(publicIv)
                    checkCanceled()
                    writeSessionData(output, derivedKey, publicIv, sessionIv, sessionKey)
                    encryptPayload(input, output, sessionIv, sessionKey, checkCanceled)
                }
            }
        } finally {
            passwordBytes.fill(0)
            publicIv.fill(0)
            sessionIv.fill(0)
            sessionKey.fill(0)
            derivedKey.fill(0)
        }
    }

    fun decryptFile(
        inputPath: String,
        outputDir: String,
        password: CharArray,
        fallbackOriginalFileName: String? = null,
        checkCanceled: () -> Unit = {}
    ): File {
        val inputFile = File(inputPath)
        if (!inputFile.exists()) throw IllegalArgumentException("Encrypted input not found")
        checkCanceled()

        return RandomAccessFile(inputFile, "r").use { raf ->
            val header = ByteArray(5)
            raf.readFully(header)
            if (header[0] != 'A'.code.toByte() || header[1] != 'E'.code.toByte() || header[2] != 'S'.code.toByte()) {
                throw IllegalArgumentException("Invalid AESCrypt header")
            }

            when (header[3]) {
                STREAM_VERSION_V3 -> decryptStandardV3(
                    raf = raf,
                    inputFile = inputFile,
                    outputDir = outputDir,
                    password = password,
                    fallbackOriginalFileName = fallbackOriginalFileName,
                    checkCanceled = checkCanceled
                )
                LEGACY_VERSION_V2 -> decryptLegacyV2(
                    inputFile = inputFile,
                    outputDir = outputDir,
                    password = password,
                    fallbackOriginalFileName = fallbackOriginalFileName,
                    checkCanceled = checkCanceled
                )
                else -> throw IllegalArgumentException("Unsupported AESCrypt format version ${header[3].toInt() and 0xff}")
            }
        }
    }

    private fun decryptStandardV3(
        raf: RandomAccessFile,
        inputFile: File,
        outputDir: String,
        password: CharArray,
        fallbackOriginalFileName: String?,
        checkCanceled: () -> Unit
    ): File {
        val extensions = readExtensions(raf, checkCanceled)
        val originalFileName = sanitizeStoredFileName(
            extensions[ORIGINAL_FILENAME_EXTENSION],
            sanitizeStoredFileName(fallbackOriginalFileName, stripTrailingAesSuffix(inputFile.name))
        )

        val iterations = raf.readInt()
        if (iterations <= 0) throw IllegalArgumentException("Invalid AESCrypt iteration count")

        val publicIv = ByteArray(IV_LEN)
        raf.readFully(publicIv)

        val passwordBytes = password.concatToString().toByteArray(StandardCharsets.UTF_8)
        val derivedKey = deriveV3Key(passwordBytes, publicIv, iterations)

        try {
            checkCanceled()
            val encryptedSession = ByteArray(SESSION_DATA_LEN)
            raf.readFully(encryptedSession)

            val expectedSessionHmac = ByteArray(HMAC_LEN)
            raf.readFully(expectedSessionHmac)

            val actualSessionHmac = hmacSha256(derivedKey) {
                update(encryptedSession)
                update(byteArrayOf(STREAM_VERSION_V3))
            }
            if (!actualSessionHmac.contentEquals(expectedSessionHmac)) {
                throw IllegalArgumentException("Password incorrect or encrypted file was modified")
            }

            val sessionIv = xor(aesEcbDecrypt(derivedKey, encryptedSession.copyOfRange(0, 16)), publicIv)
            val sessionKey = ByteArray(AES_KEY_LEN)
            xorInto(
                aesEcbDecrypt(derivedKey, encryptedSession.copyOfRange(16, 32)),
                encryptedSession.copyOfRange(0, 16),
                sessionKey,
                0
            )
            xorInto(
                aesEcbDecrypt(derivedKey, encryptedSession.copyOfRange(32, 48)),
                encryptedSession.copyOfRange(16, 32),
                sessionKey,
                16
            )

            try {
                return decryptPayloadV3(raf, outputDir, sessionIv, sessionKey, originalFileName, checkCanceled)
            } finally {
                sessionIv.fill(0)
                sessionKey.fill(0)
            }
        } finally {
            passwordBytes.fill(0)
            publicIv.fill(0)
            derivedKey.fill(0)
        }
    }

    private fun decryptPayloadV3(
        raf: RandomAccessFile,
        outputDir: String,
        sessionIv: ByteArray,
        sessionKey: ByteArray,
        originalFileName: String,
        checkCanceled: () -> Unit
    ): File {
        val payloadStart = raf.filePointer
        val ciphertextLen = raf.length() - payloadStart - HMAC_LEN
        if (ciphertextLen < 0) {
            throw IllegalArgumentException("Truncated AESCrypt payload")
        }

        val outputDirectory = File(outputDir).apply { mkdirs() }
        val outFile = File(outputDirectory, originalFileName)
        val decryptCipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        decryptCipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(sessionKey, "AES"), IvParameterSpec(sessionIv))
        val ciphertextHmac = hmacSha256(sessionKey)

        val tempFile = File(outputDirectory, "${outFile.name}.partial")
        try {
            FileOutputStream(tempFile).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var remaining = ciphertextLen

                while (remaining > 0) {
                    checkCanceled()
                    val chunkSize = minOf(buffer.size.toLong(), remaining).toInt()
                    val read = raf.read(buffer, 0, chunkSize)
                    if (read != chunkSize) throw IllegalArgumentException("Unexpected end of encrypted payload")

                    ciphertextHmac.update(buffer, 0, read)
                    val plainChunk = decryptCipher.update(buffer, 0, read)
                    if (plainChunk != null && plainChunk.isNotEmpty()) {
                        output.write(plainChunk)
                    }
                    remaining -= read.toLong()
                }

                val expectedPayloadHmac = ByteArray(HMAC_LEN)
                raf.readFully(expectedPayloadHmac)
                val actualPayloadHmac = ciphertextHmac.doFinal()
                if (!actualPayloadHmac.contentEquals(expectedPayloadHmac)) {
                    throw IllegalArgumentException("Password incorrect or encrypted file was modified")
                }

                checkCanceled()
                val finalPlain = decryptCipher.doFinal()
                if (finalPlain.isNotEmpty()) {
                    output.write(finalPlain)
                }
            }

            if (outFile.exists() && !outFile.delete()) {
                throw IllegalStateException("Unable to replace existing decrypted file")
            }
            if (!tempFile.renameTo(outFile)) {
                throw IllegalStateException("Unable to finalize decrypted file")
            }
            return outFile
        } catch (t: Throwable) {
            tempFile.delete()
            throw t
        }
    }

    private fun decryptLegacyV2(
        inputFile: File,
        outputDir: String,
        password: CharArray,
        fallbackOriginalFileName: String?,
        checkCanceled: () -> Unit
    ): File {
        val passwordBytes = password.concatToString().toByteArray(StandardCharsets.UTF_8)
        try {
            FileInputStream(inputFile).use { input ->
                val header = ByteArray(5)
                if (input.read(header) != 5) throw IllegalArgumentException("Invalid header")

                val salt = ByteArray(IV_LEN)
                if (input.read(salt) != IV_LEN) throw IllegalArgumentException("Invalid salt")

                val iv = ByteArray(IV_LEN)
                if (input.read(iv) != IV_LEN) throw IllegalArgumentException("Invalid IV")

                val keyBytes = deriveLegacyKeys(passwordBytes, salt)
                val aesKey = SecretKeySpec(keyBytes.copyOfRange(0, 32), "AES")
                val macKey = SecretKeySpec(keyBytes.copyOfRange(32, 64), "HmacSHA256")

                val ciphertextWithMac = input.readBytes()
                if (ciphertextWithMac.size < HMAC_LEN) throw IllegalArgumentException("Truncated encrypted file")

                val cipherBytes = ciphertextWithMac.copyOfRange(0, ciphertextWithMac.size - HMAC_LEN)
                val expectedMac = ciphertextWithMac.copyOfRange(ciphertextWithMac.size - HMAC_LEN, ciphertextWithMac.size)

                val mac = Mac.getInstance("HmacSHA256")
                mac.init(macKey)
                mac.update(header)
                mac.update(salt)
                mac.update(iv)
                mac.update(cipherBytes)
                val actualMac = mac.doFinal()
                if (!actualMac.contentEquals(expectedMac)) {
                    throw IllegalArgumentException("Password incorrect or encrypted file was modified")
                }

                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, aesKey, IvParameterSpec(iv))
                checkCanceled()
                val plain = cipher.doFinal(cipherBytes)

                val outFile = File(
                    outputDir,
                    sanitizeStoredFileName(fallbackOriginalFileName, stripTrailingAesSuffix(inputFile.name))
                )
                File(outputDir).mkdirs()
                FileOutputStream(outFile).use { it.write(plain) }
                return outFile
            }
        } finally {
            passwordBytes.fill(0)
        }
    }

    private fun encryptPayload(
        input: FileInputStream,
        output: FileOutputStream,
        sessionIv: ByteArray,
        sessionKey: ByteArray,
        checkCanceled: () -> Unit
    ) {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sessionKey, "AES"), IvParameterSpec(sessionIv))
        val payloadHmac = hmacSha256(sessionKey)
        val buffer = ByteArray(BUFFER_SIZE)

        while (true) {
            checkCanceled()
            val read = input.read(buffer)
            if (read < 0) break

            val encryptedChunk = cipher.update(buffer, 0, read)
            if (encryptedChunk != null && encryptedChunk.isNotEmpty()) {
                output.write(encryptedChunk)
                payloadHmac.update(encryptedChunk)
            }
        }

        checkCanceled()
        val finalChunk = cipher.doFinal()
        if (finalChunk.isNotEmpty()) {
            output.write(finalChunk)
            payloadHmac.update(finalChunk)
        }

        output.write(payloadHmac.doFinal())
    }

    private fun writeSessionData(
        output: FileOutputStream,
        derivedKey: ByteArray,
        publicIv: ByteArray,
        sessionIv: ByteArray,
        sessionKey: ByteArray
    ) {
        val sessionHmac = hmacSha256(derivedKey)
        val block1 = aesEcbEncrypt(derivedKey, xor(sessionIv, publicIv))
        val block2 = aesEcbEncrypt(derivedKey, xor(sessionKey.copyOfRange(0, 16), block1))
        val block3 = aesEcbEncrypt(derivedKey, xor(sessionKey.copyOfRange(16, 32), block2))

        output.write(block1)
        sessionHmac.update(block1)

        output.write(block2)
        sessionHmac.update(block2)

        output.write(block3)
        sessionHmac.update(block3)

        sessionHmac.update(byteArrayOf(STREAM_VERSION_V3))
        output.write(sessionHmac.doFinal())
    }

    private fun readExtensions(raf: RandomAccessFile, checkCanceled: () -> Unit): Map<String, String> {
        val extensions = linkedMapOf<String, String>()
        while (true) {
            checkCanceled()
            val length = raf.readUnsignedShort()
            if (length == 0) return extensions

            val payload = ByteArray(length)
            raf.readFully(payload)
            val separatorIndex = payload.indexOf(0)
            if (separatorIndex < 0) continue

            val identifier = payload.copyOfRange(0, separatorIndex).toString(StandardCharsets.UTF_8)
            val value = payload.copyOfRange(separatorIndex + 1, payload.size).toString(StandardCharsets.UTF_8)
            if (identifier.isNotBlank()) {
                extensions[identifier] = value
            }
        }
    }

    private fun writeExtension(output: FileOutputStream, identifier: String, value: String) {
        require(!identifier.contains('\u0000')) { "Extension identifiers cannot contain NUL bytes" }
        val identifierBytes = identifier.toByteArray(StandardCharsets.UTF_8)
        val valueBytes = value.toByteArray(StandardCharsets.UTF_8)
        val length = identifierBytes.size + 1 + valueBytes.size
        require(length <= 0xffff) { "Extension is too large" }
        output.write(byteArrayOf(((length ushr 8) and 0xff).toByte(), (length and 0xff).toByte()))
        output.write(identifierBytes)
        output.write(0)
        output.write(valueBytes)
    }

    private fun writeInt32Be(output: FileOutputStream, value: Int) {
        output.write(
            byteArrayOf(
                ((value ushr 24) and 0xff).toByte(),
                ((value ushr 16) and 0xff).toByte(),
                ((value ushr 8) and 0xff).toByte(),
                (value and 0xff).toByte()
            )
        )
    }

    private fun deriveV3Key(passwordBytes: ByteArray, salt: ByteArray, iterations: Int): ByteArray {
        val generator = PKCS5S2ParametersGenerator(SHA512Digest())
        generator.init(passwordBytes, salt, iterations)
        return (generator.generateDerivedParameters(AES_KEY_LEN * 8) as KeyParameter).key
    }

    private fun deriveLegacyKeys(passwordBytes: ByteArray, salt: ByteArray): ByteArray {
        val generator = PKCS5S2ParametersGenerator(SHA256Digest())
        generator.init(passwordBytes, salt, 8192)
        return (generator.generateDerivedParameters((AES_KEY_LEN + HMAC_LEN) * 8) as KeyParameter).key
    }

    private inline fun hmacSha256(key: ByteArray, block: Mac.() -> Unit): ByteArray {
        val mac = hmacSha256(key)
        mac.block()
        return mac.doFinal()
    }

    private fun hmacSha256(key: ByteArray): Mac {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac
    }

    private fun aesEcbEncrypt(key: ByteArray, block: ByteArray): ByteArray {
        require(block.size == 16) { "AES block must be 16 bytes" }
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(block)
    }

    private fun aesEcbDecrypt(key: ByteArray, block: ByteArray): ByteArray {
        require(block.size == 16) { "AES block must be 16 bytes" }
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(block)
    }

    private fun xor(left: ByteArray, right: ByteArray): ByteArray {
        require(left.size == right.size) { "XOR operands must be equal length" }
        return ByteArray(left.size) { index -> (left[index].toInt() xor right[index].toInt()).toByte() }
    }

    private fun xorInto(left: ByteArray, right: ByteArray, destination: ByteArray, offset: Int) {
        require(left.size == right.size) { "XOR operands must be equal length" }
        for (index in left.indices) {
            destination[offset + index] = (left[index].toInt() xor right[index].toInt()).toByte()
        }
    }

    private fun sanitizeStoredFileName(candidate: String?, fallback: String): String {
        val raw = candidate?.trim().orEmpty()
        val normalized = raw
            .replace('\\', '/')
            .substringAfterLast('/')
            .trim()
        return if (normalized.isBlank() || normalized == "." || normalized == "..") fallback else normalized
    }

    private fun stripTrailingAesSuffix(fileName: String): String {
        return if (fileName.endsWith(".aes", ignoreCase = true)) {
            fileName.dropLast(4)
        } else {
            fileName
        }
    }
}
