package cn.minerealms.rigworks.character.client;

import cn.minerealms.assimploader.client.ui.FlatUi;
import cn.minerealms.rigworks.ysm.asset.RwymNative;
import cn.minerealms.rigworks.ysm.runtime.YsmModelSource;
import cn.minerealms.rigworks.ysm.runtime.YsmRuntimeBridge;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;

/** Resource-pack profile selection; model loading stays in the existing async owner. */
public final class CharacterSelectionScreen extends Screen {
    private final Screen parent;
    private final List<Button> rows = new ArrayList<>();
    private final List<Button> motionModes = new ArrayList<>();
    private List<ResourceLocation> profiles = List.of();
    private Map<String,String> profileNames = Map.of();
    private EditBox profile;
    private Button apply, previous, next, importYsm;
    private int first, left, panelWidth, visibleRows, listY, footerY, controlsY, motionY, profileY;
    private boolean compact, narrow, ysmOwned, motionInfoInitialized;
    private YsmModelSource.YsmModelView displayedYsm;
    private String sourceModel = "", motionDetail = "", motionTooltip = "";
    private CompletableFuture<String> converterCheck;
    private String converterStatus = "Checking C++ converter";
    private boolean converterReady;

    public CharacterSelectionScreen(Screen parent) {
        super(Component.literal("Character"));
        this.parent = parent;
    }

