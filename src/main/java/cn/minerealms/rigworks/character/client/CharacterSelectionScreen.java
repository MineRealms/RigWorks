package cn.minerealms.rigworks.character.client;

import cn.minerealms.assimploader.client.ui.FlatUi;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** Resource-pack and local profile selection; model loading stays in the existing async owner. */
public final class CharacterSelectionScreen extends Screen {
    private final Screen parent;
    private final List<Button> rows = new ArrayList<>();
    private List<ResourceLocation> profiles = List.of();
    private EditBox profile;
    private Button apply, remove, previous, next;
    private int first, left, panelWidth, visibleRows, listY, footerY, controlsY, motionY, profileY;
    private boolean compact;
    private String notice = "";

    public CharacterSelectionScreen(Screen parent) {
        super(Component.literal("Character"));
        this.parent = parent;
    }

    @Override protected void init() {
        panelWidth = Math.min(760, width - 32);
        left = (width - panelWidth) / 2;
        compact = height < 360;
        controlsY = compact ? 54 : 62;
        motionY = compact ? 120 : 142;
        profileY = compact ? 154 : 184;
        cn.minerealms.assimploader.client.ui.LookDevNavigation.install(this,widget->addRenderableWidget(widget),
                RigWorksClientSetup.CHARACTERS);
        String value = profile == null ? LocalPlayerCharacter.settings().profile() : profile.getValue();
        int toggleWidth = Math.max(64, (panelWidth - 44) / 3);
        addRenderableWidget(FlatUi.toggle(left, controlsY, toggleWidth, 26, Component.literal("Character"),
                () -> LocalPlayerCharacter.settings().enabled(), button -> {
                    cn.minerealms.rigworks.RigWorksConfig.update(edit->edit.set(cn.minerealms.rigworks.RigWorksConfig.CLIENT.enabled,
                            !LocalPlayerCharacter.settings().enabled()));
                    LocalPlayerCharacter.refreshConfiguration(); LocalPlayerCharacter.reload();
                })).setTooltip(Tooltip.create(Component.literal("Enable custom character")));
        addRenderableWidget(FlatUi.toggle(left + toggleWidth + 6, controlsY, toggleWidth, 26, Component.literal("Rig physics"),
                LocalPlayerCharacter::physicsEnabled,
                button -> LocalPlayerCharacter.setPhysicsEnabled(!LocalPlayerCharacter.physicsEnabled())))
                .setTooltip(Tooltip.create(Component.literal("Enable character rigid-body physics")));
        addRenderableWidget(FlatUi.toggle(left + 2 * (toggleWidth + 6),controlsY,toggleWidth,26,Component.literal("Contacts"),
                LocalPlayerCharacter::handContactsEnabled,button->LocalPlayerCharacter.setHandContactsEnabled(!LocalPlayerCharacter.handContactsEnabled())))
                .setTooltip(Tooltip.create(Component.literal("Support-hand contacts from equipment JSON")));
        addRenderableWidget(FlatUi.icon(left + panelWidth - 26, controlsY, FlatUi.Icon.RELOAD,
                Component.literal("Reload character"), button -> {
                    reloadProfiles();
                    LocalPlayerCharacter.reloadSaved();
                }));
        int sharingY=controlsY+34,sharingWidth=(panelWidth-10)/2;
        addRenderableWidget(FlatUi.toggle(left,sharingY,sharingWidth,24,Component.literal("Show my character to others"),
                CharacterVisualSharing::uploadEnabled,b->CharacterVisualSharing.uploadEnabled(!CharacterVisualSharing.uploadEnabled())))
                .setTooltip(Tooltip.create(Component.literal("Uploads your configuration and avatar behavior; requires RigWorks on the server")));
        addRenderableWidget(FlatUi.toggle(left+sharingWidth+10,sharingY,sharingWidth,24,Component.literal("Display other characters"),
                CharacterVisualSharing::remoteEnabled,b->CharacterVisualSharing.remoteEnabled(!CharacterVisualSharing.remoteEnabled())));

        var modes = cn.minerealms.rigworks.RigWorksConfig.CharacterAnimationMode.values();
        int modeWidth = (panelWidth - 4) / modes.length;
        for (int index = 0; index < modes.length; index++) {
            var mode = modes[index];
            String label = switch (mode) { case VANILLA -> "Vanilla"; case SCRIPTED -> "Scripted"; case CLIPS -> "Clips"; };
            addRenderableWidget(FlatUi.choice(left + index * (modeWidth + 2), motionY,
                    index == modes.length - 1 ? panelWidth - index * (modeWidth + 2) : modeWidth, 26,
                    Component.literal(label), () -> LocalPlayerCharacter.animationMode() == mode,
                    button -> LocalPlayerCharacter.setAnimationMode(mode)));
        }

        profile = FlatUi.field(font, left, profileY, panelWidth - 114, 26, Component.literal("Profile resource"));
        profile.setMaxLength(512);
        profile.setValue(value);
        profile.setHint(Component.literal("namespace:models/character/profile.json"));
        profile.setResponder(ignored -> { updateApply(); updateRows(); });
        addRenderableWidget(profile);
        remove = addRenderableWidget(FlatUi.icon(left + panelWidth - 108, profileY, FlatUi.Icon.MINUS,
                Component.literal("Remove local profile"), button -> removeProfile()));
        apply = addRenderableWidget(FlatUi.button(left + panelWidth - 76, profileY, 76, 26, Component.literal("Apply"),
                button -> applyProfile()));

        profiles = collectProfiles();
        listY = compact ? 198 : 234;
        footerY = height - (compact ? 28 : 58);
        visibleRows = Math.max(0, Math.min(12, (footerY - listY - 12) / 30));
        rows.clear();
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
        addRenderableWidget(FlatUi.button(left + panelWidth - 172, footerY - 1, 90, 26,
                Component.literal("Import avatar"), button -> RigWorksClientSetup.importCharacter()));
        addRenderableWidget(FlatUi.button(left + panelWidth - 76, footerY - 1, 76, 26,
                Component.translatable("gui.done"), button -> onClose()));
        updateRows(); updateApply();
    }

