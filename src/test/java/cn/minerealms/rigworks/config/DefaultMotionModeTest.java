package cn.minerealms.rigworks.config;

import cn.minerealms.rigworks.RigWorksConfig;
import cn.minerealms.rigworks.RigWorksConfig.CharacterAnimationMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultMotionModeTest {
    @Test void addingThePresetPreservesPreviousModeOrdinalsAndDefaultConfiguration() {
        assertArrayEquals(new CharacterAnimationMode[]{CharacterAnimationMode.VANILLA,
                CharacterAnimationMode.SCRIPTED, CharacterAnimationMode.CLIPS, CharacterAnimationMode.DEFAULT},
                CharacterAnimationMode.values());
        assertEquals(CharacterAnimationMode.VANILLA, RigWorksConfig.CLIENT.animationMode.getDefault());
    }
}
