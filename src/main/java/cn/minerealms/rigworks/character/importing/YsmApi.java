package cn.minerealms.rigworks.character.importing;

import cn.minerealms.assimploader.model.AssimpScene;

import java.io.IOException;

/**
 * Typed boundary around AssimpLoader's YSM API. Handlers and UI only ever talk to {@link Bridge};
 * the active implementation is chosen in one place, so a {@code DirectBridge} can replace the
 * reflective one after the API is merged into RigWorks' AssimpLoader submodule without touching
 * any handler or screen.
 */
public final class YsmApi {
    public interface Bridge {
        boolean available();

        boolean isYsm(byte[] bytes);

        AssimpScene load(String name, byte[] bytes) throws IOException;

        byte[] toGlb(byte[] bytes) throws IOException;
    }

    private static final Bridge BRIDGE = new ReflectiveBridge();

    private YsmApi() {}

    public static Bridge bridge() {
        return BRIDGE;
    }
}
