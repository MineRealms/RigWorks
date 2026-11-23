# Starpop pixel avatar

`Starpop` is the small, original voxel avatar used to exercise the RigWorks
authoring path. It is generated from the BlockPlayer Blender armature, but its
mesh, palette, clothing, hair and accessories are original. The generator
creates one welded skinned mesh instead of exporting a cube object for every
voxel.

## Files

- [Blender source](../../build/starpop-vmd/starpop.blend) - open the `Starpop | RigWorks avatar` scene to preview the VMD walk and secondary motion action.
- [FBX](../../build/starpop-vmd/starpop.fbx) - the game-facing export, with 31 named clips and two shape keys.
- [RigWorks package](../../build/starpop-vmd/starpop.rig.zip) - a Workbench package containing the FBX, profile, rig, equipment, instance sidecar and isolated avatar scripts.
- [Front preview](../../build/starpop-vmd/starpop-preview.png) and [back preview](../../build/starpop-vmd/starpop-back.png).
- [Authoring script](../../model-tests/scripts/build_starpop.py) and [package command](../../model-tests/scripts/Invoke-StarpopPackage.ps1).

The earlier files under `build/starpop` have only the original fixture clips.
They do not contain VMD motions. Use the `starpop-vmd` outputs above.

The Blender file and package are generated build artifacts. First generate the
local, solved VMD library with the [motion conversion tool](../../tools/motion-conversion/README.md).
No source VMD, PMX or proprietary solver is included in this repository. Rebuild the model
from the repository root with Blender 5.3 (this starts a background instance
and does not replace an open Blender scene):

```powershell
& 'G:/Program Files/Blender Foundation/Blender 5.3/blender.exe' `
  --background --factory-startup --python-exit-code 1 `
  --python model-tests/scripts/build_starpop.py -- --output build/starpop-vmd --render
```

Then package it with Java 21 and built `1.21.1-neoforge` classes in both
repositories:

```powershell
& '.\model-tests\scripts\Invoke-StarpopPackage.ps1' `
  -AssetDir build/starpop-vmd `
  -JavaHome 'C:/Program Files/Java/jdk-21' `
  -AssimpLoaderDir 'H:/MinecraftMods/AssimpLoader'
