package cn.minerealms.rigworks.ysm.runtime;

import cn.minerealms.rigworks.character.core.PlayerActionState;
import cn.minerealms.rigworks.character.core.PlayerVisualInput;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class YsmDefaultAnimationControllerTest {
    private static final class Sink implements YsmControllerRuntime.AnimationSink {
        final Map<String, String> layers = new LinkedHashMap<>();
        int shows, hides;
        @Override public void show(String clip, float weight) { fail("expected named layers"); }
        @Override public void hide() { fail("expected named layers"); }
        @Override public boolean hasClip(String clip) { fail("availability must be indexed at construction"); return false; }
        @Override public void showLayer(String layer, String clip, float weight) { layers.put(layer, clip); shows++; }
        @Override public void hideLayer(String layer) { layers.remove(layer); hides++; }
        String layer(String category, String slot) { return layers.get("ysm:default:" + category + ":" + slot); }
    }
    private static final class Fixture {
        final Map<String, Sink> sinks = new LinkedHashMap<>();
        final List<YsmModelSource.YsmAnimationView> clips = new ArrayList<>();
        Fixture clips(String category, String... names) {
            for (String name : names) clips.add(new YsmModelSource.YsmAnimationView(name, category, 0, 1f, 1, List.of()));
            return this;
        }
        YsmDefaultAnimationController controller() { return controller("main"); }
        YsmDefaultAnimationController controller(String category) {
            return new YsmDefaultAnimationController(clips, key -> sinks.computeIfAbsent(key, ignored -> new Sink()), category);
        }
        Sink sink(String category) { return sinks.get(category); }
    }
    private static PlayerVisualInput.Builder input(long tick) { return PlayerVisualInput.builder(tick, tick / 20d); }
    private static YsmExpressionRuntime.MapContext context() { return new YsmExpressionRuntime.MapContext(Map.of(), Set.of()); }
    private static PlayerActionState action(String pose, boolean climb, boolean water, boolean riptide, int hurt) {
        return new PlayerActionState(pose, PlayerActionState.AirPhase.GROUNDED, climb, water, water, false,
                riptide, false, hurt, "none", false, false);
    }

    @Test void locomotionFollowsYsmPriorityAndOnlyChangesTheSinkWhenSelectionChanges() {
        var fixture = new Fixture().clips("main", "idle", "walk", "run", "sneaking", "sneak", "swim", "death", "sleep", "riptide");
        var runtime = fixture.controller();
        var state = context();
        runtime.update(input(0).build(), state, Map.of());
        assertEquals("idle", fixture.sink("main").layer("main", "main"));
        runtime.update(input(1).build(), state, Map.of());
        assertEquals(1, fixture.sink("main").shows);
        runtime.update(input(2).motion(1, 0, 0).build(), state, Map.of());
        assertEquals("walk", fixture.sink("main").layer("main", "main"));
        runtime.update(input(3).motion(1, 0, 0).sprinting(true).build(), state, Map.of());
        assertEquals("run", fixture.sink("main").layer("main", "main"));
        runtime.update(input(4).motion(1, 0, 0).sprinting(true).crouching(true).build(), state, Map.of());
        assertEquals("sneak", fixture.sink("main").layer("main", "main"));
        runtime.update(input(5).crouching(true).build(), state, Map.of());
        assertEquals("sneaking", fixture.sink("main").layer("main", "main"));
        runtime.update(input(6).swimming(true).sleeping(true).build(), state, Map.of());
        assertEquals("sleep", fixture.sink("main").layer("main", "main"));
        runtime.update(input(7).sleeping(true).dead(true).build(), state, Map.of());
        assertEquals("death", fixture.sink("main").layer("main", "main"));
    }

    @Test void authoredParallelLayersRunAlongsideLocomotionAndResetTogether() {
        var fixture = new Fixture().clips("main", "idle", "walk", "pre_parallel0", "parallel0", "parallel7", "parallel8");
        var runtime = fixture.controller();
        runtime.update(input(0).build(), context(), Map.of());
        var sink = fixture.sink("main");
        assertEquals(List.of("pre_parallel0", "idle", "parallel0", "parallel7"), List.copyOf(sink.layers.values()));
        runtime.update(input(1).motion(2, 0, 0).build(), context(), Map.of());
        assertTrue(sink.layers.containsValue("walk"));
        assertFalse(sink.layers.containsValue("idle"));
        assertEquals(4, sink.layers.size());
        runtime.reset();
        assertTrue(sink.layers.isEmpty());
    }

    @Test void authoredControllerReplacesItsOwnSlotWithoutRemovingLocomotionOrOtherParallelSlots() {
        var fixture = new Fixture().clips("main", "idle", "walk", "parallel0", "parallel1", "hold_mainhand:sword");
        var runtime = new YsmDefaultAnimationController(fixture.clips,
                key -> fixture.sinks.computeIfAbsent(key, ignored -> new Sink()), "main", Set.of("main/parallel0"));
        var sword = new YsmItemStateBridge.HeldItem("minecraft:iron_sword", Set.of(), false, "none", false);
        runtime.update(input(0).build(), context(), Map.of(), sword, null);
        var sink = fixture.sink("main");
        assertEquals("idle", sink.layer("main", "main"));
        assertEquals("hold_mainhand:sword", sink.layer("main", "hold_mainhand"));
        assertNull(sink.layer("main", "parallel0"));
        assertEquals("parallel1", sink.layer("main", "parallel1"));
        runtime.update(input(1).motion(1, 0, 0).build(), context(), Map.of(), sword, null);
        assertEquals("walk", sink.layer("main", "main"));
        assertEquals("parallel1", sink.layer("main", "parallel1"));
    }

    @Test void authoredMainControllerReplacesOnlyTheMainCategoryLocomotion() {
        var fixture = new Fixture().clips("main", "idle", "parallel0", "hold_mainhand:sword")
                .clips("tac", "tac:idle", "tac:hold:rifle");
        var runtime = new YsmDefaultAnimationController(fixture.clips,
                key -> fixture.sinks.computeIfAbsent(key, ignored -> new Sink()), "main", Set.of("main/main"));
        runtime.update(input(0).build(), context(), Map.of());
        assertNull(fixture.sink("main").layer("main", "main"));
        assertEquals("parallel0", fixture.sink("main").layer("main", "parallel0"));
        runtime.update(input(1).build(), context().setQuery("tac_holding_gun", 1), Map.of());
        assertEquals("tac:idle", fixture.sink("tac").layer("main", "main"));
    }

    @Test void armCategoryRunsYsmWeaponVisibilityActionsWithoutReplacingBodyLocomotion() {
        var fixture = new Fixture().clips("main", "idle", "walk")
                .clips("arm", "hold_mainhand:sword", "hold_mainhand:empty");
        var body = fixture.controller("main");
        var arms = fixture.controller("arm");
        var sword = new YsmItemStateBridge.HeldItem("minecraft:iron_sword", Set.of(), false, "none", false);

        body.update(input(0).build(), context(), Map.of(), sword, null);
        arms.update(input(0).build(), context(), Map.of(), sword, null);
        assertEquals("idle", fixture.sink("main").layer("main", "main"));
        assertEquals("hold_mainhand:sword", fixture.sink("arm").layer("arm", "hold_mainhand"));

        arms.update(input(1).build(), context(), Map.of(), YsmItemStateBridge.HeldItem.EMPTY, null);
        assertEquals("hold_mainhand:empty", fixture.sink("arm").layer("arm", "hold_mainhand"));
    }

    @Test void crawlLadderAirAndRidingUseTheirAuthoredActions() {
        var fixture = new Fixture().clips("main", "climb", "climbing", "ladder_up", "ladder_stillness", "ladder_down",
                "jump", "swim_stand", "elytra_fly", "fly", "ride", "sit", "boat");
        var runtime = fixture.controller();
        var state = context();
        runtime.update(input(0).actionState(action("swimming", false, false, false, 0)).build(), state, Map.of());
        assertEquals("climbing", fixture.sink("main").layer("main", "main"));
        runtime.update(input(1).actionState(action("swimming", false, false, false, 0)).motion(1, 0, 0).build(), state, Map.of());
        assertEquals("climb", fixture.sink("main").layer("main", "main"));
        runtime.update(input(2).actionState(action("standing", true, false, false, 0)).motion(0, 0, -.1).build(), state, Map.of());
        assertEquals("ladder_down", fixture.sink("main").layer("main", "main"));
        runtime.update(input(3).onGround(false).actionState(action("standing", false, true, false, 0)).build(), state, Map.of());
        assertEquals("swim_stand", fixture.sink("main").layer("main", "main"));
        runtime.update(input(4).onGround(false).build(), state, Map.of());
        assertEquals("jump", fixture.sink("main").layer("main", "main"));
        runtime.update(input(5).riding(true).build(), state, Map.of("vehicle.type", "boat"));
        assertEquals("boat", fixture.sink("main").layer("main", "main"));
    }

    @Test void bodyNeverFallsBackToFirstPersonArmClips() {
        var fixture = new Fixture().clips("main", "idle").clips("arm", "idle", "walk");
        var body = fixture.controller();
        body.update(input(0).motion(1, 0, 0).build(), context(), Map.of());
        assertTrue(fixture.sink("main").layers.isEmpty());
        assertNull(fixture.sink("arm"));
        var arms = fixture.controller("arm");
        arms.update(input(0).motion(1, 0, 0).build(), context(), Map.of());
        assertEquals("walk", fixture.sink("arm").layer("arm", "main"));
    }

    @Test void itemRulesPreferExactIdThenTagThenClassification() {
        var fixture = new Fixture().clips("main", "idle", "hold_mainhand$minecraft:diamond_sword",
                "hold_mainhand#custom:special", "hold_mainhand:sword", "hold_offhand:empty");
        var runtime = fixture.controller();
        var item = new YsmItemStateBridge.HeldItem("minecraft:diamond_sword", Set.of("custom:special"), false, "none", false);
        runtime.update(input(0).build(), context(), Map.of(), item, YsmItemStateBridge.HeldItem.EMPTY);
        assertEquals("hold_mainhand$minecraft:diamond_sword", fixture.sink("main").layer("main", "hold_mainhand"));
        assertEquals("hold_offhand:empty", fixture.sink("main").layer("main", "hold_offhand"));
        item = new YsmItemStateBridge.HeldItem("minecraft:iron_sword", Set.of("custom:special"), false, "none", false);
        runtime.update(input(1).build(), context(), Map.of(), item, YsmItemStateBridge.HeldItem.EMPTY);
        assertEquals("hold_mainhand#custom:special", fixture.sink("main").layer("main", "hold_mainhand"));
        item = new YsmItemStateBridge.HeldItem("minecraft:iron_sword", Set.of(), false, "none", false);
        runtime.update(input(2).build(), context(), Map.of(), item, YsmItemStateBridge.HeldItem.EMPTY);
        assertEquals("hold_mainhand:sword", fixture.sink("main").layer("main", "hold_mainhand"));
    }

    @Test void usingAndAcceptedSwingsSuppressTheCorrespondingHoldAndRetriggerImmediately() {
        var fixture = new Fixture().clips("main", "idle", "hold_mainhand:sword", "use_mainhand:bow", "swing:sword");
        var runtime = fixture.controller();
        var sword = new YsmItemStateBridge.HeldItem("minecraft:iron_sword", Set.of(), false, "none", false);
        runtime.update(input(0).build(), context(), Map.of(), sword, YsmItemStateBridge.HeldItem.EMPTY);
        runtime.update(input(1).swinging(true).swingAction(1, true, 0).build(), context(), Map.of(), sword, null);
        assertNull(fixture.sink("main").layer("main", "hold_mainhand"));
        assertEquals("swing:sword", fixture.sink("main").layer("main", "swing"));
        runtime.update(input(2).swinging(false).swingAction(1, true, .5f).build(), context(), Map.of(), sword, null);
        assertNull(fixture.sink("main").layer("main", "hold_mainhand"), "hold must stay suppressed beyond the swing trigger tick");
        int shows = fixture.sink("main").shows;
        runtime.update(input(3).swinging(true).swingAction(2, true, 0).build(), context(), Map.of(), sword, null);
        assertEquals(shows + 1, fixture.sink("main").shows, "accepted retrigger must restart even if the prior swing is active");
        var bow = new YsmItemStateBridge.HeldItem("minecraft:bow", Set.of(), true, "bow", false);
        runtime.update(input(4).usingItem(true).build(), context(), Map.of(), bow, null);
        assertEquals("use_mainhand:bow", fixture.sink("main").layer("main", "use"));
        assertNull(fixture.sink("main").layer("main", "hold_mainhand"));
    }

    @Test void offhandUseAndAcceptedSwingSelectOnlyTheCorrespondingAuthoredActions() {
        var fixture = new Fixture().clips("main", "hold_mainhand:sword", "hold_offhand:sword",
                "use_mainhand:bow", "use_offhand:bow", "swing:sword", "swing_offhand:sword");
        var runtime = fixture.controller();
        var sword = new YsmItemStateBridge.HeldItem("minecraft:iron_sword", Set.of(), false, "none", false);
        var bow = new YsmItemStateBridge.HeldItem("minecraft:bow", Set.of(), true, "bow", false);
        var state = context().setQuery("using_offhand", 1);
        runtime.update(input(0).usingItem(true).build(), state, Map.of(), sword, bow);
        assertEquals("use_offhand:bow", fixture.sink("main").layer("main", "use"));
        assertEquals("hold_mainhand:sword", fixture.sink("main").layer("main", "hold_mainhand"));
        assertNull(fixture.sink("main").layer("main", "hold_offhand"));
        state.setQuery("using_offhand", 0).setQuery("swinging_offhand", 1);
        runtime.update(input(1).swingAction(1, true, 0).build(), state, Map.of(), sword, sword);
        assertEquals("swing_offhand:sword", fixture.sink("main").layer("main", "swing"));
        assertEquals("hold_mainhand:sword", fixture.sink("main").layer("main", "hold_mainhand"));
        runtime.update(input(2).swingAction(1, true, .5f).build(), state, Map.of(), sword, sword);
        assertNull(fixture.sink("main").layer("main", "hold_offhand"));
        runtime.update(input(3).swingAction(1, false, 0).build(), state, Map.of(), sword, sword);
        assertEquals("hold_offhand:sword", fixture.sink("main").layer("main", "hold_offhand"));
    }

    @Test void chargedCrossbowAndItemClassificationKeepYsmSpecialCasePriority() {
        var fixture = new Fixture().clips("main", "idle", "hold_mainhand:block", "hold_mainhand:shield",
                "hold_mainhand$minecraft:crossbow", "hold_mainhand:charged_crossbow");
        var runtime = fixture.controller();
        var shield = new YsmItemStateBridge.HeldItem("minecraft:shield", Set.of(), false, "block", false);
        runtime.update(input(0).build(), context(), Map.of(), shield, null);
        assertEquals("hold_mainhand:shield", fixture.sink("main").layer("main", "hold_mainhand"));
        var charged = new YsmItemStateBridge.HeldItem("minecraft:crossbow", Set.of(), false, "crossbow", true);
        runtime.update(input(1).build(), context(), Map.of(), charged, null);
        assertEquals("hold_mainhand:charged_crossbow", fixture.sink("main").layer("main", "hold_mainhand"));
    }

    @Test void contextOnlyRulesRequireTheMatchingLogicalHand() {
        var fixture = new Fixture().clips("main", "hold_mainhand:bow", "hold_offhand:bow");
        var runtime = fixture.controller();
        var state = new YsmExpressionRuntime.MapContext(Map.of(), Set.of("ctrl.hold('bow')", "hold('offhand','bow')"));
        runtime.update(input(0).build(), state, Map.of());
        assertNull(fixture.sink("main").layer("main", "hold_mainhand"));
        assertEquals("hold_offhand:bow", fixture.sink("main").layer("main", "hold_offhand"));
    }

    @Test void taCzSelectsGunIdBeforePistolRpgAndRifleDefaults() {
        var fixture = new Fixture().clips("main", "idle").clips("tac", "tac:idle", "tac:hold$tacz:glock_17",
                "tac:hold:pistol", "tac:hold:rpg", "tac:hold:rifle", "tac:aim:pistol", "tac:run:pistol");
        var runtime = fixture.controller();
        var state = context().setQuery("tac_holding_gun", 1);
        runtime.update(input(0).build(), state, Map.of("tac.gun_id", "tacz:glock_17", "tac.gun_type", "pistol"));
        assertEquals("tac:idle", fixture.sink("tac").layer("main", "main"));
        assertEquals("tac:hold$tacz:glock_17", fixture.sink("tac").layer("main", "hold_mainhand"));
        runtime.update(input(1).build(), state, Map.of("tac.gun_id", "tacz:other", "tac.gun_type", "pistol"));
        assertEquals("tac:hold:pistol", fixture.sink("tac").layer("main", "hold_mainhand"));
        runtime.update(input(2).build(), state, Map.of("tac.gun_type", "rpg"));
        assertEquals("tac:hold:rpg", fixture.sink("tac").layer("main", "hold_mainhand"));
        runtime.update(input(3).build(), state, Map.of("tac.gun_type", "sniper"));
        assertEquals("tac:hold:rifle", fixture.sink("tac").layer("main", "hold_mainhand"));
        runtime.update(input(4).sprinting(true).build(), state.setQuery("tac_aiming", 1), Map.of("tac.gun_type", "pistol"));
        assertEquals("tac:aim:pistol", fixture.sink("tac").layer("main", "hold_mainhand"));
    }

    @Test void taCzCrawlingOverridesAimAndRun() {
        var fixture = new Fixture().clips("tac", "tac:climb:rifle", "tac:climbing:rifle");
        var runtime = fixture.controller();
        var state = context().setQuery("tac_holding_gun", 1).setQuery("tac_aiming", 1);
        runtime.update(input(0).sprinting(true).motion(1, 0, 0)
                .actionState(action("swimming", false, false, false, 0)).build(), state, Map.of());
        assertEquals("tac:climb:rifle", fixture.sink("tac").layer("main", "hold_mainhand"));
        runtime.update(input(1).actionState(action("swimming", false, false, false, 0)).build(), state, Map.of());
        assertEquals("tac:climbing:rifle", fixture.sink("tac").layer("main", "hold_mainhand"));
    }

    @Test void gunActionPriorityAndFollowThroughDoNotDependOnCooldownRemainingPositive() {
        var fixture = new Fixture().clips("tac", "tac:hold:rifle", "tac:reload:rifle", "tac:melee:rifle", "tac:hold:fire:rifle");
        var runtime = fixture.controller();
        var state = context().setQuery("tac_holding_gun", 1).setQuery("tac_reloading", 1)
                .setQuery("tac_melee", 1).setQuery("tac_firing", 1);
        runtime.update(input(0).build(), state, Map.of());
        assertEquals("tac:reload:rifle", fixture.sink("tac").layer("main", "gun_action"));
        state.setQuery("tac_reloading", 0).setQuery("tac_melee", 0).setQuery("tac_firing", 0);
        runtime.update(input(1).build(), state, Map.of());
        assertEquals("tac:reload:rifle", fixture.sink("tac").layer("main", "gun_action"));
        runtime.update(input(20).build(), state, Map.of());
        assertNull(fixture.sink("tac").layer("main", "gun_action"));
        runtime.update(input(21).build(), state.setQuery("tac_firing", 1), Map.of());
        assertEquals("tac:hold:fire:rifle", fixture.sink("tac").layer("main", "gun_action"));
        int shows = fixture.sink("tac").shows;
        runtime.update(input(22).build(), state.setQuery("tac_fire_generation", 1), Map.of());
        assertEquals(shows + 1, fixture.sink("tac").shows);
    }

    @Test void resetAndGunRemovalClearOwnedLayers() {
        var fixture = new Fixture().clips("main", "idle").clips("tac", "tac:hold:rifle", "tac:hold:fire:rifle");
        var runtime = fixture.controller();
        var state = context().setQuery("tac_holding_gun", 1).setQuery("tac_firing", 1);
        runtime.update(input(0).build(), state, Map.of());
        runtime.update(input(1).build(), state.setQuery("tac_holding_gun", 0), Map.of());
        assertTrue(fixture.sink("tac").layers.isEmpty());
        runtime.reset();
        assertTrue(fixture.sink("main").layers.isEmpty());
    }
}
