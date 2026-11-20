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
