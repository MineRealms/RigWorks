package cn.minerealms.rigworks.character.client;

import cn.minerealms.rigworks.character.core.CharacterEquipmentDefinition;
import cn.minerealms.rigworks.character.core.CharacterProfile;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CharacterEquipmentFrameTest {
    private static CharacterEquipmentDefinition.Mount mount(String name) {
        return new CharacterEquipmentDefinition.Mount(name,new CharacterEquipmentDefinition.Position(0,.1f,0),
                CharacterProfile.Rotation.IDENTITY,1);
    }

    @Test void authoredCollapsedLocatorsHideTheirOwnAttachmentWithoutWeakeningStrictFrames() {
        var socket=mount("ElytraLocator");
        var collapsed=new Matrix4f().translation(1,2,3).scale(0);
        assertNull(socket.renderTransform(collapsed));
        assertNull(socket.renderTransform(new Matrix4f().scale(1,0,1)));
        assertThrows(IllegalArgumentException.class,()->socket.transform(collapsed));
        assertThrows(IllegalArgumentException.class,()->socket.renderTransform(new Matrix4f().scale(-1,1,1)));
        assertThrows(IllegalArgumentException.class,()->socket.renderTransform(new Matrix4f().m00(Float.NaN)));
        var normal=new Matrix4f().translation(1,2,3).scale(100);
        assertEquals(socket.transform(normal),socket.renderTransform(normal));
    }

    @Test void hiddenBackLocatorDoesNotFallThroughToCapeTorsoOrIdentity() {
        var hidden=CharacterEquipmentLayers.socketFrame(mount("ElytraLocator"),new Matrix4f().scale(0));
        var visible=CharacterEquipmentLayers.socketFrame(mount("CapeLocator"),new Matrix4f());
        var absent=CharacterEquipmentLayers.socketFrame(null,null);
        var torso=new Matrix4f().translate(2,3,4);
        assertTrue(hidden.configured());
        assertNull(CharacterEquipmentLayers.backFrame(hidden,visible,torso));
        assertSame(visible.transform(),CharacterEquipmentLayers.backFrame(visible,hidden,torso),
                "An independently visible cape must not inherit the Elytra locator's hidden state");
        assertNull(CharacterEquipmentLayers.backFrame(absent,hidden,torso));
        assertEquals(visible.transform(),CharacterEquipmentLayers.backFrame(absent,visible,torso));
        assertSame(torso,CharacterEquipmentLayers.backFrame(absent,absent,torso));
        assertEquals(new Matrix4f(),CharacterEquipmentLayers.backFrame(absent,absent,null));
        assertNull(hidden.orElse(torso));
        assertSame(torso,absent.orElse(torso));
    }
}
