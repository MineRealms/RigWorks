# Offline VMD Clip Preview

This optional authoring tool samples the user's local CustomSteve MMD engine,
then bakes its solved world poses onto existing RigWorks Blender avatars.
Nothing in this directory is a Minecraft runtime dependency. No original JAR,
native library, PMX or VMD is redistributed here. The 25 exported, solved motions
are also packaged as a compact built-in library under `assets/rigworks/motions/default/`.
The pure-Java DEFAULT runtime retargets that shared library onto each calibrated
avatar without creating another FBX. See [DEFAULT](../../docs/guides/DEFAULT_MOTION_PRESET.md).

To regenerate only that library from existing solved samples, run
`python tools/motion-conversion/pack_default_motions.py`. This step uses Python's
standard library and does not require Blender or the source MMD engine.

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

Use `-Manifest <path.tsv>` for a different source clip list, or `-SkipExport` to
produce only the Blender project. The default source manifest contains 25 clips.

The new file is
`build/vmd-motion-preview/customsteve_clips_preview.blend`.
The `RigWorks_VMD_Clips_Preview` scene contains both target avatars and an NLA
reel with named movement and equipment timeline markers. Individual actions are
named `csm_<motion>__BlockPlayer` and `csm_<motion>__Madoka`. The original
avatar scenes, original actions, and source files are preserved. Rebuilding
replaces only the generated preview scene and its unused generated actions.
The launcher starts playback after successful authoring checks and export.

`build/vmd-motion-preview/exports/blockplayer.fbx` and `madoka.fbx` each contain
25 independent, single-cycle takes. They exclude the demonstration reel,
floor, labels, other avatar, and preview offsets. Textures are embedded and
copied alongside each FBX. `exports/manifest.json` records hashes, original
skin weights, expected durations, sampled mesh bounds and joint positions.
These are model-specific motion assets, not a complete imported avatar package.

## Clip Selection

`source_clips.tsv` defines an output ID, VMD filename, inclusive source frame
range, playback intent and role mask. Source frames use 30 Hz; output uses
60 Hz. A `*` end uses the actual source maximum. Static poses retain two equal
FBX keys, so their exported duration is 1/60 second rather than zero.

| Group | Included clips |
| --- | --- |
| Locomotion | stand, walk, run, jump, crouch, crouch_walk |
| Water | swim (water stance), swimming (prone strokes) |
| Bow | bow_hold, bow_draw, bow_aim |
| Crossbow | crossbow_hold, crossbow_loaded, crossbow_draw |
| Arm overlays | item_hold_left/right, shield_hold_left/right, shield_block_left/right |
| Attacks | attack_sword, attack_tool_right, attack_item, attack_item_right, attack_shield_right |

`attack_sword` comes from the source's generic stick/tool upper-body swing.
It is a candidate weapon action, not a sword-specific combo. The bow draw
uses source frames 0-28; the almost-static tail through frame 480 is excluded.
`bow_aim` samples the stable drawn stance at frame 23. Crouch clips already
start crouched; they are not transitions from standing.

## Data Flow

1. Extract the source PMX and manifest-selected VMD files under ignored `build/`.
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
   Quaternion keys are hemisphere-aligned after closure, because Blender's
   component curves can otherwise pass through a near-zero quaternion and flip
   a panel between frames. The offline verifier checks adjacent quaternion keys
   and half-frame interpolation. The original source samples remain unchanged.
   Horizontal root travel is
   removed; vertical motion is retained. A constant placement adjustment per
   clip aligns the lowest deformed vertex without cancelling airborne phases.
   Swimming has a separate body rotation and floating clearance policy.
8. Store independent Blender Actions and assemble a separate demonstration reel.
9. Copy each avatar into an isolated export scene, validate raw skin weights,
   and export only the declared takes with no repeated reel or unrelated Actions.

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

Feet use `footReferences` to measure the ankle-to-toe heading. Imported foot
local axes are not sole directions. The calibration preserves neutral sole
pitch and roll. Madoka enables `legTargetIK`: a two-bone solve fits the sampled
ankle positions with the target's measured thigh/shin lengths and the source
knee plane. Foot orientation is preserved separately. A common pelvis shift
keeps the fixed ankle goals reachable. Reports record ankle error, foot matrix
error and the pelvis adjustment; the Blender verifier rejects failed solves.

`clipPolicies` separates source role masks, arm clearance, whole-body direction,
placement and numeric limits. Masked-out roles use the solved stand stance.
For bows and crossbows, an optional two-bone arm solve fits wrist targets using
the target arm lengths and source elbow plane. Both grips move together into
the arms' reachable workspace, preserving their separation. The report records
the adjustment and residual error. This fits wrists, not a specific Minecraft
item's grip mesh; final item contacts still require equipment calibration.