```

The command uses the real AssimpLoader importer and Workbench store. It first
calibrates the sidecars from imported FBX bind matrices, then exports and
re-imports the package. It does not start Minecraft.

## Authoring contract

The neutral avatar is 1.85 m tall with its lowest vertex at Y=0. It has one
mesh, one nearest-neighbour pixel palette, 40 skin bones, 6,634 triangles and
at most four normalized influences per vertex. Blender has 4,159 authoring
vertices; FBX exports 19,902 split vertices for UV and hard-normal boundaries.
The 25 `csm_*__Starpop` clips reuse the solved BlockPlayer VMD bake: stand,
walk, run, jump, crouch, crouch walk, water stance, swimming, three bow poses,
three crossbow poses, six left/right item and shield poses, and five attacks.
The source MMD engine evaluated IK and append transforms offline; the FBX
contains FK keys at 60 Hz. All 18 shared core bind matrices are checked before
reuse. Floor placement is measured again against the new skinned mesh.

Six BlockPlayer clips remain as fallbacks: `fall`, `fly`, `elytra`, `ride`,
`sleep` and `dead`. They are resampled from 30 to 60 Hz without changing their
durations. The obsolete fixture locomotion and generic `use_item` clip are
excluded from this export. `--fixture-only` explicitly generates the original
15-clip geometry fixture, not this VMD avatar package.

This reuse requires the identical core bind skeleton. Reusing these clips on
an arbitrary rig still needs the calibration work in the
[shared motion library TODO](../development/SHARED_MOTION_LIBRARY_TODO.md).

## Motion modes

`CLIPS` uses the profile's VMD locomotion and synchronized upper-body swing.
Missing special states retain native compatibility. The generic `attack_sword`
source is a tool swing, not a sword combo.

`SCRIPTED` uses the included [avatar behavior](../../model-tests/starpop/behavior/controller.js).
Its default `frame.defaultMotion()` selects the same profile clips. Edit JSON
`motion.driver` to `vanilla` for full native poses. An optional Rhino runtime
is required for scripts; the development launch uses `-PwithKubeJS`. Without
Rhino, SCRIPTED falls back to native poses; CLIPS works independently.

The package's `clipLibrary` lists all 25 VMD candidates. Bow, crossbow, held
item and shield candidates are available for preview, but are not automatically
selected for gameplay until their grip contacts are calibrated. Native arms
handle item use; a generic clip must not overwrite both arms. The Workbench
imports scripts into each avatar copy's own `kubejs/rigworks/avatars/<uuid>`.
Installing a new package is necessary to replace an older imported copy.
When manually replacing resource-pack assets, an accepted configuration in a
copied world can still pin the previous model hash. Use a fresh profile ID or
the Workbench's import/apply workflow; do not disable the hash check. The local
preview uses `rigworks:models/character/starpop_vmd/profile.json` for this reason.

`Blink` and `Smile` are exported as morph targets. FBX's authored morph
weights are 1 by default, so `rig.json` explicitly resets both to zero before
runtime playback. This keeps a new import's neutral face open and expression
free.

## Secondary motion

The instance sidecar owns 22 spring bones: two segments for each of eight
skirt panels, two segments for each twin-tail chain, and one ribbon segment on
each side. Five capsule colliders cover the two thighs, two shins and head.
The springs use a 120 Hz fixed step and an eight-step per-update work cap;
their state is per animation instance. The skirt is an open garment with an
inner wall, rather than a filled cone, so the legs can pass through its bottom
opening. The FBX keeps authored clips free of baked spring state; the runtime
sidecar applies secondary motion after clip evaluation.

The offline report is [runtime-validation.json](../../build/starpop-vmd/runtime-validation.json).
It loaded the exported FBX with AssimpLoader, checked all 43 scene nodes, ran
1,240 simulated ticks across the 31 clips, observed at most six fixed substeps,
and measured a maximum imported bind-joint error of `1.33e-6 m`. The report
also compares 1,206 source-derived joint samples after FBX import (maximum
error `1.53e-6 m`) and checks gameplay intent selection, native item fallback,
and three accepted attack retriggers synchronized to the game swing clock.
The worst sampled particle penetration is `0.000225 m` in crouch. The checks cover bone-tip particles,
not every skirt triangle, self collision or full cloth simulation. Fast running
and riding can substantially displace a panel from its rest direction; these
poses still need visual review in the client.

The example exposed a near-180-degree rotation error and rotation-dependent
capsule inflation in AssimpLoader's Spring modifier. The corrected rotation
keeps the original bone scale; a Gram-matrix bound preserves orthogonal TRS
radii while remaining conservative under shear. The maximum published-tip /
particle difference is `8.1e-7 m`, and all ten SpringBone tests pass. Use an
AssimpLoader build containing both fixes; a package cannot patch a running game.

The package report is [starpop.rig.zip.validation.json](../../build/starpop-vmd/starpop.rig.zip.validation.json).
It confirms the Workbench round trip retained one skinned mesh, one embedded
texture, all 31 clips and the exact avatar source files. The isolated controller
is executed by the actual Rhino runtime before and after the round trip.
Live Minecraft visuals, equipment rendering and network sharing remain
explicitly unverified until inspected in a fresh client.

The isolated `run-starpop-preview-1.21.1` client has logged a successful load
of model SHA-256 `e1e08d8af551cc9b909bd8f7dee06887301e1d7632d9a7be9dad46a64278c3bc`,
31 clips and mode `CLIPS`. This confirms client loading, not visual acceptance
of every action. Rosetta's visible session confirmation remains required for
in-game screenshots and input.
