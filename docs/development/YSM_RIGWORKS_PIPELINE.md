# YSM to RigWorks Asset and Action Contract

Reviewed against runtime commit `16a971e` on 2026-11-25. This document describes
both the asset contract and current implementation; planned behavior is labeled
explicitly. See the [priority TODO](YSM_PRIORITY_TODO.md) for remaining work.

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

This list is the preservation contract, not a claim that Java executes every field.
The converter retains functions, sounds and opaque source data, but the Java asset/runtime
view does not yet expose all of it. Complete item-variant selection, sound playback,
model-defined functions and source-specific material/physics semantics remain extensions.

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
The parser has branches for inner payload layouts 1..15, while real external fixtures currently
verify payload 1 and payload 9. Legacy V1/V2 outer containers and modern MXC containers are not
supported by this converter. The import picker advertises `.ysm` only and unsupported envelopes
fail explicitly.

The external compatibility run converted `_Rumia.ysm` (payload 9, 3 models, 13,440 faces,
9,527,085-byte RWYM), `168.ysm` (payload 1, 3 models, 11,407 faces, 5,827,291-byte RWYM),
and `2_0lingsha(1).ysm` (payload 1, 2 models, 6,780 faces, 2,104,455-byte RWYM). The Java
compatibility test read 225/130/97 clips and 456/371/296 unique expressions respectively;
malformed imported expressions used deterministic zero fallbacks 1/1/0 times. Rejection logs
retain source clip, bone, axis and keyframe time context. These results validate conversion and
runtime preparation for the three samples, not every payload branch or every YSM feature.

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

Controller completion queries (`q.all_animations_finished`, `q.any_animation_finished`, including
their call forms) refer only to the current controller's applied layers. One-shots can transition
back to their idle state; a hold keeps its last pose, and a looping clip records completion of its
first cycle while continuing to loop. Retriggering or resetting clears completion. Static holds
keep a zero animation clock. Completion state does not leak between character owners or controllers.

Item predicates support `$namespace:item`, `#namespace:tag`, `:classification`, `:use_animation`
and `empty`, with physical `left`/`right` and logical `mainhand`/`offhand` selectors. The logical
slots follow the player's dominant arm. Authored `ctrl.use` predicates follow YSM's global item-use
condition; the per-item `q.use_*` queries retain the actual active-hand state. Immutable item token
snapshots are cached per owner and reused until the equipment or use state changes. Vanilla boat
and saddleable vehicle state feeds the native `boat`/`ride` actions without an optional mod provider.

Reimport preserves a valid saved configuration only when its model ID and immutable RWYM hash both
match. A registered scene can be reused only for the exact render-cache path, including its hash and
revision. Removing the last saved reference releases the local model mapping and schedules the
pinned scene for retirement after live local, remote and Workbench users release it. A remaining
character copy keeps the shared model. Canonical RWYM files and disposable render-cache files
remain on disk for later reimport.

### Current runtime coverage

| Area | Implemented | Remaining boundary |
|---|---|---|
| Owners | Independent local and remote runtime instances with shared immutable definitions | Multiplayer gameplay acceptance is separate from instance-isolation tests |
| Categories | Main/body, `arm`, `tac`; dedicated first-person geometry and `fp_arm` controllers | Other historical categories do not have complete triggers and composition |
| Expressions | Bounded Molang subset, persistent `v.*`, clip-local time and timeline expressions | Model functions, `t.*` assignment/scope, advanced builtins and complete coded/hybrid controller semantics |
| Equipment | Authored locators, zero-scale item suppression, held/use predicates and TaCZ rendering integration | General variant selection, replacement priorities and missing-resource fallback |
| Workbench | Portable RWYM preparation, source binding and metadata inspectors | No independent YSM controller playback/input-simulation runtime yet |
| Effects | Source sections retained; generic engine material and rig physics available | YSM sound events, dynamic material behavior and first/second-order source physics are not automatically implemented |

Unknown expression functions can currently fall back to zero; unsupported-symbol diagnostics
and model-owned function execution remain work. Keeping an unsupported source section in RWYM
prevents data loss but does not make its behavior active.

## Optional mod compatibility

The YSM pattern separates authored animation from host integration: the model provides tac: clips,
controllers, and per-gun overrides; a client adapter reads the optional mod's current state. For
TaCZ, the adapter can expose normalized values for holding a gun, gun id/type, aim, fire, reload,
melee, draw, and fire mode. The controller/runtime selects a gun-id-specific clip when present,
then category or generic fallback clips. Missing TaCZ or an unsupported TaCZ version disables only
that adapter and leaves core YSM animation and rendering available.

