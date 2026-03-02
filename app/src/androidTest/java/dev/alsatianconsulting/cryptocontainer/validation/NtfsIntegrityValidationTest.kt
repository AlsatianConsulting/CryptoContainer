package dev.alsatianconsulting.cryptocontainer.validation

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.alsatianconsulting.cryptocontainer.manager.VeraCryptManager
import dev.alsatianconsulting.cryptocontainer.model.Algorithm
import dev.alsatianconsulting.cryptocontainer.model.FileSystem
import dev.alsatianconsulting.cryptocontainer.model.Hash
import dev.alsatianconsulting.cryptocontainer.model.VcFsType
import dev.alsatianconsulting.cryptocontainer.model.VolumeCreateOptions
import dev.alsatianconsulting.cryptocontainer.repo.VeraCryptRepo
import dev.alsatianconsulting.cryptocontainer.util.sanitizeFileName
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Collections
import java.util.Locale
import java.util.Random

@RunWith(AndroidJUnit4::class)
class NtfsIntegrityValidationTest {
    @Test
    fun runNtfsIntegrityStress() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = parseConfig(args)
        val validationDir = context.filesDir.resolve("validation").apply { mkdirs() }
        val containerName = normalizeContainerName(config.containerName)
        val containerFile = validationDir.resolve(containerName)
        val reportFile = validationDir.resolve(REPORT_FILE_NAME)
        val repo = VeraCryptRepo()
        val manager = VeraCryptManager()
        val random = Random(config.seed ?: System.nanoTime())
        val expectedDigests = linkedMapOf<String, String>()
        val failures = mutableListOf<String>()
        val operations = JSONArray()
        val startedAt = Instant.now().toString()
        val stats = ValidationStats()

        if (containerFile.exists()) {
            containerFile.delete()
        }

        fun note(op: String, rc: Int? = null, detail: String? = null) {
            val entry = JSONObject()
                .put("time", Instant.now().toString())
                .put("op", op)
            if (rc != null) entry.put("rc", rc)
            if (!detail.isNullOrBlank()) entry.put("detail", detail)
            operations.put(entry)
        }

