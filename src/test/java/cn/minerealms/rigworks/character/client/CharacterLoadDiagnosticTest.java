package cn.minerealms.rigworks.character.client;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CharacterLoadDiagnosticTest {
    @Test void reboundFailureIdentifiesTheActualModelAndFile() {
        Path source = Path.of("avatar-copy", "model.fbx");
        String expected = "a".repeat(64), actual = "b".repeat(64);
        var diagnostic = new CharacterLoadDiagnostic("rigworks:avatars/copy/profile.json", "madoka:model.fbx",
                "madoka:model.fbx", null, expected, null)
                .resolved("rigworks:models/avatars/copy/model.fbx", source).hashed(actual);
        String message = diagnostic.message("Cannot load");
        assertTrue(message.contains("model=rigworks:models/avatars/copy/model.fbx"));
        assertTrue(message.contains("declared model=madoka:model.fbx"));
        assertTrue(message.contains("source=" + source.toAbsolutePath().normalize()));
        assertTrue(message.contains("expected SHA-256=" + expected));
        assertTrue(message.contains("actual SHA-256=" + actual));
    }

    @Test void resourceFailureDoesNotInventALocalSource() {
        String message = new CharacterLoadDiagnostic("demo:profile.json", "demo:player.fbx", "demo:player.fbx",
                null, null, null).message("Cannot initialize");
        assertTrue(message.contains("source=resource:demo:player.fbx"));
        assertFalse(message.contains("declared model="));
        assertFalse(message.contains("SHA-256="));
    }
}
