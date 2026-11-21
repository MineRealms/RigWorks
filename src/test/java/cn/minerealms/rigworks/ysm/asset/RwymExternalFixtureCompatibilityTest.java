package cn.minerealms.rigworks.ysm.asset;

import cn.minerealms.rigworks.ysm.model.YsmTypedValue;
import cn.minerealms.rigworks.ysm.runtime.YsmExpressionRuntime;
import cn.minerealms.rigworks.ysm.runtime.YsmControllerRuntime;
import cn.minerealms.rigworks.ysm.runtime.YsmModelSource;
import cn.minerealms.rigworks.ysm.runtime.YsmPoseDriver;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RwymExternalFixtureCompatibilityTest {
    @Test void nativeConversionProducesAssetsReadableByTheJavaPipeline() throws Exception {
        String configured = System.getProperty("rigworks.ysm.compatFixtures", "");
        Assumptions.assumeTrue(!configured.isBlank(), "Set rigworks.ysm.compatFixtures to external YSM paths");
        List<Path> fixtures = Arrays.stream(configured.split(";"))
                .filter(path -> !path.isBlank())
                .map(Path::of)
                .toList();
        assertFalse(fixtures.isEmpty());
        assertTrue(RwymNative.available(), RwymNative::failure);

        assertAll("external YSM runtime compatibility", fixtures.stream().map(fixture -> () -> verifyFixture(fixture)));
    }

    private static void verifyFixture(Path fixture) throws Exception {
            byte[] source = Files.readAllBytes(fixture);
            var asset = RwymReader.read(RwymNative.convert(source));
            assertTrue(asset.sourceFormat() == 1 || asset.sourceFormat() >= 4 && asset.sourceFormat() <= 15,
                    fixture.getFileName() + " has unsupported source format " + asset.sourceFormat());
            assertFalse(asset.geometries().isEmpty(), fixture.getFileName() + " has no models");
            assertFalse(asset.mainModel().bones().isEmpty(), fixture.getFileName() + " has no bones");
            assertFalse(asset.clips().isEmpty(), fixture.getFileName() + " has no animation clips");
            assertFalse(asset.textures().isEmpty(), fixture.getFileName() + " has no textures");
            Set<String> expressions = new LinkedHashSet<>();
            for (var clip : asset.clips()) {
                collectExpressions(expressions, clip.blendWeight());
                for (var channel : clip.channels()) for (var key : channel.frames()) {
                    collectExpressions(expressions, key.preValues());
                    collectExpressions(expressions, key.postValues());
                }
                for (var key : clip.instructionKeyframes()) expressions.addAll(key.instructions());
            }
            for (var controller : asset.controllers()) for (var state : controller.states().values()) {
                expressions.addAll(state.onEntry());
                expressions.addAll(state.onExit());
                for (var reference : state.animationReferences()) expressions.add(reference.condition());
                for (var transition : state.transitions()) expressions.add(transition.condition());
            }
            var view = YsmModelSource.reflective().view(asset.mainModel());
            var definition = assertDoesNotThrow(() -> new YsmPoseDriver.Definition(view.animations(), view.bones()),
                    fixture.getFileName() + " runtime animation preparation");
            var driver = new YsmPoseDriver(definition);
            var context = new YsmExpressionRuntime.MapContext(Map.of(), Set.of())
                    .setQuery("ground_speed", 3).setQuery("vertical_speed", -0.25)
                    .setQuery("anim_time", 0.25).setQuery("life_time", 10);
            for (var controller : view.controllers()) {
                var runtime = assertDoesNotThrow(() -> new YsmControllerRuntime(YsmControllerRuntime.fromView(controller),
                        driver.scoped(controller.sourceGroup(), controller.name())));
                assertDoesNotThrow(() -> runtime.update(context));
                assertDoesNotThrow(() -> runtime.update(context));
            }
            int fallbacks = 0;
            for (String expression : expressions) {
                var program = assertDoesNotThrow(() -> YsmExpressionRuntime.compileImported(expression, fixture.getFileName().toString()));
                assertTrue(Double.isFinite(program.evaluate(context)), expression);
                if (program.compileError() != null) {
                    fallbacks++;
                    System.out.println(fixture.getFileName() + " expression fallback: " + program.compileError() + " [" + expression + "]");
                }
            }
            System.out.println(fixture.getFileName() + ": " + asset.clips().size() + " clips, " + asset.controllers().size()
                    + " controllers, " + expressions.size() + " unique expressions, " + fallbacks + " malformed expression fallbacks");
    }

    private static void collectExpressions(Set<String> target, List<YsmTypedValue> values) {
        for (var value : values) if (!value.isFloat()) target.add(value.rawString());
    }
}
