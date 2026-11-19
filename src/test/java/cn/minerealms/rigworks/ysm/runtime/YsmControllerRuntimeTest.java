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

import static org.junit.jupiter.api.Assertions.*;

class YsmControllerRuntimeTest {
    private static final class Sink implements YsmControllerRuntime.AnimationSink {
        String clip;
        float weight = -1;
        int shows;
        int hides;
        final Set<String> available;

        Sink(String... available) { this.available = Set.of(available); }

        @Override public void show(String value, float blend) { clip = value; weight = blend; shows++; }
        @Override public void hide() { clip = null; hides++; }
        @Override public boolean hasClip(String value) { return available.contains(value); }
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
        assertEquals("anim.idle", selection.animation());
        assertNull(runtime.currentClip());
        assertEquals(1, sink.hides);
    }

    @Test void stateAnimationPriorityPrefersMainThenIdle() {
        var state = new YsmControllerRuntime.StateDef(Map.of("idle", "a", "walk", "b", "main", "c"), List.of());
        var def = new YsmControllerRuntime.ControllerDef("c", "s", Map.of("s", state));
        var sink = new Sink("a", "b", "c");
        new YsmControllerRuntime(def, sink).update(context(false, 0));
        assertEquals("c", sink.clip);
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
}
