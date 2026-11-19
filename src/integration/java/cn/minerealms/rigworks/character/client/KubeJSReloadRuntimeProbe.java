package cn.minerealms.rigworks.character.client;

import cn.minerealms.rigworks.compat.kubejs.CharacterActionFrame;
import dev.latvian.mods.kubejs.KubeJS;
import dev.latvian.mods.kubejs.client.KubeJSClient;
import dev.latvian.mods.kubejs.script.ScriptFile;
import dev.latvian.mods.kubejs.script.ScriptFileInfo;
import dev.latvian.mods.kubejs.script.ScriptManager;
import dev.latvian.mods.kubejs.script.ScriptPack;
import dev.latvian.mods.kubejs.script.ScriptPackInfo;
//? if forge {
import dev.latvian.mods.kubejs.script.ScriptSource;
//?}
//? if neoforge {
/*
import dev.latvian.mods.kubejs.script.KubeJSContext;
*///?}
import dev.latvian.mods.kubejs.script.ScriptType;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.Scriptable;
import dev.latvian.mods.rhino.ScriptableObject;
import net.minecraft.client.Minecraft;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Explicit dev acceptance only: exercises real manager reloads and console mixins with isolated fixtures. */
public final class KubeJSReloadRuntimeProbe {
    private KubeJSReloadRuntimeProbe(){}
    public static final class Counter {
        private int value;
        public void set(int value){this.value=value;}
        public int get(){return value;}
    }

