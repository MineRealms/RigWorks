# DEFAULT Motion Data

This library contains all 25 previously exported, solved VMD motion excerpts.
Their 60 Hz samples retain 25 semantic body, toe, and hand-reference roles in
the source engine's right-handed Y-up coordinates and native MMD units.
No source bone names are used for target avatar channel lookup.

The shipped data is a gzip-compressed RWMD v1 stream with bounded UTF-8 names,
clip metadata and float32 world positions/quaternions. Quaternion signs are
aligned between samples, and runtime interpolation uses shortest-path slerp.
All avatars share 607,600 bytes of pose arrays; each avatar reuses its own
small output poses. The source PMX/VMD files and reference engine binaries
are not required or loaded by Java playback.

`manifest.json` preserves source and engine hashes, exact clip ranges and
masks, binary hashes, and numerical reference samples. It describes the
exported excerpts, not every VMD file present in the original FullPack.
Source VMD interpolation, append bones, and source IK were evaluated before
these samples were exported; target avatar calibration and contact IK are
separate runtime responsibilities.

Rebuild from the existing ignored source samples:

```text
python tools/motion-conversion/pack_default_motions.py
```

The original source motion and character creators retain their rights in
their works. The reference engine identifiers in the manifest record the
offline provenance; no original engine code or native library is included.
