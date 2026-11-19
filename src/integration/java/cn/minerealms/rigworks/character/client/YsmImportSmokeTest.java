package cn.minerealms.rigworks.character.client;

import cn.minerealms.assimploader.AssimpLoader;
import cn.minerealms.assimploader.model.AssimpScene;
import cn.minerealms.rigworks.RigWorks;
import cn.minerealms.rigworks.character.importing.AvatarImportRegistry;
import cn.minerealms.rigworks.character.importing.YsmApi;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Opt-in, headless import probe for a real client launch. Reads a .ysm path from the
 * {@code rigworks.ysm.asset} system property and drives the format-agnostic
 * {@link AvatarImportRegistry} load/apply chain on the client thread, without opening the file
 * picker. Only client-visual state is touched; no server entity or saved-data API is called.
 */
@Mod.EventBusSubscriber(modid=RigWorks.MODID,value=Dist.CLIENT)
public final class YsmImportSmokeTest {
    private static final boolean ENABLED=Boolean.getBoolean("rigworks.ysm.enabled");
    private static final long START=System.currentTimeMillis();
    private static final Map<String,Object> RESULT=new LinkedHashMap<>();
    private static boolean done,opening;
    private YsmImportSmokeTest(){}

    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if(!ENABLED||done||event.phase!=TickEvent.Phase.END)return;
        var mc=Minecraft.getInstance();
        // ClientTickEvent is delivered on the render thread; load/apply stay on that thread.
        try {
            require(System.currentTimeMillis()-START<600_000,"YSM import smoke timed out");
            if(mc.player==null||mc.level==null) {
                String world=System.getProperty("rigworks.ysm.world");
                if(!opening&&world!=null&&!world.isBlank()&&mc.screen instanceof TitleScreen&&mc.getOverlay()==null) {
                    opening=true;SmokeCompat.openWorld(mc,world);
                }
                return;
            }
            if(mc.getOverlay()!=null||mc.screen!=null)return;
            runOnce(mc);
        }catch(Throwable error){finish(mc,error);}
    }

    private static void runOnce(Minecraft mc)throws Exception {
        String raw=System.getProperty("rigworks.ysm.asset");
        require(raw!=null&&!raw.isBlank(),"rigworks.ysm.asset is not set");
        Path asset=Path.of(raw);
        require(Files.isRegularFile(asset),"YSM asset is missing: "+asset);
        byte[] bytes=Files.readAllBytes(asset);
        RESULT.put("asset",asset.toString());
        RESULT.put("assetBytes",bytes.length);
        RESULT.put("bridgeAvailable",YsmApi.bridge().available());
        RESULT.put("detectedAsYsm",YsmApi.bridge().isYsm(bytes));
        String name=asset.getFileName().toString();
        AssimpScene scene=AvatarImportRegistry.get().load(name,bytes);
        require(scene!=null,"YSM load produced no scene");
        RESULT.put("meshCount",scene.getMeshes().size());
        RESULT.put("animationCount",scene.getAnimations().size());
        RESULT.put("boneCount",boneCount(scene));
        AvatarImportRegistry.get().apply(name,bytes,scene);
        RESULT.put("applied",true);
        finish(mc,null);
    }

    private static int boneCount(AssimpScene scene) {
        Set<String> bones=new LinkedHashSet<>();
        for(var mesh:scene.getMeshes())if(mesh.hasBones())bones.addAll(mesh.getBoneNames());
        return bones.size();
    }

    private static void finish(Minecraft mc,Throwable error) {
        done=true;
        RESULT.put("success",error==null);
        RESULT.put("error",error==null?"":error.toString());
        RESULT.put("clientLeftOpen",true);
        Path result=mc.gameDirectory.toPath().resolve("rigworks-ysm-smoke-result.json");
        try{Files.writeString(result,new GsonBuilder().setPrettyPrinting().create().toJson(RESULT));}
        catch(Exception writeError){AssimpLoader.LOGGER.error("Cannot write YSM import smoke result",writeError);}
        if(error!=null)AssimpLoader.LOGGER.error("[YsmImportSmoke] FAILED",error);
        else AssimpLoader.LOGGER.info("[YsmImportSmoke] PASS; client remains open");
    }

    private static void require(boolean value,String message){if(!value)throw new IllegalStateException(message);}
}
