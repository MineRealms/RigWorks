package cn.minerealms.rigworks.character.client;

import cn.minerealms.assimploader.workbench.model.ModelTransform;
import cn.minerealms.rigworks.character.core.CharacterEquipmentDefinition;
import cn.minerealms.rigworks.character.core.CharacterProfile;
import cn.minerealms.rigworks.character.core.CharacterVisualConfig;
import com.google.gson.JsonParser;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CharacterWorkbenchTransformsTest {
    private static CharacterVisualConfig visual(int yaw){
        var profile=CharacterProfile.parse(JsonParser.parseString("{\"version\":1,\"model\":\"test:avatar.fbx\",\"scale\":0.8,\"modelYawDegrees\":"+yaw+
                ",\"humanoid\":{\"right_hand\":\"Palm\"},\"animations\":{\"walk\":\"Walk\"}}").getAsJsonObject());
        return new CharacterVisualConfig(profile,CharacterEquipmentDefinition.defaults(profile),
                new CharacterVisualConfig.Transform(1,2,3,12,23,34,.8f,.9f,1.1f),"a".repeat(64),null,null);
    }
    @Test void displayAndEditingRoundTripAcrossForwardCalibrations(){
        for(int yaw:new int[]{0,90,180,-90}){
            var value=visual(yaw);var displayed=CharacterWorkbenchTransforms.display(value);
            var expected=new Matrix4f().rotateY((float)Math.toRadians(yaw)).scale(.8f).mul(value.transform().matrix());
            assertTrue(expected.equals(displayed.matrix(),.0001f));
            var restored=CharacterWorkbenchTransforms.edit(value,displayed);
            assertTrue(value.transform().matrix().equals(restored.transform().matrix(),.0001f));
            assertEquals(value.profile(),restored.profile());assertEquals(value.equipment(),restored.equipment());
        }
    }
    @Test void sharedGizmoSetsOneEffectiveRootWithoutChangingAuthoredActions(){
        var value=visual(90);var desired=new ModelTransform(0,1,0,10,30,50,1.2f,1.4f,1.6f);
        var updated=CharacterWorkbenchTransforms.edit(value,desired);
        assertTrue(desired.matrix().equals(CharacterWorkbenchTransforms.display(updated).matrix(),.0001f));
        assertEquals("Walk",updated.profile().animation("walk"));assertEquals(.8,updated.profile().scale());
        assertThrows(IllegalArgumentException.class,()->CharacterWorkbenchTransforms.edit(value,desired.withScale(-1,1,1)));
    }
}
