# YSM to RigWorks Asset and Action Contract

This contract replaces the earlier GLB bridge. Importing an encrypted YSM file performs one
conversion and writes a versioned RigWorks asset to disk. Character selection and later launches
read that asset; they do not decrypt or parse the source YSM again.

## Source identity and stored asset

The import manifest records sourceFormat: "ysm", the source SHA-256, the source YSM format
number, and the RigWorks asset schema/converter versions. Portable workbench projects retain the
canonical RWYM asset and its identity in their RigWorks extension. Reimporting identical source bytes and
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

Initial native support is staged by source version and feature coverage. The C++ converter now
decrypts the bounded legacy V3 envelope, parses the inner model payload, and writes RWYM sections.
The real encrypted fixture exercises inner layout 9; layouts 4..15 have parser branches but do not
all have real fixture coverage. Legacy V1/V2 and modern MXC containers are not supported by this
converter. The import picker advertises `.ysm` only and unsupported envelopes fail explicitly.

RWYM is the canonical asset. AssimpLoader consumes an internal disposable merged/skinned GLB
render cache generated from RWYM. It is not the interchange contract and contains no Molang or
controller authority. Regenerating it never reads or decrypts the source YSM. Visible geometry is
batched into meshes with at most 256 rigid bone assignments per palette. Runtime reload uses a
bounded Java RWYM reader, so the C++ converter is not required on that path.

### Model orientation

The render cache keeps the source YSM/Blockbench axes (Y up, face at -Z). AssimpLoader imports
`.glb` as OpenGL right-handed and converts that to Minecraft space (face at +Z), so a character
bound to the cache must use `CharacterProfile.ModelOrientation.FORWARD_PLUS_Z`: `modelYawDegrees`
0 and `headLook.forward` +Z. The workbench import path keeps `FORWARD_MINUS_Z`, the 180 degree
default for Blender-style exports whose own forward is -Z. The two fields are one calibration, so
they must be set together. `YsmImportSmokeTest` asserts the imported profile carries the
render-cache orientation instead of inheriting the workbench default.

### YSM locator semantics versus generic bone mapping

YSM does not discover its authored attachment points by guessing a humanoid skeleton. The
reference implementation builds a parent map and then resolves a fixed locator vocabulary by
exact name, following each locator's parent chain: `LeftHandLocator`, `RightHandLocator`,
`ElytraLocator`, `PistolLocator`, `RifleLocator`, waist and shoulder locators, `BladeLocator`,
`SheathLocator`, `BackpackLocator`, `Head`, the numbered hand locators, and the passenger locator
family. A missing locator produces an empty path and the corresponding authored attachment is
absent.

RigWorks therefore uses two separate layers. Imported YSM names and locator paths are authoritative
for YSM equipment, weapon replacement, first-person arms, elytra, and other authored attachments.
The generic role mapper (`body`, `hips`, `spine`, limbs) is only a fallback for vanilla action
retargeting and for models that do not provide an explicit semantic name. It must never rename or
override a YSM controller bone. This keeps YSM behavior faithful while still allowing an ordinary
FBX/Blender model to work without hand-authored mappings.

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
- [x] C++ legacy V3 decoder verified against a real fixture's decompressed-payload hash.
- [x] Native parser/container preserve understood source fields and opaque source sections.
- [x] Native converter writes .rwym; Java loads .rwym without entering the YSM decoder.
- [x] Import manifests retain provenance; content-addressed atomic assets are reused on repeat import.
- [x] Conversion/model decoding run on one import worker; applying a prepared scene returns to the client.
- [x] Imported profiles persist in the character selector; successful imports update the field only if it was not edited meanwhile.
- [x] Import status is visible in the character page and repeated imports are disabled while one conversion is active.
- [x] RigWorks owns the canonical asset and disposable rigid-skin render cache; AssimpLoader has no YSM code.
- [x] Workbench stages required RWYM data alongside its engine GLB; a RigWorks-only edit handoff verifies the copied render cache before restoring YSM provenance.
- [x] Degree-based Molang math, persistent variables, clip-local time, instruction keys and parallel slots have tests.
- [x] UUID-offset blink query is passed to imported expressions rather than inventing a replacement facial animation.
- [x] Authored controllers replace their own fallback slots; first-person/extra categories do not drive body bones.
- [ ] Character-page edit handoff and portable Workbench save/reopen have not yet been driven end to end in a client.
- [ ] Workbench preview cannot yet play YSM-authored controller clips; its generic animation preview is not used for YSM.
- [ ] Full animation/controller/instruction/sound semantics are exercised by fixture tests.
- [ ] TaCZ adapter and per-gun/category fallback are tested with and without TaCZ installed.
- [x] Windows x64 C++ shared library and real-fixture/C ABI tests run locally; resource packaging is wired.
- [ ] Six-platform CI build/test/package jobs have successful remote execution evidence.
- [ ] Client verification uses a fresh run after the new Java/native binaries are packaged.

## Verification Boundaries

The 2026-11-21 full Java run passed on both supported targets: 295 tests on Forge 1.20.1 and
300 on NeoForge 1.21.1, with two intentionally skipped tests per target. Integration source
compilation and module-boundary jar checks passed after controller, cache, and UI changes.
Windows x64 CTest passed both the real-fixture round trip and C ABI tests.

The real fixture has two geometry models (338 total bones, 312 in the main model), 4,986 faces,
195 clips across seven categories (44 in main), 14,319 keyframes and 229 pre-values. Its RWYM
container is 1,086,580 bytes. The Java tests compare the main geometry, per-face UVs, PNG bytes,
animation keys and source hierarchy against the independent Java reference decoder.

Passing structure/cache/unit tests is not proof of model visibility. The opt-in client acceptance
test verifies the exact RWYM profile, live YSM pose modifier, nonempty mesh instances, finite and
changing skin palettes, advancing rendered-pass counters and a screenshot after at least ten
seconds. Shader-cache draws are additionally required when an Iris/Oculus shader pack is active.
Iris being installed alone does not count as an active shader test. FPS at capture is diagnostic,
not a representative performance benchmark.

The latest real client run used the `rigid-v3` cache and produced two meshes, 274 palette entries,
559 rendered passes, and a maximum sampled palette delta of 25.388 after a 10,004 ms texture-settle
period. It also asserts the imported profile carries the render-cache orientation (`modelYawDegrees`
0, `headLook.forward` +Z); the third-person-front capture shows the model's face, where the earlier
run showed the back of its head because the workbench's 180 degree default yaw was applied. The
client acceptance passed at 60 FPS. Iris was installed but no shader pack was active, so the
Iris/Oculus shader-cache path remains unverified.

Still outside demonstrated coverage: YSM Workbench edit/save/reopen from the character page, authored
clip preview inside Workbench, sound playback, model-owned Molang functions, every optional mod
adapter, variants/item replacement geometry, remote-player YSM runtime, all source versions, and the
five non-Windows-x64 native executions. Preserve opaque data for later support; do not report those
features as complete merely because conversion retains their sections.

See [portable asset details](YSM_PORTABLE_BRIDGE_HANDOFF.md) and
[native build and platform gates](../../native/rigworks_ysm/README.md).
