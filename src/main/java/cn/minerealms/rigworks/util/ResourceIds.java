package cn.minerealms.rigworks.util;

import cn.minerealms.assimploader.util.ResourceLocations;
import net.minecraft.resources.ResourceLocation;

/** Shared construction for canonical ResourceLocation call sites after version processing. */
public final class ResourceIds {
    private ResourceIds() {}

    public static ResourceLocation create(String id) {
        return ResourceLocations.parse(id);
    }

    public static ResourceLocation create(String namespace, String path) {
        return ResourceLocations.of(namespace, path);
    }
}
