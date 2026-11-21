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

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import cn.minerealms.rigworks.ysm.model.YsmAnimationReference;
import cn.minerealms.rigworks.ysm.model.YsmBlendTransition;
import cn.minerealms.rigworks.ysm.model.YsmBlendPoint;

import static org.junit.jupiter.api.Assertions.*;

class YsmControllerRuntimeTest {
    private static final class Sink implements YsmControllerRuntime.AnimationSink {
        String clip;
        float weight = -1;
        int shows;
        int hides;
        int transitions;
        YsmBlendTransition transition;
        boolean shortestPath;
        final Set<String> available;
        final Map<String, String> layers = new LinkedHashMap<>();

        Sink(String... available) { this.available = Set.of(available); }

        @Override public void show(String value, float blend) { clip = value; weight = blend; shows++; }
        @Override public void hide() { clip = null; hides++; }
        @Override public boolean hasClip(String value) { return available.contains(value); }
        @Override public void showLayer(String layer, String value, float blend) {
            layers.put(layer, value);
            show(value, blend);
        }
        @Override public void hideLayer(String layer) {
            if (layers.remove(layer) != null) hides++;
            if (layers.isEmpty()) clip = null;
        }
        @Override public void transitionLayer(String layer, String value, float weight,
                                              YsmBlendTransition transition, boolean shortestPath) {
            transitions++;
            this.transition = transition;
            this.shortestPath = shortestPath;
            showLayer(layer, value, weight);
        }
    }

    private static YsmControllerRuntime.ControllerDef definition() {
        Map<String, YsmControllerRuntime.StateDef> states = new LinkedHashMap<>();
        states.put("idle", new YsmControllerRuntime.StateDef(Map.of("main", "anim.idle"), List.of(
                new YsmControllerRuntime.TransitionDef("sneak", "q.is_sneaking"),
                new YsmControllerRuntime.TransitionDef("move", "v.speed > 0.5"))));
        states.put("sneak", new YsmControllerRuntime.StateDef(Map.of("idle", "anim.sneak"), List.of(
                new YsmControllerRuntime.TransitionDef("idle", "!q.is_sneaking"))));
        states.put("move", new YsmControllerRuntime.StateDef(Map.of("main", "anim.run"), List.of(
                new YsmControllerRuntime.TransitionDef("idle", "v.speed <= 0.5"))));
        return new YsmControllerRuntime.ControllerDef("locomotion", "idle", states);
    }

    private static YsmExpressionRuntime.MapContext context(boolean sneaking, double speed) {
        return new YsmExpressionRuntime.MapContext(Map.of(), Set.of())
                .set("q.is_sneaking", sneaking ? 1 : 0).set("speed", speed);
    }

    @Test void initialStatePlaysItsMainClip() {
        var sink = new Sink("anim.idle", "anim.sneak", "anim.run");
        var runtime = new YsmControllerRuntime(definition(), sink);
        var selection = runtime.update(context(false, 0));
        assertEquals("idle", runtime.currentState());
        assertEquals("anim.idle", selection.animation());
        assertTrue(selection.changed());
        assertEquals("anim.idle", sink.clip);
        assertEquals(1, sink.shows);
    }

    @Test void malformedImportedControllerExpressionsLeaveOtherReferencesAndStatementsActive() {
        var state = new YsmControllerRuntime.StateDef(Map.of(),
                List.of(new YsmControllerRuntime.TransitionDef("other", "1)")),
                List.of(new YsmAnimationReference("anim.broken", "1 +"),
                        new YsmAnimationReference("anim.idle", "")),
                List.of("v.partial=99; 1 +", "v.entered=7"), List.of("v.exited=1; 0)"),
                null, false, List.of());
        var def = new YsmControllerRuntime.ControllerDef("imported", "idle", Map.of(
                "idle", state, "other", new YsmControllerRuntime.StateDef(Map.of("main", "anim.other"), List.of())));
        var sink = new Sink("anim.idle", "anim.broken", "anim.other");
        var runtime = new YsmControllerRuntime(def, sink);
        var c = context(false, 0);
        runtime.update(c);
        runtime.update(c);
        assertEquals("idle", runtime.currentState());
        assertEquals(List.of("anim.idle"), runtime.currentClips());
        assertEquals(7, c.value("v.entered"));
        assertFalse(c.has("v.partial"));
    }

