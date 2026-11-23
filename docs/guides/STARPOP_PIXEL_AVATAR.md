# Starpop pixel avatar

`Starpop` is the small, original voxel avatar used to exercise the RigWorks
authoring path. It is generated from the BlockPlayer Blender armature, but its
mesh, palette, clothing, hair and accessories are original. The generator
creates one welded skinned mesh instead of exporting a cube object for every
voxel.

## Files

- [Blender source](../../build/starpop/starpop.blend) - open the `Starpop | RigWorks avatar` scene and press Space to preview the authored walk and secondary motion action.
- [FBX](../../build/starpop/starpop.fbx) - the game-facing export, with 15 named clips and two shape keys.
- [RigWorks package](../../build/starpop/starpop.rig.zip) - a Workbench package containing the FBX, profile, rig, equipment and AssimpLoader instance sidecar.
- [Front preview](../../build/starpop/starpop-preview.png) and [back preview](../../build/starpop/starpop-back.png).
- [Authoring script](../../model-tests/scripts/build_starpop.py) and [package command](../../model-tests/scripts/Invoke-StarpopPackage.ps1).

The Blender file and package are generated build artifacts. Rebuild the model
from the repository root with Blender 5.3 (this starts a background instance
and does not replace an open Blender scene):

```powershell
& 'G:/Program Files/Blender Foundation/Blender 5.3/blender.exe' `
  --background --factory-startup --python-exit-code 1 `
  --python model-tests/scripts/build_starpop.py -- --render
```

Then package it with Java 21 and built `1.21.1-neoforge` classes in both
repositories:

```powershell
& '.\model-tests\scripts\Invoke-StarpopPackage.ps1' `
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
The clips are `idle`, `walk`,
`run`, `crouch`, `crouch_walk`, `jump`, `fall`, `swim`, `fly`, `elytra`, `ride`,
`sleep`, `dead`, `use_item` and `swing`.
These are the existing BlockPlayer validation clips. They are useful for this
sample's skeleton and physical contacts; they are not the shared VMD motion
library, which remains in the [separate TODO](../development/SHARED_MOTION_LIBRARY_TODO.md).

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

The offline report is [runtime-validation.json](../../build/starpop/runtime-validation.json).
It loaded the exported FBX with AssimpLoader, checked all 43 scene nodes, ran
600 simulated ticks across the 15 clips, observed at most six fixed substeps,
and measured a maximum imported bind-joint error of `1.33e-6 m`. The report
also records the minimum particle clearance per clip. Riding still has up to
`0.000713 m` of residual particle penetration under the capped projection
solver; crouch contacts are within `1e-6 m`. The checks cover bone-tip particles,
not every skirt triangle, self collision or full cloth simulation. Fast running
and riding can substantially displace a panel from its rest direction; these
poses still need visual review in the client.

The example exposed a near-180-degree rotation error in AssimpLoader's Spring
modifier. The corrected rotation keeps the original bone scale. With the
correction, the maximum published-tip/particle difference across the sampled
clips is `1.53e-6 m`, and all seven SpringBone regression tests pass. Use a build
with that fix; the package does not patch an already running game.

The package report is [starpop.rig.zip.validation.json](../../build/starpop/starpop.rig.zip.validation.json).
It confirms the Workbench round trip retained one skinned mesh, one embedded
texture and all 15 clips. Live Minecraft validation, equipment rendering and
network sharing remain explicitly unverified until this avatar is installed in
a fresh client.
