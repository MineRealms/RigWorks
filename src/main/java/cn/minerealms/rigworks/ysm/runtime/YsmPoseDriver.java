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
import java.util.IdentityHashMap;
import java.util.HashMap;

/** Applies YSM-authored clips to RigWorks bone nodes without requiring Assimp animation clips. */
public final class YsmPoseDriver implements PoseModifier, YsmControllerRuntime.AnimationSink {
    private final Map<String, Vec3> initialRotations;
    private final Map<String, ActiveClip> active = new LinkedHashMap<>();
    private final List<ActiveClip> orderedActive = new ArrayList<>();
    private boolean layerOrderChanged;
    private final Map<String, AssimpNode> nodes = new LinkedHashMap<>();
    private final Map<YsmModelSource.YsmAnimationView, PreparedClip> prepared;
    private final Map<String, PoseContribution> contributions = new LinkedHashMap<>();
    private final List<PoseContribution> touchedContributions = new ArrayList<>();
    private final List<PoseContribution> controllerContributions = new ArrayList<>();
    private final Map<String, List<YsmModelSource.YsmAnimationView>> clipsByName;
    private final float[] sampled = new float[3];
    private final ClipContext clipContext = new ClipContext();
    private AssimpScene indexedScene;
    private YsmExpressionRuntime.Context context;

    public YsmPoseDriver(List<YsmModelSource.YsmAnimationView> clips) {
        this(clips, List.of());
    }

    public YsmPoseDriver(List<YsmModelSource.YsmAnimationView> clips, List<YsmModelSource.YsmBoneView> bones) {
        this(new Definition(clips, bones));
    }

    public YsmPoseDriver(Definition definition) {
        initialRotations = definition.initialRotations;
        prepared = definition.prepared;
        clipsByName = definition.clipsByName;
    }

    /** Prepared off the client thread, then shared by independent character instances. */
    public static final class Definition {
        private final Map<String, Vec3> initialRotations = new LinkedHashMap<>();
        private final Map<YsmModelSource.YsmAnimationView, PreparedClip> prepared = new IdentityHashMap<>();
        private final Map<String, List<YsmModelSource.YsmAnimationView>> clipsByName = new HashMap<>();

        public Definition(List<YsmModelSource.YsmAnimationView> clips, List<YsmModelSource.YsmBoneView> bones) {
        for (var clip : clips == null ? List.<YsmModelSource.YsmAnimationView>of() : clips) {
            clipsByName.computeIfAbsent(clip.name(), ignored -> new ArrayList<>()).add(clip);
            List<PreparedChannel> tracks = new ArrayList<>(clip.channels().size());
            for (YsmChannel channel : clip.channels()) {
                if (channel.frames() == null || channel.frames().isEmpty()) continue;
                int path = switch (channel.path().toLowerCase(Locale.ROOT)) {
                    case "rotation" -> 0;
                    case "position" -> 1;
                    case "scale" -> 2;
                    default -> -1;
                };
                if (path >= 0) tracks.add(new PreparedChannel(YsmKeyframeSampler.prepare(channel), path));
            }
            List<PreparedEvent> events = new ArrayList<>(clip.instructions().size());
            for (var event : clip.instructions()) {
                if (!Float.isFinite(event.time()) || event.time() < 0)
                    throw new IllegalArgumentException("YSM timeline time must be finite and nonnegative");
                var programs = event.instructions().stream().map(YsmExpressionRuntime::compile).toList();
                events.add(new PreparedEvent(event.time(), programs));
            }
            events.sort(java.util.Comparator.comparingDouble(PreparedEvent::time));
            var weights = clip.blendWeight().stream().map(value -> YsmExpressionRuntime.compile(
                    value.isFloat() ? Float.toString(value.floatValue()) : value.rawString())).toList();
            prepared.put(clip, new PreparedClip(List.copyOf(tracks), List.copyOf(events), weights));
        }
        clipsByName.replaceAll((name, matching) -> List.copyOf(matching));
        for (var bone : bones) if (bone.initialRotation() != null)
            initialRotations.put(bone.name(), bone.initialRotation());
        }
    }

    public void setContext(YsmExpressionRuntime.Context value) { context = value; }