    private void updateApply() {
        if (apply != null) apply.active = ResourceLocation.tryParse(profile.getValue()) != null;
        if (remove != null) remove.active = CharacterVisualOverrides.hasSaved(profile.getValue());
    }

    private List<ResourceLocation> collectProfiles() {
        var ids = new TreeSet<String>();
        ids.addAll(minecraft.getResourceManager().listResources("models", id -> id.getPath().endsWith("/profile.json"))
                .keySet().stream().map(ResourceLocation::toString).toList());
        ids.addAll(CharacterVisualOverrides.savedProfiles());
        return ids.stream().map(ResourceLocation::tryParse).filter(value -> value != null).toList();
    }

    private void reloadProfiles() {
        List<ResourceLocation> latest = collectProfiles();
        if (!latest.equals(profiles)) {
            profiles = latest;
            updateRows();
        }
    }

    boolean containsProfile(ResourceLocation value) {
        return value != null && profiles.contains(value);
    }

    private void applyProfile() {
        ResourceLocation selected = ResourceLocation.tryParse(profile.getValue());
        if (selected == null) return;
        notice = "";
        cn.minerealms.rigworks.RigWorksConfig.update(edit->edit.set(cn.minerealms.rigworks.RigWorksConfig.CLIENT.profile,selected.toString()));
        LocalPlayerCharacter.refreshConfiguration(); LocalPlayerCharacter.reload();
    }

    private void removeProfile() {
        String selected = profile.getValue();
        if (!CharacterVisualOverrides.hasSaved(selected)) return;
        minecraft.setScreen(new RemoveProfileScreen(selected));
    }

