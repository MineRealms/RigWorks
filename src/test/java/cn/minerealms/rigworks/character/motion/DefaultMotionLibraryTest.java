package cn.minerealms.rigworks.character.motion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class DefaultMotionLibraryTest {
    private static JsonObject manifest() throws Exception {
        try (var input = DefaultMotionLibraryTest.class.getResourceAsStream(
                "/assets/rigworks/motions/default/manifest.json")) {
            assertNotNull(input);
            return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test
    void allExportedMotionsRetainSourceMetadataAndNumericalPoses() throws Exception {
        var library = DefaultMotionLibrary.builtin();
        var manifest = manifest();
        assertEquals(25, library.roles().size());
        assertEquals(25, library.clipNames().size());
        assertEquals(60, library.fps());
        assertEquals(manifest.get("poseArrayBytes").getAsInt(), library.poseArrayBytes());
        assertTrue(library.poseArrayBytes() < 1024 * 1024);
        assertFalse(manifest.get("nativeRuntimeRequired").getAsBoolean());
        var pose = library.newPose();
        Vector3f position = new Vector3f();
        Quaternionf rotation = new Quaternionf();
        for (var value : manifest.getAsJsonArray("clips")) {
            var expected = value.getAsJsonObject();
            var clip = library.clip(expected.get("id").getAsString());
            assertNotNull(clip);
            assertEquals(expected.get("source").getAsString(), clip.source());
            assertEquals(expected.get("duration").getAsDouble(), clip.duration());
            assertEquals(expected.get("frames").getAsInt(), clip.frameCount());
            assertEquals(expected.get("mode").getAsString(), clip.mode());
            assertEquals(expected.get("mask").getAsString(), clip.mask());
            for (var checkValue : expected.getAsJsonArray("checks")) {
                var check = checkValue.getAsJsonObject();
                clip.sample(check.get("time").getAsDouble(), pose);
                for (var role : check.getAsJsonObject("roles").entrySet()) {
                    int index = library.roles().indexOf(role.getKey());
                    assertTrue(index >= 0);
                    assertPose(role.getValue().getAsJsonArray(), pose.position(index, position),
                            pose.rotation(index, rotation), clip.id() + " " + role.getKey());
                }
            }
        }
        var bind = library.bind();
        for (var role : manifest.getAsJsonObject("bind").entrySet()) {
            int index = library.roles().indexOf(role.getKey());
            assertPose(role.getValue().getAsJsonArray(), bind.position(index, position),
                    bind.rotation(index, rotation), "bind " + role.getKey());
        }
    }

    @Test
    void compressedResourceMatchesProvenanceHash() throws Exception {
        try (var input = DefaultMotionLibraryTest.class.getResourceAsStream(DefaultMotionLibrary.RESOURCE)) {
            assertNotNull(input);
            var data = input.readAllBytes();
            var expected = manifest();
            assertEquals(expected.get("compressedBytes").getAsInt(), data.length);
            assertEquals(expected.get("sha256").getAsString(),
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)));
        }
    }

    @Test
    void reusableSamplingWrapsLoopsClampsOneShotsAndPreservesImmutableBind() {
        var library = DefaultMotionLibrary.builtin();
        assertSame(library, DefaultMotionLibrary.builtin());
        var first = library.newPose();
        var output = library.newPose();
        int hand = library.roles().indexOf("right_hand");
        var initialBind = library.bind().rotation(hand, new Quaternionf());
        var borrowedBind = library.bind();
        var walk = library.clip("walk");
        walk.sample(.183, first);
        walk.sample(walk.duration() * 100 + .183, output);
        assertEquivalent(first, output, library, 1e-4f);
        var attack = library.clip("attack_item_right");
        attack.sample(attack.duration(), first);
        attack.sample(100, output);
        assertEquivalent(first, output, library, 1e-6f);
        attack.sample(.17, borrowedBind);
        assertEquals(1, Math.abs(initialBind.dot(library.bind().rotation(hand, new Quaternionf()))), 1e-6);
        assertThrows(UnsupportedOperationException.class, () -> library.roles().clear());
        assertThrows(UnsupportedOperationException.class, () -> library.clipNames().clear());
        assertThrows(IllegalArgumentException.class, () -> walk.sample(Double.NaN, output));
        assertThrows(IllegalArgumentException.class, () -> walk.sample(Double.POSITIVE_INFINITY, output));
    }

    @Test
    void semanticMirrorReflectsRightArmIntoLeftAndRoundTripsEveryRole() {
        var library = DefaultMotionLibrary.builtin();
        var original = library.newPose();
        var mirrored = library.newPose();
        var restored = library.newPose();
        library.clip("attack_tool_right").sample(.183, original);
        library.mirror(original, mirrored);
        int right = library.roles().indexOf("right_forearm");
        int left = library.roles().indexOf("left_forearm");
        Vector3f expectedPosition = original.position(right, new Vector3f());
        expectedPosition.x = -expectedPosition.x;
        assertTrue(expectedPosition.equals(mirrored.position(left, new Vector3f()), 1e-6f));
        Quaternionf expectedRotation = original.rotation(right, new Quaternionf());
        expectedRotation.y = -expectedRotation.y;
        expectedRotation.z = -expectedRotation.z;
        assertEquals(1, Math.abs(expectedRotation.dot(mirrored.rotation(left, new Quaternionf()))), 1e-6);
        library.mirror(mirrored, restored);
        assertEquivalent(original, restored, library, 1e-6f);
        assertThrows(IllegalArgumentException.class, () -> library.mirror(original, original));
    }

    @Test
    void everyHalfFrameInterpolatesFiniteUnitRotationsWithoutAnAntipodalFlip() {
        var library = DefaultMotionLibrary.builtin();
        var sample = library.newPose();
        Vector3f position = new Vector3f();
        Quaternionf rotation = new Quaternionf();
        for (String id : library.clipNames()) {
            var clip = library.clip(id);
            for (double time = 0; time < clip.duration(); time += 1.0 / 120) {
                clip.sample(time, sample);
                for (int role = 0; role < library.roles().size(); role++) {
                    assertTrue(sample.position(role, position).isFinite(), id);
                    assertTrue(sample.rotation(role, rotation).isFinite(), id);
                    assertEquals(1, rotation.lengthSquared(), 1e-5, id);
                }
            }
        }
    }

    @Test
    void boundedReaderRejectsCorruptHeadersInvalidQuaternionsAndAllocationBombs() throws Exception {
        byte[] good;
        try (var input = DefaultMotionLibraryTest.class.getResourceAsStream(DefaultMotionLibrary.RESOURCE)) {
            assertNotNull(input);
            good = input.readAllBytes();
        }
        assertThrows(IOException.class, () -> DefaultMotionLibrary.read(
                new ByteArrayInputStream(Arrays.copyOf(good, good.length / 2))));
        assertThrows(IOException.class, () -> DefaultMotionLibrary.read(
                new ByteArrayInputStream(libraryWithInvalidMetadata(false))));
        assertThrows(IOException.class, () -> DefaultMotionLibrary.read(
                new ByteArrayInputStream(libraryWithInvalidMetadata(true))));
        assertNull(DefaultMotionLibrary.builtin().clip("not_a_motion"));
    }

    private static byte[] libraryWithInvalidMetadata(boolean oversized) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (var data = new DataOutputStream(new GZIPOutputStream(output))) {
            data.writeInt(0x52574D44);
            data.writeInt(1);
            data.writeShort(60);
            data.writeShort(1);
            data.writeShort(1);
            text(data, "hips");
            for (int component = 0; component < 7; component++) data.writeFloat(component == 6 && oversized ? 1 : 0);
            if (oversized) {
                for (String value : new String[]{"test", "test.vmd", "once", "full_body"}) text(data, value);
                data.writeDouble(600);
                data.writeInt(Integer.MAX_VALUE);
            }
        }
        return output.toByteArray();
    }

    private static void text(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    private static void assertEquivalent(DefaultMotionLibrary.Pose a, DefaultMotionLibrary.Pose b,
                                         DefaultMotionLibrary library, float epsilon) {
        Vector3f ap = new Vector3f(), bp = new Vector3f();
        Quaternionf aq = new Quaternionf(), bq = new Quaternionf();
        for (int role = 0; role < library.roles().size(); role++) {
            assertTrue(a.position(role, ap).equals(b.position(role, bp), epsilon), library.roles().get(role));
            assertEquals(1, Math.abs(a.rotation(role, aq).dot(b.rotation(role, bq))), epsilon, library.roles().get(role));
        }
    }

    private static void assertPose(JsonArray expected, Vector3f position, Quaternionf rotation, String label) {
        assertEquals(expected.get(0).getAsFloat(), position.x, 1e-5, label);
        assertEquals(expected.get(1).getAsFloat(), position.y, 1e-5, label);
        assertEquals(expected.get(2).getAsFloat(), position.z, 1e-5, label);
        Quaternionf quaternion = new Quaternionf(expected.get(3).getAsFloat(), expected.get(4).getAsFloat(),
                expected.get(5).getAsFloat(), expected.get(6).getAsFloat()).normalize();
        assertEquals(1, Math.abs(quaternion.dot(rotation)), 1e-5, label);
    }
}
