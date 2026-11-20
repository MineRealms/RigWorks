package cn.minerealms.rigworks.character.client;

import cn.minerealms.assimploader.core.AssimpModelLoader;
import cn.minerealms.assimploader.core.ModelImportMode;
import cn.minerealms.rigworks.character.core.CharacterEquipmentDefinition;
import cn.minerealms.rigworks.character.core.CharacterProfile;
import cn.minerealms.rigworks.character.core.CharacterVisualConfig;
import cn.minerealms.rigworks.character.core.CharacterVisualPackage;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CharacterLocalModelAssetsTest {
    private static final String ORIGINAL="demo:models/avatar/model.fbx";
    private static final Path FIXTURE=Path.of("src/main/resources/assets/rigworks/models/character/block_player/block_player.fbx");
    @TempDir Path temp;

    @BeforeEach void clearBefore(){AssimpModelLoader.clearCache();}
    @AfterEach void clearAfter(){AssimpModelLoader.clearCache();}

    @Test void independentFbxCopiesSurvivePersistentRegistryAndCacheInvalidation()throws Exception {
        var first=copy("first",false);var second=copy("second",true);
        var firstScene=new AssimpModelLoader().loadModel(first,ModelImportMode.DYNAMIC);
        var secondScene=new AssimpModelLoader().loadModel(second,ModelImportMode.DYNAMIC);
        var original=new ResourceLocation(ORIGINAL);
        AssimpModelLoader.registerModel(original,firstScene);
        var registry=new DiskRegistry(temp.resolve("assets.json"));registry.put(ORIGINAL,first);
        String hashA=CharacterLocalModelAssets.hash(first),hashB=CharacterLocalModelAssets.hash(second);
        assertNotEquals(hashA,hashB);
        UUID copyA=UUID.randomUUID(),copyB=UUID.randomUUID();
        var visualA=CharacterLocalModelAssets.bind(copyA,visual(hashA),first,registry);
        var visualB=CharacterLocalModelAssets.bind(copyB,visual(hashB),second,registry);
        var idA=new ResourceLocation(visualA.profile().model());var idB=new ResourceLocation(visualB.profile().model());
        assertNotEquals(original,idA);assertNotEquals(original,idB);assertNotEquals(idA,idB);
        CharacterLocalModelAssets.register(idA,firstScene,registry);
        CharacterLocalModelAssets.register(idB,secondScene,registry);
        assertSame(firstScene,AssimpModelLoader.getCachedModel(ORIGINAL,ModelImportMode.DYNAMIC));
        assertSame(firstScene,CharacterLocalModelAssets.load(idA,registry));
        assertSame(secondScene,CharacterLocalModelAssets.load(idB,registry));
        assertEquals(first,registry.get(ORIGINAL));
        String profileA="rigworks:avatars/"+copyA+"/profile.json";
        var roundTrip=CharacterVisualPackage.parse(new CharacterVisualPackage(profileA,null,visualA,Map.of()).json()).visual();
        assertEquals(visualA,roundTrip);assertEquals(idA.toString(),roundTrip.profile().model());
        assertEquals(hashA,roundTrip.modelHash());assertEquals(visualA.profile().humanoid(),roundTrip.profile().humanoid());

        AssimpModelLoader.clearCache();
        var reopenedRegistry=new DiskRegistry(temp.resolve("assets.json"));
        var reloadedB=CharacterLocalModelAssets.load(idB,reopenedRegistry);
        var reloadedA=CharacterLocalModelAssets.load(idA,reopenedRegistry);
        assertEquals(first,reloadedA.getSourcePath());assertEquals(second,reloadedB.getSourcePath());
        assertNotSame(reloadedA,reloadedB);assertTrue(reloadedA.hasSkeleton());assertTrue(reloadedB.hasSkeleton());
        assertEquals(hashA,CharacterLocalModelAssets.hash(reloadedA.getSourcePath()));
        assertEquals(hashB,CharacterLocalModelAssets.hash(reloadedB.getSourcePath()));
    }

    @Test void identitySeparatesBothCopyIdentityAndContentRevision()throws Exception {
        Path first=copy("original",false),second=copy("revision",true);UUID copy=UUID.randomUUID();
        String originalHash=CharacterLocalModelAssets.hash(first),revisionHash=CharacterLocalModelAssets.hash(second);
        var original=CharacterLocalModelAssets.identity(copy,originalHash,first);
        assertNotEquals(original,CharacterLocalModelAssets.identity(UUID.randomUUID(),originalHash,first));
        assertNotEquals(original,CharacterLocalModelAssets.identity(copy,revisionHash,second));
        assertEquals(original,CharacterLocalModelAssets.identity(copy,originalHash,first));
        assertEquals(AssimpModelLoader.canonicalIdentity(original),
                AssimpModelLoader.canonicalIdentity(new ResourceLocation(original.toString().replace(".fbx",".glb"))));
    }

    @Test void reopeningSameCopyWithIdenticalBytesPreservesTheExistingSceneAndItsSource()throws Exception {
        Path first=copy("first",false),reopened=copy("reopened",false);UUID copy=UUID.randomUUID();
        var registry=new DiskRegistry(temp.resolve("assets.json"));String hash=CharacterLocalModelAssets.hash(first);
        var firstScene=new AssimpModelLoader().loadModel(first,ModelImportMode.DYNAMIC);
        var reopenedScene=new AssimpModelLoader().loadModel(reopened,ModelImportMode.DYNAMIC);
        var original=CharacterLocalModelAssets.bind(copy,visual(hash),first,registry);
        var id=new ResourceLocation(original.profile().model());
        CharacterLocalModelAssets.register(id,firstScene,registry);
        assertEquals(original,CharacterLocalModelAssets.bind(copy,visual(hash),reopened,registry));
        CharacterLocalModelAssets.register(id,reopenedScene,registry);
        assertSame(firstScene,CharacterLocalModelAssets.load(id,registry));
        assertEquals(first,registry.get(id.toString()));
        assertEquals(reopened,reopenedScene.getSourcePath());
        assertNull(firstScene.getSourceResource());assertNull(reopenedScene.getSourceResource());
    }

    @Test void wrongExpectedHashCannotRebindOrOverwriteAnotherSource()throws Exception {
        Path first=copy("first",false),second=copy("second",true);UUID copy=UUID.randomUUID();
        var registry=new DiskRegistry(temp.resolve("assets.json"));String hash=CharacterLocalModelAssets.hash(first);
        var firstScene=new AssimpModelLoader().loadModel(first,ModelImportMode.DYNAMIC);
        var bound=CharacterLocalModelAssets.bind(copy,visual(hash),first,registry);
        var id=new ResourceLocation(bound.profile().model());
        CharacterLocalModelAssets.register(id,firstScene,registry);
        var mismatch=assertThrows(IOException.class,()->CharacterLocalModelAssets.bind(copy,visual(hash),second,registry));
        assertTrue(mismatch.getMessage().contains("expected "+hash));
        assertThrows(IOException.class,()->CharacterLocalModelAssets.register(id,
                new AssimpModelLoader().loadModel(second,ModelImportMode.DYNAMIC),registry));
        assertSame(firstScene,CharacterLocalModelAssets.load(id,registry));assertEquals(first,registry.get(id.toString()));
    }

    @Test void resourcePackSourceKeepsItsResourceIdentityAndHasNoLocalRegistryEntry()throws Exception {
        byte[] bytes=Files.readAllBytes(FIXTURE);var source=new ResourceLocation(ORIGINAL);
        var scene=new AssimpModelLoader().loadModelFromResources(source,ignored->bytes,ModelImportMode.DYNAMIC);
        var registry=new DiskRegistry(temp.resolve("assets.json"));
        CharacterLocalModelAssets.register(source,scene,registry);
        assertSame(scene,AssimpModelLoader.getCachedModel(ORIGINAL,ModelImportMode.DYNAMIC));
        assertEquals(source,scene.getSourceResource());assertNull(scene.getSourcePath());assertNull(registry.get(ORIGINAL));
        assertFalse(Files.exists(temp.resolve("assets.json")));
    }

    private Path copy(String directory,boolean distinctBytes)throws IOException {
        Path target=temp.resolve(directory).resolve("model.fbx");Files.createDirectories(target.getParent());
        byte[] bytes=Files.readAllBytes(FIXTURE);
        if(distinctBytes) {
            // A valid FBX with a distinct byte identity; the importer ignores this trailing annotation.
            byte[] annotation="\nRigWorks independent copy regression\n".getBytes(StandardCharsets.UTF_8);
            int length=bytes.length;bytes=Arrays.copyOf(bytes,length+annotation.length);
            System.arraycopy(annotation,0,bytes,length,annotation.length);
        }
        return Files.write(target,bytes).toAbsolutePath().normalize();
    }

    private static CharacterVisualConfig visual(String hash) {
        var profile=new CharacterProfile(ORIGINAL,1,Map.of("head",new CharacterProfile.HumanoidBone("Head",CharacterProfile.Rotation.IDENTITY)),
                Map.of("idle","idle"),null,.15,.02,CharacterProfile.HeadLook.defaults());
        return new CharacterVisualConfig(profile,CharacterEquipmentDefinition.defaults(profile),CharacterVisualConfig.Transform.IDENTITY,hash,null,null);
    }

    /** Reopens the file on every lookup so a test cannot pass using only an in-memory map. */
    private record DiskRegistry(Path file) implements CharacterLocalModelAssets.Registry {
        public Path get(String id) {
            try{var json=read();return json.has(id)?Path.of(json.get(id).getAsString()):null;}
            catch(IOException error){throw new java.io.UncheckedIOException(error);}
        }
        public void put(String id,Path source)throws IOException {
            var json=read();json.addProperty(id,source.toString());Files.writeString(file,json.toString());
        }
        private JsonObject read()throws IOException {
            return Files.exists(file)?JsonParser.parseString(Files.readString(file)).getAsJsonObject():new JsonObject();
        }
    }
}
