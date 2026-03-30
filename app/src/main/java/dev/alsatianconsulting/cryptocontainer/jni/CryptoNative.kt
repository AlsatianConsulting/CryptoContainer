package dev.alsatianconsulting.cryptocontainer.jni

object CryptoNative {
    init {
        System.loadLibrary("cryptocore")
    }

    external fun vcOpen(
        containerPath: String,
        password: ByteArray,
        pim: Int,
        hidden: Boolean,
        keyfilePaths: Array<String>,
        protectionPassword: ByteArray,
        protectionPim: Int
    ): Long
    external fun vcOpenFd(
        fd: Int,
        password: ByteArray,
        pim: Int,
        hidden: Boolean,
        readOnly: Boolean,
        keyfilePaths: Array<String>,
        protectionPassword: ByteArray,
        protectionPim: Int
    ): Long
    external fun vcClose(handle: Long)
    external fun vcList(handle: Long, path: String): Array<String>
    external fun vcReadFile(handle: Long, path: String, destPath: String): Int
    external fun vcWriteFile(handle: Long, path: String, srcPath: String): Int
    external fun vcMkdir(handle: Long, path: String): Int
    external fun vcDelete(handle: Long, path: String): Int
    external fun vcGetFsType(handle: Long): Int
    external fun vcIsReadOnly(handle: Long): Boolean
    external fun vcGetMountWarning(handle: Long): Int
    external fun vcRequestCancel()
    external fun vcClearCancel()
    external fun vcCreateVolume(
        containerPath: String,
        sizeBytes: Long,
        filesystem: String,
        algorithm: String,
        hash: String,
        pim: Int,
        password: ByteArray,
        keyfilePaths: Array<String>,
        hiddenSizeBytes: Long,
        hiddenPassword: ByteArray,
        hiddenKeyfilePaths: Array<String>,
        hiddenPim: Int,
        readOnly: Boolean
    ): Int
    external fun vcCreateVolumeFd(
        fd: Int,
        sizeBytes: Long,
        filesystem: String,
        algorithm: String,
        hash: String,
        pim: Int,
        password: ByteArray,
        keyfilePaths: Array<String>,
        hiddenSizeBytes: Long,
        hiddenPassword: ByteArray,
        hiddenKeyfilePaths: Array<String>,
        hiddenPim: Int,
        readOnly: Boolean
    ): Int
}
