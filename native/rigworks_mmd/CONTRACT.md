# Import ABI v1

The public header is `include/rigworks_mmd.h`. Library basename: `rigworks_mmd`.

`rwmd_convert(model_bytes, model_size, source_path, output_directory, options_json)`
returns zero on success, nonzero on failure. Strings are UTF-8. `model_bytes` is the
selected PMX or PMD file, not a filename. `source_path` names its original location
for texture resolution. The importer writes `model.glb` and `metadata.json` into
the caller's staging directory. The caller publishes that directory only after
successful conversion and validation. Errors are returned by `rwmd_last_error()`.

Options are JSON: `{"height":1.85,"sampleRate":60,"maxFrames":18000,"motions":[{"path":"absolute/path/walk.vmd","name":"walk"}]}`.
Height is the uniformly scaled neutral mesh height, in blocks; feet become Y=0.
The source Z coordinate is reflected once, producing Y-up and model-forward +Z.

Metadata is JSON with `version:1`, `sourceFormat`, `height`, `scale`, `offset`,
`bounds:{min,max}`, `bones:[{name,parent,position,translation}]`,
`morphNames`, `clips:[{name,duration}]`, `textures:[{sourcePath,assetPath}]`,
`materials`, `physics`, and `warnings`. Positions and dimensions use the converted
model coordinates. Texture source paths retain the original PMX-relative name;
asset paths match the GLB image URIs under `textures/`. Texture pixels are copied
by the Java adapter, never loaded by the native evaluator.

`physics` is a ready-to-parse generic CharacterPhysicsDefinition JSON object
(`version`, `bodies`, `joints`); `rig` is a generic CharacterRigDefinition object
with neutral defaults and supported bone/group morphs. `bones` positions are
converted model-space bind positions; translations are parent-relative.
`morphNames` lists exported GLB vertex targets only. VMD group weights are
resolved into these targets; bone morph effects are baked into FK channels,
so the runtime must not apply the original VMD bone/group tracks a second time.

The converter has no rendering or CSM runtime. Animation is solved once with
Saba and stored as standard glTF FK channels; Bullet is linked for Saba model
construction but runtime rigid-body simulation is not baked into these clips.
Physics source definitions are returned as metadata for the generic RigWorks
physics adapter. Unsupported skinning/material semantics produce warnings.
