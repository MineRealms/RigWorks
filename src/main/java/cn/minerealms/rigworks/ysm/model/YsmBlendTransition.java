/*
 * Originally derived from:
 * Source: https://github.com/YesSteveModel/YesSteveModel (Apache-2.0)
 * Licensed under the Apache License, Version 2.0.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 * Modified by MineRealms for RigWorks.
 * Changes: retain either the linear blend duration or source curve points.
 */
package cn.minerealms.rigworks.ysm.model;

import java.util.List;

public record YsmBlendTransition(Float linearLength, List<YsmBlendPoint> points) {
    public YsmBlendTransition {
        points = List.copyOf(points);
    }
}
