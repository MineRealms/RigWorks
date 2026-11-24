package cn.minerealms.rigworks.ysm.runtime;

import cn.minerealms.rigworks.character.core.PlayerVisualInput;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class YsmItemStateBridgeTest {
    private static boolean matches(YsmItemStateBridge.State state, String expression) {
        var input = PlayerVisualInput.builder(1, .05).usingItem(state.matches("using")).build();
        return YsmExpressionRuntime.condition(expression, YsmExpressionRuntime.context(input, null, state));
    }

    @Test void exactIdsAndTagsSelectTheLogicalHandForEitherDominantArm() {
        var main = new YsmItemStateBridge.HeldItem("addon:longbow", Set.of("forge:bows"), true, "bow", false);
        var off = new YsmItemStateBridge.HeldItem("minecraft:shield", Set.of("minecraft:shields"), false, "block", false);
        for (boolean mainRight : new boolean[]{true, false}) {
            var state = YsmItemStateBridge.derive(null, mainRight ? main : off, mainRight ? off : main, mainRight);
            assertTrue(matches(state, "ctrl.hold('mainhand','$addon:longbow')"));
            assertTrue(matches(state, "q.ctrl.use('mainhand','#forge:bows')"));
            assertTrue(matches(state, "ctrl.hold('offhand','$minecraft:shield')"));
            assertFalse(matches(state, "ctrl.hold('offhand','$addon:longbow')"));
            assertTrue(matches(state, "ctrl.use('offhand','#minecraft:shields')"));
            assertTrue(matches(state, "ctrl.use('" + (mainRight ? "right" : "left") + "',':bow')"));
            assertTrue(state.holdBow());
            assertTrue(state.useBow());
            assertTrue(state.holdShield());
            assertFalse(state.useShield());
        }
    }

    @Test void authoredUseFollowsGlobalItemUseWhileQueriesRetainTheActualActiveHand() {
        var main = new YsmItemStateBridge.HeldItem("minecraft:iron_sword", Set.of("minecraft:swords"), false, "none", false);
        var off = new YsmItemStateBridge.HeldItem("minecraft:shield", Set.of("minecraft:shields"), true, "block", false);
        for (boolean mainRight : new boolean[]{true, false}) {
            var state = YsmItemStateBridge.derive(null, mainRight ? main : off, mainRight ? off : main, mainRight);
            assertTrue(matches(state, "ctrl.use('mainhand',':sword')"));
            assertTrue(matches(state, "use('mainhand','$minecraft:iron_sword')"));
            assertTrue(matches(state, "ctrl.use('offhand','#minecraft:shields')"));
            assertFalse(matches(state, "ctrl.use('offhand',':sword')"));
            assertFalse(matches(state, "q.use_sword"));
            assertTrue(matches(state, "q.use_shield"));

            var released = YsmExpressionRuntime.context(PlayerVisualInput.builder(1, .05).build(), null, state);
            assertFalse(YsmExpressionRuntime.condition("ctrl.use('mainhand',':sword')", released));
            var sleeping = YsmExpressionRuntime.context(PlayerVisualInput.builder(1, .05)
                    .usingItem(true).sleeping(true).build(), null, state);
            assertFalse(YsmExpressionRuntime.condition("ctrl.use('mainhand',':sword')", sleeping));
            assertFalse(YsmExpressionRuntime.condition("use('offhand',':shield')", sleeping));
            assertTrue(YsmExpressionRuntime.condition("ctrl.hold('mainhand',':sword')", sleeping));
            assertTrue(YsmExpressionRuntime.condition("q.use_shield", sleeping));
            assertTrue(matches(state, "ctrl.use('mainhand',':sword')"), "Other contexts must not mutate cached selectors");
        }
    }

    @Test void globalUseCanTestAnEmptyHandWhileTheOtherHandUsesAnItem() {
        var off = new YsmItemStateBridge.HeldItem("minecraft:bow", Set.of(), true, "bow", false);
        var state = YsmItemStateBridge.derive(null, YsmItemStateBridge.HeldItem.EMPTY, off, true);
        assertTrue(matches(state, "ctrl.use('mainhand','empty')"));
        assertTrue(matches(state, "ctrl.use('right','$minecraft:air')"));
        assertFalse(matches(state, "ctrl.use('offhand','empty')"));
        assertTrue(matches(state, "q.use_offhand_bow"));
    }

    @Test void classificationAndUseAnimationBothRemainAvailableToAuthoredControllers() {
        var sword = new YsmItemStateBridge.HeldItem("minecraft:iron_sword", Set.of(), true, "block", false);
        var state = YsmItemStateBridge.derive(null, sword, YsmItemStateBridge.HeldItem.EMPTY, true);
        assertTrue(matches(state, "ctrl.hold('mainhand',':sword')"));
        assertTrue(matches(state, "ctrl.use('mainhand',':block')"));
        assertTrue(matches(state, "use('mainhand','sword')"));
        assertTrue(matches(state, "ctrl.hold(':iron_sword')"), "Earlier RigWorks item-path aliases stay valid");
        assertFalse(matches(state, "ctrl.hold('mainhand','#missing:swords')"));
        assertFalse(matches(state, "ctrl.hold('mainhand','$other:iron_sword')"), "Exact IDs retain the namespace");
    }

    @Test void emptySelectorsRespectHandsAndDoNotPretendThatTheOtherHandIsEmpty() {
        var item = new YsmItemStateBridge.HeldItem("addon:wand", Set.of(), false, "none", false);
        var state = YsmItemStateBridge.derive(null, YsmItemStateBridge.HeldItem.EMPTY, item, true);
        assertTrue(matches(state, "ctrl.hold('mainhand','empty')"));
        assertTrue(matches(state, "hold('right','empty')"));
        assertTrue(matches(state, "ctrl.hold('mainhand','$minecraft:air')"));
        assertTrue(matches(state, "ctrl.hold('mainhand',':none')"));
        assertFalse(matches(state, "ctrl.hold('offhand','empty')"));
        assertFalse(matches(state, "ctrl.use('mainhand','empty')"));
        assertTrue(matches(state, "ctrl.hold('offhand',':none')"));
    }

    @Test void standardTagsCoverModdedToolsAndRangedItemsWithoutTheirVanillaIds() {
        for (var pair : new String[][]{{"axes", "axe"}, {"pickaxes", "pickaxe"}, {"shovels", "shovel"},
                {"hoes", "hoe"}, {"swords", "sword"}, {"crossbows", "crossbow"}, {"bows", "bow"},
                {"shields", "shield"}, {"fishing_rods", "fishing_rod"}, {"tridents", "spear"}}) {
            var item = new YsmItemStateBridge.HeldItem("addon:custom", Set.of("forge:" + pair[0]), false, "none", false);
            assertEquals(pair[1], YsmItemStateBridge.kindOf(item));
            var state = YsmItemStateBridge.derive(null, item, YsmItemStateBridge.HeldItem.EMPTY, true);
            assertTrue(matches(state, "ctrl.hold('mainhand',':" + pair[1] + "')"));
            assertTrue(matches(state, "ctrl.hold('mainhand','#forge:" + pair[0] + "')"));
        }
    }

    @Test void usingAnUnclassifiedItemStillMatchesItsFullIdAndTags() {
        var item = new YsmItemStateBridge.HeldItem("addon:magic_wand", Set.of("addon:magic_tools"), true, "none", false);
        var state = YsmItemStateBridge.derive(null, item, YsmItemStateBridge.HeldItem.EMPTY, true);
        assertTrue(matches(state, "ctrl.use('mainhand','$addon:magic_wand')"));
        assertTrue(matches(state, "ctrl.use('mainhand','#addon:magic_tools')"));
        assertFalse(state.matches("hold('')"));
    }

    @Test void aBoundedOwnerCacheReusesUnchangedItemsAndInvalidatesChangedSelectors() {
        var cache = new YsmItemStateBridge.Cache();
        var sword = new YsmItemStateBridge.HeldItem("minecraft:iron_sword", Set.of("minecraft:swords"), false, "none", false);
        var initial = cache.derive(null, sword, null, true);
        var equal = new YsmItemStateBridge.HeldItem(sword.id(), Set.of("minecraft:swords"), false, "none", false);
        assertSame(initial, cache.derive(null, equal, YsmItemStateBridge.HeldItem.EMPTY, true));
        assertSame(initial.functionNames(), cache.derive(null, equal, null, true).functionNames());
        var using = new YsmItemStateBridge.HeldItem(sword.id(), sword.tags(), true, "none", false);
        var changed = cache.derive(null, using, null, true);
        assertNotSame(initial, changed);
        assertTrue(matches(changed, "ctrl.use('mainhand','$minecraft:iron_sword')"));
        assertFalse(matches(initial, "ctrl.use('mainhand','$minecraft:iron_sword')"));
        var swapped = cache.derive(null, using, null, false);
        assertTrue(matches(swapped, "ctrl.use('offhand','$minecraft:iron_sword')"));
        assertFalse(matches(swapped, "ctrl.use('mainhand','$minecraft:iron_sword')"));
        cache.clear();
        assertNotSame(swapped, cache.derive(null, using, null, false));
        assertNotSame(initial, new YsmItemStateBridge.Cache().derive(null, sword, null, true));
        assertThrows(UnsupportedOperationException.class, () -> initial.states().clear());
        assertThrows(UnsupportedOperationException.class, () -> initial.functionNames().clear());
    }
}
