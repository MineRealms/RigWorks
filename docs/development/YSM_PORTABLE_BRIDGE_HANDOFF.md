# Portable RWYM workbench bridge

Date: 2026-11-21. Worktree: `RigWorks-ysm-native-import`. The original RigWorks and AssimpLoader repositories were not edited by this task.

## Asset layout

Keep the generic engine project source as `assets/*.glb`. RigWorks adds a required canonical asset at:

```text
extensions/rigworks/ysm/model.rwym
```

The RigWorks document extension gains:

```json
{
  "ysm": {
    "version": 1,
    "source": "ysm",
    "asset": "extensions/rigworks/ysm/model.rwym",
    "sha256": "<same SHA-256 as visual.modelHash>"
  }
}
```

The GLB is the engine's editable/renderable representation; RWYM keeps the full authored YSM animation, controller and instruction data. Reopening RWYM uses Java and does not load the native converter or decrypt YSM again. No YSM knowledge is added to AssimpLoader.

## APIs to connect

Two new helpers are ready:

```java
RwymPortableAssets.prepare(Path projectDirectory, Path canonicalAsset, String expectedHash)
RwymPortableAssets.validateMetadata(JsonObject metadata, String expectedHash)
YsmPortableWorkbenchBridge.prepare(WorkbenchExtensions.Context context)
YsmPortableWorkbenchBridge.requirePrepared(WorkbenchExtensions.Context context)
```

The first helper is blocking, bounded file/parse work. The workbench helper runs it on a single worker with a bounded queue and completes on the Minecraft client thread. The latter restores the model registry and `YsmRuntimeBridge` scene binding, validates all authored bone names against the GLB, and writes the `ysm` descriptor to the session. Changed character identity during the job rejects completion.

The parent agent owns `RigWorksWorkbenchExtension`; that file was not edited here. Add these connections:

1. `prepareAsync`:

```java
return YsmPortableWorkbenchBridge.prepare(context)
        .thenCompose(unused -> CharacterAuthoringSources.prepare(context));
```

2. In `opened`, chain the existing source task rather than starting a second overlapping task:

```java
context.runTask("Reading avatar sources", () ->
        YsmPortableWorkbenchBridge.prepare(context).thenCompose(unused ->
                CharacterAuthoringSources.initialize(context, profileId(context), false)))
        .thenRun(context::refresh);
```

3. Add `imported` or chain the completed `initialize` flow:

```java
@Override public CompletableFuture<Void> imported(WorkbenchExtensions.Context context) {
    return initialize(context).thenCompose(unused -> YsmPortableWorkbenchBridge.prepare(context));
}
```

4. Start `packageFiles` with `YsmPortableWorkbenchBridge.requirePrepared(context)`, then keep its existing `CharacterAuthoringSources.packageFiles(context)` result. Do not add RWYM to a `Map<String,byte[]>`.

5. For a newly saved project the async preparation ran before its directory existed. The helper deliberately rejects export until preparation runs again for the actual project directory. Existing `ModelWorkbenchScreen` export does run preparation again after first save. If other callers export directly, they must await `prepare(context)` after the project is created.

## Important integration requirements

- Initial YSM scenes must have a persistent GLB `sourcePath`; `WorkbenchAssets.importLoaded` cannot save memory-only scenes. `RwymAssetModels.loadRenderCache` was observed updated by the parent to load its persisted GLB through `loadModel(Path, DYNAMIC)`.
- Preserve the RWYM mapping in `CharacterVisualOverrides` when applying an opened project. Current `CharacterVisualSharing.registerAsset` writes `scene.getSourcePath()` to the model ID when that model is not already cached. A reopened YSM scene has a GLB source, while its logical model ID ends in `.rwym`. That write would replace the mapping restored by this bridge with the GLB path. The parent must preserve an existing validated `.rwym` mapping for a YSM-bound scene while still registering its scene in AssimpModelLoader. Reproduce on a fresh process/cache; an already cached model hides this bug.
- Do not return an `extensions/rigworks/ysm/` directory snapshot marker from `packageFiles`: the engine excludes the matching on-disk subtree when such a marker is supplied. The canonical RWYM must always remain in the ZIP even if the user disables optional script exports.
- `CharacterVisualPackage` is a small configuration-sharing channel (512 KiB, JSON/JS only), not this full portable asset archive. No large RWYM bytes should be placed there.
- `WorkbenchProjectStore` does not accept RWYM as its model source; its normal GLB path is intentional. No engine importer changes are required for this bridge.

## Validation and failure rules

- Fixed sidecar path, no symbolic links or directory/file conflicts.
- Bounded asset read (512 MiB); SHA-256 must match `visual.modelHash`; RWYM section checksums and parser limits also apply.
- Packaged RWYM takes precedence over any global author cache. A damaged packaged asset fails rather than silently using a different local copy.
- Validation precedes atomic publication. Invalid canonical data does not leave an extension file.
- The prepared state tracks the exact project directory, model ID and hash; stale preparation cannot authorize an export from a different project.
- Export uses the engine's existing streaming file path, including archive checksums, without cloning the large RWYM into extension byte-array callbacks.

## Focused verification

No Gradle process or Minecraft client was started by this task.

Compiled current model/reader/cache/portable helper sources with JDK 17 `javac`; compiled `YsmPortableWorkbenchBridge` against the existing 1.20.1 Forge/AssimpLoader/RigWorks class outputs. Compilation succeeded (two warnings for absent Forge `Dist` annotation classes in the standalone classpath).

Executed `RwymPortableAssetsTest` through the cached JUnit Platform launcher and the current C++ DLL using `rigworks.ysm.nativeLibrary`. All seven tests passed:

- Stage canonical RWYM then delete the author cache and reopen from the project: 312 bones and 195 authored clips, including TaCZ metadata.
- Unsaved project reads its canonical asset without creating directories.
- Corrupt packaged asset cannot fall back to a valid author copy.
- Invalid canonical input does not publish a partial asset.
- RWYM checksum still rejects corruption when an externally supplied SHA-256 matches the corrupt bytes.
- Missing asset and directory conflicts are rejected.
- Metadata rejects path traversal, wrong identity, unsupported/fractional/malformed versions and missing fields.

This validates the helper's file/metadata path. Parent integration and unified tests must cover the actual workbench callbacks and fresh-process Apply path; this document does not claim client rendering verification.
