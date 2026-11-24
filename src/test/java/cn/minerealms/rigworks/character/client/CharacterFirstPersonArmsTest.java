package cn.minerealms.rigworks.character.client;

import cn.minerealms.rigworks.character.core.CharacterProfile;
import net.minecraft.world.entity.HumanoidArm;
import org.junit.jupiter.api.Test;

import java.util.Map;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CharacterFirstPersonArmsTest {
    @Test void ysmArmsKeepAuthoredPivotsAndExcludeAnimatedAncestors() {
        var ancestor=new Matrix4f().translation(5,2,-3).rotateZYX(.2f,-.5f,.4f).scale(1.3f);
        var authored=new Vector3f(1.9f,18.5f,-2);
        var imported=new Vector3f(authored.x,authored.y,-authored.z);
        for(boolean left:new boolean[]{false,true}) {
            var expected=new Matrix4f().translation(left?.25f:-.25f,1.8f,0).scale(-1f/16,-1f/16,1f/16)
                    .transformPosition(new Vector3f(authored));
            var actual=CharacterFirstPersonArms.ysmPlacement(left,ancestor)
                    .transformPosition(ancestor.transformPosition(new Vector3f(imported)));
            assertEquals(expected.x,actual.x,1e-6);
            assertEquals(expected.y,actual.y,1e-6);
            assertEquals(expected.z,actual.z,1e-6);
        }
    }

    @Test void gunHandsMatchNativeAnchorsWithoutEmptyHandScreenOffsets() {
        var shoulder=new Matrix4f().translation(3,27,-2).rotateY(.7f).rotateX((float)Math.PI);
        for(boolean left:new boolean[]{false,true}) {
            var placement=CharacterFirstPersonArms.placement(shoulder,1f/16,left,true);
            var wrist=placement.transformPosition(shoulder.transformPosition(new Vector3f(0,12,0)));
            assertEquals(left?5f/16:-5f/16,wrist.x,1e-6);
            assertEquals(14f/16,wrist.y,1e-6);
            assertEquals(0,wrist.z,1e-6);
            var ordinary=CharacterFirstPersonArms.placement(shoulder,1f/16,left,false)
                    .transformPosition(shoulder.transformPosition(new Vector3f(0,12,0)));
            assertEquals(left?.13f:-.13f,ordinary.x-wrist.x,1e-6);
            assertEquals(-.10f,ordinary.y-wrist.y,1e-6);
        }
    }

    @Test void prefersForearmGeometryAndFallsBackForSimpleRigs() {
        var detailed = new CharacterProfile("test:avatar.glb", 1,
                Map.of("right_upper_arm", new CharacterProfile.HumanoidBone("Arm.R", CharacterProfile.Rotation.IDENTITY),
                        "right_forearm", new CharacterProfile.HumanoidBone("Forearm.R", CharacterProfile.Rotation.IDENTITY)),
                Map.of(), null, .15, .05, CharacterProfile.HeadLook.defaults());
        assertEquals("right_forearm", CharacterFirstPersonArms.firstPersonRole(detailed, HumanoidArm.RIGHT));
        assertEquals("right_upper_arm", CharacterFirstPersonArms.firstPersonRole(
                new CharacterProfile("test:avatar.glb", 1, Map.of(), Map.of(), null, .15, .05,
                        CharacterProfile.HeadLook.defaults()), HumanoidArm.RIGHT));
    }
}
