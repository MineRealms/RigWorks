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
import cn.minerealms.rigworks.ysm.model.YsmBlendTransition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.HashSet;

/** Evaluates YSM controller conditions and drives one RigWorks layer per authored clip reference. */
public final class YsmControllerRuntime {
    public record TransitionDef(String to, String condition) {}

    public record StateDef(Map<String, String> animations, List<TransitionDef> transitions,
                           List<YsmAnimationReference> animationReferences, List<String> onEntry,
                           List<String> onExit, YsmBlendTransition blendTransition,
                           boolean blendViaShortestPath, List<String> soundEffects) {
        public StateDef {
            animations = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(animations));
            transitions = List.copyOf(transitions);
            animationReferences = animationReferences == null || animationReferences.isEmpty()
                    ? legacyReferences(animations) : List.copyOf(animationReferences);
            onEntry = List.copyOf(onEntry);
            onExit = List.copyOf(onExit);
            soundEffects = List.copyOf(soundEffects);
        }

        public StateDef(Map<String, String> animations, List<TransitionDef> transitions,
                        List<YsmAnimationReference> animationReferences) {
            this(animations, transitions, animationReferences, List.of(), List.of(), null, false, List.of());
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
            states = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(states));
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

        /** A state activation carries its source blend curve; sinks may capture the previous pose here. */
        default void transitionLayer(String layerId, String clip, float weight,
                                     YsmBlendTransition transition, boolean shortestPath) {
            showLayer(layerId, clip, weight);
        }
    }

    private record PreparedTransition(String to, YsmExpressionRuntime.Program condition) {}
    private record PreparedReference(String layer, String clip, YsmExpressionRuntime.Program condition) {}
    private record PreparedState(List<YsmExpressionRuntime.Program> onEntry,
                                 List<YsmExpressionRuntime.Program> onExit,
                                 List<PreparedTransition> transitions, List<PreparedReference> references) {}

    private final ControllerDef definition;
    private final Map<String, PreparedState> preparedStates = new LinkedHashMap<>();
    private AnimationSink sink;
    private String currentState;
    private final Map<String, String> activeClips = new LinkedHashMap<>();
    private final Map<String, String> selected = new LinkedHashMap<>();
    private final java.util.Set<String> visitedStates = new HashSet<>();
    private boolean entered;
    private float weight = 1f;

    public YsmControllerRuntime(ControllerDef definition, AnimationSink sink) {
        this.definition = Objects.requireNonNull(definition, "controller definition");
        this.sink = sink;
        for (var entry : definition.states().entrySet()) {
            StateDef state = entry.getValue();
            List<PreparedReference> references = new ArrayList<>(state.animationReferences().size());
            for (int i = 0; i < state.animationReferences().size(); i++) {
                var reference = state.animationReferences().get(i);
                references.add(new PreparedReference(layerId(i), reference.name(),
                        program(reference.condition())));
            }
            preparedStates.put(entry.getKey(), new PreparedState(
                    state.onEntry().stream().map(YsmExpressionRuntime::compile).toList(),
                    state.onExit().stream().map(YsmExpressionRuntime::compile).toList(),
                    state.transitions().stream().map(t -> new PreparedTransition(t.to(), program(t.condition()))).toList(),
                    List.copyOf(references)));
        }
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
            states.put(entry.getKey(), new StateDef(state.animations(), transitions, state.animationReferences(),
                    state.onEntry(), state.onExit(), state.blendTransition(), state.blendViaShortestPath(),
                    state.soundEffects()));
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
        PreparedState prepared = preparedStates.get(currentState);
        boolean stateChanged = false;
        if (!entered && prepared != null) {
            evaluate(prepared.onEntry(), context);
            entered = true;
            stateChanged = true;
        } else if (prepared != null) {
            visitedStates.clear();
            visitedStates.add(currentState);
            boolean transitioned;
            do {
                transitioned = false;
                for (PreparedTransition transition : prepared.transitions()) {
                    if (transition.condition() == null || !transition.condition().condition(context)
                            || !preparedStates.containsKey(transition.to()) || !visitedStates.add(transition.to())) continue;
                    evaluate(prepared.onExit(), context);
                    currentState = transition.to();
                    state = definition.states().get(currentState);
                    prepared = preparedStates.get(currentState);
                    evaluate(prepared.onEntry(), context);
                    stateChanged = true;
                    transitioned = true;
                    break;
                }
            } while (transitioned && prepared.references().isEmpty());
        }

        selected.clear();
        if (prepared != null) {
            for (PreparedReference reference : prepared.references()) {
                if (reference.clip() == null || reference.clip().isBlank()) continue;
                if (reference.condition() != null && !reference.condition().condition(context)) continue;
                if (!sink.hasClip(reference.clip())) continue;
                selected.put(reference.layer(), reference.clip());
            }
        }

        boolean changed = stateChanged || !selected.equals(activeClips);
        if (stateChanged) hideActive();
        for (String oldLayer : List.copyOf(activeClips.keySet())) {
            if (!selected.containsKey(oldLayer)) sink.hideLayer(oldLayer);
        }
        for (var entry : selected.entrySet()) {
            if (stateChanged) sink.transitionLayer(entry.getKey(), entry.getValue(), weight,
                    state.blendTransition(), state.blendViaShortestPath());
            else sink.showLayer(entry.getKey(), entry.getValue(), weight);
        }
        activeClips.clear();
        activeClips.putAll(selected);
        return selection(changed);
    }

    private static YsmExpressionRuntime.Program program(String source) {
        return source == null || source.isBlank() ? null : YsmExpressionRuntime.compile(source);
    }

    private static void evaluate(List<YsmExpressionRuntime.Program> programs, YsmExpressionRuntime.Context context) {
        for (var program : programs) program.evaluate(context);
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
        entered = false;
        selected.clear();
        visitedStates.clear();
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