    @Override protected void init() {
        panelWidth = Math.min(760, width - 32);
        left = (width - panelWidth) / 2;
        compact = height < 360;
        narrow = panelWidth < 500;
        controlsY = compact ? 40 : 62;
        motionY = compact ? 94 : 142;
        profileY = compact ? 122 : 184;
        cn.minerealms.assimploader.client.ui.LookDevNavigation.install(this,widget->addRenderableWidget(widget),
                RigWorksClientSetup.CHARACTERS);
        String value = profile == null ? LocalPlayerCharacter.settings().profile() : profile.getValue();
        int toggleWidth = narrow ? (panelWidth - 40) / 2 : (panelWidth - 44) / 3;
        addRenderableWidget(FlatUi.toggle(left, controlsY, toggleWidth, 24, Component.literal(narrow ? "Avatar" : "Character"),
                () -> LocalPlayerCharacter.settings().enabled(), button -> {
                    cn.minerealms.rigworks.RigWorksConfig.update(edit->edit.set(cn.minerealms.rigworks.RigWorksConfig.CLIENT.enabled,
                            !LocalPlayerCharacter.settings().enabled()));
                    LocalPlayerCharacter.refreshConfiguration(); LocalPlayerCharacter.reload();
                })).setTooltip(Tooltip.create(Component.literal("Enable custom character")));
        addRenderableWidget(FlatUi.toggle(left + toggleWidth + 6, controlsY, toggleWidth, 24, Component.literal(narrow ? "Physics" : "Rig physics"),
                LocalPlayerCharacter::physicsEnabled,
                button -> LocalPlayerCharacter.setPhysicsEnabled(!LocalPlayerCharacter.physicsEnabled())))
                .setTooltip(Tooltip.create(Component.literal("Enable character rigid-body physics")));
        if (!narrow) addRenderableWidget(FlatUi.toggle(left + 2 * (toggleWidth + 6),controlsY,toggleWidth,24,Component.literal("Contacts"),
                LocalPlayerCharacter::handContactsEnabled,button->LocalPlayerCharacter.setHandContactsEnabled(!LocalPlayerCharacter.handContactsEnabled())))
                .setTooltip(Tooltip.create(Component.literal("Support-hand contacts from equipment JSON")));
        addRenderableWidget(FlatUi.icon(left + panelWidth - 26, controlsY, FlatUi.Icon.RELOAD,
                Component.literal("Reload character"), button -> LocalPlayerCharacter.reloadSaved()));
        int sharingY=controlsY+(compact?26:34);
        if (narrow) {
            int thirdWidth = (panelWidth - 12) / 3;
            addRenderableWidget(FlatUi.toggle(left,sharingY,thirdWidth,24,Component.literal("Share"),
                    CharacterVisualSharing::uploadEnabled,b->CharacterVisualSharing.uploadEnabled(!CharacterVisualSharing.uploadEnabled())))
                    .setTooltip(Tooltip.create(Component.literal("Uploads your configuration and avatar behavior; requires RigWorks on the server")));
            addRenderableWidget(FlatUi.toggle(left+thirdWidth+6,sharingY,thirdWidth,24,Component.literal("Others"),
                    CharacterVisualSharing::remoteEnabled,b->CharacterVisualSharing.remoteEnabled(!CharacterVisualSharing.remoteEnabled())))
                    .setTooltip(Tooltip.create(Component.literal("Display other characters")));
            addRenderableWidget(FlatUi.toggle(left+2*(thirdWidth+6),sharingY,thirdWidth,24,Component.literal("Hands"),
                    LocalPlayerCharacter::handContactsEnabled,b->LocalPlayerCharacter.setHandContactsEnabled(!LocalPlayerCharacter.handContactsEnabled())))
                    .setTooltip(Tooltip.create(Component.literal("Support-hand contacts from equipment JSON")));
        } else {
            int sharingWidth=(panelWidth-10)/2;
            addRenderableWidget(FlatUi.toggle(left,sharingY,sharingWidth,24,Component.literal("Show my character to others"),
                    CharacterVisualSharing::uploadEnabled,b->CharacterVisualSharing.uploadEnabled(!CharacterVisualSharing.uploadEnabled())))
                    .setTooltip(Tooltip.create(Component.literal("Uploads your configuration and avatar behavior; requires RigWorks on the server")));
            addRenderableWidget(FlatUi.toggle(left+sharingWidth+10,sharingY,sharingWidth,24,Component.literal("Display other characters"),
                    CharacterVisualSharing::remoteEnabled,b->CharacterVisualSharing.remoteEnabled(!CharacterVisualSharing.remoteEnabled())));
        }

        var modes = cn.minerealms.rigworks.RigWorksConfig.CharacterAnimationMode.values();
        int modeWidth = (panelWidth - 4) / modes.length;
        motionModes.clear();
        for (int index = 0; index < modes.length; index++) {
            var mode = modes[index];
            String label = switch (mode) { case VANILLA -> "Vanilla"; case SCRIPTED -> "Scripted"; case CLIPS -> "Clips"; };
            motionModes.add(addRenderableWidget(FlatUi.choice(left + index * (modeWidth + 2), motionY,
                    index == modes.length - 1 ? panelWidth - index * (modeWidth + 2) : modeWidth, 26,
                    Component.literal(label), () -> LocalPlayerCharacter.animationMode() == mode,
                    button -> LocalPlayerCharacter.setAnimationMode(mode))));
        }

        profile = FlatUi.field(font, left, profileY, panelWidth - 82, 26, Component.literal("Profile resource"));
        profile.setMaxLength(512);
        profile.setValue(value);
        profile.setHint(Component.literal("namespace:models/character/profile.json"));
        profile.setResponder(ignored -> { updateApply(); updateRows(); });
        addRenderableWidget(profile);
        apply = addRenderableWidget(FlatUi.button(left + panelWidth - 76, profileY, 76, 26, Component.literal("Apply"),
                button -> applyProfile()));

        rows.clear();
        refreshProfiles();
        listY = compact ? 162 : 234;
        footerY = height - (compact ? 28 : 42);
        visibleRows = Math.max(0, Math.min(12, (footerY - listY - (compact ? 20 : 28)) / 30));
        for (int index = 0; index < visibleRows; index++) {
            final int row = index;
            rows.add(addRenderableWidget(FlatUi.row(left, listY + index * 30, panelWidth, 28, Component.empty(),
                    () -> first + row < profiles.size() && profiles.get(first + row).toString().equals(profile.getValue()),
                    button -> {
                        int selected = first + row;
                        if (selected < profiles.size()) profile.setValue(profiles.get(selected).toString());
                    })));
        }
        previous = addRenderableWidget(FlatUi.icon(left, footerY - 1, FlatUi.Icon.PREVIOUS,
                Component.literal("Previous profiles"), button -> scroll(-visibleRows)));
        next = addRenderableWidget(FlatUi.icon(left + 30, footerY - 1, FlatUi.Icon.NEXT,
                Component.literal("Next profiles"), button -> scroll(visibleRows)));
        if (narrow) {
            importYsm = addRenderableWidget(FlatUi.icon(left + panelWidth - 90, footerY - 1, FlatUi.Icon.IMPORT,
                    Component.literal("Import YSM"), button -> beginYsmImport()));
            addRenderableWidget(FlatUi.icon(left + panelWidth - 58, footerY - 1, FlatUi.Icon.OPEN,
                    Component.literal("Import avatar"), button -> RigWorksClientSetup.importCharacter()));
            addRenderableWidget(FlatUi.icon(left + panelWidth - 26, footerY - 1, FlatUi.Icon.CLOSE,
                    Component.translatable("gui.done"), button -> onClose()));
        } else {
            importYsm = addRenderableWidget(FlatUi.button(left + panelWidth - 280, footerY - 1, 96, 26,
                    Component.literal("Import YSM"), button -> beginYsmImport()));
            addRenderableWidget(FlatUi.button(left + panelWidth - 178, footerY - 1, 96, 26,
                    Component.literal("Import avatar"), button -> RigWorksClientSetup.importCharacter()));
            addRenderableWidget(FlatUi.button(left + panelWidth - 76, footerY - 1, 76, 26,
                    Component.translatable("gui.done"), button -> onClose()));
        }
        updateRows(); updateApply(); updateMotion(); checkConverter();
    }

