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

import cn.minerealms.rigworks.character.core.CharacterEquipmentDefinition;
import cn.minerealms.rigworks.character.core.CharacterEquipmentRule;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public final class YsmItemStateBridge {
    private static final CharacterEquipmentRule HELD = new CharacterEquipmentRule(
            "any", Set.of(), Set.of(), Set.of("held"), Set.of(), Set.of(), "held", null);
    private static final CharacterEquipmentRule USING = new CharacterEquipmentRule(
            "any", Set.of(), Set.of(), Set.of("using"), Set.of(), Set.of(), "held", null);
    private static final CharacterEquipmentRule CHARGED = new CharacterEquipmentRule(
            "any", Set.of(), Set.of(), Set.of("charged"), Set.of(), Set.of(), "held", null);

    private YsmItemStateBridge() {}

    public record HeldItem(String id, Set<String> tags, boolean using, String useAnimation, boolean charged) {
        public HeldItem {
            tags = tags == null ? Set.of() : Set.copyOf(tags);
            id = id == null ? "" : id;
            useAnimation = useAnimation == null ? "none" : useAnimation;
        }

        public static final HeldItem EMPTY = new HeldItem("", Set.of(), false, "none", false);

        public boolean empty() { return id.isEmpty(); }
    }

    public record State(Set<String> states, boolean holdBow, boolean useBow, boolean holdSword, boolean useSword,
                        boolean holdCrossbow, boolean useCrossbow, boolean holdShield, boolean useShield,
                        boolean holdOffhandBow, boolean useOffhandBow, Set<String> functionNames) {
        public State {
            states = Set.copyOf(states);
            functionNames = Set.copyOf(functionNames);
        }

        public State(Set<String> states, boolean holdBow, boolean useBow, boolean holdSword, boolean useSword,
                     boolean holdCrossbow, boolean useCrossbow, boolean holdShield, boolean useShield,
                     boolean holdOffhandBow, boolean useOffhandBow) {
            this(states, holdBow, useBow, holdSword, useSword, holdCrossbow, useCrossbow,
                    holdShield, useShield, holdOffhandBow, useOffhandBow, YsmExpressionRuntime.indexFunctions(states));
        }

        public boolean matches(String token) { return token != null && states.contains(token); }
    }

    /** Retains only the latest immutable snapshot; ordinary ticks do not rebuild selector strings. */
    public static final class Cache {
        private CharacterEquipmentDefinition equipment;
        private HeldItem right, left;
        private boolean mainHandRight;
        private State state;

        public State derive(CharacterEquipmentDefinition equipment, HeldItem right, HeldItem left, boolean mainHandRight) {
            right = right == null ? HeldItem.EMPTY : right;
            left = left == null ? HeldItem.EMPTY : left;
            if (state == null || this.equipment != equipment || !right.equals(this.right) || !left.equals(this.left)
                    || mainHandRight != this.mainHandRight) {
                state = YsmItemStateBridge.derive(equipment, right, left, mainHandRight);
                this.equipment = equipment;
                this.right = right;
                this.left = left;
                this.mainHandRight = mainHandRight;
            }
            return state;
        }

        public void clear() { equipment = null; right = left = null; state = null; }
    }

    public static State derive(CharacterEquipmentDefinition equipment, HeldItem right, HeldItem left, boolean mainHandRight) {
        Set<String> states = new LinkedHashSet<>();
        boolean using = right != null && right.using() || left != null && left.using();
        boolean holdBow = false, useBow = false, holdSword = false, useSword = false;
        boolean holdCrossbow = false, useCrossbow = false, holdShield = false, useShield = false;
        boolean holdOffhandBow = false, useOffhandBow = false;

        for (int hand = 0; hand < 2; hand++) {
            boolean rightHand = hand == 0;
            HeldItem item = rightHand ? right : left;
            String arm = rightHand ? "right" : "left";
            String logical = rightHand == mainHandRight ? "mainhand" : "offhand";
            if (item == null || item.empty()) {
                addItemSelector(states, using, arm, logical, "empty");
                addItemSelector(states, using, arm, logical, "$minecraft:air");
                addItemSelector(states, using, arm, logical, ":none");
                continue;
            }
            String suffix = kindOf(item);
            String state = item.using() ? "using" : item.charged() ? "charged" : "held";
            if (USING.matches(arm, item.id(), item.tags(), state, item.useAnimation(), "third_person_" + arm + "_hand"))
                states.add("using");
            else if (CHARGED.matches(arm, item.id(), item.tags(), state, item.useAnimation(), "third_person_" + arm + "_hand"))
                states.add("charged");
            else if (HELD.matches(arm, item.id(), item.tags(), state, item.useAnimation(), "third_person_" + arm + "_hand"))
                states.add("held");

            String itemKey = item.id().substring(Math.max(0, item.id().indexOf(':') + 1));
            addItemSelector(states, using, arm, logical, "$" + item.id());
            for (String tag : item.tags()) addItemSelector(states, using, arm, logical, "#" + tag);
            addItemSelector(states, using, arm, logical, ":" + item.useAnimation());
            if (!suffix.isEmpty()) {
                addItemSelector(states, using, arm, logical, ":" + suffix);
                addItemSelector(states, using, arm, logical, suffix);
            }
            // Earlier RigWorks presets also accepted an unqualified item path.
            addItemSelector(states, using, arm, logical, ":" + itemKey);
            if (equipment != null && equipment.override(arm, item.id(), item.tags(), state,
                    item.useAnimation(), "third_person_" + arm + "_hand") != null)
                states.add("equip('" + arm + "','" + itemKey + "')");

            boolean offhand = logical.equals("offhand");
            boolean bow = suffix.equals("bow"), sword = suffix.equals("sword");
            boolean crossbow = suffix.equals("crossbow"), shield = suffix.equals("shield");
            if (bow) { holdBow = true; if (item.using()) useBow = true; if (offhand) { holdOffhandBow = true; if (item.using()) useOffhandBow = true; } }
            if (sword) { holdSword = true; if (item.using()) useSword = true; }
            if (crossbow) { holdCrossbow = true; if (item.using()) useCrossbow = true; }
            if (shield) { holdShield = true; if (item.using()) useShield = true; }
        }
        return new State(states, holdBow, useBow, holdSword, useSword, holdCrossbow, useCrossbow,
                holdShield, useShield, holdOffhandBow, useOffhandBow);
    }

    private static void addItemSelector(Set<String> states, boolean using, String arm, String logical, String selector) {
        addSelector(states, "hold", arm, logical, selector);
        if (using) addSelector(states, "use", arm, logical, selector);
    }

    private static void addSelector(Set<String> states, String verb, String arm, String logical, String selector) {
        addToken(states, verb + "('" + selector + "')");
        addToken(states, verb + "('" + arm + "','" + selector + "')");
        addToken(states, verb + "('" + logical + "','" + selector + "')");
    }

    private static void addToken(Set<String> states, String token) {
        states.add(token);
        states.add("ctrl." + token);
    }

    public static State sample(net.minecraft.client.player.AbstractClientPlayer player, CharacterEquipmentDefinition equipment) {
        if (player == null) return derive(equipment, HeldItem.EMPTY, HeldItem.EMPTY, true);
        boolean mainRight = player.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT;
        HeldItem main = held(player, player.getMainHandItem(), true);
        HeldItem offhand = held(player, player.getOffhandItem(), false);
        return derive(equipment, mainRight ? main : offhand, mainRight ? offhand : main, mainRight);
    }

    public static HeldItem held(net.minecraft.client.player.AbstractClientPlayer player, net.minecraft.world.item.ItemStack stack, boolean mainHand) {
        if (stack == null || stack.isEmpty()) return HeldItem.EMPTY;
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        Set<String> tags = stack.getTags().map(tag -> tag.location().toString()).collect(Collectors.toSet());
        boolean using = player.isUsingItem() && player.getUsedItemHand() == (mainHand
                ? net.minecraft.world.InteractionHand.MAIN_HAND : net.minecraft.world.InteractionHand.OFF_HAND);
        String useAnimation = stack.getUseAnimation().name().toLowerCase(Locale.ROOT);
        boolean charged = stack.getItem() instanceof net.minecraft.world.item.CrossbowItem
                && net.minecraft.world.item.CrossbowItem.isCharged(stack);
        return new HeldItem(id, tags, using, useAnimation, charged);
    }

    static String kindOf(HeldItem item) {
        if (item == null || item.empty()) return "";
        String id = item.id().toLowerCase(Locale.ROOT);
        if (id.endsWith("_sword") || id.endsWith(":sword") || tagged(item, ":swords", ":sword")) return "sword";
        if (id.endsWith("_axe") || tagged(item, ":axes", ":axe")) return "axe";
        if (id.endsWith("_pickaxe") || tagged(item, ":pickaxes", ":pickaxe")) return "pickaxe";
        if (id.endsWith("_shovel") || tagged(item, ":shovels", ":shovel")) return "shovel";
        if (id.endsWith("_hoe") || tagged(item, ":hoes", ":hoe")) return "hoe";
        if (id.endsWith(":shield") || tagged(item, ":shields", ":shield")) return "shield";
        if (id.endsWith(":crossbow") || tagged(item, ":crossbows", ":crossbow")) return "crossbow";
        if (id.endsWith(":bow") || tagged(item, ":bows", ":bow")) return "bow";
        if (id.endsWith(":fishing_rod") || tagged(item, ":fishing_rods", ":fishing_rod")) return "fishing_rod";
        if (id.endsWith(":trident") || tagged(item, ":tridents", ":trident")) return "spear";
        if (id.endsWith(":splash_potion") || id.endsWith(":lingering_potion") || tagged(item, ":throwable_potion", ":throwable_potions"))
            return "throwable_potion";
        return "";
    }

    private static boolean tagged(HeldItem item, String plural, String singular) {
        for (String tag : item.tags())
            if (tag.endsWith(plural) || tag.endsWith(singular)) return true;
        return false;
    }
}
