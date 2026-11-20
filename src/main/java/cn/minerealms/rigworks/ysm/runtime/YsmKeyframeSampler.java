/*
 * Originally derived from:
 * Source: https://github.com/YesSteveModel/YesSteveModel (Apache-2.0)
 * Licensed under the Apache License, Version 2.0.
 * Modified by MineRealms for RigWorks.
 */
package cn.minerealms.rigworks.ysm.runtime;

import cn.minerealms.rigworks.ysm.model.YsmChannel;
import cn.minerealms.rigworks.ysm.model.YsmKeyframe;
import cn.minerealms.rigworks.ysm.model.YsmTypedValue;

import java.util.List;

/** Samples YSM's pre/post keyframes. Input and output times are seconds. */
public final class YsmKeyframeSampler {
    private static final float DEGREES_TO_RADIANS = (float) (Math.PI / 180.0);

    private YsmKeyframeSampler() {}

    public static float[] sample(YsmChannel channel, double timeSeconds, YsmExpressionRuntime.Context context) {
        if (channel == null) throw new IllegalArgumentException("YSM channel is required");
        if (!Double.isFinite(timeSeconds)) throw new IllegalArgumentException("YSM sample time must be finite");
        List<YsmKeyframe> frames = channel.frames();
        if (frames == null || frames.isEmpty()) return new float[3];

        for (int i = 1; i < frames.size(); i++) {
            if (frames.get(i).time() < frames.get(i - 1).time())
                throw new IllegalArgumentException("YSM keyframes must be sorted by time");
        }

        float[] result;
        if (timeSeconds < frames.get(0).time() || frames.size() == 1) {
            result = values(timeSeconds < frames.get(0).time() ? pre(frames.get(0)) : post(frames.get(0)), context);
        } else {
            result = sampleSegment(frames, timeSeconds, context);
        }

        if ("rotation".equalsIgnoreCase(channel.path())) {
            result[0] = -result[0] * DEGREES_TO_RADIANS;
            result[1] = -result[1] * DEGREES_TO_RADIANS;
            result[2] = result[2] * DEGREES_TO_RADIANS;
        }
        return result;
    }

    private static float[] sampleSegment(List<YsmKeyframe> frames, double time, YsmExpressionRuntime.Context context) {
        for (int i = 0; i < frames.size() - 1; i++) {
            YsmKeyframe begin = frames.get(i);
            YsmKeyframe end = frames.get(i + 1);
            if (time >= end.time()) continue;

            float duration = end.time() - begin.time();
            if (duration <= 0) continue;
            float progress = (float) ((time - begin.time()) / duration);
            float[] p1 = values(post(begin), context);
            float[] p2 = values(pre(end), context);
            boolean catmullRom = end.interpolationMode() == 2 || begin.interpolationMode() == 2;
            if (!catmullRom) return lerp(p1, p2, progress);

            YsmKeyframe leftFrame = frames.get(Math.max(0, i - 1));
            YsmKeyframe rightFrame = frames.get(Math.min(frames.size() - 1, i + 2));
            float[] p0 = values(post(leftFrame), context);
            float[] p3 = values(pre(rightFrame), context);
            return catmullRom(p0, p1, p2, p3, progress);
        }
        return values(post(frames.get(frames.size() - 1)), context);
    }

    private static List<YsmTypedValue> pre(YsmKeyframe frame) {
        return frame.hasPreValue() ? frame.preValues() : frame.postValues();
    }

    private static List<YsmTypedValue> post(YsmKeyframe frame) {
        return frame.postValues();
    }

    private static float[] values(List<YsmTypedValue> values, YsmExpressionRuntime.Context context) {
        float[] result = new float[3];
        for (int axis = 0; axis < 3; axis++) {
            YsmTypedValue value = values.get(axis);
            double number = value.isFloat() ? value.floatValue()
                    : YsmExpressionRuntime.evaluate(value.rawString(), context);
            if (!Double.isFinite(number))
                throw new IllegalArgumentException("YSM keyframe value evaluated to a non-finite number");
            result[axis] = (float) number;
        }
        return result;
    }

    private static float[] lerp(float[] start, float[] end, float t) {
        return new float[]{
                start[0] + t * (end[0] - start[0]),
                start[1] + t * (end[1] - start[1]),
                start[2] + t * (end[2] - start[2])
        };
    }

    private static float[] catmullRom(float[] left, float[] begin, float[] end, float[] right, float t) {
        float[] result = new float[3];
        float t2 = t * t;
        float t3 = t2 * t;
        for (int axis = 0; axis < 3; axis++) {
            float v0 = (end[axis] - left[axis]) * 0.5f;
            float v1 = (right[axis] - begin[axis]) * 0.5f;
            result[axis] = (2 * begin[axis] - 2 * end[axis] + v0 + v1) * t3
                    + (-3 * begin[axis] + 3 * end[axis] - 2 * v0 - v1) * t2
                    + v0 * t + begin[axis];
        }
        return result;
    }
}
