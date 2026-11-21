#!/usr/bin/env python3
"""Check native release contents and JAR integrity; this does not execute binaries."""

import argparse
import hashlib
import json
from pathlib import Path
import struct
from typing import Optional
import zipfile


TARGETS = {
    "win32-x86-64": ("rigworks_ysm.dll", "pe", 0x8664),
    "win32-aarch64": ("rigworks_ysm.dll", "pe", 0xAA64),
    "linux-x86-64": ("librigworks_ysm.so", "elf", 62),
    "linux-aarch64": ("librigworks_ysm.so", "elf", 183),
    "darwin-x86-64": ("librigworks_ysm.dylib", "macho", 0x01000007),
    "darwin-aarch64": ("librigworks_ysm.dylib", "macho", 0x0100000C),
}
RESOURCE_ROOT = "natives/rigworks_ysm/"


def binary_identity(data: bytes):
    if len(data) < 64:
        raise ValueError("truncated or empty native library")
    if data[:2] == b"MZ":
        offset = struct.unpack_from("<I", data, 0x3C)[0]
        if offset + 24 > len(data) or data[offset:offset + 4] != b"PE\0\0":
            raise ValueError("invalid PE header")
        machine, optional_size, characteristics = struct.unpack_from("<H14xHH", data, offset + 4)
        if not characteristics & 0x2000 or optional_size < 2 or offset + 24 + optional_size > len(data):
            raise ValueError("PE image is not a complete DLL")
        if struct.unpack_from("<H", data, offset + 24)[0] != 0x20B:
            raise ValueError("PE image is not 64-bit")
        return "pe", machine
    if data[:4] == b"\x7fELF":
        if data[4:6] != b"\x02\x01":
            raise ValueError("ELF image must be 64-bit little endian")
        kind, machine = struct.unpack_from("<HH", data, 16)
        if kind != 3:
            raise ValueError("ELF image is not a shared object")
        return "elf", machine
    if data[:4] == b"\xcf\xfa\xed\xfe":
        machine, _, kind = struct.unpack_from("<III", data, 4)
        if kind != 6:
            raise ValueError("Mach-O image is not a dynamic library")
        return "macho", machine
    raise ValueError("unrecognized native library format")


def verify(dist: Path, targets, jar: Optional[Path] = None):
    expected = {}
    libraries = []
    for target in targets:
        filename, expected_format, expected_machine = TARGETS[target]
        relative = target + "/" + filename
        path = dist / relative
        if not path.is_file():
            raise ValueError("missing native library: " + str(path))
        data = path.read_bytes()
        actual_format, actual_machine = binary_identity(data)
        if (actual_format, actual_machine) != (expected_format, expected_machine):
            raise ValueError("wrong binary format or architecture for " + target)
        digest = hashlib.sha256(data).hexdigest()
        expected[relative] = (len(data), digest)
        libraries.append({"target": target, "file": relative, "bytes": len(data), "sha256": digest})

    license_path = dist / "licenses/zstd-LICENSE"
    if not license_path.is_file() or not license_path.stat().st_size:
        raise ValueError("missing zstd license: " + str(license_path))
    license_data = license_path.read_bytes()
    expected["licenses/zstd-LICENSE"] = (len(license_data), hashlib.sha256(license_data).hexdigest())
    if jar is not None:
        with zipfile.ZipFile(jar) as archive:
            for relative, identity in expected.items():
                resource = RESOURCE_ROOT + relative
                try:
                    data = archive.read(resource)
                except KeyError as error:
                    raise ValueError("missing JAR resource: " + resource) from error
                if (len(data), hashlib.sha256(data).hexdigest()) != identity:
                    raise ValueError("JAR resource differs from native build output: " + resource)
    return {"libraries": libraries, "jar": str(jar) if jar else None,
            "checks": "file format, architecture, presence, SHA-256 package integrity; no target execution"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dist", type=Path, default=Path("native/rigworks_ysm/dist"))
    parser.add_argument("--jar", type=Path)
    parser.add_argument("--target", action="append", choices=TARGETS,
                        help="Explicit local subset; omit for the mandatory six-target release gate")
    parser.add_argument("--manifest", type=Path)
    args = parser.parse_args()
    try:
        result = verify(args.dist, args.target or TARGETS, args.jar)
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        parser.exit(1, "Native package verification failed: " + str(error) + "\n")
    report = json.dumps(result, indent=2) + "\n"
    if args.manifest:
        args.manifest.parent.mkdir(parents=True, exist_ok=True)
        args.manifest.write_text(report, encoding="utf-8")
    print(report, end="")


if __name__ == "__main__":
    main()
