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

public record Vec3(float x, float y, float z) {
    public static final Vec3 ZERO = new Vec3(0f, 0f, 0f);

    public static Vec3 of(float[] values) {
        return new Vec3(values[0], values[1], values[2]);
    }

    public float[] asArray() {
        return new float[]{x, y, z};
    }
}
