package cn.minerealms.rigworks.character.client;

import cn.minerealms.assimploader.workbench.model.ModelTransform;
import cn.minerealms.assimploader.workbench.model.WorkbenchDocument;
import cn.minerealms.rigworks.character.core.CharacterEquipmentDefinition;
import cn.minerealms.rigworks.character.core.CharacterProfile;
import cn.minerealms.rigworks.character.core.CharacterVisualConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RigWorksWorkbenchExportTest {
    private static CharacterVisualConfig visual(){
        var profile=CharacterProfile.parse(JsonParser.parseString("""
                {"version":1,"model":"test:avatar.fbx","scale":0.01,"modelYawDegrees":90,
                 "humanoid":{"right_hand":"Palm"},"animations":{"walk":"Walk"},
                 "equipmentDefinition":"test:equipment.json","physicsDefinition":"test:physics.json"}
                """).getAsJsonObject());
        return new CharacterVisualConfig(profile,CharacterEquipmentDefinition.defaults(profile),
                new CharacterVisualConfig.Transform(1,2,3,10,20,30,2,2,2),"a".repeat(64),
                "{\"version\":1,\"ik\":{}}",null);
    }

    private static WorkbenchDocument edits(){
        var material=new WorkbenchDocument.MaterialOverride(1,1,1,1,.8f,.3f,2,
                WorkbenchDocument.AlphaMode.MASK,.1f,false,Map.of("normal","textures/normal.png"));
        return new WorkbenchDocument(1,"test:avatar","assets/model.fbx","",ModelTransform.IDENTITY,
                Map.of("Palm",new WorkbenchDocument.NodeOverride(ModelTransform.IDENTITY,false)),Map.of(0,material),
                Map.of("right_hand",new WorkbenchDocument.Socket("Palm",ModelTransform.IDENTITY)),
                WorkbenchDocument.AnimationPreview.DEFAULT,new JsonObject());
    }

    @Test void excludedCoreEditsCannotRemainInTheRigRuntimeSnapshot(){
        var source=edits();var original=visual().withAssetEdits(source);
        var selected=Set.of("rigworks:transform","rigworks:equipment","rigworks:physics","assimploader:materials");
        var exported=RigWorksWorkbenchExtension.filterExportVisual(original,source,selected);
        assertTrue(exported.assetEdits().nodes().isEmpty());
        assertTrue(exported.assetEdits().sockets().isEmpty());
        assertEquals(source.materials(),exported.assetEdits().materials());
        assertEquals(source.nodes(),original.assetEdits().nodes());
        assertEquals(source.sockets(),original.assetEdits().sockets());
    }

    @Test void equipmentAndPhysicsCanBothBeExcludedWithoutRestoringReferences(){
        var original=visual();
        var exported=RigWorksWorkbenchExtension.filterExportVisual(original,edits(),Set.of("rigworks:transform"));
        assertNull(exported.profile().equipmentDefinition());
        assertNull(exported.profile().physicsDefinition());
        assertFalse(exported.options().physics());
        assertEquals(original.rig(),exported.rig());
        assertNotNull(original.profile().equipmentDefinition());
        assertNotNull(original.profile().physicsDefinition());
    }

    @Test void excludingRootEditsKeepsImportCalibrationWithoutInvertingItsScale(){
        var original=visual();
        var exported=assertDoesNotThrow(()->RigWorksWorkbenchExtension.filterExportVisual(original,edits(),
                Set.of("rigworks:equipment","rigworks:physics")));
        assertEquals(CharacterVisualConfig.Transform.IDENTITY,exported.transform());
        assertEquals(.01,exported.profile().scale());
        assertEquals(90,exported.profile().modelYawDegrees());
        assertEquals("Walk",exported.profile().animation("walk"));
        assertEquals(2,original.transform().sx());
    }
}
