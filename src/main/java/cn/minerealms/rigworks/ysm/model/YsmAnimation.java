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

public record YsmAnimation(String name, float duration, boolean loop, List<YsmChannel> channels,
                           int loopMode, List<YsmTypedValue> blendWeight,
                           List<YsmInstructionKeyframe> instructionKeyframes,
                           List<YsmSoundKeyframe> soundKeyframes, List<Integer> formatFlags,
                           String category, int categoryId) {
    public YsmAnimation {
        channels = List.copyOf(channels);
        blendWeight = List.copyOf(blendWeight);
        instructionKeyframes = List.copyOf(instructionKeyframes);
        soundKeyframes = List.copyOf(soundKeyframes);
        formatFlags = List.copyOf(formatFlags);
        category = category == null ? "unknown" : category;
    }

    public YsmAnimation(String name, float duration, boolean loop, List<YsmChannel> channels,
                        int loopMode, List<YsmTypedValue> blendWeight,
                        List<YsmInstructionKeyframe> instructionKeyframes,
                        List<YsmSoundKeyframe> soundKeyframes, List<Integer> formatFlags) {
        this(name, duration, loop, channels, loopMode, blendWeight, instructionKeyframes,
                soundKeyframes, formatFlags, "unknown", 0);
    }

    public YsmAnimation(String name, float duration, boolean loop, List<YsmChannel> channels) {
        this(name, duration, loop, channels, loop ? 1 : 2, List.of(), List.of(), List.of(), List.of(), "unknown", 0);
    }
}
