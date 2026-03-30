package dev.alsatianconsulting.cryptocontainer.provider

import android.content.Context
import android.net.Uri
import android.content.pm.ProviderInfo
import android.provider.DocumentsContract.Root
import dev.alsatianconsulting.cryptocontainer.MountController
import dev.alsatianconsulting.cryptocontainer.manager.MountMode
import dev.alsatianconsulting.cryptocontainer.manager.VeraCryptManager
import dev.alsatianconsulting.cryptocontainer.model.VcEntry
import dev.alsatianconsulting.cryptocontainer.model.VcFsInfo
import dev.alsatianconsulting.cryptocontainer.model.VcFsType
import dev.alsatianconsulting.cryptocontainer.model.VcMountWarning
import dev.alsatianconsulting.cryptocontainer.repo.VeraCryptRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap

@RunWith(RobolectricTestRunner::class)
class VolumeProviderNtfsFlowTest {
    private class FakeRepository(
        readOnly: Boolean
    ) : VeraCryptRepository {
        private val files = ConcurrentHashMap<String, ByteArray>()
        private var opened = true
        private val _entries = MutableStateFlow<List<VcEntry>>(emptyList())
        override val entries: StateFlow<List<VcEntry>> = _entries
        private val _fsInfo = MutableStateFlow<VcFsInfo?>(
            VcFsInfo(type = VcFsType.NTFS, readOnly = readOnly, mountWarning = VcMountWarning.NONE)
        )
        override val fsInfo: StateFlow<VcFsInfo?> = _fsInfo

        fun read(path: String): ByteArray? = files[path]

        override fun isOpen(): Boolean = opened

        override suspend fun open(
            context: Context,
            uri: Uri,
            password: CharArray,
            pim: Int,
            hidden: Boolean,
            readOnly: Boolean,
            keyfileUris: List<String>,
            protectionPassword: CharArray?,
            protectionPim: Int
        ): Int = 0

        override suspend fun refresh(path: String) {
            _entries.value = list(path)
        }

        override suspend fun list(path: String): List<VcEntry> {
            val prefix = path.trim('/').let { if (it.isEmpty()) "" else "$it/" }
            val out = linkedSetOf<VcEntry>()
            for (full in files.keys) {
                if (!full.startsWith(prefix)) continue
                val rel = full.removePrefix(prefix)
                val first = rel.substringBefore('/')
                if (first.isBlank()) continue
                if (rel.contains('/')) {
                    val p = if (path.isBlank()) first else "$path/$first"
                    out += VcEntry(path = p, isDir = true)
                } else {
                    val p = if (path.isBlank()) first else "$path/$first"
                    out += VcEntry(path = p, isDir = false, size = files[full]?.size?.toLong() ?: 0L)
                }
            }
            return out.toList()
        }

        override suspend fun extract(path: String, dest: String): Int {
            val data = files[path] ?: return -2
            File(dest).writeBytes(data)
            return 0
        }

        override suspend fun add(path: String, src: String): Int {
            if (_fsInfo.value?.readOnly == true) return -30
            files[path] = File(src).readBytes()
            return 0
        }

        override suspend fun mkdir(path: String): Int {
            if (_fsInfo.value?.readOnly == true) return -30
            return 0
        }

        override suspend fun delete(path: String): Int {
            if (_fsInfo.value?.readOnly == true) return -30
            return if (files.remove(path) != null) 0 else -2
        }

        override suspend fun close() {
            opened = false
            _entries.value = emptyList()
            _fsInfo.value = null
        }
    }

    @After
    fun tearDown() {
        MountController.setTestOverrides(null, null)
    }

    @Test
    fun queryRoots_readWriteMount_supportsCreate() {
        runBlocking {
            val repo = FakeRepository(readOnly = false)
            val manager = VeraCryptManager()
            manager.markMounted(
                volumeUri = "content://test/container.hc",
                hidden = false,
                readOnly = false,
                mode = MountMode.Full,
                message = "Mounted"
            )
            MountController.setTestOverrides(manager, repo)

            val provider = buildProvider()

            val cursor = provider.queryRoots(null)
            assertTrue(cursor.moveToFirst())
            val flags = cursor.getInt(cursor.getColumnIndexOrThrow(Root.COLUMN_FLAGS))
            assertTrue(flags and Root.FLAG_SUPPORTS_CREATE != 0)
        }
    }

    @Test
    fun provider_createDelete_roundTrip() {
        runBlocking {
            val repo = FakeRepository(readOnly = false)
            val manager = VeraCryptManager()
            manager.markMounted(
                volumeUri = "content://test/container.hc",
                hidden = false,
                readOnly = false,
                mode = MountMode.Full,
                message = "Mounted"
            )
            MountController.setTestOverrides(manager, repo)

            val provider = buildProvider()

            val docId = provider.createDocument("root", "application/octet-stream", "note.txt")
            assertEquals("note.txt", docId)
            assertNotNull(repo.read("note.txt"))

            provider.deleteDocument(docId)
            assertFalse(repo.read("note.txt") != null)
        }
    }

    @Test(expected = FileNotFoundException::class)
    fun provider_readOnlyMount_blocksWriteOpen() {
        runBlocking {
            val repo = FakeRepository(readOnly = true)
            val manager = VeraCryptManager()
            manager.markMounted(
                volumeUri = "content://test/container.hc",
                hidden = false,
                readOnly = true,
                mode = MountMode.Full,
                message = "Mounted"
            )
            MountController.setTestOverrides(manager, repo)

            val provider = buildProvider()

            val rootCursor = provider.queryRoots(null)
            assertTrue(rootCursor.moveToFirst())
            val flags = rootCursor.getInt(rootCursor.getColumnIndexOrThrow(Root.COLUMN_FLAGS))
            assertFalse(flags and Root.FLAG_SUPPORTS_CREATE != 0)

            val docId = "note.txt"
            assertNotNull(docId)
            provider.openDocument(docId, "w", null)
        }
    }

    private fun buildProvider(): VolumeProvider {
        val context = RuntimeEnvironment.getApplication()
        val provider = VolumeProvider()
        val info = ProviderInfo().apply {
            authority = "dev.alsatianconsulting.cryptocontainer.volumeprovider"
            exported = true
            grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"
            writePermission = "android.permission.MANAGE_DOCUMENTS"
            applicationInfo = context.applicationInfo
        }
        provider.attachInfo(context, info)
        return provider
    }
}
