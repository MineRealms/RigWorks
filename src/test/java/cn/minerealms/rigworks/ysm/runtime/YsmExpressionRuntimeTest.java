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

import cn.minerealms.rigworks.character.core.PlayerActionState;
import cn.minerealms.rigworks.character.core.PlayerVisualInput;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.*;

class YsmExpressionRuntimeTest {
    private static YsmExpressionRuntime.MapContext context() {
        return new YsmExpressionRuntime.MapContext(Map.of(), Set.of());
    }

    @Test void arithmeticRespectsPrecedenceAndGuardsDivision() {
        assertEquals(7, YsmExpressionRuntime.evaluate("1 + 2 * 3", context()), 1e-9);
        assertEquals(9, YsmExpressionRuntime.evaluate("(1 + 2) * 3", context()), 1e-9);
        assertEquals(3, YsmExpressionRuntime.evaluate("-2 + 5", context()), 1e-9);
        assertEquals(3, YsmExpressionRuntime.evaluate("7 % 4", context()), 1e-9);
        assertEquals(4, YsmExpressionRuntime.evaluate("8 / 2", context()), 1e-9);
        assertEquals(0, YsmExpressionRuntime.evaluate("8 / 0", context()), 1e-9);
    }

    @Test void comparisonsAndLogicReturnZeroOrOne() {
        var c = context();
        assertEquals(1, YsmExpressionRuntime.evaluate("1 < 2", c), 1e-9);
        assertEquals(1, YsmExpressionRuntime.evaluate("2 <= 2", c), 1e-9);
        assertEquals(0, YsmExpressionRuntime.evaluate("3 > 4", c), 1e-9);
        assertEquals(1, YsmExpressionRuntime.evaluate("5 >= 5", c), 1e-9);
        assertEquals(1, YsmExpressionRuntime.evaluate("1 == 1", c), 1e-9);
        assertEquals(1, YsmExpressionRuntime.evaluate("1 != 2", c), 1e-9);
        assertEquals(0, YsmExpressionRuntime.evaluate("1 && 0", c), 1e-9);
        assertEquals(1, YsmExpressionRuntime.evaluate("1 || 0", c), 1e-9);
        assertEquals(1, YsmExpressionRuntime.evaluate("!0", c), 1e-9);
        assertEquals(0, YsmExpressionRuntime.evaluate("!1", c), 1e-9);
    }

    @Test void ternaryAndMathFunctions() {
        var c = context().set("q.health", 15);
        assertEquals(1, YsmExpressionRuntime.evaluate("q.health > 10 ? 1 : 0", c), 1e-9);
        assertEquals(0, YsmExpressionRuntime.evaluate("q.health < 10 ? 1 : 0", c), 1e-9);
        assertEquals(0, YsmExpressionRuntime.evaluate("math.sin(0)", c), 1e-9);
        assertEquals(1, YsmExpressionRuntime.evaluate("math.cos(0)", c), 1e-9);
        assertEquals(1, YsmExpressionRuntime.evaluate("math.clamp(5, 0, 1)", c), 1e-9);
        assertEquals(1, YsmExpressionRuntime.evaluate("math.min(3, 1, 2)", c), 1e-9);
        assertEquals(3, YsmExpressionRuntime.evaluate("math.max(3, 1, 2)", c), 1e-9);
    }

    @Test void variablesAndQueriesResolveFromContext() {
        var c = context().set("q.is_sneaking", 1).set("speed", 0.5).setQuery("ysm.is_close_eyes", 1);
        assertEquals(1, YsmExpressionRuntime.evaluate("q.is_sneaking && v.speed > 0.25", c), 1e-9);
        assertEquals(1, YsmExpressionRuntime.evaluate("is_sneaking", c), 1e-9);
        assertTrue(YsmExpressionRuntime.condition("ysm.is_close_eyes", c));
        assertEquals(0, YsmExpressionRuntime.condition("q.is_sneaking == 0", c) ? 1 : 0);
        assertTrue(YsmExpressionRuntime.condition("q.is_sneaking", c));
    }

    @Test void controlTokensSupportSingleAndTwoArgumentForms() {
        var c = new YsmExpressionRuntime.MapContext(Map.of(), Set.of(
                "ctrl.hold(':bow')", "ctrl.hold('mainhand',':bow')", "use('bow')",
                "ctrl.tac_hold_gun()"));
        assertEquals(1, YsmExpressionRuntime.evaluate("ctrl.hold(':bow')", c), 1e-9);
        assertEquals(1, YsmExpressionRuntime.evaluate("q.ctrl.hold(':bow')", c), 1e-9);
        assertEquals(1, YsmExpressionRuntime.evaluate("ctrl.hold('mainhand', ':bow')", c), 1e-9);
        assertEquals(1, YsmExpressionRuntime.evaluate("ctrl.use('bow')", c), 1e-9);
        assertEquals(0, YsmExpressionRuntime.evaluate("ctrl.hold(':sword')", c), 1e-9);
        assertEquals(1, YsmExpressionRuntime.evaluate("ctrl.tac_hold_gun()", c), 1e-9);
    }

