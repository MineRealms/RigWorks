package cn.minerealms.rigworks.character.client;

import cn.minerealms.assimploader.animation.AnimationState;
import cn.minerealms.assimploader.compat.kubejs.AssimpModelHandle;
import cn.minerealms.assimploader.core.AssimpModelLoader;
import cn.minerealms.assimploader.core.ModelImportMode;
import cn.minerealms.rigworks.RigWorks;
import com.google.gson.Gson;
import dev.latvian.mods.kubejs.KubeJSPlugin;
import dev.latvian.mods.kubejs.script.BindingsEvent;
import dev.latvian.mods.kubejs.script.ScriptType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/** Player-only Rhino acceptance. Never packaged or enabled in a normal client. */
@Mod.EventBusSubscriber(modid=RigWorks.MODID,value=Dist.CLIENT)
public final class RigWorksKubeJSSmokeTest {
    private static final Map<String,Object> CHECKS=new LinkedHashMap<>();
    private static String failure;
    private static boolean passed,done;
    private static long titleAt;
    private RigWorksKubeJSSmokeTest(){}

    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if(!Boolean.getBoolean("rigworks.kubejsSmoke")||done||event.phase!=TickEvent.Phase.END)return;
        var mc=Minecraft.getInstance();
        if(!(mc.screen instanceof TitleScreen))return;
        if(titleAt==0)titleAt=System.currentTimeMillis();
        if(!passed&&failure==null&&System.currentTimeMillis()-titleAt<15_000)return;
        done=true;
        try {
            var result=new LinkedHashMap<String,Object>();
            result.put("success",passed);result.put("failure",failure==null?(passed?"":"acceptance script did not execute"):failure);
            result.put("checks",CHECKS);
            Files.writeString(mc.gameDirectory.toPath().resolve("rigworks-kubejs-smoke-result.json"),new Gson().toJson(result));
        }catch(Exception error){throw new IllegalStateException("Cannot save RigWorks Rhino acceptance",error);}
        mc.stop();
    }

    public static final class Plugin extends KubeJSPlugin {
        @Override public void registerBindings(BindingsEvent event) {
            if(event.getType()==ScriptType.CLIENT&&Boolean.getBoolean("rigworks.kubejsSmoke"))event.add("RigWorksAcceptance",new ProbeApi());
        }
    }

    public static final class ProbeApi {
        public void onClient(Runnable callback) {
            Minecraft.getInstance().execute(()->{
                try{callback.run();}catch(Throwable error){failure=error.toString();}
            });
        }
        public Probe create() throws Exception {
            var id=new ResourceLocation("rigworks","models/character/block_player/block_player.fbx");
            try(var input=Minecraft.getInstance().getResourceManager().open(id)) {
                var scene=new AssimpModelLoader().loadModelFromMemory(id.toString(),input.readAllBytes(),ModelImportMode.DYNAMIC);
                if(scene==null)throw new IllegalStateException("Block-player fixture did not import");
                var state=new AnimationState("rigworks:acceptance",scene);state.initialize();return new Probe(state);
            }
        }
        public void success() {
            CHECKS.put("clientReload",KubeJSReloadRuntimeProbe.verify());
            passed=true;
        }
        public void failure(String message){failure=message;}
    }

    public static final class Probe implements AutoCloseable {
        private final AnimationState state;
        Probe(AnimationState state){this.state=state;}
        public AssimpModelHandle handle(){return new AssimpModelHandle(state);}
        @Override public void close(){state.close();}
    }
}
