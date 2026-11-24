package cn.minerealms.rigworks.character.client;

import cn.minerealms.assimploader.config.AssimpLoaderConfig;
import cn.minerealms.assimploader.core.AssimpModelLoader;
import cn.minerealms.assimploader.model.AssimpMesh;
import cn.minerealms.assimploader.model.AssimpNode;
import cn.minerealms.assimploader.model.AssimpScene;
import cn.minerealms.assimploader.animation.PoseSnapshot;
import cn.minerealms.assimploader.animation.SkinBindPose;
import cn.minerealms.assimploader.render.base.BaseAssimpRenderer;
import cn.minerealms.assimploader.rendering.MeshDrawSelection;
import cn.minerealms.rigworks.RigWorks;
import cn.minerealms.rigworks.RigWorksConfig;
import cn.minerealms.rigworks.character.core.CharacterProfile;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Replaces only native arm geometry. Native map, equip, use and item animation retain their original clock. */
@Mod.EventBusSubscriber(modid=RigWorks.MODID,value=Dist.CLIENT)
public final class CharacterFirstPersonArms {
    private static final BaseAssimpRenderer RENDERER=new BaseAssimpRenderer(){@Override protected String getModelName(){return "rigworks:first_person_arm";}};
    private static final float FIRST_PERSON_OUTWARD_OFFSET = 0.13f;
    private static final float FIRST_PERSON_DOWN_OFFSET = 0.10f;
    private static final EnumMap<HumanoidArm,Selection> CACHE=new EnumMap<>(HumanoidArm.class);
    private static final EnumMap<HumanoidArm,Selection> YSM_ARMS=new EnumMap<>(HumanoidArm.class);
    private static AssimpScene armScene, neutralScene, dedicatedScene;
    private static CharacterProfile armProfile;
    private static CharacterProfile neutralProfile;
    private static PoseSnapshot neutralPose;
    private record Selection(MeshDrawSelection draw,boolean empty,int triangles,AssimpNode parent) {
        Selection(MeshDrawSelection draw,boolean empty,int triangles){this(draw,empty,triangles,null);}
    }
    private static long generation=-1;
    private static long leftDraws,rightDraws;
    private static int leftTriangles,rightTriangles;
    private CharacterFirstPersonArms(){}

    public static boolean render(AbstractClientPlayer player,HumanoidArm arm,PoseStack poses,MultiBufferSource buffers,int light) {
        var minecraft=Minecraft.getInstance();
        if(player!=minecraft.player||player.isInvisible()||!AssimpLoaderConfig.getOrDefault(RigWorksConfig.CLIENT.firstPerson::get,true))return false;
        var view=LocalPlayerCharacter.view(player,minecraft.getFrameTime());if(view==null)return false;
        if(generation!=AssimpModelLoader.getResourceGeneration()){clear();generation=AssimpModelLoader.getResourceGeneration();}
        if(armScene!=view.scene()||armProfile!=view.profile()) {
            clear();armScene=view.scene();armProfile=view.profile();
        }
        boolean gunHand=TaczFirstPersonCompat.drawingHand(player);
        var ysm=LocalPlayerCharacter.currentYsmRuntime();
        var firstPerson=ysm==null?null:ysm.firstPersonView(minecraft.getFrameTime());
        if(firstPerson!=null&&renderYsm(firstPerson,arm,poses,buffers,light))return true;
        var selection=CACHE.computeIfAbsent(arm,ignored->selection(view.scene(),view.profile(),arm));
        if(selection.empty())return false;
        var snapshot=gunHand?neutralPose(view.scene(),view.profile()):view.snapshot();
        var frames=CharacterAttachmentFrames.of(view.profile(),view.scene());
        var frame=frames.arm(snapshot,arm==HumanoidArm.LEFT);
        if(frame==null)return false;
        Matrix4f placement=placement(frame,frames.armScale(arm==HumanoidArm.LEFT),arm==HumanoidArm.LEFT,gunHand);
        poses.pushPose();
        try (var edits=LocalPlayerCharacter.renderEdits(player)) {
            cn.minerealms.rigworks.util.PoseTransforms.multiply(poses,placement);
            RENDERER.renderSelected(view.scene(),view.animation(),snapshot,selection.draw(),poses,buffers,light,OverlayTexture.NO_OVERLAY,minecraft.getFrameTime());
            if(arm==HumanoidArm.LEFT){leftDraws++;leftTriangles=selection.triangles();}
            else{rightDraws++;rightTriangles=selection.triangles();}
        } finally {poses.popPose();}
        return true;
    }

