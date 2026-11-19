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
        var c = context().set("q.is_sneaking", 1).set("speed", 0.5);
        assertEquals(1, YsmExpressionRuntime.evaluate("q.is_sneaking && v.speed > 0.25", c), 1e-9);
        assertEquals(1, YsmExpressionRuntime.evaluate("is_sneaking", c), 1e-9);
        assertEquals(0, YsmExpressionRuntime.condition("q.is_sneaking == 0", c) ? 1 : 0);
        assertTrue(YsmExpressionRuntime.condition("q.is_sneaking", c));
    }

    @Test void controlTokensSupportSingleAndTwoArgumentForms() {
        var c = new YsmExpressionRuntime.MapContext(Map.of(), Set.of(
                "ctrl.hold(':bow')", "ctrl.hold('mainhand',':bow')", "use('bow')"));
        assertEquals(1, YsmExpressionRuntime.evaluate("ctrl.hold(':bow')", c), 1e-9);
        assertEquals(1, YsmExpressionRuntime.evaluate("q.ctrl.hold(':bow')", c), 1e-9);
        assertEquals(1, YsmExpressionRuntime.evaluate("ctrl.hold('mainhand', ':bow')", c), 1e-9);
        assertEquals(1, YsmExpressionRuntime.evaluate("ctrl.use('bow')", c), 1e-9);
        assertEquals(0, YsmExpressionRuntime.evaluate("ctrl.hold(':sword')", c), 1e-9);
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
    }

    @Test void malformedExpressionThrows() {
        assertThrows(IllegalArgumentException.class, () -> YsmExpressionRuntime.evaluate("1 +", context()));
    }
}
