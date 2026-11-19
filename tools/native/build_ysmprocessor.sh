#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SRC="$ROOT/native/ysmprocessor/src/ysmprocessor.zig"
DIST="$ROOT/native/ysmprocessor/dist"
RES="$ROOT/src/main/resources/native/ysmprocessor"

ZIG=""
if [ -x "$ROOT/.reference/zig" ] && [ -d "$ROOT/.reference/lib/std" ]; then
    ZIG="$ROOT/.reference/zig"
elif [ -x "/h/MinecraftMods/FabriKNative/.tools/zig-windows-x86_64-0.13.0/zig.exe" ]; then
    ZIG="/h/MinecraftMods/FabriKNative/.tools/zig-windows-x86_64-0.13.0/zig.exe"
else
    ZIG="$(command -v zig || true)"
fi
if [ -z "$ZIG" ]; then
    echo "[ysmprocessor] Zig toolchain not found" >&2
    exit 1
fi
echo "Using Zig: $ZIG"

mkdir -p "$DIST"

build() {
    local target="$1" out="$2" arch="$3" destname="$4"
    echo "[ysmprocessor] $target -> $out"
    "$ZIG" build-lib "$SRC" -target "$target" -dynamic -lc -OReleaseFast \
        "-femit-bin=$DIST/$out"
    mkdir -p "$RES/$arch"
    cp -f "$DIST/$out" "$RES/$arch/$destname"
}

build x86_64-windows-gnu        ysmprocessor-windows-x86_64.dll    win32-x86-64   ysmprocessor.dll
build x86_64-linux-gnu          ysmprocessor-linux-x86_64.so       linux-x86-64   libysmprocessor.so
build x86_64-macos.11.0.0-none  ysmprocessor-macos-x86_64.dylib    darwin-x86-64  libysmprocessor.dylib
build aarch64-macos.11.0.0-none ysmprocessor-macos-aarch64.dylib   darwin-aarch64 libysmprocessor.dylib

find "$DIST" -maxdepth 1 -type f \( -name '*.o' -o -name '*.obj' -o -name '*.pdb' -o -name '*.lib' \) -delete
echo "YSM native build complete."
