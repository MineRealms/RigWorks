# YSM Interface Contract (shared between the two worktrees)

> Historical design, superseded by [YSM_RIGWORKS_PIPELINE.md](YSM_RIGWORKS_PIPELINE.md).
> Current YSM conversion/native code belongs only to RigWorks; AssimpLoader has no YSM API.

This file is duplicated in both worktrees. Both agents implement against this contract and MUST NOT copy each other's uncommitted code.

## Branch / directory map

| Repo | Worktree | Branch |
|---|---|---|
| AssimpLoader | `H:\MinecraftMods\AssimpLoader-ysm-import` | `feature/ysm-import` |
| RigWorks | `H:\MinecraftMods\RigWorks-ysm` | `feature/ysm-capabilities` |

Original directories `H:\MinecraftMods\AssimpLoader` and `H:\MinecraftMods\RigWorks` are untouched; do not edit them.

## Contract owned by AssimpLoader (Agent A)

New public API, package `cn.minerealms.assimploader.format.ysm`:

```java
public final class YsmImporter {
    /** true when bytes start with .mxc magic (EF BB BF YSGP2 ...) or legacy YSGP magic. */
    public static boolean isYsm(byte[] data);

    /**
     * Convert YSM container bytes into a GLB byte[] that Assimp can import.
     * Result is passed to AssimpModelLoader.loadModelFromMemory("model.glb", glb, mode).
     * Throws IOException on invalid/truncated/unsupported input.
     */
    public static byte[] toGlb(byte[] ysmBytes) throws IOException;
}
```

Add a convenience overload in `cn.minerealms.assimploader.core.AssimpModelLoader`:

```java
public static AssimpScene loadYsmFromMemory(String name, byte[] ysmBytes, ModelImportMode mode);
```

Semantics: calls `YsmImporter.toGlb(...)` then the existing `loadModelFromMemory("model.glb", glb, mode)`. Returns `null` on failure (matching existing `loadModelFromMemory` error style).

### GLB output requirements (Agent A)
- Bone hierarchy -> glTF `nodes`, pivot -> `translation`, rotation -> `rotation`.
- Each Bedrock cube -> one glTF `mesh` (8 corners, 6 faces, normals, UV normalized by texture_width/height with V flipped, indices).
- Every cube vertex: `JOINTS_0 = [boneIndex,0,0,0]`, `WEIGHTS_0 = [1,0,0,0]` (rigid, matches Blockbench `buildSkinnedMeshFromGroup`).
- Textures embedded as `images[].bufferView` (no external URI, since memory import has no file base).
- Animations -> glTF `animations[].samplers[]` + `channels[]` targeting translation/rotation/scale.
- Y-up / Z-front, unit scale consistent with `fbx.ts:163-170,54`.

### Native (optional, Agent A)
A thin `ysmprocessor` C ABI (JNA) may provide container chunk reading / zstd / blake3, but `YsmImporter` MUST have a pure-Java fallback so it works without the native library.

## Contract consumed by RigWorks (Agent B)

Do not depend on Agent A's branch until it is merged. Implement against the API above:

1. Workbench import: accept `.mxc`/`.ysm` in `ModelWorkbenchScreen.requestImport("rigworks")` path; read bytes, call `AssimpModelLoader.loadYsmFromMemory(name, bytes, ModelImportMode.DYNAMIC)`, then run existing `RigWorksWorkbenchExtension.initialize` bone/clip autodiscovery.
2. Blink capability: use existing `AnimationState.setLayer(id, clip, weight, additive, mask)` + `AnimationPoseLayer`; add a `BlinkQuery` computing `is_close_eyes` with the YSM formula `(renderTicks + abs(uuid.leastSignificantBits) % 10) % 90 in (85,90)`.
3. Keep all changes client-visual only; never write server AABB/position/damage.

Agent B must NOT modify AssimpLoader files (including the submodule). Build with `-PassimpLoaderDir=../AssimpLoader-ysm-import` if a build is needed.

## Merge order
1. Merge `feature/ysm-import` first (AssimpLoader), update RigWorks submodule pin.
2. Then merge `feature/ysm-capabilities` (RigWorks).
