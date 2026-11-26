import hashlib
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile

import verify_rigworks_mmd as verifier


class MmdNativePackageTest(unittest.TestCase):
    @staticmethod
    def elf(machine=62):
        data = bytearray(1024)
        data[:6] = b"\x7fELF\x02\x01"
        struct.pack_into("<HH", data, 16, 3, machine)
        struct.pack_into("<Q", data, 40, 128)
        struct.pack_into("<HH", data, 58, 64, 3)
        struct.pack_into("<I", data, 128 + 64 + 4, 11)
        struct.pack_into("<QQI", data, 128 + 64 + 24, 384, 24 * 4, 2)
        struct.pack_into("<Q", data, 128 + 64 + 56, 24)
        struct.pack_into("<I", data, 128 + 128 + 4, 3)
        struct.pack_into("<Q", data, 128 + 128 + 24, 768)
        strings = bytearray(b"\0")
        for index, name in enumerate(sorted(verifier.REQUIRED_EXPORTS), start=1):
            struct.pack_into("<IBBH", data, 384 + 24 * index, len(strings), 0x12, 0, 1)
            strings += name.encode("ascii") + b"\0"
        data[768:768 + len(strings)] = strings
        return bytes(data)

    def stage(self, root):
        path = root / "linux-x86-64/librigworks_mmd.so"
        path.parent.mkdir(parents=True)
        path.write_bytes(self.elf())
        for name in verifier.LICENSES:
            license_file = root / "licenses" / name
            license_file.parent.mkdir(exist_ok=True)
            license_file.write_text("test license: " + name, encoding="utf-8")
        return path

    def test_verifies_every_required_license_and_exact_jar_bytes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            binary = self.stage(root)
            jar = root / "rigworks.jar"
            with zipfile.ZipFile(jar, "w") as archive:
                for file in root.rglob("*"):
                    if file.is_file() and file != jar:
                        archive.write(file, verifier.RESOURCE_ROOT + file.relative_to(root).as_posix())
            report = verifier.verify(root, ["linux-x86-64"], jar)
            self.assertEqual(hashlib.sha256(binary.read_bytes()).hexdigest(), report["libraries"][0]["sha256"])
            binary.write_bytes(binary.read_bytes() + b"changed")
            with self.assertRaisesRegex(ValueError, "differs"):
                verifier.verify(root, ["linux-x86-64"], jar)

    def test_rejects_wrong_architecture(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.stage(root).write_bytes(self.elf(machine=183))
            with self.assertRaisesRegex(ValueError, "architecture"):
                verifier.verify(root, ["linux-x86-64"])

    def test_rejects_missing_license_and_missing_target(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.stage(root)
            (root / "licenses/glm-LICENSE").unlink()
            with self.assertRaisesRegex(ValueError, "license"):
                verifier.verify(root, ["linux-x86-64"])
            with self.assertRaisesRegex(ValueError, "missing native library"):
                verifier.verify(root, ["win32-x86-64"])

    def test_default_targets_cover_all_six_architectures(self):
        self.assertEqual(6, len(verifier.TARGETS))
        self.assertEqual({"win32", "linux", "darwin"}, {target.split("-", 1)[0] for target in verifier.TARGETS})

    def test_rejects_library_without_the_required_c_abi(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            binary = self.stage(root)
            binary.write_bytes(binary.read_bytes().replace(b"rwmd_convert", b"xxxx_convert"))
            with self.assertRaisesRegex(ValueError, "missing native exports"):
                verifier.verify(root, ["linux-x86-64"])


if __name__ == "__main__":
    unittest.main()