        try {
            val createRc = manager.create(
                context = context,
                options = VolumeCreateOptions(
                    containerUri = containerFile.absolutePath,
                    sizeBytes = config.containerSizeBytes,
                    filesystem = FileSystem.NTFS,
                    algorithm = Algorithm.AES,
                    hash = Hash.SHA512,
                    password = config.password,
                    pim = null,
                    hiddenSizeBytes = null,
                    hiddenPassword = null,
                    hiddenPim = null,
                    readOnly = false
                )
            )
            note("create", createRc, "container=${containerFile.name}")
            if (createRc != 0) {
                failures += "create failed: $createRc"
            }

            if (failures.isEmpty()) {
                val openRc = repo.open(
                    context = context,
                    uri = Uri.fromFile(containerFile),
                    password = config.password,
                    pim = 0,
                    hidden = false,
                    readOnly = false
                )
                note("open", openRc)
                if (openRc != 0) {
                    failures += "open failed: $openRc"
                }
            }

            if (failures.isEmpty()) {
                val fsInfo = repo.fsInfo.value
                if (fsInfo?.type != VcFsType.NTFS) {
                    failures += "unexpected fs type: ${fsInfo?.type}"
                } else if (fsInfo.readOnly) {
                    failures += "unexpected read-only mount while expecting writable NTFS"
                }
            }

            for (idx in 0 until config.iterations) {
                if (failures.isNotEmpty()) break
                val path = "stress/dir_${idx % DIRECTORY_FANOUT}/file_$idx.bin"
                val payload = randomPayload(random, config.minPayloadBytes, config.maxPayloadBytes)
                val addRc = addPayload(context, repo, path, payload)
                stats.addAttempts += 1
                note("add", addRc, "path=$path bytes=${payload.size}")
                if (addRc != 0) {
                    failures += "add failed at iteration $idx: $addRc"
                    break
                }
                stats.addSuccess += 1
                expectedDigests[path] = sha256Hex(payload)

                if (idx % config.overwriteEvery == 0) {
                    val overwritePayload = randomPayload(random, config.minPayloadBytes, config.maxPayloadBytes)
                    val overwriteRc = addPayload(context, repo, path, overwritePayload)
                    stats.overwriteAttempts += 1
                    note("overwrite", overwriteRc, "path=$path bytes=${overwritePayload.size}")
                    if (overwriteRc != 0) {
                        failures += "overwrite failed at iteration $idx: $overwriteRc"
                        break
                    }
                    stats.overwriteSuccess += 1
                    expectedDigests[path] = sha256Hex(overwritePayload)
                }

                if (idx % config.verifyEvery == 0) {
                    val verify = verifyDigest(context, repo, path, expectedDigests[path].orEmpty())
                    stats.verifyAttempts += 1
                    note("verify", verify.rc, "path=$path digest=${verify.actualDigest}")
                    if (verify.rc != 0) {
                        failures += "extract failed during verify at iteration $idx: ${verify.rc}"
                        break
                    }
                    if (!verify.matches) {
                        failures += "digest mismatch at iteration $idx path=$path expected=${verify.expectedDigest} actual=${verify.actualDigest}"
                        break
                    }
                    stats.verifySuccess += 1
                }
            }

            if (failures.isEmpty() && expectedDigests.isNotEmpty()) {
                val deleteTargets = expectedDigests.keys.toMutableList()
                Collections.shuffle(deleteTargets, random)
                val deleteCount = (deleteTargets.size * config.deletePercent) / 100
                for (path in deleteTargets.take(deleteCount)) {
                    val deleteRc = repo.delete(path)
                    stats.deleteAttempts += 1
                    note("delete", deleteRc, "path=$path")
                    if (deleteRc != 0) {
                        failures += "delete failed for $path: $deleteRc"
                        break
                    }
                    stats.deleteSuccess += 1
                    expectedDigests.remove(path)
                }
            }

            if (failures.isEmpty() && expectedDigests.isNotEmpty()) {
                val verifyTargets = expectedDigests.keys.toMutableList()
                Collections.shuffle(verifyTargets, random)
                val sampleCount = verifyTargets.size.coerceAtMost(config.finalVerifySampleSize)
                for (path in verifyTargets.take(sampleCount)) {
                    val verify = verifyDigest(context, repo, path, expectedDigests[path].orEmpty())
                    stats.finalVerifyAttempts += 1
                    note("final_verify", verify.rc, "path=$path digest=${verify.actualDigest}")
                    if (verify.rc != 0) {
                        failures += "final extract failed for $path: ${verify.rc}"
                        break
                    }
                    if (!verify.matches) {
                        failures += "final digest mismatch for $path expected=${verify.expectedDigest} actual=${verify.actualDigest}"
                        break
                    }
                    stats.finalVerifySuccess += 1
                }
            }

            if (repo.isOpen()) {
                val closeRc = try {
                    repo.close()
                    0
                } catch (_: Throwable) {
                    -1
                }
                note("close", closeRc)
                if (closeRc != 0) failures += "close failed before read-only reopen"
            }

            if (failures.isEmpty()) {
                val reopenRc = repo.open(
                    context = context,
                    uri = Uri.fromFile(containerFile),
                    password = config.password,
                    pim = 0,
                    hidden = false,
                    readOnly = true
                )
                note("reopen_read_only", reopenRc)
                if (reopenRc != 0) {
                    failures += "read-only reopen failed: $reopenRc"
                }
            }

            if (failures.isEmpty()) {
                val readOnlyEntries = repo.list("")
                stats.readOnlyListCount = readOnlyEntries.size
                note("list_read_only", 0, "entries=${readOnlyEntries.size}")
            }
        } catch (t: Throwable) {
            failures += "unexpected exception: ${t.javaClass.simpleName}: ${t.message ?: "no message"}"
            note("exception", -1, t.stackTraceToString())
        } finally {
            try {
                repo.close()
            } catch (_: Throwable) {
            }
            try {
                manager.unmount()
            } catch (_: Throwable) {
            }

            val report = JSONObject()
                .put("reportVersion", 1)
                .put("status", if (failures.isEmpty()) "PASS" else "FAIL")
                .put("startedAtUtc", startedAt)
                .put("finishedAtUtc", Instant.now().toString())
                .put(
                    "config",
                    JSONObject()
                        .put("containerName", containerName)
                        .put("containerSizeBytes", config.containerSizeBytes)
                        .put("iterations", config.iterations)
                        .put("minPayloadBytes", config.minPayloadBytes)
                        .put("maxPayloadBytes", config.maxPayloadBytes)
                        .put("verifyEvery", config.verifyEvery)
                        .put("overwriteEvery", config.overwriteEvery)
                        .put("deletePercent", config.deletePercent)
                        .put("finalVerifySampleSize", config.finalVerifySampleSize)
                        .put("seed", config.seed ?: JSONObject.NULL)
                )
                .put(
                    "device",
                    JSONObject()
                        .put("manufacturer", Build.MANUFACTURER)
                        .put("model", Build.MODEL)
                        .put("sdkInt", Build.VERSION.SDK_INT)
                        .put("fingerprint", Build.FINGERPRINT)
                )
                .put(
                    "stats",
                    JSONObject()
                        .put("addAttempts", stats.addAttempts)
                        .put("addSuccess", stats.addSuccess)
                        .put("overwriteAttempts", stats.overwriteAttempts)
                        .put("overwriteSuccess", stats.overwriteSuccess)
                        .put("verifyAttempts", stats.verifyAttempts)
                        .put("verifySuccess", stats.verifySuccess)
                        .put("deleteAttempts", stats.deleteAttempts)
                        .put("deleteSuccess", stats.deleteSuccess)
                        .put("finalVerifyAttempts", stats.finalVerifyAttempts)
                        .put("finalVerifySuccess", stats.finalVerifySuccess)
                        .put("readOnlyListCount", stats.readOnlyListCount)
                )
                .put("artifacts", JSONObject().put("containerFile", containerFile.absolutePath).put("reportFile", reportFile.absolutePath))
                .put("failures", JSONArray(failures))
                .put("operations", operations)
            reportFile.writeText(report.toString(2))
        }

