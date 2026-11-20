/*
 * Originally derived from:
 * Source: https://github.com/YesSteveModel/YesSteveModel (Apache-2.0)
 *        and https://github.com/stevewongv/YesSteveModel (Apache-2.0)
 * Licensed under the Apache License, Version 2.0.
 * Modified by MineRealms for RigWorks.
 */
package cn.minerealms.rigworks.ysm.runtime;

import cn.minerealms.assimploader.animation.AnimationState;
import cn.minerealms.rigworks.ysm.model.YsmAnimationReference;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Evaluates YSM controller conditions and drives one RigWorks layer per authored clip reference. */
public final class YsmControllerRuntime {
    public record TransitionDef(String to, String condition) {}

    public record StateDef(Map<String, String> animations, List<TransitionDef> transitions,
                           List<YsmAnimationReference> animationReferences) {
        public StateDef {
            animations = Map.copyOf(animations);
            transitions = List.copyOf(transitions);
            animationReferences = animationReferences == null || animationReferences.isEmpty()
                    ? legacyReferences(animations) : List.copyOf(animationReferences);
        }

        public StateDef(Map<String, String> animations, List<TransitionDef> transitions) {
            this(animations, transitions, legacyReferences(animations));
        }

        private static List<YsmAnimationReference> legacyReferences(Map<String, String> animations) {
            List<YsmAnimationReference> references = new ArrayList<>();
            for (String clip : animations.values()) references.add(new YsmAnimationReference(clip, ""));
            return List.copyOf(references);
        }
    }

    public record ControllerDef(String name, String initialState, Map<String, StateDef> states) {
        public ControllerDef {
            states = Map.copyOf(states);
        }
    }

    public record Selection(String controller, String state, String animation, boolean changed,
                            List<String> activeAnimations) {
        public Selection {
            activeAnimations = List.copyOf(activeAnimations);
        }

        public Selection(String controller, String state, String animation, boolean changed) {
            this(controller, state, animation, changed, animation == null ? List.of() : List.of(animation));
        }
    }

    public interface AnimationSink {
        void show(String clip, float weight);
        void hide();
        boolean hasClip(String clip);

        default void showLayer(String layerId, String clip, float weight) { show(clip, weight); }
        default void hideLayer(String layerId) { hide(); }
    }

    private final ControllerDef definition;
    private AnimationSink sink;
    private String currentState;
    private final Map<String, String> activeClips = new LinkedHashMap<>();
    private float weight = 1f;

    public YsmControllerRuntime(ControllerDef definition, AnimationSink sink) {
        this.definition = Objects.requireNonNull(definition, "controller definition");
        this.sink = sink;
        this.currentState = definition.initialState();
        if (currentState == null || !definition.states().containsKey(currentState))
            currentState = definition.states().keySet().stream().findFirst().orElse(null);
    }

    public static ControllerDef fromView(YsmModelSource.YsmControllerView view) {
        Map<String, StateDef> states = new LinkedHashMap<>();
        for (var entry : view.states().entrySet()) {
            var state = entry.getValue();
            List<TransitionDef> transitions = state.transitions().stream()
                    .map(transition -> new TransitionDef(transition.to(), transition.condition()))
                    .toList();
            states.put(entry.getKey(), new StateDef(state.animations(), transitions, state.animationReferences()));
        }
        return new ControllerDef(view.name(), view.initialState(), states);
    }

    public void bindSink(AnimationSink value) {
        if (value == null || sink == value) return;
        hideActive();
        sink = value;
    }

    public boolean hasSink() { return sink != null; }

    private void hideActive() {
        if (sink != null) for (String layer : activeClips.keySet()) sink.hideLayer(layer);
        activeClips.clear();
    }

