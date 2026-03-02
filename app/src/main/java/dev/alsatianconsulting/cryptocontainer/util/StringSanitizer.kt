package dev.alsatianconsulting.cryptocontainer.util

fun sanitizeFileName(name: String, fallback: String): String {
    val sanitized = name
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .trim()
    return if (sanitized.isBlank()) fallback else sanitized
}
