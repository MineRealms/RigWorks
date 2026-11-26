package cn.minerealms.rigworks.mmd;

import cn.minerealms.assimploader.animation.AnimationState;
import cn.minerealms.assimploader.animation.PoseGraph;
import cn.minerealms.assimploader.character.physics.BulletCharacterPhysics;
import cn.minerealms.assimploader.character.physics.CharacterPhysicsAsset;
import cn.minerealms.assimploader.character.solver.CharacterRigDefinition;
import cn.minerealms.assimploader.model.AssimpScene;
import cn.minerealms.rigworks.character.core.CharacterVisualConfig;

import java.io.IOException;
import java.io.StringReader;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;

/** Checks the ordinary playback contracts before an import changes the selected avatar. */
public final class MmdConvertedAssets {
    private MmdConvertedAssets() {}

    public static void validate(AssimpScene scene, CharacterVisualConfig visual) throws IOException {
        try (var animation = new AnimationState("mmd-import-validation", scene)) {
            animation.initialize();
            var graph = new PoseGraph(scene.getRootNode(), new HashMap<>());
            for (var bone : visual.profile().humanoid().entrySet())
                if (!graph.contains(bone.getValue().node())) throw new IOException("MMD role " + bone.getKey() + " has no node: " + bone.getValue().node());
            var clips = new HashSet<>(Arrays.asList(animation.getAvailableAnimations()));
            for (var action : visual.profile().animations().entrySet())
                if (!clips.contains(action.getValue())) throw new IOException("MMD action " + action.getKey() + " has no clip: " + action.getValue());
            if (visual.rig() != null) CharacterRigDefinition.read(new StringReader(visual.rig())).install(animation);
            if (visual.physics() != null) {
                var definition = CharacterPhysicsAsset.read(new StringReader(visual.physics()), scene);
                var physics = new BulletCharacterPhysics(definition, scene, (float) visual.profile().scale());
                physics.close();
            }
            animation.update(0);
            for (var mesh : scene.getMeshes()) {
                var data = mesh.getSkinnedData();
                if (data == null) throw new IOException("Converted MMD mesh has no skin: " + mesh.getName());
                for (float value : data.getBoneWeights()) if (!Float.isFinite(value) || value < 0)
                    throw new IOException("Converted MMD mesh has an invalid skin weight: " + mesh.getName());
                var palette = animation.getPoseSnapshot().getBoneTransforms(mesh);
                if (palette == null) throw new IOException("Converted MMD mesh has no pose palette: " + mesh.getName());
                for (var transform : palette) if (transform == null || !transform.isFinite())
                    throw new IOException("Converted MMD mesh has a non-finite pose: " + mesh.getName());
            }
        } catch (RuntimeException invalid) {
            throw new IOException("Converted MMD rig cannot initialize: " + invalid.getMessage(), invalid);
        }
    }
}