    public Selection update(YsmExpressionRuntime.Context context) {
        if (sink == null) return selection(false);
        StateDef state = currentState == null ? null : definition.states().get(currentState);
        boolean stateChanged = false;
        if (state != null) for (TransitionDef transition : state.transitions()) {
            if (transition.condition() == null || transition.condition().isBlank()
                    || !YsmExpressionRuntime.condition(transition.condition(), context)) continue;
            StateDef next = definition.states().get(transition.to());
            if (next == null) continue;
            currentState = transition.to();
            state = next;
            stateChanged = true;
            break;
        }

        Map<String, String> selected = new LinkedHashMap<>();
        if (state != null) {
            List<YsmAnimationReference> references = state.animationReferences();
            for (int i = 0; i < references.size(); i++) {
                YsmAnimationReference reference = references.get(i);
                if (reference.name() == null || reference.name().isBlank()) continue;
                String condition = reference.condition();
                if (condition != null && !condition.isBlank()
                        && !YsmExpressionRuntime.condition(condition, context)) continue;
                if (!sink.hasClip(reference.name())) continue;
                selected.put(layerId(i), reference.name());
            }
        }

        boolean changed = stateChanged || !selected.equals(activeClips);
        for (String oldLayer : List.copyOf(activeClips.keySet())) {
            if (!selected.containsKey(oldLayer)) sink.hideLayer(oldLayer);
        }
        for (var entry : selected.entrySet()) {
            sink.showLayer(entry.getKey(), entry.getValue(), weight);
        }
        activeClips.clear();
        activeClips.putAll(selected);
        return selection(changed);
    }

    private String layerId(int index) {
        String name = definition.name() == null ? "controller" : definition.name().replaceAll("[^A-Za-z0-9_.:-]", "_");
        return "ysm:controller:" + name + ":" + index;
    }

    private Selection selection(boolean changed) {
        List<String> clips = List.copyOf(activeClips.values());
        return new Selection(definition.name(), currentState, clips.isEmpty() ? null : clips.get(0), changed, clips);
    }

    public void setWeight(float value) { weight = !Float.isFinite(value) || value <= 0 ? 0 : Math.min(1, value); }
    public float weight() { return weight; }
    public String currentState() { return currentState; }
    public String currentClip() { return activeClips.values().stream().findFirst().orElse(null); }
    public List<String> currentClips() { return List.copyOf(activeClips.values()); }

    public void reset() {
        currentState = definition.initialState();
        if (currentState == null || !definition.states().containsKey(currentState))
            currentState = definition.states().keySet().stream().findFirst().orElse(null);
        hideActive();
    }

    public static AnimationSink fromAnimationState(AnimationState animation, String baseLayerId) {
        return new AnimationSink() {
            private final Map<String, String> layers = new LinkedHashMap<>();
            private final Map<String, Float> weights = new LinkedHashMap<>();

            @Override public void show(String clip, float blend) { showLayer(baseLayerId, clip, blend); }
            @Override public void hide() { for (String layer : List.copyOf(layers.keySet())) hideLayer(layer); }
            @Override public boolean hasClip(String value) {
                for (String available : animation.getAvailableAnimations()) if (value.equals(available)) return true;
                return false;
            }

            @Override public void showLayer(String layerId, String clip, float blend) {
                String previous = layers.get(layerId);
                if (!clip.equals(previous)) {
                    if (animation.getLayer(layerId) != null) animation.removeLayer(layerId);
                    animation.setLayer(layerId, clip, blend, false, Map.of());
                    layers.put(layerId, clip);
                    weights.put(layerId, blend);
                } else if (animation.getLayer(layerId) != null
                        && Math.abs(weights.getOrDefault(layerId, Float.NaN) - blend) > 1e-4f) {
                    animation.setLayerWeight(layerId, blend);
                    weights.put(layerId, blend);
                }
            }

            @Override public void hideLayer(String layerId) {
                weights.remove(layerId);
                if (layers.remove(layerId) != null && animation.getLayer(layerId) != null)
                    animation.removeLayer(layerId);
            }
        };
    }
}