    public YsmControllerRuntime.AnimationSink scoped(String category, String controllerId) {
        String group = category == null ? "" : category.toLowerCase(Locale.ROOT);
        String controller = controllerId == null || controllerId.isBlank() ? "ysm:controller" : controllerId;
        String prefix = group + ":" + controller;
        int order = YsmDefaultAnimationController.slotOrder(controller);
        boolean defaultController = "default".equals(controller);
        return new YsmControllerRuntime.AnimationSink() {
            @Override public void show(String clip, float weight) { showLayer(prefix, clip, weight); }
            @Override public void hide() { hideLayer(prefix); }
            @Override public boolean hasClip(String clip) { return resolve(clip, group) != null; }
            @Override public void showLayer(String layerId, String clip, float weight) {
                String slot = defaultController ? layerId.substring(layerId.lastIndexOf(':') + 1) : controller;
                int layerOrder = defaultController ? YsmDefaultAnimationController.slotOrder(slot) : order;
                String owner = defaultController ? prefix + "/" + layerId : prefix;
                boolean additiveRotation = defaultController && slot.startsWith("parallel")
                        && !slot.startsWith("pre_parallel");
                YsmPoseDriver.this.showLayer(prefix + "/" + layerId, clip, weight, group, layerOrder,
                        owner, additiveRotation);
            }
            @Override public void hideLayer(String layerId) { YsmPoseDriver.this.hideLayer(prefix + "/" + layerId); }
        };
    }

    @Override public void show(String clip, float weight) { showLayer("ysm:direct", clip, weight); }

    @Override public void hide() {
        active.clear();
        orderedActive.clear();
        layerOrderChanged = false;
    }

    @Override public boolean hasClip(String clip) { return resolve(clip, "") != null; }

    @Override public void showLayer(String layerId, String clip, float weight) {
        showLayer(layerId, clip, weight, "", YsmDefaultAnimationController.slotOrder(layerId), layerId, false);
    }

    private void showLayer(String layerId, String clipName, float weight, String category, int order,
                           String controller, boolean additiveRotation) {
        YsmModelSource.YsmAnimationView clip = resolve(clipName, category);
        if (clip == null || !Float.isFinite(weight) || weight <= 0) {
            hideLayer(layerId);
            return;
        }
        ActiveClip previous = active.get(layerId);
        if (previous != null && previous.clip == clip) {
            previous.weight = Math.min(1f, weight);
            return;
        }
        active.put(layerId, new ActiveClip(layerId, order, controller, additiveRotation, clip, Math.min(1f, weight)));
        layerOrderChanged = true;
    }

    @Override public void hideLayer(String layerId) {
        if (active.remove(layerId) != null) layerOrderChanged = true;
    }

    private YsmModelSource.YsmAnimationView resolve(String name, String category) {
        if (name == null || name.isBlank()) return null;
        List<YsmModelSource.YsmAnimationView> matching = clipsByName.get(name);
        if (matching == null || matching.isEmpty()) return null;
        if (category.isBlank()) return matching.get(0);
        for (YsmModelSource.YsmAnimationView clip : matching)
            if (category.equalsIgnoreCase(clip.category())) return clip;
        return null;
    }

    @Override public Category category() { return Category.MOTION; }

