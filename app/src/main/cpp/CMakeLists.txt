cmake_minimum_required(VERSION 3.22.1)
project(cryptocore)

# Paths to third-party sources (checked out as submodules)
set(VC_ROOT "${CMAKE_SOURCE_DIR}/../../../../third_party/veracrypt/src")
set(FUSE_ROOT "${CMAKE_SOURCE_DIR}/../../../../third_party/libfuse")
set(EXFAT_ROOT "${CMAKE_SOURCE_DIR}/../../../../third_party/exfat")
set(NTFS_ROOT "${CMAKE_SOURCE_DIR}/../../../../third_party/ntfs-3g")
set(FATFS_ROOT "${CMAKE_SOURCE_DIR}/../../../../third_party/fatfs")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# VeraCrypt core source set (reduced; will expand as port progresses)
file(GLOB VC_COMMON_CPP ${VC_ROOT}/Common/*.cpp)
file(GLOB VC_COMMON_C ${VC_ROOT}/Common/*.c)
file(GLOB VC_CRYPTO_CPP ${VC_ROOT}/Crypto/*.cpp)
file(GLOB VC_CRYPTO_C ${VC_ROOT}/Crypto/*.c)
file(GLOB VC_ARGON2_C ${VC_ROOT}/Crypto/Argon2/src/*.c)
file(GLOB VC_VOLUME_CPP ${VC_ROOT}/Volume/*.cpp)
file(GLOB VC_PLATFORM_CPP ${VC_ROOT}/Platform/*.cpp)
file(GLOB VC_PLATFORM_UNIX_CPP ${VC_ROOT}/Platform/Unix/*.cpp)
set(VC_CORE_CPP
    ${VC_ROOT}/Core/RandomNumberGenerator.cpp
)
file(GLOB EXFAT_C ${EXFAT_ROOT}/libexfat/*.c)
file(GLOB EXFAT_MKFS_C ${EXFAT_ROOT}/mkfs/*.c)
file(GLOB NTFS3G_C ${NTFS_ROOT}/libntfs-3g/*.c)
set(FATFS_C
    ${FATFS_ROOT}/ff.c
    ${FATFS_ROOT}/ffsystem.c
    ${FATFS_ROOT}/ffunicode.c
)
set(NTFS_MKNTFS_C
    ${NTFS_ROOT}/ntfsprogs/attrdef.c
    ${NTFS_ROOT}/ntfsprogs/boot.c
    ${NTFS_ROOT}/ntfsprogs/mkntfs.c
    ${NTFS_ROOT}/ntfsprogs/sd.c
    ${NTFS_ROOT}/ntfsprogs/utils.c
)

# Drop Windows/GUI/smartcard-specific sources
list(FILTER VC_COMMON_CPP EXCLUDE REGEX ".*(BaseCom|BootEncryption|CommandAPDU|EMV|PCSC|SCard|SecurityToken|Token|TLVParser|IccDataExtractor|ResponseAPDU).*")
list(FILTER VC_COMMON_C EXCLUDE REGEX ".*(Dlgcode|Cmdline|Registry|Tests|Xml|Format|Fat|Language|Progress|Dictionary|Combo|Inflate|EncryptionThreadPool|Keyfiles|Password|Volumes|Cache|Random).*")

# Filter Crypto sources that are x86-only
list(FILTER VC_CRYPTO_CPP EXCLUDE REGEX ".*(aesni|simd).*")
list(FILTER VC_CRYPTO_C EXCLUDE REGEX ".*(AesSmall|CamelliaSmall|Sha2Intel|blake2s_SSE|blake2s_SSSE3|blake2s_SSE41|chacha-xmm|rdrand|kuznyechik_simd).*")

# Explicit removes to avoid accidental inclusion
list(REMOVE_ITEM VC_COMMON_C
    ${VC_ROOT}/Common/Random.c
    ${VC_ROOT}/Common/Cache.c
    ${VC_ROOT}/Common/EncryptionThreadPool.c
)
list(REMOVE_ITEM VC_CRYPTO_C
    ${VC_ROOT}/Crypto/AesSmall.c
    ${VC_ROOT}/Crypto/CamelliaSmall.c
    ${VC_ROOT}/Crypto/Sha2Intel.c
    ${VC_ROOT}/Crypto/Sha2Small.c
    ${VC_ROOT}/Crypto/blake2s-ref.c
    ${VC_ROOT}/Crypto/blake2s_SSE2.c
    ${VC_ROOT}/Crypto/blake2s_SSSE3.c
    ${VC_ROOT}/Crypto/blake2s_SSE41.c
    ${VC_ROOT}/Crypto/chacha-xmm.c
    ${VC_ROOT}/Crypto/rdrand.c
    ${VC_ROOT}/Crypto/kuznyechik_simd.c
    ${VC_ROOT}/Crypto/wolfCrypt.c
    ${VC_ROOT}/Crypto/Serpent.c
)

# Drop Windows-specific volume sources
list(FILTER VC_VOLUME_CPP EXCLUDE REGEX ".*(Win32|Win).*")
list(FILTER VC_VOLUME_CPP EXCLUDE REGEX ".*WolfCrypt.*")
list(FILTER VC_VOLUME_CPP EXCLUDE REGEX ".*Keyfile.*")

# Drop platform tests
list(FILTER VC_PLATFORM_CPP EXCLUDE REGEX ".*PlatformTest.*")

list(FILTER VC_ARGON2_C EXCLUDE REGEX ".*(opt_|avx|sse).*")
list(REMOVE_ITEM VC_ARGON2_C
    ${VC_ROOT}/Crypto/Argon2/src/opt_avx2.c
    ${VC_ROOT}/Crypto/Argon2/src/opt_sse2.c
)
list(APPEND VC_ARGON2_C
    ${VC_ROOT}/Crypto/Argon2/src/blake2/blake2b.c
)

# NTFS-3G: drop platform-specific I/O backends (we provide custom ops)
list(FILTER NTFS3G_C EXCLUDE REGEX ".*(unix_io|win32_io)\\.c$")
list(FILTER EXFAT_MKFS_C EXCLUDE REGEX ".*/main\\.c$")

add_library(ntfs3g STATIC
    ${NTFS3G_C}
)

set_target_properties(ntfs3g PROPERTIES
    POSITION_INDEPENDENT_CODE ON
)

target_include_directories(ntfs3g PRIVATE
    ${NTFS_ROOT}/include/ntfs-3g
)

target_compile_definitions(ntfs3g PRIVATE
    HAVE_CONFIG_H
    NO_NTFS_DEVICE_DEFAULT_IO_OPS
)

add_library(ntfsmkfs STATIC
    ${NTFS_MKNTFS_C}
)

set_target_properties(ntfsmkfs PROPERTIES
    POSITION_INDEPENDENT_CODE ON
)

target_include_directories(ntfsmkfs PRIVATE
    ${NTFS_ROOT}/include/ntfs-3g
    ${NTFS_ROOT}/ntfsprogs
)

target_compile_definitions(ntfsmkfs PRIVATE
    HAVE_CONFIG_H
    NO_NTFS_DEVICE_DEFAULT_IO_OPS
    VERSION=\"android\"
)

set_source_files_properties(${NTFS_ROOT}/ntfsprogs/mkntfs.c PROPERTIES
    COMPILE_DEFINITIONS "main=ntfs_mkntfs_main"
)

add_library(cryptocore SHARED
    cryptocore.cpp
    ${VC_COMMON_CPP}
    ${VC_COMMON_C}
    ${VC_CRYPTO_CPP}
    ${VC_CRYPTO_C}
    ${VC_ARGON2_C}
    ${VC_VOLUME_CPP}
    ${VC_PLATFORM_CPP}
    ${VC_PLATFORM_UNIX_CPP}
    ${VC_CORE_CPP}
    ${EXFAT_C}
    ${EXFAT_MKFS_C}
    ${FATFS_C}
    exfat_mkfs_bridge.c
    vc_stubs.cpp
)

find_library(log-lib log)

# TODO: replace with built static libs for exfat/ntfs when available

target_include_directories(cryptocore PRIVATE
    ${VC_ROOT}
    ${VC_ROOT}/Crypto
    ${VC_ROOT}/Crypto/Argon2/include
    ${VC_ROOT}/Common
    ${VC_ROOT}/Volume
    ${VC_ROOT}/Core
    ${VC_ROOT}/Platform
    ${VC_ROOT}/Platform/Unix
    ${EXFAT_ROOT}/libexfat
    ${EXFAT_ROOT}/mkfs
    ${NTFS_ROOT}/include
    ${FATFS_ROOT}
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_compile_definitions(cryptocore PRIVATE
    TC_UNIX
    TC_NO_GUI
    TC_NO_WX
    __ANDROID__
    TC_LINUX
    _FILE_OFFSET_BITS=64
    UNICODE
    _UNICODE
    ARGON2_NO_THREADS
    TC_EXTERNAL_AES_HW
    PACKAGE=\"exfat\"
    FS_FATFS_WINDOW_ALIGNMENT=4
)

target_compile_options(cryptocore PRIVATE
    -fexceptions
    -frtti
    -include ${CMAKE_CURRENT_SOURCE_DIR}/vc_compat.h
    $<$<STREQUAL:${ANDROID_ABI},arm64-v8a>:-march=armv8-a+crypto>
)

# Only NTFS sources and the native bridge need these flags to see ntfs config.
set(NTFS3G_DEFS HAVE_CONFIG_H;NO_NTFS_DEVICE_DEFAULT_IO_OPS)
set_source_files_properties(cryptocore.cpp PROPERTIES COMPILE_DEFINITIONS "${NTFS3G_DEFS};VC_COMPAT_SKIP_FATFS_TYPES")
set_source_files_properties(${FATFS_C} PROPERTIES COMPILE_DEFINITIONS "VC_COMPAT_SKIP_FATFS_TYPES")

target_link_libraries(cryptocore
    ${log-lib}
    ntfs3g
    ntfsmkfs
)
