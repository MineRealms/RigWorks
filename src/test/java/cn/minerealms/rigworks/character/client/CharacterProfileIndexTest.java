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

    @Test void removingACopyDeletesItsConfigAndItCannotReturnFromLegacyHints() throws Exception {
        String first = "rigworks:avatars/one/profile.json";
        String second = "rigworks:avatars/two/profile.json";
        writeConfig(first); writeConfig(second);
        Path source = Files.writeString(temp.resolve("shared-model.fbx"), "shared asset");
        var index = new CharacterProfileIndex(temp);
        index.remember(first); index.remember(second);
        index.remove(first);
        index.remove(first);
        assertFalse(Files.exists(configFile(first)));
        assertTrue(Files.exists(configFile(second)));
        assertTrue(Files.exists(source));
        assertEquals(List.of(second), new CharacterProfileIndex(temp).profiles(List.of(first, second)));
    }

    @Test void removalRejectsAnInvalidIdBeforeTouchingFiles() throws Exception {
        String id = "rigworks:avatars/one/profile.json";
        writeConfig(id);
        var index = new CharacterProfileIndex(temp);
        index.remember(id);
        assertThrows(java.io.IOException.class, () -> index.remove("../characters"));
        assertEquals(List.of(id), index.profiles(List.of()));
    }

    @Test void removalReleasesUnreferencedYsmMappingsButKeepsAnotherCopiesModel() throws Exception {
        String first = "rigworks:imported/ysm/one/profile.json";
        String second = "rigworks:avatars/copy/profile.json";
        String model = "rigworks:models/imported/ysm/one/model.rwym";
        String orphan = "rigworks:models/imported/ysm/deleted/model.rwym";
        writeConfig(first, model); writeConfig(second, model);
        Path asset = Files.writeString(temp.resolve("model.rwym"), "canonical cache");
        var index = new CharacterProfileIndex(temp);
        index.remember(first); index.remember(second);
        var removed = index.remove(first);
        var retired = new CharacterModelRetirement<Object>();
        Object scene = new Object();
        if (!removed.keepAsset(model)) retired.request(model, scene);
        assertTrue(retired.isEmpty(), "Shared saved profiles must keep their pinned model");
        assertEquals(java.util.Set.of(second), removed.profiles());
        assertTrue(removed.keepAsset(model), "The remaining copy still uses the canonical model mapping");
        assertFalse(removed.keepAsset(orphan), "Historical deleted YSM mappings must not keep filling the quota");
        assertFalse(removed.keepAsset("rigworks:workbench_sources/" + CharacterVisualConfig.hash(first.getBytes(StandardCharsets.UTF_8))));
        assertTrue(removed.keepAsset("other:editor-draft.fbx"));
        var last = index.remove(second);
        if (!last.keepAsset(model)) retired.request(model, scene);
        assertEquals(Map.of(model, scene), retired.ready(id -> scene, live -> false));
        assertTrue(last.keepAsset("other:editor-draft.fbx"));
        assertFalse(last.keepAsset(model), "Deleting the last copy releases the model mapping");
        assertTrue(Files.exists(asset), "Removing aliases must not delete the shared disk cache");
    }

    @Test void legacyUnindexedSavedCopyStillProtectsItsModelMapping() throws Exception {
        String first = "rigworks:imported/ysm/one/profile.json";
        String legacy = "rigworks:avatars/legacy/profile.json";
        String model = "rigworks:models/imported/ysm/one/model.rwym";
        writeConfig(first, model); writeConfig(legacy, model);
        var index = new CharacterProfileIndex(temp);
        index.remember(first);
        assertTrue(index.remove(first).keepAsset(model));
        assertTrue(Files.exists(configFile(legacy)));
    }

    private void writeConfig(String profile) throws Exception {
        Files.createDirectories(temp.resolve("characters"));
        Files.writeString(configFile(profile), visual().json(), StandardCharsets.UTF_8);
    }

    private void writeConfig(String profile, String model) throws Exception {
        var original = visual();
        var json = com.google.gson.JsonParser.parseString(original.json()).getAsJsonObject();
        json.getAsJsonObject("profile").addProperty("model", model);
        Files.createDirectories(temp.resolve("characters"));
        Files.writeString(configFile(profile), CharacterVisualConfig.parse(json.toString()).json(), StandardCharsets.UTF_8);
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