    @Override public void apply(AssimpScene scene, Map<String, Matrix4f> localOverrides, double deltaSeconds) {
        if (scene == null || active.isEmpty()) return;
        if (indexedScene != scene) {
            nodes.clear();
            contributions.clear();
            touchedContributions.clear();
            controllerContributions.clear();
            index(scene.getRootNode());
            indexedScene = scene;
        }
        for (PoseContribution contribution : touchedContributions) contribution.clear();
        touchedContributions.clear();
        controllerContributions.clear();
        ensureLayerOrder();
        String currentController = null;
        boolean additiveRotation = false;
        for (ActiveClip state : orderedActive) {
            if (!state.controller.equals(currentController)) {
                applyController(additiveRotation);
                currentController = state.controller;
                additiveRotation = state.additiveRotation;
            }
            YsmModelSource.YsmAnimationView clip = state.clip;
            double duration = Math.max(0, clip.durationSeconds());
            if (state.finished) continue;
            PreparedClip preparedClip = prepared.get(clip);
            if (!state.started) {
                events(preparedClip, Double.NEGATIVE_INFINITY, 0);
                state.started = true;
            }
            if (Double.isFinite(deltaSeconds) && deltaSeconds > 0) {
                double previous = state.timeSeconds;
                double next = previous + deltaSeconds;
                if (duration > 0 && Double.isFinite(duration) && clip.loopMode() == 1 && next >= duration) {
                    events(preparedClip, previous, duration);
                    long cycles = (long) (next / duration);
                    // Bound catch-up work after a long pause; replay the most recent cycles.
                    for (long cycle = 1; cycle < Math.min(cycles, 16); cycle++)
                        events(preparedClip, Double.NEGATIVE_INFINITY, duration);
                    state.timeSeconds = next % duration;
                    events(preparedClip, Double.NEGATIVE_INFINITY, state.timeSeconds);
                } else {
                    state.timeSeconds = duration > 0 ? Math.min(next, duration) : next;
                    events(preparedClip, previous, state.timeSeconds);
                }
                if (duration > 0 && clip.loopMode() != 1 && next > duration && clip.loopMode() != 3) {
                    state.finished = true;
                    continue;
                }
            }
            clipContext.set(context, state.timeSeconds);
            float weight = state.weight;
            for (var program : preparedClip.weight()) weight *= (float) program.evaluate(clipContext);
            weight = Float.isFinite(weight) ? Math.max(0, Math.min(1, weight)) : 0;
            if (weight == 0) continue;
            for (PreparedChannel channel : preparedClip.tracks()) {
                channel.track().sample(state.timeSeconds, clipContext, sampled);
                AssimpNode node = nodes.get(channel.track().bone());
                if (node == null) continue;
                PoseContribution pose = contributions.computeIfAbsent(channel.track().bone(),
                        ignored -> new PoseContribution(node, initialRotations.get(channel.track().bone())));
                if (!pose.active) touchedContributions.add(pose);
                pose.active = true;
                if (pose.controllerPaths == 0) controllerContributions.add(pose);
                pose.controllerPaths |= 1 << channel.path();
                switch (channel.path()) {
                    case 0 -> {
                        pose.controllerRotation[0] += sampled[0] * weight;
                        pose.controllerRotation[1] += sampled[1] * weight;
                        pose.controllerRotation[2] += sampled[2] * weight;
                    }
                    case 1 -> {
                        pose.controllerPosition[0] += sampled[0] * weight;
                        pose.controllerPosition[1] += sampled[1] * weight;
                        pose.controllerPosition[2] += sampled[2] * weight;
                    }
                    case 2 -> {
                        pose.controllerScale[0] *= 1f + (sampled[0] - 1f) * weight;
                        pose.controllerScale[1] *= 1f + (sampled[1] - 1f) * weight;
                        pose.controllerScale[2] *= 1f + (sampled[2] - 1f) * weight;
                    }
                    default -> { }
                }
            }
        }
        applyController(additiveRotation);

        for (PoseContribution pose : touchedContributions) {
            String bone = pose.bone;
            Vector3f position = pose.bindPosition, scale = pose.bindScale, rotation = pose.bindRotation;
            // YSM adds Euler deltas before Rz * Ry * Rx; position is pixel-space before rotation.
            Matrix4f transformed = pose.matrix.translation(position.x - pose.position[0],
                    position.y + pose.position[1], position.z - pose.position[2])
                    .rotateZYX(rotation.z + pose.rotation[2], -rotation.y - pose.rotation[1], -rotation.x - pose.rotation[0])
                    .scale(scale.x * pose.scale[0], scale.y * pose.scale[1], scale.z * pose.scale[2]);
            localOverrides.put(bone, transformed);
        }
    }

    // Source animation references combine within one controller; controllers replace only authored paths.
    private void applyController(boolean additiveRotation) {
        for (PoseContribution pose : controllerContributions) {
            if ((pose.controllerPaths & 1) != 0) for (int axis = 0; axis < 3; axis++)
                pose.rotation[axis] = additiveRotation ? pose.rotation[axis] + pose.controllerRotation[axis]
                        : pose.controllerRotation[axis];
            if ((pose.controllerPaths & 2) != 0) System.arraycopy(pose.controllerPosition, 0, pose.position, 0, 3);
            if ((pose.controllerPaths & 4) != 0) System.arraycopy(pose.controllerScale, 0, pose.scale, 0, 3);
            pose.clearController();
        }
        controllerContributions.clear();
    }

