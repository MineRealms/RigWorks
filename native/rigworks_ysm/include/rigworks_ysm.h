/* RigWorks YSM import ABI. All returned buffers belong to this library. */
#ifndef RIGWORKS_YSM_H
#define RIGWORKS_YSM_H

#include <stdint.h>

#if defined(_WIN32)
#if defined(RIGWORKS_YSM_BUILD)
#define RWYM_API __declspec(dllexport)
#else
#define RWYM_API __declspec(dllimport)
#endif
#define RWYM_CALL __cdecl
#else
#define RWYM_API __attribute__((visibility("default")))
#define RWYM_CALL
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define RWYM_ABI_VERSION UINT32_C(0x00010000)
#define RWYM_OK INT32_C(0)
#define RWYM_ERR_ARGUMENT INT32_C(-1)
#define RWYM_ERR_FORMAT INT32_C(-2)
#define RWYM_ERR_LIMIT INT32_C(-3)
#define RWYM_ERR_MEMORY INT32_C(-4)
#define RWYM_ERR_DECODE INT32_C(-5)
#define RWYM_ERR_INTERNAL INT32_C(-6)

RWYM_API uint32_t RWYM_CALL rwym_abi_version(void);
RWYM_API const char* RWYM_CALL rwym_version(void);

/* One-time import: legacy V3 envelope -> validated RWYM v1 asset.
 * On success, *out is non-NULL and must be released with rwym_free.
 * On failure, provided outputs are reset to NULL/0; no exception crosses the ABI.
 * Source bytes remain caller-owned and are not retained after the call.
 * Lengths are fixed-width for identical JNA declarations on every platform. */
RWYM_API int32_t RWYM_CALL rwym_convert_legacy_v3(
    const uint8_t* file, uint64_t length, uint8_t** out, uint64_t* out_length);

/* Imports an already decrypted legacy payload (source versions 4..15).
 * This entry point also supports a host-side decoder fallback without a second
 * YSM decryption pass. It has the same ownership contract as the function above. */
RWYM_API int32_t RWYM_CALL rwym_convert_payload(
    const uint8_t* payload, uint64_t length, uint8_t** out, uint64_t* out_length);

/* Validates an existing RWYM asset without decrypting or converting YSM again. */
RWYM_API int32_t RWYM_CALL rwym_validate(const uint8_t* asset, uint64_t length);
RWYM_API void RWYM_CALL rwym_free(void* buffer);

/* Borrowed UTF-8 text, valid until the next conversion/validation on this thread.
 * Copy it before making another such call. Successful calls clear the error.
 * Independent threads have independent errors and may convert concurrently. */
RWYM_API const char* RWYM_CALL rwym_last_error(void);

#ifdef __cplusplus
}
#endif

#endif
