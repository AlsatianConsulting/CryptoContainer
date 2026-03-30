package dev.alsatianconsulting.cryptocontainer.repo

import android.content.Context
import android.net.Uri
import dev.alsatianconsulting.cryptocontainer.model.VcEntry
import dev.alsatianconsulting.cryptocontainer.model.VcFsInfo
import kotlinx.coroutines.flow.StateFlow

const val VC_ERR_KEYFILE_IO = -1003

interface VeraCryptRepository {
    val entries: StateFlow<List<VcEntry>>
    val fsInfo: StateFlow<VcFsInfo?>
    fun isOpen(): Boolean
    /**
     * Opens the VeraCrypt volume at [uri].
     *
     * **Ownership note:** Both [password] and [protectionPassword] are zeroed (filled with
     * `'\u0000'`) before this function returns, regardless of success or failure.  Callers must
     * not reuse these arrays after the call; create a fresh [CharArray] for each invocation.
     */
    suspend fun open(
        context: Context,
        uri: Uri,
        password: CharArray,
        pim: Int = 0,
        hidden: Boolean,
        readOnly: Boolean,
        keyfileUris: List<String> = emptyList(),
        protectionPassword: CharArray? = null,
        protectionPim: Int = 0
    ): Int
    suspend fun refresh(path: String)
    suspend fun list(path: String): List<VcEntry>
    suspend fun extract(path: String, dest: String): Int
    suspend fun add(path: String, src: String): Int
    suspend fun mkdir(path: String): Int
    suspend fun delete(path: String): Int
    suspend fun close()
}
