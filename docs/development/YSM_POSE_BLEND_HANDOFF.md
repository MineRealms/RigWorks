# YSM pose and state blend verification

Worktree: `RigWorks-ysm-native-import`. Date: 2026-11-21.

## Controller composition

`YsmPoseDriver` now distinguishes animation references inside one controller from separate
controllers. References in one authored state add weighted rotations/positions and multiply
weighted scales. The completed controller then replaces only the bone paths that it authored.
An ordinary later controller no longer adds its entire pose to an earlier controller.

The built-in legacy post-parallel slots retain additive rotation only. Position and scale still
replace prior values. Authored post-parallel controllers and pre-parallel slots use ordinary
replacement. References evaluate by numeric source index, including indices above nine.

Reference anchors in `H:/MinecraftMods/OpenYSM/common/src/main/java/`:

- `com/elfmcys/yesstevemodel/geckolib3/core/processor/AnimationProcessor.java`, `applyTransform`.
- `com/elfmcys/yesstevemodel/geckolib3/core/controller/AnimationControllerRuntime.java`,
  `BoneBlendState.getRotation/getPosition/getScale`.
- `com/elfmcys/yesstevemodel/geckolib3/core/controller/controllers/PlayerAnimationController.java`,
  legacy `parallel` factory versus authored `CompositeAnimationController`.

## State activation transition

`YsmControllerRuntime.AnimationSink.transitionLayer` is implemented by the pose sink.
An activation captures the previous final YSM pose once per controller. This immutable snapshot
is shared by all new references of that controller and released when their transition completes.
Only bones present in incoming animation tracks are copied; unrelated bones do not allocate
transition snapshots.
The current clip is sampled at time zero while the source transition is active. Clip time and
timeline events begin only after the transition duration; excess elapsed time advances the clip.

Linear durations are seconds. Source curve points contain the remaining old-pose weight, so
interpolation progress is `1 - output`. Position and scale interpolate linearly. Rotation uses
normalized quaternion interpolation of ZYX rotations including the authored bind rotation.
The reference OpenYSM runtime uses that interpolation for both values of
`blend_via_shortest_path`; the flag remains preserved in metadata.

The Euler extraction uses the source's explicit formula rather than JOML 1.10.5's ZYX helper.
The locally inspected 1.10.5 helper has a positive Y-square term in its X denominator; the
source formula and the corrected rotation reconstruction require a negative term.

Relevant source classes: `AnimationControllerInstance`, `TransitionPoint`,
`LinearKeyframeInterpolator`, `TicksInterpolator`, `MathUtil`.

## Verification

Compiled the current driver, controller, model view and test with JDK 21, then executed
`YsmPoseDriverTest` through the cached JUnit launcher and Windows x64 native converter.
All 16 tests passed. This includes the real Flandre RWYM fixture and focused tests for:

- Source pivot/Euler hierarchy, loop/hold/once behavior and separate categories.
- Persistent timeline variables and per-clip animation time.
- Controller replacement, same-controller reference aggregation and legacy parallel behavior.
- Frozen old pose, delayed clip clock and delayed initial timeline events.
- Nonlinear source transition curves and quaternion interpolation across the angle boundary.
- Numeric reference order and clearing every layer owned by a scoped sink.

No Gradle process or Minecraft client was started by this subtask. Parent integration must rerun
the normal dual-version build and runtime checks. The ignored standalone runner is
`build/standalone-pose-tests/run.ps1`; it uses the current 1.21.1 cached class outputs.

This does not implement source sound playback, automatic ending transitions for completed
one-shots, or source constant-keyframe reset fades. These remain separate runtime work.
