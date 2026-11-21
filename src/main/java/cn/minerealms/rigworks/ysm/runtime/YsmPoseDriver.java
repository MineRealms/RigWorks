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
import cn.minerealms.rigworks.ysm.model.YsmBlendTransition;
import cn.minerealms.rigworks.ysm.model.YsmBlendPoint;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
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
    private final Map<String, ControllerTransition> pendingTransitions = new HashMap<>();
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
            String location = clip.category() + "/" + clip.name();
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
                if (path >= 0) tracks.add(new PreparedChannel(YsmKeyframeSampler.prepare(channel, location), path));
            }
            List<PreparedEvent> events = new ArrayList<>(clip.instructions().size());
            for (var event : clip.instructions()) {
                if (!Float.isFinite(event.time()) || event.time() < 0)
                    throw new IllegalArgumentException("YSM timeline time must be finite and nonnegative");
                var programs = event.instructions().stream().map(source -> YsmExpressionRuntime.compileImported(
                        source, location + " timeline time=" + event.time())).toList();
                events.add(new PreparedEvent(event.time(), programs));
            }
            events.sort(java.util.Comparator.comparingDouble(PreparedEvent::time));
            var weights = clip.blendWeight().stream().map(value -> YsmExpressionRuntime.compileImported(
                    value.isFloat() ? Float.toString(value.floatValue()) : value.rawString(), location + " blend weight")).toList();
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
            @Override public void hide() {
                if (active.entrySet().removeIf(entry -> entry.getKey().startsWith(prefix + "/")))
                    layerOrderChanged = true;
            }
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
            @Override public void transitionLayer(String layerId, String clip, float weight,
                                                   YsmBlendTransition transition, boolean shortestPath) {
                // The reference runtime nlerps ZYX rotations for either value of this retained source flag.
                showLayer(layerId, clip, weight);
                YsmPoseDriver.this.beginTransition(prefix + "/" + layerId, transition);
            }
        };
    }

    @Override public void show(String clip, float weight) { showLayer("ysm:direct", clip, weight); }

    @Override public void hide() {
        active.clear();
        orderedActive.clear();
        pendingTransitions.clear();
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

    private void beginTransition(String layerId, YsmBlendTransition source) {
        ActiveClip clip = active.get(layerId);
        if (clip == null) return;
        clip.timeSeconds = 0;
        clip.started = clip.finished = false;
        clip.transition = null;
        if (source == null) return;
        var transition = pendingTransitions.get(clip.controller);
        if (transition == null || !transition.source.equals(source)) {
            BlendCurve curve = new BlendCurve(source);
            if (curve.duration <= 0) return;
            Map<String, FrozenPose> frozen = new HashMap<>();
            transition = new ControllerTransition(source, curve, frozen);
            pendingTransitions.put(clip.controller, transition);
        }
        for (PreparedChannel channel : prepared.get(clip.clip).tracks()) {
            String bone = channel.track().bone();
            PoseContribution pose = contributions.get(bone);
            if (pose != null && pose.active && !transition.frozen.containsKey(bone))
                transition.frozen.put(bone, new FrozenPose(pose));
        }
        clip.transition = transition;
        clip.transitionSeconds = 0;
        clip.transitionStarted = false;
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
        pendingTransitions.clear();
        ensureLayerOrder();
        String currentController = null;
        boolean additiveRotation = false;
        ControllerTransition currentTransition = null;
        float transitionProgress = 1;
        for (ActiveClip state : orderedActive) {
            if (!state.controller.equals(currentController)) {
                applyController(additiveRotation, currentTransition, transitionProgress);
                currentController = state.controller;
                additiveRotation = state.additiveRotation;
                currentTransition = null;
                transitionProgress = 1;
            }
            YsmModelSource.YsmAnimationView clip = state.clip;
            double duration = Math.max(0, clip.durationSeconds());
            if (state.finished) continue;
            PreparedClip preparedClip = prepared.get(clip);
            double clipAdvance = Double.isFinite(deltaSeconds) && deltaSeconds > 0 ? deltaSeconds : 0;
            boolean beginningTransition = false;
            if (state.transition != null) {
                ControllerTransition transition = state.transition;
                double previous = state.transitionSeconds;
                if (state.transitionStarted) state.transitionSeconds += clipAdvance;
                else state.transitionStarted = true;
                double durationSeconds = transition.curve.duration;
                if (Math.abs(state.transitionSeconds - durationSeconds) < 1e-7)
                    state.transitionSeconds = durationSeconds;
                clipAdvance = Math.max(0, state.transitionSeconds - durationSeconds)
                        - Math.max(0, previous - durationSeconds);
                if (state.transitionSeconds < durationSeconds) {
                    beginningTransition = true;
                    currentTransition = transition;
                    transitionProgress = transition.curve.progress(state.transitionSeconds);
                } else state.transition = null;
            }
            if (!beginningTransition && !state.started) {
                events(preparedClip, Double.NEGATIVE_INFINITY, 0);
                state.started = true;
            }
            if (clipAdvance > 0) {
                double previous = state.timeSeconds;
                double next = previous + clipAdvance;
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
        applyController(additiveRotation, currentTransition, transitionProgress);

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
    private void applyController(boolean additiveRotation, ControllerTransition transition, float progress) {
        for (PoseContribution pose : controllerContributions) {
            if (transition != null) pose.transitionFrom(transition.frozen.get(pose.bone), progress);
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
                .thenComparing(state -> state.controller).thenComparingInt(state -> state.referenceOrder)
                .thenComparing(state -> state.layerId));
        layerOrderChanged = false;
    }

    private record PreparedChannel(YsmKeyframeSampler.PreparedTrack track, int path) {}
    private record PreparedEvent(double time, List<YsmExpressionRuntime.Program> instructions) {}
    private record PreparedClip(List<PreparedChannel> tracks, List<PreparedEvent> events,
                                List<YsmExpressionRuntime.Program> weight) {}

    private record ControllerTransition(YsmBlendTransition source, BlendCurve curve, Map<String, FrozenPose> frozen) {}

    private static final class BlendCurve {
        final double duration;
        final List<YsmBlendPoint> points;

        BlendCurve(YsmBlendTransition source) {
            points = source.points().stream().sorted(java.util.Comparator.comparingDouble(YsmBlendPoint::input)).toList();
            for (var point : points) if (!Float.isFinite(point.input()) || point.input() < 0
                    || !Float.isFinite(point.output()))
                throw new IllegalArgumentException("YSM blend points must have finite nonnegative times and finite values");
            duration = points.isEmpty() ? source.linearLength() == null ? 0 : source.linearLength()
                    : points.get(points.size() - 1).input();
            if (!Double.isFinite(duration) || duration < 0)
                throw new IllegalArgumentException("YSM blend duration must be finite and nonnegative");
        }

        float progress(double seconds) {
            if (points.isEmpty()) return (float) (seconds / duration);
            if (seconds <= points.get(0).input()) return 1 - points.get(0).output();
            int lo = 1, hi = points.size();
            while (lo < hi) {
                int middle = (lo + hi) >>> 1;
                if (seconds < points.get(middle).input()) hi = middle;
                else lo = middle + 1;
            }
            if (lo == points.size()) return 1 - points.get(lo - 1).output();
            YsmBlendPoint a = points.get(lo - 1), b = points.get(lo);
            double t = (seconds - a.input()) / (b.input() - a.input());
            return (float) (1 - a.output() - (b.output() - a.output()) * t);
        }
    }

    private static final class FrozenPose {
        final float[] rotation, position, scale;

        FrozenPose(PoseContribution pose) {
            rotation = pose.rotation.clone();
            position = pose.position.clone();
            scale = pose.scale.clone();
        }
    }

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
        final int referenceOrder;
        final YsmModelSource.YsmAnimationView clip;
        double timeSeconds;
        float weight;
        boolean finished;
        boolean started;
        ControllerTransition transition;
        double transitionSeconds;
        boolean transitionStarted;

        ActiveClip(String layerId, int order, String controller, boolean additiveRotation,
                   YsmModelSource.YsmAnimationView clip, float weight) {
            this.layerId = layerId;
            this.order = order;
            this.controller = controller;
            this.additiveRotation = additiveRotation;
            referenceOrder = referenceOrder(layerId);
            this.clip = clip;
            this.weight = weight;
        }

        private static int referenceOrder(String id) {
            int colon = id.lastIndexOf(':');
            if (colon < 0 || colon == id.length() - 1) return 0;
            try { return Integer.parseInt(id.substring(colon + 1)); }
            catch (NumberFormatException ignored) { return 0; }
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
        final Quaternionf rotationFrom = new Quaternionf(), rotationTo = new Quaternionf();
        final Vector3f rotationScratch = new Vector3f();

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

        void transitionFrom(FrozenPose from, float progress) {
            if ((controllerPaths & 1) != 0) {
                float x = bindRotation.x, y = bindRotation.y, z = bindRotation.z;
                rotationFrom.rotationZYX(z + (from == null ? 0 : from.rotation[2]),
                        y + (from == null ? 0 : from.rotation[1]), x + (from == null ? 0 : from.rotation[0]));
                rotationTo.rotationZYX(z + controllerRotation[2], y + controllerRotation[1], x + controllerRotation[0]);
                rotationFrom.nlerp(rotationTo, progress, rotationTo);
                // JOML 1.10.5's ZYX extraction has a denominator sign bug; keep source-compatible math here.
                rotationScratch.set(
                        (float) Math.atan2(rotationTo.y * rotationTo.z + rotationTo.w * rotationTo.x,
                                .5f - rotationTo.x * rotationTo.x - rotationTo.y * rotationTo.y),
                        (float) Math.asin(Math.max(-1, Math.min(1,
                                -2f * (rotationTo.x * rotationTo.z - rotationTo.w * rotationTo.y)))),
                        (float) Math.atan2(rotationTo.x * rotationTo.y + rotationTo.w * rotationTo.z,
                                .5f - rotationTo.y * rotationTo.y - rotationTo.z * rotationTo.z));
                controllerRotation[0] = rotationScratch.x - x;
                controllerRotation[1] = rotationScratch.y - y;
                controllerRotation[2] = rotationScratch.z - z;
            }
            for (int axis = 0; axis < 3; axis++) {
                if ((controllerPaths & 2) != 0) {
                    float previous = from == null ? 0 : from.position[axis];
                    controllerPosition[axis] = previous + (controllerPosition[axis] - previous) * progress;
                }
                if ((controllerPaths & 4) != 0) {
                    float previous = from == null ? 1 : from.scale[axis];
                    controllerScale[axis] = previous + (controllerScale[axis] - previous) * progress;
                }
            }
        }
    }
}
