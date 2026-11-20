/*
 * Originally derived from YSM's client-side animation semantics (Apache-2.0).
 * Modified by MineRealms for RigWorks.
 */
package cn.minerealms.rigworks.ysm.runtime;

import cn.minerealms.assimploader.animation.PoseModifier;
import cn.minerealms.assimploader.model.AssimpNode;
import cn.minerealms.assimploader.model.AssimpScene;
import cn.minerealms.rigworks.ysm.model.YsmChannel;
import cn.minerealms.rigworks.ysm.model.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Applies YSM-authored clips to RigWorks bone nodes without requiring Assimp animation clips. */
public final class YsmPoseDriver implements PoseModifier, YsmControllerRuntime.AnimationSink {
    private final List<YsmModelSource.YsmAnimationView> clips;
    private final Map<String, Vec3> initialRotations = new LinkedHashMap<>();
    private final Map<String, ActiveClip> active = new LinkedHashMap<>();
    private final Map<String, AssimpNode> nodes = new LinkedHashMap<>();
    private AssimpScene indexedScene;
    private YsmExpressionRuntime.Context context;

    public YsmPoseDriver(List<YsmModelSource.YsmAnimationView> clips) {
        this(clips, List.of());
    }

    public YsmPoseDriver(List<YsmModelSource.YsmAnimationView> clips, List<YsmModelSource.YsmBoneView> bones) {
        this.clips = List.copyOf(clips == null ? List.of() : clips);
        for (var bone : bones) if (bone.initialRotation() != null)
            initialRotations.put(bone.name(), bone.initialRotation());
    }

    public void setContext(YsmExpressionRuntime.Context value) { context = value; }

    public YsmControllerRuntime.AnimationSink scoped(String category, String controllerId) {
        String group = category == null ? "" : category.toLowerCase(Locale.ROOT);
        String prefix = group + ":" + (controllerId == null || controllerId.isBlank() ? "ysm:controller" : controllerId);
        return new YsmControllerRuntime.AnimationSink() {
            @Override public void show(String clip, float weight) { showLayer(prefix, clip, weight); }
            @Override public void hide() { hideLayer(prefix); }
            @Override public boolean hasClip(String clip) { return resolve(clip, group) != null; }
            @Override public void showLayer(String layerId, String clip, float weight) {
                YsmPoseDriver.this.showLayer(prefix + "/" + layerId, clip, weight, group);
            }
            @Override public void hideLayer(String layerId) { YsmPoseDriver.this.hideLayer(prefix + "/" + layerId); }
        };
    }

    @Override public void show(String clip, float weight) { showLayer("ysm:direct", clip, weight); }

    @Override public void hide() { active.clear(); }

    @Override public boolean hasClip(String clip) { return resolve(clip, "") != null; }

    @Override public void showLayer(String layerId, String clip, float weight) {
        showLayer(layerId, clip, weight, "");
    }

    private void showLayer(String layerId, String clipName, float weight, String category) {
        YsmModelSource.YsmAnimationView clip = resolve(clipName, category);
        if (clip == null || !Float.isFinite(weight) || weight <= 0) {
            active.remove(layerId);
            return;
        }
        ActiveClip previous = active.get(layerId);
        if (previous != null && previous.clip == clip) {
            previous.weight = Math.min(1f, weight);
            return;
        }
        active.put(layerId, new ActiveClip(clip, Math.min(1f, weight)));
    }

    @Override public void hideLayer(String layerId) { active.remove(layerId); }

    private YsmModelSource.YsmAnimationView resolve(String name, String category) {
        if (name == null || name.isBlank()) return null;
        YsmModelSource.YsmAnimationView first = null;
        for (YsmModelSource.YsmAnimationView clip : clips) {
            if (!name.equals(clip.name())) continue;
            if (first == null) first = clip;
            if (!category.isBlank() && category.equalsIgnoreCase(clip.category())) return clip;
        }
        return first;
    }

    @Override public Category category() { return Category.MOTION; }

