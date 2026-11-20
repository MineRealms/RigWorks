/*
 * Originally derived from:
 * Source: https://github.com/YesSteveModel/YesSteveModel (Apache-2.0)
 * Modified by MineRealms for RigWorks.
 */
package cn.minerealms.rigworks.ysm.runtime;

import cn.minerealms.rigworks.ysm.model.YsmChannel;
import cn.minerealms.rigworks.ysm.model.YsmKeyframe;
import cn.minerealms.rigworks.ysm.model.YsmTypedValue;
import cn.minerealms.rigworks.ysm.model.YsmBinaryDecoder;
import cn.minerealms.rigworks.ysm.java.YsmJavaDecoder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class YsmKeyframeSamplerTest {
    private static List<YsmTypedValue> v(float x, float y, float z) {
        return List.of(YsmTypedValue.ofFloat(x), YsmTypedValue.ofFloat(y), YsmTypedValue.ofFloat(z));
    }

    private static YsmKeyframe key(float time, float x, int easing) {
        return new YsmKeyframe(time, List.of(), v(x, 0, 0), "linear", easing, false);
    }

    @Test
    void samplesLinearSegmentsFromPreviousPostToNextPreAndUsesPostAtKeyTime() {
        var frames = List.of(
                key(0, 0, 0),
                new YsmKeyframe(1, v(90, 0, 0), v(180, 0, 0), "linear", 0, true));
        var channel = new YsmChannel("arm", "rotation", frames);

        assertEquals(-Math.PI / 4, YsmKeyframeSampler.sample(channel, 0.5, null)[0], 1e-5);
        assertEquals(-Math.PI, YsmKeyframeSampler.sample(channel, 1, null)[0], 1e-5);
    }

    @Test
    void firstKeyframeHoldsPreValueUntilItsTimestampThenJumpsToPostValue() {
        var channel = new YsmChannel("eyelid", "position", List.of(
                new YsmKeyframe(0.5f, v(1, 2, 3), v(4, 5, 6), "linear", 0, true)));

        assertEquals(1, YsmKeyframeSampler.sample(channel, 0.25, null)[0], 1e-6);
        assertEquals(4, YsmKeyframeSampler.sample(channel, 0.5, null)[0], 1e-6);
    }

    @Test
    void catmullRomUsesPreviousPostAndFollowingPreNeighborValues() {
        var channel = new YsmChannel("arm", "position", List.of(
                key(0, 0, 0),
                key(1, 10, 2),
                new YsmKeyframe(2, v(20, 0, 0), v(25, 0, 0), "catmullrom", 2, true),
                new YsmKeyframe(3, v(40, 0, 0), v(40, 0, 0), "linear", 0, true)));

        assertEquals(14.375, YsmKeyframeSampler.sample(channel, 1.5, null)[0], 1e-5);
    }

    @Test
    void evaluatesExpressionComponentsAtSampleTimeAndConvertsRotationAxes() {
        var expression = new YsmKeyframe(0, List.of(), List.of(
                YsmTypedValue.expression("q.angle"),
                YsmTypedValue.expression("q.angle * 2"),
                YsmTypedValue.ofFloat(90)), "linear", 0, false);
        var channel = new YsmChannel("head", "rotation", List.of(expression));
        var context = new YsmExpressionRuntime.MapContext(Map.of("q.angle", 45d), Set.of());

        float[] value = YsmKeyframeSampler.sample(channel, 0, context);
        assertEquals(-Math.PI / 4, value[0], 1e-5);
        assertEquals(-Math.PI / 2, value[1], 1e-5);
        assertEquals(Math.PI / 2, value[2], 1e-5);
    }

    @Test
    void preparedTracksMatchOriginalSamplerAcrossBoundariesDuplicatesAndCurves() {
        Random random = new Random(0x5257594d);
        List<YsmKeyframe> frames = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            float time = i / 2 * 0.125f;
            List<YsmTypedValue> before = v(random.nextFloat() * 90, random.nextFloat(), random.nextFloat());
            List<YsmTypedValue> after = List.of(YsmTypedValue.expression("q.angle * 2 + " + i),
                    YsmTypedValue.ofFloat(random.nextFloat()), YsmTypedValue.ofFloat(random.nextFloat()));
            frames.add(new YsmKeyframe(time, before, after, "linear", i % 3 == 0 ? 2 : 0, true));
        }
        var context = new YsmExpressionRuntime.MapContext(Map.of("q.angle", 5d), Set.of());
        for (String path : List.of("position", "rotation", "scale")) {
            YsmChannel channel = new YsmChannel("bone", path, frames);
            var prepared = YsmKeyframeSampler.prepare(channel);
            float[] out = {0, 0, 0, 123};
            for (int sample = -2; sample <= 258; sample++) {
                double time = sample / 64.0;
                prepared.sample(time, context, out);
                assertArrayEquals(LegacyYsmSamplingReference.sample(channel, time, context),
                        new float[]{out[0], out[1], out[2]}, 0f, path + " at " + time);
                assertEquals(123, out[3], "sampling must not overwrite the rest of the caller buffer");
            }
        }
    }

    @Test
    void preparedTrackSnapshotsInputAndKeepsQueriesLive() {
        var frames = new ArrayList<>(List.of(new YsmKeyframe(0, List.of(), List.of(
                YsmTypedValue.expression("q.angle"), YsmTypedValue.ofFloat(2), YsmTypedValue.ofFloat(3)), "linear", 0, false)));
        var prepared = YsmKeyframeSampler.prepare(new YsmChannel("head", "position", frames));
        frames.clear();
        assertEquals("head", prepared.bone());
        assertEquals("position", prepared.path());
        assertEquals(1, prepared.keyframeCount());
        float[] out = new float[3];
        var context = new YsmExpressionRuntime.MapContext(Map.of("q.angle", 10d), Set.of());
        prepared.sample(0, context, out);
        assertArrayEquals(new float[]{10, 2, 3}, out);
        context.setQuery("angle", 25);
        prepared.sample(0, context, out);
        assertArrayEquals(new float[]{25, 2, 3}, out);
    }

    @Test
    void preparedTracksRejectInvalidTimesAndOutputAndAllowEmptyTracks() {
        assertThrows(IllegalArgumentException.class, () -> YsmKeyframeSampler.prepare(
                new YsmChannel("bone", "position", List.of(key(1, 0, 0), key(0, 1, 0)))));
        assertThrows(IllegalArgumentException.class, () -> YsmKeyframeSampler.prepare(
                new YsmChannel("bone", "position", List.of(key(Float.NaN, 0, 0)))));
        var empty = YsmKeyframeSampler.prepare(new YsmChannel("bone", "rotation", List.of()));
        float[] out = {1, 2, 3};
        empty.sample(1, null, out);
        assertArrayEquals(new float[3], out);
        assertThrows(IllegalArgumentException.class, () -> empty.sample(Double.NaN, null, out));
        assertThrows(IllegalArgumentException.class, () -> empty.sample(0, null, new float[2]));
        assertThrows(IllegalArgumentException.class, () -> empty.sample(0, null, null));
    }

    @Test
    void realFixtureNumericChannelsMatchOriginalAtKeysAndBetweenKeys() throws Exception {
        byte[] file;
        try (var stream = getClass().getResourceAsStream("/ysm/Flandre_Scarlet.ysm")) {
            assertNotNull(stream);
            file = stream.readAllBytes();
        }
        var model = YsmBinaryDecoder.decode(YsmJavaDecoder.decode(file));
        int channels = 0;
        float[] out = new float[3];
        for (var clip : model.animationClips()) for (var channel : clip.channels()) {
            if (channel.frames().isEmpty() || channel.frames().stream().anyMatch(frame ->
                    frame.postValues().stream().anyMatch(value -> !value.isFloat()) ||
                            frame.preValues().stream().anyMatch(value -> !value.isFloat()))) continue;
            var prepared = YsmKeyframeSampler.prepare(channel);
            double previous = -0.1;
            for (var frame : channel.frames()) {
                for (double time : new double[]{frame.time(), (previous + frame.time()) * 0.5}) {
                    prepared.sample(time, null, out);
                    assertArrayEquals(LegacyYsmSamplingReference.sample(channel, time, null), out, 0,
                            clip.name() + "/" + channel.bone() + "/" + channel.path() + " at " + time);
                }
                previous = frame.time();
            }
            channels++;
        }
        assertTrue(channels > 100, "real fixture must exercise a representative set of numeric channels");
    }
}
