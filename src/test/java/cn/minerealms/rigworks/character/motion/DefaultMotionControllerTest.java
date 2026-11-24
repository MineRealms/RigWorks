package cn.minerealms.rigworks.character.motion;

import cn.minerealms.assimploader.animation.AnimationState;
import cn.minerealms.assimploader.animation.PoseGraph;
import cn.minerealms.assimploader.animation.SkinBindPose;
import cn.minerealms.assimploader.core.AssimpModelLoader;
import cn.minerealms.assimploader.core.ModelImportMode;
import cn.minerealms.assimploader.model.AssimpScene;
import cn.minerealms.rigworks.character.core.CharacterProfile;
import cn.minerealms.rigworks.character.core.PlayerActionState;
import cn.minerealms.rigworks.character.core.PlayerVisualInput;
import cn.minerealms.rigworks.character.core.VanillaPoseSelection;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.joml.Matrix4f;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DefaultMotionControllerTest {
    private static final Path ROOT = Path.of("src/main/resources/assets/rigworks/models/character/block_player");
    private static AssimpScene scene() throws Exception {
        return new AssimpModelLoader().loadModel(ROOT.resolve("block_player.fbx"), ModelImportMode.DYNAMIC);
    }
    private static CharacterProfile profile() throws Exception {
        try (var reader = Files.newBufferedReader(ROOT.resolve("profile.json"))) { return CharacterProfile.read(reader); }
    }
    private static PlayerActionState action(String use, boolean water) {
        return new PlayerActionState("standing", PlayerActionState.AirPhase.GROUNDED,
                false, water, water, false, false, false, 0, use, true, true);
    }

    @Test void acceptedSwingPhaseRetriggersImmediatelyWithoutWaitingForAuthoredDuration() throws Exception {
        var scene = scene();
        try (var animation = new AnimationState("default-clock", scene);
             var controller = new DefaultMotionController(profile(), animation)) {
            var sword = new DefaultMotionController.Items("empty", "sword", "right", 0, false, false);
            for (int generation = 0; generation < 8; generation++) {
                var input = PlayerVisualInput.builder(generation, generation * .05)
                        .swingAction(generation, true, .2f).build();
                var nativeParts = controller.update(input, sword);
                animation.update(.05f);
                assertFalse(controller.lastSelection().nativeFallback(), controller.lastSelection().diagnostic());
                assertEquals("attack_sword", controller.lastSelection().right());
                assertEquals(.2f, controller.lastSelection().swingPhase(), 1e-6);
                assertFalse(nativeParts.attack(), "Vanilla attack must not be applied a second time");
            }
            controller.update(PlayerVisualInput.builder(8, .4).swingAction(7, true, .85f).build(), sword);
            assertEquals(.85f, controller.lastSelection().swingPhase(), 1e-6);
        }
    }

    @Test void transitionsBetweenMovementWaterAndSpecialNativeStates() throws Exception {
        var scene = scene();
        try (var animation = new AnimationState("default-states", scene);
             var controller = new DefaultMotionController(profile(), animation)) {
            var inputs = List.of(
                    PlayerVisualInput.builder(0, 0).build(),
                    PlayerVisualInput.builder(1, .1).motion(4, 0, 0).build(),
                    PlayerVisualInput.builder(2, .2).motion(6, 0, 0).sprinting(true).build(),
                    PlayerVisualInput.builder(3, .3).crouching(true).build(),
                    PlayerVisualInput.builder(4, .4).crouching(true).motion(1, 0, 0).build(),
                    PlayerVisualInput.builder(5, .5).onGround(false).jumping(true).build(),
                    PlayerVisualInput.builder(6, .6).actionState(action("none", true)).build(),
                    PlayerVisualInput.builder(7, .7).swimming(true).build());
            String[] names = {"stand", "walk", "run", "crouch", "crouch_walk", "jump", "swim", "swimming"};
            for (int i = 0; i < inputs.size(); i++) {
                controller.update(inputs.get(i)); animation.update(.05f);
                assertEquals(names[i], controller.lastSelection().base());
            }
            assertEquals(VanillaPoseSelection.ALL, controller.update(PlayerVisualInput.builder(8, .8).elytra(true).build()));
            assertTrue(controller.lastSelection().nativeFallback());
            assertEquals(VanillaPoseSelection.ALL, controller.update(PlayerVisualInput.builder(9, .9).riding(true).build()));
            controller.update(PlayerVisualInput.builder(10, 1).build());
            assertFalse(controller.lastSelection().nativeFallback());
            assertEquals("stand", controller.lastSelection().base());
        }
    }

    @Test void worksWithoutTargetClipsAndRestoresNativeItemUseWhenThePresetHasNoSuchAction() throws Exception {
        var scene = new AssimpModelLoader().loadModel(Path.of("src/test/resources/motion/humanoid-no-clips.gltf"), ModelImportMode.DYNAMIC);
        assertTrue(scene.getAnimations().isEmpty());
        try (var animation = new AnimationState("default-no-target-clips", scene);
             var controller = new DefaultMotionController(profile(), animation)) {
            controller.update(PlayerVisualInput.builder(0, 0).motion(3, 0, 0).build());
            controller.update(PlayerVisualInput.builder(1, .2).motion(3, 0, 0).build());
            animation.update(.05f);
            assertNotNull(animation.getPoseSnapshot());
            assertEquals("walk", controller.lastSelection().base());
            assertFalse(controller.lastSelection().nativeFallback());
            var use = controller.update(PlayerVisualInput.builder(2, .3).usingItem(true).actionState(action("eat", false)).build());
            assertEquals(1, use.leftArm()); assertEquals(1, use.rightArm()); assertTrue(use.items());
        }
    }

    @Test void allExportedClipsPreserveBindLengthsAndReachTargetsOnTheRealFbx() throws Exception {
        var scene = scene(); var profile = profile(); var library = DefaultMotionLibrary.builtin();
        var retargeter = new DefaultMotionRetargeter(profile, scene, library);
        var bind = new PoseGraph(scene.getRootNode(), SkinBindPose.locals(scene));
        var sample = library.newPose();
        Map<String, Matrix4f> locals = new LinkedHashMap<>();
        for (String id : library.clipNames()) {
            var clip = library.clip(id);
            for (int frame = 0; frame < clip.frameCount(); frame += 6) {
                clip.sample(frame / (double)library.fps(), sample);
                boolean paired = id.startsWith("bow_") || id.startsWith("crossbow_");
                retargeter.apply(locals, sample, paired ? sample : null, paired ? sample : null,
                        true, true, paired, false, false);
                var graph = new PoseGraph(scene.getRootNode(), locals);
                String context = id + " frame " + frame;
                for (String side : List.of(".L", ".R")) {
                    for (String[] segment : new String[][]{{"UpperArm", "Forearm"}, {"Forearm", "Hand"}, {"Thigh", "Shin"}, {"Shin", "Foot"}}) {
                        float expected = bind.position(segment[0] + side).distance(bind.position(segment[1] + side));
                        float actual = graph.position(segment[0] + side).distance(graph.position(segment[1] + side));
                        assertEquals(expected, actual, 2e-4, context + " " + segment[0] + side);
                    }
                }
                assertTrue(locals.values().stream().allMatch(Matrix4f::isFinite), context);
                assertTrue(retargeter.maxLegError() < 1e-3, context + " ankle error=" + retargeter.maxLegError());
                if (paired) assertTrue(retargeter.maxHandError() < 1e-3, context + " grip error=" + retargeter.maxHandError());
            }
        }
    }

    @Test void unsupportedHumanoidRetainsNativeAndDoesNotCorruptTheSkeleton() throws Exception {
        var scene = scene(); var source = profile();
        var profile = new CharacterProfile(source.model(), source.scale(), Map.of(), source.animations(),
                null, source.blendSeconds(), source.movementThreshold(), source.headLook(), null,
                source.modelYawDegrees(), null, source.timing());
        try (var animation = new AnimationState("default-incomplete", scene);
             var controller = new DefaultMotionController(profile, animation)) {
            assertEquals(VanillaPoseSelection.ALL, controller.update(PlayerVisualInput.builder(0, 0).build()));
            assertTrue(controller.lastSelection().diagnostic().contains("calibrate"));
            assertEquals(VanillaPoseSelection.ALL, controller.update(PlayerVisualInput.builder(1, .05).build()));
        }
    }

    @Test void explicitRolesRetargetRenamedBonesDifferentProportionsAndReflectedRootAxes() throws Exception {
        var source = profile(); var library = DefaultMotionLibrary.builtin();
        for (float rootScale : new float[]{.65f, -1.4f}) {
            JsonObject asset = JsonParser.parseString(Files.readString(Path.of("src/test/resources/motion/humanoid-no-clips.gltf"))).getAsJsonObject();
            var mapped = new LinkedHashMap<String, CharacterProfile.HumanoidBone>();
            source.humanoid().forEach((role, bone) -> mapped.put(role,
                    new CharacterProfile.HumanoidBone("custom_" + bone.node(), bone.referenceRotation())));
            for (var entry : asset.getAsJsonArray("nodes")) {
                var node = entry.getAsJsonObject();
                String name = node.get("name").getAsString();
                node.addProperty("name", "custom_" + name);
                JsonArray translation = node.getAsJsonArray("translation");
                if (name.startsWith("Shin") || name.startsWith("Foot")) translation.set(1, new com.google.gson.JsonPrimitive(-.56));
                if (name.startsWith("Forearm") || name.startsWith("Hand")) translation.set(1, new com.google.gson.JsonPrimitive(-.21));
                if (name.equals("Hips")) {
                    node.remove("translation");
                    var matrix = new JsonArray();
                    for (float component : new Matrix4f().translation(0, 1.12f, 0).rotateY(.8f)
                            .scale(rootScale, Math.abs(rootScale), Math.abs(rootScale)).get(new float[16])) matrix.add(component);
                    node.add("matrix", matrix);
                }
            }
            var scene = new AssimpModelLoader().loadModelFromMemory("custom.gltf", asset.toString().getBytes(StandardCharsets.UTF_8), ModelImportMode.DYNAMIC);
            assertNotNull(scene);
            var profile = new CharacterProfile(source.model(), source.scale(), mapped, Map.of(), null, 0,
                    source.movementThreshold(), source.headLook(), null, source.modelYawDegrees(), null, source.timing());
            var retargeter = new DefaultMotionRetargeter(profile, scene, library);
            var bind = new PoseGraph(scene.getRootNode(), SkinBindPose.locals(scene));
            var sample = library.newPose();
            Map<String, Matrix4f> locals = new LinkedHashMap<>();
            for (String id : library.clipNames()) {
                var clip = library.clip(id);
                for (int frame = 0; frame < clip.frameCount(); frame += 15) {
                    clip.sample(frame / (double)library.fps(), sample);
                    boolean paired = id.startsWith("bow_") || id.startsWith("crossbow_");
                    retargeter.apply(locals, sample, paired ? sample : null, paired ? sample : null, true, true, paired, false, false);
                    var graph = new PoseGraph(scene.getRootNode(), locals);
                    for (String side : List.of(".L", ".R")) {
                        for (String[] segment : new String[][]{{"UpperArm", "Forearm"}, {"Forearm", "Hand"}, {"Thigh", "Shin"}, {"Shin", "Foot"}}) {
                            String a = "custom_" + segment[0] + side, b = "custom_" + segment[1] + side;
                            assertEquals(bind.position(a).distance(bind.position(b)), graph.position(a).distance(graph.position(b)), 2e-4,
                                    id + " scale=" + rootScale + " " + a);
                        }
                    }
                    assertTrue(retargeter.maxLegError() < 1e-3, id + " ankle=" + retargeter.maxLegError());
                    if (paired) assertTrue(retargeter.maxHandError() < 1e-3, id + " wrist=" + retargeter.maxHandError());
                }
            }
        }
    }

    @Test void reenteringDefaultDoesNotBlendFromThePreviousCrouch() throws Exception {
        var scene = scene(); var profile = profile();
        try (var animation = new AnimationState("default-reentry", scene);
             var controller = new DefaultMotionController(profile, animation)) {
            controller.update(PlayerVisualInput.builder(0, 0).crouching(true).build());
            animation.update(.05f);
            float crouched = animation.getPoseSnapshot().getNodeTransform("Hips").m31();
            controller.reset();
            controller.update(PlayerVisualInput.builder(1, 1).build());
            animation.update(.05f);
            float standing = animation.getPoseSnapshot().getNodeTransform("Hips").m31();
            assertTrue(standing > crouched + .05f);
        }
    }

    @Test void offhandBowDoesNotMirrorOrTakeOverTheOtherHandsToolAttack() throws Exception {
        var scene = scene(); var profile = profile();
        Matrix4f expected;
        try (var animation = new AnimationState("default-right-tool", scene);
             var controller = new DefaultMotionController(profile, animation)) {
            controller.update(PlayerVisualInput.builder(0, 0).swingAction(0, true, .65f).build(),
                    new DefaultMotionController.Items("empty", "tool", "right", 0, false, false));
            animation.update(.05f);
            expected = animation.getPoseSnapshot().getNodeTransform("Forearm.R");
        }
        try (var animation = new AnimationState("default-offhand-bow", scene);
             var controller = new DefaultMotionController(profile, animation)) {
            controller.update(PlayerVisualInput.builder(0, 0).swingAction(0, true, .65f).build(),
                    new DefaultMotionController.Items("bow", "tool", "right", 0, false, false));
            animation.update(.05f);
            assertEquals("item_hold_left", controller.lastSelection().left());
            assertEquals("attack_tool_right", controller.lastSelection().right());
            assertTrue(expected.equals(animation.getPoseSnapshot().getNodeTransform("Forearm.R"), 1e-5f));
        }
    }

    @Test void aliasesForTheSameNodeApplyNativeCalibrationOnlyOnce() throws Exception {
        var scene = new AssimpModelLoader().loadModel(Path.of("src/test/resources/motion/humanoid-no-clips.gltf"), ModelImportMode.DYNAMIC);
        var source = profile();
        var humanoid = new LinkedHashMap<>(source.humanoid());
        var rotation = new Quaternionf().rotationY(.3f);
        var bone = new CharacterProfile.HumanoidBone("Hips", CharacterProfile.Rotation.IDENTITY,
                new CharacterProfile.Rotation(rotation.x, rotation.y, rotation.z, rotation.w));
        humanoid.put("hips", bone); humanoid.put("body", bone);
        var profile = new CharacterProfile(source.model(), source.scale(), humanoid, Map.of(), null, 0,
                source.movementThreshold(), source.headLook(), null, source.modelYawDegrees(), null, source.timing());
        var library = DefaultMotionLibrary.builtin();
        var retargeter = new DefaultMotionRetargeter(profile, scene, library);
        var locals = new LinkedHashMap<String, Matrix4f>();
        retargeter.apply(locals, library.bind(), null, null, true, true, false, false, false);
        var expected = new Matrix3f(SkinBindPose.locals(scene).get("Hips")).rotate(rotation);
        assertTrue(expected.equals(new Matrix3f(new PoseGraph(scene.getRootNode(), locals).global("Hips")), 1e-5f));
    }
}
