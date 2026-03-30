package dev.alsatianconsulting.cryptocontainer.model

data class VcEntry(
    val path: String,
    val isDir: Boolean,
    val size: Long = 0L
)