## Skirt Physics

The skirt solver is experimental. The strict all-frame gate currently rejects
run and crouch-walk because some frames still penetrate the leg capsules. A
successful script execution is not evidence that these takes passed. Do not
replace a gameplay avatar with an unchecked generated preview.

The default Madoka preview currently leaves this solver disabled. Its prior
bake had antipodal skirt-bone quaternion keys at the loop-closure boundary;
the closure pass now aligns quaternion signs and the no-physics control passes
the half-frame curve checks. Keep the physics option off until the corrected
bake passes the skirt collision gate and visual review. This is separate from
the loop-curve fix: the previous physics solver also left large penetrations
and produced visibly over-flared panels.

On the retained old physics bake, the loop-sign probe measured a 0.543 m
maximum skirt-mesh midpoint excursion in `swim`; applying only the quaternion
hemisphere fix reduced that diagnostic excursion to 0.000034 m without changing
the endpoint poses or mesh. This is an authoring curve defect, not evidence
that a closed or hollow skirt surface is physically solved.

In the inspected Madoka mesh, 2,424 vertices carry skirt weights and form
2,693 skirt-region faces. A vertical ray through the skirt center found no
underside hit, so this is not a closed solid cone. The mesh also has many
downward-facing underside/lining faces near the hem. Those faces are normal for
a lined garment and are not a reason to delete all underside geometry.
Topology alone did not prove the cause of the flying panels. The current
collision verifier now samples triangle vertices, edge midpoints and centroids,
but it still is a conservative authoring check rather than a continuous
triangle-capsule solver. The experimental solver also made corrections up to about
175 degrees, plus sizeable root lifts; those corrections are a direct risk for
folding and detached-looking panels.

Madoka's optional `skirtPhysics` uses six existing two-link skirt chains, fixed
substeps, gravity, damping and ring constraints. Thigh/shin capsules follow the
solved legs. Bone fitting uses child joint positions, not imported bone tails.
Collision fitting also measures the actual 2,424 skirt-influenced vertices;
particle clearance alone cannot establish that the skinned panels are clear.
Only generated mesh copies may receive skirt weight repair. Original model
files and source scene meshes are preserved.

This solver runs during authoring and bakes its output into the FBX takes. It
does not add a runtime cloth simulation. Its mesh fitting is deliberately more
expensive than a game-time bone spring; this work is paid once at conversion.
When enabled, the final verifier checks every baked frame, including the loop
closure, against the leg capsules. A remaining penetration is a failure, not a
successful bake.

The existing runtime `SpringBoneModifier` provides fixed-step bone springs and
sphere/capsule particle contacts. `BulletCharacterPhysics` provides rigid bodies,
6DOF joints, spring forces and bone feedback. Neither provides cloth surface
stretch/bend constraints, cloth self-collision or vertex-level continuous
collision detection. Sparse skirt bones can still leave a panel between joints
inside a leg even when the rigid proxies are clear. Runtime fitting requires a
separate calibrated physics asset and client verification; do not stack a full
baked secondary motion and unrestricted dynamic feedback on the same bones.

## Verification And Limits

See the [shared motion library TODO](../../docs/development/SHARED_MOTION_LIBRARY_TODO.md)
for reusing these samples across avatar skeletons.

This preview checks authoring poses, not Minecraft gameplay. Stand is only its
first-frame stance. Jump is the source's 0.3-second
pose sequence, not a synthesized takeoff/flight/landing cycle. Repetition in
the reel is for inspection; the standalone jump action is not a looping action.

Source bone morphs are already included in FK. Do not apply them again.
Target face morphs default to zero; facial aliases, finger retargeting, equipment
locators, source secondary physics and runtime action policy are outside this
preview. The optional skirt solver is target-specific and does not reproduce
the source model's rigid-body configuration. A source motion's duration is not Minecraft attack or
locomotion timing. Game integration must still synchronize phase, stride,
item contacts, fallback and root policy, and be validated in the actual client.
The standalone FBX takes contain a complete inspection pose, including a stand
base for arm overlays. Runtime use must apply their declared masks; playing an
arm overlay on the entire character would replace locomotion. The prone swim
take owns its body rotation, which must not be applied again by the renderer.

Blender's FBX Morph defaults can be interpreted differently by importers. Set
all target face Morph defaults explicitly to zero in the eventual `rig.json`;
an FBX export alone does not replace that runtime requirement.

The source evaluator is version specific. This tool demonstrates conversion;
it does not establish permission to redistribute third-party motion assets.
