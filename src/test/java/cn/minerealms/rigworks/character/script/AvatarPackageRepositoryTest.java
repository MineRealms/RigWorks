package cn.minerealms.rigworks.character.script;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AvatarPackageRepositoryTest {
    @TempDir Path temp;

    @Test void scanFindsAvatarSourcesSkipsCacheAndReloadsWithoutReplacingOtherBundles() throws Exception {
        var repository=AvatarPackageRepository.forGame(temp);Files.createDirectories(repository.directory());
        writeAvatar(repository.directory().resolve("one"),"demo:one","one.js");writeAvatar(repository.directory().resolve("two"),"demo:two","two.js");
        Files.createDirectories(repository.directory().resolve("cache"));Files.writeString(repository.directory().resolve("cache/deadbeef.json"),"{}");
        assertEquals(2,repository.refresh().size());var original=repository.find("demo:two").orElseThrow();
        assertNotEquals(repository.find("demo:one").orElseThrow().instanceId(),original.instanceId());
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

    @Test void legacyManifestGetsStableInstanceIdentityAndIdentityParticipatesInHash() {
        var first = bundle(Map.of("controller.js", "RigWorks.register('demo:controller',function(){});"));
        var second = bundle(Map.of("controller.js", "RigWorks.register('demo:controller',function(){});"));
        UUID expected = UUID.nameUUIDFromBytes("legacy:test\ndemo:models/profile.json".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(expected, first.instanceId());
        assertEquals(first.instanceId(), second.instanceId());
        assertEquals(1, first.manifest().get("version").getAsInt());
        assertEquals(expected.toString(), first.manifest().get("instanceId").getAsString());

        UUID otherIdentity = UUID.randomUUID();
        var independent = AvatarPackageRepository.rebind(first, first.id(), first.profile(), otherIdentity);
        assertNotEquals(first.hash(), independent.hash());
        assertEquals(otherIdentity, independent.instanceId());
        assertThrows(IllegalArgumentException.class,
                () -> new AvatarScriptBundle(first.id(), first.profile(), first.controller(), otherIdentity, first.files()));
    }

    @Test void legacyCopiesOfOneProfileUseSeparateIdsAndPreservePublishedExplicitIds() {
        String profile="demo:models/profile.json";
        var files=Map.of("controller.js","RigWorks.register('demo:controller',function(){});");
        var first=new AvatarScriptBundle("demo:one",profile,"demo:controller",files);
        var second=new AvatarScriptBundle("demo:two",profile,"demo:controller",files);
        assertNotEquals(first.instanceId(),second.instanceId());
        assertEquals(AvatarScriptBundle.legacyInstanceId("demo:one",profile),first.instanceId());

        UUID previous=UUID.nameUUIDFromBytes(("legacy:"+profile).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var published=new LinkedHashMap<>(files);
        published.put("manifest.json","{\"instanceId\":\""+previous+"\"}");
        var restored=new AvatarScriptBundle("demo:one",profile,"demo:controller",published);
        assertEquals(previous,restored.instanceId());
        assertEquals(previous,new AvatarScriptBundle(restored.id(),profile,restored.controller(),restored.files()).instanceId());
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

    @Test void importUsesInstanceDirectoryAndFindsByIdentity() throws Exception {
        var repository=AvatarPackageRepository.forGame(temp);Path archive=temp.resolve("local");writeNestedAvatar(archive,"first");
        var imported=repository.importLocal(AvatarPackageRepository.readBundle(archive));
        Path expected=repository.directory().resolve("avatars").resolve(imported.bundle().instanceId().toString());
        assertEquals(expected,imported.directory());
        assertEquals(imported.bundle(),repository.findInstance(imported.bundle().instanceId()).orElseThrow().bundle());
        assertEquals(imported.bundle(),repository.findInstance(imported.bundle().instanceId().toString()).orElseThrow().bundle());
        assertEquals(imported.bundle(),repository.forInstance(imported.bundle().instanceId()).orElseThrow());
        assertEquals(imported.bundle().instanceId().toString(),
                AvatarPackageRepository.readBundle(imported.directory()).manifest().get("instanceId").getAsString());
    }

    @Test void explicitReplacementMigratesLegacyDirectoryWithoutChangingIdentity() throws Exception {
        var repository=AvatarPackageRepository.forGame(temp);Path legacy=repository.directory().resolve("avatar_legacy");
        writeNestedAvatar(legacy,"before");var original=repository.refresh().stream().filter(AvatarPackageRepository.Entry::valid).findFirst().orElseThrow();
        UUID identity=original.bundle().instanceId();
        var files=new LinkedHashMap<>(original.bundle().files());
        files.put("config/actions/options.json","{\"name\":\"after\"}");
        var replacement=new AvatarScriptBundle(original.bundle().id(),original.bundle().profile(),original.bundle().controller(),identity,files);

        var updated=repository.replaceLocal(identity,replacement);
        assertEquals(identity,updated.bundle().instanceId());
        assertEquals(original.bundle().id(),updated.bundle().id());
        assertEquals(original.bundle().profile(),updated.bundle().profile());
        assertNotEquals(original.bundle().hash(),updated.bundle().hash());
        assertFalse(Files.exists(legacy));
        assertEquals(repository.directory().resolve("avatars").resolve(identity.toString()),updated.directory());
        assertEquals("{\"name\":\"after\"}",Files.readString(updated.directory().resolve("config/actions/options.json")));
    }

    @Test void invalidReplacementLeavesPublishedCopyUntouched() throws Exception {
        var repository=AvatarPackageRepository.forGame(temp);Path archive=temp.resolve("local");writeNestedAvatar(archive,"before");
        var original=repository.importLocal(AvatarPackageRepository.readBundle(archive));
        var files=new LinkedHashMap<>(original.bundle().files());
        files.put("scripts/actions/controller.js","RigWorks.register('demo:controller', function( {");
        var invalid=new AvatarScriptBundle(original.id(),original.bundle().profile(),original.bundle().controller(),
                original.bundle().instanceId(),files);

        assertThrows(java.io.IOException.class,()->repository.replaceLocal(invalid));
        assertEquals(original.bundle().hash(),repository.findInstance(original.bundle().instanceId()).orElseThrow().bundle().hash());
        assertEquals(original.bundle().hash(),AvatarPackageRepository.readBundle(original.directory()).hash());
        assertFalse(Files.exists(repository.directory().resolve("avatars/.backup-"+original.bundle().instanceId())));
    }

    @Test void replacementRejectsAStaleHashAfterTheSourceChanges() throws Exception {
        var repository=AvatarPackageRepository.forGame(temp);Path archive=temp.resolve("local");writeNestedAvatar(archive,"before");
        var original=repository.importLocal(AvatarPackageRepository.readBundle(archive));
        var candidateFiles=new LinkedHashMap<>(original.bundle().files());
        candidateFiles.put("config/actions/options.json","{\"name\":\"archive\"}");
        var candidate=new AvatarScriptBundle(original.id(),original.bundle().profile(),original.bundle().controller(),
                original.bundle().instanceId(),candidateFiles);
        Files.writeString(original.directory().resolve("config/actions/options.json"),"{\"name\":\"newer local edit\"}");

        assertThrows(java.io.IOException.class,()->repository.replaceLocal(original.bundle().instanceId(),original.bundle().hash(),candidate));
        var current=repository.forInstance(original.bundle().instanceId()).orElseThrow();
        assertEquals("{\"name\":\"newer local edit\"}",current.files().get("config/actions/options.json"));
    }

    @Test void replacementPreservesInvalidLocalEditsEvenWhenPublishedHashStillMatches() throws Exception {
        var repository=AvatarPackageRepository.forGame(temp);Path archive=temp.resolve("local");writeNestedAvatar(archive,"before");
        var original=repository.importLocal(AvatarPackageRepository.readBundle(archive));
        var candidateFiles=new LinkedHashMap<>(original.bundle().files());
        candidateFiles.put("config/actions/options.json","{\"name\":\"archive\"}");
        var candidate=new AvatarScriptBundle(original.id(),original.bundle().profile(),original.bundle().controller(),
                original.bundle().instanceId(),candidateFiles);
        Path localEdit=original.directory().resolve("config/actions/options.json");
        Files.writeString(localEdit,"{");

        var error=assertThrows(java.io.IOException.class,()->repository.replaceLocal(original.bundle().instanceId(),original.bundle().hash(),candidate));
        assertTrue(error.getMessage().contains("local errors"),error.getMessage());
        assertEquals("{",Files.readString(localEdit));
        var retained=repository.findInstance(original.bundle().instanceId()).orElseThrow();
        assertEquals(original.bundle().hash(),retained.bundle().hash());
        assertFalse(retained.error().isBlank());
        assertFalse(Files.exists(repository.directory().resolve("avatars/.backup-"+original.bundle().instanceId())));
    }

    @Test void refreshRecoversInterruptedReplacementFromBackup() throws Exception {
        var repository=AvatarPackageRepository.forGame(temp);Path archive=temp.resolve("local");writeNestedAvatar(archive,"before");
        var original=repository.importLocal(AvatarPackageRepository.readBundle(archive));
        UUID identity=original.bundle().instanceId();
        Path avatars=repository.directory().resolve("avatars");
        Path target=avatars.resolve(identity.toString());Path backup=avatars.resolve(".backup-"+identity);
        Files.move(target,backup);
        Files.createDirectories(target);
        Files.writeString(target.resolve("manifest.json"),"{");
        String origin=java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("avatars/"+identity).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.writeString(avatars.resolve(".backup-"+identity+".origin"),
                "interrupted-hash\n"+origin);

        var recovered=repository.refresh().stream().filter(AvatarPackageRepository.Entry::valid).findFirst().orElseThrow();
        assertEquals(original.bundle().hash(),recovered.bundle().hash());
        assertEquals(identity,recovered.bundle().instanceId());
        assertFalse(Files.exists(backup));
        assertFalse(Files.exists(target.resolve("manifest.json")) && Files.readString(target.resolve("manifest.json")).equals("{"));
        try(var paths=Files.list(avatars)) {
            assertTrue(paths.anyMatch(path->path.getFileName().toString().startsWith(".failed-")));
        }
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
