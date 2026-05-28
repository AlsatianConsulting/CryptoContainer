package dev.alsatianconsulting.cryptocontainer.validation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.alsatianconsulting.cryptocontainer.jni.CryptoNative
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

/**
 * Exercises every encryption algorithm, every KDF hash, and every filesystem format.
 * Algorithm sweep  : 16 algorithms x (sha512, exfat)
 * Hash sweep       :  5 hashes     x (aes,   exfat)
 * Filesystem sweep :  3 filesystems x (aes, sha512)
 * All containers use PIM=1 for speed. Results -> files/validation/encryption_matrix_report.json
 */
@RunWith(AndroidJUnit4::class)
class EncryptionMatrixTest {

    private val allAlgorithms = listOf(
        "aes", "serpent", "twofish", "camellia", "kuznyechik",
        "aes_twofish", "aes_twofish_serpent",
        "aes_serpent", "serpent_aes",
        "serpent_twofish_aes", "twofish_serpent",
        "camellia_kuznyechik", "camellia_serpent",
        "kuznyechik_aes", "kuznyechik_twofish", "kuznyechik_serpent_camellia"
    )
    private val allHashes      = listOf("sha512", "whirlpool", "sha256", "blake2s", "streebog")
    private val allFilesystems = listOf("exfat", "ntfs", "fat")

    private val defaultAlgo = "aes"
    private val defaultHash = "sha512"
    private val defaultFs   = "exfat"

    private val password    = "TestPassword123!".toByteArray()
    private val pim         = 1
    private val sizeBytes   = 8L * 1024 * 1024
    private val testPayload = "CryptoContainer matrix test -- round-trip integrity check\n"

    private fun runCase(
        dir: File, algo: String, hash: String, fs: String,
        results: JSONArray, label: String
    ) {
        val t0        = System.currentTimeMillis()
        val safeLabel = label.replace(Regex("[^a-z0-9_]"), "_")
        val container = File(dir, "matrix_${safeLabel}.vc")
        val srcFile   = File(dir, "src_${safeLabel}.txt").also { it.writeText(testPayload) }
        val dstFile   = File(dir, "dst_${safeLabel}.txt")
        val obj = JSONObject().put("label", label).put("algorithm", algo)
                              .put("hash", hash).put("filesystem", fs)

        fun fail(reason: String) {
            container.delete(); srcFile.delete(); dstFile.delete()
            results.put(obj.put("status", "FAIL").put("reason", reason)
                           .put("elapsedMs", System.currentTimeMillis() - t0))
        }

        val createRc = try {
            CryptoNative.vcCreateVolume(
                containerPath = container.absolutePath, sizeBytes = sizeBytes,
                filesystem = fs, algorithm = algo, hash = hash, pim = pim,
                password = password.clone(), keyfilePaths = emptyArray(),
                hiddenSizeBytes = 0L, hiddenPassword = ByteArray(0),
                hiddenKeyfilePaths = emptyArray(), hiddenPim = 0, readOnly = false
            )
        } catch (t: Throwable) { fail("create threw: ${t.message}"); return }
        if (createRc != 0) { fail("create rc=$createRc"); return }

        val handle = try {
            CryptoNative.vcOpen(
                containerPath = container.absolutePath, password = password.clone(), pim = pim,
                hidden = false, keyfilePaths = emptyArray(),
                protectionPassword = ByteArray(0), protectionPim = 0
            )
        } catch (t: Throwable) { container.delete(); srcFile.delete(); fail("open threw: ${t.message}"); return }
        if (handle <= 0L) { container.delete(); srcFile.delete(); fail("open=$handle"); return }

        try {
            val wrc = CryptoNative.vcWriteFile(handle, "/test.txt", srcFile.absolutePath)
            if (wrc != 0) { fail("write rc=$wrc"); return }
            dstFile.delete()
            val rrc = CryptoNative.vcReadFile(handle, "/test.txt", dstFile.absolutePath)
            if (rrc != 0) { fail("read rc=$rrc"); return }
            val got = dstFile.readText()
            if (got != testPayload) { fail("mismatch: ${got.take(60)}"); return }
            val listing = CryptoNative.vcList(handle, "")
            if (listing.none { it.trimEnd('/') == "test.txt" }) {
                fail("test.txt absent from listing: ${listing.toList()}"); return
            }
            results.put(obj.put("status", "PASS").put("elapsedMs", System.currentTimeMillis() - t0))
        } finally {
            try { CryptoNative.vcClose(handle) } catch (_: Throwable) {}
            container.delete(); srcFile.delete(); dstFile.delete()
        }
    }

    @Test
    fun encryptionMatrix() {
        val ctx     = InstrumentationRegistry.getInstrumentation().targetContext
        val dir     = ctx.filesDir.resolve("validation").apply { mkdirs() }
        val report  = dir.resolve("encryption_matrix_report.json")
        val results = JSONArray()

        for (algo in allAlgorithms) runCase(dir, algo, defaultHash, defaultFs, results, "algo_$algo")
        for (hash in allHashes)     runCase(dir, defaultAlgo, hash, defaultFs, results, "hash_$hash")
        for (fs in allFilesystems)  runCase(dir, defaultAlgo, defaultHash, fs, results, "fs_$fs")

        var passed = 0; var failed = 0
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            if (r.getString("status") == "PASS") passed++ else failed++
            val lbl    = r.getString("label")
            val ms     = r.optLong("elapsedMs", -1)
            val reason = r.optString("reason", "")
            if (r.getString("status") == "FAIL") println("  FAIL  $lbl -- $reason")
            else println("  PASS  $lbl (${ms}ms)")
        }
        println("=== EncryptionMatrixTest: $passed/$${results.length()} passed ===")

        val root = JSONObject()
            .put("startedAt", Instant.now().toString())
            .put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} SDK${android.os.Build.VERSION.SDK_INT}")
            .put("passed", passed).put("failed", failed).put("total", results.length())
            .put("results", results)
        report.writeText(root.toString(2))

        assert(failed == 0) { "$failed test(s) failed" }
    }
}
