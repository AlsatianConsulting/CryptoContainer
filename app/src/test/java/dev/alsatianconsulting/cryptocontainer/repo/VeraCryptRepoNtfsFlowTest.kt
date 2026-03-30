package dev.alsatianconsulting.cryptocontainer.repo

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import dev.alsatianconsulting.cryptocontainer.model.VcMountWarning
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@RunWith(RobolectricTestRunner::class)
class VeraCryptRepoNtfsFlowTest {
    private class FakeNativeBridge(
        private val readOnly: Boolean = false,
        private val mountWarning: Int = 0
    ) : VeraCryptNativeBridge {
        private val files = ConcurrentHashMap<String, ByteArray>()
        private var opened = false
        private val handle = 7L

        override fun vcOpen(
            pfd: ParcelFileDescriptor,
            password: ByteArray,
            pim: Int,
            hidden: Boolean,
            readOnly: Boolean,
            keyfilePaths: Array<String>,
            protectionPassword: ByteArray,
            protectionPim: Int
        ): Long {
            try { pfd.close() } catch (_: Throwable) { }
            opened = true
            return handle
        }

        override fun vcGetFsType(handle: Long): Int = 2
        override fun vcIsReadOnly(handle: Long): Boolean = readOnly
        override fun vcGetMountWarning(handle: Long): Int = mountWarning
        override fun vcReadFile(handle: Long, path: String, destPath: String): Int {
            val data = files[path] ?: return -2
            File(destPath).writeBytes(data)
            return 0
        }

        override fun vcWriteFile(handle: Long, path: String, srcPath: String): Int {
            if (readOnly) return -30
            files[path] = File(srcPath).readBytes()
            return 0
        }

        override fun vcMkdir(handle: Long, path: String): Int {
            if (readOnly) return -30
            return 0
        }

        override fun vcDelete(handle: Long, path: String): Int {
            if (readOnly) return -30
            return if (files.remove(path) != null) 0 else -2
        }

        override fun vcClose(handle: Long) {
            opened = false
        }

        override fun vcList(handle: Long, path: String): Array<String> {
            val prefix = path.trim('/').let { if (it.isEmpty()) "" else "$it/" }
            val result = linkedSetOf<String>()
            for (full in files.keys) {
                if (!full.startsWith(prefix)) continue
                val rel = full.removePrefix(prefix)
                val head = rel.substringBefore('/')
                if (head.isBlank()) continue
                if (rel.contains('/')) result += "$head/" else result += head
            }
            return result.toTypedArray()
        }
    }

    @Test
    fun ntfsFlow_addOverwriteExtractDelete_roundTrips() {
        runBlocking {
            val bridge = FakeNativeBridge(readOnly = false, mountWarning = 0)
            val repo = VeraCryptRepo(bridge)
            val context: Context = RuntimeEnvironment.getApplication()
            val container = File.createTempFile("vc-test-", ".hc", context.cacheDir)

            val openRc = repo.open(
                context = context,
                uri = Uri.fromFile(container),
                password = "pw".toCharArray(),
                pim = 0,
                hidden = false,
                readOnly = false,
                protectionPassword = null,
                protectionPim = 0
            )
            assertEquals(0, openRc)

            val src1 = File.createTempFile("src1-", ".bin", context.cacheDir).apply { writeBytes("one".toByteArray()) }
            val src2 = File.createTempFile("src2-", ".bin", context.cacheDir).apply { writeBytes("two".toByteArray()) }
            val dst = File.createTempFile("dst-", ".bin", context.cacheDir)

            assertEquals(0, repo.add("docs/note.txt", src1.absolutePath))
            assertEquals(0, repo.add("docs/note.txt", src2.absolutePath))
            assertEquals(0, repo.extract("docs/note.txt", dst.absolutePath))
            assertArrayEquals("two".toByteArray(), dst.readBytes())

            val docs = repo.list("docs")
            assertTrue(docs.any { it.path == "docs/note.txt" && !it.isDir })
            assertEquals(0, repo.delete("docs/note.txt"))
            val docsAfter = repo.list("docs")
            assertFalse(docsAfter.any { it.path == "docs/note.txt" })

            repo.close()
            src1.delete()
            src2.delete()
            dst.delete()
            container.delete()
        }
    }

    @Test
    fun open_mapsNtfsSafetyFallbackWarning() {
        runBlocking {
            val bridge = FakeNativeBridge(readOnly = true, mountWarning = 1)
            val repo = VeraCryptRepo(bridge)
            val context: Context = RuntimeEnvironment.getApplication()
            val container = File.createTempFile("vc-test-", ".hc", context.cacheDir)

            val openRc = repo.open(
                context = context,
                uri = Uri.fromFile(container),
                password = "pw".toCharArray(),
                pim = 0,
                hidden = false,
                readOnly = false,
                protectionPassword = null,
                protectionPim = 0
            )
            assertEquals(0, openRc)
            val info = repo.fsInfo.value
            assertNotNull(info)
            assertTrue(info!!.readOnly)
            assertEquals(VcMountWarning.NTFS_HIBERNATED_FALLBACK_READONLY, info.mountWarning)

            repo.close()
            container.delete()
        }
    }
}
