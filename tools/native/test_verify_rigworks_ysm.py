import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile


spec = importlib.util.spec_from_file_location("native_verifier", Path(__file__).with_name("verify_rigworks_ysm.py"))
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


class NativePackageVerifierTest(unittest.TestCase):
    def test_binary_architectures(self):
        for target, (_, kind, machine) in verifier.TARGETS.items():
            with self.subTest(target=target):
                self.assertEqual((kind, machine), verifier.binary_identity(self.header(kind, machine)))

    def test_rejects_empty_truncated_and_non_library_headers(self):
        for data in (b"", b"MZ", bytes(64), self.header("elf", 62, 2), self.header("macho", 0x01000007, 2)):
            with self.subTest(data=data[:16]):
                with self.assertRaises(ValueError):
                    verifier.binary_identity(data)

    def test_release_requires_every_target_and_correct_architecture(self):
        with tempfile.TemporaryDirectory() as directory:
            dist = Path(directory)
            self.stage(dist, "win32-x86-64")
            self.assertEqual(1, len(verifier.verify(dist, ["win32-x86-64"])["libraries"]))
            with self.assertRaisesRegex(ValueError, "missing native library"):
                verifier.verify(dist, verifier.TARGETS)
            self.stage(dist, "win32-aarch64", self.header("pe", 0x8664))
            with self.assertRaisesRegex(ValueError, "architecture"):
                verifier.verify(dist, ["win32-aarch64"])

    def test_jar_must_contain_exact_build_bytes_and_license(self):
        with tempfile.TemporaryDirectory() as directory:
            dist = Path(directory) / "dist"
            self.stage(dist, "linux-x86-64")
            jar = Path(directory) / "rigworks.jar"
            relative = "linux-x86-64/librigworks_ysm.so"
            with zipfile.ZipFile(jar, "w") as archive:
                archive.writestr(verifier.RESOURCE_ROOT + relative, (dist / relative).read_bytes())
                archive.writestr(verifier.RESOURCE_ROOT + "licenses/zstd-LICENSE", b"license")
            verifier.verify(dist, ["linux-x86-64"], jar)
            (dist / relative).write_bytes((dist / relative).read_bytes() + b"modified")
            with self.assertRaisesRegex(ValueError, "differs"):
                verifier.verify(dist, ["linux-x86-64"], jar)

    @staticmethod
    def header(kind, machine, library_kind=None):
        data = bytearray(512)
        if kind == "pe":
            data[:2] = b"MZ"
            struct.pack_into("<I", data, 0x3C, 64)
            data[64:68] = b"PE\0\0"
            struct.pack_into("<H14xHH", data, 68, machine, 240, 0x2000)
            struct.pack_into("<H", data, 88, 0x20B)
        elif kind == "elf":
            data[:6] = b"\x7fELF\x02\x01"
            struct.pack_into("<HH", data, 16, library_kind or 3, machine)
        else:
            data[:4] = b"\xcf\xfa\xed\xfe"
            struct.pack_into("<III", data, 4, machine, 0, library_kind or 6)
        return bytes(data)

    def stage(self, dist, target, data=None):
        filename, kind, machine = verifier.TARGETS[target]
        path = dist / target / filename
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data if data is not None else self.header(kind, machine))
        license_path = dist / "licenses/zstd-LICENSE"
        license_path.parent.mkdir(parents=True, exist_ok=True)
        license_path.write_bytes(b"license")


if __name__ == "__main__":
    unittest.main()
