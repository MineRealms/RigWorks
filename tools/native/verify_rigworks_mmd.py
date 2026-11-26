#!/usr/bin/env python3
"""Verify the six MMD native libraries and exact packaged bytes; does not execute them."""

import argparse
import hashlib
import json
from pathlib import Path
import struct
from typing import Optional
import zipfile

from verify_rigworks_ysm import binary_identity


TARGETS = {
    "win32-x86-64": ("rigworks_mmd.dll", "pe", 0x8664),
    "win32-aarch64": ("rigworks_mmd.dll", "pe", 0xAA64),
    "linux-x86-64": ("librigworks_mmd.so", "elf", 62),
    "linux-aarch64": ("librigworks_mmd.so", "elf", 183),
    "darwin-x86-64": ("librigworks_mmd.dylib", "macho", 0x01000007),
    "darwin-aarch64": ("librigworks_mmd.dylib", "macho", 0x0100000C),
}
LICENSES = ("saba-LICENSE", "glm-LICENSE", "spdlog-LICENSE", "json-LICENSE", "bullet-LICENSE",
            "zig-LICENSE", "libcxx-LICENSE", "libcxxabi-LICENSE", "libunwind-LICENSE")
RESOURCE_ROOT = "natives/rigworks_mmd/"
REQUIRED_EXPORTS = {"rwmd_abi_version", "rwmd_convert", "rwmd_last_error"}


def exported_symbols(data: bytes):
    def string(offset):
        end = data.find(b"\0", offset)
        if offset < 0 or end < offset:
            raise ValueError("invalid native symbol string")
        return data[offset:end].decode("ascii")

    kind, _ = binary_identity(data)
    result = set()
    if kind == "pe":
        pe = struct.unpack_from("<I", data, 0x3C)[0]
        section_count = struct.unpack_from("<H", data, pe + 6)[0]
        optional_size = struct.unpack_from("<H", data, pe + 20)[0]
        optional = pe + 24
        sections = optional + optional_size

        def file_offset(rva):
            for index in range(section_count):
                virtual_size, start, raw_size, raw = struct.unpack_from("<IIII", data, sections + 40 * index + 8)
                if start <= rva < start + max(virtual_size, raw_size):
                    return raw + rva - start
            raise ValueError("invalid PE export RVA")

        export_rva = struct.unpack_from("<I", data, optional + 112)[0]
        export = file_offset(export_rva)
        names_count = struct.unpack_from("<I", data, export + 24)[0]
        if names_count > 65536:
            raise ValueError("excessive PE export count")
        names = file_offset(struct.unpack_from("<I", data, export + 32)[0])
        for index in range(names_count):
            result.add(string(file_offset(struct.unpack_from("<I", data, names + 4 * index)[0])))
    elif kind == "elf":
        sections = struct.unpack_from("<Q", data, 40)[0]
        section_size, section_count = struct.unpack_from("<HH", data, 58)
        if section_size < 64:
            raise ValueError("invalid ELF section table")
        for index in range(section_count):
            section = sections + index * section_size
            if struct.unpack_from("<I", data, section + 4)[0] != 11:
                continue
            offset, size = struct.unpack_from("<QQ", data, section + 24)
            strings_index = struct.unpack_from("<I", data, section + 40)[0]
            stride = struct.unpack_from("<Q", data, section + 56)[0]
            if strings_index >= section_count or stride < 24 or size // stride > 1000000:
                raise ValueError("invalid ELF dynamic symbols")
            strings = struct.unpack_from("<Q", data, sections + strings_index * section_size + 24)[0]
            for symbol in range(size // stride):
                name, info, visibility, section_index = struct.unpack_from("<IBBH", data, offset + symbol * stride)
                if info >> 4 in (1, 2) and section_index and visibility & 3 in (0, 3):
                    result.add(string(strings + name))
    else:
        commands = struct.unpack_from("<I", data, 16)[0]
        offset = 32
        for _ in range(commands):
            command, size = struct.unpack_from("<II", data, offset)
            if size < 8:
                raise ValueError("invalid Mach-O load command")
            if command == 2:
                symbols, count, strings, _ = struct.unpack_from("<IIII", data, offset + 8)
                if count > 1000000:
                    raise ValueError("excessive Mach-O symbol count")
                for index in range(count):
                    name, symbol_type, section = struct.unpack_from("<IBB", data, symbols + 16 * index)
                    if symbol_type & 1 and symbol_type & 0x0E and section:
                        result.add(string(strings + name).removeprefix("_"))
            offset += size
    return result


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
        if binary_identity(data) != (expected_format, expected_machine):
            raise ValueError("wrong binary format or architecture for " + target)
        missing = REQUIRED_EXPORTS - exported_symbols(data)
        if missing:
            raise ValueError("missing native exports for " + target + ": " + ", ".join(sorted(missing)))
        digest = hashlib.sha256(data).hexdigest()
        expected[relative] = (len(data), digest)
        libraries.append({"target": target, "file": relative, "bytes": len(data), "sha256": digest})

    for license_name in LICENSES:
        relative = "licenses/" + license_name
        path = dist / relative
        if not path.is_file() or not path.stat().st_size:
            raise ValueError("missing native dependency license: " + str(path))
        data = path.read_bytes()
        expected[relative] = (len(data), hashlib.sha256(data).hexdigest())

    if jar is not None:
        with zipfile.ZipFile(jar) as archive:
            names = archive.namelist()
            for relative, identity in expected.items():
                resource = RESOURCE_ROOT + relative
                if names.count(resource) != 1:
                    raise ValueError("expected exactly one JAR resource: " + resource)
                data = archive.read(resource)
                if (len(data), hashlib.sha256(data).hexdigest()) != identity:
                    raise ValueError("JAR resource differs from native build output: " + resource)
    return {"libraries": libraries, "jar": str(jar) if jar else None,
            "checks": "format, architecture, required C ABI exports, licenses, SHA-256 package integrity; no target execution"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dist", type=Path, default=Path("native/rigworks_mmd/dist"))
    parser.add_argument("--jar", type=Path)
    parser.add_argument("--target", action="append", choices=TARGETS,
                        help="Local subset only; omit for the mandatory six-target release check")
    parser.add_argument("--manifest", type=Path)
    args = parser.parse_args()
    try:
        result = verify(args.dist, args.target or TARGETS, args.jar)
    except (OSError, ValueError, struct.error, zipfile.BadZipFile) as error:
        parser.exit(1, "MMD native package verification failed: " + str(error) + "\n")
    report = json.dumps(result, indent=2) + "\n"
    if args.manifest:
        args.manifest.parent.mkdir(parents=True, exist_ok=True)
        args.manifest.write_text(report, encoding="utf-8")
    print(report, end="")


if __name__ == "__main__":
    main()