    @Test void transitionsFollowConditionPriority() {
        var sink = new Sink("anim.idle", "anim.sneak", "anim.run");
        var runtime = new YsmControllerRuntime(definition(), sink);
        runtime.update(context(false, 0));
        runtime.update(context(true, 1));
        assertEquals("sneak", runtime.currentState());
        assertEquals("anim.sneak", runtime.currentClip());
        runtime.update(context(false, 1));
        assertEquals("idle", runtime.currentState());
        runtime.update(context(false, 1));
        assertEquals("move", runtime.currentState());
        assertEquals("anim.run", runtime.currentClip());
        runtime.update(context(false, 0));
        assertEquals("idle", runtime.currentState());
    }

    @Test void weightAndResetArePropagated() {
        var sink = new Sink("anim.idle");
        var runtime = new YsmControllerRuntime(definition(), sink);
        runtime.setWeight(0.4f);
        runtime.update(context(false, 0));
        assertEquals(0.4f, sink.weight, 1e-6);
        runtime.reset();
        assertEquals("idle", runtime.currentState());
        assertNull(runtime.currentClip());
        assertEquals(1, sink.hides);
    }

    @Test void missingClipHidesLayerAndDoesNotSelect() {
        var sink = new Sink();
        var runtime = new YsmControllerRuntime(definition(), sink);
        var selection = runtime.update(context(false, 0));
        assertNull(selection.animation());
        assertNull(runtime.currentClip());
        assertEquals(0, sink.hides);
    }

    @Test void legacyStateMapsCanDriveMultipleLayers() {
        var state = new YsmControllerRuntime.StateDef(Map.of("idle", "a", "walk", "b", "main", "c"), List.of());
        var def = new YsmControllerRuntime.ControllerDef("c", "s", Map.of("s", state));
        var sink = new Sink("a", "b", "c");
        var result = new YsmControllerRuntime(def, sink).update(context(false, 0));
        assertEquals(Set.of("a", "b", "c"), Set.copyOf(result.activeAnimations()));
    }

    @Test void evaluatesAllConditionalAnimationReferencesAsSeparateLayers() {
        var state = new YsmControllerRuntime.StateDef(Map.of(), List.of(), List.of(
                new YsmAnimationReference("anim.idle", ""),
                new YsmAnimationReference("anim.sprint", "q.is_sprinting")));
        var def = new YsmControllerRuntime.ControllerDef("main", "base", Map.of("base", state));
        var sink = new Sink("anim.idle", "anim.sprint");
        var result = new YsmControllerRuntime(def, sink).update(
                new YsmExpressionRuntime.MapContext(Map.of("q.is_sprinting", 1d), Set.of()));
        assertEquals(List.of("anim.idle", "anim.sprint"), result.activeAnimations());
        assertEquals(2, sink.layers.size());
    }

    @Test void fromViewReadsReflectiveModelShape() {
        var transitions = List.of(new YsmModelSource.YsmTransitionView("move", "v.speed > 0.5"));
        var state = new YsmModelSource.YsmStateView(Map.of("main", "anim.idle"), transitions);
        var view = new YsmModelSource.YsmControllerView("locomotion", "idle", Map.of("idle", state));
        var def = YsmControllerRuntime.fromView(view);
        assertEquals("locomotion", def.name());
        assertEquals("idle", def.initialState());
        assertEquals("anim.idle", def.states().get("idle").animations().get("main"));
        assertEquals("move", def.states().get("idle").transitions().get(0).to());
        assertEquals("v.speed > 0.5", def.states().get("idle").transitions().get(0).condition());
    }

    @Test void stateHooksRunOnceInExitThenEntryOrderWithPersistentVariables() {
        var first = state("a", List.of(new YsmControllerRuntime.TransitionDef("second", "v.switch")),
                List.of("v.order=v.order*10+1;v.enabled=1"), List.of("v.order=v.order*10+2"), null, false);
        var second = state("b", List.of(), List.of("v.order=v.order*10+3"), List.of(), null, false);
        var sink = new Sink("a", "b");
        var runtime = new YsmControllerRuntime(new YsmControllerRuntime.ControllerDef("main", "first",
                Map.of("first", first, "second", second)), sink);
        var context = new YsmVariableContext();
        context.bind(new YsmExpressionRuntime.MapContext(Map.of(), Set.of()));
        runtime.update(context);
        assertEquals(1, context.value("v.order"));
        assertEquals(1, context.value("v.enabled"));
        assertEquals("a", sink.clip);
        runtime.update(context);
        assertEquals(1, context.value("v.order"), "unchanged states must not repeat their hooks");
        context.setVariable("v.switch", 1);
        runtime.update(context);
        assertEquals(123, context.value("v.order"));
        assertEquals("b", sink.clip);
        context.bind(new YsmExpressionRuntime.MapContext(Map.of("q.time", 5d), Set.of()));
        runtime.update(context);
        assertEquals(123, context.value("v.order"), "query refresh must not erase the controller variables");
        runtime.reset();
        context.setVariable("v.switch", 0);
        runtime.update(context);
        assertEquals(1231, context.value("v.order"), "reset must activate the initial state's entry again");
    }