    /** Run only in a disposable KubeJS acceptance client: KubeJS unload clears its global CLIENT handlers. */
    public static Map<String,Object> verify(){
        if(!Minecraft.getInstance().isSameThread()||!Boolean.getBoolean("rigworks.kubejsSmoke"))
            throw new IllegalStateException("Reload probe requires the explicit KubeJS acceptance client");
        var saved=new LinkedHashMap<String,Consumer<CharacterActionFrame>>();
        CharacterScriptRuntime.controllers().forEach(id->saved.put(id,CharacterScriptRuntime.publishedController(id)));
        String selected=CharacterScriptRuntime.active();
        boolean muted=ScriptType.CLIENT.console.getMuted();
        var manager=new FixtureManager();
        var previousManager=swapManager(manager);
        try {
            manager.sources=List.of("var retained = 40; RigWorks.register('reload:good',function(frame){retained++; reloadCounter.set(retained);});");
            KubeJSClient.reloadClientScripts();
            var counter=new Counter();
            manager.bind("reloadCounter",counter);
            var callback=CharacterScriptRuntime.publishedController("reload:good");
            require(callback!=null,"Successful first load did not publish");
            callback.accept(null);require(counter.get()==41,"Initial Rhino closure did not execute");
            long revision=CharacterScriptRuntime.revision();
            var context=manager.rhinoContext();var scope=manager.scope();

            manager.sources=List.of("RigWorks.register('reload:partial',function(frame){});","function (");
            ScriptType.CLIENT.console.setMuted(true);
            KubeJSClient.reloadClientScripts();
            ScriptType.CLIENT.console.setMuted(muted);
            require(context!=manager.rhinoContext()&&scope!=manager.scope(),"Manager did not replace context and scope");
            require(revision==CharacterScriptRuntime.revision(),"Failed reload advanced the live revision");
            require(callback==CharacterScriptRuntime.publishedController("reload:good"),"Failed reload replaced the old closure");
            require(CharacterScriptRuntime.publishedController("reload:partial")==null,"Failed reload published its first file");
            require(!CharacterScriptRuntime.reloadFailure().isEmpty(),"Console error mixin did not flag the swallowed failure");
            callback.accept(null);require(counter.get()==42,"Old closure did not execute after actual failed manager reload");

            manager.preloadFailure=true;manager.sources=List.of();
            KubeJSClient.reloadClientScripts();
            require(CharacterScriptRuntime.revision()==revision,"Preload failure cleared the registry");
            callback.accept(null);require(counter.get()==43,"Closure did not survive preload failure");
            manager.preloadFailure=false;
            KubeJSClient.reloadClientScripts();
            require(CharacterScriptRuntime.revision()==revision+1,"Successful zero-script reload did not commit once");
            require(CharacterScriptRuntime.controllers().isEmpty()&&CharacterScriptRuntime.active()==null,"Zero scripts retained stale controllers");
            return Map.of("success",true,"retainedClosureCalls",counter.get()-40,"failedReloadRevisionStable",true,
                    "preloadFailureRetained",true,"emptyReloadCleared",true,"contextReplaced",true,"mutedFailureRetained",true,
                    "standardClientEntry",true);
        } finally {
            swapManager(previousManager);
            ScriptType.CLIENT.console.setMuted(muted);
            CharacterScriptRuntime.clear();saved.forEach(CharacterScriptRuntime::register);
            if(selected!=null&&saved.containsKey(selected))CharacterScriptRuntime.use(selected);else CharacterScriptRuntime.useDefault();
        }
    }

    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}

    private static ScriptManager swapManager(ScriptManager replacement){
        try {
            var field=KubeJS.class.getDeclaredField("clientScriptManager");field.setAccessible(true);
            var previous=(ScriptManager)field.get(null);field.set(null,replacement);return previous;
        }catch(ReflectiveOperationException error){throw new IllegalStateException("Pinned KubeJS manager field changed",error);}
    }

    private static final class FixtureManager extends ScriptManager {
        List<String> sources=List.of();boolean preloadFailure;
        FixtureManager(){super(ScriptType.CLIENT);}
        //? if forge {
        @Override public void reload(net.minecraft.server.packs.resources.ResourceManager resources){super.reload(null);}
        //?}
        Context rhinoContext(){
            //? if forge {
            return context;
            //?}
            //? if neoforge {
            /*
            return contextFactory.enter();
            *///?}
        }
        Scriptable scope(){
            //? if forge {
            return topLevelScope;
            //?}
            //? if neoforge {
            /*
            return ((KubeJSContext)rhinoContext()).topLevelScope;
            *///?}
        }
        void bind(String name,Object value){
            var current=rhinoContext();var target=scope();
            //? if forge {
            var wrapped=Context.javaToJS(current,value,target);
            //?}
            //? if neoforge {
            /*
            var wrapped=current.javaToJS(value,target);
            *///?}
            ScriptableObject.putProperty(target,name,wrapped,current);
        }
        @Override public void loadFromDirectory(){
            if(preloadFailure){ScriptType.CLIENT.console.error("[RigWorksReloadProbe] Intentional preload failure");return;}
            if(sources.isEmpty())return;
            var info=new ScriptPackInfo("rigworks_reload_probe","client_scripts/");
            var pack=new ScriptPack(this,info);
            for(int index=0;index<sources.size();index++){
                //? if forge {
                var file=new ScriptFileInfo(info,"fixture_"+index+".js");
                String text=sources.get(index);file.lines=text.split("\n",-1);
                ScriptSource source=ignored->Arrays.asList(text.split("\n",-1));
                pack.scripts.add(new ScriptFile(pack,file,source));
                //?}
                //? if neoforge {
                /*
                // 2101 removed ScriptSource; ScriptFile reads the fixture into its own lines array.
                java.nio.file.Path temporary=null;
                try {
                    temporary=java.nio.file.Files.createTempFile("rigworks_reload_probe_",".js");
                    java.nio.file.Files.writeString(temporary,sources.get(index));
                    var file=new ScriptFileInfo(info,temporary,"fixture_"+index+".js");
                    pack.scripts.add(new ScriptFile(pack,file));
                }catch(Exception error){throw new IllegalStateException("Cannot prepare KubeJS reload fixture",error);}
                finally {
                    if(temporary!=null)try{java.nio.file.Files.deleteIfExists(temporary);}
                    catch(java.io.IOException error){throw new IllegalStateException("Cannot remove KubeJS reload fixture",error);}
                }
                *///?}
            }
            packs.put(info.namespace,pack);
        }
    }
}
