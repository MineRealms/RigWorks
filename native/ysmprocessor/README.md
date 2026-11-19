<!--
Originally derived from:
Source: https://github.com/YesSteveModel/YesSteveModel (Apache-2.0)
       and https://github.com/stevewongv/YesSteveModel (Apache-2.0)

Licensed under the Apache License, Version 2.0.
You may obtain a copy of the License at:
https://www.apache.org/licenses/LICENSE-2.0

Modified by MineRealms for RigWorks.
Changes: Cleanroom reimplementation / package adaptation / API changes for in-mod YSM decode.
-->
# ysmprocessor — YSM legacy V3 decode native backend

`ysmprocessor` is a cleanroom reimplementation of the YesSteveModel legacy V3
file decoder. The source is written in Zig and exports a pure C ABI so it can be
loaded directly by JNA. The mandatory YSM/OpenYSM attribution header is kept at
the top of every derived source file (Zig has no block comments, so the header is
expressed as line comments there).

## C ABI

See `include/ysmprocessor.h`. All functions are `cdecl`. Buffers returned through
`uint8_t** out` are allocated with `malloc` and **must** be released with
`ysp_free`.

| function | purpose |
| --- | --- |
| `ysp_init` | Initialise the library; returns `YSP_OK` (0). |
| `ysp_shutdown` | Release library-global resources (currently a no-op). |
| `ysp_version` | Static NUL-terminated version string. |
| `ysp_legacy_v3_decode` | Full header parse, hash check, modified XChaCha20, MT19937 XOR, padding strip, YSM zstd wash and zstd decompression. |
| `ysp_zstd_decompress` | Decompress a standard zstd frame (used for the escape hatch below). |
| `ysp_free` | Free any buffer returned by this library. |
| `ysp_last_error` | Thread-local message describing the most recent failure. |

### Return codes

`0` = success, `2` = washed zstd frame returned, negative = error (see the
header for the full table).

### zstd strategy and escape hatch

Zig 0.13 ships a pure-Zig zstd decoder in `std.compress.zstd`, so the normal
path performs the **entire** decode natively and returns `YSP_OK` with the final
decompressed payload.

The contract nevertheless permits a lighter native build that stops after the
YSM zstd wash. To support that mode, `ysp_legacy_v3_decode` never fails silently
on zstd: if the bundled decoder rejects the frame, it returns
`YSP_WASHED_ZSTD_FRAME` (`2`) with the *washed standard zstd frame* in `out`.
The Java caller then decompresses that frame itself (using the bundled pure-Java
zstd, or `ysp_zstd_decompress`). This is deliberate and documented here so a
future native build that drops Zig's zstd decoder stays compatible.

## Algorithm (file layout)

```
[BOM EF BB BF]["YSGP" legacy header text][0x00]
[LE int32 crypto == 3]
[modified XChaCha20 ciphertext ..........]
[key 32][iv 24][LE uint64 CityHash64(file[0..len-8], 0x9E5599DB80C67C29)]
```

* `modified XChaCha20`: HChaCha20 subkey derivation, variable round count
  `10 * (hash % 3) + 10` derived from `CityHash64(key||iv, 0xA62B1A2C43842BC3)`,
  and a state re-key after each `(hash & 0x3F | 0x40) << 6` byte chunk using the
  CityHash64 of the *plaintext* chunk.
* `MT19937-64` XOR with seed `CityHash64(key||iv, 0xD017CBBA7B5D3581)`.
* `uint16 n & 0x3FF` padding bytes, then the washed zstd frame.
* zstd wash: verify magic `0xFD2FB528`, clear FHD bit 2, remap block types
  `0->2, 1->1, 2->3, 3->0` and XOR the compressed size with `0xD4E9`.

## Building

`tools/native/build_ysmprocessor.bat` / `.sh` cross-compile four targets with
Zig and copy the results into
`src/main/resources/native/ysmprocessor/<JNA platform>/`. They are used
automatically by `./gradlew <node>:test -PbuildNative`.
