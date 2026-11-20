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
import java.util.Collections;
import java.util.LinkedHashMap;

public record YsmState(Map<String, String> animations, List<YsmTransition> transitions,
                       List<YsmAnimationReference> animationReferences, List<String> onEntry,
                       List<String> onExit, YsmBlendTransition blendTransition,
                       boolean blendViaShortestPath, List<String> soundEffects) {
    public YsmState {
        animations = Collections.unmodifiableMap(new LinkedHashMap<>(animations));
        transitions = List.copyOf(transitions);
        animationReferences = List.copyOf(animationReferences);
        onEntry = List.copyOf(onEntry);
        onExit = List.copyOf(onExit);
        soundEffects = List.copyOf(soundEffects);
    }

    public YsmState(Map<String, String> animations, List<YsmTransition> transitions) {
        this(animations, transitions, List.of(), List.of(), List.of(), null, false, List.of());
    }
}
