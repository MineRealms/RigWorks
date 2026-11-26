package cn.minerealms.rigworks.character.importing;

import cn.minerealms.assimploader.model.AssimpScene;
import cn.minerealms.rigworks.character.client.MmdAvatarInstaller;

import java.io.IOException;
import java.util.List;

/** PMD/PMX sources become ordinary RigWorks assets before application. */
public final class MmdAvatarImportHandler implements AvatarImportHandler {
    @Override public String id() { return "mmd"; }
    @Override public List<String> extensions() { return List.of("pmx", "pmd"); }
    @Override public AssimpScene load(String name, byte[] bytes) throws IOException {
        return load(new AvatarImportContext(name, bytes, null));
    }
    @Override public AssimpScene load(AvatarImportContext source) throws IOException { return MmdAvatarInstaller.prepare(source); }
    @Override public void apply(String name, byte[] bytes, AssimpScene scene) throws IOException { MmdAvatarInstaller.install(name, scene); }
}
