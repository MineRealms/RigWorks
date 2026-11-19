package cn.minerealms.rigworks.character.client;

import cn.minerealms.assimploader.AssimpLoader;
import cn.minerealms.assimploader.client.transform.EditableTransform;
import cn.minerealms.assimploader.client.transform.GizmoMath;
import cn.minerealms.assimploader.compat.OculusCompat;
import cn.minerealms.assimploader.rendering.OculusEntityBufferCache;
import cn.minerealms.assimploader.client.ui.LookDevNavigation;
import cn.minerealms.rigworks.RigWorks;
import cn.minerealms.rigworks.character.core.CharacterEquipmentDefinition;
import com.google.gson.GsonBuilder;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Opt-in UI integration check in a copied world. The final editor/client stays open. */
@Mod.EventBusSubscriber(modid = RigWorks.MODID, value = Dist.CLIENT)
public final class EquipmentWorkbenchSmokeTest {
    private static final boolean ENABLED = Boolean.getBoolean("equipmentworkbenchtest.enabled");
    private static final long START = System.currentTimeMillis();
    private static boolean finished, capturing;
    private static int phase, ticks;
    private static CharacterEquipmentEditorSession observer;
    private static EditableTransform right, left;
    private static Object animation;
    private static CameraType previousCamera;
    private static float yaw, pitch;
    private static long uploads, dispatches, frozenCacheDraws, frozenCacheHits;
    private static final List<String> captures = new ArrayList<>();
    private static Vec3 beforeWall;
    private static CompletableFuture<Void> wallTask;
    private static final Map<BlockPos, BlockState> replaced = new LinkedHashMap<>();
    private static ItemStack originalMain, originalOff;

