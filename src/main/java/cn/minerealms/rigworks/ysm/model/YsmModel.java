/*
 * Originally derived from:
 * Source: https://github.com/YesSteveModel/YesSteveModel (Apache-2.0)
 *        and https://github.com/stevewongv/YesSteveModel (Apache-2.0)
 * Licensed under the Apache License, Version 2.0.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 * Modified by MineRealms for RigWorks.
 * Changes: cleanroom reimplementation / package adaptation for YSM decoding in RigWorks.
 */
package cn.minerealms.rigworks.ysm.model;

import java.util.List;
import java.util.Map;

public record YsmModel(int format, String modelId, List<YsmBone> bones, Map<String, YsmTexture> textures,
                       Map<String, YsmAnimation> animations, List<YsmController> controllers,
                       List<YsmAnimation> animationClips) {
    public YsmModel {
        animationClips = List.copyOf(animationClips == null ? animations.values() : animationClips);
    }

    public YsmModel(int format, String modelId, List<YsmBone> bones, Map<String, YsmTexture> textures,
                    Map<String, YsmAnimation> animations, List<YsmController> controllers) {
        this(format, modelId, bones, textures, animations, controllers, List.copyOf(animations.values()));
    }
}