RigWorks supplies a mod-neutral Java adapter SPI: providers sample bounded immutable values on the
game thread and expose them to controller conditions. TaCZ is the current built-in adapter;
SlashBlade, CarryOn, ParCool, Swem, TLM and other integrations require their own implementations.
Providers do not own RigWorks controller state, renderer resources, or gameplay decisions.
Actual ItemStacks continue through Minecraft's item renderer. Imported locators
and YSM attachment groups provide pose alignment; optional adapters may supply the external item's
render context and safe transforms.

Planned YSM-specific per-profile JSON/KubeJS rules will enable, disable, or override imported
animations, controllers, attachments, mod providers and feature fallbacks, with validated atomic
reload. The generic avatar scripting API exists, but the current YSM update branch bypasses its
per-profile script hooks; the Java provider SPI does not by itself expose these controls to
KubeJS. Provider input and controller evaluation are local visual data only.

Remote characters already run their own YSM controllers and owner input snapshots. Appearance
configuration/action-pack sharing does not distribute RWYM geometry, textures or audio; receiving
clients need the matching model asset installed locally. Authored configuration forms and
`v.roaming.*` synchronization are not implemented by ordinary per-owner `v.*` variables.

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
- [x] Authored controllers replace their own fallback slots; body categories are isolated from dedicated `fp_arm` controllers and unsupported extra categories.
- [x] Controller-local animation completion, item ID/tag/type predicates, logical hands and native vehicle inputs have offline regression coverage.
- [x] Reimport preserves same-asset calibration; deleting profiles prunes unused mappings while protecting shared model references.
- [ ] Character-page edit handoff and portable Workbench save/reopen have not yet been driven end to end in a client.
- [ ] Workbench preview cannot yet create and drive a YSM runtime instance; generic same-name baked-clip preview does not execute YSM controllers.
- [ ] Full animation/controller/instruction/sound semantics are exercised by fixture tests.
- [x] TaCZ adapter, per-gun/category fallback and optional-dependency guards have implementation and offline coverage; user feedback confirmed corrected held-item direction.
- [ ] TaCZ gunpack ADS/recoil/reload/optics, multiplayer and active-shader behavior have complete client acceptance coverage.
- [x] Windows x64 C++ shared library and real-fixture/C ABI tests run locally; resource packaging is wired.
- [x] Six-platform native build/CTest/architecture/package gates and both version builds passed in CI run `45500656880` for `16a971e`.
- [ ] The latest item-predicate/controller-completion/reimport/retirement changes have a fresh client acceptance after packaging.

## Verification Boundaries

The 2026-11-25 offline regression passed 439 tests on NeoForge 1.21.1 and 433 on Forge 1.20.1,
with three skipped tests per target. Both complete builds, integration-source compilation,
module-boundary checks and bundled-JAR checks passed against the pinned AssimpLoader revision.
This covers the new item predicates, controller completion, native vehicle input, reimport
preservation and deferred cache retirement. These additions have not been retested in a client;
the earlier client observations below do not establish their visual or multiplayer behavior.

[CI run 45500656880](https://github.com/MineRealms/RigWorks/actions/runs/45500656880) completed
successfully for `16a971e`: Windows, Linux and macOS each built and executed the native fixture
round-trip and C ABI tests on x64 and ARM64 runners. Artifact collection checked all six library
architectures; both Forge 1.20.1 and NeoForge 1.21.1 build/package jobs passed. These results verify
converter execution and packaging, not Minecraft gameplay on each platform.

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

The previous real client run used the then-current `rigid-v3` cache and produced two meshes, 274 palette entries,
559 rendered passes, and a maximum sampled palette delta of 25.388 after a 10,004 ms texture-settle
period. It also asserts the imported profile carries the render-cache orientation (`modelYawDegrees`
0, `headLook.forward` +Z); the third-person-front capture shows the model's face, where the earlier
run showed the back of its head because the workbench's 180 degree default yaw was applied. The
client acceptance passed at 60 FPS. Iris was installed but no shader pack was active, so the
Iris/Oculus shader-cache path remains unverified. The current render-cache revision is newer than
that run and requires a fresh client acceptance before these numbers are reused.

Still outside complete client coverage: YSM Workbench edit/save/reopen from the character page,
multiple independent remote YSM players, the full TaCZ gunpack/shader matrix, all source layouts,
and Minecraft execution on the other native target platforms. Runtime feature gaps include live
YSM controller preview in Workbench, sound playback, model-owned Molang functions, most optional
mod adapters, complete variants/item replacement rules, and source-specific material/physics
behavior. Preserve opaque data for later support; retaining sections does not implement them.

See [portable asset details](YSM_PORTABLE_BRIDGE_HANDOFF.md) and
[native build and platform gates](../../native/rigworks_ysm/README.md).
