package cn.minerealms.rigworks.mixin.character.kubejs;

import net.minecraftforge.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Contains no KubeJS class references, so optional targets are never resolved without the dependency. */
public final class KubeJSReloadMixinPlugin implements IMixinConfigPlugin {
    @Override public void onLoad(String mixinPackage){}
    @Override public String getRefMapperConfig(){return null;}
    @Override public boolean shouldApplyMixin(String target,String mixin){
        var mods=LoadingModList.get();var kube=mods==null?null:mods.getModFileById("kubejs");
        //? if forge {
        String inspectedVersion="2001.6.5-build.26";
        //?}
        //? if neoforge {
        /*
        String inspectedVersion="2101.7.2-build.381";
        *///?}
        return kube!=null&&kube.getMods().stream().anyMatch(mod->mod.getModId().equals("kubejs")&&mod.getVersion().toString().equals(inspectedVersion));
    }
    @Override public void acceptTargets(Set<String> targets,Set<String> otherTargets){}
    @Override public List<String> getMixins(){return null;}
    @Override public void preApply(String target,ClassNode node,String mixin,IMixinInfo info){}
    @Override public void postApply(String target,ClassNode node,String mixin,IMixinInfo info){}
}
