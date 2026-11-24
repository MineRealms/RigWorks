# Misaka pixel avatar

This block-styled avatar uses the BlockPlayer armature and the built-in Java
`DEFAULT` motion library. Its FBX deliberately contains no animation clips and
its RigWorks package contains no behavior script. This makes it a separate
retargeting example from Starpop's baked FBX motion example.

## Outputs

- [Workbench avatar package](../../build/misaka/misaka.rig.zip)
- [Blender source](../../build/misaka/misaka.blend)
- [FBX](../../build/misaka/misaka.fbx)
- [Authoring manifest](../../build/misaka/authoring.json)
- [Runtime validation](../../build/misaka/runtime-validation.json)
- [Package validation](../../build/misaka/misaka.rig.zip.validation.json)

The generated files live under the ignored `build/misaka` directory. Source
templates live in [model-tests/misaka](../../model-tests/misaka); the
[Blender generator](../../model-tests/scripts/build_misaka.py) recreates the
model, textures and authoring manifest.

Import `misaka.rig.zip` through the Workbench avatar import flow and apply its
RigWorks profile. Then select **DEFAULT** in RigWorks' Motion selector.
Animation mode is a client preference: importing a package does not change it.
`CLIPS` has nothing to play in this example. No KubeJS reload is needed.

## Authoring Contract

| Property | Value |
| --- | --- |
| Neutral height | 1.85 blocks, soles at Y=0 after import |
| Blender axes | Z up, face toward -Y |
| Engine orientation | Face +Z, profile yaw 0, head forward +Z |
| Geometry | One merged skinned mesh, 6,240 triangles |
| Materials | One material; four embedded image textures |
| Skeleton / skin | 48 bones; at most four normalized weights per vertex |
| FBX clips / package scripts | 0 / 0 |
| Secondary motion | 28 spring bones and five body capsules |

The 3,725 Blender authoring vertices become 18,720 imported vertices because
the faceted model has split normals and UV seams. Triangle count and draw
partitioning matter more than the authoring vertex count here.

The humanoid roles map to `Hips`, `Spine`, `Chest`, `Neck`, `Head`, and the
left/right `UpperArm`, `Forearm`, `Hand`, `Thigh`, `Shin`, and `Foot` bones.
`Root` remains a helper parent. `Toe.L/R` define the shoes' actual forward
direction for DEFAULT foot calibration.

The open skirt uses eight panels named `Skirt.F/FR/R/BR/B/BL/L/FL.1/.2`.
Hair uses `Hair.Side/Back/Fringe.L/R.1/.2`. Each pair is a parent-child chain.
The imported inverse bind matrices calibrate every spring tail and capsule;
the tool does not assume that exported bone-local units are metres.

`Blink` and `Smile` are optional authored shape keys. The sidecar explicitly
zeros their imported FBX target names. This package does not install an
automatic blinking controller.

## Rebuild The Package

Generate the Blender source, zero-clip FBX and embedded pixel PBR textures:

```powershell
blender --background --factory-startup --python-exit-code 1 `
  --python model-tests/scripts/build_misaka.py -- --render
```

Use Java 21 and existing `1.21.1-neoforge` build outputs in RigWorks and
AssimpLoader, then run:

```powershell
& '.\model-tests\scripts\Invoke-MisakaPackage.ps1' `
  -JavaHome 'C:/Program Files/Java/jdk-21' `
  -AssimpLoaderDir 'H:/MinecraftMods/AssimpLoader'
```

The command compiles the small authoring utilities, loads the actual FBX,
calibrates sidecars, evaluates DEFAULT motion and springs, exports through
`WorkbenchProjectStore`, and reimports the resulting archive. It verifies
metadata, visual configuration and every source asset hash. It neither starts
Minecraft nor needs a script engine for the Misaka package.

The package is the official Workbench format, with `manifest.json`,
`project.workbench.json`, and `assets/`. The RigWorks extension includes
`scriptsIncluded: false`; there is no behavior entry or behavior directory.
Keep the instance sidecar with the FBX: it carries the spring and collider
settings used after import.

## Physics And Validation

The springs are cosmetic secondary motion. They use gravity, stiffness,
damping and animated body capsules. Each fixed step is 1/120 second, with a
maximum of eight steps per update. These are bone springs, so there is no
cloth self-collision, mesh-to-mesh collision, or server hitbox simulation.
Particle clearance alone cannot prove that every skirt triangle stays outside
a moving thigh.

The current offline probe covers idle, walk, run, crouch, crouch walking,
jump, water idle, swimming, repeated sword attacks, bow drawing and crossbow
drawing. It checks finite poses and CPU-skinned vertices, normalized weights,
zero facial defaults, ankle/hand target errors, spring pose/tail agreement,
work limits and capsule particle clearance. The current 660-tick result is
`TESTED_OFFLINE`; client rendering and equipment alignment remain
`UNVERIFIED_LIVE` until checked in a client.

Read [Avatar Validation Workflow](AVATAR_VALIDATION_WORKFLOW.md) for the
remaining client checks. Use the built-in DEFAULT debug counters to inspect
ankle and paired-hand errors during gameplay.
