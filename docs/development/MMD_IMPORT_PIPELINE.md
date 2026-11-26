# MMD Import Pipeline

RigWorks owns the import-only C++ converter and the Java installation adapter.
AssimpLoader remains the ordinary model, animation and rendering engine. No CSM
JNI bridge, PMD/PMX parser or VMD evaluator runs during character playback.

User workflow and supported source semantics:
[MMD Import](../guides/MMD_IMPORT.md).

## Data Flow

```text
PMD/PMX + relative textures + optional VMD + import settings
  -> bounded native conversion in a private staging directory
  -> ordinary skinned GLB + metadata
  -> captured textures + generic profile/rig/physics/equipment sidecars
  -> content-addressed cache
  -> ordinary AssimpLoader loading and RigWorks semantic validation
  -> transactional character installation
  -> ordinary Workbench .rig.zip export/import
```

`AvatarImportContext` retains the selected file's real path, so dependencies
resolve against its directory rather than the current working directory.
Absolute references and paths or symlinks escaping that directory are rejected.
The existing import worker performs conversion away from the render thread.

`MmdNative` loads `rigworks_mmd` lazily through JNA and checks ABI version
`0x00010000`. The file-output contract is in
[`CONTRACT.md`](../../native/rigworks_mmd/CONTRACT.md). The native implementation
uses a pinned, vendored headless Saba subset with Bullet linked statically;
neither a viewer nor OpenGL is built. The C boundary catches exceptions and
serializes access to the evaluator's shared state.

## Solving And Coordinates

VMD destination-key Bezier curves, IK enable switches, append transforms and
supported Morph effects are evaluated once and sampled into standard FK and
vertex Morph channels. The adapter corrects two pinned evaluator conventions:
curve ownership and the translation value consumed by append transforms. State
is reset between clips. Constant tracks collapse and unchanged channels are
omitted; empty sparse Morph targets include a zero entry compatible with Assimp.

The native GLB uses Y up and faces +Z, with uniform neutral height 1.85 and feet
at Y=0. AssimpLoader reflects GLB Z during loading, so the resulting scene faces
-Z. The generated profile pairs yaw 180 with head-look forward -Z. Java reflects
Rig/physics metadata exactly once, including joint interval direction. Changing
only the profile yaw or only the head vector breaks that calibration.

Japanese/common English humanoid names map to ordinary RigWorks roles. The arm
neutral correction is derived from each model's bind geometry. Name discovery
does not prove correct calibration for arbitrary skeletons; the Workbench can
edit the result. An identity `mmd_neutral` clip keeps the ordinary modifier and
physics path active when the source contains no VMD.

Generic physics remains live at runtime; the import solver does not bake it into
VMD clips. Original IK/append constraints are not installed again at runtime.
SDEF/QDEF use LBS approximations. Unsupported Morph, material and constraint
semantics are diagnosed; see the user guide for limits.

## Cache And Installation

`MmdAssetCache` hashes the conversion revision, source bytes, selected directory,
settings and motion contents. It captures texture dependencies and rechecks
their hashes on reimport. A missing texture becoming available invalidates the
previous result. Motion inputs are copied privately and rehashed before native
evaluation. Converted bundles contain relative dependencies, not source paths.

The model input is limited to 256 MiB, combined model/motion input to 512 MiB,
each texture to 64 MiB and the converted bundle to 512 MiB. Texture sizes count
toward the budget before copying; a bounded copy detects size changes. Animation
sampling has a shared eight-million bone/Morph sample budget. These limits apply
at import, with no converter allocation or source scan during playback.

Cache directories are published after structural checks and sidecar generation.
A corrupt existing directory is backed up and replaced; failed publication
attempts to restore it. The installer then loads the real engine scene, checks
roles/clips/weights/matrices and constructs generic Rig/physics definitions before
changing the selected character. Failures log the exception and preserve the
previous selection, scene and configuration. Compatible reimports preserve saved
visual edits and the user's motion mode. Removing an imported character uses
the existing deferred scene retirement mechanism.

Export uses the ordinary Workbench package path. A `.rig.zip` contains the model,
captured textures, profile, Rig, physics and equipment definitions. Reopening it
requires neither the original PMD/PMX/VMD files nor the converter library.

## Verification

| Evidence | Result |
| --- | --- |
| Windows x64 native CTest | 2/2 passed: authored PMD/PMX, weights, inverse binds, Morphs, Bezier, IK/switches, grants, clip reset, joint frames and invalid input |
| Six native binary formats | Architecture, three ABI exports, notices and SHA-256 verified |
| Native verifier regression | 5/5 passed |
| Java import regressions | Cache identity/repair/budget, path confinement, calibration and failure cleanup covered |
| Real engine/package checks | Authored fixture plus local CSM PMX/stand VMD; actual AssimpLoader load and portable ZIP round trip passed |
| Minecraft 1.20.1 Forge | 449 tests, zero failures/errors, three unrelated skips; release and bundled JAR verification passed |
| Minecraft 1.21.1 NeoForge | 455 tests, zero failures/errors, three unrelated skips; release and bundled JAR verification passed |
| Four release JARs | Standalone/bundled variants for both versions passed exact six-target native resource checks |
| Foreign-platform execution | Unverified locally; six-host CTest matrix configured in CI |
| Minecraft visual/input acceptance | UNVERIFIED_LIVE; no client launched for this implementation |

The local real model contains 283 source bones, 138 vertex Morphs, 33 material
primitives, 19 captured textures and 118/154 converted physics bodies/joints.
The neutral loaded height is 1.85. The offline test samples animation and skin
matrices, builds generic Rig/physics, exports an ordinary package and reopens it
without its source directory or conversion cache. This does not establish
visual parity with CSM, correct equipment placement, or gameplay action coverage.
Third-party character assets remain local test inputs and are not redistributed.

The local build used AssimpLoader `d56a254135797c4b73f2cefb7cc4e6e0722efe3f`,
which also matches the pinned submodule. To repeat the Java and packaging gates:

```powershell
./gradlew.bat ':1.20.1-forge:build' ':1.21.1-neoforge:build' `
  ':1.20.1-forge:verifyBundledJar' ':1.21.1-neoforge:verifyBundledJar' `
  --no-daemon --console=plain
python -m unittest discover -s tools/native -p test_verify_rigworks_mmd.py
python tools/native/verify_rigworks_mmd.py
```

Java tests use original generated fixtures by default. Add
`'-PmmdModelFixture=path/to/character.pmx'` to include a local real-model check;
place matching VMD and textures beside it as described in the import guide.
The first implementation's local logs are `build/mmd-final-build.log` and the
per-node Gradle test reports. These ignored outputs are not repository fixtures.

Build commands, platform baselines and binary provenance are recorded in
[`BUILD_NOTES.md`](../../native/rigworks_mmd/BUILD_NOTES.md). The workflow checks
all six native resources in standalone and bundled JARs. Runtime source parsing
and MMD-specific rendering must not migrate into AssimpLoader.
