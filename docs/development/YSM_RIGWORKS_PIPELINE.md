# YSM to RigWorks Asset and Action Contract

This contract replaces the earlier GLB bridge. Importing an encrypted YSM file performs one
conversion and writes a versioned RigWorks asset to disk. Character selection and later launches
read that asset; they do not decrypt or parse the source YSM again.

## Source identity and stored asset

The imported character records sourceFormat: "ysm", the source SHA-256, the source YSM format
number, and the RigWorks asset schema/converter versions. The profile keeps this provenance when
the character is edited, copied, shared, or re-exported. Reimporting identical source bytes and
converter/schema versions reuses the existing converted asset; a newer converter creates a new
asset revision and leaves the prior profile usable until the new one validates.

The persisted .rwym container is RigWorks-owned and platform-independent. It must contain
validated, length-bounded sections for:

- Model identity, source/provenance metadata, unit and axis conventions, and original bone names.
- Bone hierarchy, bind pivots/rotations, vertex positions, per-face UVs/normals, and rigid bone
  assignment. Keep the source face geometry directly; do not depend on Blockbench's geometric
  reverse inference for runtime fidelity.
- Texture variants, dimensions, color/normal/specular channel identity, and the converted image
  bytes needed at runtime.
- Animation categories/files and original clip names; duration, loop mode, blend weights; bone
  rotation/position/scale channels; keyframe time, interpolation/easing, pre/post values; timeline
  instructions and sound keyframes.
- Controller names and initial states; conditional animation selections and transitions; state
  entry/exit programs; blend transition data; controller sound/effect events.
- User Molang functions and other model-owned string programs, plus unrecognized source sections
  as opaque versioned payloads until RigWorks supports their semantics.
- Named locators, hand/weapon bone groups, item mount transforms, and model replacement/variant
  rules required to preserve model-authored attachment behavior.

The container has a magic, schema version, section directory, per-section lengths and checksums.
Writes use a temporary file followed by atomic replacement. Native conversion stays under the
RigWorks build and packaging; AssimpLoader has no YSM parser, decoder, or YSM-specific asset type.

## Conversion approach

YSMParser demonstrates the useful authoring representation: a Blockbench-compatible Minecraft
project directory with models/*.json, animations/*.animation.json, controller/*.json, textures/,
sounds/, functions/, language, avatar and model metadata. Its model parser starts from baked faces
and reverse-infers cube origin/size/pivot/rotation for editing. That inference is not needed for
RigWorks runtime geometry and can be ambiguous for rotated or nonstandard shapes; the .rwym
geometry section stores the original face vertices and UVs.

Blockbench's FBX exporter provides a useful cross-check for bone hierarchy and animation export: it
creates a Skin deformer and per-bone Clusters with weighted vertices and animation tracks. The
exporter itself labels armature FBX export experimental, and FBX cannot by itself represent YSM
controllers, Molang predicates, sound/instruction keys, or optional-mod state adapters. Therefore
FBX is an optional interchange/export target, not the canonical converted cache. A later
Blockbench-compatible JSON export can be generated from .rwym without decrypting the source.

Initial native support is staged by source version and feature coverage. The current C++ slice
implements only the bounded legacy V3 encrypted envelope decoder. It does not yet parse the inner
model payload or write .rwym; the current Java parser and GLB writer are not evidence of completed
native conversion. A version is supported only after the native converter preserves its model,
animation, controller, texture, function, and event sections in a fixture round-trip test.

## Runtime animation semantics

Import preserves author-authored YSM logic as data. It must not bake controllers into a single
default pose or replace their animation selection with generic Minecraft-to-bone mapping. RigWorks
evaluates converted clips and controllers against a per-character client input snapshot. The
snapshot combines vanilla-visible state with optional mod adapter data; it never changes server
position, collision, damage, or other gameplay authority.

YSM blink is a query plus model-authored animation/controller logic, not an automatically invented
curve. Preserve eyelid bones/channels and expressions that read ysm.is_close_eyes. Implement the
query with UUID-offset timing and sleeping behavior. RigWorks may additionally offer an opt-in
fallback blink layer when a model lacks authored blink logic, and must label it as RigWorks
behavior rather than imported YSM content.

## Optional mod compatibility

The YSM pattern separates authored animation from host integration: the model provides tac: clips,
controllers, and per-gun overrides; a client adapter reads the optional mod's current state. For
TaCZ, the adapter can expose normalized values for holding a gun, gun id/type, aim, fire, reload,
melee, draw, and fire mode. The controller/runtime selects a gun-id-specific clip when present,
then category or generic fallback clips. Missing TaCZ or an unsupported TaCZ version disables only
that adapter and leaves core YSM animation and rendering available.

RigWorks supplies a mod-neutral adapter SPI: providers sample bounded immutable values on the game
thread, then expose those values to controller conditions and KubeJS/JSON rules. TaCZ, SlashBlade,
backpack, swim, and other providers do not own RigWorks controller state, renderer resources, or
gameplay decisions. Actual ItemStacks continue through Minecraft's item renderer. Imported locators
and YSM attachment groups provide pose alignment; optional adapters may supply the external item's
render context and safe transforms.

Per-profile JSON/KubeJS rules can enable, disable, or override imported animations, controllers,
attachments, mod providers, and feature fallbacks. Successful reload replaces that character's
rules atomically; a failed reload retains its last valid behavior. Provider input and controller
evaluation are local visual data only.

## Implementation checklist

- [x] Dedicated worktree and RigWorks-owned native source directory.
- [ ] C++ legacy V3 decoder verified against a known YSM fixture's decompressed-payload hash.
- [ ] Native format parser preserves all supported source fields and unknown sections.
- [ ] Native converter writes .rwym; Java loads .rwym without entering the YSM decoder.
- [ ] Import UI persists sourceFormat: "ysm", source/converter hashes, and atomic profile/asset
  updates; repeated import reuses the cache.
- [ ] RigWorks avatar loading and renderer consume the converted asset without GLB conversion.
- [ ] Full animation/controller/instruction/sound semantics are exercised by fixture tests.
- [ ] Blink uses imported YSM query and authored channels; fallback is separately configurable.
- [ ] TaCZ adapter and per-gun/category fallback are tested with and without TaCZ installed.
- [ ] Windows x64, Linux x64, and macOS arm64 libraries are packaged by RigWorks; each platform
  also runs the same format/fixture tests.
- [ ] Client verification uses a fresh run after the new Java/native binaries are packaged.
