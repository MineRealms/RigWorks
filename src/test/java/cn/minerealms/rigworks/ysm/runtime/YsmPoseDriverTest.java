package cn.minerealms.rigworks.ysm.runtime;

import cn.minerealms.assimploader.animation.AnimationState;
import cn.minerealms.assimploader.animation.PoseSnapshot;
import cn.minerealms.assimploader.core.AssimpModelLoader;
import cn.minerealms.assimploader.core.ModelImportMode;
import cn.minerealms.assimploader.model.AssimpScene;
import cn.minerealms.rigworks.ysm.model.*;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class YsmPoseDriverTest {
    @Test
    void animatedHierarchyMatchesYsmPositionPivotEulerAndScaleOrder() throws Exception {
        var model = model();
        var view = YsmModelSource.reflective().view(model);
        AssimpScene scene = scene(model);
        var driver = new YsmPoseDriver(view.animations(), view.bones());
        driver.show("action", 1);
        Map<String, Matrix4f> overrides = new LinkedHashMap<>();
        driver.apply(scene, overrides, 0);
        var snapshot = PoseSnapshot.capture(1, scene, overrides);

        // Independent YSM reference: absolute pivots, pre-rotation position, summed Euler angles.
        Matrix4f parent = new Matrix4f().translate(3, 10, -2).rotateZ(.4f).rotateY(-.3f).rotateX(.2f)
                .translate(-3, -10, 2);
        float radians = (float) (Math.PI / 180);
        Matrix4f expected = parent.translate(5 - 2, 16 + 3, 1 + 4)
                .rotateZ(.3f + 30 * radians).rotateY(.2f - 20 * radians).rotateX(-.1f - 10 * radians)
                .scale(1.2f, .8f, 1.1f).translate(-5, -16, -1);
        // A bone node lives at its pivot; the mesh child cancels that pivot.
        expected.translate(5, 16, 1);
        Matrix4f basis = new Matrix4f().scaling(1, 1, -1);
        assertMatrix(new Matrix4f(basis).mul(expected).mul(basis), snapshot.getNodeTransform("child"));
        assertFalse(snapshot.copyMeshInstances().isEmpty(), "the animated hierarchy must still submit meshes");
        assertTrue(snapshot.getBounds().maxY > snapshot.getBounds().minY);
    }

    @Test
    void loopHoldAndPlayOnceHaveDistinctCompletionBehavior() throws Exception {
        AssimpScene scene = scene(model());
        for (int mode : new int[]{0, 1, 2, 3}) {
            var driver = new YsmPoseDriver(List.of(clip("action", "main", mode,
                    List.of(channel("position", 2, 3, 4)))));
            driver.show("action", 1);
            var overrides = new LinkedHashMap<String, Matrix4f>();
            driver.apply(scene, overrides, 2);
            assertEquals(mode == 1 || mode == 3, overrides.containsKey("child"), "loop mode " + mode);
            driver.show("action", 1);
            overrides.clear();
            driver.apply(scene, overrides, .05);
            assertEquals(mode == 1 || mode == 3, overrides.containsKey("child"),
                    "a completed one-shot must not restart while its condition remains true");
            driver.hide();
            driver.show("action", 1);
            overrides.clear();
            driver.apply(scene, overrides, 0);
            assertTrue(overrides.containsKey("child"), "a new activation can restart the clip");
        }
    }

    @Test
    void sameNamedControllersInDifferentGroupsKeepIndependentLayers() {
        var driver = new YsmPoseDriver(List.of(
                clip("action", "main", 1, List.of()), clip("action", "tac", 1, List.of())));
        var main = driver.scoped("main", "controller");
        var tac = driver.scoped("tac", "controller");
        main.showLayer("slot", "action", 1);
        tac.showLayer("slot", "action", 1);
        assertEquals(2, driver.activeLayers().size());
        main.hideLayer("slot");
        assertEquals(1, driver.activeLayers().size());
        tac.hideLayer("slot");
        assertTrue(driver.activeLayers().isEmpty());
        assertFalse(driver.scoped("armor", "controller").hasClip("action"),
                "a missing category must not resolve to a same-named clip from another rig");
    }

    @Test
    void expressionChannelsReceiveTheirOwnAnimationTime() throws Exception {
        var expression = YsmTypedValue.expression("q.anim_time");
        var values = List.of(expression, YsmTypedValue.ofFloat(0), YsmTypedValue.ofFloat(0));
        var keyframe = new YsmKeyframe(0, List.of(), values, "linear", 0, false);
        var clip = clip("action", "main", 1,
                List.of(new YsmChannel("child", "position", List.of(keyframe))));
        var model = model();
        AssimpScene scene = scene(model);
        var driver = new YsmPoseDriver(List.of(clip), YsmModelSource.reflective().view(model).bones());
        driver.setContext(new YsmExpressionRuntime.MapContext(Map.of("q.time", 90d), Set.of()));
        driver.show("action", 1);

        var atStart = new LinkedHashMap<String, Matrix4f>();
        driver.apply(scene, atStart, 0);
        float initialX = atStart.get("child").m30();
        var later = new LinkedHashMap<String, Matrix4f>();
        driver.apply(scene, later, .25);

        assertNotNull(atStart.get("child"));
        assertNotNull(later.get("child"));
        assertEquals(.25f, initialX - later.get("child").m30(), 1e-5f);
    }

    @Test
    void timelineVariablesPersistAcrossQueryRefreshAndLoopBoundaries() throws Exception {
        var values = List.of(YsmTypedValue.expression("v.count"), YsmTypedValue.ofFloat(0), YsmTypedValue.ofFloat(0));
        var key = new YsmKeyframe(0, List.of(), values, "linear", 0, false);
        var clip = new YsmModelSource.YsmAnimationView("action", "main", 1, 1, 1,
                List.of(new YsmChannel("child", "position", List.of(key))),
                List.of(new YsmInstructionKeyframe(0, List.of("v.count=v.count+1")),
                        new YsmInstructionKeyframe(.5f, List.of("v.count=v.count+2"))), List.of());
        var driver = new YsmPoseDriver(List.of(clip), YsmModelSource.reflective().view(model()).bones());
        var context = new YsmVariableContext();
        context.bind(new YsmExpressionRuntime.MapContext(Map.of("q.speed", 0d), Set.of()));
        driver.setContext(context);
        driver.show("action", 1);
        AssimpScene scene = scene(model());
        var pose = new LinkedHashMap<String, Matrix4f>();
        driver.apply(scene, pose, 0);
        assertEquals(1, context.value("v.count"));
        driver.apply(scene, pose, .5);
        assertEquals(3, context.value("v.count"));
        context.bind(new YsmExpressionRuntime.MapContext(Map.of("q.speed", 4d), Set.of()));
        driver.apply(scene, pose, 0);
        assertEquals(3, context.value("v.count"), "paused/repeated evaluation must not refire events");
        driver.apply(scene, pose, 1);
        assertEquals(6, context.value("v.count"), "events must run again after a loop boundary");
        assertEquals(4, context.value("q.speed"));
    }

    @Test
    void locomotionReplacementKeepsPreAndPostParallelEvaluationOrder() throws Exception {
        var clips = List.of(orderClip("pre_parallel0", 1), orderClip("idle", 2),
                orderClip("walk", 4), orderClip("parallel0", 3));
        var driver = new YsmPoseDriver(clips, YsmModelSource.reflective().view(model()).bones());
        var runtime = new YsmDefaultAnimationController(clips, group -> driver.scoped(group, "default"));
        var variables = new YsmVariableContext();
        var queries = new YsmExpressionRuntime.MapContext(Map.of(), Set.of());
        variables.bind(queries);
        driver.setContext(variables);
        var pose = new LinkedHashMap<String, Matrix4f>();
        var scene = scene(model());
        runtime.update(cn.minerealms.rigworks.character.core.PlayerVisualInput.builder(0, 0).build(), variables, Map.of());
        driver.apply(scene, pose, 0);
        assertEquals(123, variables.value("v.order"));

        variables.setVariable("v.order", 0);
        runtime.update(cn.minerealms.rigworks.character.core.PlayerVisualInput.builder(1, .05).motion(1, 0, 0).build(), variables, Map.of());
        driver.apply(scene, pose, 0);
        assertEquals(143, variables.value("v.order"), "a replacement locomotion clip must still run before post-parallel");
    }

    private static YsmModelSource.YsmAnimationView orderClip(String name, int digit) {
        var values = List.of(YsmTypedValue.expression("v.order=v.order*10+" + digit),
                YsmTypedValue.ofFloat(0), YsmTypedValue.ofFloat(0));
        var key = new YsmKeyframe(0, List.of(), values, "linear", 0, false);
        return clip(name, "main", 1, List.of(new YsmChannel("child", "position", List.of(key))));
    }

    @Test
    void laterControllerOverridesItsPathsAndKeepsUnauthoredPaths() throws Exception {
        var first = clip("base", "main", 1, List.of(channel("rotation", 10, 20, 30),
                channel("position", 2, 3, 4), channel("scale", 2, 3, 4)));
        var second = clip("use", "main", 1, List.of(channel("rotation", 40, 50, 60)));
        var model = model();
        var scene = scene(model);
        var driver = new YsmPoseDriver(List.of(first, second), YsmModelSource.reflective().view(model).bones());
        driver.scoped("main", "player.main").show("base", 1);
        driver.scoped("main", "player.use").show("use", .5f);
        assertLocalPose(driver, scene, new float[]{20,25,30}, new float[]{2,3,4}, new float[]{2,3,4});
    }

    @Test
    void referencesInOneAuthoredControllerCombineBeforeReplacingEarlierController() throws Exception {
        var first = clip("base", "main", 1, List.of(channel("rotation", 100, 100, 100),
                channel("position", 100, 100, 100), channel("scale", 5, 5, 5)));
        var a = clip("a", "main", 1, List.of(channel("rotation", 10, 20, 30),
                channel("position", 2, 3, 4), channel("scale", 2, 2, 2)));
        var b = clip("b", "main", 1, List.of(channel("rotation", 4, 6, 8),
                channel("position", 4, 6, 8), channel("scale", 3, 3, 3)));
        var model = model();
        var scene = scene(model);
        var driver = new YsmPoseDriver(List.of(first,a,b), YsmModelSource.reflective().view(model).bones());
        driver.scoped("main", "player.main").show("base", 1);
        var use = driver.scoped("main", "player.use");
        use.showLayer("0", "a", 1);
        use.showLayer("1", "b", .5f);
        assertLocalPose(driver, scene, new float[]{12,23,34}, new float[]{4,6,8}, new float[]{4,4,4});
    }

    @Test
    void builtinLegacyParallelAddsOnlyRotation() throws Exception {
        var base = clip("idle", "main", 1, List.of(channel("rotation", 10, 20, 30),
                channel("position", 2, 3, 4), channel("scale", 2, 2, 2)));
        var parallel = clip("parallel0", "main", 1, List.of(channel("rotation", 5, 7, 9),
                channel("position", 4, 6, 8), channel("scale", 3, 3, 3)));
        var model = model();
        var scene = scene(model);
        var driver = new YsmPoseDriver(List.of(base,parallel), YsmModelSource.reflective().view(model).bones());
        var defaults = driver.scoped("main", "default");
        defaults.showLayer("ysm:default:main:main", "idle", 1);
        defaults.showLayer("ysm:default:main:parallel0", "parallel0", 1);
        assertLocalPose(driver, scene, new float[]{15,27,39}, new float[]{4,6,8}, new float[]{3,3,3});
    }

    @Test
    void authoredParallelAndBuiltinPreParallelUseNormalOverrideSemantics() throws Exception {
        var base = clip("idle", "main", 1, List.of(channel("rotation", 10, 20, 30),
                channel("position", 2, 3, 4), channel("scale", 2, 2, 2)));
        var parallel = clip("parallel0", "main", 1, List.of(channel("rotation", 5, 7, 9),
                channel("position", 4, 6, 8), channel("scale", 3, 3, 3)));
        var model = model();
        var scene = scene(model);
        var driver = new YsmPoseDriver(List.of(base,parallel), YsmModelSource.reflective().view(model).bones());
        var defaults = driver.scoped("main", "default");
        defaults.showLayer("ysm:default:main:main", "idle", 1);
        driver.scoped("main", "player.parallel_0").show("parallel0", 1);
        assertLocalPose(driver, scene, new float[]{5,7,9}, new float[]{4,6,8}, new float[]{3,3,3});

        driver.reset();
        defaults.showLayer("ysm:default:main:pre_parallel0", "parallel0", 1);
        defaults.showLayer("ysm:default:main:main", "idle", 1);
        assertLocalPose(driver, scene, new float[]{10,20,30}, new float[]{2,3,4}, new float[]{2,2,2});
    }

    private static void assertLocalPose(YsmPoseDriver driver, AssimpScene scene, float[] degrees,
                                         float[] position, float[] scale) {
        var overrides = new LinkedHashMap<String, Matrix4f>();
        driver.apply(scene, overrides, 0);
        assertLocalPose(overrides.get("child"), degrees, position, scale);
    }

    private static void assertLocalPose(Matrix4f actual, float[] degrees, float[] position, float[] scale) {
        float radians = (float) (Math.PI / 180);
        // This fixture's child bind pivot relative to its parent is (2, 6, -3) after the Z basis change.
        Matrix4f expected = new Matrix4f().translation(2-position[0], 6+position[1], -3-position[2])
                .rotateZYX(.3f+degrees[2]*radians, -.2f+degrees[1]*radians, .1f+degrees[0]*radians)
                .scale(scale[0], scale[1], scale[2]);
        assertMatrix(expected, actual);
    }

    @Test
    void stateTransitionFreezesPreviousPoseAndDelaysClipClockAndTimeline() throws Exception {
        var a = clip("a", "main", 1, List.of(channel("position", 2, 0, 0), channel("scale", 2, 2, 2)));
        var values = List.of(YsmTypedValue.expression("10+q.anim_time"), YsmTypedValue.ofFloat(0), YsmTypedValue.ofFloat(0));
        var position = new YsmChannel("child", "position", List.of(new YsmKeyframe(0, List.of(), values, "linear", 0, false)));
        var b = new YsmModelSource.YsmAnimationView("b", "main", 1, 2, 1,
                List.of(position, channel("scale", 4, 4, 4)),
                List.of(new YsmInstructionKeyframe(0, List.of("v.started=v.started+1"))), List.of());
        var scene = scene(model());
        var driver = new YsmPoseDriver(List.of(a,b), YsmModelSource.reflective().view(model()).bones());
        var variables = new YsmVariableContext();
        variables.bind(new YsmExpressionRuntime.MapContext(Map.of(), Set.of()));
        driver.setContext(variables);
        var sink = driver.scoped("main", "player.main");
        sink.showLayer("0", "a", 1);
        var pose = new LinkedHashMap<String, Matrix4f>();
        driver.apply(scene, pose, 0);
        sink.hideLayer("0");
        sink.transitionLayer("0", "b", 1, new YsmBlendTransition(.4f, List.of()), false);
        driver.apply(scene, pose, .05);
        assertLocalPose(pose.get("child"), new float[]{0,0,0}, new float[]{2,0,0}, new float[]{2,2,2});
        assertEquals(0, variables.value("v.started"));
        driver.apply(scene, pose, .2);
        assertLocalPose(pose.get("child"), new float[]{0,0,0}, new float[]{6,0,0}, new float[]{3,3,3});
        assertEquals(0, variables.value("v.started"));
        driver.apply(scene, pose, .2);
        assertLocalPose(pose.get("child"), new float[]{0,0,0}, new float[]{10,0,0}, new float[]{4,4,4});
        assertEquals(1, variables.value("v.started"));
        driver.apply(scene, pose, .1);
        assertLocalPose(pose.get("child"), new float[]{0,0,0}, new float[]{10.1f,0,0}, new float[]{4,4,4});
    }

    @Test
    void sourceBlendCurveContainsRemainingOldPoseWeight() throws Exception {
        var a = clip("a", "main", 1, List.of(channel("position", 2, 0, 0)));
        var b = clip("b", "main", 1, List.of(channel("position", 10, 0, 0)));
        var scene = scene(model());
        var driver = new YsmPoseDriver(List.of(a,b), YsmModelSource.reflective().view(model()).bones());
        var sink = driver.scoped("main", "player.main");
        sink.showLayer("0", "a", 1);
        var pose = new LinkedHashMap<String, Matrix4f>();
        driver.apply(scene, pose, 0);
        sink.hideLayer("0");
        var curve = new YsmBlendTransition(null, List.of(new YsmBlendPoint(0,1),
                new YsmBlendPoint(.5f,.25f), new YsmBlendPoint(1,0)));
        sink.transitionLayer("0", "b", 1, curve, false);
        driver.apply(scene, pose, 0);
        driver.apply(scene, pose, .25);
        assertLocalPose(pose.get("child"), new float[]{0,0,0}, new float[]{5,0,0}, new float[]{1,1,1});
        driver.apply(scene, pose, .25);
        assertLocalPose(pose.get("child"), new float[]{0,0,0}, new float[]{8,0,0}, new float[]{1,1,1});
    }

    @Test
    void rotationTransitionUsesNormalizedQuaternionBlendWithBindRotation() throws Exception {
        var a = clip("a", "main", 1, List.of(channel("rotation", 10, 170, 30)));
        var b = clip("b", "main", 1, List.of(channel("rotation", 40, -170, 60)));
        var scene = scene(model());
        var driver = new YsmPoseDriver(List.of(a,b), YsmModelSource.reflective().view(model()).bones());
        var sink = driver.scoped("main", "player.main");
        sink.showLayer("0", "a", 1);
        var pose = new LinkedHashMap<String, Matrix4f>();
        driver.apply(scene, pose, 0);
        Matrix4f from = new Matrix4f(pose.get("child"));
        sink.hideLayer("0");
        sink.transitionLayer("0", "b", 1, new YsmBlendTransition(1f, List.of()), true);
        driver.apply(scene, pose, 0);
        driver.apply(scene, pose, .5);
        Matrix4f blended = new Matrix4f(pose.get("child"));
        driver.apply(scene, pose, .5);
        Matrix4f to = new Matrix4f(pose.get("child"));
        var expectedRotation = from.getUnnormalizedRotation(new org.joml.Quaternionf())
                .nlerp(to.getUnnormalizedRotation(new org.joml.Quaternionf()), .5f);
        Matrix4f expected = new Matrix4f().translation(2,6,-3).rotate(expectedRotation);
        assertMatrix(expected, blended);
    }

    @Test
    void authoredReferencesEvaluateInNumericSourceOrderAndScopedHideClearsThem() throws Exception {
        var driver = new YsmPoseDriver(List.of(orderClip("a0",1), orderClip("a2",2), orderClip("a10",3)),
                YsmModelSource.reflective().view(model()).bones());
        var variables = new YsmVariableContext();
        variables.bind(new YsmExpressionRuntime.MapContext(Map.of(), Set.of()));
        driver.setContext(variables);
        var sink = driver.scoped("main", "player.main");
        sink.showLayer("ysm:controller:player.main:0", "a0", 1);
        sink.showLayer("ysm:controller:player.main:10", "a10", 1);
        sink.showLayer("ysm:controller:player.main:2", "a2", 1);
        driver.apply(scene(model()), new LinkedHashMap<>(), 0);
        assertEquals(123, variables.value("v.order"));
        sink.hide();
        assertTrue(driver.activeLayers().isEmpty());
    }

    @Test
    void realImportedModelPreparesAndEvaluatesItsAuthoredParallelExpressions() throws Exception {
        byte[] source;
        try (var input = getClass().getResourceAsStream("/ysm/Flandre_Scarlet.ysm")) {
            assertNotNull(input); source = input.readAllBytes();
        }
        var asset = cn.minerealms.rigworks.ysm.asset.RwymReader.read(
                cn.minerealms.rigworks.ysm.asset.RwymNative.convert(source));
        var model = asset.mainModel();
        var view = YsmModelSource.reflective().view(model);
        assertEquals(134, view.animations().stream().mapToInt(c -> c.instructions().stream()
                .mapToInt(e -> e.instructions().size()).sum()).sum());
        var driver = new YsmPoseDriver(view.animations(), view.bones());
        var variables = new YsmVariableContext();
        var queries = new YsmExpressionRuntime.MapContext(Map.of("q.ysm.is_close_eyes", 0d,
                "q.ysm.head_pitch", 20d, "q.ground_speed", 0d), Set.of());
        variables.bind(queries);
        driver.setContext(variables);
        var parallel = view.animations().stream().filter(c -> c.category().equals("main")
                && c.name().startsWith("parallel")).findFirst().orElseThrow();
        assertTrue(driver.scoped("main", "fixture").hasClip(parallel.name()));
        driver.scoped("main", "fixture").show(parallel.name(), 1);
        AssimpScene scene = new AssimpModelLoader().loadModelFromMemory("runtime.glb",
                YsmGlbWriter.toRuntimeGlb(model), ModelImportMode.DYNAMIC);
        var overrides = new LinkedHashMap<String, Matrix4f>();
        for (int tick = 0; tick < 100; tick++) {
            queries.setQuery("life_time", tick / 20d);
            driver.apply(scene, overrides, .05);
            for (Matrix4f matrix : overrides.values())
                for (float component : matrix.get(new float[16])) assertTrue(Float.isFinite(component));
        }
        assertFalse(overrides.isEmpty(), "authored expressions must actually contribute a pose");
        assertFalse(PoseSnapshot.capture(1, scene, overrides).copyMeshInstances().isEmpty());
    }

    @Test
    void clearingImportedModelDetachesItsPoseModifier() throws Exception {
        AnimationState animation = new AnimationState("fixture", scene(model()));
        animation.initialize();
        var driver = new YsmPoseDriver(List.of());
        animation.addModifier("ysm:pose", driver);
        var field = YsmRuntimeBridge.class.getDeclaredField("boundAnimation");
        field.setAccessible(true);
        field.set(null, animation);
        YsmRuntimeBridge.installed(null);
        assertNull(animation.getModifier("ysm:pose"));
        animation.close();
    }

    private static YsmModel model() {
        var face = new YsmFace(new float[]{0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0},
                new float[]{0, 0, 1}, new int[]{0, 0, 1, 1}, "");
        var cube = new YsmCube(Vec3.ZERO, new Vec3(1, 1, 0), Vec3.ZERO, Vec3.ZERO,
                Map.of("front", face), 0, false);
        var bones = List.of(new YsmBone("parent", "", new Vec3(3, 10, -2), new Vec3(.2f, -.3f, .4f), List.of()),
                new YsmBone("child", "parent", new Vec3(5, 16, 1), new Vec3(-.1f, .2f, .3f), List.of(cube)));
        var channels = List.of(channel("rotation", 10, 20, 30), channel("position", 2, 3, 4),
                channel("scale", 1.2f, .8f, 1.1f));
        var animation = new YsmAnimation("action", 1, true, channels);
        return new YsmModel(9, "fixture", bones, Map.of(), Map.of("action", animation), List.of());
    }

    private static YsmChannel channel(String path, float x, float y, float z) {
        return new YsmChannel("child", path, List.of(new YsmKeyframe(0, new float[]{x, y, z}, "linear")));
    }

    private static YsmModelSource.YsmAnimationView clip(String name, String category, int loop, List<YsmChannel> channels) {
        return new YsmModelSource.YsmAnimationView(name, category, 1, 1, loop, channels);
    }

    private static AssimpScene scene(YsmModel model) throws Exception {
        AssimpScene scene = new AssimpModelLoader().loadModelFromMemory("model.glb", YsmGlbWriter.toGlb(model), ModelImportMode.DYNAMIC);
        assertNotNull(scene);
        return scene;
    }

    private static void assertMatrix(Matrix4f expected, Matrix4f actual) {
        assertNotNull(actual);
        assertArrayEquals(expected.get(new float[16]), actual.get(new float[16]), 2e-4f);
    }
}
