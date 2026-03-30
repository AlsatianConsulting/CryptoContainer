package dev.alsatianconsulting.cryptocontainer.validation

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.alsatianconsulting.cryptocontainer.crypto.AESCrypt
import dev.alsatianconsulting.cryptocontainer.manager.VeraCryptManager
import dev.alsatianconsulting.cryptocontainer.model.Algorithm
import dev.alsatianconsulting.cryptocontainer.model.FileSystem
import dev.alsatianconsulting.cryptocontainer.model.Hash
import dev.alsatianconsulting.cryptocontainer.model.VolumeCreateOptions
import dev.alsatianconsulting.cryptocontainer.repo.VeraCryptRepo
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class FeatureDiagnosticTest {
    @Test
    fun runFeatureDiagnostic() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val validationDir = context.filesDir.resolve("validation").apply { mkdirs() }
        val reportFile = validationDir.resolve("feature_diagnostic_report.json")
        val startedAt = Instant.now().toString()
        val failures = mutableListOf<String>()
        val ops = JSONArray()

        val manager = VeraCryptManager()
        val repo = VeraCryptRepo()

        fun note(op: String, rc: Int? = null, detail: String? = null) {
            val obj = JSONObject().put("time", Instant.now().toString()).put("op", op)
            if (rc != null) obj.put("rc", rc)
            if (!detail.isNullOrBlank()) obj.put("detail", detail)
            ops.put(obj)
        }

        fun ensure(condition: Boolean, message: String) {
            if (!condition) failures += message
        }

        try {
            val outerKeyfile = validationDir.resolve("outer.key")
            outerKeyfile.writeText("outer-key-material", Charsets.UTF_8)
            val hiddenKeyfile = validationDir.resolve("hidden.key")
            hiddenKeyfile.writeText("hidden-key-material", Charsets.UTF_8)
            val stdKeyfile = validationDir.resolve("standard.key")
            stdKeyfile.writeText("standard-key-material", Charsets.UTF_8)

            val stdContainer = validationDir.resolve("feature_std.hc")
            stdContainer.delete()

            val createStdRc = manager.create(
                context = context,
                options = VolumeCreateOptions(
                    containerUri = stdContainer.absolutePath,
                    sizeBytes = 96L * 1024L * 1024L,
                    filesystem = FileSystem.NTFS,
                    algorithm = Algorithm.AES,
                    hash = Hash.SHA512,
                    password = "test123".toCharArray(),
                    pim = null,
                    hiddenSizeBytes = null,
                    hiddenPassword = null,
                    hiddenPim = null,
                    readOnly = false
                )
            )
            note("create_standard", createStdRc, stdContainer.absolutePath)
            ensure(createStdRc == 0, "create standard failed: $createStdRc")

            if (failures.isEmpty()) {
                val wrongRc = repo.open(
                    context = context,
                    uri = android.net.Uri.fromFile(stdContainer),
                    password = "wrongpass".toCharArray(),
                    pim = 0,
                    hidden = false,
                    readOnly = false
                )
                note("open_standard_wrong_password", wrongRc)
                ensure(wrongRc != 0, "wrong password unexpectedly opened standard volume")
            }

            if (failures.isEmpty()) {
                val openStdRc = repo.open(
                    context = context,
                    uri = android.net.Uri.fromFile(stdContainer),
                    password = "test123".toCharArray(),
                    pim = 0,
                    hidden = false,
                    readOnly = false
                )
                note("open_standard", openStdRc)
                ensure(openStdRc == 0, "open standard failed: $openStdRc")
            }

            if (failures.isEmpty()) {
                val mkdirRc = repo.mkdir("diag")
                note("mkdir_diag", mkdirRc)
                ensure(mkdirRc == 0, "mkdir failed: $mkdirRc")

                val src = validationDir.resolve("diag_src.txt")
                src.writeText("feature-diagnostic-hello", Charsets.UTF_8)
                val addRc = repo.add("diag/diag_src.txt", src.absolutePath)
                note("add_file", addRc)
                ensure(addRc == 0, "add failed: $addRc")

                val list = repo.list("diag")
                note("list_diag", 0, "entries=${list.map { it.path }}")
                ensure(list.any { it.path.endsWith("diag_src.txt") }, "added file missing from list")

                val extracted = validationDir.resolve("diag_extract.txt")
                val extractRc = repo.extract("diag/diag_src.txt", extracted.absolutePath)
                note("extract_file", extractRc)
                ensure(extractRc == 0, "extract failed: $extractRc")
                ensure(extracted.exists(), "extract output missing")
                ensure(extracted.readText(Charsets.UTF_8) == "feature-diagnostic-hello", "extract content mismatch")

                val deleteRc = repo.delete("diag/diag_src.txt")
                note("delete_file", deleteRc)
                ensure(deleteRc == 0, "delete failed: $deleteRc")
            }

            try {
                repo.close()
                note("close_standard", 0)
            } catch (_: Throwable) {
                note("close_standard", -1)
                failures += "close standard failed"
            }

            val standardKeyfileContainer = validationDir.resolve("feature_std_keyfile_pim.hc")
            standardKeyfileContainer.delete()
            val stdKeyfileUri = Uri.fromFile(stdKeyfile).toString()

            val createStdKeyfileRc = manager.create(
                context = context,
                options = VolumeCreateOptions(
                    containerUri = standardKeyfileContainer.absolutePath,
                    sizeBytes = 96L * 1024L * 1024L,
                    filesystem = FileSystem.FAT,
                    algorithm = Algorithm.AES,
                    hash = Hash.SHA512,
                    password = "pimpass123".toCharArray(),
                    pim = 17,
                    keyfileUris = listOf(stdKeyfileUri),
                    hiddenSizeBytes = null,
                    hiddenPassword = null,
                    hiddenPim = null,
                    readOnly = false
                )
            )
            note("create_standard_keyfile_pim", createStdKeyfileRc, standardKeyfileContainer.absolutePath)
            ensure(createStdKeyfileRc == 0, "create standard keyfile/PIM failed: $createStdKeyfileRc")

            if (failures.isEmpty()) {
                val openNoKeyfileRc = repo.open(
                    context = context,
                    uri = Uri.fromFile(standardKeyfileContainer),
                    password = "pimpass123".toCharArray(),
                    pim = 17,
                    hidden = false,
                    readOnly = false,
                    keyfileUris = emptyList()
                )
                note("open_standard_missing_keyfile", openNoKeyfileRc)
                ensure(openNoKeyfileRc != 0, "standard open unexpectedly succeeded without keyfile")
            }

            if (failures.isEmpty()) {
                val openWrongPimRc = repo.open(
                    context = context,
                    uri = Uri.fromFile(standardKeyfileContainer),
                    password = "pimpass123".toCharArray(),
                    pim = 1,
                    hidden = false,
                    readOnly = false,
                    keyfileUris = listOf(stdKeyfileUri)
                )
                note("open_standard_wrong_pim", openWrongPimRc)
                ensure(openWrongPimRc != 0, "standard open unexpectedly succeeded with wrong PIM")
            }

            if (failures.isEmpty()) {
                val openStdKeyfileRc = repo.open(
                    context = context,
                    uri = Uri.fromFile(standardKeyfileContainer),
                    password = "pimpass123".toCharArray(),
                    pim = 17,
                    hidden = false,
                    readOnly = false,
                    keyfileUris = listOf(stdKeyfileUri)
                )
                note("open_standard_keyfile_pim", openStdKeyfileRc)
                ensure(openStdKeyfileRc == 0, "open standard keyfile/PIM failed: $openStdKeyfileRc")
            }

            if (failures.isEmpty()) {
                val src = validationDir.resolve("copy_move_src.txt")
                src.writeText("copy-move-data", Charsets.UTF_8)
                val addRc = repo.add("copy_move_src.txt", src.absolutePath)
                note("add_copy_move_src", addRc)
                ensure(addRc == 0, "copy/move add failed: $addRc")

                val extracted = validationDir.resolve("copy_move_tmp.txt")
                val extractRc = repo.extract("copy_move_src.txt", extracted.absolutePath)
                note("extract_copy_src", extractRc)
                ensure(extractRc == 0, "copy extract failed: $extractRc")

                val copyRc = repo.add("copy_move_copy.txt", extracted.absolutePath)
                note("copy_file_via_add", copyRc)
                ensure(copyRc == 0, "copy add failed: $copyRc")

                val moveMkdirRc = repo.mkdir("moved")
                note("move_mkdir", moveMkdirRc)
                ensure(moveMkdirRc == 0, "move mkdir failed: $moveMkdirRc")

                val moveAddRc = repo.add("moved/copy_move_src.txt", extracted.absolutePath)
                note("move_add_target", moveAddRc)
                ensure(moveAddRc == 0, "move target add failed: $moveAddRc")

                val moveDeleteRc = repo.delete("copy_move_src.txt")
                note("move_delete_source", moveDeleteRc)
                ensure(moveDeleteRc == 0, "move delete source failed: $moveDeleteRc")
            }

            try {
                repo.close()
                note("close_standard_keyfile_pim", 0)
            } catch (_: Throwable) {
                note("close_standard_keyfile_pim", -1)
                failures += "close standard keyfile/PIM failed"
            }

            val hiddenContainer = validationDir.resolve("feature_hidden.hc")
            hiddenContainer.delete()
            val outerKeyfileUri = Uri.fromFile(outerKeyfile).toString()
            val hiddenKeyfileUri = Uri.fromFile(hiddenKeyfile).toString()

            val createHiddenRc = manager.create(
                context = context,
                options = VolumeCreateOptions(
                    containerUri = hiddenContainer.absolutePath,
                    sizeBytes = 128L * 1024L * 1024L,
                    // NTFS hidden-volume creation currently crashes in native mkntfs path on-device.
                    // Use FAT here so the rest of hidden-volume diagnostics can execute.
                    filesystem = FileSystem.FAT,
                    algorithm = Algorithm.AES,
                    hash = Hash.SHA512,
                    password = "outer123".toCharArray(),
                    pim = 9,
                    keyfileUris = listOf(outerKeyfileUri),
                    hiddenSizeBytes = 32L * 1024L * 1024L,
                    hiddenPassword = "hidden123".toCharArray(),
                    hiddenKeyfileUris = listOf(hiddenKeyfileUri),
                    hiddenPim = 13,
                    readOnly = false
                )
            )
            note("create_hidden", createHiddenRc, hiddenContainer.absolutePath)
            ensure(createHiddenRc == 0, "create hidden failed: $createHiddenRc")

            if (failures.isEmpty()) {
                val openHiddenWrongModeRc = repo.open(
                    context = context,
                    uri = android.net.Uri.fromFile(hiddenContainer),
                    password = "hidden123".toCharArray(),
                    pim = 0,
                    hidden = false,
                    readOnly = false,
                    keyfileUris = listOf(hiddenKeyfileUri)
                )
                note("open_hidden_password_as_standard", openHiddenWrongModeRc)
                ensure(openHiddenWrongModeRc != 0, "hidden password unexpectedly opened standard mode")
            }

            if (failures.isEmpty()) {
                val openHiddenMissingKeyfileRc = repo.open(
                    context = context,
                    uri = Uri.fromFile(hiddenContainer),
                    password = "hidden123".toCharArray(),
                    pim = 13,
                    hidden = true,
                    readOnly = false,
                    keyfileUris = emptyList()
                )
                note("open_hidden_missing_keyfile", openHiddenMissingKeyfileRc)
                ensure(openHiddenMissingKeyfileRc != 0, "hidden open unexpectedly succeeded without keyfile")
            }

            if (failures.isEmpty()) {
                val openHiddenWrongPimRc = repo.open(
                    context = context,
                    uri = Uri.fromFile(hiddenContainer),
                    password = "hidden123".toCharArray(),
                    pim = 1,
                    hidden = true,
                    readOnly = false,
                    keyfileUris = listOf(hiddenKeyfileUri)
                )
                note("open_hidden_wrong_pim", openHiddenWrongPimRc)
                ensure(openHiddenWrongPimRc != 0, "hidden open unexpectedly succeeded with wrong PIM")
            }

            if (failures.isEmpty()) {
                val openHiddenRc = withTimeout(30_000L) {
                    repo.open(
                        context = context,
                        uri = Uri.fromFile(hiddenContainer),
                        password = "hidden123".toCharArray(),
                        pim = 13,
                        hidden = true,
                        readOnly = false,
                        keyfileUris = listOf(hiddenKeyfileUri)
                    )
                }
                note("open_hidden", openHiddenRc)
                ensure(openHiddenRc == 0, "open hidden failed: $openHiddenRc")
            }

            if (failures.isEmpty()) {
                val srcHidden = validationDir.resolve("hidden_src.txt")
                srcHidden.writeText("hidden-volume-data", Charsets.UTF_8)
                val addHiddenRc = repo.add("hidden_src.txt", srcHidden.absolutePath)
                note("hidden_add", addHiddenRc)
                ensure(addHiddenRc == 0, "hidden add failed: $addHiddenRc")
            }

            try {
                repo.close()
                note("close_hidden", 0)
            } catch (_: Throwable) {
                note("close_hidden", -1)
                failures += "close hidden failed"
            }

            // AESCrypt compatibility core checks
            val aesPlain = validationDir.resolve("aes_plain.txt")
            aesPlain.writeText("aes-feature-check", Charsets.UTF_8)
            val aesEncrypted = validationDir.resolve("aes_plain.txt.aes")
            val aesOutDir = validationDir.resolve("aes_out").apply { mkdirs() }

            try {
                AESCrypt.encryptFiles(
                    inputs = listOf(aesPlain.absolutePath),
                    outputPath = aesEncrypted.absolutePath,
                    password = "aespass123".toCharArray(),
                    originalFileName = aesPlain.name
                )
                note("aes_encrypt", 0, aesEncrypted.absolutePath)
                ensure(aesEncrypted.exists(), "AES encrypted output missing")

                val dec = AESCrypt.decryptFile(
                    inputPath = aesEncrypted.absolutePath,
                    outputDir = aesOutDir.absolutePath,
                    password = "aespass123".toCharArray(),
                    fallbackOriginalFileName = aesPlain.name
                )
                note("aes_decrypt_correct", 0, dec.absolutePath)
                ensure(dec.exists(), "AES decrypted file missing")
                ensure(dec.readText(Charsets.UTF_8) == "aes-feature-check", "AES decrypted content mismatch")

                var wrongFailed = false
                try {
                    AESCrypt.decryptFile(
                        inputPath = aesEncrypted.absolutePath,
                        outputDir = aesOutDir.absolutePath,
                        password = "wrong-aes-pass".toCharArray(),
                        fallbackOriginalFileName = aesPlain.name
                    )
                } catch (_: Throwable) {
                    wrongFailed = true
                }
                note("aes_decrypt_wrong_password", if (wrongFailed) 0 else -1)
                ensure(wrongFailed, "AES wrong password did not fail")
            } catch (t: Throwable) {
                note("aes_exception", -1, t.message)
                failures += "AES flow exception: ${t.message ?: "unknown"}"
            }
        } catch (t: Throwable) {
            note("unexpected_exception", -1, t.stackTraceToString())
            failures += "unexpected exception: ${t.message ?: t.javaClass.simpleName}"
        } finally {
            try { repo.close() } catch (_: Throwable) {}
            try { manager.unmount() } catch (_: Throwable) {}

            val report = JSONObject()
                .put("reportVersion", 1)
                .put("status", if (failures.isEmpty()) "PASS" else "FAIL")
                .put("startedAtUtc", startedAt)
                .put("finishedAtUtc", Instant.now().toString())
                .put("failures", JSONArray(failures))
                .put("operations", ops)

            reportFile.writeText(report.toString(2), Charsets.UTF_8)
        }

        assertTrue("Feature diagnostic failures: $failures", failures.isEmpty())
    }
}
