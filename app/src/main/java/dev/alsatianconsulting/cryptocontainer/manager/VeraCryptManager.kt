package dev.alsatianconsulting.cryptocontainer.manager

import android.content.Context
import android.net.Uri
import android.util.Log
import dev.alsatianconsulting.cryptocontainer.jni.CryptoNative
import dev.alsatianconsulting.cryptocontainer.model.FileSystem
import dev.alsatianconsulting.cryptocontainer.model.VolumeCreateOptions
import dev.alsatianconsulting.cryptocontainer.repo.VC_ERR_KEYFILE_IO
import dev.alsatianconsulting.cryptocontainer.util.deleteTempFiles
import dev.alsatianconsulting.cryptocontainer.util.stageUrisToCacheFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

sealed interface MountMode { object Full : MountMode; object Limited : MountMode }

data class VolumeState(
    val uri: String,
    val mounted: Boolean,
    val mountPoint: String?, // null in limited mode
    val hidden: Boolean,
    val mode: MountMode,
    val readOnly: Boolean,
    val message: String? = null
)

class VeraCryptManager {
    private val lock = Mutex()
    private val _volumeState = MutableStateFlow<VolumeState?>(null)
    val volumeState: StateFlow<VolumeState?> = _volumeState
    private val preferredHiddenByUri = ConcurrentHashMap<String, Boolean>()

    fun preferredHiddenFor(volumeUri: String): Boolean? = preferredHiddenByUri[volumeUri]

    suspend fun markMounted(
        volumeUri: String,
        hidden: Boolean,
        readOnly: Boolean,
        mode: MountMode = MountMode.Limited,
        mountPoint: String? = null,
        message: String? = "Mounted"
    ) {
        lock.withLock {
            _volumeState.value = VolumeState(
                uri = volumeUri,
                mounted = true,
                mountPoint = mountPoint,
                hidden = hidden,
                mode = mode,
                readOnly = readOnly,
                message = message
            )
            preferredHiddenByUri[volumeUri] = hidden
        }
    }

    suspend fun create(context: Context, options: VolumeCreateOptions): Int {
        return withContext(Dispatchers.IO) {
            lock.withLock {
                try {
                    val passwordBytes = options.password.toByteArray()
                    val hiddenPasswordBytes = (options.hiddenPassword ?: "").toByteArray()
                    val outerKeyfileUris = options.keyfileUris.distinct()
                    val hiddenKeyfileUris = if ((options.hiddenSizeBytes ?: 0L) > 0L) {
                        options.hiddenKeyfileUris.ifEmpty { outerKeyfileUris }.distinct()
                    } else {
                        emptyList()
                    }
                    val outerKeyfiles = stageUrisToCacheFiles(
                        context = context,
                        uriStrings = outerKeyfileUris,
                        cacheSubdir = "vc-keyfiles",
                        fallbackPrefix = "outer-keyfile"
                    ) ?: return@withLock VC_ERR_KEYFILE_IO
                    val hiddenKeyfiles = if (hiddenKeyfileUris == outerKeyfileUris) {
                        outerKeyfiles
                    } else {
                        stageUrisToCacheFiles(
                            context = context,
                            uriStrings = hiddenKeyfileUris,
                            cacheSubdir = "vc-keyfiles",
                            fallbackPrefix = "hidden-keyfile"
                        ) ?: run {
                            deleteTempFiles(outerKeyfiles)
                            return@withLock VC_ERR_KEYFILE_IO
                        }
                    }
                    try {
                        val parsed = Uri.parse(options.containerUri)
                        try {
                            if (parsed.scheme == "content") {
                                val pfd = context.contentResolver.openFileDescriptor(parsed, "rwt") ?: return@withLock -1
                                val fd = pfd.detachFd()
                                try { pfd.close() } catch (_: Throwable) { }
                                CryptoNative.vcCreateVolumeFd(
                                    fd = fd,
                                    sizeBytes = options.sizeBytes,
                                    filesystem = when (options.filesystem) {
                                        FileSystem.EXFAT -> "exfat"
                                        FileSystem.NTFS -> "ntfs"
                                        FileSystem.FAT -> "fat"
                                    },
                                    algorithm = options.algorithm.name.lowercase(),
                                    hash = options.hash.name.lowercase(),
                                    pim = options.pim ?: 0,
                                    password = passwordBytes,
                                    keyfilePaths = outerKeyfiles.map { it.absolutePath }.toTypedArray(),
                                    hiddenSizeBytes = options.hiddenSizeBytes ?: 0,
                                    hiddenPassword = hiddenPasswordBytes,
                                    hiddenKeyfilePaths = hiddenKeyfiles.map { it.absolutePath }.toTypedArray(),
                                    hiddenPim = options.hiddenPim ?: 0,
                                    readOnly = options.readOnly
                                )
                            } else {
                                CryptoNative.vcCreateVolume(
                                    containerPath = options.containerUri,
                                    sizeBytes = options.sizeBytes,
                                    filesystem = when (options.filesystem) {
                                        FileSystem.EXFAT -> "exfat"
                                        FileSystem.NTFS -> "ntfs"
                                        FileSystem.FAT -> "fat"
                                    },
                                    algorithm = options.algorithm.name.lowercase(),
                                    hash = options.hash.name.lowercase(),
                                    pim = options.pim ?: 0,
                                    password = passwordBytes,
                                    keyfilePaths = outerKeyfiles.map { it.absolutePath }.toTypedArray(),
                                    hiddenSizeBytes = options.hiddenSizeBytes ?: 0,
                                    hiddenPassword = hiddenPasswordBytes,
                                    hiddenKeyfilePaths = hiddenKeyfiles.map { it.absolutePath }.toTypedArray(),
                                    hiddenPim = options.hiddenPim ?: 0,
                                    readOnly = options.readOnly
                                )
                            }
                        } finally {
                            deleteTempFiles(outerKeyfiles)
                            if (hiddenKeyfiles !== outerKeyfiles) {
                                deleteTempFiles(hiddenKeyfiles)
                            }
                        }
                    } finally {
                        passwordBytes.fill(0)
                        hiddenPasswordBytes.fill(0)
                    }
                } catch (t: Throwable) {
                    Log.e("VeraCryptManager", "vcCreateVolume failed", t)
                    -1
                }
            }
        }
    }

    suspend fun unmount() {
        lock.withLock {
            _volumeState.value = null
        }
    }
}
