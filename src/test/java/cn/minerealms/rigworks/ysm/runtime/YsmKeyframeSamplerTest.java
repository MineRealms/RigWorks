/*
 * Originally derived from:
 * Source: https://github.com/YesSteveModel/YesSteveModel (Apache-2.0)
 * Modified by MineRealms for RigWorks.
 */
package cn.minerealms.rigworks.ysm.runtime;

import cn.minerealms.rigworks.ysm.model.YsmChannel;
import cn.minerealms.rigworks.ysm.model.YsmKeyframe;
import cn.minerealms.rigworks.ysm.model.YsmTypedValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
