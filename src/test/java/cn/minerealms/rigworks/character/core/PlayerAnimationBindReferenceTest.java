package cn.minerealms.rigworks.character.core;

import cn.minerealms.assimploader.animation.AnimationState;
import cn.minerealms.assimploader.animation.PoseGraph;
import cn.minerealms.assimploader.animation.SkinBindPose;
import cn.minerealms.assimploader.core.AssimpModelLoader;
import cn.minerealms.assimploader.core.ModelImportMode;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlayerAnimationBindReferenceTest {
    private static final Path ROOT = Path.of("src/main/resources/assets/rigworks/models/character/block_player");

    @Test void headAndTravelUseSkinBindHeadingWhenNodeTreeContainsAnExportedPose() throws Exception {
        var scene = new AssimpModelLoader().loadModel(ROOT.resolve("block_player.fbx"), ModelImportMode.DYNAMIC);
        CharacterProfile source;
        try (var reader = Files.newBufferedReader(ROOT.resolve("profile.json"))) { source = CharacterProfile.read(reader); }
        var original = new PoseGraph(scene.getRootNode(), SkinBindPose.locals(scene));
        var meshNodes = new PoseGraph(scene.getRootNode(), Map.of());
        var headFrame = new Matrix4f().translationRotateScale(original.position("Head"),
                new Quaternionf().rotationY((float)Math.PI / 2), original.global("Head").getScale(new Vector3f()));
        int offsets = 0;
        for (var mesh : scene.getMeshes()) {
            var offset = mesh.getBoneOffsets().get("Head");
            if (offset != null) {
                offset.set(headFrame).invert().mul(meshNodes.global(scene.getNodeNameForMesh(mesh)));
                offsets++;
            }
        }
        assertTrue(offsets > 0, "Fixture must reconstruct Head from an inverse bind matrix");
        scene.findNodeByName("Head").getTransformation().rotateX(.65f).rotateZ(.4f);
        var bind = new PoseGraph(scene.getRootNode(), SkinBindPose.locals(scene));
        var localForward = bind.global("Head").invert().transformDirection(new Vector3f(0, 0, 1)).normalize();
        assertTrue(Math.abs(localForward.x) > .99f, "Fixture uses a nontrivial local face axis");
        var rawForward = new PoseGraph(scene.getRootNode(), Map.of()).global("Head")
                .transformDirection(new Vector3f(localForward)).normalize();
        assertTrue(rawForward.distance(new Vector3f(0, 0, 1)) > .25f,
                "Exported node pose must disagree with the actual skin bind heading");
        var look = new CharacterProfile.HeadLook(true,
                new CharacterProfile.Direction(localForward.x, localForward.y, localForward.z), 75, 60);
        var profile = new CharacterProfile(source.model(), source.scale(), source.humanoid(), source.animations(),
                source.physicsDefinition(), source.blendSeconds(), source.movementThreshold(), look,
                source.solverDefinition(), source.modelYawDegrees(), source.equipmentDefinition(), source.timing());

        for (String action : new String[]{"idle", "swim", "elytra"})
            for (float yaw : new float[]{-40, 0, 40}) for (float pitch : new float[]{-50, 0, 50}) {
                try (var state = new AnimationState("skin-bind-reference", scene);
                     var controller = new PlayerAnimationController(profile, state)) {
                    controller.updateCompatible(PlayerVisualInput.builder(0, 0).orientation(20, 20 + yaw, pitch)
                            .swimming(action.equals("swim")).elytra(action.equals("elytra"))
                            .onGround(action.equals("idle")).build());
                    assertEquals(action, controller.lastSelection().requested().key());
                    assertEquals(action, controller.lastSelection().selected().key());
                    state.update(.2f);
                    var pose = state.getPoseSnapshot();
                    var expected = direction(yaw, pitch);
                    var face = pose.getNodeTransform("Head").transformDirection(new Vector3f(localForward)).normalize();
                    assertEquals(0, face.distance(expected), 1e-4,
                            "Skin-bound face must match view heading/pitch: " + action + " yaw=" + yaw + " pitch=" + pitch);
                    if (!action.equals("idle")) {
                        var axis = pose.getNodeTransform("Head").getTranslation(new Vector3f())
                                .sub(pose.getNodeTransform("Hips").getTranslation(new Vector3f())).normalize();
                        assertEquals(0, axis.distance(expected), 1e-4,
                                "Authored travel must use the same skin-bound forward axis: " + action);
                    }
                }
            }
    }

    private static Vector3f direction(float yaw, float pitch) {
        float y = (float)Math.toRadians(yaw), p = (float)Math.toRadians(pitch);
        return new Vector3f(-(float)Math.sin(y) * (float)Math.cos(p), -(float)Math.sin(p),
                (float)Math.cos(y) * (float)Math.cos(p));
    }
}