    private void updateApply() {
        if (apply != null) apply.active = ResourceLocation.tryParse(profile.getValue()) != null;
    }

    private void refreshProfiles() {
        var ids = new TreeSet<ResourceLocation>();
        var names = new HashMap<String,String>();
        minecraft.getResourceManager().listResources("models", id -> id.getPath().endsWith("/profile.json"))
                .keySet().forEach(ids::add);
        for (var imported : CharacterVisualOverrides.importedProfiles()) {
            ResourceLocation id = ResourceLocation.tryParse(imported.id());
            if (id != null) { ids.add(id); names.put(imported.id(), imported.name()); }
        }
        ResourceLocation selected = ResourceLocation.tryParse(LocalPlayerCharacter.settings().profile());
        if (selected != null) ids.add(selected);
        profiles = List.copyOf(ids);
        profileNames = Map.copyOf(names);
        updateRows();
    }

    private void beginYsmImport() {
        String valueAtStart = profile.getValue();
        RigWorksClientSetup.importYsmCharacter(imported -> {
            if (minecraft.screen != this) return;
            refreshProfiles();
            if (profile.getValue().equals(valueAtStart)) {
                profile.setValue(imported);
                int selected = profiles.indexOf(ResourceLocation.tryParse(imported));
                if (selected >= 0 && visibleRows > 0)
                    first = Math.max(0, Math.min(Math.max(0, profiles.size() - visibleRows), selected / visibleRows * visibleRows));
                updateRows();
            }
        });
    }

    private void applyProfile() {
        ResourceLocation selected = ResourceLocation.tryParse(profile.getValue());
        if (selected == null) return;
        cn.minerealms.rigworks.RigWorksConfig.update(edit->edit.set(cn.minerealms.rigworks.RigWorksConfig.CLIENT.profile,selected.toString()));
        LocalPlayerCharacter.refreshConfiguration(); LocalPlayerCharacter.reload();
    }

    private void scroll(int amount) {
        first = Math.max(0, Math.min(Math.max(0, profiles.size() - visibleRows), first + amount));
        updateRows();
    }

    private void updateRows() {
        first = Math.max(0, Math.min(Math.max(0, profiles.size() - visibleRows), first));
        for (int index = 0; index < rows.size(); index++) {
            Button button = rows.get(index);
            int entry = first + index;
            button.visible = entry < profiles.size();
            if (button.visible) {
                String id = profiles.get(entry).toString();
                String name = profileNames.getOrDefault(id, id);
                button.setMessage(Component.literal(FlatUi.clip(font, name, panelWidth - 24)));
                button.setTooltip(Tooltip.create(Component.literal(name.equals(id) ? id : name + "\n" + id)));
            }
        }
        if (previous != null) previous.active = first > 0;
        if (next != null) next.active = first + visibleRows < profiles.size();
    }

    @Override public void tick() {
        //? if forge {
        profile.tick();
        //?}
        updateMotion();
    }

    private void updateMotion() {
        var animation = LocalPlayerCharacter.currentAnimation();
        var model = animation == null ? null : YsmRuntimeBridge.modelForScene(animation.getScene());
        var current = LocalPlayerCharacter.currentProfile();
        String source = current == null ? "" : current.model();
        boolean owned = model != null || source.endsWith(".rwym");
        if (!motionInfoInitialized || model != displayedYsm || !source.equals(sourceModel) || owned != ysmOwned) {
            motionInfoInitialized = true;
            displayedYsm = model;
            sourceModel = source;
            ysmOwned = owned;
            if (owned) {
                motionDetail = model == null ? "Loading asset controllers" : model.animations().size() + " clips / " + model.controllers().size() + " controllers";
                var categories = new TreeSet<String>();
                if (model != null) for (var clip : model.animations()) categories.add(clip.category());
                String driver = model == null ? "waiting for asset"
                        : model.controllers().isEmpty() ? "YSM default actions" : "YSM controllers + default actions";
                motionTooltip = "Source: YSM\nDriver: " + driver
                        + "\nAsset: " + source + "\nCache: " + (source.endsWith(".rwym")
                        ? "RWYM " + cn.minerealms.rigworks.ysm.asset.RwymAssetCache.CONVERTER_REVISION : "legacy import")
                        + (categories.isEmpty() ? "" : "\nCategories: " + String.join(", ", categories));
            } else {
                motionDetail = "";
                motionTooltip = "";
            }
        }
        for (var mode : motionModes) mode.visible = mode.active = !owned;
    }

