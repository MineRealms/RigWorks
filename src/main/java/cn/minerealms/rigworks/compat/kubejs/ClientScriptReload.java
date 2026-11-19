package cn.minerealms.rigworks.compat.kubejs;

import cn.minerealms.rigworks.character.client.CharacterScriptRuntime;
import dev.latvian.mods.kubejs.script.ScriptManager;
import dev.latvian.mods.kubejs.script.ScriptType;
//? if forge {
import net.minecraft.server.packs.resources.ResourceManager;
//?}

/** Only the RigWorks registration registry is transactional; other script side effects remain KubeJS-owned. */
public final class ClientScriptReload {
    private ClientScriptReload() {}

    //? if forge {
    public static void reload(ScriptManager manager, ResourceManager resources) {
        reload(manager, () -> manager.reload(resources));
    }
    //?}
    //? if neoforge {
    /*
    public static void reload(ScriptManager manager) {
        reload(manager, manager::reload);
    }
    *///?}

    private static void reload(ScriptManager manager, Runnable action) {
        if(manager.scriptType!=ScriptType.CLIENT){action.run();return;}
        try(var registration=CharacterScriptRuntime.beginRegistrationReload()) {
            action.run();
            registration.complete();
        }
    }
}
