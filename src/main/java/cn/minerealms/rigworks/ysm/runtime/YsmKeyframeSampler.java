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

    /** Convenience API. Runtime owners should prepare and retain a track instead. */
    public static float[] sample(YsmChannel channel, double timeSeconds, YsmExpressionRuntime.Context context) {
        float[] result = new float[3];
        prepare(channel).sample(timeSeconds, context, result);
        return result;
    }

    public static PreparedTrack prepare(YsmChannel channel) {
        return prepare(channel, "unnamed clip");
    }

    public static PreparedTrack prepare(YsmChannel channel, String clip) {
        if (channel == null) throw new IllegalArgumentException("YSM channel is required");
        return new PreparedTrack(channel, clip);
    }

    /** Owns copied arrays and immutable expression programs, never the caller's keyframe list. */
    public static final class PreparedTrack {
        private final String bone;
        private final String path;
        private final boolean rotation;
        private final float[] times;
        private final boolean[] curved;
        private final float[] pre;
        private final float[] post;
        private final YsmExpressionRuntime.Program[] prePrograms;
        private final YsmExpressionRuntime.Program[] postPrograms;

        private PreparedTrack(YsmChannel channel, String clip) {
            bone = channel.bone();
            path = channel.path();
            rotation = "rotation".equalsIgnoreCase(path);
            List<YsmKeyframe> frames = channel.frames() == null ? List.of() : List.copyOf(channel.frames());
            times = new float[frames.size()];
            curved = new boolean[frames.size()];
            post = new float[Math.multiplyExact(frames.size(), 3)];
            boolean expressions = false;
            boolean separatePre = false;
            for (int i = 0; i < frames.size(); i++) {
                YsmKeyframe frame = frames.get(i);
                float time = frame.time();
                if (!Float.isFinite(time)) throw new IllegalArgumentException("YSM keyframe time must be finite");
                if (i > 0 && time < times[i - 1])
                    throw new IllegalArgumentException("YSM keyframes must be sorted by time");
                times[i] = time;
                curved[i] = frame.interpolationMode() == 2;
                separatePre |= frame.hasPreValue();
                for (int axis = 0; axis < 3; axis++) {
                    expressions |= !frame.postValues().get(axis).isFloat();
                    if (frame.hasPreValue()) expressions |= !frame.preValues().get(axis).isFloat();
                }
            }
            pre = separatePre ? new float[post.length] : post;
            postPrograms = expressions ? new YsmExpressionRuntime.Program[post.length] : null;
            prePrograms = separatePre && expressions ? new YsmExpressionRuntime.Program[pre.length] : postPrograms;
            for (int i = 0; i < frames.size(); i++) {
                YsmKeyframe frame = frames.get(i);
                prepareValues(frame.hasPreValue() ? frame.preValues() : frame.postValues(), i, pre, prePrograms, clip, "pre");
                prepareValues(frame.postValues(), i, post, postPrograms, clip, "post");
            }
        }

        public String bone() { return bone; }
        public String path() { return path; }
        public int keyframeCount() { return times.length; }

        /** No sampling scratch or result arrays are allocated. A track may be shared across threads. */
        public void sample(double timeSeconds, YsmExpressionRuntime.Context context, float[] out) {
            if (!Double.isFinite(timeSeconds)) throw new IllegalArgumentException("YSM sample time must be finite");
            if (out == null || out.length < 3) throw new IllegalArgumentException("YSM sample output requires three components");
            int count = times.length;
            if (count == 0) {
                out[0] = out[1] = out[2] = 0;
                return;
            }
            if (timeSeconds < times[0]) copy(pre, prePrograms, 0, context, out);
            else if (count == 1 || timeSeconds >= times[count - 1]) copy(post, postPrograms, count - 1, context, out);
            else sampleSegment(timeSeconds, context, out);
            if (rotation) {
                out[0] *= -DEGREES_TO_RADIANS;
                out[1] *= -DEGREES_TO_RADIANS;
                out[2] *= DEGREES_TO_RADIANS;
            }
        }

        private void sampleSegment(double time, YsmExpressionRuntime.Context context, float[] out) {
            // Upper bound retains post-value semantics at exact and duplicate timestamps.
            int lo = 0, hi = times.length;
            while (lo < hi) {
                int middle = (lo + hi) >>> 1;
                if (time < times[middle]) hi = middle;
                else lo = middle + 1;
            }
            int end = lo, begin = end - 1;
            float progress = (float) ((time - times[begin]) / (times[end] - times[begin]));
            float x1 = component(post, postPrograms, begin * 3, context);
            float y1 = component(post, postPrograms, begin * 3 + 1, context);
            float z1 = component(post, postPrograms, begin * 3 + 2, context);
            float x2 = component(pre, prePrograms, end * 3, context);
            float y2 = component(pre, prePrograms, end * 3 + 1, context);
            float z2 = component(pre, prePrograms, end * 3 + 2, context);
            if (!curved[begin] && !curved[end]) {
                out[0] = lerp(x1, x2, progress);
                out[1] = lerp(y1, y2, progress);
                out[2] = lerp(z1, z2, progress);
                return;
            }
            int left = Math.max(0, begin - 1) * 3, right = Math.min(times.length - 1, end + 1) * 3;
            float x0 = component(post, postPrograms, left, context);
            float y0 = component(post, postPrograms, left + 1, context);
            float z0 = component(post, postPrograms, left + 2, context);
            float x3 = component(pre, prePrograms, right, context);
            float y3 = component(pre, prePrograms, right + 1, context);
            float z3 = component(pre, prePrograms, right + 2, context);
            out[0] = catmullRom(x0, x1, x2, x3, progress);
            out[1] = catmullRom(y0, y1, y2, y3, progress);
            out[2] = catmullRom(z0, z1, z2, z3, progress);
        }

        private void prepareValues(List<YsmTypedValue> values, int frame, float[] numbers,
                                   YsmExpressionRuntime.Program[] programs, String clip, String phase) {
            for (int axis = 0; axis < 3; axis++) {
                YsmTypedValue value = values.get(axis);
                int offset = frame * 3 + axis;
                if (value.isFloat()) numbers[offset] = value.floatValue();
                else programs[offset] = YsmExpressionRuntime.compileImported(value.rawString(),
                        clip + "/" + bone + "/" + path + " " + phase + " time=" + times[frame] + " axis=" + axis);
            }
        }

        private static void copy(float[] numbers, YsmExpressionRuntime.Program[] programs, int frame,
                                 YsmExpressionRuntime.Context context, float[] out) {
            for (int axis = 0; axis < 3; axis++) out[axis] = component(numbers, programs, frame * 3 + axis, context);
        }

        private static float component(float[] numbers, YsmExpressionRuntime.Program[] programs, int offset,
                                       YsmExpressionRuntime.Context context) {
            double value = programs != null && programs[offset] != null ? programs[offset].evaluate(context) : numbers[offset];
            if (!Double.isFinite(value)) throw new IllegalArgumentException("YSM keyframe value evaluated to a non-finite number");
            return (float) value;
        }
    }

    private static float lerp(float start, float end, float t) { return start + t * (end - start); }

    private static float catmullRom(float left, float begin, float end, float right, float t) {
        float t2 = t * t, t3 = t2 * t;
        float v0 = (end - left) * 0.5f, v1 = (right - begin) * 0.5f;
        return (2 * begin - 2 * end + v0 + v1) * t3
                + (-3 * begin + 3 * end - 2 * v0 - v1) * t2 + v0 * t + begin;
    }
}
