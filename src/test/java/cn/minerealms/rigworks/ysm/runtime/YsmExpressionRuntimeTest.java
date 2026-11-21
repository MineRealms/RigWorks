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
import cn.minerealms.rigworks.ysm.asset.RwymNative;
import cn.minerealms.rigworks.ysm.asset.RwymReader;
import cn.minerealms.rigworks.ysm.model.YsmTypedValue;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
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
        assertEquals(1, YsmExpressionRuntime.evaluate("math.sin(90)", c), 1e-9);
        assertEquals(-1, YsmExpressionRuntime.evaluate("math.cos(180)", c), 1e-9);
        assertEquals(3, YsmExpressionRuntime.evaluate("math.abs(-3)", c), 1e-9);
        assertEquals(8, YsmExpressionRuntime.evaluate("math.pow(2, 3)", c), 1e-9);
        assertEquals(Math.PI, YsmExpressionRuntime.evaluate("math.pi", c), 1e-9);
        for (int i = 0; i < 100; i++) {
            double random = YsmExpressionRuntime.evaluate("math.random(-30, 30)", c);
            assertTrue(random >= -30 && random < 30);
        }
    }

    @Test void aliasesAreCanonicalizedInProgramsAndContexts() {
        var c = new YsmExpressionRuntime.MapContext(Map.of("QUERY.HEAD_Y_ROTATION", 60d,
                "VARIABLE.Amount", 3d), Set.of());
        assertEquals(63, YsmExpressionRuntime.evaluate("query.head_y_rotation + v.amount", c), 1e-9);
        assertEquals(3, c.value("VARIABLE.AMOUNT"), 1e-9);
        assertTrue(c.has("query.head_y_rotation"));
        c.setQuery("QUERY.HEAD_Y_ROTATION", 90).set("VARIABLE.Amount", 4);
        assertEquals(94, YsmExpressionRuntime.evaluate("Q.HEAD_Y_ROTATION + variable.amount", c), 1e-9);
    }

    @Test void shorthandTernarySuppliesZeroForMissingFalseBranch() {
        var c = context().setQuery("ground_speed", 1).setQuery("anim_time", 0.25);
        assertEquals(10, YsmExpressionRuntime.evaluate("q.ground_speed ? math.sin(q.anim_time * 360) * 10", c), 1e-9);
        c.setQuery("ground_speed", 0);
        assertEquals(0, YsmExpressionRuntime.evaluate("q.ground_speed ? math.sin(q.anim_time * 360) * 10", c), 1e-9);
        assertEquals(7, YsmExpressionRuntime.evaluate("1 ? 0 ? 3 : 7 : 9", c), 1e-9);
    }

    @Test void assignmentsAndStatementsPreserveLiveVariablesAndReturnTheLastValue() {
        var c = context();
        var initialize = YsmExpressionRuntime.compile("variable.L1_F=5; v.L1_C=0.5; v.L1_K1=v.L1_C/math.pi/v.L1_F;");
        assertEquals(0.5 / Math.PI / 5, initialize.evaluate(c), 1e-9);
        assertEquals(5, c.value("v.l1_f"), 1e-9);
        assertEquals(6, YsmExpressionRuntime.evaluate("v.a=v.b=3; v.a+v.b", c), 1e-9);
        assertEquals(1, YsmExpressionRuntime.evaluate("v.roaming.a=1-v.roaming.a;", c), 1e-9);
        assertEquals(0, YsmExpressionRuntime.evaluate("v.roaming.a=1-v.roaming.a;", c), 1e-9);
        assertEquals(8, YsmExpressionRuntime.evaluate("temp.value=8; temporary.value", c), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> YsmExpressionRuntime.compile("q.health=0"));
        var readOnly = new YsmExpressionRuntime.Context() {
            @Override public double value(String name) { return 0; }
            @Override public boolean has(String name) { return false; }
        };
        assertEquals(7, YsmExpressionRuntime.compile("v.value=7").evaluate(readOnly), 1e-9);
    }

    @Test void skippedBranchesDoNotMutateVariables() {
        var c = context();
        assertEquals(2, YsmExpressionRuntime.evaluate("1 ? v.x=2 : v.x=3", c));
        assertEquals(2, c.value("v.x"));
        assertEquals(1, YsmExpressionRuntime.evaluate("1 || (v.x=9)", c));
        assertEquals(0, YsmExpressionRuntime.evaluate("0 && (v.x=9)", c));
        assertEquals(2, c.value("v.x"));
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

    @Test void importedInvalidProgramsUseZeroWithoutRunningPartialStatementsOrWeakeningStrictCompilation() {
        String expression = "v.imported_fault=99; 1 +";
        var c = context();
        var fallback = YsmExpressionRuntime.compileImported(expression, "test/timeline");
        assertNotNull(fallback.compileError());
        assertEquals(0, fallback.evaluate(c));
        assertFalse(c.has("v.imported_fault"));
        assertSame(fallback, YsmExpressionRuntime.compileImported(expression, "test/another-track"));
        assertThrows(IllegalArgumentException.class, () -> YsmExpressionRuntime.compile(expression));
        var valid = YsmExpressionRuntime.compileImported("q.angle * 2", "test/valid-track");
        assertNull(valid.compileError());
        assertEquals(14, valid.evaluate(c.setQuery("angle", 7)));
        assertEquals(20, valid.evaluate(c.setQuery("angle", 10)));
    }

    @Test void malformedRumiaSourceUsesTheReferenceYsmFallbackWithoutGuessingMissingParentheses() {
        String expression = "(1-math.exp(-0.02*query.ground_speed))*60)-(query.vertical_speed/math.abs(query.vertical_speed)*(1-math.exp(-0.02*math.abs(query.vertical_speed)))*30";
        assertThrows(IllegalArgumentException.class, () -> YsmExpressionRuntime.compile(expression));
        var program = YsmExpressionRuntime.compileImported(expression, "Rumia");
        assertTrue(program.compileError().contains("at 41"));
        assertEquals(0, program.evaluate(context().setQuery("ground_speed", 3).setQuery("vertical_speed", -1)));
    }

    @Test void compiledProgramsMatchOriginalInterpreterAndReadCurrentContext() {
        String[] expressions = {
                "1 + 2 * 3 - 8 / 4", "8 / 0 + 9 % 0", "!!q.speed", "1 < 2 == 2 > 1",
                "q.angle > 10 ? q.speed * 2 : q.angle / 3",
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

    @Test void compiledProgramsShortCircuitAndRetainFunctionArgumentOrder() {
        List<String> visits = new ArrayList<>();
        var context = new YsmExpressionRuntime.Context() {
            @Override public double value(String name) { visits.add(name); return name.equals("q.test") ? 1 : 0; }
            @Override public boolean has(String name) { return true; }
        };
        var expressions = List.of("q.test || q.right", "q.test && q.right", "q.test ? q.yes : q.no",
                "math.clamp(q.first, q.second, q.third)");
        var expected = List.of(List.of("q.test"), List.of("q.test", "q.right"),
                List.of("q.test", "q.yes"), List.of("q.first", "q.second", "q.third"));
        for (int i = 0; i < expressions.size(); i++) {
            String expression = expressions.get(i);
            YsmExpressionRuntime.compile(expression).evaluate(context);
            assertEquals(expected.get(i), visits, expression);
            visits.clear();
        }
    }

    @Test void compiledTokenCallsRetainMathOverridesAndNewTokenFamilies() {
        var context = context().setQuery("speed", 2);
        var program = YsmExpressionRuntime.compile("math.cos(q.speed)");
        assertEquals(Math.cos(Math.toRadians(2)), program.evaluate(context));
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
                    () -> program.evaluate(context().setQuery("speed", 90).setQuery("angle", 7)));
            var results = pool.invokeAll(calls);
            assertEquals(3, results.get(0).get());
            assertEquals(11, results.get(1).get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void everyFlandreKeyframeAndTimelineExpressionCompilesAndEvaluates() throws Exception {
        assertTrue(RwymNative.available(), RwymNative::failure);
        byte[] encoded;
        try (var source = getClass().getResourceAsStream("/ysm/Flandre_Scarlet.ysm")) {
            assertNotNull(source);
            encoded = RwymNative.convert(source.readAllBytes());
        }
        var asset = RwymReader.read(encoded);
        Set<String> expressions = new LinkedHashSet<>();
        for (var clip : asset.clips()) {
            collectExpressions(expressions, clip.blendWeight());
            for (var channel : clip.channels()) for (var key : channel.frames()) {
                collectExpressions(expressions, key.preValues());
                collectExpressions(expressions, key.postValues());
            }
            for (var key : clip.instructionKeyframes()) expressions.addAll(key.instructions());
        }
        for (var controller : asset.controllers()) for (var state : controller.states().values()) {
            expressions.addAll(state.onEntry());
            expressions.addAll(state.onExit());
            expressions.addAll(state.animations().values());
            for (var transition : state.transitions()) expressions.add(transition.condition());
        }
        assertTrue(expressions.size() >= 300, () -> "Only " + expressions.size() + " fixture expressions retained");
        var c = context().setQuery("ground_speed", 0.2).setQuery("vertical_speed", -0.1)
                .setQuery("life_time", 10).setQuery("anim_time", 0.25).setQuery("head_y_rotation", 30)
                .setQuery("head_x_rotation", -10).setQuery("ysm.head_yaw", 30).setQuery("ysm.head_pitch", -10);
        for (String expression : expressions) {
            var program = assertDoesNotThrow(() -> YsmExpressionRuntime.compile(expression), expression);
            double value = assertDoesNotThrow(() -> program.evaluate(c), expression);
            assertTrue(Double.isFinite(value), () -> "Non-finite result for " + expression);
        }
    }

    private static void collectExpressions(Set<String> target, List<YsmTypedValue> values) {
        for (var value : values) if (!value.isFloat()) target.add(value.rawString());
    }
}
