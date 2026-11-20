package cn.minerealms.rigworks.character.client;

import cn.minerealms.rigworks.character.core.CharacterEquipmentDefinition;
import cn.minerealms.rigworks.character.core.CharacterProfile;
import cn.minerealms.rigworks.character.core.CharacterVisualConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CharacterProfileIndexTest {
    @TempDir Path temp;

    @Test void rememberPersistsAcrossIndexRestartAndIsIdempotent() throws Exception {
        String id = "rigworks:avatars/one/profile.json";
        writeConfig(id);
        var index = new CharacterProfileIndex(temp);
        index.remember(id);
        Path file = temp.resolve("profiles.json");
        String before = Files.readString(file);
        var stamp = java.nio.file.attribute.FileTime.fromMillis(1_600_000_000_000L);
        Files.setLastModifiedTime(file, stamp);
        stamp = Files.getLastModifiedTime(file);
        index.remember(id);
        assertEquals(before, Files.readString(file));
        assertEquals(stamp, Files.getLastModifiedTime(file));
        assertEquals(List.of(id), new CharacterProfileIndex(temp).profiles(List.of()));
    }

    @Test void damagedOrMissingConfigurationRemovesOnlyThatProfile() throws Exception {
        String first = "rigworks:avatars/one/profile.json";
        String second = "rigworks:avatars/two/profile.json";
        writeConfig(first); writeConfig(second);
        var index = new CharacterProfileIndex(temp);
        index.remember(first); index.remember(second);
        Files.writeString(configFile(first), "{broken", StandardCharsets.UTF_8);
        assertEquals(List.of(second), index.profiles(List.of()));
        Files.delete(configFile(second));
        assertEquals(List.of(), index.profiles(List.of()));
    }

    @Test void knownLegacyHintIsImportedOnlyWhenItsSavedConfigIsValid() throws Exception {
        String id = "rigworks:avatars/legacy/profile.json";
        writeConfig(id);
        var index = new CharacterProfileIndex(temp);
        assertEquals(List.of(id), index.profiles(List.of(id, "bad id", "other:missing/profile.json")));
        assertEquals(List.of(id), new CharacterProfileIndex(temp).profiles(List.of()));
    }

    @Test void separateProfileIdsUseSeparateContentAddressedFiles() throws Exception {
        String first = "rigworks:avatars/one/profile.json";
        String second = "rigworks:avatars/two/profile.json";
        writeConfig(first); writeConfig(second);
        var index = new CharacterProfileIndex(temp);
        index.remember(first); index.remember(second);
        assertEquals(List.of(first, second), index.profiles(List.of()));
        assertNotEquals(configFile(first), configFile(second));
    }

    @Test void indexRejectsTheTwoHundredAndFiftySeventhProfile() throws Exception {
        var index = new CharacterProfileIndex(temp);
        for (int i = 0; i < 256; i++) {
            String id = "rigworks:avatars/p" + i + "/profile.json";
            writeConfig(id); index.remember(id);
        }
        String tooMany = "rigworks:avatars/overflow/profile.json";
        writeConfig(tooMany);
        assertThrows(java.io.IOException.class, () -> index.remember(tooMany));
        assertEquals(256, index.profiles(List.of()).size());
    }

    private void writeConfig(String profile) throws Exception {
        Files.createDirectories(temp.resolve("characters"));
        Files.writeString(configFile(profile), visual().json(), StandardCharsets.UTF_8);
    }

    private Path configFile(String profile) {
        return temp.resolve("characters").resolve(CharacterVisualConfig.hash(profile.getBytes(StandardCharsets.UTF_8)) + ".json");
    }

    private static CharacterVisualConfig visual() {
        var profile = new CharacterProfile("demo:avatar.fbx", 1, Map.of(), Map.of("idle", "Idle"),
                null, .15, .02, CharacterProfile.HeadLook.defaults());
        return new CharacterVisualConfig(profile, CharacterEquipmentDefinition.defaults(profile),
                CharacterVisualConfig.Transform.IDENTITY, "a".repeat(64), null, null);
    }
}
