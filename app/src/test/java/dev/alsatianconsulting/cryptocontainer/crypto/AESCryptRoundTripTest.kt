package dev.alsatianconsulting.cryptocontainer.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64
import kotlin.io.path.createTempDirectory

class AESCryptRoundTripTest {
    @Test
    fun decryptsOfficialCliV3Sample() {
        val workDir = createTempDirectory("aes-cli-sample-").toFile()
        val encryptedFile = File(workDir, "sample.txt.aes")
        val decryptedDir = File(workDir, "out").apply { mkdirs() }

        try {
            encryptedFile.writeBytes(
                Base64.getDecoder().decode(
                    "QUVTAwAAH0NSRUFURURfQlkAYWVzY3J5cHRfY2xpIDQuNS4wLjAAAAAEk+Ad5aYtkx5rRehkp0T9ig6Jp3+traOQh9Npxbu2cuO1+Jn22svFNgBK9MaHsv/q5f/8F8oNzDHlrdYvG7+FFCZ68c4TpD3JYr9JoKal54IhSgEcbhg+yQfZSGPhfpTgBl6lvvAQLAdj3rheHfHYFcBh6Dk/IlLXB5TQIs4XYvuhu9j7/rIlENaksgt29RDDBgDQ7Z+jYZBzDET/5WnlLU/6"
                )
            )

            AESCrypt.decryptFile(
                inputPath = encryptedFile.absolutePath,
                outputDir = decryptedDir.absolutePath,
                password = "testpass123".toCharArray()
            )

            val decryptedFile = File(decryptedDir, "sample.txt")
            assertTrue(decryptedFile.exists())
            assertEquals("interop plaintext\n", decryptedFile.readText())
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun decryptsOfficialCliV3SampleUsingProvidedFallbackName() {
        val workDir = createTempDirectory("aes-cli-fallback-").toFile()
        val encryptedFile = File(workDir, "aes-in-1735945959.aes")
        val decryptedDir = File(workDir, "out").apply { mkdirs() }

        try {
            encryptedFile.writeBytes(
                Base64.getDecoder().decode(
                    "QUVTAwAAH0NSRUFURURfQlkAYWVzY3J5cHRfY2xpIDQuNS4wLjAAAAAEk+Ad5aYtkx5rRehkp0T9ig6Jp3+traOQh9Npxbu2cuO1+Jn22svFNgBK9MaHsv/q5f/8F8oNzDHlrdYvG7+FFCZ68c4TpD3JYr9JoKal54IhSgEcbhg+yQfZSGPhfpTgBl6lvvAQLAdj3rheHfHYFcBh6Dk/IlLXB5TQIs4XYvuhu9j7/rIlENaksgt29RDDBgDQ7Z+jYZBzDET/5WnlLU/6"
                )
            )

            AESCrypt.decryptFile(
                inputPath = encryptedFile.absolutePath,
                outputDir = decryptedDir.absolutePath,
                password = "testpass123".toCharArray(),
                fallbackOriginalFileName = "sample.txt"
            )

            val decryptedFile = File(decryptedDir, "sample.txt")
            assertTrue(decryptedFile.exists())
            assertEquals("interop plaintext\n", decryptedFile.readText())
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun encryptThenDecrypt_roundTripsOriginalBytes() {
        val workDir = createTempDirectory("aes-roundtrip-").toFile()
        val plainFile = File(workDir, "plain.txt").apply {
            writeBytes("hello from aescrypt".toByteArray())
        }
        val encryptedFile = File(workDir, "plain.txt.aes")
        val decryptedDir = File(workDir, "out").apply { mkdirs() }

        try {
            AESCrypt.encryptFiles(
                inputs = listOf(plainFile.absolutePath),
                outputPath = encryptedFile.absolutePath,
                password = "correct horse battery staple".toCharArray()
            )

            assertTrue(encryptedFile.exists())
            assertTrue(encryptedFile.length() > 0L)

            AESCrypt.decryptFile(
                inputPath = encryptedFile.absolutePath,
                outputDir = decryptedDir.absolutePath,
                password = "correct horse battery staple".toCharArray()
            )

            val decryptedFile = File(decryptedDir, "plain.txt")
            assertTrue(decryptedFile.exists())
            assertArrayEquals(plainFile.readBytes(), decryptedFile.readBytes())
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun decryptRestoresOriginalFilenameFromMetadata() {
        val workDir = createTempDirectory("aes-name-roundtrip-").toFile()
        val stagedPlainFile = File(workDir, "staged-input.bin").apply {
            writeBytes("named payload".toByteArray())
        }
        val encryptedFile = File(workDir, "renamed-output.aes")
        val decryptedDir = File(workDir, "out").apply { mkdirs() }

        try {
            AESCrypt.encryptFiles(
                inputs = listOf(stagedPlainFile.absolutePath),
                outputPath = encryptedFile.absolutePath,
                password = "correct horse battery staple".toCharArray(),
                originalFileName = "report.final.pdf"
            )

            AESCrypt.decryptFile(
                inputPath = encryptedFile.absolutePath,
                outputDir = decryptedDir.absolutePath,
                password = "correct horse battery staple".toCharArray()
            )

            val decryptedFile = File(decryptedDir, "report.final.pdf")
            assertTrue(decryptedFile.exists())
            assertArrayEquals(stagedPlainFile.readBytes(), decryptedFile.readBytes())
        } finally {
            workDir.deleteRecursively()
        }
    }
}