    @Test void initialEntryPrecedesOutgoingTransitionsAndEmptyStateChainsAreBounded() {
        var first = state("a", List.of(new YsmControllerRuntime.TransitionDef("empty", "v.enabled")),
                List.of("v.enabled=1"), List.of(), null, false);
        var empty = new YsmControllerRuntime.StateDef(Map.of(), List.of(
                new YsmControllerRuntime.TransitionDef("last", "1")), List.of(),
                List.of("v.order=2"), List.of("v.order=v.order*10+3"), null, false, List.of());
        var last = state("b", List.of(new YsmControllerRuntime.TransitionDef("last", "1")),
                List.of("v.order=v.order*10+4"), List.of("v.order=999"), null, false);
        var sink = new Sink("a", "b");
        var runtime = new YsmControllerRuntime(new YsmControllerRuntime.ControllerDef("main", "first",
                Map.of("first", first, "empty", empty, "last", last)), sink);
        var context = new YsmVariableContext();
        runtime.update(context);
        assertEquals("first", runtime.currentState(), "the initial state is activated before evaluating its outgoing transitions");
        runtime.update(context);
        assertEquals("last", runtime.currentState());
        assertEquals(234, context.value("v.order"));
        runtime.update(context);
        assertEquals(234, context.value("v.order"), "a self-transition must not rerun the state's hooks every frame");
    }

    @Test void stateBlendMetadataReachesTheSinkOncePerActivation() {
        var blend = new YsmBlendTransition(null, List.of(new YsmBlendPoint(0, 1), new YsmBlendPoint(.2f, 0)));
        var source = state("a", List.of(), List.of(), List.of(), blend, true);
        var sink = new Sink("a");
        var runtime = new YsmControllerRuntime(new YsmControllerRuntime.ControllerDef("main", "a", Map.of("a", source)), sink);
        runtime.update(context(false, 0));
        assertEquals(blend, sink.transition);
        assertTrue(sink.shortestPath);
        assertEquals(1, sink.transitions);
        runtime.update(context(false, 0));
        assertEquals(1, sink.transitions);
    }

    @Test void fromViewPreservesHooksBlendAndSoundMetadata() {
        var blend = new YsmBlendTransition(.15f, List.of());
        var state = new YsmModelSource.YsmStateView(Map.of(), List.of(),
                List.of(new YsmAnimationReference("a", "v.enabled")), List.of("v.enabled=1"),
                List.of("v.enabled=0"), blend, true, List.of("voice"));
        var view = new YsmModelSource.YsmControllerView("main", "idle", Map.of("idle", state));
        var copied = YsmControllerRuntime.fromView(view).states().get("idle");
        assertEquals(state.onEntry(), copied.onEntry());
        assertEquals(state.onExit(), copied.onExit());
        assertEquals(blend, copied.blendTransition());
        assertTrue(copied.blendViaShortestPath());
        assertEquals(List.of("voice"), copied.soundEffects());
    }

    @Test void modelProjectionRetainsAuthoredStateInitializers() {
        var blend = new YsmBlendTransition(.25f, List.of());
        var state = new cn.minerealms.rigworks.ysm.model.YsmState(Map.of(), List.of(),
                List.of(new YsmAnimationReference("a", "v.enabled")), List.of("v.enabled=1"),
                List.of("v.enabled=0"), blend, true, List.of("voice"));
        var controller = new cn.minerealms.rigworks.ysm.model.YsmController("player.main", "default",
                Map.of("default", state), "main", 1);
        var model = new cn.minerealms.rigworks.ysm.model.YsmModel(9, "fixture", List.of(), Map.of(), Map.of(), List.of(controller));
        var view = YsmModelSource.reflective().view(model).controllers().get(0).states().get("default");
        assertEquals(state.onEntry(), view.onEntry());
        assertEquals(state.onExit(), view.onExit());
        assertEquals(blend, view.blendTransition());
        assertTrue(view.blendViaShortestPath());
        assertEquals(state.soundEffects(), view.soundEffects());
    }

    private static YsmControllerRuntime.StateDef state(String clip,
            List<YsmControllerRuntime.TransitionDef> transitions, List<String> entry, List<String> exit,
            YsmBlendTransition blend, boolean shortest) {
        return new YsmControllerRuntime.StateDef(Map.of(), transitions,
                List.of(new YsmAnimationReference(clip, "")), entry, exit, blend, shortest, List.of());
    }
}
