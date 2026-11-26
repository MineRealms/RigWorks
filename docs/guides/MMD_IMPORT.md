# MMD Import

RigWorks can convert a PMD/PMX character and optional VMD motions into ordinary
RigWorks assets. Conversion happens when importing the source. Playing or
reopening the resulting character uses AssimpLoader and RigWorks, with no CSM
installation or MMD evaluator required.

## Import

1. Put the `.pmd` or `.pmx` beside its texture files. Preserve texture subfolders.
2. Put the desired `.vmd` files in the same directory, or select them through the
   adjacent settings file described below.
3. Open **RigWorks > Characters > Import MMD** and select the model.
4. Use **Edit avatar** to tune humanoid mapping, equipment, actions and physics.
5. Export the edited project as a `.rig.zip`. Import that ZIP with **Import
   avatar** on another installation.

The default neutral mesh height is 1.85 blocks, with uniform scaling and feet at
Y=0. Standard MMD Japanese and common English humanoid bone names are mapped
automatically. Unusual skeletons still need manual calibration in the Workbench.
The original files are not rewritten.

When VMD files are supplied, their solved motion is installed as **Clips**. With
no VMD, the model uses the built-in **DEFAULT** humanoid preset. These modes can
still be changed in Characters. YSM models retain their own animation system.

## Per-model Settings

For `character.pmx`, create `character.mmd-import.json` beside it:

```json
{
  "height": 1.85,
  "sampleRate": 60,
  "maxFrames": 18000,
  "motions": [
    {"path": "motions/stand.vmd", "name": "idle"},
    {"path": "motions/walk.vmd", "name": "walk"}
  ],
  "animations": {
    "idle": "idle",
    "walk": "walk"
  }
}
```

All motion and texture paths must stay inside the selected model's directory.
Absolute paths, escaping parent paths and escaping symlinks are rejected. Copy
shared motions into that directory before importing. `motions: []` explicitly
disables motion discovery; omitting `motions` discovers sibling `.vmd` files.
An omitted clip name uses the VMD filename without its extension.

Common names such as `stand`, `idle`, `walk`, `run`, `crouch` and `attack` map to
player intents automatically. Use `animations` for other filenames. The keys
are the same intents accepted by an ordinary RigWorks profile; values must name
an imported clip. A VMD is animation data for a particular skeleton, so matching
bone names and a compatible source rig still matter. Unknown bone keys are
reported instead of being presented as successfully mapped motion.

`sampleRate` accepts 15-120 samples per second. `maxFrames` limits the number of
output samples per motion, not the source VMD frame number. Up to 64 unique
motions are accepted, with a combined budget of eight million bone/Morph
samples. Constant tracks are collapsed, unchanged bone channels are omitted,
and vertex Morph offsets use sparse storage. Lower the sampling rate or import
fewer motions when the budget is exceeded. Playback does not call the converter.

## Supported Data

| Source data | Converted result |
| --- | --- |
| PMD 1.0, PMX 2.0/2.1 | Ordinary skinned GLB with hierarchy, inverse binds, normals, UV0 and material primitives |
| BDEF weights | Normalized linear blend skinning, up to four influences |
| SDEF, QDEF | Two-/four-weight LBS approximation, with a warning |
| Vertex Morph | Named neutral-zero targets, plus VMD weight curves |
| Bone and group Morph | Generic RigWorks manual controls; motion effects baked into FK / vertex curves |
| VMD 0002 | Destination-key Bezier evaluation, IK enable switches and append transforms baked into ordinary clips |
| Diffuse texture and material alpha | Copied local texture dependencies and generic PBR material |
| Sphere, Toon and CSM effects | Source diagnostics only; not an equivalent shader implementation |
| Sphere/box/capsule bodies and six-axis joints | Generic RigWorks physics, including collision masks and supported springs |

Physics stays live in the ordinary RigWorks solver. It is not baked into the
VMD clips. Global Rig physics controls and per-character definitions continue to
apply. Generic limits are 128 bodies, 256 joints and one bound body per bone;
omitted bodies, joints and unsupported semantics produce import warnings.
Physics and Rig sidecar numbers are rounded to six decimal places to fit the
existing bounded visual configuration.

PMX soft bodies are rejected. UV/material/impulse Morphs, interactive MMD
fixed-axis/external-parent constraints and camera/light/shadow VMD tracks are
not reproduced. IK and append are solved during conversion; they are not
installed a second time as runtime constraints. This importer handles standard
MMD files, not arbitrary encrypted CSM containers or their databases.

## Cache, Packages And Diagnostics

Converted assets are stored under `config/rigworks/mmd-assets/`. Cache identity
includes source/model/motion settings and texture contents. Reimporting unchanged
data reuses the conversion; changed dependencies create a new immutable asset.
Failures retain the previous selected character and log the exception. Enable
AssimpLoader Debug for additional preparation timing and cache information.

The `.rig.zip` contains the ordinary model, captured textures, profile, Rig,
physics and equipment definitions. The PMD/PMX/VMD sources and converter are not
needed to reopen it. Keep the original author sources separately if you intend
to import more motions later. Importing a standalone VMD into an already
converted avatar is not currently a separate GUI action: update the source
settings and import the source model again.

Windows/Linux/macOS x64 and ARM64 libraries are packaged. Foreign-platform
execution and Minecraft visual acceptance are tracked separately in the
[pipeline notes](../development/MMD_IMPORT_PIPELINE.md). Ordinary equipment
mounting and vanilla/DEFAULT retargeting may need model-specific tuning; source
conversion does not guarantee that every authored character looks correct in
every player action.
