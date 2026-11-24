package cn.minerealms.rigworks.character.client;

import cn.minerealms.rigworks.character.motion.DefaultMotionController;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.UseAnim;

/** Samples accepted item state on the client tick, independently from animation sampling. */
final class DefaultMotionItems {
    private DefaultMotionItems() {}

    static DefaultMotionController.Items sample(AbstractClientPlayer player) {
        boolean leftMain = player.getMainArm() == HumanoidArm.LEFT;
        ItemStack left = leftMain ? player.getMainHandItem() : player.getOffhandItem();
        ItemStack right = leftMain ? player.getOffhandItem() : player.getMainHandItem();
        String active = "";
        float progress = 0;
        if (player.isUsingItem()) {
            active = physicalArm(player.getMainArm(), player.getUsedItemHand()) == HumanoidArm.LEFT ? "left" : "right";
            ItemStack using = player.getUseItem();
            int duration = 20;
            if (using.getItem() instanceof CrossbowItem) {
                //? if forge {
                duration = CrossbowItem.getChargeDuration(using);
                //?}
                //? if neoforge {
                /*
                duration = CrossbowItem.getChargeDuration(using, player);
                *///?}
            }
            progress = useProgress(player.getTicksUsingItem(), duration);
        } else if (player.swinging && player.swingingArm != null) {
            active = physicalArm(player.getMainArm(), player.swingingArm) == HumanoidArm.LEFT ? "left" : "right";
        }
        return new DefaultMotionController.Items(category(left), category(right), active, progress,
                charged(left), charged(right));
    }

    static HumanoidArm physicalArm(HumanoidArm mainArm, InteractionHand hand) {
        return hand == InteractionHand.MAIN_HAND ? mainArm : mainArm.getOpposite();
    }

    static float useProgress(int ticks, int duration) {
        return Math.max(0, Math.min(1, (float) ticks / Math.max(1, duration)));
    }

    private static boolean charged(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(stack);
    }

    private static String category(ItemStack stack) {
        if (stack.isEmpty()) return "empty";
        if (stack.getUseAnimation() == UseAnim.CROSSBOW) return "crossbow";
        if (stack.getUseAnimation() == UseAnim.BOW) return "bow";
        if (stack.getUseAnimation() == UseAnim.BLOCK) return "shield";
        if (stack.getItem() instanceof SwordItem) return "sword";
        if (stack.getItem() instanceof DiggerItem) return "tool";
        return "item";
    }
}