    /** Native PlayerRenderer arm origins are (-/+5,2,0) pixels, with +Y pointing toward the hand. */
    public static Matrix4f placement(Matrix4f customShoulder,double scale,boolean left) {
        return placement(customShoulder,scale,left,false);
    }

    static Matrix4f placement(Matrix4f customShoulder,double scale,boolean left,boolean gunHand) {
        if(!Double.isFinite(scale)||scale<=0)throw new IllegalArgumentException("Invalid arm scale");
        float outward=gunHand?0:left?FIRST_PERSON_OUTWARD_OFFSET:-FIRST_PERSON_OUTWARD_OFFSET;
        return new Matrix4f().translation(left?5f/16+outward:-5f/16+outward,
                2f/16-(gunHand?0:FIRST_PERSON_DOWN_OFFSET),0).scale((float)scale).mul(new Matrix4f(customShoulder).invert());
    }

    private static PoseSnapshot neutralPose(AssimpScene scene,CharacterProfile profile) {
        if(neutralScene!=scene||neutralProfile!=profile) {
            var locals=new LinkedHashMap<>(SkinBindPose.locals(scene));
            profile.humanoid().values().forEach(bone->{
                var local=locals.get(bone.node());
                if(local!=null)locals.put(bone.node(),VanillaPoseRetargeter.calibratedLocal(local,bone.nativeRotation()));
            });
            neutralPose=PoseSnapshot.capture(0,scene,locals);
            neutralScene=scene;neutralProfile=profile;
        }
        return neutralPose;
    }

    private static boolean renderYsm(cn.minerealms.rigworks.ysm.runtime.YsmRuntimeInstance.FirstPersonView view,
                                     HumanoidArm arm,PoseStack poses,MultiBufferSource buffers,int light) {
        String root=arm==HumanoidArm.LEFT?"LeftArm":"RightArm";
        var scene=view.scene();
        var rootNode=scene.findNodeByName(root);
        if(rootNode==null)return false;
        if(dedicatedScene!=scene) {closeSelections(YSM_ARMS);dedicatedScene=scene;}
        var selection=YSM_ARMS.computeIfAbsent(arm,ignored->{
            var names=new HashSet<String>();descendants(rootNode,names);
            var selected=selection(scene,names);
            return new Selection(selected.draw(),selected.empty(),selected.triangles(),parent(scene.getRootNode(),rootNode));
        });
        if(selection.empty())return false;
        var ancestor=selection.parent()==null?null:view.snapshot().getNodeTransform(selection.parent().getName());
        if(ancestor!=null&&(!ancestor.isFinite()||Math.abs(ancestor.determinant3x3())<1e-12f))return true;
        Matrix4f placement=ysmPlacement(arm==HumanoidArm.LEFT,ancestor);
        poses.pushPose();
        try {
            cn.minerealms.rigworks.util.PoseTransforms.multiply(poses,placement);
            RENDERER.renderSelected(scene,view.animation(),view.snapshot(),selection.draw(),poses,buffers,light,
                    OverlayTexture.NO_OVERLAY,Minecraft.getInstance().getFrameTime());
            if(arm==HumanoidArm.LEFT){leftDraws++;leftTriangles=selection.triangles();}
            else{rightDraws++;rightTriangles=selection.triangles();}
        } finally {poses.popPose();}
        return true;
    }

