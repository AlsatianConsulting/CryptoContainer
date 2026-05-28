/*
 * vc_x86_64_stubs.c — link-time stubs for x86_64 Android builds.
 *
 * VeraCrypt's crypto code has many SIMD/AES-NI accelerated paths that
 * are selected at compile time based on CRYPTOPP_BOOL_X64 / CRYPTOPP_BOOL_X86.
 * Those guards don't respect CRYPTOPP_DISABLE_* flags, so the linker needs
 * definitions even though the call sites are never reached at runtime because:
 *   - CRYPTOPP_DISABLE_ASM   → Camellia/Twofish/SHA-2 fall back to pure C
 *   - CRYPTOPP_DISABLE_SSE2  → kuznyechik/serpent SSE2 paths disabled
 *   - CRYPTOPP_DISABLE_AESNI → HasAESNI() always returns 0
 *   - CRYPTOPP_DISABLE_SHANI → HasSHA256() detection disabled
 *   - blake2s_has_* below    → return 0, so no SSE path is selected
 *   - TC_AES_HW_CPU not set  → IsAesHwCpuSupported() returns 0 (Cipher.cpp)
 */

#if defined(__x86_64__) || defined(_M_X64)

/* AES-NI hardware acceleration.
 * Unreachable: IsAesHwCpuSupported() always returns 0 when TC_AES_HW_CPU
 * is not defined (see Volume/Cipher.cpp). */
void aes_hw_cpu_encrypt(const void *ks, void *data)               { (void)ks; (void)data; }
void aes_hw_cpu_decrypt(const void *ks, void *data)               { (void)ks; (void)data; }
void aes_hw_cpu_encrypt_32_blocks(const void *ks, void *data)     { (void)ks; (void)data; }
void aes_hw_cpu_decrypt_32_blocks(const void *ks, void *data)     { (void)ks; (void)data; }

/* blake2s SSE capability probes — return 0 to force the pure-C
 * blake2s_compress_ref path in blake2s_init(). */
int blake2s_has_sse2()  { return 0; }
int blake2s_has_ssse3() { return 0; }
int blake2s_has_sse41() { return 0; }

/* blake2s SSE compression functions.
 * Unreachable because blake2s_has_* all return 0 above. */
void blake2s_compress_sse2 (void *S, const void *block) { (void)S; (void)block; }
void blake2s_compress_ssse3(void *S, const void *block) { (void)S; (void)block; }
void blake2s_compress_sse41(void *S, const void *block) { (void)S; (void)block; }

/* SM4 AES-NI acceleration.
 * Unreachable: CRYPTOPP_DISABLE_AESNI ensures HasAESNI() returns 0, so
 * sm4_set_key() never assigns these function pointers. */
void sm4_set_key_aesni(const void *key, void *kds)
    { (void)key; (void)kds; }
void sm4_encrypt_block_aesni(void *out, const void *in, void *kds)
    { (void)out; (void)in; (void)kds; }
void sm4_decrypt_block_aesni(void *out, const void *in, void *kds)
    { (void)out; (void)in; (void)kds; }
void sm4_encrypt_blocks_aesni(void *out, const void *in, unsigned long n, void *kds)
    { (void)out; (void)in; (void)n; (void)kds; }
void sm4_decrypt_blocks_aesni(void *out, const void *in, unsigned long n, void *kds)
    { (void)out; (void)in; (void)n; (void)kds; }

/* Intel SHA-NI detection.
 * Returns 0 so cpu.c keeps g_hasSHA256 = 0 and falls back to software SHA. */
int TrySHA256(void) { return 0; }

/* Argon2 SSE2/AVX2 segment fill.
 * Unreachable: CRYPTOPP_DISABLE_SSE2 makes HasSSE2()/HasSAVX2() return 0.
 * Returns 0 (ARGON2_OK) as a safe placeholder. */
int fill_segment_sse2(const void *instance, void *position)
    { (void)instance; (void)position; return 0; }
int fill_segment_avx2(const void *instance, void *position)
    { (void)instance; (void)position; return 0; }

#endif /* __x86_64__ */
