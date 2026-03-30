package dev.alsatianconsulting.cryptocontainer.util

import java.nio.CharBuffer
import java.nio.charset.StandardCharsets

fun sanitizeFileName(name: String, fallback: String): String {
    val sanitized = name
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .trim()
    return if (sanitized.isBlank()) fallback else sanitized
}

/**
 * Converts a [CharArray] directly to a UTF-8 [ByteArray] without creating an intermediate
 * [String] object, so the sensitive data is not left in the immutable String pool.
 *
 * The caller is responsible for zeroing the returned array once it is no longer needed.
 */
fun charArrayToUtf8Bytes(chars: CharArray): ByteArray {
    val buf = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars))
    return ByteArray(buf.limit()).also { buf.get(it) }
}