    private void checkConverter() {
        importYsm.active = converterReady;
        updateConverterTooltip();
        if (converterCheck != null) return;
        var client = minecraft;
        converterCheck = CompletableFuture.supplyAsync(() -> {
            if (RwymNative.available()) return "C++ converter ready";
            return "C++ converter unavailable\n" + RwymNative.failure();
        });
        converterCheck.whenComplete((status, error) -> client.execute(() -> {
            converterReady = error == null && RwymNative.available();
            converterStatus = error == null ? status : "C++ converter unavailable\n" + error.getMessage();
            if (importYsm != null) { importYsm.active = converterReady; updateConverterTooltip(); }
        }));
    }

    private void updateConverterTooltip() {
        importYsm.setTooltip(Tooltip.create(Component.literal("Import YSM\n" + converterStatus)));
    }

    //? if neoforge {
    /*
    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}
    *///?}

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, FlatUi.BACKGROUND);
        cn.minerealms.assimploader.client.ui.LookDevNavigation.render(graphics,width);
        if (!compact) graphics.drawString(font, ysmOwned ? "Motion / YSM source" : "Motion", left, motionY - 11, FlatUi.MUTED, false);
        if (ysmOwned) {
            graphics.fill(left, motionY, left + panelWidth, motionY + 26, FlatUi.SURFACE);
            graphics.fill(left, motionY, left + 2, motionY + 26, FlatUi.ACCENT);
            String driver = narrow ? "YSM driver" : "YSM asset controllers";
            graphics.drawString(font, driver, left + 10, motionY + 9, FlatUi.ACCENT, false);
            int detailX = left + Math.min(panelWidth / 2, font.width(driver) + 28);
            graphics.drawString(font, FlatUi.clip(font, motionDetail, panelWidth - (detailX - left) - 10), detailX, motionY + 9, FlatUi.MUTED, false);
        }
        if (!compact) graphics.drawString(font, "Profile", left, profileY - 11, FlatUi.MUTED, false);
        graphics.drawString(font, "Available profiles", left, listY - 13, FlatUi.MUTED, false);
        String count = profiles.isEmpty() || visibleRows == 0 ? Integer.toString(profiles.size()) : (first + 1) + "-" + Math.min(profiles.size(), first + visibleRows) + " / " + profiles.size();
        graphics.drawString(font, count, left + panelWidth - font.width(count), listY - 13, FlatUi.MUTED, false);
        if (profiles.isEmpty()) graphics.drawString(font, "No character profiles", left + 9, listY + 9, FlatUi.MUTED, false);
        int statusY = footerY - (compact ? 14 : 17);
        graphics.fill(left, statusY - 4, left + panelWidth, statusY - 3, FlatUi.LINE);
        var diagnostics = LocalPlayerCharacter.diagnostics();
        String status = diagnostics.status() + (ysmOwned ? " / YSM driver" : diagnostics.selectedAnimation().isBlank() ? "" : " / " + diagnostics.selectedAnimation());
        if (!diagnostics.failure().isBlank()) status += " / ERROR";
        graphics.drawString(font, FlatUi.clip(font, status, panelWidth), left, statusY,
                diagnostics.status() == LocalPlayerCharacter.Status.FAILED || !diagnostics.failure().isBlank() ? FlatUi.ERROR : FlatUi.MUTED, false);
        super.render(graphics, mouseX, mouseY, partialTick);
        if (ysmOwned && mouseX >= left && mouseX < left + panelWidth && mouseY >= motionY && mouseY < motionY + 26)
            graphics.renderTooltip(font, font.split(Component.literal(motionTooltip), Math.min(panelWidth, 420)), mouseX, mouseY);
        if (!diagnostics.failure().isBlank() && mouseX >= left && mouseX < left + panelWidth
                && mouseY >= statusY - 4 && mouseY < statusY + 12)
            graphics.renderTooltip(font, font.split(Component.literal(diagnostics.failure()), panelWidth), mouseX, mouseY);
    }

    //? if forge {
    @Override public boolean mouseScrolled(double x, double y, double amount) {
        return scrollProfiles(x, y, amount) || super.mouseScrolled(x, y, amount);
    }
    //?}
    //? if neoforge {
    /*
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        return scrollProfiles(x, y, vertical) || super.mouseScrolled(x, y, horizontal, vertical);
    }
    *///?}

    private boolean scrollProfiles(double x, double y, double amount) {
        if (amount != 0 && x >= left && x < left + panelWidth && y >= listY && y < listY + visibleRows * 30) {
            scroll(-(int) Math.signum(amount)); return true;
        }
        return false;
    }

    @Override public void onClose() { cn.minerealms.assimploader.client.ui.LookDevNavigation.close(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
