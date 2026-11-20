package cn.minerealms.rigworks.ysm.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class YsmRuntimeBridgeTest {
    @Test void bodyBindingExcludesFirstPersonAndExtraModelControllers() {
        var main = controller("player.parallel_0", "main");
        var tac = controller("player.main", "tac");
        var arm = controller("player.main", "arm");
        var extra = controller("player.main", "extra");
        var model = new YsmModelSource.YsmModelView(List.of(), List.of(main, arm, extra, tac));
        var controllers = YsmRuntimeBridge.bodyControllers(model);
        assertEquals(List.of(tac, main), controllers);
        assertEquals(Set.of("main/parallel0", "tac/main"), YsmRuntimeBridge.defaultOverrides(controllers));
    }

    @Test void legacyControllerSlotNamesUseTheSameDefaultSlot() {
        assertEquals("pre_parallel7", YsmDefaultAnimationController.controllerSlot("player_ctrl_pre_parallel_7"));
        assertEquals("hold_mainhand", YsmDefaultAnimationController.controllerSlot("player.hold_mainhand"));
    }

    private static YsmModelSource.YsmControllerView controller(String name, String category) {
        return new YsmModelSource.YsmControllerView(name, category, 0, "default", Map.of());
    }
}