    /** Source FP meshes retain their authored arm pivots; the imported scene has flipped Z. */
    static Matrix4f ysmPlacement(boolean left,org.joml.Matrix4fc ancestor) {
        var placement=new Matrix4f().translation(left?.25f:-.25f,1.8f,0).scale(-1f/16,-1f/16,-1f/16);
        return ancestor==null?placement:placement.mul(new Matrix4f(ancestor).invert());
    }

    private static AssimpNode parent(AssimpNode node,AssimpNode target) {
        for(var child:node.getChildren()) {
            if(child==target)return node;
            var found=parent(child,target);if(found!=null)return found;
        }
        return null;
    }

    private static Selection selection(AssimpScene scene,CharacterProfile profile,HumanoidArm arm) {
        String role=firstPersonRole(profile,arm);
        var bone=profile.bone(role);var names=new HashSet<String>();
        if(bone!=null){var root=scene.findNodeByName(bone.node());if(root!=null)descendants(root,names);}
        return selection(scene,names);
    }
    private static Selection selection(AssimpScene scene,Set<String> names) {
        var selected=new IdentityHashMap<AssimpMesh,int[]>();boolean empty=true;int count=0;
        for(var mesh:scene.getMeshes()) {
            int[] triangles=selectTriangles(mesh,names);
            if(triangles.length>0){selected.put(mesh,triangles);empty=false;count+=triangles.length;}
        }
        return new Selection(new MeshDrawSelection(selected),empty,count);
    }
    static String firstPersonRole(CharacterProfile profile,HumanoidArm arm) {
        String side=arm==HumanoidArm.LEFT?"left_":"right_";
        String forearm=side+"forearm";
        return profile.bone(forearm)!=null?forearm:side+"upper_arm";
    }
    private static void descendants(AssimpNode node,Set<String> names){names.add(node.getName());node.getChildren().forEach(child->descendants(child,names));}

    /** All three corners must primarily belong to the selected arm; this excludes torso and opposite limbs. */
    public static int[] selectTriangles(AssimpMesh mesh,Set<String> names) {
        var data=mesh.getSkinnedData();if(data==null||names.isEmpty())return new int[0];
        int vertices=mesh.getVertexCount();if(vertices==0)return new int[0];
        var bones=mesh.getBoneNames();int[] indices=data.getBoneIndices();float[] weights=data.getBoneWeights();
        int stride=weights.length/vertices;boolean[] include=new boolean[vertices];
        for(int vertex=0;vertex<vertices;vertex++) {
            float weight=0;
            for(int slot=0;slot<stride;slot++) {
                int index=indices[vertex*stride+slot];
                if(index>=0&&index<bones.size()&&names.contains(bones.get(index)))weight+=weights[vertex*stride+slot];
            }
            include[vertex]=weight>=.5f;
        }
        int[] source=mesh.getIndices(),out=new int[source.length/3];int count=0;
        for(int triangle=0;triangle<out.length;triangle++) {
            int a=source[triangle*3],b=source[triangle*3+1],c=source[triangle*3+2];
            if(a>=0&&b>=0&&c>=0&&a<vertices&&b<vertices&&c<vertices&&include[a]&&include[b]&&include[c])out[count++]=triangle;
        }
        return java.util.Arrays.copyOf(out,count);
    }

    public static void clear() {
        if(!RenderSystem.isOnRenderThread()){RenderSystem.recordRenderCall(CharacterFirstPersonArms::clear);return;}
        closeSelections(CACHE);closeSelections(YSM_ARMS);
        armScene=neutralScene=dedicatedScene=null;armProfile=neutralProfile=null;neutralPose=null;
    }
    private static void closeSelections(EnumMap<HumanoidArm,Selection> selections) {
        selections.values().forEach(value->value.draw().close());selections.clear();
    }
    public static Map<String,Object> diagnostics() {
        return Map.of("leftDraws",leftDraws,"rightDraws",rightDraws,"leftTriangles",leftTriangles,"rightTriangles",rightTriangles);
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event){clear();}
}
