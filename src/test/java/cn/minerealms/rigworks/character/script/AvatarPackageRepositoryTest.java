package cn.minerealms.rigworks.character.script;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AvatarPackageRepositoryTest {
    @TempDir Path temp;

    @Test void scanFindsAvatarSourcesSkipsCacheAndReloadsWithoutReplacingOtherBundles() throws Exception {
        var repository=AvatarPackageRepository.forGame(temp);Files.createDirectories(repository.directory());
        writeAvatar(repository.directory().resolve("one"),"demo:one","one.js");writeAvatar(repository.directory().resolve("two"),"demo:two","two.js");
        Files.createDirectories(repository.directory().resolve("cache"));Files.writeString(repository.directory().resolve("cache/deadbeef.json"),"{}");
        assertEquals(2,repository.refresh().size());var original=repository.find("demo:two").orElseThrow();
        var old=repository.find("demo:one").orElseThrow();Files.writeString(repository.directory().resolve("one/one.js"),"RigWorks.register('demo:controller', function(f){f.defaultMotion();});");
        var updated=repository.reload("demo:one");assertTrue(updated.valid(),updated.error());assertNotEquals(old.hash(),updated.bundle().hash());
        assertSame(original,repository.find("demo:two").orElseThrow());
        assertEquals(2,repository.forProfile("demo:models/profile.json").orElseThrow().files().size());
    }

    @Test void invalidReloadPreservesLastPublishedBundleAndReportsFailure() throws Exception {
        var repository=AvatarPackageRepository.forGame(temp);writeAvatar(repository.directory().resolve("one"),"demo:one","one.js");repository.refresh();
        var old=repository.find("demo:one").orElseThrow();Files.writeString(repository.directory().resolve("one/manifest.json"),"{");
        assertFalse(repository.reload("demo:one").valid());assertSame(old,repository.find("demo:one").orElseThrow());
        var refreshed=repository.refresh();assertTrue(refreshed.get(0).valid());assertFalse(refreshed.get(0).error().isEmpty());
        assertSame(old,repository.find("demo:one").orElseThrow());
    }

    @Test void hashIsDeterministicRegardlessOfMapInsertionAndRejectsTraversalOrOversizedFiles() {
        var first=new LinkedHashMap<String,String>();first.put("controller.js","RigWorks.register('demo:controller',function(){});");first.put("mappings.json","{}");
        var second=new LinkedHashMap<String,String>();second.put("mappings.json","{}");second.put("controller.js",first.get("controller.js"));
        assertEquals(bundle(first).hash(),bundle(second).hash());
        assertThrows(IllegalArgumentException.class,()->bundle(Map.of("../escape.js","x")));
        assertThrows(IllegalArgumentException.class,()->bundle(Map.of("controller.js","x".repeat(65537))));
        assertThrows(IllegalArgumentException.class,()->bundle(Map.of("controller.js","x","manifest.json","{\"scripts\":[\"missing.js\"]}")));
    }

    @Test void localArchiveImportKeepsSubfoldersAndForksAnExistingDifferentProfile() throws Exception {
        var repository=AvatarPackageRepository.forGame(temp);Path archive=temp.resolve("imported/extensions/rigworks/behavior");
        writeNestedAvatar(archive,"old");var original=repository.importLocal(AvatarPackageRepository.readBundle(archive));
        Files.writeString(archive.resolve("config/actions/options.json"),"{\"name\":\"new\"}");
        var imported=repository.importLocal(AvatarPackageRepository.readBundle(archive));
        assertNotEquals(original.bundle().profile(),imported.bundle().profile());
        assertNotEquals(original.id(),imported.id());assertNotEquals(original.directory(),imported.directory());
        assertEquals("{\"name\":\"old\"}",Files.readString(original.directory().resolve("config/actions/options.json")));
        assertEquals("{\"name\":\"new\"}",Files.readString(imported.directory().resolve("config/actions/options.json")));
        assertEquals("demo:controller",imported.bundle().controller());
        assertEquals(imported.bundle().hash(),AvatarPackageRepository.readBundle(imported.directory()).hash());
        assertEquals(imported.directory(),repository.importLocal(AvatarPackageRepository.readBundle(archive)).directory());
        assertFalse(Files.exists(temp.resolve("kubejs/client_scripts")));
    }

    @Test void sourceEditsBecomeTheExportSnapshotAndInvalidScriptLeavesLastBundlePublished() throws Exception {
        var repository=AvatarPackageRepository.forGame(temp);Path archive=temp.resolve("local");writeNestedAvatar(archive,"first");
        var entry=repository.importLocal(AvatarPackageRepository.readBundle(archive));
        Path json=entry.directory().resolve("config/actions/options.json");Files.writeString(json,"{\"name\":\"edited\"}");
        var changed=repository.reload(entry.id());assertTrue(changed.valid(),changed.error());
        assertNotEquals(entry.bundle().hash(),changed.bundle().hash());
        assertEquals("{\"name\":\"edited\"}",changed.bundle().files().get("config/actions/options.json"));
        Files.writeString(entry.directory().resolve("scripts/actions/controller.js"),"RigWorks.register('demo:controller', function( {");
        try(var probe=AvatarScriptRuntime.open(changed.bundle())){
            assertTrue(probe.available(),probe.failure());
            var invalid=repository.reload(entry.id());assertFalse(invalid.valid());assertFalse(invalid.error().isBlank());
            assertSame(changed.bundle(),repository.find(entry.id()).orElseThrow());
        }
    }

    @Test void invalidExistingSourceFolderIsNeverOverwrittenByImport() throws Exception {
        var repository=AvatarPackageRepository.forGame(temp);Path archive=temp.resolve("local");writeNestedAvatar(archive,"first");
        var entry=repository.importLocal(AvatarPackageRepository.readBundle(archive));
        Files.writeString(entry.directory().resolve("manifest.json"),"{");
        var imported=repository.importLocal(AvatarPackageRepository.readBundle(archive));
        assertNotEquals(entry.directory(),imported.directory());
        assertEquals("{",Files.readString(entry.directory().resolve("manifest.json")));
    }

    private static void writeNestedAvatar(Path folder,String name) throws Exception {
        Files.createDirectories(folder.resolve("config/actions"));Files.createDirectories(folder.resolve("scripts/actions"));
        Files.writeString(folder.resolve("manifest.json"),"{\"version\":1,\"id\":\"demo:nested\",\"profile\":\"demo:models/profile.json\",\"controller\":\"demo:controller\",\"scripts\":[\"scripts/actions/controller.js\"],\"mappings\":\"config/mappings.json\"}");
        Files.writeString(folder.resolve("scripts/actions/controller.js"),"RigWorks.register('demo:controller', function(f){f.defaultAnimation();});");
        Files.writeString(folder.resolve("config/mappings.json"),"{}");
        Files.writeString(folder.resolve("config/actions/options.json"),"{\"name\":\""+name+"\"}");
    }
    private static AvatarScriptBundle bundle(Map<String,String> files){return new AvatarScriptBundle("test","demo:models/profile.json","demo:controller",files);}
    private static void writeAvatar(Path folder,String id,String script) throws Exception {
        Files.createDirectories(folder);Files.writeString(folder.resolve("manifest.json"),"{\"version\":1,\"id\":\""+id+"\",\"profile\":\"demo:models/profile.json\",\"controller\":\"demo:controller\",\"scripts\":[\""+script+"\"]}");
        Files.writeString(folder.resolve(script),"RigWorks.register('demo:controller',function(f){f.defaultAnimation();});");
    }
}
