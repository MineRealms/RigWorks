package cn.minerealms.rigworks.character;

import cn.minerealms.rigworks.character.core.CharacterEquipmentDefinition;
import cn.minerealms.rigworks.character.core.CharacterProfile;
import org.junit.jupiter.api.Test;

import java.io.FileNotFoundException;
import java.io.StringReader;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class CharacterEquipmentSourceTest {
    private static final String REFERENCE = "test:models/avatar/equipment.json";

    private static CharacterProfile profile(boolean resource) {
        return CharacterProfile.read(new StringReader("""
                {"version":1,"model":"test:models/avatar/model.fbx",
                 "humanoid":{"right_hand":"Hand.R","left_hand":"Hand.L"}%s}
                """.formatted(resource ? ",\"equipmentDefinition\":\"" + REFERENCE + "\"" : "")));
    }

    @Test void importedEquipmentDoesNotOpenItsUnavailableSourceResource() throws Exception {
        var authored = CharacterEquipmentDefinition.read(new StringReader("""
                {"version":1,"right_hand":{"node":"Hand.R","position":[0.2,0.1,0.3],"scale":0.75},
                 "left_hand":{"node":"Hand.L"},"sockets":{"eye":{"node":"Head"}},
                 "overrides":[{"items":["minecraft:spyglass"],"socket":"eye"}]}
                """));
        var resolved = CharacterEquipmentDefinition.resolve(profile(true), authored, reference -> {
            throw new FileNotFoundException(reference);
        });
        assertEquals(authored, resolved);
        assertEquals(authored.rightHand().position(), resolved.rightHand().position());
        assertEquals(authored.sockets(), resolved.sockets());
        assertEquals(authored.overrides(), resolved.overrides());
    }

    @Test void olderEmbeddedSettingsUseOnlyProfileRoleFallbacksAndRetainDisablement() throws Exception {
        var embedded = new CharacterEquipmentDefinition(false, null, null);
        var resolved = CharacterEquipmentDefinition.resolve(profile(true), embedded, reference -> {
            fail("Self-contained visual settings must not open " + reference);
            return null;
        });
        assertFalse(resolved.enabled());
        assertEquals("Hand.R", resolved.rightHand().node());
        assertEquals("Hand.L", resolved.leftHand().node());
    }

    @Test void embeddedNamedHandLocatorsTakePrecedenceOverDefaultRoleMounts() throws Exception {
        var embedded = CharacterEquipmentDefinition.read(new StringReader("""
                {"version":1,"sockets":{"right_hand":{"node":"HeldItemLocator.R"},
                                         "left_hand":{"node":"HeldItemLocator.L"}}}
                """));
        var resolved = CharacterEquipmentDefinition.resolve(profile(true), embedded, reference -> {
            fail("Named locators are already embedded");
            return null;
        });
        assertEquals(embedded, resolved);
        assertEquals("HeldItemLocator.R", resolved.handMount(false).node());
        assertEquals("HeldItemLocator.L", resolved.handMount(true).node());
    }

    @Test void resourceProfileStillLoadsAndClosesItsAuthoredDefinition() throws Exception {
        var closed = new AtomicBoolean();
        var resolved = CharacterEquipmentDefinition.resolve(profile(true), null, reference -> {
            assertEquals(REFERENCE, reference);
            return new StringReader("{\"version\":1,\"enabled\":false}") {
                @Override public void close() { closed.set(true); super.close(); }
            };
        });
        assertTrue(closed.get());
        assertFalse(resolved.enabled());
        assertNull(resolved.rightHand());
    }

    @Test void resourceFailuresRemainVisibleAndAbsentReferencesUseDefaultHands() throws Exception {
        var missing = new FileNotFoundException(REFERENCE);
        assertSame(missing, assertThrows(FileNotFoundException.class,
                () -> CharacterEquipmentDefinition.resolve(profile(true), null, reference -> { throw missing; })));
        assertThrows(IllegalArgumentException.class, () -> CharacterEquipmentDefinition.resolve(profile(true), null,
                reference -> new StringReader("{\"version\":1,\"right_hand\":{\"node\":\"Hand.R\",\"scale\":0}}")));
        var resolved = CharacterEquipmentDefinition.resolve(profile(false), null, reference -> {
            fail("No equipment reference was configured");
            return null;
        });
        assertEquals(CharacterEquipmentDefinition.defaults(profile(false)), resolved);
    }
}
