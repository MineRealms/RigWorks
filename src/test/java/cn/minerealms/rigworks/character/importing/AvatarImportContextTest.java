package cn.minerealms.rigworks.character.importing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AvatarImportContextTest {
    @TempDir Path directory;

    @Test void resolvesPortableDependenciesWithinTheSelectedModelDirectory() throws Exception {
        Path source = Files.write(directory.resolve("model.pmx"), new byte[]{1});
        Path texture = Files.createDirectories(directory.resolve("textures")).resolve("color.png");
        Files.write(texture, new byte[]{2});
        var context = AvatarImportContext.from(source, new byte[]{1});
        assertEquals(texture.toRealPath(), context.resolve("textures\\color.png"));
        assertEquals(texture.toRealPath(), context.resolve("textures/../textures/color.png"));
        for (String path : new String[]{"../outside.png", "C:\\secret.png", "/absolute.png", "\\\\host\\share.png", "", "bad\0.png"})
            assertThrows(IOException.class, () -> context.resolve(path), path);
        assertThrows(IOException.class, () -> context.resolve("missing.png"));
        assertThrows(IOException.class, () -> context.resolve("textures"));
    }

    @Test void byteOnlyHandlersDoNotAcquireAnImplicitFilesystemRoot() {
        var context = new AvatarImportContext("model.pmx", new byte[]{1}, null);
        assertThrows(IOException.class, () -> context.resolve("color.png"));
    }
}
