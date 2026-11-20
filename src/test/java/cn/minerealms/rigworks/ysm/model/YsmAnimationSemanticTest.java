/*
 * Originally derived from:
 * Source: https://github.com/YesSteveModel/YesSteveModel (Apache-2.0)
 * Licensed under the Apache License, Version 2.0.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 * Modified by MineRealms for RigWorks.
 */
package cn.minerealms.rigworks.ysm.model;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YsmAnimationSemanticTest {
    @Test
    void typedReaderPreservesExpressionsAndRejectsNumericCoercion() throws Exception {
        YsmByteReader expressionReader = new YsmByteReader(new Payload().typedString("q.speed * 0.5").bytes());
        YsmTypedValue expression = expressionReader.readTypedValuePreserved();
        assertFalse(expression.isFloat());
        assertEquals(YsmTypedValue.STRING_TAG, expression.typeTag());
        assertEquals("q.speed * 0.5", expression.rawString());

        YsmByteReader strictReader = new YsmByteReader(new Payload().typedString("q.speed").bytes());
        assertThrows(IOException.class, strictReader::readTypedValue);

        YsmTypedValue numeric = new YsmByteReader(new Payload().typedFloat(1.25f).bytes())
                .readTypedValuePreserved();
        assertEquals(YsmTypedValue.FLOAT_TAG, numeric.typeTag());
        assertEquals(1.25f, numeric.floatValue());
    }

    @Test
    void decoderRetainsAnimationKeyframeTimelineSoundAndControllerData() throws Exception {
        YsmModel model = YsmBinaryDecoder.decode(payload());
        YsmAnimation animation = model.animations().get("semantic_clip");

        assertEquals("tac", animation.category());
        assertEquals(4, animation.categoryId());
        assertEquals(1, model.animationClips().size());

        assertEquals(3, animation.loopMode());
        assertFalse(animation.loop());
        assertTrue(animation.holdLastFrame());
        assertFalse(animation.playOnce());
        assertEquals(java.util.List.of(0, 0, 0), animation.formatFlags());
        assertEquals(1, animation.blendWeight().size());
        assertEquals("q.blend", animation.blendWeight().get(0).rawString());

        YsmKeyframe keyframe = animation.channels().get(0).frames().get(0);
        assertEquals(2, keyframe.interpolationMode());
        assertEquals("catmullrom", keyframe.interpolation());
        assertTrue(keyframe.hasPreValue());
        assertEquals("q.pre_x", keyframe.preValues().get(0).rawString());
        assertEquals("q.post_x", keyframe.postValues().get(0).rawString());
        assertEquals(90f, keyframe.postValues().get(1).floatValue());
        assertThrows(IllegalStateException.class, keyframe::value);

        assertEquals(1, animation.instructionKeyframes().size());
        assertEquals(1.5f, animation.instructionKeyframes().get(0).time());
        assertEquals(java.util.List.of("v.step = 1", "q.action = 'fire'"),
                animation.instructionKeyframes().get(0).instructions());
        assertEquals("test:shot", animation.soundKeyframes().get(0).sound());
        assertEquals(1f, animation.soundKeyframes().get(0).time());

        YsmController controller = model.controllers().get(0);
        assertEquals("player.main", controller.name());
        assertEquals("unk_0", controller.sourceGroup());
        assertEquals(0, controller.sourceGroupId());
        assertEquals("idle", controller.initialState());
        YsmState state = controller.states().get("idle");
        assertEquals("q.run", state.animations().get("run"));
        assertEquals("q.run", state.animationReferences().get(0).condition());
        assertEquals("moving", state.transitions().get(0).to());
        assertEquals("q.speed > 0", state.transitions().get(0).condition());
        assertEquals(java.util.List.of("v.entered = 1", "v.ready = 1"), state.onEntry());
        assertEquals(java.util.List.of("v.exited = 1"), state.onExit());
        assertTrue(state.blendViaShortestPath());
        assertEquals(2, state.blendTransition().points().size());
        assertEquals(0f, state.blendTransition().points().get(0).input());
        assertEquals(1f, state.blendTransition().points().get(1).output());
    }

    @Test
    void legacyConstructorsKeepTheirOriginalSurface() {
        YsmKeyframe keyframe = new YsmKeyframe(0.5f, new float[]{1, 2, 3}, "linear");
        assertArrayEquals(new float[]{1, 2, 3}, keyframe.value());

        YsmAnimation animation = new YsmAnimation("legacy", 1, true, java.util.List.of());
        assertEquals(1, animation.loopMode());

        YsmState state = new YsmState(java.util.Map.of("main", "clip"), java.util.List.of());
        assertEquals("clip", state.animations().get("main"));
        assertTrue(state.onEntry().isEmpty());
    }

    private static byte[] payload() {
        Payload w = new Payload();
        w.dword(10);
        w.varint(0); // metadata bytes
        w.varint(0); // geometry count

        w.varint(1); // animation blobs
        w.varint(4); // TAC animation category
        w.varint(1); // animation blob marker
        w.varint(1); // animations
        w.str("semantic_clip");
        w.float32(40f); // duration in ticks
        w.varint(3); // hold on last frame
        w.varint(0);
        w.varint(0);
        w.varint(1); // blend weight entries
        w.typedString("q.blend");
        w.varint(0);
        w.varint(1); // animated bones
        w.str("right_arm");

        w.varint(1); // rotation keyframes
        w.float32(10f);
        w.varint(2); // catmull-rom
        w.typedString("q.pre_x");
        w.typedFloat(20f);
        w.typedFloat(30f);
        w.varint(1); // both pre and post are present
        w.typedString("q.post_x");
        w.typedFloat(90f);
        w.typedFloat(100f);
        w.varint(0); // position keyframes
        w.varint(0); // scale keyframes

        w.varint(1); // timeline groups
        w.varint(2); // instructions in group
        w.str("v.step = 1");
        w.str("q.action = 'fire'");
        w.float32(30f); // event time in ticks
        w.varint(1); // animation sound events
        w.str("test:shot");
        w.float32(20f); // sound time in ticks

        w.varint(1); // controllers
        w.varint(0); // legacy controller id
        w.varint(1); // controller animation groups
        w.str("player.main");
        w.str("idle");
        w.varint(1); // states
        w.str("idle");
        w.varint(2); // animation references
        w.str("run");
        w.str("q.run");
        w.str("idle");
        w.str("");
        w.varint(1); // transitions
        w.str("moving");
        w.str("q.speed > 0");
        w.varint(2); // on_entry expressions
        w.str("v.entered = 1");
        w.str("v.ready = 1");
        w.varint(1); // on_exit expressions
        w.str("v.exited = 1");
        w.varint(0); // curve transition mode
        w.varint(2); // curve points
        w.float32(0f);
        w.float32(0f);
        w.float32(1f);
        w.float32(1f);
        w.varint(1); // blend via shortest path
        w.varint(0); // controller-name table

        w.varint(0); // textures
        w.varint(0); // sound files
        w.varint(0); // sound table
        w.varint(0); // extra textures
        w.varint(0); // model table
        w.varint(0); // animation table
        w.varint(0); // texture table
        return w.bytes();
    }

    private static final class Payload {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Payload dword(int value) {
            for (int i = 0; i < 4; i++) out.write((value >>> (i * 8)) & 0xFF);
            return this;
        }

        Payload varint(int value) {
            int next = value;
            while ((next & -128) != 0) {
                out.write((next & 127) | 128);
                next >>>= 7;
            }
            out.write(next);
            return this;
        }

        Payload str(String value) {
            return bytes(value.getBytes(StandardCharsets.UTF_8));
        }

        Payload bytes(byte[] value) {
            varint(value.length);
            out.write(value, 0, value.length);
            return this;
        }

        Payload typedString(String value) {
            out.write(YsmTypedValue.STRING_TAG);
            return str(value);
        }

        Payload typedFloat(float value) {
            out.write(YsmTypedValue.FLOAT_TAG);
            return float32(value);
        }

        Payload float32(float value) {
            return dword(Float.floatToRawIntBits(value));
        }

        byte[] bytes() {
            return out.toByteArray();
        }
    }
}
