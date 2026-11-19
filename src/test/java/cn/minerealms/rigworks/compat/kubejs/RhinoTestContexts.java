package cn.minerealms.rigworks.compat.kubejs;

import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.Scriptable;
//? if neoforge {
/*
import dev.latvian.mods.rhino.ContextFactory;
*///?}

/** Creates independent contexts for retained callback tests on each pinned Rhino API. */
public final class RhinoTestContexts {
    private RhinoTestContexts(){}

    public static Context create(){
        //? if forge {
        return Context.enter();
        //?}
        //? if neoforge {
        /*
        return new ContextFactory().enter();
        *///?}
    }

    public static Object wrap(Context context,Object value,Scriptable scope){
        //? if forge {
        return Context.javaToJS(context,value,scope);
        //?}
        //? if neoforge {
        /*
        return context.javaToJS(value,scope);
        *///?}
    }
}
