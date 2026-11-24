package cn.minerealms.rigworks.ysm.runtime;

import cn.minerealms.rigworks.character.core.CharacterEquipmentDefinition;
import cn.minerealms.rigworks.character.core.CharacterProfile;
import cn.minerealms.rigworks.character.core.CharacterVisualConfig;
import cn.minerealms.rigworks.ysm.asset.RwymAssetModels;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class YsmAvatarReimportTest {
    private static final String MODEL = "rigworks:models/imported/ysm/fixture/model.rwym";
    private static final String HASH = "a".repeat(64);

    @Test void exactAssetReimportKeepsSavedScaleSocketsRootAndBehavior() {
        var saved = configured();
        var restored = YsmAvatarInstaller.savedConfiguration(saved.json(), MODEL, HASH);
        assertEquals(saved, restored);
        assertEquals(.08, restored.profile().scale());
        assertEquals(saved.equipment(), restored.equipment());
        assertEquals(saved.transform(), restored.transform());
        assertEquals(saved.options(), restored.options());
        assertEquals(saved.rig(), restored.rig());
    }

    @Test void differentAssetOrModelCannotInheritTheOldCalibration() {
        var saved = configured();
        assertNull(YsmAvatarInstaller.savedConfiguration(saved.json(), MODEL, "b".repeat(64)));
        assertNull(YsmAvatarInstaller.savedConfiguration(saved.json(), "other:asset.rwym", HASH));
        assertNull(YsmAvatarInstaller.savedConfiguration(null, MODEL, HASH));
        assertNull(YsmAvatarInstaller.savedConfiguration("{broken", MODEL, HASH));
    }

    @Test void renderSceneReuseRequiresTheExactAssetAndRenderRevisionPath() {
        Path current = Path.of("cache", "r", HASH, RwymAssetModels.RENDER_REVISION, "model.glb");
        assertTrue(YsmAvatarInstaller.sameRenderCache(current, current.toAbsolutePath()));
        assertTrue(YsmAvatarInstaller.sameRenderCache(current, current.resolveSibling("unused").resolve("..").resolve("model.glb")));
        assertFalse(YsmAvatarInstaller.sameRenderCache(current,
                Path.of("cache", "r", HASH, RwymAssetModels.RENDER_REVISION + "-previous", "model.glb")));
        assertFalse(YsmAvatarInstaller.sameRenderCache(current,
                Path.of("cache", "r", "b".repeat(64), RwymAssetModels.RENDER_REVISION, "model.glb")));
        assertFalse(YsmAvatarInstaller.sameRenderCache(null, current));
        assertFalse(YsmAvatarInstaller.sameRenderCache(current, null));
    }

    private static CharacterVisualConfig configured() {
        var profile = CharacterProfile.parse(JsonParser.parseString("""
                {"version":1,"model":"%s","scale":0.08,"modelYawDegrees":0,
                 "humanoid":{"right_hand":"RightHandLocator"},"animations":{"walk":"main:walk"}}
                """.formatted(MODEL)).getAsJsonObject());
        var equipment = CharacterEquipmentDefinition.read(new StringReader("""
                {"version":1,"right_hand":{"node":"RightHandLocator","position":[0.1,0.2,0.3],"scale":1.25}}
                """));
        return new CharacterVisualConfig(profile, equipment,
                new CharacterVisualConfig.Transform(0, .2f, 0, 0, 15, 0, 1.1f, 1.1f, 1.1f), HASH,
                "{\"version\":1,\"ik\":{}}", null, new CharacterVisualConfig.Options(true, false, false, true));
    }
}
