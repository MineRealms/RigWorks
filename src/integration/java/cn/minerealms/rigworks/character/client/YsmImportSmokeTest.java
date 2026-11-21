package cn.minerealms.rigworks.character.client;

import cn.minerealms.assimploader.AssimpLoader;
import cn.minerealms.assimploader.animation.PoseSnapshot;
import cn.minerealms.assimploader.compat.OculusCompat;
import cn.minerealms.assimploader.model.AssimpScene;
import cn.minerealms.assimploader.rendering.OculusEntityBufferCache;
import cn.minerealms.rigworks.RigWorks;
import cn.minerealms.rigworks.character.importing.AvatarImportRegistry;
import cn.minerealms.rigworks.ysm.runtime.YsmPoseDriver;
import cn.minerealms.rigworks.ysm.runtime.YsmRuntimeBridge;
import com.google.gson.GsonBuilder;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Opt-in real-client acceptance: worker conversion, exact binding, changing pose, draws and capture. */
@Mod.EventBusSubscriber(modid=RigWorks.MODID,value=Dist.CLIENT)
public final class YsmImportSmokeTest {
    private static final boolean ENABLED=Boolean.getBoolean("rigworks.ysm.enabled");
    private static final long START=System.currentTimeMillis();
    private static final Map<String,Object> RESULT=new LinkedHashMap<>();
    private static boolean done,opening,loading,capturing,characterPagePending;
    private static int characterPageTicks;
    private static AssimpScene imported;
    private static String expectedProfile;
    private static long readyAt,firstPass,firstFrame,firstDraw;
    private static Map<String,float[]> baseline;
    private static float poseChange;
    private static String observedScreen="";
    private YsmImportSmokeTest(){}

    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if(!ENABLED||done||event.phase!=TickEvent.Phase.END)return;
        var mc=Minecraft.getInstance();
        try {
            mc.options.pauseOnLostFocus=false;
            String screen=mc.screen==null?"game":mc.screen.getClass().getSimpleName();
            if(!screen.equals(observedScreen)) {
                observedScreen=screen;
                AssimpLoader.LOGGER.info("[YsmImportSmoke] screen={}",screen);
            }
            require(System.currentTimeMillis()-START<600_000,"YSM acceptance timed out on "+screen);
            if(mc.getOverlay()==null&&mc.screen instanceof AccessibilityOnboardingScreen) {
                mc.options.onboardAccessibility=false;
                mc.setScreen(new TitleScreen());
                return;
            }
            if (characterPagePending) {
                if (!(mc.screen instanceof CharacterSelectionScreen)) throw new IllegalStateException("Character selector closed before capture");
                if (++characterPageTicks < 3) return;
                characterPagePending = false;
                captureCharacterPage(mc);
                return;
            }
            if(mc.player==null||mc.level==null) {
                String world=System.getProperty("rigworks.ysm.world");
                if(!opening&&world!=null&&!world.isBlank()&&mc.screen instanceof TitleScreen&&mc.getOverlay()==null) {
                    opening=true;SmokeCompat.openWorld(mc,world);
                }
                return;
            }
            if(mc.getOverlay()!=null||mc.screen!=null||capturing)return;
            if(!loading){load(mc);return;}
            if(imported==null)return;
            verify(mc);
        }catch(Throwable error){finish(mc,error);}
    }

    private static void load(Minecraft mc) {
        loading=true;
        String raw=System.getProperty("rigworks.ysm.asset");
        require(raw!=null&&!raw.isBlank(),"rigworks.ysm.asset is not set");
        Path asset=Path.of(raw);
        CompletableFuture.supplyAsync(()->{
            try {
                require(Files.isRegularFile(asset),"YSM asset is missing: "+asset);
                require(Files.size(asset)>0&&Files.size(asset)<=256L*1024*1024,"Invalid YSM source size");
                byte[] bytes;
                try(var input=Files.newInputStream(asset)){bytes=input.readNBytes(256*1024*1024+1);}
                require(bytes.length<=256*1024*1024,"YSM source grew beyond its limit");
                var scene=AvatarImportRegistry.get().load(asset.getFileName().toString(),bytes);
                require(scene!=null,"YSM import produced no scene");
                return new Loaded(asset.getFileName().toString(),bytes,scene);
            }catch(Exception error){throw new CompletionException(error);}
        }).whenComplete((loaded,error)->mc.execute(()->{
            if(error!=null){finish(mc,error);return;}
            try {
                AvatarImportRegistry.get().apply(loaded.name(),loaded.bytes(),loaded.scene());
                imported=loaded.scene();
                expectedProfile=LocalPlayerCharacter.settings().profile();
                RESULT.put("asset",asset.toString());
                RESULT.put("assetBytes",loaded.bytes().length);
                RESULT.put("profile",expectedProfile);
                RESULT.put("meshCount",imported.getMeshes().size());
                mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
                mc.options.hideGui=true;
            }catch(Throwable failure){finish(mc,failure);}
        }));
    }

    private static void verify(Minecraft mc) {
        var diagnostics=LocalPlayerCharacter.diagnostics();
        require(diagnostics.status()!=LocalPlayerCharacter.Status.FAILED,"Character failed: "+diagnostics.failure());
        var animation=LocalPlayerCharacter.currentAnimation();
        if(diagnostics.status()!=LocalPlayerCharacter.Status.READY||animation==null||animation.getScene()!=imported)return;
        require(expectedProfile.equals(diagnostics.profile()),"A previous profile was retained instead of this import");
        require(diagnostics.model().endsWith(".rwym"),"The character is not bound to its canonical RWYM asset");
        var visual=LocalPlayerCharacter.currentVisualConfig();
        require(visual!=null,"The imported character has no visual configuration");
        require(visual.profile().modelYawDegrees()==0f,"Imported YSM profile kept a workbench yaw instead of the render-cache orientation");
        require(visual.profile().headLook().forward().z()>0f,"Imported YSM profile does not face the engine forward axis");
        require(visual.equipment().rightHand()!=null&&visual.equipment().leftHand()!=null,
                "YSM hand locators were not mapped to vanilla held-item mounts");
        var backSocket=visual.equipment().socket("back");
        require(backSocket!=null,"YSM ElytraLocator was not mapped to the back equipment socket");
        RESULT.put("modelYawDegrees",visual.profile().modelYawDegrees());
        RESULT.put("headLookForward",java.util.List.of(visual.profile().headLook().forward().x(),visual.profile().headLook().forward().y(),visual.profile().headLook().forward().z()));
        RESULT.put("rightHandMount",visual.equipment().rightHand().node());
        RESULT.put("leftHandMount",visual.equipment().leftHand().node());
        RESULT.put("backSocket",backSocket.node());
        require(YsmRuntimeBridge.ownsScene(imported)&&YsmRuntimeBridge.active(),"YSM animation runtime did not bind");
        require(animation.getModifier("ysm:pose") instanceof YsmPoseDriver,"YSM authored pose modifier is missing");
        var driver=(YsmPoseDriver)animation.getModifier("ysm:pose");
        var pose=animation.getPoseSnapshot();
        require(!pose.copyMeshInstances().isEmpty(),"The renderer has no mesh instances");
        var current=matrices(pose);
        if(readyAt==0) {
            readyAt=System.currentTimeMillis();
            baseline=current;
            firstFrame=pose.getFrameId();
            firstPass=diagnostics.renderedPasses();
            firstDraw=OculusEntityBufferCache.poseStatistics().draws();
        } else {
            for(var entry:baseline.entrySet()) {
                float[] after=current.get(entry.getKey());
                if(after!=null)for(int i=0;i<16;i++)poseChange=Math.max(poseChange,Math.abs(entry.getValue()[i]-after[i]));
            }
        }
        if(System.currentTimeMillis()-readyAt<10_000)return;
        require(pose.getFrameId()>firstFrame&&poseChange>1e-5,"Authored animation did not change the skinning matrices");
        require(!driver.activeLayers().isEmpty(),"No authored YSM clips are active");
        require(diagnostics.renderedPasses()>firstPass,"The imported character was never rendered");
        if(OculusCompat.isShadersActive()) {
            require(!OculusEntityBufferCache.isPoseCacheFailed(),"Shader pose cache failed");
            require(OculusEntityBufferCache.poseStatistics().draws()>firstDraw,"No posed model draws reached the shader cache");
        }
        var bounds=pose.getBounds();
        require(bounds.getXsize()>0&&bounds.getYsize()>0&&bounds.getZsize()>0,"Empty posed model bounds");
        RESULT.put("model",diagnostics.model());
        RESULT.put("meshInstances",pose.copyMeshInstances().size());
        RESULT.put("skinnedBones",current.size());
        RESULT.put("poseFrames",java.util.List.of(firstFrame,pose.getFrameId()));
        RESULT.put("maxPaletteDifference",poseChange);
        RESULT.put("renderedPasses",diagnostics.renderedPasses()-firstPass);
        RESULT.put("activeLayers",driver.activeLayers());
        RESULT.put("bounds",java.util.List.of(bounds.minX,bounds.minY,bounds.minZ,bounds.maxX,bounds.maxY,bounds.maxZ));
        RESULT.put("shadersActive",OculusCompat.isShadersActive());
        RESULT.put("poseCache",OculusEntityBufferCache.poseStatistics());
        RESULT.put("fpsAtCapture",mc.getFps());
        RESULT.put("settleMillis",System.currentTimeMillis()-readyAt);
        String capture="rigworks-ysm-"+START+".png";
        RESULT.put("screenshot","screenshots/"+capture);
        capturing=true;
        Screenshot.grab(mc.gameDirectory,capture,mc.getMainRenderTarget(),ignored->mc.execute(()->{
            try {
                require(Files.isRegularFile(mc.gameDirectory.toPath().resolve("screenshots").resolve(capture)),"Screenshot was not saved");
                verifyCharacterPage(mc);
            }catch(Throwable error){finish(mc,error);}
        }));
    }

    @SuppressWarnings("unchecked")
    private static void verifyCharacterPage(Minecraft mc) throws Exception {
        CharacterVisualOverrides.rememberImportedYsmProfile(expectedProfile);
        mc.options.hideGui = false;
        var screen = new CharacterSelectionScreen(null);
        mc.setScreen(screen);
        EditBox profile = (EditBox) field(screen, "profile");
        List<ResourceLocation> profiles = (List<ResourceLocation>) field(screen, "profiles");
        Button edit = (Button) field(screen, "editYsm");
        require(expectedProfile.equals(profile.getValue()), "Character page did not select the imported YSM profile");
        require(profiles.contains(new ResourceLocation(expectedProfile)), "Imported YSM profile is missing from the saved profile catalogue");
        require(edit.active, "Edit YSM action is not available for the loaded YSM avatar");
        RESULT.put("characterPageProfileSelected", true);
        RESULT.put("characterPageProfileListed", true);
        RESULT.put("editYsmActionEnabled", true);
        characterPagePending = true;
    }

    private static void captureCharacterPage(Minecraft mc) {
        String capture = "rigworks-ysm-characters-" + START + ".png";
        RESULT.put("characterScreenshot", "screenshots/" + capture);
        capturing = true;
        Screenshot.grab(mc.gameDirectory, capture, mc.getMainRenderTarget(), ignored -> mc.execute(() -> {
            try {
                require(Files.isRegularFile(mc.gameDirectory.toPath().resolve("screenshots").resolve(capture)),
                        "Character selection screenshot was not saved");
                finish(mc, null);
            } catch (Throwable error) { finish(mc, error); }
        }));
    }

    private static Object field(Object value, String name) throws Exception {
        var field = value.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(value);
    }

    private static Map<String,float[]> matrices(PoseSnapshot pose) {
        Map<String,float[]> result=new LinkedHashMap<>();
        int meshIndex=0;
        for(var mesh:pose.getScene().getMeshes()) {
            var palette=pose.getBoneTransforms(mesh);
            require(palette!=null&&palette.length>0&&palette.length<=256,"Invalid rigid skin palette");
            for(int i=0;i<palette.length;i++) {
                require(palette[i].isFinite(),"Non-finite palette matrix");
                result.put(meshIndex+":"+i,palette[i].get(new float[16]));
            }
            meshIndex++;
        }
        return result;
    }

    private record Loaded(String name,byte[] bytes,AssimpScene scene){}

    private static void finish(Minecraft mc,Throwable error) {
        if(done)return;
        done=true;
        boolean keepOpen=Boolean.getBoolean("rigworks.ysm.keepOpen");
        RESULT.put("success",error==null);
        RESULT.put("error",error==null?"":error.toString());
        RESULT.put("clientLeftOpen",keepOpen);
        Path result=mc.gameDirectory.toPath().resolve("rigworks-ysm-smoke-result.json");
        try{Files.writeString(result,new GsonBuilder().setPrettyPrinting().create().toJson(RESULT));}
        catch(Exception writeError){AssimpLoader.LOGGER.error("Cannot write YSM acceptance result",writeError);}
        if(error!=null)AssimpLoader.LOGGER.error("[YsmImportSmoke] FAILED",error);
        else AssimpLoader.LOGGER.info("[YsmImportSmoke] PASS model={} poseChange={}",RESULT.get("model"),poseChange);
        if(!keepOpen)mc.stop();
    }

    private static void require(boolean value,String message){if(!value)throw new IllegalStateException(message);}
}
