# Native build integration

## Latest Follow-up Complete

The five foreign targets now contain the sparse-zero entry fix for empty Morph
targets. Full six-target verification refreshed `dist/manifest.json`; export,
architecture, notice and SHA-256 checks passed. The Python verifier suite passed
5/5 again. No native build process remains active.

Independent review identified corrupt-cache recovery and late texture-budget
checks. Both are fixed in the Java cache with focused regressions. Installation
also preserves compatible saved edits and uses deferred scene retirement.

## Cross-build Follow-up Complete

All six real platform binaries are staged under `dist/` and pass architecture,
required export and notice checks. The five foreign targets were rebuilt from
the same stable source snapshot using Zig 0.13.0. CI host execution gates remain
enabled. No placeholder binary is used and foreign-host execution is unverified.

The public ABI is `0x00010000`. CMake uses C++17, including its UTF-8 filesystem
path behavior, and compiles only the checked-in headless Saba/Bullet subset.
The converter library contains no runtime dependency on a system Bullet DLL.

Windows local build:

```powershell
./tools/native/build_rigworks_mmd.ps1
```

Linux/macOS local build:

```sh
./tools/native/build_rigworks_mmd.sh
```

Both scripts configure, build, test and stage the host target by default.
PowerShell supports `-Architecture ARM64` and Bash supports the standard CMake
toolchain environment through `RWMD_CMAKE_OPTIONS`. Output is staged under
`native/rigworks_mmd/dist/<JNA platform prefix>/`.

The CI matrix builds and executes CTest on Windows/Linux/macOS x64 and ARM64.
The collector validates all six binary architectures and license files before
either Minecraft release is packaged. `verify_rigworks_mmd.py` also compares
the final standalone/bundled JAR resources against those exact build outputs.
Presence and architecture checks are not execution evidence on another host.

To rebuild the five foreign targets from Windows with the pinned Zig compiler:

```powershell
python tools/native/cross_build_rigworks_mmd.py --zig C:/path/to/zig-0.13.0/zig.exe
```

The cross-build uses Linux glibc 2.28 and macOS 11 deployment baselines, removes
debug symbols and dead code, verifies required ABI exports and stages each
successful target. `--target linux-aarch64` selects one target for development.
Rebuild Windows x64 with the normal CMake script before checking the full set.

`tests/mmd_build_*` exercises the public C ABI. CMake registers the authored
model/animation fixture suite as `mmd.converter`.

## Local Verification

Windows x64 was built with MSVC 19.44.35216.0 using the normal build script.
CTest passed 2/2 (`mmd.c_abi` and `mmd.converter`). The former checks C header
compatibility, ABI version, invalid-input diagnostics and thread-local errors;
the latter is the authored model/animation/IK fixture suite.

The package verifier parses PE/ELF/Mach-O exports, requires all three public
C ABI symbols, and checks all nine notices. Its Python tests passed 5/5. Full
six-target verification generated `dist/manifest.json` with exact SHA-256s.

| Target | Compiler | Bytes | Target execution |
| --- | --- | --- | --- |
| win32-x86-64 | MSVC | 1522688 | CTest 2/2 passed |
| win32-aarch64 | Zig 0.13.0 | 1441792 | Unverified |
| linux-x86-64 | Zig 0.13.0, glibc 2.28 | 2005576 | Unverified |
| linux-aarch64 | Zig 0.13.0, glibc 2.28 | 1758528 | Unverified |
| darwin-x86-64 | Zig 0.13.0, macOS 11 | 1826872 | Unverified |
| darwin-aarch64 | Zig 0.13.0, macOS 11 | 1736136 | Unverified |

The cross-build rejects source edits during its run. Its five per-target
manifests under `build/native/rigworks_mmd/zig/` share source SHA-256
`ed648777693fc8e0f01d4a3c498fd2bc2a1df94bd31aef5a2ff643f2e518241d`.
No native build process remains active for this task.
