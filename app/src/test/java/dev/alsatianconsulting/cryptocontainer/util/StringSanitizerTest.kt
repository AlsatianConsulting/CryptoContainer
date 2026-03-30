package dev.alsatianconsulting.cryptocontainer.util

import org.junit.Assert.assertEquals
import org.junit.Test

class StringSanitizerTest {
    @Test
    fun sanitizeFileName_replacesInvalidCharacters() {
        val value = sanitizeFileName("a:b/c*d?e\"f<g>h|i", "fallback.bin")
        assertEquals("a_b_c_d_e_f_g_h_i", value)
    }

    @Test
    fun sanitizeFileName_usesFallbackWhenBlank() {
        assertEquals("fallback.bin", sanitizeFileName("   ", "fallback.bin"))
    }
}
