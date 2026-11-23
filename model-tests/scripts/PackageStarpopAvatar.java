package cn.minerealms.rigworks.character.client;

import cn.minerealms.assimploader.workbench.io.WorkbenchProjectStore;
import cn.minerealms.rigworks.character.core.CharacterVisualConfig;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Builds the sample archive through the same store and profile reader used by the Workbench. */
public final class PackageStarpopAvatar {
    private static final String MODEL = "starpop.fbx";
    private static final List<String> ASSETS = List.of(MODEL, "profile.json", "rig.json", "equipment.json", "starpop.instance.json");
    private static final UUID COPY_ID = UUID.nameUUIDFromBytes("rigworks:sample/starpop".getBytes(StandardCharsets.UTF_8));

    public static void main(String[] args) throws IOException {
        if (args.length != 2) throw new IllegalArgumentException("Usage: PackageStarpopAvatar <asset-dir> <output.rig.zip>");
        var output = Path.of(args[1]).toAbsolutePath().normalize();
        if (!output.getFileName().toString().endsWith(".rig.zip")) throw new IOException("Expected an output ending in .rig.zip");
        var parent = Files.createDirectories(output.getParent());
        try (var workspace = new PackageWorkspace(Files.createTempDirectory(parent, ".starpop-package-"))) {
            var source = workspace.root().resolve("input");
            Files.createDirectory(source);
            for (var file : ASSETS) Files.copy(Path.of(args[0]).resolve(file), source.resolve(file));

            var sourceModel = source.resolve(MODEL);
            var hash = CharacterLocalModelAssets.hash(sourceModel);
            var modelId = CharacterLocalModelAssets.identity(COPY_ID, hash, sourceModel).toString();
            var selection = CharacterWorkbenchSources.loadLocal(sourceModel, modelId, hash);
            if (selection == null || selection.visual().rig() == null || selection.visual().profile().equipmentDefinition() == null)
                throw new IOException("profile.json must describe starpop.fbx and reference rig.json and equipment.json");
            var visual = selection.visual();
            var extension = new JsonObject();
            extension.addProperty("version", 1);
            extension.addProperty("profileId", "rigworks:avatars/" + COPY_ID + "/profile.json");
            extension.addProperty("instanceId", COPY_ID.toString());
            extension.addProperty("sourceProfileId", selection.profileId());
            extension.addProperty("scriptsIncluded", false);
            extension.add("visual", CharacterVisualConfig.parseBounded(visual.json()));

            var store = new WorkbenchProjectStore(workspace.root().resolve("projects"));
            var project = store.importAsset(sourceModel, source);
            var archive = store.exportZip(project, project.document().withExtension("rigworks", extension), workspace.root().resolve("sample.rig.zip"));
            var imported = store.importZip(archive);
            var restored = imported.document().extensions().getAsJsonObject("rigworks");
            if (!extension.equals(restored)) throw new IOException("RigWorks metadata changed in package round trip");
            var restoredVisual = CharacterVisualConfig.parse(restored.get("visual").toString());
            if (!visual.digest().equals(restoredVisual.digest())) throw new IOException("The restored RigWorks definition differs from the authored definition");

            var restoredModel = WorkbenchProjectStore.resolvePortable(imported.directory(), imported.document().sourcePath());
            var files = new JsonObject();
            for (var file : ASSETS) {
                var before = source.resolve(file);
                var after = restoredModel.resolveSibling(file);
                if (Files.mismatch(before, after) != -1) throw new IOException("Asset changed in package round trip: " + file);
                files.addProperty(file, CharacterLocalModelAssets.hash(after));
            }
            var metadata = imported.metadata();
            if (!metadata.skinned() || metadata.embeddedTextures() == 0 || metadata.clips().isEmpty())
                throw new IOException("The restored FBX is missing weights, textures or clips");
            var report = new JsonObject();
            report.addProperty("status", "TESTED_OFFLINE");
            report.addProperty("gameplay", "UNVERIFIED_LIVE");
            report.addProperty("archiveSha256", CharacterLocalModelAssets.hash(archive));
            report.addProperty("visualDigest", restoredVisual.digest());
            report.addProperty("nodes", metadata.nodes().size());
            report.addProperty("meshes", metadata.meshes().size());
            report.addProperty("clips", metadata.clips().size());
            report.addProperty("embeddedTextures", metadata.embeddedTextures());
            report.add("assetSha256", files);
            var reportFile = workspace.root().resolve("report.json");
            Files.writeString(reportFile, new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n");
            publish(archive, output);
            publish(reportFile, output.resolveSibling(output.getFileName() + ".validation.json"));
            System.out.println("Validated RigWorks avatar archive: " + output);
        }
    }

    private static void publish(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record PackageWorkspace(Path root) implements AutoCloseable {
        @Override public void close() throws IOException {
            // Only traverses the fresh directory owned by this run, without following links.
            List<Path> paths;
            try (var walk = Files.walk(root)) { paths = walk.sorted(Comparator.reverseOrder()).toList(); }
            for (var path : paths) Files.delete(path);
        }
    }
}
