package cn.minerealms.rigworks.character.importing;

import cn.minerealms.assimploader.model.AssimpScene;

import java.io.IOException;

/**
 * RigWorks-owned conversion boundary. Cached RWYM loading does not enter this native converter.
 */
public final class YsmApi {
    public interface Bridge {
        boolean available();

        boolean isYsm(byte[] bytes);

        AssimpScene load(String name, byte[] bytes) throws IOException;

        byte[] toGlb(byte[] bytes) throws IOException;
    }

    private static final Bridge BRIDGE = new Bridge() {
        @Override public boolean available() { return cn.minerealms.rigworks.ysm.asset.RwymNative.available(); }
        @Override public boolean isYsm(byte[] bytes) { return cn.minerealms.rigworks.ysm.YsmDecoder.isYsm(bytes); }
        @Override public AssimpScene load(String name, byte[] bytes) throws IOException {
            return cn.minerealms.rigworks.ysm.runtime.YsmAvatarInstaller.loadScene(name, bytes);
        }
        @Override public byte[] toGlb(byte[] bytes) throws IOException {
            byte[] asset = cn.minerealms.rigworks.ysm.asset.RwymNative.convert(bytes);
            return cn.minerealms.rigworks.ysm.model.YsmGlbWriter.toRuntimeGlb(
                    cn.minerealms.rigworks.ysm.asset.RwymReader.read(asset).mainModel());
        }
    };

    private YsmApi() {}

    public static Bridge bridge() {
        return BRIDGE;
    }
}
