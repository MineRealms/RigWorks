package cn.minerealms.rigworks.character.client;

import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CharacterGroundingTest {
    @Test void liftsOnlyTheAmountTheTransformedPoseExtendsBelowTheFloor() {
        var bounds = new AABB(-.2, -.25, -.2, .2, 1.7, .2);
        assertEquals(.5, CharacterGrounding.verticalLift(bounds, new Matrix4f().scale(2)), 1e-6);
        assertEquals(0, CharacterGrounding.verticalLift(bounds, new Matrix4f().translate(0, .3f, 0)), 1e-6);
        assertEquals(0, CharacterGrounding.verticalLift(null, new Matrix4f()), 1e-6);
    }
}