        assertTrue(
            "NTFS validation failed. Pull report at files/validation/$REPORT_FILE_NAME from app data.",
            failures.isEmpty()
        )
    }

    private fun parseConfig(args: Bundle): ValidationConfig {
        val sizeMb = args.intArg("containerSizeMb", 128, minValue = 32)
        val minPayload = args.intArg("minPayloadBytes", 4096, minValue = 1024)
        val maxPayload = args.intArg("maxPayloadBytes", 262144, minValue = minPayload)
        return ValidationConfig(
            password = args.stringArg("vcPassword", "Validation-Password-Change-Me"),
            containerName = args.stringArg("containerName", DEFAULT_CONTAINER_NAME),
            containerSizeBytes = sizeMb.toLong() * 1024L * 1024L,
            iterations = args.intArg("iterations", 160, minValue = 1),
            minPayloadBytes = minPayload,
            maxPayloadBytes = maxPayload,
            verifyEvery = args.intArg("verifyEvery", 5, minValue = 1),
            overwriteEvery = args.intArg("overwriteEvery", 3, minValue = 1),
            deletePercent = args.intArg("deletePercent", 35, minValue = 0, maxValue = 90),
            finalVerifySampleSize = args.intArg("finalVerifySampleSize", 24, minValue = 1),
            seed = args.longArgOrNull("seed")
        )
    }

    private suspend fun addPayload(context: Context, repo: VeraCryptRepo, path: String, payload: ByteArray): Int {
        val src = File.createTempFile("ntfs-src-", ".bin", context.cacheDir)
        return try {
            src.writeBytes(payload)
            repo.add(path, src.absolutePath)
        } finally {
            src.delete()
        }
    }

    private suspend fun verifyDigest(context: Context, repo: VeraCryptRepo, path: String, expectedDigest: String): VerifyResult {
        val out = File.createTempFile("ntfs-dst-", ".bin", context.cacheDir)
        return try {
            val rc = repo.extract(path, out.absolutePath)
            if (rc != 0) {
                VerifyResult(rc = rc, expectedDigest = expectedDigest, actualDigest = "", matches = false)
            } else {
                val actual = sha256Hex(out.readBytes())
                VerifyResult(rc = 0, expectedDigest = expectedDigest, actualDigest = actual, matches = actual == expectedDigest)
            }
        } finally {
            out.delete()
        }
    }

    private fun randomPayload(random: Random, minBytes: Int, maxBytes: Int): ByteArray {
        val size = if (maxBytes == minBytes) minBytes else minBytes + random.nextInt(maxBytes - minBytes + 1)
        val out = ByteArray(size)
        random.nextBytes(out)
        return out
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return buildString(digest.size * 2) {
            for (b in digest) {
                append(String.format(Locale.US, "%02x", b))
            }
        }
    }

    private fun normalizeContainerName(raw: String): String {
        val safe = sanitizeFileName(raw, DEFAULT_CONTAINER_NAME)
        return if (safe.lowercase(Locale.US).endsWith(".hc")) safe else "$safe.hc"
    }

    private fun Bundle.stringArg(key: String, default: String): String {
        return getString(key)?.takeIf { it.isNotBlank() } ?: default
    }

    private fun Bundle.intArg(
        key: String,
        default: Int,
        minValue: Int = Int.MIN_VALUE,
        maxValue: Int = Int.MAX_VALUE
    ): Int {
        val parsed = getString(key)?.toIntOrNull() ?: default
        return parsed.coerceIn(minValue, maxValue)
    }

    private fun Bundle.longArgOrNull(key: String): Long? {
        return getString(key)?.toLongOrNull()
    }

    private data class ValidationConfig(
        val password: String,
        val containerName: String,
        val containerSizeBytes: Long,
        val iterations: Int,
        val minPayloadBytes: Int,
        val maxPayloadBytes: Int,
        val verifyEvery: Int,
        val overwriteEvery: Int,
        val deletePercent: Int,
        val finalVerifySampleSize: Int,
        val seed: Long?
    )

    private data class ValidationStats(
        var addAttempts: Int = 0,
        var addSuccess: Int = 0,
        var overwriteAttempts: Int = 0,
        var overwriteSuccess: Int = 0,
        var verifyAttempts: Int = 0,
        var verifySuccess: Int = 0,
        var deleteAttempts: Int = 0,
        var deleteSuccess: Int = 0,
        var finalVerifyAttempts: Int = 0,
        var finalVerifySuccess: Int = 0,
        var readOnlyListCount: Int = 0
    )

    private data class VerifyResult(
        val rc: Int,
        val expectedDigest: String,
        val actualDigest: String,
        val matches: Boolean
    )

    companion object {
        private const val DEFAULT_CONTAINER_NAME = "ntfs_validation_container.hc"
        private const val REPORT_FILE_NAME = "ntfs_stress_report.json"
        private const val DIRECTORY_FANOUT = 8
    }
}
