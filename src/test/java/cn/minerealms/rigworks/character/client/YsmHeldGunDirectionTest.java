package cn.minerealms.rigworks.character.client;

import cn.minerealms.assimploader.animation.PoseSnapshot;
import cn.minerealms.assimploader.core.AssimpModelLoader;
import cn.minerealms.assimploader.core.ModelImportMode;
import cn.minerealms.rigworks.ysm.asset.RwymNative;
import cn.minerealms.rigworks.ysm.asset.RwymReader;
import cn.minerealms.rigworks.ysm.model.YsmGlbWriter;
import cn.minerealms.rigworks.ysm.runtime.YsmExpressionRuntime;
import cn.minerealms.rigworks.ysm.runtime.YsmModelSource;
import cn.minerealms.rigworks.ysm.runtime.YsmPoseDriver;
import cn.minerealms.rigworks.ysm.runtime.YsmVariableContext;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class YsmHeldGunDirectionTest {
    @Test void realRiflePoseKeepsNativeGunMuzzleAheadOfThePlayer() throws Exception {
        byte[] bytes;
        try(var input=getClass().getResourceAsStream("/ysm/Flandre_Scarlet.ysm")) {
            assertNotNull(input);bytes=input.readAllBytes();
        }
        var model=RwymReader.read(RwymNative.convert(bytes)).mainModel();
        var source=YsmModelSource.reflective().view(model);
        var scene=new AssimpModelLoader().loadModelFromMemory("held-direction.glb",
                YsmGlbWriter.toRuntimeGlb(model),ModelImportMode.DYNAMIC);
        for(String clip:new String[]{"tac:hold:rifle","tac:aim:rifle"}) {
            var driver=new YsmPoseDriver(source.animations(),source.bones());
            var context=new YsmVariableContext();
            context.bind(new YsmExpressionRuntime.MapContext(Map.of(),Set.of()));
            driver.setContext(context);
            var sink=driver.scoped("tac","direction_fixture");
            assertTrue(sink.hasClip(clip),"The real source must contain "+clip);
            sink.show(clip,1);
            var locals=new LinkedHashMap<String,Matrix4f>();
            driver.apply(scene,locals,.5);
            var pose=PoseSnapshot.capture(1,scene,locals);
            var item=CharacterItemFrames.frame(CharacterItemFrames.ysmHandMount("RightHandLocator"),
                    pose.getNodeTransform("RightHandLocator"),1f/16,true);
            assertNotNull(item);
            // TaCZ M4's muzzle and thirdperson_hand have no relative rotation. Its
            // native item transform flips X/Y; the muzzle still points along -Z.
            var muzzle=item.transformDirection(new Vector3f(0,0,-1)).normalize();
            assertTrue(muzzle.z>.7f,clip+" must aim toward the scene's +Z front, actual="+muzzle);
            // BedrockModel bakes authored +Y as -Y; GunItemTransforms' Y flip
            // restores it. The renderer's resulting native-item up is +Y.
            var up=item.transformDirection(new Vector3f(0,1,0)).normalize();
            assertTrue(up.y>.7f,clip+" must keep the gun upright, actual="+up);
        }
    }
}
