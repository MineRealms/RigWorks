/*
 * Originally derived from:
 * Source: https://github.com/YesSteveModel/YesSteveModel (Apache-2.0)
 * Licensed under the Apache License, Version 2.0.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 * Modified by MineRealms for RigWorks.
 * Changes: preserve source timeline instruction groups.
 */
package cn.minerealms.rigworks.ysm.model;

import java.util.List;

public record YsmInstructionKeyframe(float time, List<String> instructions) {
    public YsmInstructionKeyframe {
        instructions = List.copyOf(instructions);
    }
}
