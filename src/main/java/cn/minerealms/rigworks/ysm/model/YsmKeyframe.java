/*
 * Originally derived from:
 * Source: https://github.com/YesSteveModel/YesSteveModel (Apache-2.0)
 * Licensed under the Apache License, Version 2.0.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 * Modified by MineRealms for RigWorks.
 * Changes: retain typed pre/post values and the original easing enum.
 */
package cn.minerealms.rigworks.ysm.model;

import java.util.ArrayList;
import java.util.List;

public final class YsmKeyframe {
    private final float time;
    private final List<YsmTypedValue> preValues;
    private final List<YsmTypedValue> postValues;
    private final String interpolation;
    private final int interpolationMode;
    private final boolean hasPreValue;

    public YsmKeyframe(float time, List<YsmTypedValue> preValues, List<YsmTypedValue> postValues,
                       String interpolation, int interpolationMode, boolean hasPreValue) {
        this.time = time;
        this.preValues = List.copyOf(preValues);
        this.postValues = List.copyOf(postValues);
        this.interpolation = interpolation == null ? "linear" : interpolation;
        this.interpolationMode = interpolationMode;
        this.hasPreValue = hasPreValue;
        if (this.postValues.size() != 3 || hasPreValue && this.preValues.size() != 3)
            throw new IllegalArgumentException("YSM keyframes must retain three-component vectors");
    }

    /** Compatibility constructor for callers creating numeric keyframes. */
    public YsmKeyframe(float time, float[] value, String interpolation) {
        this(time, List.of(), numericValues(value), interpolation, -1, false);
    }

    public float time() { return time; }
    public String interpolation() { return interpolation; }
    public int interpolationMode() { return interpolationMode; }
    public boolean hasPreValue() { return hasPreValue; }
    public List<YsmTypedValue> preValues() { return preValues; }
    public List<YsmTypedValue> postValues() { return postValues; }

    /** Numeric view for legacy consumers; expressions fail explicitly instead of becoming zero. */
    public float[] value() {
        return numericValues(postValues);
    }

    public float[] preValue() {
        if (!hasPreValue) return null;
        return numericValues(preValues);
    }

    private static List<YsmTypedValue> numericValues(float[] values) {
        if (values == null || values.length != 3)
            throw new IllegalArgumentException("YSM keyframe numeric value must have three components");
        List<YsmTypedValue> result = new ArrayList<>(3);
        for (float value : values) result.add(YsmTypedValue.ofFloat(value));
        return List.copyOf(result);
    }

    private static float[] numericValues(List<YsmTypedValue> values) {
        float[] result = new float[values.size()];
        for (int i = 0; i < values.size(); i++) result[i] = values.get(i).floatValue();
        return result;
    }
}
