package cn.minerealms.rigworks.character.core;

import cn.minerealms.assimploader.workbench.model.ModelTransform;
import cn.minerealms.assimploader.workbench.model.WorkbenchDocument;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CharacterAssetEditsPackageTest {
    @Test void assetEditsRoundTripThroughConfigurationPackageAndBinaryCache() throws Exception {
        var edits=edits(Map.of("albedo","textures/body.png","normal","example:textures/avatar/body_n.png","specular","*2"),.2f);
        var source=CharacterVisualSyncTest.config().withAssetEdits(edits);
        var decoded=CharacterVisualConfig.parse(source.json());
        assertEquals(source.digest(),decoded.digest());assertEquals(edits,decoded.assetEdits());
        var bundle=pack(source);var transferred=CharacterVisualPackage.parse(bundle.json());
        var cached=CharacterPackageBinary.decode(CharacterPackageBinary.encode(bundle));
        assertEquals(edits,transferred.visual().assetEdits());assertEquals(edits,cached.visual().assetEdits());
        assertEquals(bundle.hash(),cached.hash());assertEquals(bundle.manifest(),transferred.manifest());
        assertEquals(.2f,cached.visual().assetEdits().materials().get(0).roughness());
        assertEquals("Palm",cached.visual().assetEdits().sockets().get("right_item").node());
        assertFalse(cached.visual().assetEdits().nodes().get("HiddenInnerMesh").visible());
    }

    @Test void localAbsoluteTexturesRemainEditableButCannotBeUploadedOrReceived() {
        for(String path:List.of("C:/private/face.png","c:/private/face.png","H:\\art\\face.png","/etc/face.png","\\\\server\\share\\face.png")) {
            var local=CharacterVisualSyncTest.config().withAssetEdits(edits(Map.of("albedo",path),.5f));
            assertEquals(path,CharacterVisualConfig.parse(local.json()).assetEdits().materials().get(0).textures().get("albedo"));
            var outgoing=assertThrows(IllegalArgumentException.class,()->pack(local),path);
            assertTrue(outgoing.getMessage().contains("albedo"));assertTrue(outgoing.getMessage().contains("installed"));
            var received=JsonParser.parseString(pack(CharacterVisualSyncTest.config()).json()).getAsJsonObject();received.addProperty("visual",local.json());
            assertThrows(IllegalArgumentException.class,()->CharacterVisualPackage.parse(received.toString()),path);
        }
    }

    @Test void unsafeTextureAliasesAndTraversalAreRejectedOnEveryMaterialSlot() {
        for(String path:List.of("../secret.png","textures/../../secret.png","..\\secret.png","textures/.. /secret.png",
                "textures/./face.png","textures//face.png","file:///C:/face.png","https://example.org/face.png",
                "example:../face.png","example:/face.png","example:textures/../../face.png","textures/%2e%2e/face.png",
                "textures/NUL.png","textures/COM1.png","textures/CONIN$","textures/face.png:private","textures/face.png ","*2147483648","*-1","*bad")) {
            var config=CharacterVisualSyncTest.config().withAssetEdits(edits(Map.of("roughness",path),.5f));
            var rejected=assertThrows(IllegalArgumentException.class,()->pack(config),path);
            assertTrue(rejected.getMessage().contains("roughness"),path);
        }
    }

    @Test void portableRelativeResourceAndEmbeddedReferencesAreAccepted() {
        for(String path:List.of("textures/face.png","textures\\face_n.png","textures/Face Color.png","example:textures/face_s.png","example:textures/con.png","minecraft:textures/block/stone.png","*0","*999999999")) {
            var config=CharacterVisualSyncTest.config().withAssetEdits(edits(Map.of("normal",path),.5f));
            assertEquals(path,CharacterVisualPackage.parse(pack(config).json()).visual().assetEdits().materials().get(0).textures().get("normal"));
        }
    }

    @Test void unusedSourceMetadataCannotSmuggleAnExternalAssetPath() {
        var local=CharacterVisualSyncTest.config().withAssetEdits(edits(Map.of(),.5f).withSource("C:/private/avatar.fbx",""));
        var error=assertThrows(IllegalArgumentException.class,()->pack(local));assertTrue(error.getMessage().contains("sourcePath"));
    }

    @Test void twoOwnersOfIdenticalFbxAndProfileKeepIndependentEditsAndRevisions() {
        var ledger=new CharacterVisualLedger();UUID first=UUID.randomUUID(),second=UUID.randomUUID();
        var selection=new CharacterAppearance(true,"test:profile.json","SCRIPTED",false,0);
        var firstConfig=CharacterVisualSyncTest.config().withAssetEdits(edits(Map.of("albedo","textures/body.png"),.2f));
        var secondConfig=CharacterVisualSyncTest.config().withAssetEdits(edits(Map.of("albedo","textures/body.png"),.8f));
        assertEquals(firstConfig.modelHash(),secondConfig.modelHash());assertEquals(firstConfig.profile().model(),secondConfig.profile().model());
        var originalA=ledger.commit(first,selection,CharacterVisualPackage.parse(pack(firstConfig).json()).visual(),0);
        var originalB=ledger.commit(second,selection,CharacterVisualPackage.parse(pack(secondConfig).json()).visual(),0);
        String secondDigest=originalB.config().digest();long secondRevision=originalB.configRevision();
        var replacement=firstConfig.withAssetEdits(edits(Map.of("albedo","textures/body_changed.png"),.1f));
        var updated=ledger.commit(first,selection,CharacterVisualPackage.parse(pack(replacement).json()).visual(),originalA.configRevision());
        assertNotEquals(originalA.config().digest(),updated.config().digest());assertTrue(updated.configRevision()>originalA.configRevision());
        assertSame(originalB,ledger.get(second));assertEquals(secondDigest,ledger.get(second).config().digest());assertEquals(secondRevision,ledger.get(second).configRevision());
        assertEquals(.8f,ledger.get(second).config().assetEdits().materials().get(0).roughness());
        assertEquals("textures/body.png",ledger.get(second).config().assetEdits().materials().get(0).textures().get("albedo"));
        assertThrows(IllegalArgumentException.class,()->ledger.commit(first,selection,secondConfig,originalA.configRevision()));
        assertSame(originalB,ledger.get(second));
    }

    private static CharacterVisualPackage pack(CharacterVisualConfig config){return new CharacterVisualPackage("test:profile.json",null,config,Map.of());}
    private static WorkbenchDocument edits(Map<String,String> textures,float roughness) {
        var transform=new ModelTransform(.1f,.2f,.3f,10,20,30,1,.9f,.8f);
        var material=new WorkbenchDocument.MaterialOverride(.2f,.3f,.4f,.8f,.9f,roughness,12,WorkbenchDocument.AlphaMode.MASK,.25f,true,textures);
        return new WorkbenchDocument(1,"rigworks:instance","assets/model.fbx","",ModelTransform.IDENTITY,
                Map.of("Palm",new WorkbenchDocument.NodeOverride(transform,true),"HiddenInnerMesh",new WorkbenchDocument.NodeOverride(ModelTransform.IDENTITY,false)),
                Map.of(0,material),Map.of("right_item",new WorkbenchDocument.Socket("Palm",transform),"left_item",new WorkbenchDocument.Socket("OtherPalm",ModelTransform.IDENTITY)),
                new WorkbenchDocument.AnimationPreview("Stand",1.25,.8f,true,false),new JsonObject());
    }
}
