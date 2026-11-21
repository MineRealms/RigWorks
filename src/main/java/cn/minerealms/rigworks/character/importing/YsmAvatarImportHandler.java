package cn.minerealms.rigworks.character.importing;

import cn.minerealms.assimploader.model.AssimpScene;
import cn.minerealms.rigworks.ysm.runtime.YsmAvatarInstaller;
import cn.minerealms.rigworks.ysm.runtime.YsmRuntimeBridge;

import java.io.IOException;
import java.util.List;

/** All YSM-specific format conversion is owned and packaged by RigWorks. */
public final class YsmAvatarImportHandler implements AvatarImportHandler {
    @Override public String id() { return "ysm"; }
    @Override public List<String> extensions() { return List.of("ysm"); }
    @Override public AssimpScene load(String name, byte[] bytes) throws IOException {
        return YsmAvatarInstaller.loadScene(name, bytes);
    }
    @Override public void apply(String name, byte[] bytes, AssimpScene scene) throws IOException {
        YsmRuntimeBridge.installed(YsmAvatarInstaller.installPrepared(name, scene));
    }
}
