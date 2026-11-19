/*
 * Originally derived from:
 * Source: https://github.com/YesSteveModel/YesSteveModel (Apache-2.0)
 *        and https://github.com/stevewongv/YesSteveModel (Apache-2.0)
 *
 * Licensed under the Apache License, Version 2.0.
 * You may obtain a copy of the License at:
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Modified by MineRealms for RigWorks.
 * Changes: Cleanroom reimplementation / package adaptation / API changes for in-mod YSM decode.
 */
package cn.minerealms.rigworks.ysm.runtime;

import cn.minerealms.assimploader.animation.AnimationState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class YsmControllerRuntime {
    public record TransitionDef(String to, String condition) {}

    public record StateDef(Map<String, String> animations, List<TransitionDef> transitions) {
        public StateDef {
            animations = Map.copyOf(animations);
            transitions = List.copyOf(transitions);
        }
    }

    public record ControllerDef(String name, String initialState, Map<String, StateDef> states) {
        public ControllerDef {
            states = Map.copyOf(states);
        }
    }

    public record Selection(String controller, String state, String animation, boolean changed) {}

    public interface AnimationSink {
        void show(String clip, float weight);

        void hide();

        boolean hasClip(String clip);
    }

    private final ControllerDef definition;
    private AnimationSink sink;
    private String currentState;
    private String currentClip;
    private float weight = 1f;

    public YsmControllerRuntime(ControllerDef definition, AnimationSink sink) {
        this.definition = Objects.requireNonNull(definition, "controller definition");
        this.sink = Objects.requireNonNull(sink, "animation sink");
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
            states.put(entry.getKey(), new StateDef(state.animations(), transitions));
        }
        return new ControllerDef(view.name(), view.initialState(), states);
    }

    public void bindSink(AnimationSink value) {
        if (value == null) return;
        if (sink != null && sink != value) hide();
        sink = value;
    }

    public boolean hasSink() { return sink != null; }

    private void hide() {
        if (sink != null) sink.hide();
        currentClip = null;
    }

    public Selection update(YsmExpressionRuntime.Context context) {
        if (sink == null) return new Selection(definition.name(), currentState, null, false);
        StateDef state = currentState == null ? null : definition.states().get(currentState);
        if (state != null) for (TransitionDef transition : state.transitions()) {
            if (transition.condition() != null && !transition.condition().isBlank()
                    && YsmExpressionRuntime.condition(transition.condition(), context)) {
                if (definition.states().containsKey(transition.to())) {
                    currentState = transition.to();
                    state = definition.states().get(currentState);
                }
                break;
            }
        }
        String clip = clipFor(state);
        boolean changed = !Objects.equals(clip, currentClip);
        if (clip == null || !sink.hasClip(clip)) {
            sink.hide();
            currentClip = null;
        } else {
            sink.show(clip, weight);
            currentClip = clip;
        }
        return new Selection(definition.name(), currentState, clip, changed);
    }

    private static String clipFor(StateDef state) {
        if (state == null) return null;
        Map<String, String> animations = state.animations();
        for (String key : new String[]{"main", "idle", "loop", "default"}) {
            String clip = animations.get(key);
            if (clip != null && !clip.isBlank()) return clip;
        }
        return animations.values().stream().filter(value -> value != null && !value.isBlank()).findFirst().orElse(null);
    }

    public void setWeight(float value) { weight = value <= 0 ? 0 : Math.min(1, value); }

    public float weight() { return weight; }

    public String currentState() { return currentState; }

    public String currentClip() { return currentClip; }

    public void reset() {
        currentState = definition.initialState();
        currentClip = null;
        sink.hide();
    }

    public static AnimationSink fromAnimationState(AnimationState animation, String layerId) {
        return new AnimationSink() {
            private String clip;

            @Override public void show(String value, float blend) {
                if (!value.equals(clip)) {
                    if (animation.getLayer(layerId) != null) animation.removeLayer(layerId);
                    animation.setLayer(layerId, value, blend, false, Map.of());
                    animation.playAnimation(value);
                    clip = value;
                } else animation.setLayerWeight(layerId, blend);
            }

            @Override public void hide() {
                if (clip != null && animation.getLayer(layerId) != null) animation.removeLayer(layerId);
                clip = null;
            }

            @Override public boolean hasClip(String value) {
                for (String available : animation.getAvailableAnimations()) if (value.equals(available)) return true;
                return false;
            }
        };
    }
}
