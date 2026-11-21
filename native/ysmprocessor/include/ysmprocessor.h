/*
 * Originally derived from:
 * Source: https://github.com/YesSteveModel/YesSteveModel (Apache-2.0)
 *        and https://github.com/stevewongv/YesSteveModel (Apache-2.0)
 *
 * Licensed under the Apache License, Version 2.0.
 * You may obtain a copy of the License at:
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Modified by MineRealms for RigWorks.
 * Changes: Cleanroom reimplementation / package adaptation / API changes for in-mod YSM decode.
 */
#ifndef YSP_PROCESSOR_H
#define YSP_PROCESSOR_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#if defined(_WIN32)
#define YSP_EXPORT __declspec(dllexport)
#else
#define YSP_EXPORT __attribute__((visibility("default")))
#endif

/* ysp_legacy_v3_decode / ysp_zstd_decompress return codes. */
#define YSP_OK                        0
#define YSP_WASHED_ZSTD_FRAME         2   /* out = washed zstd frame, caller must also zstd-decompress */
#define YSP_ERR_INVALID_ARGUMENT     (-1)
#define YSP_ERR_FILE_TOO_SHORT       (-2)
#define YSP_ERR_BAD_HEADER           (-3)
#define YSP_ERR_HASH_MISMATCH        (-4)
#define YSP_ERR_UNSUPPORTED_CRYPTO   (-5)
#define YSP_ERR_DECODE               (-6)
#define YSP_ERR_OUT_OF_MEMORY        (-7)
#define YSP_ERR_ZSTD                 (-8)

YSP_EXPORT int32_t ysp_init(void);
YSP_EXPORT void ysp_shutdown(void);
YSP_EXPORT const char* ysp_version(void);
YSP_EXPORT int32_t ysp_legacy_v3_decode(const uint8_t* file, int32_t len, uint8_t** out, int32_t* outLen);
YSP_EXPORT int32_t ysp_zstd_decompress(const uint8_t* src, int32_t len, uint8_t** out, int32_t* outLen);
YSP_EXPORT void ysp_free(void* ptr);
YSP_EXPORT const char* ysp_last_error(void);

#ifdef __cplusplus
}
#endif

#endif
