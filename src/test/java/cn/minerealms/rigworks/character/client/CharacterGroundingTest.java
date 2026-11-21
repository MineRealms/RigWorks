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

    @Test void includesRootTranslationRotationScaleAndCrouchRenderOffset() {
        var bounds = new AABB(-.2, -.25, -.3, .2, 1.7, .3);
        var root = new Matrix4f().rotateY(.7f).scale(.5f).translate(0, -.4f, 0).scale(2, 1, 1);
        assertEquals(.45, CharacterGrounding.verticalLift(bounds, root, -.125), 1e-6);
        var rotated = new Matrix4f().rotateZ((float) Math.PI / 2);
        assertEquals(.325, CharacterGrounding.verticalLift(bounds, rotated, -.125), 1e-6);
        assertEquals(0, CharacterGrounding.verticalLift(bounds, new Matrix4f().translate(0, 1, 0), -.125), 1e-6);
    }
}
