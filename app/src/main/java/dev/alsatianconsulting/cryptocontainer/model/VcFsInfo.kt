package dev.alsatianconsulting.cryptocontainer.model

enum class VcFsType { UNKNOWN, EXFAT, NTFS, FAT }

enum class VcMountWarning {
    NONE,
    NTFS_HIBERNATED_FALLBACK_READONLY,
    NTFS_UNCLEAN_FALLBACK_READONLY
}

data class VcFsInfo(
    val type: VcFsType,
    val readOnly: Boolean,
    val mountWarning: VcMountWarning = VcMountWarning.NONE
)
