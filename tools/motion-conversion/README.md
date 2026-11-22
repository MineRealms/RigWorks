# Offline VMD Clip Preview

This optional authoring tool samples the user's local CustomSteve MMD engine,
then bakes its solved world poses onto existing RigWorks Blender avatars.
Nothing in this directory is a Minecraft runtime dependency. No original JAR,
native library, PMX, VMD, or converted animation data is redistributed here.

## Requirements

- An open Blender with the existing Blender MCP addon listening on loopback
  port 9876. The helper uses the addon's `execute_code` API.
- Python 3.10+ and Java 21+.
- The matching local `java_ngengine` JAR and `df_java_bridge` library, plus
  the user's FullPack ZIP. The supplied launcher has this workstation's defaults;
  paths are parameters, not a downloader or a packaged engine.
- Existing avatar scenes. `prepare_blender_preview.py` appends the repository's
  block-player fixture and, when present, the calibrated local Madoka project.

## Run

From the RigWorks root, with Blender already open:

```powershell
.\tools\motion-conversion\Invoke-VmdPreview.ps1
```

After editing `retarget_preview.json`, reuse the already solved source poses:

```powershell
.\tools\motion-conversion\Invoke-VmdPreview.ps1 -SkipSampling
```

The new file is
`build/vmd-motion-preview/customsteve_clips_preview.blend`.
The `RigWorks_VMD_Clips_Preview` scene contains both target avatars and an NLA
reel with STAND, WALK, RUN, and JUMP timeline markers. Individual actions are
named `csm_<motion>__BlockPlayer` and `csm_<motion>__Madoka`. The original
avatar scenes, original actions, and source files are preserved. Rebuilding
replaces only the generated preview scene and its unused generated actions.

## Data Flow

1. Extract the source PMX and four selected VMD files under ignored `build/`.
2. Load the reference MMD solver in a separate Java process without Minecraft,
   an OpenGL window, or material loading. The JAR itself is a JNI wrapper.
3. Explicitly seek each source frame, reset bones and morph weights, evaluate
   VMD curves and bone morphs, then execute `FullUpdateWithMotion` for IK and
   append transforms. Identity attachments expose final world rotations.
4. Sample at 60 Hz. Validate finite transforms, unit quaternions, independent
   world-position agreement, absolute-seek repeatability, and animated knees.
5. Convert native right-handed Y-up output into Blender Z-up. The native loader
   has already reflected raw PMX Z; do not reflect it a second time.
6. Bind source roles to target bones and calibrate their world directions.
   Preserve full target matrices, imported scale, intermediate parents, and
   independent pelvis/torso branches. Fold source twist/control/IK effects only
   after the source solver has evaluated them.
7. Bake target FK channels at the sampled times. Configured walk/run loops blend
   their final four samples toward the first pose to remove the source seam.
   The original source samples remain unchanged. Horizontal root travel is
   removed; vertical motion is retained. A constant floor adjustment per clip
   aligns the lowest deformed vertex without cancelling airborne phases.
8. Store independent Blender Actions and assemble a separate demonstration reel.

`source-samples.json` records source/engine hashes and source timing.
`retarget_report.json` records bindings, unit scale, axis conversion, knee ranges,
and floor correction. Both files are ignored build artifacts.
`blender_verification.json` additionally records finite pose/skin checks,
deformed mesh height and ground minimum over every sample, endpoint differences,
and preservation of the original 15 block-player actions.

## Model Calibration

`retarget_preview.json` owns source role names, target role names, neutral pose
source, and preview repetitions. Bind-pose targets use identity local offsets.
For imported avatars, a calibrated neutral **action** can be required: Madoka's
`mc_idle` also evaluates the imported axis correction and uniform scale at the
armature/root. A different neutral object's local offsets alone lose that data.

Madoka's physical left/right mapping is intentionally `_R`/`_L`, as recorded
in its existing profile. The tool checks the mapped side directions and records
any needed X mirroring. Its configurable 8-degree arm clearance accommodates
the target skirt; this is model fitting, not a universal VMD rule.
Palm fitting uses the target middle/index metacarpal positions to build a
complete palm frame, rather than assuming the hand bone's local Y points
towards its fingers. These references do not animate the finger joints.

## Verification And Limits

This preview checks authoring poses, not Minecraft gameplay. Only the stand
first-frame stance and walk/run/jump are sampled. Jump is the source's 0.3-second
pose sequence, not a synthesized takeoff/flight/landing cycle. Repetition in
the reel is for inspection; the standalone jump action is not a looping action.

Source bone morphs are already included in FK. Do not apply them again.
Target face morphs default to zero; facial aliases, finger retargeting, equipment
locators, source secondary physics, collisions and runtime action policy are
outside this preview. A source motion's duration is not Minecraft attack or
locomotion timing. Game integration must still synchronize phase, stride,
item contacts, fallback and root policy, and be validated in the actual client.

The source evaluator is version specific. This tool demonstrates conversion;
it does not establish permission to redistribute third-party motion assets.
