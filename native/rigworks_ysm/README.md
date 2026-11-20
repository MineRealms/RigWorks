# RigWorks YSM Native Converter

This directory belongs to RigWorks. It has no AssimpLoader dependency and does
not include a renderer. Import converts a YSM legacy V3 file once into the
RigWorks-owned RWYM v1 container. Reading a cached RWYM asset does not decrypt
the source YSM again.

## Build and Test

Requirements: CMake 3.21+, a C++20 compiler, and Git for the first dependency
fetch. CMake builds the decompression part of zstd 1.5.7 statically from pinned
upstream commit `ac66b19e6bd6b83238bf008eecc1298105298532`. An offline source tree
can be supplied with `-DRWYM_ZSTD_SOURCE_DIR=/path/to/zstd`.

Windows x64, with Visual Studio 2022 C++ and CMake tools:

```powershell
./tools/native/build_rigworks_ysm.ps1
# Optional offline dependency:
./tools/native/build_rigworks_ysm.ps1 -ZstdSourceDirectory H:/path/to/zstd
```

Linux or macOS:

```bash
bash tools/native/build_rigworks_ysm.sh
```

The scripts compile, run CTest against `Flandre_Scarlet.ysm`, and stage only the
shared library under `native/rigworks_ysm/dist/<JNA-platform-prefix>/` plus the
public C header and zstd BSD license. They do not overwrite the existing Zig `ysmprocessor` resources
or change Gradle packaging. Windows uses the static MSVC runtime; zstd is static
on every platform, so no separate zstd DLL/SO is required.

CTest has two tests: a structured RWYM round trip/integrity test and a shared
library C ABI test. The ABI test compares complete conversion bytes against the
direct C++ API, checks an already decoded payload produces identical bytes,
and exercises invalid arguments, length limits, checksums, output ownership,
and independent thread error state. The public header is also compiled as C.

## C ABI

`include/rigworks_ysm.h` is the entire exported interface. Use cdecl on Windows.
All lengths are `uint64_t`; do not map them to JNA's platform-dependent `NativeLong`.
The current ABI version is `0x00010000` (major in the upper 16 bits).

- `rwym_convert_legacy_v3`: encrypted file bytes -> complete RWYM v1 bytes.
- `rwym_convert_payload`: decrypted legacy payload -> complete RWYM v1 bytes.
- `rwym_validate`: checks a cached RWYM asset and its section checksums.
- `rwym_free`: releases a successful conversion result; NULL is accepted.
- `rwym_last_error`: borrowed UTF-8 error text for the current thread. Copy it
  before another conversion or validation call on that thread.
- `rwym_abi_version` / `rwym_version`: ABI gate and diagnostic version.

The caller owns the input. The library owns successful output until `rwym_free`.
Failures reset supplied output arguments to NULL and zero. Every exported
operation contains exceptions; no C++ exception or STL object crosses the ABI.
There is no global init/shutdown requirement, and separate imports may run on
separate threads. Limits are 256 MiB source file, 512 MiB decoded payload, and
1 GiB RWYM container. Format detection errors are explicit rather than silently
discarding unsupported sections.

## Platform Matrix

Build one library per target with the same source revision and test fixture:

| Target | Resource prefix | Toolchain |
|---|---|---|
| Windows x64 | `win32-x86-64` | MSVC x64, script default |
| Windows ARM64 | `win32-aarch64` | MSVC ARM64, `-Architecture ARM64` |
| Linux x64 | `linux-x86-64` | GCC/Clang on Linux x64 |
| Linux ARM64 | `linux-aarch64` | GCC/Clang on Linux ARM64 |
| macOS x64 | `darwin-x86-64` | Apple Clang, `-DCMAKE_OSX_ARCHITECTURES=x86_64` |
| macOS ARM64 | `darwin-aarch64` | Apple Clang, `-DCMAKE_OSX_ARCHITECTURES=arm64` |

Native runners should execute CTest before staging each platform binary. Cross
compilers can use `-DCMAKE_TOOLCHAIN_FILE=...`; tests require an appropriate
emulator or the target runner. Do not call a cross-compiled binary verified
until it has run there. macOS builds should use separate x64/ARM64 build
directories, not one universal binary installed under a single-architecture
prefix. This source change provides the build and staging path; Java/Gradle
integration and release CI remain separate work.

Source parsing currently supports the understood legacy payload layouts 4..15;
the real fixture exercises layout 9. Newer formats are rejected. See the source
notices for Apache-2.0-derived format observations. Include the staged
`licenses/zstd-LICENSE` when redistributing native binaries.
