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

public record YsmFace(float[] positions, float[] normal, int[] uv, String texture,
                      float[] u, float[] v) {
    public YsmFace(int[] uv, String texture) {
        this(new float[12], new float[3], uv, texture, null, null);
    }

    public YsmFace(float[] positions, float[] normal, int[] uv, String texture) {
        this(positions, normal, uv, texture, null, null);
    }

    public static YsmFace of(int[] uv, String texture) {
        return new YsmFace(uv, texture);
    }

    public int[] uvCoords() {
        return uv;
    }
}
