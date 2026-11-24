package cn.minerealms.rigworks.character.client;

import cn.minerealms.assimploader.animation.PoseGraph;
import cn.minerealms.assimploader.animation.SkinBindPose;
import cn.minerealms.assimploader.core.AssimpModelLoader;
import cn.minerealms.assimploader.core.ModelImportMode;
import cn.minerealms.rigworks.character.core.CharacterProfile;
import cn.minerealms.rigworks.character.core.VanillaPoseSelection;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VanillaNativeItemCompatibilityTest {
    @Test void nativeGunOwnsBothArmsWhileLocomotionAndHeadMasksRemainSelected() {
        var authored = new VanillaPoseSelection(true, true, false, .25f, .4f, .7f, 0, 0, .3f, .8f);
        var effective = VanillaPlayerPoseDriver.itemSelection(authored, true);
        assertFalse(effective.attack());
        assertTrue(effective.items());
        assertEquals(1, effective.leftArm());
        assertEquals(1, effective.rightArm());
        assertEquals(authored.root(), effective.root());
        assertEquals(authored.body(), effective.body());
        assertEquals(authored.head(), effective.head());
        assertEquals(authored.leftLeg(), effective.leftLeg());
        assertEquals(authored.rightLeg(), effective.rightLeg());
        assertSame(authored, VanillaPlayerPoseDriver.itemSelection(authored, false));
        var disabled = new VanillaPoseSelection(false, false, false, 0, 0, 0, 0, 0, 0, 0);
        assertSame(disabled, VanillaPlayerPoseDriver.itemSelection(disabled, true),
                "Explicitly disabled native sampling must remain disabled");
    }

    @Test void realFbxGunFallbackReplacesBothAuthoredArmTracksWithoutChangingTheLegs() throws Exception {
        var root = Path.of("src/main/resources/assets/rigworks/models/character/block_player");
        var scene = new AssimpModelLoader().loadModel(root.resolve("block_player.fbx"), ModelImportMode.DYNAMIC);
        CharacterProfile profile;
        try (var reader = Files.newBufferedReader(root.resolve("profile.json"))) { profile = CharacterProfile.read(reader); }
        var locals = new LinkedHashMap<>(SkinBindPose.locals(scene));
        locals.compute("Thigh.L", (node, local) -> new Matrix4f(local).rotateX(.6f));
        locals.compute("Thigh.R", (node, local) -> new Matrix4f(local).rotateX(-.6f));
        locals.compute("UpperArm.L", (node, local) -> new Matrix4f(local).rotateX(.8f));
        locals.compute("UpperArm.R", (node, local) -> new Matrix4f(local).rotateX(-.8f));
        var leftLeg = new Matrix4f(locals.get("Thigh.L"));
        var rightLeg = new Matrix4f(locals.get("Thigh.R"));
        var before = new PoseGraph(scene.getRootNode(), locals);
        var leftHand = before.position("Hand.L");
        var rightHand = before.position("Hand.R");
        var pose = new VanillaPoseRetargeter(profile, scene);
        var selection = VanillaPlayerPoseDriver.itemSelection(
                new VanillaPoseSelection(true, false, false, 0, 0, 0, 0, 0, 0, 0), true);
        pose.update(new Matrix4f(), Map.of(
                "left_upper_arm", new VanillaPoseRetargeter.Part(-1.4f, .8f, 0, 0, 0, 0),
                "right_upper_arm", new VanillaPoseRetargeter.Part(-1.4f, -.3f, 0, 0, 0, 0),
                "left_forearm", new VanillaPoseRetargeter.Part(0, 0, 0, 0, 0, 0),
                "right_forearm", new VanillaPoseRetargeter.Part(0, 0, 0, 0, 0, 0)), selection);
        pose.apply(scene, locals, 0);
        var after = new PoseGraph(scene.getRootNode(), locals);
        assertTrue(after.position("Hand.L").distance(leftHand) > .1f);
        assertTrue(after.position("Hand.R").distance(rightHand) > .1f);
        assertEquals(leftLeg, locals.get("Thigh.L"));
        assertEquals(rightLeg, locals.get("Thigh.R"));
        assertTrue(VanillaPlayerPoseDriver.supportsNativeItemPose(profile));
    }
}
