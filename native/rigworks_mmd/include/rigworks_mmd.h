#ifndef RIGWORKS_MMD_H
#define RIGWORKS_MMD_H

#include <stddef.h>
#include <stdint.h>

#if defined(_WIN32)
#if defined(RIGWORKS_MMD_BUILD)
#define RWMD_API __declspec(dllexport)
#else
#define RWMD_API __declspec(dllimport)
#endif
#else
#define RWMD_API __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

/* UTF-8 paths and JSON. Conversion owns no caller buffers after returning. */
RWMD_API uint32_t rwmd_abi_version(void);
RWMD_API int rwmd_convert(const void* model_bytes, size_t model_size,
                         const char* source_path, const char* output_directory,
                         const char* options_json);
/* Thread-local diagnostic, valid until the next conversion on the same thread. */
RWMD_API const char* rwmd_last_error(void);

#ifdef __cplusplus
}
#endif
#endif
