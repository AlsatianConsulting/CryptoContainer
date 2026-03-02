package dev.alsatianconsulting.cryptocontainer.repo

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import dev.alsatianconsulting.cryptocontainer.jni.CryptoNative
import dev.alsatianconsulting.cryptocontainer.model.VcEntry
import dev.alsatianconsulting.cryptocontainer.model.VcFsInfo
import dev.alsatianconsulting.cryptocontainer.model.VcFsType
import dev.alsatianconsulting.cryptocontainer.model.VcMountWarning
import dev.alsatianconsulting.cryptocontainer.util.deleteTempFiles
import dev.alsatianconsulting.cryptocontainer.util.stageUrisToCacheFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

interface VeraCryptNativeBridge {
    fun vcOpen(
        pfd: ParcelFileDescriptor,
        password: ByteArray,
        pim: Int,
        hidden: Boolean,
        readOnly: Boolean,
        keyfilePaths: Array<String>,
        protectionPassword: ByteArray,
        protectionPim: Int
    ): Long
    fun vcGetFsType(handle: Long): Int
    fun vcIsReadOnly(handle: Long): Boolean
    fun vcGetMountWarning(handle: Long): Int
    fun vcReadFile(handle: Long, path: String, destPath: String): Int
    fun vcWriteFile(handle: Long, path: String, srcPath: String): Int
    fun vcMkdir(handle: Long, path: String): Int
    fun vcDelete(handle: Long, path: String): Int
    fun vcClose(handle: Long)
    fun vcList(handle: Long, path: String): Array<String>
}

object JniVeraCryptNativeBridge : VeraCryptNativeBridge {
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
        val fd = pfd.detachFd()
        try { pfd.close() } catch (_: Throwable) { }
        return CryptoNative.vcOpenFd(fd, password, pim, hidden, readOnly, keyfilePaths, protectionPassword, protectionPim)
    }

    override fun vcGetFsType(handle: Long): Int = CryptoNative.vcGetFsType(handle)
    override fun vcIsReadOnly(handle: Long): Boolean = CryptoNative.vcIsReadOnly(handle)
    override fun vcGetMountWarning(handle: Long): Int = CryptoNative.vcGetMountWarning(handle)
    override fun vcReadFile(handle: Long, path: String, destPath: String): Int = CryptoNative.vcReadFile(handle, path, destPath)
    override fun vcWriteFile(handle: Long, path: String, srcPath: String): Int = CryptoNative.vcWriteFile(handle, path, srcPath)
    override fun vcMkdir(handle: Long, path: String): Int = CryptoNative.vcMkdir(handle, path)
    override fun vcDelete(handle: Long, path: String): Int = CryptoNative.vcDelete(handle, path)
    override fun vcClose(handle: Long) = CryptoNative.vcClose(handle)
    override fun vcList(handle: Long, path: String): Array<String> = CryptoNative.vcList(handle, path)
}

