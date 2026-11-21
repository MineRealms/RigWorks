package cn.minerealms.rigworks.character.capabilities;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class YsmLocatorSocketsTest {
    @Test void keepsHandLocatorsCompatibleWithEquipmentMounts() {
        assertEquals("left_hand", YsmLocatorSockets.socketFor("LeftHandLocator"));
        assertEquals("right_hand", YsmLocatorSockets.socketFor("RightHandLocator"));
    }

    @Test void mapsYsmBackLocatorsForVanillaElytraLayers() {
        assertEquals("cape", YsmLocatorSockets.socketFor("CapeLocator"));
        assertEquals("back", YsmLocatorSockets.socketFor("BackLocator"));
        assertEquals("back", YsmLocatorSockets.socketFor("ElytraLocator"));
    }

    @Test void preservesTheAuthoredYsmWeaponAndPassengerLocatorFamilies() {
        assertEquals("pistol", YsmLocatorSockets.socketFor("PistolLocator"));
        assertEquals("rifle", YsmLocatorSockets.socketFor("RifleLocator"));
        assertEquals("left_waist", YsmLocatorSockets.socketFor("LeftWaistLocator"));
        assertEquals("right_shoulder", YsmLocatorSockets.socketFor("RightShoulderLocator"));
        assertEquals("blade", YsmLocatorSockets.socketFor("BladeLocator"));
        assertEquals("sheath", YsmLocatorSockets.socketFor("SheathLocator"));
        assertEquals("backpack", YsmLocatorSockets.socketFor("BackpackLocator"));
        assertEquals("left_hand_8", YsmLocatorSockets.socketFor("LeftHandLocator8"));
        assertEquals("passenger_8", YsmLocatorSockets.socketFor("PassengerLocator8"));
    }
}
