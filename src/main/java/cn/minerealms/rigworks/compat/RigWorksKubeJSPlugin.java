package cn.minerealms.rigworks.compat;

import cn.minerealms.rigworks.compat.kubejs.CharacterScriptApi;
import cn.minerealms.rigworks.character.client.CharacterScriptRuntime;
//? if forge {
import dev.latvian.mods.kubejs.KubeJSPlugin;
import dev.latvian.mods.kubejs.script.BindingsEvent;
//?}
//? if neoforge {
/*
import dev.latvian.mods.kubejs.plugin.KubeJSPlugin;
import dev.latvian.mods.kubejs.script.BindingRegistry;
*///?}
import dev.latvian.mods.kubejs.script.ScriptType;

/** The player scripting binding lives in this optional mod, independently of the engine API. */
//? if forge {
public final class RigWorksKubeJSPlugin extends KubeJSPlugin {
    @Override public void registerBindings(BindingsEvent event) {
        if (event.getType() == ScriptType.CLIENT) {
            CharacterScriptRuntime.prepareClientBindings();
            event.add("RigWorks", CharacterScriptApi.INSTANCE);
        }
    }
}
//?}
//? if neoforge {
/*
public final class RigWorksKubeJSPlugin implements KubeJSPlugin {
    @Override public void registerBindings(BindingRegistry registry) {
        if (registry.type() == ScriptType.CLIENT) {
            // 2101 binds each Rhino context, including later callback contexts.
            // The ScriptManager reload transaction owns registry replacement.
            registry.add("RigWorks", CharacterScriptApi.INSTANCE);
        }
    }
}
*///?}