    @Override public void apply(AssimpScene scene, Map<String, Matrix4f> localOverrides, double deltaSeconds) {
        if (scene == null || active.isEmpty()) return;
        if (indexedScene != scene) {
            nodes.clear();
            index(scene.getRootNode());
            indexedScene = scene;
        }
        Map<String, PoseContribution> contributions = new LinkedHashMap<>();
        for (ActiveClip state : active.values()) {
            YsmModelSource.YsmAnimationView clip = state.clip;
            double duration = Math.max(0, clip.durationSeconds());
            if (state.finished) continue;
            if (Double.isFinite(deltaSeconds) && deltaSeconds > 0) {
                state.timeSeconds += deltaSeconds;
                if (duration > 0 && clip.loopMode() == 1)
                    state.timeSeconds %= duration;
                else if (duration > 0 && state.timeSeconds > duration && clip.loopMode() != 3) {
                    state.finished = true;
                    continue;
                } else if (duration > 0)
                    state.timeSeconds = Math.min(state.timeSeconds, duration);
            }
            for (YsmChannel channel : clip.channels()) {
                if (channel.frames() == null || channel.frames().isEmpty()) continue;
                float[] sampled = YsmKeyframeSampler.sample(channel, state.timeSeconds, context);
                PoseContribution pose = contributions.computeIfAbsent(channel.bone(), ignored -> new PoseContribution());
                float weight = state.weight;
                switch (channel.path().toLowerCase(Locale.ROOT)) {
                    case "rotation" -> {
                        pose.rotation[0] += sampled[0] * weight;
                        pose.rotation[1] += sampled[1] * weight;
                        pose.rotation[2] += sampled[2] * weight;
                    }
                    case "position" -> {
                        pose.position[0] += sampled[0] * weight;
                        pose.position[1] += sampled[1] * weight;
                        pose.position[2] += sampled[2] * weight;
                    }
                    case "scale" -> {
                        pose.scale[0] *= 1f + (sampled[0] - 1f) * weight;
                        pose.scale[1] *= 1f + (sampled[1] - 1f) * weight;
                        pose.scale[2] *= 1f + (sampled[2] - 1f) * weight;
                    }
                    default -> { }
                }
            }
        }

        for (var entry : contributions.entrySet()) {
            String bone = entry.getKey();
            AssimpNode node = nodes.get(bone);
            if (node == null) continue;
            PoseContribution pose = entry.getValue();
            Matrix4f bind = node.getTransformation();
            Vector3f position = bind.getTranslation(new Vector3f());
            Vector3f scale = bind.getScale(new Vector3f());
            Vec3 authored = initialRotations.get(bone);
            Vector3f rotation = authored == null ? bind.getEulerAnglesZYX(new Vector3f()).mul(-1, -1, 1)
                    : new Vector3f(authored.x(), authored.y(), authored.z());
            // YSM adds Euler deltas before Rz * Ry * Rx; position is pixel-space before rotation.
            Matrix4f transformed = new Matrix4f().translation(position.x - pose.position[0],
                    position.y + pose.position[1], position.z - pose.position[2])
                    .rotateZYX(rotation.z + pose.rotation[2], -rotation.y - pose.rotation[1], -rotation.x - pose.rotation[0])
                    .scale(scale.x * pose.scale[0], scale.y * pose.scale[1], scale.z * pose.scale[2]);
            localOverrides.put(bone, transformed);
        }
    }

    private void index(AssimpNode node) {
        if (node == null) return;
        nodes.putIfAbsent(node.getName(), node);
        for (AssimpNode child : node.getChildren()) index(child);
    }

    @Override public void reset() { active.clear(); }

    public List<String> activeLayers() { return new ArrayList<>(active.keySet()); }

    private static final class ActiveClip {
        final YsmModelSource.YsmAnimationView clip;
        double timeSeconds;
        float weight;
        boolean finished;

        ActiveClip(YsmModelSource.YsmAnimationView clip, float weight) {
            this.clip = clip;
            this.weight = weight;
        }
    }

    private static final class PoseContribution {
        final float[] rotation = new float[3];
        final float[] position = new float[3];
        final float[] scale = {1, 1, 1};
    }
}
