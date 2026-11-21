package cn.minerealms.rigworks.character.importing;

import cn.minerealms.assimploader.model.AssimpScene;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Proves a second handler can be registered and is dispatched purely by file extension. */
class AvatarImportRegistryTest {
    private static final class FakeHandler implements AvatarImportHandler {
        private String loaded;
        private byte[] appliedBytes;
        private AssimpScene appliedScene;
        private int loadCalls;
        private int applyCalls;

        @Override public String id() { return "fake"; }
        @Override public List<String> extensions() { return List.of("fake", "fak"); }

        @Override public AssimpScene load(String name, byte[] bytes) {
            loadCalls++;
            loaded = name;
            return null;
        }

        @Override public void apply(String name, byte[] bytes, AssimpScene scene) {
            applyCalls++;
            appliedBytes = bytes;
            appliedScene = scene;
        }
    }

    private static AvatarImportRegistry registryWithFake(FakeHandler fake) {
        AvatarImportRegistry registry = new AvatarImportRegistry();
        registry.register(fake);
        return registry;
    }

    @Test void builtinsAreRegisteredByDefault() {
        assertEquals(List.of("ysm"), new AvatarImportRegistry().handlers().stream()
                .map(AvatarImportHandler::id).toList());
    }

    @Test void secondHandlerRegistersInOrder() {
        AvatarImportRegistry registry = registryWithFake(new FakeHandler());
        assertEquals(List.of("ysm", "fake"), registry.handlers().stream()
                .map(AvatarImportHandler::id).toList());
    }

    @Test void dispatchIsByExtensionToTheSecondHandler() throws IOException {
        FakeHandler fake = new FakeHandler();
        AvatarImportRegistry registry = registryWithFake(fake);
        byte[] bytes = {1, 2, 3};

        assertTrue(registry.accepts("avatar.fake"));
        assertTrue(registry.accepts("avatar.FAK"));
        assertFalse(registry.accepts("avatar.unknown"));
        assertFalse(registry.accepts("avatar"));

        assertNull(registry.load("avatar.fake", bytes));
        assertEquals("avatar.fake", fake.loaded);
        assertEquals(1, fake.loadCalls);

        AssimpScene scene = null;
        registry.apply("avatar.FAK", bytes, scene);
        assertEquals(1, fake.applyCalls);
        assertSame(bytes, fake.appliedBytes);
        assertSame(scene, fake.appliedScene);
    }

    @Test void builtinRoutingSurvivesExtraHandler() {
        AvatarImportRegistry registry = registryWithFake(new FakeHandler());
        assertTrue(registry.accepts("model.ysm"));
        assertFalse(registry.accepts("model.mxc"), "modern MXC containers need a separate decoder");
        assertFalse(registry.accepts("model.json"), "arbitrary JSON is not an encrypted YSM container");
    }

    @Test void unsupportedExtensionIsRejected() {
        AvatarImportRegistry registry = registryWithFake(new FakeHandler());
        assertThrows(IOException.class, () -> registry.load("model.obj", new byte[]{1}));
        assertThrows(IOException.class, () -> registry.apply("model.obj", new byte[]{1}, null));
        assertTrue(assertThrows(IOException.class, () -> registry.load("model.mxc", new byte[]{1}))
                .getMessage().contains("Modern YSM/MXC"));
    }

    @Test void duplicateIdIsRejected() {
        AvatarImportRegistry registry = registryWithFake(new FakeHandler());
        assertThrows(IllegalArgumentException.class, () -> registry.register(new FakeHandler()));
    }

    @Test void rejectedHandlerSelectionDoesNotBlockLaterImports() {
        var registry = new AvatarImportRegistry();
        assertTrue(registry.importFromDisk("missing", "missing").isCompletedExceptionally());
        assertTrue(registry.importFromDisk("missing").isCompletedExceptionally());
    }

    @Test void boundedReadRejectsEmptyFilesAndDirectories(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws IOException {
        var empty = java.nio.file.Files.createFile(directory.resolve("empty.ysm"));
        assertThrows(IOException.class, () -> AvatarImportRegistry.readSource(empty));
        assertThrows(IOException.class, () -> AvatarImportRegistry.readSource(directory));
        var source = directory.resolve("small.ysm");
        java.nio.file.Files.write(source, new byte[]{1,2,3});
        assertArrayEquals(new byte[]{1,2,3}, AvatarImportRegistry.readSource(source));
    }
}
