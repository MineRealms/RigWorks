#!/usr/bin/env python3
"""Cross-compile the headless MMD converter using Zig 0.13.0; never execute foreign binaries."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

import verify_rigworks_mmd as verifier


TARGETS = {
    "win32-aarch64": "aarch64-windows-gnu",
    "linux-x86-64": "x86_64-linux-gnu.2.28",
    "linux-aarch64": "aarch64-linux-gnu.2.28",
    "darwin-x86-64": "x86_64-macos.11.0-none",
    "darwin-aarch64": "aarch64-macos.11.0-none",
}


def quote(argument):
    return '"' + str(argument).replace('\\', '/').replace('"', '\\"') + '"'


def source_identity(native):
    digest = hashlib.sha256()
    for path in sorted(native.rglob("*")):
        if path.is_file() and path.suffix in (".c", ".cpp", ".h", ".hpp", ".inl") and "dist" not in path.relative_to(native).parts:
            digest.update(path.relative_to(native).as_posix().encode("utf-8"))
            digest.update(path.read_bytes())
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--zig", default=os.environ.get("ZIG", "zig"))
    parser.add_argument("--target", choices=TARGETS, action="append")
    parser.add_argument("--optimize", choices=("-O1", "-O2", "-O3"), default="-O2")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    native = root / "native/rigworks_mmd"
    saba = native / "third_party/saba"
    bullet = native / "third_party/bullet/src"
    zig = str(Path(args.zig).resolve()) if Path(args.zig).is_file() else shutil.which(args.zig)
    if not zig:
        parser.error("Zig 0.13.0 not found; supply --zig or ZIG")
    version = subprocess.check_output([zig, "version"], text=True).strip()
    if version != "0.13.0":
        parser.error("This pinned build supports Zig 0.13.0; found " + version)
    sources = [native / "src/converter.cpp", native / "src/preflight.cpp"]
    sources += sorted((saba / "src/Saba/Base").glob("*.cpp"))
    sources += sorted((saba / "src/Saba/Model/MMD").glob("*.cpp"))
    for component in ("BulletCollision", "BulletDynamics", "LinearMath"):
        sources += sorted((bullet / component).rglob("*.cpp"))
    includes = [native / "include", saba / "src", saba / "external/glm/include",
                saba / "external/spdlog/include", saba / "external/json/include", bullet]
    selected = args.target or list(TARGETS)
    source_hash = source_identity(native)
    for prefix in selected:
        if source_identity(native) != source_hash:
            raise RuntimeError("MMD sources changed during cross-build; rerun once source edits finish")
        build = root / "build/native/rigworks_mmd/zig" / prefix
        build.mkdir(parents=True, exist_ok=True)
        filename = verifier.TARGETS[prefix][0]
        output = build / filename
        options = ["-target", TARGETS[prefix], "-std=c++17", args.optimize, "-fPIC", "-shared",
                   "-fvisibility=hidden", "-fvisibility-inlines-hidden", "-DRIGWORKS_MMD_BUILD",
                   "-ffunction-sections", "-fdata-sections", "-g0", "-s",
                   "-Wno-deprecated-declarations", "-Wno-unused-command-line-argument", "-pthread"]
        options += ["-Wl,-dead_strip"] if prefix.startswith("darwin-") else ["-Wl,--gc-sections"]
        if prefix.startswith("win32-"):
            options += ["-DUNICODE", "-D_UNICODE", "-DNOMINMAX"]
        for include in includes:
            options += ["-I", str(include)]
        options += [str(source) for source in sources]
        options += ["-o", str(output)]
        response = build / "compile.rsp"
        response.write_text("\n".join(quote(option) for option in options) + "\n", encoding="utf-8")
        print("[mmd-cross] building " + prefix + " with Zig " + version, flush=True)
        subprocess.run([zig, "c++", "@" + str(response)], cwd=root, check=True)
        if source_identity(native) != source_hash:
            raise RuntimeError("MMD sources changed while compiling " + prefix + "; rerun once source edits finish")
        destination = native / "dist" / prefix / filename
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(output, destination)
        report = verifier.verify(native / "dist", [prefix])
        report["sourceSha256"] = source_hash
        (build / "manifest.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(report, indent=2), flush=True)
    print("[mmd-cross] Cross-compilation complete; foreign target execution remains unverified.", flush=True)


if __name__ == "__main__":
    main()
