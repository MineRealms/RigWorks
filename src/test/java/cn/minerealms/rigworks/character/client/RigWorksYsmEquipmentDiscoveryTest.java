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
        assertEquals(-.0625f, equipment.rightHand().position().y());
        assertEquals(.1f, equipment.rightHand().position().z());
        assertTrue(Math.abs(equipment.rightHand().rotation().quaternion()
                .dot(new org.joml.Quaternionf().rotationX((float)Math.PI/2))) > 1 - 1e-6);
        assertNotNull(equipment.socket("cape"));
        assertEquals("CapeLocator", equipment.socket("cape").node());
        assertNotNull(equipment.socket("back"));
        assertEquals("ElytraLocator", equipment.socket("back").node());
    }

    @Test void ysmTorsoAndLegNamesHaveStableSemanticFallbacks() {
        var nodes=List.of("right_waist", "AllBody", "UpBody", "Breast", "AllHead", "Waist",
                "Head", "LeftLeg", "LeftLowerLeg", "RightLeg", "RightLowerLeg");
        assertEquals("AllBody",RigWorksWorkbenchExtension.suggest(nodes,"body"));
        assertEquals("Waist",RigWorksWorkbenchExtension.suggest(nodes,"hips"));
        assertEquals("UpBody",RigWorksWorkbenchExtension.suggest(nodes,"spine"));
        assertEquals("Breast",RigWorksWorkbenchExtension.suggest(nodes,"chest"));
        assertEquals("AllHead",RigWorksWorkbenchExtension.suggest(nodes,"neck"));
        assertEquals("LeftLeg",RigWorksWorkbenchExtension.suggest(nodes,"left_thigh"));
        assertEquals("RightLowerLeg",RigWorksWorkbenchExtension.suggest(nodes,"right_shin"));
        assertEquals("mixamorig:Pelvis",RigWorksWorkbenchExtension.suggest(List.of("mixamorig:Pelvis"),"hips"));
    }

    @Test void authoredHandLocatorsWinOverGenericHandBones() {
        var profile = new CharacterProfile("test:avatar.rwym", 1,
                Map.of("right_hand", new CharacterProfile.HumanoidBone("RightHand", CharacterProfile.Rotation.IDENTITY),
                        "left_hand", new CharacterProfile.HumanoidBone("LeftHand", CharacterProfile.Rotation.IDENTITY)),
                Map.of(), null, .15, .05, CharacterProfile.HeadLook.defaults());
        var nodes = List.of("RightHand", "RightHandLocator", "LeftHand", "LeftHandLocator");
        var equipment = RigWorksWorkbenchExtension.discoverEquipment(profile, nodes);

        assertEquals("RightHandLocator", equipment.rightHand().node());
        assertEquals("LeftHandLocator", equipment.leftHand().node());
        assertEquals("RightHandLocator", RigWorksWorkbenchExtension.suggest(nodes, "right_hand"));
        assertEquals("LeftHandLocator", RigWorksWorkbenchExtension.suggest(nodes, "left_hand"));
    }
}
