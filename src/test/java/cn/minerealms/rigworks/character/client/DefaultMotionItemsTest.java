package cn.minerealms.rigworks.character.client;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultMotionItemsTest {
    @Test void activeItemUsesPhysicalHandsForEitherMainArm() {
        assertEquals(HumanoidArm.RIGHT, DefaultMotionItems.physicalArm(HumanoidArm.RIGHT, InteractionHand.MAIN_HAND));
        assertEquals(HumanoidArm.LEFT, DefaultMotionItems.physicalArm(HumanoidArm.RIGHT, InteractionHand.OFF_HAND));
        assertEquals(HumanoidArm.LEFT, DefaultMotionItems.physicalArm(HumanoidArm.LEFT, InteractionHand.MAIN_HAND));
        assertEquals(HumanoidArm.RIGHT, DefaultMotionItems.physicalArm(HumanoidArm.LEFT, InteractionHand.OFF_HAND));
    }

    @Test void chargeProgressUsesWeaponDurationAndClampsTheResult() {
        assertEquals(0f, DefaultMotionItems.useProgress(0, 20));
        assertEquals(.5f, DefaultMotionItems.useProgress(10, 20));
        assertEquals(.4f, DefaultMotionItems.useProgress(10, 25));
        assertEquals(1f, DefaultMotionItems.useProgress(30, 20));
        assertEquals(0f, DefaultMotionItems.useProgress(-1, 20));
        assertEquals(1f, DefaultMotionItems.useProgress(1, 0));
    }
}
