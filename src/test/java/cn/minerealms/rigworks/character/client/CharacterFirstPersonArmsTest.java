package cn.minerealms.rigworks.character.client;

import cn.minerealms.rigworks.character.core.CharacterProfile;
import net.minecraft.world.entity.HumanoidArm;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CharacterFirstPersonArmsTest {
    @Test void prefersForearmGeometryAndFallsBackForSimpleRigs() {
        var detailed = new CharacterProfile("test:avatar.glb", 1,
                Map.of("right_upper_arm", new CharacterProfile.HumanoidBone("Arm.R", CharacterProfile.Rotation.IDENTITY),
                        "right_forearm", new CharacterProfile.HumanoidBone("Forearm.R", CharacterProfile.Rotation.IDENTITY)),
                Map.of(), null, .15, .05, CharacterProfile.HeadLook.defaults());
        assertEquals("right_forearm", CharacterFirstPersonArms.firstPersonRole(detailed, HumanoidArm.RIGHT));
        assertEquals("right_upper_arm", CharacterFirstPersonArms.firstPersonRole(
                new CharacterProfile("test:avatar.glb", 1, Map.of(), Map.of(), null, .15, .05,
                        CharacterProfile.HeadLook.defaults()), HumanoidArm.RIGHT));
    }
}
