package cn.minerealms.rigworks.character.client;

import cn.minerealms.rigworks.character.core.CharacterProfile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RigWorksYsmEquipmentDiscoveryTest {
    @Test void importsHandAndBackLocatorsAsEquipmentMounts() {
        var profile = new CharacterProfile("test:avatar.rwym", 1,
                Map.of("right_hand", new CharacterProfile.HumanoidBone("RightHandLocator", CharacterProfile.Rotation.IDENTITY),
                        "left_hand", new CharacterProfile.HumanoidBone("LeftHandLocator", CharacterProfile.Rotation.IDENTITY)),
                Map.of(), null, .15, .05, CharacterProfile.HeadLook.defaults());

        var equipment = RigWorksWorkbenchExtension.discoverEquipment(profile,
                List.of("RightHandLocator", "LeftHandLocator", "CapeLocator", "ElytraLocator"));

        assertTrue(equipment.enabled());
        assertEquals("RightHandLocator", equipment.rightHand().node());
        assertEquals("LeftHandLocator", equipment.leftHand().node());
        assertNotNull(equipment.socket("cape"));
        assertEquals("CapeLocator", equipment.socket("cape").node());
        assertNotNull(equipment.socket("back"));
        assertEquals("ElytraLocator", equipment.socket("back").node());
    }
}
