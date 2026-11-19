package cn.minerealms.rigworks.mixin.character.kubejs;

import cn.minerealms.rigworks.character.client.CharacterScriptRuntime;
import dev.latvian.mods.kubejs.script.ScriptType;
//? if forge {
import dev.latvian.mods.kubejs.util.ConsoleJS;
//?}
//? if neoforge {
/*
import dev.latvian.mods.kubejs.script.ConsoleJS;
import dev.latvian.mods.kubejs.script.SourceLine;
*///?}
import dev.latvian.mods.kubejs.util.LogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Track errors independently of ConsoleJS's optional capturingErrors collection. */
@Pseudo
//? if forge {
@Mixin(targets="dev.latvian.mods.kubejs.util.ConsoleJS",remap=false)
//?}
//? if neoforge {
/*
@Mixin(targets="dev.latvian.mods.kubejs.script.ConsoleJS",remap=false)
*///?}
public abstract class KubeJSConsoleMixin {
    //? if forge {
    @Inject(method="log(Ldev/latvian/mods/kubejs/util/LogType;Ljava/lang/Throwable;Ljava/lang/Object;)Ldev/latvian/mods/kubejs/script/ConsoleLine;",at=@At("HEAD"),remap=false)
    private void rigworks$recordReloadError(LogType type,Throwable error,Object message,CallbackInfoReturnable<?> cir){
        if(type==LogType.ERROR&&((ConsoleJS)(Object)this).scriptType==ScriptType.CLIENT)
            CharacterScriptRuntime.recordReloadError("KubeJS client scripts emitted ERROR during reload; see client.log");
    }
    //?}
    //? if neoforge {
    /*
    @Inject(method="log(Ldev/latvian/mods/kubejs/util/LogType;Ldev/latvian/mods/kubejs/script/SourceLine;Ljava/lang/Throwable;Ljava/lang/Object;)Ldev/latvian/mods/kubejs/script/ConsoleLine;",at=@At("HEAD"),remap=false)
    private void rigworks$recordReloadError(LogType type,SourceLine source,Throwable error,Object message,CallbackInfoReturnable<?> cir){
        if(type==LogType.ERROR&&((ConsoleJS)(Object)this).scriptType==ScriptType.CLIENT)
            CharacterScriptRuntime.recordReloadError("KubeJS client scripts emitted ERROR during reload; see client.log");
    }
    *///?}

    //? if forge {
    @Inject(method="error(Ljava/lang/String;Ljava/lang/Throwable;Ljava/util/regex/Pattern;)Ldev/latvian/mods/kubejs/script/ConsoleLine;",at=@At("HEAD"),remap=false)
    //?}
    //? if neoforge {
    /*
    @Inject(method="error(Ljava/lang/String;Ldev/latvian/mods/kubejs/script/SourceLine;Ljava/lang/Throwable;Ljava/util/regex/Pattern;)Ldev/latvian/mods/kubejs/script/ConsoleLine;",at=@At("HEAD"),remap=false)
    *///?}
    private void rigworks$recordMutedException(CallbackInfoReturnable<?> cir){
        if(((ConsoleJS)(Object)this).scriptType==ScriptType.CLIENT)
            CharacterScriptRuntime.recordReloadError("KubeJS client script exception during reload; see client.log (console may be muted)");
    }
}