    @Test void contextAdaptsPlayerAndItemState() {
        var action = new PlayerActionState("swimming", PlayerActionState.AirPhase.FALLING,
                false, true, false, false, false, false, 0, "none", false, false);
        var input = PlayerVisualInput.builder(1, 1.0).crouching(true).onGround(false).swimming(true)
                .actionState(action).build();
        var item = YsmItemStateBridge.derive(null,
                new YsmItemStateBridge.HeldItem("minecraft:bow", Set.of(), false, "bow", false),
                YsmItemStateBridge.HeldItem.EMPTY, true);
        var c = YsmExpressionRuntime.context(input, action, item);
        assertTrue(YsmExpressionRuntime.condition("q.is_sneaking", c));
        assertFalse(YsmExpressionRuntime.condition("q.on_ground", c));
        assertTrue(YsmExpressionRuntime.condition("q.is_swimming", c));
        assertEquals(1, YsmExpressionRuntime.evaluate("q.hold_bow", c), 1e-9);
        assertEquals(0, YsmExpressionRuntime.evaluate("q.hold_sword", c), 1e-9);
        assertTrue(c.matches("ctrl.hold(':bow')"));

        var moddedItem = YsmItemStateBridge.derive(null,
                new YsmItemStateBridge.HeldItem("addon:modded_tool", Set.of(), false, "none", false),
                YsmItemStateBridge.HeldItem.EMPTY, true);
        assertTrue(moddedItem.matches("ctrl.hold(':modded_tool')"));
    }

    @Test void malformedExpressionThrows() {
        assertThrows(IllegalArgumentException.class, () -> YsmExpressionRuntime.evaluate("1 +", context()));
    }

    @Test void compiledProgramsMatchOriginalInterpreterAndReadCurrentContext() {
        String[] expressions = {
                "1 + 2 * 3 - 8 / 4", "8 / 0 + 9 % 0", "!!q.speed", "1 < 2 == 2 > 1",
                "q.angle > 10 ? math.sin(q.speed) : math.cos(q.angle)",
                "math.min(1, q.angle, q.speed, 4, 5, 6, 7, 8)", "math.max()",
                "math.clamp(q.speed * 4, -2, 2)", "q.unknown || false && true",
                "ctrl.hold(':bow')", "q.ctrl.use('mainhand',':bow')", "ctrl.tac_hold_gun()",
                "ctrl.tac_gun('tacz:m4')", "addon.numeric(q.speed, 'test')", "'abc' ? 1e-3 : -0.2"
        };
        var context = new YsmExpressionRuntime.MapContext(Map.of(), Set.of("ctrl.hold(':bow')",
                "ctrl.use('mainhand',':bow')", "ctrl.tac_hold_gun()", "ctrl.tac_gun('tacz:m4')"));
        for (String expression : expressions) {
            var program = YsmExpressionRuntime.compile(expression);
            assertSame(program, YsmExpressionRuntime.compile(expression));
            for (int sample = 0; sample < 20; sample++) {
                context.setQuery("speed", sample / 4d).setQuery("angle", sample * 3);
                assertEquals(LegacyYsmSamplingReference.evaluate(expression, context), program.evaluate(context), 0,
                        expression + " at sample " + sample);
            }
        }
    }

    @Test void compiledProgramsRetainEagerEvaluationOrder() {
        List<String> visits = new ArrayList<>();
        var context = new YsmExpressionRuntime.Context() {
            @Override public double value(String name) { visits.add(name); return name.equals("q.test") ? 1 : 0; }
            @Override public boolean has(String name) { return true; }
        };
        for (String expression : List.of("q.test || q.right", "q.test && q.right", "q.test ? q.yes : q.no",
                "math.clamp(q.first, q.second, q.third)")) {
            LegacyYsmSamplingReference.evaluate(expression, context);
            List<String> expected = List.copyOf(visits);
            visits.clear();
            YsmExpressionRuntime.compile(expression).evaluate(context);
            assertEquals(expected, visits, expression);
            visits.clear();
        }
    }

    @Test void compiledTokenCallsRetainMathOverridesAndNewTokenFamilies() {
        var context = context().setQuery("speed", 2);
        var program = YsmExpressionRuntime.compile("math.cos(q.speed)");
        assertEquals(Math.cos(2), program.evaluate(context));
        context.addTokens(Set.of("math.cos(2.0)"));
        assertEquals(1, program.evaluate(context));
        assertEquals(1, YsmExpressionRuntime.compile("custom.number(2)").evaluate(
                new YsmExpressionRuntime.MapContext(Map.of(), Set.of("custom.number(2.0)"))));
    }

    @Test void cachedProgramsAreBoundedButRetainedProgramsRemainUsable() {
        var retained = YsmExpressionRuntime.compile("q.speed + 987654");
        for (int i = 0; i < YsmExpressionRuntime.MAX_CACHED_PROGRAMS + 10; i++)
            YsmExpressionRuntime.compile("q.speed + " + i);
        assertTrue(YsmExpressionRuntime.cachedProgramCount() <= YsmExpressionRuntime.MAX_CACHED_PROGRAMS);
        assertTrue(YsmExpressionRuntime.cachedSourceCharCount() <= YsmExpressionRuntime.MAX_CACHED_SOURCE_CHARS);
        assertEquals(987655, retained.evaluate(context().setQuery("speed", 1)));
    }

    @Test void programsCanBeSharedWithIndependentContextsAcrossThreads() throws Exception {
        var program = YsmExpressionRuntime.compile("math.sin(q.speed) * 4 + q.angle");
        var pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Double>> calls = List.of(
                    () -> program.evaluate(context().setQuery("speed", 0).setQuery("angle", 3)),
                    () -> program.evaluate(context().setQuery("speed", Math.PI / 2).setQuery("angle", 7)));
            var results = pool.invokeAll(calls);
            assertEquals(3, results.get(0).get());
            assertEquals(11, results.get(1).get());
        } finally {
            pool.shutdownNow();
        }
    }
}