class VeraCryptRepo(
    private val native: VeraCryptNativeBridge = JniVeraCryptNativeBridge
) : VeraCryptRepository {
    private val lock = Mutex()
    private var handle: Long = 0
    @Volatile
    private var opened: Boolean = false
    private val _entries = MutableStateFlow<List<VcEntry>>(emptyList())
    override val entries: StateFlow<List<VcEntry>> = _entries
    private val _fsInfo = MutableStateFlow<VcFsInfo?>(null)
    override val fsInfo: StateFlow<VcFsInfo?> = _fsInfo

    override fun isOpen(): Boolean = opened

    override suspend fun open(
        context: Context,
        uri: Uri,
        password: String,
        pim: Int,
        hidden: Boolean,
        readOnly: Boolean,
        keyfileUris: List<String>,
        protectionPassword: String?,
        protectionPim: Int
    ): Int {
        return withContext(Dispatchers.IO) {
            lock.withLock {
                closeInternal()
                val mode = if (readOnly) "r" else "rw"
                val pfd = try {
                    if (uri.scheme == "content") {
                        context.contentResolver.openFileDescriptor(uri, mode)
                    } else {
                        val path = uri.path?.takeIf { it.isNotBlank() } ?: return@withLock -1
                        val fileMode = if (readOnly) {
                            ParcelFileDescriptor.MODE_READ_ONLY
                        } else {
                            ParcelFileDescriptor.MODE_READ_WRITE
                        }
                        ParcelFileDescriptor.open(File(path), fileMode)
                    }
                } catch (_: Throwable) {
                    null
                } ?: return@withLock -1
                val stagedKeyfiles = stageUrisToCacheFiles(
                    context = context,
                    uriStrings = keyfileUris,
                    cacheSubdir = "vc-keyfiles",
                    fallbackPrefix = "keyfile"
                ) ?: run {
                    try { pfd.close() } catch (_: Throwable) { }
                    return@withLock VC_ERR_KEYFILE_IO
                }
                val passwordBytes = password.toByteArray()
                val protectionPasswordBytes = (protectionPassword ?: "").toByteArray()
                handle = try {
                    try {
                        native.vcOpen(
                            pfd = pfd,
                            password = passwordBytes,
                            pim = pim,
                            hidden = hidden,
                            readOnly = readOnly,
                            keyfilePaths = stagedKeyfiles.map(File::getAbsolutePath).toTypedArray(),
                            protectionPassword = protectionPasswordBytes,
                            protectionPim = protectionPim
                        )
                    } finally {
                        deleteTempFiles(stagedKeyfiles)
                    }
                } finally {
                    passwordBytes.fill(0)
                    protectionPasswordBytes.fill(0)
                }
                if (handle > 0L) {
                    val fsType = when (native.vcGetFsType(handle)) {
                        1 -> VcFsType.EXFAT
                        2 -> VcFsType.NTFS
                        3 -> VcFsType.FAT
                        else -> VcFsType.UNKNOWN
                    }
                    val ro = native.vcIsReadOnly(handle)
                    val mountWarning = when (native.vcGetMountWarning(handle)) {
                        1 -> VcMountWarning.NTFS_HIBERNATED_FALLBACK_READONLY
                        2 -> VcMountWarning.NTFS_UNCLEAN_FALLBACK_READONLY
                        else -> VcMountWarning.NONE
                    }
                    _fsInfo.value = VcFsInfo(type = fsType, readOnly = ro, mountWarning = mountWarning)
                    _entries.value = listUnsafe("")
                    opened = true
                    return@withLock 0
                }
                val rc = if (handle < 0L) handle.toInt() else -1
                handle = 0
                return@withLock rc
            }
        }
    }

    override suspend fun refresh(path: String) {
        withContext(Dispatchers.IO) {
            lock.withLock {
                if (handle == 0L) return@withLock
                _entries.value = listUnsafe(path)
            }
        }
    }

    override suspend fun list(path: String): List<VcEntry> = withContext(Dispatchers.IO) {
        lock.withLock {
            if (handle == 0L) return@withLock emptyList()
            listUnsafe(path)
        }
    }

    override suspend fun extract(path: String, dest: String): Int = withContext(Dispatchers.IO) {
        lock.withLock {
            if (handle == 0L) return@withLock -1
            try {
                native.vcReadFile(handle, path, dest)
            } catch (_: Throwable) {
                -1
            }
        }
    }

    override suspend fun add(path: String, src: String): Int = withContext(Dispatchers.IO) {
        lock.withLock {
            if (handle == 0L) return@withLock -1
            try {
                native.vcWriteFile(handle, path, src)
            } catch (_: Throwable) {
                -1
            }
        }
    }

    override suspend fun mkdir(path: String): Int = withContext(Dispatchers.IO) {
        lock.withLock {
            if (handle == 0L) return@withLock -1
            try {
                native.vcMkdir(handle, path)
            } catch (_: Throwable) {
                -1
            }
        }
    }

    override suspend fun delete(path: String): Int = withContext(Dispatchers.IO) {
        lock.withLock {
            if (handle == 0L) return@withLock -1
            try {
                native.vcDelete(handle, path)
            } catch (_: Throwable) {
                -1
            }
        }
    }

    override suspend fun close() {
        withContext(Dispatchers.IO) {
            lock.withLock { closeInternal() }
        }
    }

    private fun closeInternal() {
        if (handle != 0L) {
            try { native.vcClose(handle) } catch (_: Throwable) { }
            handle = 0
        }
        opened = false
        _entries.value = emptyList()
        _fsInfo.value = null
    }

    private fun listUnsafe(path: String): List<VcEntry> {
        val list = try {
            native.vcList(handle, path)
        } catch (_: Throwable) {
            emptyArray()
        }
        return list.map { name ->
            val isDir = name.endsWith("/")
            val clean = name.trimEnd('/')
            VcEntry(path = if (path.isEmpty()) clean else "$path/$clean", isDir = isDir)
        }
    }
}
