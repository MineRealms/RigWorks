# Avatar Authoring Sources

The shared AssimpLoader workbench edits a RigWorks avatar through the registered RigWorks extension. The FBX, meshes and textures remain source assets. Character configuration and behavior are overlays. Saving or exporting does not rewrite an FBX.

## Local Layout

Editable scripts belong to the avatar-specific directory, never to `kubejs/client_scripts`:

```text
kubejs/rigworks/avatars/<instance-uuid>/
  manifest.json
  config/
    mappings.json
    actions/options.json
  scripts/
    controller.js
    actions/locomotion.js
```

Each imported copy gets a stable `instanceId`, its own profile resource ID (`rigworks:avatars/<instance-uuid>/profile.json`) and its own authoring folder. The manifest schema `version` is not a source revision: the SHA-256 source hash changes when the script contents or copy identity changes. The controller ID identifies a function inside that package's isolated interpreter. Two packages may use the same controller ID without sharing JavaScript state. Entry scripts run in the order listed in `manifest.json`; other JSON and JS files are retained for export.

```json
{
  "version": 1,
  "id": "example:avatar/lusia",
  "profile": "example:characters/lusia/profile.json",
  "controller": "example:lusia",
  "instanceId": "e20b74cf-531c-4f98-a2c8-a88a5260c4f5",
  "scripts": ["scripts/actions/locomotion.js", "scripts/controller.js"],
  "mappings": "config/mappings.json"
}
```

A newly imported FBX without an inherited controller receives a controller template that calls `frame.defaultAnimation()`. This delegates vanilla action selection to RigWorks' native action system. It does not synthesize FBX animation clips or replace authored animation mappings. An existing inherited controller is not replaced by a template.

```javascript
RigWorks.register('example:lusia', function(frame) {
  frame.defaultAnimation();
});
```

## Import, Reload And Export

**Import avatar** delegates file selection to AssimpLoader's platform-native file chooser. Select a model or portable ZIP there instead of typing a filesystem path. Opening an existing workbench project uses the same native dialog infrastructure.

The portable ZIP stores the same relative source layout under `extensions/rigworks/behavior/`. All entry points for a marked avatar ZIP (generic Import, Open, drag/drop and Import avatar) first ask whether to create an independent copy or update the copy identified by the archive. A new copy receives a new UUID, profile ID and source folder even when the contents are identical. Opening a saved local project retains its identity. Import reads archived sources before consulting the local repository. When updating an existing copy with different scripts, the workbench asks whether to replace that copy's scripts, import as a new copy, or keep the local scripts. Replacement only updates the exact matching copy and keeps its profile and ID. An identical update reuses the source bundle without rewriting its files. Cancel restores the previous editor session and removes the newly unpacked project.

An explicit RigWorks FBX import first reads an associated authored `profile.json`, including its referenced equipment, rig/IK and physics definitions. Those definitions become a self-contained visual snapshot bound to the imported model, while the new avatar gets a separate profile identity. Imported animation/action mappings and calibrated humanoid axes take priority over name guessing. If no associated definition exists, the importer can inherit the active matching model or propose mappings from node/clip names. Source scripts are cloned into the new identity when available; otherwise a template is generated.

Resource-pack models still use `assets/<namespace>/models/...`. When captured into a workbench project, the engine retains their sidecars and original resource IDs through an adjacent `.resources.json` index. File-backed `.instance.json` is loaded by the animation manager, including indexed parents. Workbench ZIPs and Minecraft resource packs remain distinct formats. See [RigWorks package guide](RIGWORKS_AVATAR_PACKAGE.md#资源包与工作台包).

A reopened local workbench project follows its published authoring folder. **Reload avatar sources** reads current files, validates JSON and starts a temporary bounded interpreter to validate the entry scripts when Rhino is installed. A failed load leaves the last published bundle active and reports failure; it does not publish a partial replacement. Save, Apply and Export must await the same asynchronous source preparation before taking their snapshot.

Export uses the prepared immutable snapshot. The export dialog lets the user include or omit RigWorks character setup, equipment transforms, root transform, physics/runtime options and KubeJS avatar scripts. If scripts are selected, every allowed JSON and JS source is included, including nested files. Omitting scripts removes their project snapshot from the ZIP; they are not left behind as stale files. The exporter does not scan the source tree while constructing the archive on the render thread. Unresolved or failed source preparation rejects export instead of silently exporting an obsolete bundle.

Source limits are 32 files, 64 KiB per file and 512 KiB total. Source paths are relative JSON/JS paths, directory traversal is rejected, symbolic links are rejected and the supported source tree is at most eight levels deep.

## Network Boundary

This authoring service is called only for an explicitly opened local RigWorks workbench project. Received player packages stay in the network package/cache flow. They never become writable authoring folders under `kubejs/rigworks`, and they are never installed into global KubeJS `client_scripts`.

Player ownership, revision checks, sharing preferences and per-owner runtime sessions are handled by RigWorks' configuration distribution layer. A source package and its controller ID are not a global mutable player instance.

Local script folders use `kubejs/rigworks/avatars/<instance-uuid>/`. Received player packages stay in the verified per-owner cache and are executed by isolated sessions; they are not written into that authoring folder and are never installed into global KubeJS scripts. The server identifies the publisher by player UUID, so two players with the same FBX or profile template still have separate authoritative configurations.

## Sharing Material And Node Edits

`assetEdits` in the shared character configuration preserves node transforms and visibility, material factors and texture references, sockets, and animation preview metadata. Its data participates in the configuration digest, package hash and each owner's server revision. The same FBX and profile used by two player UUIDs still have independent accepted configuration revisions; one owner's Apply does not update the other owner's edits.

Local workbench configuration can retain absolute texture paths for authoring. Such paths cannot be included in a multiplayer configuration package. Both outgoing package construction and incoming package parsing reject absolute filesystem paths, UNC paths, directory traversal, unsafe URI/device aliases and invalid embedded references. Failures identify the material index and texture slot instead of silently rewriting the reference. Shared source-path metadata is restricted as well.

Portable references are relative paths confined to the installed model's directory, namespaced Minecraft resource IDs, or `*N` indices into that model's existing embedded textures. Examples are `textures/body.png`, `example:textures/avatar/body_n.png` and `*0`. **New FBX files and texture images are not sent with multiplayer configuration packages.** Every client must already have the matching asset and any newly referenced textures installed. A local full workbench ZIP export is an asset distribution artifact and is separate from this lightweight player configuration channel.
