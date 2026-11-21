package cn.minerealms.rigworks.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeConfigFileTest {
    @TempDir Path directory;

    private Path initial() throws Exception {
        Path path = directory.resolve("rigworks-client.toml");
        Files.writeString(path, """
                #Keep custom profile and unrelated preferences when changing an option
                [character]
                enabled = true
                profile = "lusia:models/character/lusia/profile.json"
                uploadConfigurations = true
                remotePlayers = true
                animationMode = "VANILLA"
                handContacts = true
                remoteDistance = 128
                """);
        return path;
    }

    private static boolean valid(CommentedConfig config) {
        return config.getRaw("character.enabled") instanceof Boolean
                && config.getRaw("character.profile") instanceof String
                && config.getRaw("character.uploadConfigurations") instanceof Boolean
                && config.getRaw("character.remotePlayers") instanceof Boolean
                && config.getRaw("character.animationMode") instanceof String
                && config.getRaw("character.handContacts") instanceof Boolean
                && config.getRaw("character.remoteDistance") instanceof Number;
    }

    @Test void fullFileTransactionPreservesProfileAndDoesNotExposeAPartialEditToReload() throws Exception {
        Path path = initial();
        try (var live = CommentedFileConfig.builder(path).sync().autosave().build()) {
            live.load();
            var events = new AtomicInteger();
            var store = new RuntimeConfigFile(live, RuntimeConfigFileTest::valid, ignored -> fail(), () -> {}, events::incrementAndGet);
            var editEntered = new CountDownLatch(1);
            var finishEdit = new CountDownLatch(1);
            var reloadEntered = new CountDownLatch(1);
            var executor = Executors.newFixedThreadPool(2);
            try {
                var writer = executor.submit(() -> {
                    store.update(config -> {
                        config.set("character.animationMode", "SCRIPTED");
                        editEntered.countDown();
                        await(finishEdit);
                        config.set("character.uploadConfigurations", false);
                    });
                    return null;
                });
                assertTrue(editEntered.await(5, TimeUnit.SECONDS));
                var reload = executor.submit(() -> { reloadEntered.countDown(); store.reload(); return null; });
                assertTrue(reloadEntered.await(5, TimeUnit.SECONDS));
                var before = TomlFormat.instance().createParser().parse(Files.readString(path));
                assertEquals("VANILLA", before.get("character.animationMode"));
                assertEquals(Boolean.TRUE, before.get("character.uploadConfigurations"));
                finishEdit.countDown();
                writer.get(5, TimeUnit.SECONDS);
                reload.get(5, TimeUnit.SECONDS);
                assertEquals(1, events.get(), "The watch event from our own replacement is a no-op");
                var after = TomlFormat.instance().createParser().parse(Files.readString(path));
                assertTrue(valid(after));
                assertEquals("SCRIPTED", after.get("character.animationMode"));
                assertEquals(Boolean.FALSE, after.get("character.uploadConfigurations"));
                assertEquals("lusia:models/character/lusia/profile.json", live.get("character.profile"));
                assertEquals(Boolean.TRUE, live.get("character.enabled"));
                assertEquals(Boolean.TRUE, live.get("character.handContacts"));
                assertEquals(128, ((Number) live.get("character.remoteDistance")).intValue());
                assertTrue(Files.readString(path).contains("Keep custom profile"));
            } finally {
                finishEdit.countDown();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test void rejectedNestedEditDoesNotMutateActiveSnapshotOrDisk() throws Exception {
        Path path = initial();
        try (var live = CommentedFileConfig.builder(path).sync().autosave().build()) {
            live.load();
            String original = Files.readString(path);
            var store = new RuntimeConfigFile(live, RuntimeConfigFileTest::valid, ignored -> fail(), () -> {}, () -> {});
            assertThrows(IllegalArgumentException.class, () -> store.update(config -> {
                config.set("character.enabled", false);
                config.remove("character.profile");
            }));
            assertEquals(original, Files.readString(path));
            assertEquals(Boolean.TRUE, live.get("character.enabled"));
            store.update(config -> config.set("character.animationMode", "SCRIPTED"));
            assertEquals("lusia:models/character/lusia/profile.json", live.get("character.profile"));
            assertEquals(Boolean.TRUE, live.get("character.enabled"));
        }
    }

    @Test void malformedExternalReloadRetainsLastValidSettingsAndLaterValidReloadWorks() throws Exception {
        Path path = initial();
        try (var live = CommentedFileConfig.builder(path).sync().autosave().build()) {
            live.load();
            var events = new AtomicInteger();
            var store = new RuntimeConfigFile(live, RuntimeConfigFileTest::valid, ignored -> fail(), () -> {}, events::incrementAndGet);
            String original = Files.readString(path);
            Files.writeString(path, "[character\nprofile = \"unfinished");
            assertThrows(RuntimeException.class, store::reload);
            assertEquals("lusia:models/character/lusia/profile.json", live.get("character.profile"));
            assertEquals(Boolean.TRUE, live.get("character.enabled"));
            assertEquals(0, events.get());
            Files.writeString(path, original.replace("remoteDistance = 128", "remoteDistance = 96"));
            store.reload();
            assertEquals(96, ((Number) live.get("character.remoteDistance")).intValue());
            assertEquals(1, events.get());
        }
    }

    @Test void inMemoryNeoConfigTracksAtomicEditsAndExternalReloadsWithoutLosingComments() throws Exception {
        Path path=initial();
        var live=TomlFormat.instance().createParser().parse(Files.readString(path));
        var events=new AtomicInteger();
        var store=new RuntimeConfigFile(live,path,RuntimeConfigFileTest::valid,ignored->fail(),()->{},events::incrementAndGet);
        store.update(config->config.set("character.animationMode","SCRIPTED"));
        assertEquals("SCRIPTED",live.get("character.animationMode"));
        assertEquals("lusia:models/character/lusia/profile.json",live.get("character.profile"));
        assertTrue(Files.readString(path).contains("Keep custom profile"));
        store.reload();assertEquals(1,events.get());
        String previous=Files.readString(path);
        Files.writeString(path,"[character\nunfinished");
        assertThrows(RuntimeException.class,store::reload);
        assertEquals("SCRIPTED",live.get("character.animationMode"));
        Files.writeString(path,previous.replace("remoteDistance = 128","remoteDistance = 80"));
        store.reload();assertEquals(80,((Number)live.get("character.remoteDistance")).intValue());
        assertEquals(2,events.get());
    }

    @Test void editsTargetTheCurrentConfigAfterTheLoaderReplacesItsObject() throws Exception {
        Path path=initial();
        var original=TomlFormat.instance().createParser().parse(Files.readString(path));
        var active=new AtomicReference<CommentedConfig>(original);
        var store=new RuntimeConfigFile(active::get,path,RuntimeConfigFileTest::valid,ignored->fail(),()->{},()->{});
        var replacement=TomlFormat.instance().createParser().parse(Files.readString(path));
        active.set(replacement);
        store.update(config->config.set("character.remotePlayers",false));
        assertEquals(Boolean.FALSE,replacement.get("character.remotePlayers"));
        assertEquals(Boolean.TRUE,original.get("character.remotePlayers"));
        assertEquals(Boolean.FALSE,TomlFormat.instance().createParser().parse(Files.readString(path)).get("character.remotePlayers"));
    }

    @Test void retriesTransientWindowsAccessDeniedDuringFileReplacement() throws Exception {
        Path path=initial();
        var live=TomlFormat.instance().createParser().parse(Files.readString(path));
        var attempts=new AtomicInteger();
        var store=new RuntimeConfigFile(live,path,RuntimeConfigFileTest::valid,ignored->fail(),()->{},()->{},
                (pending,target)->{
                    if(attempts.getAndIncrement()==0)throw new java.nio.file.AccessDeniedException(target.toString());
                    Files.move(pending,target,StandardCopyOption.REPLACE_EXISTING);
                });

        store.update(config->config.set("character.remotePlayers",false));

        assertEquals(2,attempts.get());
        assertEquals(Boolean.FALSE,live.get("character.remotePlayers"));
        assertEquals(Boolean.FALSE,TomlFormat.instance().createParser().parse(Files.readString(path)).get("character.remotePlayers"));
    }

    @Test void persistentReplacementDenialKeepsPreviousConfigurationAndRemovesPendingFile() throws Exception {
        Path path=initial();
        String original=Files.readString(path);
        var live=TomlFormat.instance().createParser().parse(original);
        var attempts=new AtomicInteger();
        var store=new RuntimeConfigFile(live,path,RuntimeConfigFileTest::valid,ignored->fail(),()->{},()->{},
                (pending,target)->{
                    attempts.incrementAndGet();
                    throw new java.nio.file.AccessDeniedException(target.toString());
                });

        assertThrows(java.nio.file.AccessDeniedException.class,
                ()->store.update(config->config.set("character.remotePlayers",false)));

        assertEquals(3,attempts.get());
        assertEquals(original,Files.readString(path));
        assertEquals(Boolean.TRUE,live.get("character.remotePlayers"));
        try(var files=Files.list(directory)) {
            assertEquals(1,files.count(),"Failed replacement should remove its temporary file");
        }
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for test edit"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }
}