    private void removeConfirmed(String selected) {
        try {
            CharacterVisualOverrides.remove(selected);
            CharacterVisualSharing.localProfileRemoved(selected);
            if (selected.equals(LocalPlayerCharacter.settings().profile())) {
                cn.minerealms.rigworks.RigWorksConfig.update(edit -> edit.set(
                        cn.minerealms.rigworks.RigWorksConfig.CLIENT.profile, LocalPlayerCharacter.DEFAULT_PROFILE));
                LocalPlayerCharacter.refreshConfiguration(); LocalPlayerCharacter.reload();
            }
            profile.setValue(LocalPlayerCharacter.settings().profile());
            notice = "";
        } catch (java.io.IOException error) {
            notice = "Cannot remove local profile " + selected + ": " + error.getMessage();
        }
        minecraft.setScreen(this);
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
                button.setMessage(Component.literal(FlatUi.clip(font, id, panelWidth - 24)));
                button.setTooltip(Tooltip.create(Component.literal(profileTooltip(id))));
            }
        }
        if (previous != null) previous.active = first > 0;
        if (next != null) next.active = first + visibleRows < profiles.size();
    }

    private String profileTooltip(String id) {
        var config = CharacterVisualOverrides.load(id);
        if (config == null) return id;
        String model = config.profile().model();
        var source = CharacterVisualSharing.textureSource(id);
        if (source == null) source = CharacterVisualOverrides.asset(model);
        return id + "\nModel: " + model + "\nSource: " + (source == null ? "resource:" + model : source)
                + (source != null && !java.nio.file.Files.isRegularFile(source) ? " (missing)" : "")
                + "\nSHA-256: " + config.modelHash();
    }

    @Override public void tick() {
        //? if forge {
        profile.tick();
        //?}
    }

    //? if neoforge {
    /*
    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}
    *///?}

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, FlatUi.BACKGROUND);
        cn.minerealms.assimploader.client.ui.LookDevNavigation.render(graphics,width);
        graphics.drawString(font, "Motion", left, motionY - 11, FlatUi.MUTED, false);
        graphics.drawString(font, "Profile", left, profileY - 11, FlatUi.MUTED, false);
        graphics.drawString(font, "Available profiles", left, listY - 13, FlatUi.MUTED, false);
        String count = profiles.isEmpty() ? "0" : (first + 1) + "-" + Math.min(profiles.size(), first + visibleRows) + " / " + profiles.size();
        graphics.drawString(font, count, left + panelWidth - font.width(count), listY - 13, FlatUi.MUTED, false);
        if (profiles.isEmpty()) graphics.drawString(font, "No character profiles", left + 9, listY + 9, FlatUi.MUTED, false);
        graphics.fill(left, footerY - 10, left + panelWidth, footerY - 9, FlatUi.LINE);
        var diagnostics = LocalPlayerCharacter.diagnostics();
        String status = diagnostics.status() + (diagnostics.selectedAnimation().isBlank() ? "" : "  /  " + diagnostics.selectedAnimation());
        graphics.drawString(font, FlatUi.clip(font, status, Math.max(0, panelWidth - 246)), left + 66, footerY + 8,
                diagnostics.status() == LocalPlayerCharacter.Status.FAILED ? FlatUi.ERROR : FlatUi.MUTED, false);
        String failure = notice.isBlank() ? diagnostics.failure() : notice;
        if (!compact && !failure.isBlank()) graphics.drawString(font,
                FlatUi.clip(font, failure, panelWidth), left, height - 17, FlatUi.ERROR, false);
        super.render(graphics, mouseX, mouseY, partialTick);
        if (!failure.isBlank() && mouseX >= left && mouseX < left + panelWidth && mouseY >= footerY)
            graphics.renderTooltip(font, font.split(Component.literal(failure), panelWidth), mouseX, mouseY);
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

    private final class RemoveProfileScreen extends Screen {
        private final String selected;
        private RemoveProfileScreen(String selected) {
            super(Component.literal("Remove Local Profile"));
            this.selected = selected;
        }
        @Override protected void init() {
            int buttonWidth = Math.min(150, (width - 48) / 2);
            addRenderableWidget(FlatUi.button(width / 2 - buttonWidth - 4, height / 2 + 44, buttonWidth, 26,
                    Component.literal("Remove"), button -> removeConfirmed(selected)));
            addRenderableWidget(FlatUi.button(width / 2 + 4, height / 2 + 44, buttonWidth, 26,
                    Component.translatable("gui.cancel"), button -> onClose()));
        }
        //? if neoforge {
        /*
        @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}
        *///?}
        @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(0, 0, width, height, FlatUi.BACKGROUND);
            graphics.drawCenteredString(font, title, width / 2, height / 2 - 60, FlatUi.TEXT);
            int textWidth = Math.min(560, width - 32);
            graphics.drawString(font, FlatUi.clip(font, selected, textWidth), (width - textWidth) / 2,
                    height / 2 - 36, FlatUi.ACCENT, false);
            graphics.drawWordWrap(font, Component.literal("Remove this saved character configuration? Model and behavior files will be kept."),
                    (width - textWidth) / 2, height / 2, textWidth, FlatUi.TEXT);
            super.render(graphics, mouseX, mouseY, partialTick);
        }
        @Override public void onClose() { minecraft.setScreen(CharacterSelectionScreen.this); }
        @Override public boolean isPauseScreen() { return false; }
    }
}
