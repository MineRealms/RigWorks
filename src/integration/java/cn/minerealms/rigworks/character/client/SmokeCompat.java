package cn.minerealms.rigworks.character.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

final class SmokeCompat {
    private SmokeCompat() {}

    static void openWorld(Minecraft mc, String world) {
        //? if forge {
        mc.createWorldOpenFlows().loadLevel(new TitleScreen(), world);
        //?}
        //? if neoforge {
        /*
        mc.createWorldOpenFlows().openWorld(world, () -> mc.setScreen(new TitleScreen()));
        *///?}
    }

    static String skinName(AbstractClientPlayer player) {
        //? if forge {
        return player.getModelName();
        //?}
        //? if neoforge {
        /*
        return player.getSkin().model() == net.minecraft.client.resources.PlayerSkin.Model.SLIM ? "slim" : "default";
        *///?}
    }

    static void chargeCrossbow(ItemStack item) {
        //? if forge {
        net.minecraft.world.item.CrossbowItem.setCharged(item, true);
        //?}
        //? if neoforge {
        /*
        item.set(net.minecraft.core.component.DataComponents.CHARGED_PROJECTILES,
                net.minecraft.world.item.component.ChargedProjectiles.of(new ItemStack(Items.ARROW)));
        *///?}
    }
}
