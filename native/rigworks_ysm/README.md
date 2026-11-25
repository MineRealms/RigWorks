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
1 GiB RWYM container at the C++ API. Java import and cached-asset loading impose
the stricter 512 MiB RWYM limit. Format detection errors are explicit rather
than silently discarding unsupported sections.

## Platform Matrix

The configured workflow uses one native runner for each release target:

| Target | Resource prefix | Native CI runner |
|---|---|---|
| Windows x64 | `win32-x86-64` | windows-2022 |
| Windows ARM64 | `win32-aarch64` | windows-11-arm |
| Linux x64 | `linux-x86-64` | ubuntu-24.04 |
| Linux ARM64 | `linux-aarch64` | ubuntu-24.04-arm |
| macOS x64 | `darwin-x86-64` | macos-15-intel |
| macOS ARM64 | `darwin-aarch64` | macos-15 |

The workflow runs CTest on each matching native runner before upload. A
collection job requires all six named outputs, stages them under the resource
prefixes above, verifies PE/ELF/Mach-O architecture headers, and uploads the
complete set with a SHA-256 manifest. Both Minecraft-version build jobs depend
on collection and verify that every platform library and the zstd license in
the RigWorks jar match the collected bytes. A failed native target blocks both
mod artifacts.
Cross-compilers may be used for local experiments, but do not replace native
runner tests.

All six native build/CTest jobs, artifact collection, and both Minecraft-version
build/package jobs passed for commit `16a971e` in
[CI run 45500656880](https://github.com/MineRealms/RigWorks/actions/runs/45500656880).
This verifies converter execution and C ABI behavior on each native runner,
plus release-library architecture and packaging. It does not establish that
Minecraft rendering and gameplay have been tested on all six platforms.

Run the same release package gate locally after staging all six targets:

```sh
python tools/native/verify_rigworks_ysm.py --manifest build/native-ysm-manifest.json
python tools/native/verify_rigworks_ysm.py --jar versions/1.20.1-forge/build/libs/rigworks-1.20.1-1.0.0-forge.jar
```

`--target win32-x86-64` checks a local subset during development. Release CI
omits `--target`, making all six libraries mandatory. This check validates
packaging and architecture; CTest on the target runner validates execution.

Source parsing currently has branches for the understood legacy payload layouts 1..15;
real external fixtures currently verify payload 1 and payload 9. The envelope is still
Legacy V3 only: V1/V2 outer containers and Modern MXC are rejected before conversion.
The verified external samples are `_Rumia.ysm` (payload 9, 3 models, 13,440 faces),
`168.ysm` (payload 1, 3 models, 11,407 faces), and `2_0lingsha(1).ysm` (payload 1,
2 models, 6,780 faces). See the source notices for Apache-2.0-derived format observations. Include the staged
`licenses/zstd-LICENSE` when redistributing native binaries.
