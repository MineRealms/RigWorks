package cn.minerealms.rigworks.character.client;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CharacterHeldItemRendererTest {
    @Test void hidesVanillaItemForCollapsedYsmHandLocatorAndAcceptsNormalFrames() {
        assertTrue(CharacterHeldItemRenderer.isRenderableMountFrame(new Matrix4f().translate(1, 2, 3)));
        assertFalse(CharacterHeldItemRenderer.isRenderableMountFrame(new Matrix4f().scale(0, 0, 0)));
        assertFalse(CharacterHeldItemRenderer.isRenderableMountFrame(new Matrix4f().scale(-1, 1, 1)));
        assertFalse(CharacterHeldItemRenderer.isRenderableMountFrame(null));
    }
}
