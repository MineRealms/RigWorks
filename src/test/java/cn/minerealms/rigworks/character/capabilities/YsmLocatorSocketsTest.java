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
}
