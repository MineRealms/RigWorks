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

import cn.minerealms.rigworks.character.capabilities.BlinkQuery;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class YsmBlinkLayerTest {
    private static final UUID UUID_A = new UUID(1L, 1234567L);

    @Test void detectsBlinkAndWinkClips() {
        assertEquals("BlinkEye", YsmBlinkLayer.blinkClip(List.of("idle", "BlinkEye", "walk")));
        assertEquals("BlinkEye", YsmBlinkLayer.blinkClip(List.of("idle", "blink", "BlinkEye")));
        assertEquals("blink", YsmBlinkLayer.blinkClip(List.of("idle", "blink", "walk")));
        assertEquals("wink_left", YsmBlinkLayer.winkClip(List.of("idle", "wink_left")));
        assertNull(YsmBlinkLayer.blinkClip(List.of("idle", "walk")));
        assertNull(YsmBlinkLayer.winkClip(List.of("idle", "walk")));
    }

    @Test void fromAnimationsRequiresAClip() {
        assertNull(YsmBlinkLayer.fromAnimations(List.of("idle"), List.of(), UUID_A, () -> 0, () -> false));
        var layer = YsmBlinkLayer.fromAnimations(List.of("BlinkEye"), List.of("LeftEyelid"), UUID_A,
                () -> 0, () -> false);
        assertNotNull(layer);
        assertEquals("BlinkEye", layer.blinkClip());
        assertEquals(1f, layer.mask().get("LeftEyelid"), 1e-6);
    }

    @Test void usesEyelidMaskAndUuidOffset() {
        assertEquals(1f, cn.minerealms.rigworks.character.capabilities.YsmBlinkController
                .eyelidMask(List.of("LeftEyeLid", "RightBlink", "Head")).get("LeftEyeLid"), 1e-6);
        long offset = Math.abs(UUID_A.getLeastSignificantBits()) % 10;
        assertTrue(new BlinkQuery(UUID_A, () -> (int) (86 - offset)).isCloseEyes());
        assertFalse(new BlinkQuery(UUID_A, () -> (int) (85 - offset)).isCloseEyes());
    }
}
