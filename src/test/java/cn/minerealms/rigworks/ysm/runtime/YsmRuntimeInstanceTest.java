package cn.minerealms.rigworks.ysm.runtime;

import cn.minerealms.rigworks.character.core.PlayerVisualInput;
import cn.minerealms.rigworks.ysm.model.YsmAnimationReference;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class YsmRuntimeInstanceTest {
    @Test void stateIsPerOwnerAndARepeatedInputSequenceIsIgnored() {
        var model = modelWithEntryInstruction();
        var first = new YsmRuntimeInstance(new YsmRuntimeInstance.Definition(model), UUID.randomUUID());
        var second = new YsmRuntimeInstance(new YsmRuntimeInstance.Definition(model), UUID.randomUUID());
        var input = PlayerVisualInput.builder(1, .05).build();

        first.update(input, null, YsmRuntimeInstance.OwnerState.EMPTY);
        first.update(input, null, YsmRuntimeInstance.OwnerState.EMPTY);
        second.update(input, null, YsmRuntimeInstance.OwnerState.EMPTY);

        assertEquals(1, first.value("v.entry_count"));
        assertEquals(1, first.value("v.sample_count"), "a repeated tick must not evaluate controller expressions twice");
        assertEquals(1, second.value("v.entry_count"));
        first.update(PlayerVisualInput.builder(2, .1).build(), null, YsmRuntimeInstance.OwnerState.EMPTY);
        assertEquals(2, first.value("v.sample_count"));
        assertEquals(1, second.value("v.sample_count"));
        first.close();
        assertTrue(first.isClosed());
        first.update(PlayerVisualInput.builder(3, .15).build(), null, YsmRuntimeInstance.OwnerState.EMPTY);
        assertEquals(0, first.value("v.sample_count"), "closed owners must not retain or advance variables");
        assertEquals(1, second.value("v.entry_count"));
        second.close();
    }

    @Test void definitionsCanBeSharedWithoutSharingControllerState() {
        var definition = new YsmRuntimeInstance.Definition(modelWithEntryInstruction());
        var first = new YsmRuntimeInstance(definition, UUID.randomUUID());
        var second = new YsmRuntimeInstance(definition, UUID.randomUUID());
        assertSame(first.definition(), second.definition());
        var input = PlayerVisualInput.builder(1, .05).build();
        first.update(input, null, YsmRuntimeInstance.OwnerState.EMPTY);
        assertEquals(1, first.value("v.entry_count"));
        assertEquals(0, second.value("v.entry_count"));
        second.update(input, null, YsmRuntimeInstance.OwnerState.EMPTY);
        assertEquals(1, second.value("v.entry_count"));
        first.close();
        second.close();
    }

    @Test void liveQueriesBlinkAndOptionalInputsBelongToTheObservedOwner() {
        var definition = new YsmRuntimeInstance.Definition(modelWithEntryInstruction());
        var first = new YsmRuntimeInstance(definition, new UUID(0, 0));
        var second = new YsmRuntimeInstance(definition, new UUID(0, 1));
        var sword = new YsmItemStateBridge.HeldItem("minecraft:iron_sword", Set.of("minecraft:swords"), false, "none", false);
        var optional = new YsmAnimationInputProvider.Snapshot(Map.of("tac_holding_gun", 1d), Set.of(), Map.of());
        var owner = new YsmRuntimeInstance.OwnerState(7, 30, 60, .1, .2, .3, false,
                sword, YsmItemStateBridge.HeldItem.EMPTY, optional);
        var input = PlayerVisualInput.builder(85, 4.25).orientation(10, 35, 20).build();
        first.update(input, null, owner);
        second.update(input, null, YsmRuntimeInstance.OwnerState.EMPTY);

        assertEquals(7, first.value("q.health"));
        assertEquals(20, second.value("q.health"));
        assertEquals(1, first.value("q.hold_sword"));
        assertEquals(0, second.value("q.hold_sword"));
        assertEquals(1, first.value("q.tac_holding_gun"));
        assertEquals(0, second.value("q.tac_holding_gun"));
        assertEquals(25, first.value("q.ysm.head_yaw"));
        assertEquals(20, first.value("q.ysm.head_pitch"));
        assertEquals(.3, first.value("q.position_delta(2)"));
        assertEquals(4.25, first.value("q.life_time"));
        assertEquals(0, first.value("q.ysm.is_close_eyes"));
        assertEquals(1, second.value("q.ysm.is_close_eyes"));
        first.close();
        second.close();
    }

    @Test void staleAndRewindingInputsCannotResetAnOwnersClock() {
        var runtime = new YsmRuntimeInstance(new YsmRuntimeInstance.Definition(modelWithEntryInstruction()), UUID.randomUUID());
        runtime.update(PlayerVisualInput.builder(10, 1).build(), null, YsmRuntimeInstance.OwnerState.EMPTY);
        runtime.update(PlayerVisualInput.builder(9, 1.1).build(), null, YsmRuntimeInstance.OwnerState.EMPTY);
        runtime.update(PlayerVisualInput.builder(11, .5).build(), null, YsmRuntimeInstance.OwnerState.EMPTY);
        assertEquals(1, runtime.value("v.sample_count"));
        assertEquals(1, runtime.value("q.time"));
        runtime.update(PlayerVisualInput.builder(12, 1.1).build(), null, YsmRuntimeInstance.OwnerState.EMPTY);
        assertEquals(2, runtime.value("v.sample_count"));
        assertEquals(.1, runtime.value("q.delta_time"), 1e-9);
        runtime.close();
    }

    private static YsmModelSource.YsmModelView modelWithEntryInstruction() {
        var state = new YsmModelSource.YsmStateView(Map.of(), List.of(),
                List.of(new YsmAnimationReference("idle", "v.sample_count = v.sample_count + 1; 1")),
                List.of("v.entry_count = v.entry_count + 1"), List.of(), null, false, List.of());
        var controller = new YsmModelSource.YsmControllerView("owner", "main", 0, "idle",
                Map.of("idle", state));
        var clip = new YsmModelSource.YsmAnimationView("idle", "main", 1, 1, 1, List.of());
        return new YsmModelSource.YsmModelView(List.of("idle"), List.of(controller), List.of(clip), List.of());
    }
}