    private EquipmentWorkbenchSmokeTest() {}

    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (!ENABLED || finished || capturing || event.phase != TickEvent.Phase.END) return;
        var mc = Minecraft.getInstance();
        try {
            check(System.currentTimeMillis() - START < 300_000, "Workbench acceptance timed out");
            if (mc.player == null || mc.level == null || LocalPlayerCharacter.currentAnimation() == null) return;
            if (Boolean.getBoolean("charactercliptest.enabled") && !CharacterClipSmokeTest.succeeded()) return;
            if (Boolean.getBoolean("rigworkspersistence.enabled") && !WorkbenchPersistenceSmokeTest.succeeded()) return;
            if (mc.getOverlay() != null) return;
            ticks++;
            if (ticks < 15) return;
            ticks = 0;
            switch (phase++) {
                case 0 -> {
                    originalMain=mc.player.getMainHandItem().copy();originalOff=mc.player.getOffhandItem().copy();
                    mc.player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.IRON_SWORD));
                    mc.player.setItemInHand(InteractionHand.OFF_HAND,new ItemStack(Items.SHIELD));
                    // Observe the binding without replacing the UI's single active editing transaction.
                    observer = new CharacterEquipmentEditorSession(LocalPlayerCharacter.equipmentEditorTarget());
                    check(observer.available(HumanoidArm.RIGHT) && observer.available(HumanoidArm.LEFT), "Two hand sockets required");
                    right = observer.get(HumanoidArm.RIGHT); left = observer.get(HumanoidArm.LEFT);
                    animation = LocalPlayerCharacter.currentAnimation();
                    previousCamera = mc.options.getCameraType();
                    uploads = OculusEntityBufferCache.poseStatistics().uploads();
                    dispatches = OculusEntityBufferCache.gpuPoseStatistics().dispatches();
                    EquipmentTransformScreen.open();
                }
                case 1 -> {
                    var editor = editor(mc);
                    check(LookDevNavigation.modules().stream().anyMatch(module -> module.id().equals("rigworks")), "RigWorks module tab missing");
                    check(LookDevNavigation.pages("assimploader").stream().map(page -> page.key().page()).toList().equals(List.of("models", "edit", "debug")), "Core pages were not consolidated");
                    check(LookDevNavigation.pages("rigworks").stream().map(page -> page.key().page()).toList().equals(List.of("characters", "rig-editor", "equipment")), "RigWorks pages missing");
                    assertFocus(editor, CharacterWorkbenchPose.bodyFocus(mc.player, mc.getFrameTime()));
                    beforeWall = mc.gameRenderer.getMainCamera().getPosition();
                    var focus = CharacterWorkbenchPose.bodyFocus(mc.player, mc.getFrameTime());
                    var server = mc.getSingleplayerServer();
                    check(server != null, "Use a copied single-player acceptance world");
                    var dimension = mc.level.dimension();
                    wallTask = CompletableFuture.runAsync(() -> {
                        var level = server.getLevel(dimension);
                        for (double fraction : new double[]{.3, .6, .9}) {
                            BlockPos pos = BlockPos.containing(focus.lerp(beforeWall, fraction));
                            replaced.putIfAbsent(pos, level.getBlockState(pos));
                            level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
                        }
                    }, server);
                    captureAdditional(mc, "body-focus");
                }
                case 2 -> {
                    if (!wallTask.isDone()) { phase--; ticks=14; return; }
                    wallTask.join();
                    var editor = editor(mc);
                    check(mc.gameRenderer.getMainCamera().getPosition().distanceTo(beforeWall) < .01,
                            "Editor camera was pushed toward the player by a block");
                    assertFocus(editor, CharacterWorkbenchPose.bodyFocus(mc.player, mc.getFrameTime()));
                    restoreWall(mc);
                    editor.keyPressed(GLFW.GLFW_KEY_F, 0, 0);
                }
                case 3 -> {
                    var editor = editor(mc);
                    assertFocus(editor, observer.focus(HumanoidArm.RIGHT, mc.getFrameTime()));
                    check(CharacterWorkbenchPose.frozen(), "Workbench did not freeze character pose");
                    // Opening the editor enables third person. First-person entry has no resident player VBO yet.
                    if (OculusCompat.isShadersActive() && OculusEntityBufferCache.poseStatistics().buffers()
                            < LocalPlayerCharacter.currentAnimation().getScene().getMeshes().size()) { phase--; ticks = 14; return; }
                    uploads = OculusEntityBufferCache.poseStatistics().uploads();
                    dragTranslate(editor);
                    restoreFields(editor, right);
                    fields(editor).get(0).setValue(Float.toString(right.x() + .02f));
                    check(Math.abs(observer.get(HumanoidArm.RIGHT).x() - right.x() - .02f) < 1e-5, "Right preview not applied");
                    click(editor, "Left");
                    fields(editor).get(6).setValue(Float.toString(left.scale() * .9f));
                    check(Math.abs(observer.get(HumanoidArm.LEFT).scale() - left.scale() * .9f) < 1e-5, "Left preview not applied");
                    check(Math.abs(observer.get(HumanoidArm.RIGHT).x() - right.x() - .02f) < 1e-5, "Left edit changed right hand");
                    editor.onClose();
                }
                case 4 -> {
                    check(close(observer.get(HumanoidArm.RIGHT), right) && close(observer.get(HumanoidArm.LEFT), left),
                            "Closing editor did not revert unsaved changes");
                    check(mc.options.getCameraType() == previousCamera, "Editor camera type was not restored");
                    check(!CharacterWorkbenchPose.frozen(), "Closing editor retained frozen pose");
                    observer.cancel();
                    observer = new CharacterEquipmentEditorSession(LocalPlayerCharacter.equipmentEditorTarget());
                    EquipmentTransformScreen.open();
                    click(editor(mc), "Save");
                    check(Files.isRegularFile(observer.outputPath()), "Saved equipment JSON missing");
                    try (var reader = Files.newBufferedReader(observer.outputPath())) {
                        var saved = CharacterEquipmentDefinition.read(reader);
                        check(saved.rightHand() != null && saved.leftHand() != null, "Saved JSON lost a hand");
                        check(Math.abs(saved.rightHand().position().x() - right.x()) < 1e-6, "Saved right position differs");
                        check(Math.abs(saved.leftHand().scale() - left.scale()) < 1e-6, "Saved left scale differs");
                    }
                    yaw = mc.player.getYRot(); pitch = mc.player.getXRot();
                }
                case 5 -> {
                    var editor = editor(mc);
                    uploads = OculusEntityBufferCache.poseStatistics().uploads();
                    dispatches = OculusEntityBufferCache.gpuPoseStatistics().dispatches();
                    frozenCacheDraws = OculusEntityBufferCache.poseStatistics().draws();
                    frozenCacheHits = OculusEntityBufferCache.poseStatistics().hits();
                    editor.keyPressed(GLFW.GLFW_KEY_E, 0, 0);
                }
                case 6 -> {
                    var editor = editor(mc);
                    captureAdditional(mc, "rotation");
                    dragRotate(editor);
                    restoreFields(editor, right);
                    editor.setFocused(null);
                    editor.keyPressed(GLFW.GLFW_KEY_R, 0, 0);
                }
                case 7 -> {
                    var editor = editor(mc);
                    captureAdditional(mc, "scale");
                    var center = gizmo(editor).screenPivot();
                    check(center != null, "Scale pivot not projected");
                    editor.mouseClicked(center.x(), center.y(), 0);
                    editor.mouseDragged(center.x() + 12, center.y() - 12, 0, 12, -12);
                    editor.mouseReleased(center.x() + 12, center.y() - 12, 0);
                    check(Math.abs(observer.get(HumanoidArm.RIGHT).scale() - right.scale()) > .001f, "Scale handle did not edit item");
                    restoreFields(editor, right);
                    editor.setFocused(null);
                    editor.keyPressed(GLFW.GLFW_KEY_W, 0, 0);
                    editor.mouseClicked(30, 130, 0);
                    editor.mouseDragged(80, 140, 0, 50, 10);
                    editor.mouseReleased(80, 140, 0);
                    //? if forge {
                    editor.mouseScrolled(30, 80, 2);
                    //?}
                    //? if neoforge {
                    /*editor.mouseScrolled(30, 80, 0, 2);
                    *///?}
                    editor.keyPressed(GLFW.GLFW_KEY_F, 0, 0);
                }
                case 8 -> {
                    check(mc.player.getYRot() == yaw && mc.player.getXRot() == pitch, "Orbit changed player aim");
                    check(LocalPlayerCharacter.currentAnimation() == animation, "Editing reloaded character geometry");
                    check(OculusEntityBufferCache.poseStatistics().uploads() == uploads, "Editing rebuilt resident VBOs");
                    check(CharacterWorkbenchPose.frozen(), "Character pose thawed during editing");
                    if (OculusCompat.isShadersActive()) {
                        check(OculusEntityBufferCache.gpuPoseStatistics().dispatches() == dispatches,
                                "Frozen pose was unnecessarily recomputed on the GPU");
                        check(OculusEntityBufferCache.poseStatistics().draws() > frozenCacheDraws
                                        && OculusEntityBufferCache.poseStatistics().hits() > frozenCacheHits,
                                "Workbench fell back to CPU submission instead of drawing its frozen resident VBO");
                    }
                    capturing = true;
                    String file = "equipment-workbench-" + START + ".png";
                    Screenshot.grab(mc.gameDirectory, file, mc.getMainRenderTarget(), message -> mc.execute(() -> {
                        try {
                            check(Files.isRegularFile(mc.gameDirectory.toPath().resolve("screenshots").resolve(file)), "Editor screenshot missing");
                            complete(mc, null, file);
                        } catch (Exception failure) { complete(mc, failure, ""); }
                    }));
                }
                default -> throw new IllegalStateException("Unexpected acceptance phase");
            }
        } catch (Exception | LinkageError failure) { complete(mc, failure, ""); }
    }

    private static EquipmentTransformScreen editor(Minecraft mc) {
        check(mc.screen instanceof EquipmentTransformScreen, "Equipment screen did not open");
        return (EquipmentTransformScreen) mc.screen;
    }

    private static void assertFocus(EquipmentTransformScreen editor, Vec3 focus) throws ReflectiveOperationException {
        var projected = gizmo(editor).projectWorld(focus);
        check(projected != null, "Editor focus was not projected");
        double x = editor.viewportRight() * .5, y = (editor.height + LookDevNavigation.HEADER_HEIGHT) * .5;
        check(Math.abs(projected.x()-x) < 3 && Math.abs(projected.y()-y) < 3,
                "Editor focus drifted from viewport center: " + projected + " expected " + x + "," + y);
    }

    private static void restoreWall(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        if (server == null || mc.level == null || replaced.isEmpty()) return;
        var restore = Map.copyOf(replaced); replaced.clear();
        var dimension = mc.level.dimension();
        server.execute(() -> restore.forEach((pos, state) -> server.getLevel(dimension).setBlockAndUpdate(pos, state)));
    }

    private static List<EditBox> fields(EquipmentTransformScreen editor) {
        return editor.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).toList();
    }

    private static EquipmentTransformGizmo gizmo(EquipmentTransformScreen editor) throws ReflectiveOperationException {
        var field = EquipmentTransformScreen.class.getDeclaredField("gizmo");
        field.setAccessible(true);
        return (EquipmentTransformGizmo) field.get(editor);
    }

    private static void captureAdditional(Minecraft mc, String mode) {
        capturing = true;
        String file = "equipment-workbench-" + START + "-" + mode + ".png";
        Screenshot.grab(mc.gameDirectory, file, mc.getMainRenderTarget(), message -> mc.execute(() -> {
            if (!Files.isRegularFile(mc.gameDirectory.toPath().resolve("screenshots").resolve(file))) {
                complete(mc, new IllegalStateException("Missing " + mode + " screenshot"), "");
                return;
            }
            captures.add("screenshots/" + file);
            capturing = false;
        }));
    }

    private static void restoreFields(EquipmentTransformScreen editor, EditableTransform value) throws ReflectiveOperationException {
        float[] values = {value.x(), value.y(), value.z(), value.rx(), value.ry(), value.rz(), value.scale()};
        var fields = fields(editor);
        for (int i = 0; i < values.length; i++) fields.get(i).setValue(Float.toString(values[i]));
        var refresh = EquipmentTransformScreen.class.getDeclaredMethod("refreshFields");
        refresh.setAccessible(true);
        refresh.invoke(editor);
    }

    private static void dragTranslate(EquipmentTransformScreen editor) throws ReflectiveOperationException {
        var gizmo = gizmo(editor);
        var center = gizmo.screenPivot();
        check(center != null, "Translation pivot not projected");
        GizmoMath.Point selected = null;
        double longest = 0;
        for (int axis = 0; axis < 3; axis++) {
            var point = gizmo.screenHandle(axis);
            if (point == null || point.x() < 8 || point.x() > editor.viewportRight() - 8 || point.y() < LookDevNavigation.HEADER_HEIGHT || point.y() > editor.height - 8) continue;
            double length = Math.hypot(point.x() - center.x(), point.y() - center.y());
            if (length > longest) { longest = length; selected = point; }
        }
        check(selected != null && longest > 10, "No visible translation handle; pivot=" + center + " viewport=" + editor.viewportRight() + "x" + editor.height);
        double x = center.x() + (selected.x() - center.x()) * .8, y = center.y() + (selected.y() - center.y()) * .8;
        double dx = (selected.x() - center.x()) / longest * 16, dy = (selected.y() - center.y()) / longest * 16;
        var before = observer.get(HumanoidArm.RIGHT);
        editor.mouseClicked(x, y, 0);
        editor.mouseDragged(x + dx, y + dy, 0, dx, dy);
        editor.mouseReleased(x + dx, y + dy, 0);
        check(!close(before, observer.get(HumanoidArm.RIGHT)), "Translation arrow did not edit item");
    }

    private static void dragRotate(EquipmentTransformScreen editor) throws ReflectiveOperationException {
        var gizmo = gizmo(editor);
        var before = observer.get(HumanoidArm.RIGHT);
        boolean changed = false;
        for (int axis = 0; axis < 3 && !changed; axis++) {
            for (int step = 0; step < 12 && !changed; step++) {
                double angle = step * Math.PI / 6;
                var start = gizmo.screenRing(axis, angle);
                var end = gizmo.screenRing(axis, angle + .3);
                if (start == null || end == null || start.x() < 8 || start.x() > editor.viewportRight() - 8 || start.y() < LookDevNavigation.HEADER_HEIGHT
                        || start.y() > editor.height - 8 || Math.hypot(start.x() - end.x(), start.y() - end.y()) < 4) continue;
                editor.mouseClicked(start.x(), start.y(), 0);
                editor.mouseDragged(end.x(), end.y(), 0, end.x() - start.x(), end.y() - start.y());
                editor.mouseReleased(end.x(), end.y(), 0);
                changed = Math.abs(before.quaternion().dot(observer.get(HumanoidArm.RIGHT).quaternion())) < .99999f;
            }
        }
        check(changed, "Rotation ring did not edit item");
    }

    private static void click(EquipmentTransformScreen editor, String label) {
        editor.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> button.getMessage().getString().equals(label)).findFirst().orElseThrow().onPress();
    }

    private static boolean close(EditableTransform a, EditableTransform b) {
        return Math.abs(a.x() - b.x()) < 1e-6 && Math.abs(a.y() - b.y()) < 1e-6 && Math.abs(a.z() - b.z()) < 1e-6
                && Math.abs(a.scale() - b.scale()) < 1e-6 && Math.abs(a.quaternion().dot(b.quaternion())) > .99999;
    }

    private static void complete(Minecraft mc, Throwable failure, String screenshot) {
        if (finished) return;
        restoreWall(mc);
        if(mc.player!=null&&originalMain!=null){
            mc.player.setItemInHand(InteractionHand.MAIN_HAND,originalMain);mc.player.setItemInHand(InteractionHand.OFF_HAND,originalOff);
        }
        finished = true;
        var result = new LinkedHashMap<String, Object>();
        result.put("success", failure == null);
        result.put("failure", failure == null ? "" : failure.toString());
        result.put("profile", LocalPlayerCharacter.settings().profile());
        result.put("shadersActive", OculusCompat.isShadersActive());
        result.put("fpsAtCapture", mc.getFps());
        result.put("poseCache", OculusEntityBufferCache.poseStatistics());
        result.put("gpuPoseCache", OculusEntityBufferCache.gpuPoseStatistics());
        result.put("output", observer == null ? "" : observer.outputPath().toString());
        result.put("screenshot", screenshot.isEmpty() ? "" : "screenshots/" + screenshot);
        result.put("additionalScreenshots", captures);
        result.put("frozenResidentDraws", OculusEntityBufferCache.poseStatistics().draws() - frozenCacheDraws);
        result.put("frozenPoseCacheHits", OculusEntityBufferCache.poseStatistics().hits() - frozenCacheHits);
        result.put("frozenComputeDispatches", OculusEntityBufferCache.gpuPoseStatistics().dispatches() - dispatches);
        try {
            Files.writeString(mc.gameDirectory.toPath().resolve("equipment-workbench-smoke-result.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(result));
        } catch (Exception exception) { AssimpLoader.LOGGER.error("Cannot write workbench acceptance result", exception); }
        if (failure == null) AssimpLoader.LOGGER.info("[EquipmentWorkbenchSmoke] SUCCESS; editor/client remain open");
        else {
            AssimpLoader.LOGGER.error("[EquipmentWorkbenchSmoke] FAILED; client remains open", failure);
            Screenshot.grab(mc.gameDirectory, "equipment-workbench-" + START + "-failed.png", mc.getMainRenderTarget(), message -> {});
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
