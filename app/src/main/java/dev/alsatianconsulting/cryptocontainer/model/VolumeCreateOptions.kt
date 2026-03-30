package dev.alsatianconsulting.cryptocontainer.model

data class VolumeCreateOptions(
    val containerUri: String,
    val sizeBytes: Long,
    val filesystem: FileSystem,
    val algorithm: Algorithm,
    val hash: Hash,
    val password: CharArray,
    val keyfileUris: List<String> = emptyList(),
    val pim: Int?,
    val hiddenSizeBytes: Long?,
    val hiddenPassword: CharArray? = null,
    val hiddenKeyfileUris: List<String> = emptyList(),
    val hiddenPim: Int? = null,
    val readOnly: Boolean = false
)

enum class FileSystem { EXFAT, NTFS, FAT }
enum class Algorithm { AES, SERPENT, TWOFISH, AES_SERPENT, SERPENT_AES, SERPENT_TWOFISH_AES, TWOFISH_SERPENT }
enum class Hash { SHA512, WHIRLPOOL }
