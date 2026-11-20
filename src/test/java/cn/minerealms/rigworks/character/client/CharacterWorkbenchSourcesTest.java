package cn.minerealms.rigworks.character.client;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CharacterWorkbenchSourcesTest {
    @TempDir Path temp;
    private static final String PROFILE="""
        {"version":1,"model":"test:models/avatar/model.fbx","scale":0.75,
         "humanoid":{"right_hand":"Palm"},"animations":{"walk":"Walk","idle":"Stand"},
         "equipmentDefinition":"test:models/avatar/equipment.json",
         "solverDefinition":"test:models/avatar/rig.json","physicsDefinition":"test:models/avatar/physics.json"}
        """;
    private static String json(String reference){
        if(reference.endsWith("equipment.json"))return "{\"version\":1,\"right_hand\":{\"node\":\"Palm\",\"position\":[0.2,0.3,0.4]}}";
        if(reference.endsWith("rig.json"))return "{\"version\":1,\"ik\":{}}";
        return "{\"version\":1,\"bodies\":[],\"joints\":[]}";
    }

    @Test void capturesAuthoredActionsEquipmentAndSolversWithNewModelIdentity()throws Exception{
        var value=CharacterWorkbenchSources.capture(JsonParser.parseString(PROFILE).getAsJsonObject(),"rigworks:models/copy/model.fbx","a".repeat(64),
                reference->JsonParser.parseString(json(reference)).getAsJsonObject());
        assertEquals("rigworks:models/copy/model.fbx",value.profile().model());
        assertEquals("Walk",value.profile().animation("walk"));assertEquals(.75,value.profile().scale());
        assertEquals("Palm",value.equipment().rightHand().node());assertNotNull(value.rig());assertNotNull(value.physics());
        assertEquals(value.equipment(),CharacterWorkbenchSources.rebind(value,"other:model.fbx").equipment());
        assertEquals("other:model.fbx",CharacterWorkbenchSources.rebind(value,"other:model.fbx").profile().model());
    }

    @Test void adjacentProfileImportsWithoutResourceManagerOrCurrentPlayer()throws Exception{
        Path model=Files.writeString(temp.resolve("model.fbx"),"source");
        Files.writeString(temp.resolve("profile.json"),PROFILE);
        for(String name:new String[]{"equipment.json","rig.json","physics.json"})Files.writeString(temp.resolve(name),json(name));
        var selected=CharacterWorkbenchSources.loadLocal(model,"rigworks:models/new/model.fbx","b".repeat(64));
        assertNotNull(selected);assertEquals("test:models/avatar/profile.json",selected.profileId());
        assertEquals("Walk",selected.visual().profile().animation("walk"));
        assertEquals("rigworks:models/new/model.fbx",selected.visual().profile().model());
        Files.delete(temp.resolve("rig.json"));
        assertThrows(java.io.IOException.class,()->CharacterWorkbenchSources.loadLocal(model,"rigworks:models/new/model.fbx","b".repeat(64)));
    }

    @Test void unrelatedProfileCannotBeAppliedToAnotherFbx()throws Exception{
        Path model=Files.writeString(temp.resolve("other.fbx"),"source");
        Files.writeString(temp.resolve("profile.json"),PROFILE);
        assertNull(CharacterWorkbenchSources.loadLocal(model,"rigworks:models/new/model.fbx","b".repeat(64)));
    }
}
