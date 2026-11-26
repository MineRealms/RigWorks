# RigWorks MMD Converter

This C++17 module converts standard PMD/PMX models and optional VMD motions into
ordinary skinned GLB and generic RigWorks metadata. It is loaded only during
import. Character playback and exported packages do not use the converter.
There is no renderer or CSM JNI dependency here.

- [C ABI and metadata contract](CONTRACT.md)
- [Build commands and platform verification](BUILD_NOTES.md)
- [Pinned dependency sources and notices](third_party/README.md)
- [Java installation pipeline](../../docs/development/MMD_IMPORT_PIPELINE.md)
- [User import guide](../../docs/guides/MMD_IMPORT.md)

`src/preflight.cpp` checks supported formats, lengths and source index ranges
before Saba parsing. `src/converter.cpp` solves and samples motion, authors
standard assets and reports unsupported semantics. `src/glb.hpp` writes aligned
GLB buffers, skin data and sparse Morph accessors. `tests/` uses original small
fixtures rather than redistributing character assets.

The adapter samples destination-key VMD Bezier curves and resets state between
clips. It adapts the pinned Saba evaluator's curve ownership and append
translation conventions without modifying the vendored upstream sources.
Numerical tests cover both adaptations, IK endpoint/toggle timing and joint
local frames. Runtime physics is not baked into the clips.

Native coordinates are Y up, model forward +Z, feet Y=0 and uniform target
height. The Java adapter accounts for AssimpLoader's subsequent Z reflection
when writing the profile and Rig/physics sidecars. Keep these calibrations
together when extending the converter.

Default builds use the checked-in platform libraries. Rebuild the host library
with `tools/native/build_rigworks_mmd.ps1` or `.sh`, or pass `-PbuildMmdNative`
to Gradle. The tools and all required source dependencies live in RigWorks;
AssimpLoader contains no MMD-specific native code.