    private void index(AssimpNode node) {
        if (node == null) return;
        nodes.putIfAbsent(node.getName(), node);
        for (AssimpNode child : node.getChildren()) index(child);
    }

    @Override public void reset() { hide(); }

    public List<String> activeLayers() {
        ensureLayerOrder();
        return orderedActive.stream().map(state -> state.layerId).toList();
    }

    private void ensureLayerOrder() {
        if (!layerOrderChanged) return;
        orderedActive.clear();
        orderedActive.addAll(active.values());
        orderedActive.sort(java.util.Comparator.comparingInt((ActiveClip state) -> state.order)
                .thenComparing(state -> state.layerId));
        layerOrderChanged = false;
    }

    private record PreparedChannel(YsmKeyframeSampler.PreparedTrack track, int path) {}
    private record PreparedEvent(double time, List<YsmExpressionRuntime.Program> instructions) {}
    private record PreparedClip(List<PreparedChannel> tracks, List<PreparedEvent> events,
                                List<YsmExpressionRuntime.Program> weight) {}

    private void events(PreparedClip clip, double after, double through) {
        for (PreparedEvent event : clip.events()) {
            if (event.time() > through) break;
            if (event.time() <= after) continue;
            clipContext.set(context, event.time());
            for (var program : event.instructions()) program.evaluate(clipContext);
        }
    }

    private static final class ClipContext implements YsmExpressionRuntime.Context {
        private YsmExpressionRuntime.Context delegate;
        private double animationTime;

        void set(YsmExpressionRuntime.Context delegate, double animationTime) {
            this.delegate = delegate;
            this.animationTime = animationTime;
        }

        @Override public double value(String name) {
            if ("q.anim_time".equals(name) || "anim_time".equals(name)) return animationTime;
            return delegate == null ? 0 : delegate.value(name);
        }

        @Override public boolean has(String name) {
            return "q.anim_time".equals(name) || "anim_time".equals(name) || delegate != null && delegate.has(name);
        }

        @Override public boolean matches(String token) { return delegate != null && delegate.matches(token); }
        @Override public boolean mayMatchFunction(String name) { return delegate != null && delegate.mayMatchFunction(name); }
        @Override public void setVariable(String name, double value) {
            if (delegate != null) delegate.setVariable(name, value);
        }
    }

    private static final class ActiveClip {
        final String layerId;
        final int order;
        final String controller;
        final boolean additiveRotation;
        final YsmModelSource.YsmAnimationView clip;
        double timeSeconds;
        float weight;
        boolean finished;
        boolean started;

        ActiveClip(String layerId, int order, String controller, boolean additiveRotation,
                   YsmModelSource.YsmAnimationView clip, float weight) {
            this.layerId = layerId;
            this.order = order;
            this.controller = controller;
            this.additiveRotation = additiveRotation;
            this.clip = clip;
            this.weight = weight;
        }
    }

    private static final class PoseContribution {
        final String bone;
        final Vector3f bindPosition, bindScale, bindRotation;
        final Matrix4f matrix = new Matrix4f();
        boolean active;
        final float[] rotation = new float[3];
        final float[] position = new float[3];
        final float[] scale = {1, 1, 1};
        int controllerPaths;
        final float[] controllerRotation = new float[3];
        final float[] controllerPosition = new float[3];
        final float[] controllerScale = {1, 1, 1};

        PoseContribution(AssimpNode node, Vec3 authored) {
            bone = node.getName();
            Matrix4f bind = node.getTransformation();
            bindPosition = bind.getTranslation(new Vector3f());
            bindScale = bind.getScale(new Vector3f());
            bindRotation = authored == null ? bind.getEulerAnglesZYX(new Vector3f()).mul(-1, -1, 1)
                    : new Vector3f(authored.x(), authored.y(), authored.z());
        }

        void clear() {
            active = false;
            java.util.Arrays.fill(rotation, 0);
            java.util.Arrays.fill(position, 0);
            java.util.Arrays.fill(scale, 1);
            clearController();
        }

        void clearController() {
            controllerPaths = 0;
            java.util.Arrays.fill(controllerRotation, 0);
            java.util.Arrays.fill(controllerPosition, 0);
            java.util.Arrays.fill(controllerScale, 1);
        }
    }
}
