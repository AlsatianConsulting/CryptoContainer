#pragma once

// Minimal Windows type shims for non-Windows builds.
#include <stdlib.h>
#include <stdint.h>
// Pre-define endianness for Android cross-compilation from macOS.
// On macOS's case-insensitive filesystem, #include <endian.h> resolves to
// Common/Endian.h (self-referential); the guard blocks re-entry and BYTE_ORDER
// is never set. Android/ARM64 is always little-endian, so hard-code it here.
#if defined(__ANDROID__) && !defined(BYTE_ORDER)
# ifndef LITTLE_ENDIAN
#  define LITTLE_ENDIAN 1234
# endif
# ifndef BIG_ENDIAN
#  define BIG_ENDIAN 4321
# endif
# define BYTE_ORDER LITTLE_ENDIAN
#endif

#ifndef __GNUC_PREREQ
#define __GNUC_PREREQ(maj, min) ((__GNUC__ > (maj)) || (__GNUC__ == (maj) && __GNUC_MINOR__ >= (min)))
#endif

#ifndef bswap16
#define bswap16(x) __builtin_bswap16(x)
#endif
#ifndef bswap32
#define bswap32(x) __builtin_bswap32(x)
#endif
#ifndef bswap64
#define bswap64(x) __builtin_bswap64(x)
#endif
#ifndef __int8
#define __int8 char
#endif
#ifndef __int16
#define __int16 short
#endif
#ifndef __int32
#define __int32 int
#endif
#ifndef __int64
#define __int64 long long
#endif

#ifndef __unaligned
#define __unaligned
#endif

typedef void* HANDLE;
typedef void* HWND;
#ifndef VC_COMPAT_SKIP_FATFS_TYPES
typedef uint32_t DWORD;
typedef uint16_t WORD;
#endif
typedef int32_t LONG;
#ifndef VC_COMPAT_SKIP_FATFS_TYPES
typedef uint8_t BYTE;
#endif
typedef uint32_t ULONG;
typedef uint64_t ULONGLONG;
#ifndef _WINDEF_H
#define _WINDEF_H 1
#endif
#ifndef BOOL
typedef int BOOL;
#endif

#ifndef TRUE
#define TRUE 1
#endif
#ifndef FALSE
#define FALSE 0
#endif

#ifndef TC_EVENT
typedef int TC_EVENT;
#endif

#ifndef MAX_PATH
#define MAX_PATH 260
#endif

#ifndef _wcsicmp
// Do NOT include <wchar.h> here: the force-included vc_compat.h is processed before
// any Platform/ headers. On macOS case-insensitive fs, system wchar.h → <time.h> →
// Platform/time.h → PlatformBase.h → <locale> needs mbstate_t that system wchar.h
// hasn't defined yet (we're still inside its <time.h> include). Forward-declare
// wcscasecmp directly instead; wchar_t is a C++ builtin keyword, no header needed.
#ifdef __cplusplus
extern "C" int wcscasecmp(const wchar_t* s1, const wchar_t* s2);
#endif
#define _wcsicmp wcscasecmp
#endif

#ifndef VirtualLock
#define VirtualLock(p, s) (1)
#endif
#ifndef VirtualUnlock
#define VirtualUnlock(p, s) (1)
#endif

static inline int HasRDSEED(void) { return 0; }
static inline int HasRDRAND(void) { return 0; }
