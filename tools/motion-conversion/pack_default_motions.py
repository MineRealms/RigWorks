#!/usr/bin/env python3
"""Pack solved source poses into the pure-Java DEFAULT motion library."""

import argparse
import gzip
import hashlib
import io
import json
import math
from pathlib import Path
import struct


ROOT = Path(__file__).resolve().parents[2]
MAGIC = 0x52574D44
VERSION = 1
STRIDE = 7
MAX_COMPONENTS = 4_194_304


def string(output, value):
    data = value.encode("utf-8")
    if not data or len(data) > 256:
        raise ValueError("Invalid motion string: " + repr(value))
    output.write(struct.pack(">H", len(data)))
    output.write(data)


def pose(sample, indices, previous=None):
    values = []
    for role, index in enumerate(indices):
        position = sample["worldPositions"][index * 3:index * 3 + 3]
        rotation = sample["worldRotations"][index * 4:index * 4 + 4]
        if len(position) != 3 or len(rotation) != 4 or not all(map(math.isfinite, position + rotation)):
            raise ValueError("Nonfinite or incomplete solved pose")
        length = math.sqrt(sum(value * value for value in rotation))
        if length < 1e-8:
            raise ValueError("Zero source quaternion")
        rotation = [value / length for value in rotation]
        if previous is not None and sum(rotation[c] * previous[role * STRIDE + 3 + c] for c in range(4)) < 0:
            rotation = [-value for value in rotation]
        values.extend(position + rotation)
    return values


def pack(samples_path, roles_path, output_directory):
    source_bytes = samples_path.read_bytes()
    source = json.loads(source_bytes)
    configuration = json.loads(roles_path.read_text(encoding="utf-8"))
    roles = configuration["sourceRoles"]
    names = {bone["name"]: index for index, bone in enumerate(source["bones"])}
    indices = [names[node] for node in roles.values()]
    fps = source["fps"]
    if source["version"] != 1 or source["basis"] != "mmd-y-up" or fps != 60:
        raise ValueError("Expected solved, native Y-up 60 Hz source samples")
    if source["coordinateSystem"].get("handedness") != "right":
        raise ValueError("Unexpected source handedness")
    if not 1 <= len(roles) <= 64 or not 1 <= len(source["clips"]) <= 256:
        raise ValueError("Motion library count exceeds runtime limits")

    output = io.BytesIO()
    output.write(struct.pack(">IIHHH", MAGIC, VERSION, fps, len(roles), len(source["clips"])))
    for role in roles:
        string(output, role)
    bind = pose(source["bind"], indices)
    output.write(struct.pack(">" + "f" * len(bind), *bind))
    entries = []
    components = len(bind)
    verification_roles = [role for role in ("hips", "head", "left_forearm", "right_forearm", "left_foot", "right_foot", "left_toe", "right_toe") if role in roles]
    role_indices = {role: index for index, role in enumerate(roles)}
    for identifier, clip in source["clips"].items():
        duration = clip["duration"]
        frames = clip["frames"]
        mode = clip["samplingMode"]
        if mode not in ("loop", "once", "static") or not math.isfinite(duration) or duration < 0 or duration > 600:
            raise ValueError("Invalid motion metadata: " + identifier)
        expected_count = math.ceil(duration * fps - 1e-8) + 1
        if len(frames) != expected_count or mode == "static" and (len(frames) != 1 or duration != 0):
            raise ValueError("Unexpected sample count: " + identifier)
        if max(clip.get("absoluteSeekError", 0), clip.get("absoluteRotationSeekError", 0)) > 1e-4:
            raise ValueError("Unstable solved source: " + identifier)
        components += len(frames) * len(bind)
        if components > MAX_COMPONENTS:
            raise ValueError("Motion library exceeds shared-array memory limit")
        for value in (identifier, clip["sourceVmd"], mode, clip["sourceMask"]):
            string(output, value)
        output.write(struct.pack(">dI", duration, len(frames)))
        previous = None
        check_indices = sorted({0, len(frames) // 2, max(0, len(frames) - (2 if mode == "loop" else 1))})
        checks = []
        for index, frame in enumerate(frames):
            expected_time = min(index / fps, duration)
            if abs(frame["time"] - expected_time) > 1e-7:
                raise ValueError("Nonuniform source times: " + identifier)
            values = pose(frame, indices, previous)
            output.write(struct.pack(">" + "f" * len(values), *values))
            previous = values
            if index in check_indices:
                checks.append({"time": frame["time"], "roles": {
                    role: values[role_indices[role] * STRIDE:role_indices[role] * STRIDE + STRIDE]
                    for role in verification_roles}})
        entries.append({"id": identifier, "source": clip["sourceVmd"], "sourceSha256": source["sourceHashes"][clip["sourceVmd"]],
                        "sourceStartFrame": clip["sourceStartFrame"], "sourceEndFrame": clip["sourceEndFrame"],
                        "sourceMaxFrame": clip["sourceMaxFrame"], "mode": mode, "mask": clip["sourceMask"],
                        "duration": duration, "frames": len(frames), "checks": checks})

    raw = output.getvalue()
    compressed = io.BytesIO()
    with gzip.GzipFile(fileobj=compressed, mode="wb", filename="", mtime=0, compresslevel=9) as archive:
        archive.write(raw)
    data = compressed.getvalue()
    manifest = {"version": VERSION, "format": "RWMD big-endian float32 poses in deterministic gzip",
                "resource": "library.rwmd.gz", "sha256": hashlib.sha256(data).hexdigest(),
                "uncompressedSha256": hashlib.sha256(raw).hexdigest(), "sourceSamplesSha256": hashlib.sha256(source_bytes).hexdigest(),
                "basis": source["basis"], "coordinateSystem": source["coordinateSystem"], "fps": fps,
                "roles": roles, "engineUsedOffline": source["engine"], "nativeRuntimeRequired": False,
                "sourcePmxSha256": source["sourceHashes"]["pmx"], "sourceManifestSha256": source["manifestSha256"],
                "clipCount": len(entries), "poseArrayBytes": components * 4, "compressedBytes": len(data),
                "uncompressedBytes": len(raw), "bind": {role: bind[index * STRIDE:index * STRIDE + STRIDE]
                                                      for index, role in enumerate(roles)}, "clips": entries}
    output_directory.mkdir(parents=True, exist_ok=True)
    (output_directory / "library.rwmd.gz").write_bytes(data)
    (output_directory / "manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=True) + "\n", encoding="utf-8")
    print(json.dumps({"clips": len(entries), "roles": len(roles), "poseArrayBytes": components * 4,
                      "compressedBytes": len(data), "sha256": manifest["sha256"]}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--samples", type=Path, default=ROOT / "build/vmd-motion-preview/source-samples.json")
    parser.add_argument("--roles", type=Path, default=ROOT / "tools/motion-conversion/retarget_preview.json")
    parser.add_argument("--output", type=Path, default=ROOT / "src/main/resources/assets/rigworks/motions/default")
    args = parser.parse_args()
    pack(args.samples, args.roles, args.output)


if __name__ == "__main__":
    main()
