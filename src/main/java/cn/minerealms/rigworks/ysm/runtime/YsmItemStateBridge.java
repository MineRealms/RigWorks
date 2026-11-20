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
                        boolean holdOffhandBow, boolean useOffhandBow) {
        public State {
            states = Set.copyOf(states);
        }

        public boolean matches(String token) { return token != null && states.contains(token); }
    }

    public static State derive(CharacterEquipmentDefinition equipment, HeldItem right, HeldItem left, boolean mainHandRight) {
        Set<String> states = new LinkedHashSet<>();
        boolean holdBow = false, useBow = false, holdSword = false, useSword = false;
        boolean holdCrossbow = false, useCrossbow = false, holdShield = false, useShield = false;
        boolean holdOffhandBow = false, useOffhandBow = false;

        for (int hand = 0; hand < 2; hand++) {
            boolean rightHand = hand == 0;
            HeldItem item = rightHand ? right : left;
            if (item == null || item.empty()) continue;
            String arm = rightHand ? "right" : "left";
            String logical = rightHand == mainHandRight ? "mainhand" : "offhand";
            String suffix = kindOf(item);
            String state = item.using() ? "using" : item.charged() ? "charged" : "held";
            if (USING.matches(arm, item.id(), item.tags(), state, item.useAnimation(), "third_person_" + arm + "_hand"))
                states.add("using");
            else if (CHARGED.matches(arm, item.id(), item.tags(), state, item.useAnimation(), "third_person_" + arm + "_hand"))
                states.add("charged");
            else if (HELD.matches(arm, item.id(), item.tags(), state, item.useAnimation(), "third_person_" + arm + "_hand"))
                states.add("held");

            String itemKey = item.id().substring(Math.max(0, item.id().indexOf(':') + 1));
            String idToken = ":" + itemKey;
            states.add("hold('" + idToken + "')");
            states.add("ctrl.hold('" + idToken + "')");
            states.add("hold('" + suffix + "')");
            states.add("ctrl.hold('" + suffix + "')");
            states.add("hold('" + arm + "','" + idToken + "')");
            states.add("ctrl.hold('" + arm + "','" + idToken + "')");
            states.add("ctrl.hold('" + logical + "','" + idToken + "')");
            states.add("hold('" + logical + "','" + suffix + "')");
            if (suffix.isEmpty()) {
                if (item.using()) {
                    states.add("use('" + idToken + "')");
                    states.add("ctrl.use('" + idToken + "')");
                }
                continue;
            }
            if (item.using()) {
                states.add("use('" + idToken + "')");
                states.add("ctrl.use('" + idToken + "')");
                states.add("use('" + suffix + "')");
                states.add("ctrl.use('" + suffix + "')");
                states.add("use('" + arm + "','" + suffix + "')");
                states.add("ctrl.use('" + arm + "','" + idToken + "')");
                states.add("ctrl.use('" + logical + "','" + idToken + "')");
            }
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

    public static State sample(net.minecraft.client.player.LocalPlayer player, CharacterEquipmentDefinition equipment) {
        if (player == null) return derive(equipment, HeldItem.EMPTY, HeldItem.EMPTY, true);
        boolean mainRight = player.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT;
        HeldItem main = held(player, player.getMainHandItem(), true);
        HeldItem offhand = held(player, player.getOffhandItem(), false);
        return derive(equipment, mainRight ? main : offhand, mainRight ? offhand : main, mainRight);
    }

    private static HeldItem held(net.minecraft.client.player.LocalPlayer player, net.minecraft.world.item.ItemStack stack, boolean mainHand) {
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

    private static String kindOf(HeldItem item) {
        String id = item.id().toLowerCase(Locale.ROOT);
        for (String tag : item.tags()) {
            String lower = tag.toLowerCase(Locale.ROOT);
            if (lower.endsWith(":swords") || lower.endsWith(":sword")) return "sword";
        }
        if (id.endsWith(":bow")) return "bow";
        if (id.endsWith(":crossbow")) return "crossbow";
        if (id.endsWith(":shield")) return "shield";
        if (id.endsWith(":trident")) return "spear";
        if (id.endsWith(":sword")) return "sword";
        if (item.tags().stream().anyMatch(tag -> tag.toLowerCase(Locale.ROOT).endsWith(":bows"))) return "bow";
        if (item.tags().stream().anyMatch(tag -> tag.toLowerCase(Locale.ROOT).endsWith(":crossbows"))) return "crossbow";
        if (item.tags().stream().anyMatch(tag -> tag.toLowerCase(Locale.ROOT).endsWith(":shields"))) return "shield";
        return "";
    }
}
