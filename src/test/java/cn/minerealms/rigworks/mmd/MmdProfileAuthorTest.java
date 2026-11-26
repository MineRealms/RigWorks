package cn.minerealms.rigworks.mmd;

import cn.minerealms.rigworks.character.core.CharacterProfile;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MmdProfileAuthorTest {
    @Test void authoredIdleWinsOverNeutralAndRolesCalibrateAnArmsOutBindPose(@TempDir Path directory) throws Exception {
        var metadata = json("""
                {"bones":[
                  {"name":"arm_L","sourceName":"\u5de6\u8155","position":[1,1,0]},
                  {"name":"elbow_L","sourceName":"\u5de6\u3072\u3058","position":[2,1,0]}],
                 "clips":[{"name":"mmd_neutral"},{"name":"stand"},{"name":"walk"}]}
                """);
        var settings = new MmdImportSettings(1.85, 60, 18000, List.of(), Map.of());
        MmdProfileAuthor.write(directory, metadata, settings, "a".repeat(64));
        var profile = CharacterProfile.parse(MmdAssetCache.json(directory.resolve("profile.json")));
        assertEquals("stand", profile.animations().get("idle"));
        assertEquals("walk", profile.animations().get("walk"));
        assertEquals(180, profile.modelYawDegrees()); assertEquals(-1, profile.headLook().forward().z());
        var arm = profile.humanoid().get("left_upper_arm"); assertEquals("arm_L", arm.node());
        assertEquals(0, arm.nativeRotation().quaternion().transform(new Vector3f(1,0,0)).distance(new Vector3f(0,-1,0)), 1e-6);
        assertEquals("RigWorks_MMD", profile.humanoid().get("root").node());
    }

    @Test void physicsReflectionTransformsLocalFramesAndLimitIntervalsTogether() {
        var physics = json("""
                {"bodies":[{"position":[1,2,3],"rotation":[0.1,0.2,0.3,0.9]}],
                 "joints":[{"positionA":[1,2,3],"positionB":[4,5,6],
                 "linearLower":[-1,-2,-3],"linearUpper":[4,5,6],
                 "angularLower":[-0.1,-0.2,-0.3],"angularUpper":[0.4,0.5,0.6]}]}
                """);
        MmdProfileAuthor.reflectPhysics(physics);
        var body = physics.getAsJsonArray("bodies").get(0).getAsJsonObject();
        assertEquals(-3, body.getAsJsonArray("position").get(2).getAsFloat());
        assertEquals(-.1f, body.getAsJsonArray("rotation").get(0).getAsFloat());
        var joint = physics.getAsJsonArray("joints").get(0).getAsJsonObject();
        assertEquals(-6, joint.getAsJsonArray("linearLower").get(2).getAsFloat());
        assertEquals(3, joint.getAsJsonArray("linearUpper").get(2).getAsFloat());
        assertEquals(-.4f, joint.getAsJsonArray("angularLower").get(0).getAsFloat());
        assertEquals(.1f, joint.getAsJsonArray("angularUpper").get(0).getAsFloat());
    }

    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
}
