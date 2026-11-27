package cn.minerealms.rigworks.character.client;

import cn.minerealms.assimploader.workbench.io.WorkbenchProjectStore;
import cn.minerealms.rigworks.character.core.CharacterVisualConfig;
import cn.minerealms.rigworks.character.script.AvatarPackageRepository;
import cn.minerealms.rigworks.character.script.AvatarScriptBundle;
import cn.minerealms.rigworks.character.script.AvatarScriptRuntime;
import cn.minerealms.rigworks.mmd.MmdSourceArchive;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.UUID;

/** Release authoring tool: exports and reopens using the actual Workbench package service. */
public final class PackageReleaseAvatar {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: PackageReleaseAvatar <job.json>");
        var job = JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
        if (job.has("mmdSource")) { inspectMmdSource(job); return; }
        Path output = path(job, "output");
        if (!output.getFileName().toString().endsWith(".rig.zip")) throw new IllegalArgumentException("Expected .rig.zip output");
        Path work = Files.createTempDirectory(Files.createDirectories(output.getParent()), ".release-package-");
        var store = new WorkbenchProjectStore(work.resolve("projects"));
        WorkbenchProjectStore.Project project;
        var extra = new LinkedHashMap<String, byte[]>();
        if (job.has("archive")) {
            project = store.importZip(path(job, "archive"));
        } else {
            Path input = Files.createDirectory(work.resolve("input"));
            for (var value : job.getAsJsonArray("files")) {
                var file = value.getAsJsonObject();
                Path source = path(file, "from");
                Path target = input.resolve(file.get("to").getAsString()).normalize();
                require(target.startsWith(input) && !target.equals(input), "Invalid staged asset path");
                if (Files.isDirectory(source)) {
                    try (var tree = Files.walk(source)) {
                        for (Path child : tree.sorted().toList()) {
                            require(!Files.isSymbolicLink(child), "Asset symlinks are not portable");
                            Path destination = target.resolve(source.relativize(child));
                            if (Files.isDirectory(child)) Files.createDirectories(destination);
                            else { Files.createDirectories(destination.getParent()); Files.copy(child, destination); }
                        }
                    }
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target);
                }
            }
            Path model = input.resolve(job.get("model").getAsString()).normalize();
            require(model.startsWith(input) && Files.isRegularFile(model), "Missing staged model");
            if (job.has("profileOverrides")) {
                Path profileFile = model.resolveSibling("profile.json");
                var profile = JsonParser.parseString(Files.readString(profileFile)).getAsJsonObject();
                job.getAsJsonObject("profileOverrides").entrySet().forEach(entry -> profile.add(entry.getKey(), entry.getValue()));
                Files.writeString(profileFile, new GsonBuilder().setPrettyPrinting().create().toJson(profile));
            }
            String hash = CharacterLocalModelAssets.hash(model);
            UUID identity = UUID.nameUUIDFromBytes(("rigworks:default-model-pack/" + job.get("id").getAsString()).getBytes(StandardCharsets.UTF_8));
            String modelId = CharacterLocalModelAssets.identity(identity, hash, model).toString();
            var selected = CharacterWorkbenchSources.loadLocal(model, modelId, hash);
            require(selected != null, "No authored profile for staged model");
            var visualJson = JsonParser.parseString(selected.visual().json()).getAsJsonObject();
            if (job.has("root")) visualJson.add("root", job.get("root"));
            if (job.has("options")) visualJson.add("options", job.get("options"));
            var visual = CharacterVisualConfig.parse(visualJson.toString());
            String profileId = "rigworks:avatars/" + identity + "/profile.json";
            project = store.importAsset(model, input);
            var extension = new JsonObject();
            extension.addProperty("version", 1);
            extension.addProperty("profileId", profileId);
            extension.addProperty("sourceProfileId", selected.profileId());
            extension.addProperty("instanceId", identity.toString());
            extension.add("visual", JsonParser.parseString(visual.json()));
            extension.addProperty("scriptsIncluded", true);
            var behavior = behavior(job, identity, profileId);
            extension.add("behavior", encode(behavior));
            behavior.files().forEach((name, text) -> extra.put("extensions/rigworks/behavior/" + name, text.getBytes(StandardCharsets.UTF_8)));
            project = new WorkbenchProjectStore.Project(project.directory(), project.documentPath(), project.scene(),
                    project.document().withExtension("rigworks", extension), project.metadata());
        }
        require(project.document().extensions().has("rigworks"), "Not a RigWorks avatar");
        if (job.has("readme")) extra.put("extensions/rigworks/README.md", Files.readAllBytes(path(job, "readme")));
        Path temporary = work.resolve("output.rig.zip");
        store.exportZip(project, project.document(), temporary, extra);
        var restored = new WorkbenchProjectStore(work.resolve("roundtrip")).importZip(temporary);
        var extension = restored.document().extensions().getAsJsonObject("rigworks");
        require(project.document().extensions().get("rigworks").equals(extension), "RigWorks metadata changed in round trip");
        var visual = CharacterVisualConfig.parse(extension.get("visual").toString());
        Path restoredModel = WorkbenchProjectStore.resolvePortable(restored.directory(), restored.document().sourcePath());
        require(CharacterLocalModelAssets.hash(restoredModel).equals(visual.modelHash()), "Model hash differs from visual configuration");
        require(restored.metadata().skinned(), "Expected a skinned avatar");
        require(restored.document().validateReferences(restored.metadata()).isEmpty(), "Invalid Workbench references");
        var nodeNames = restored.metadata().nodes().stream().map(node -> node.name()).collect(java.util.stream.Collectors.toSet());
        visual.profile().humanoid().forEach((role, bone) -> require(nodeNames.contains(bone.node()), "Missing humanoid node " + role + ": " + bone.node()));
        var clipNames = restored.metadata().clips().stream().map(clip -> clip.name()).collect(java.util.stream.Collectors.toSet());
        visual.profile().animations().forEach((intent, clip) -> require(clipNames.contains(clip), "Missing clip for " + intent + ": " + clip));
        var missingTextures = restored.metadata().issues().stream().filter(issue -> issue.contains("missing texture:")).toList();
        require(missingTextures.isEmpty(), "Missing textures: " + missingTextures);
        for (var material : restored.document().materials().values()) for (String reference : material.textures().values()) {
            if (reference.matches("\\*\\d+")) continue;
            require(!reference.contains(":") && !reference.startsWith("/") && !reference.startsWith("\\"), "Nonportable texture: " + reference);
            Path texture = restoredModel.getParent().resolve(reference).normalize();
            require(texture.startsWith(restored.directory().resolve("assets")) && Files.isRegularFile(texture), "Missing packaged texture: " + reference);
        }
        if (extension.has("behavior")) {
            var bundle = AvatarPackageRepository.readBundle(restored.directory().resolve("extensions/rigworks/behavior"));
            require(encode(bundle).equals(extension.get("behavior")), "Behavior source differs from snapshot");
            try (var runtime = AvatarScriptRuntime.open(bundle)) {
                require(runtime.available(), "Behavior could not load: " + runtime.failure());
                var frame = new JsonObject(); frame.addProperty("tick", 1); frame.addProperty("onGround", true);
                frame.addProperty("delta", .05); frame.addProperty("time", .05);
                var result = runtime.execute(frame);
                require(result.handled(), "Behavior failed: " + result.failure());
            }
        }
        var hashes = new JsonObject();
        try (var files = Files.walk(project.directory().resolve("assets"))) {
            for (Path source : files.filter(Files::isRegularFile).sorted().toList()) {
                Path relative = project.directory().relativize(source);
                require(Files.mismatch(source, restored.directory().resolve(relative)) == -1, "Changed asset: " + relative);
                hashes.addProperty(relative.toString().replace('\\', '/'), CharacterLocalModelAssets.hash(source));
            }
        }
        Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
        var report = new JsonObject();
        report.addProperty("status", "TESTED_OFFLINE"); report.addProperty("gameplay", "UNVERIFIED_LIVE");
        report.addProperty("archiveSha256", CharacterLocalModelAssets.hash(output)); report.addProperty("archiveBytes", Files.size(output));
        report.addProperty("visualDigest", visual.digest());
        report.add("metadata", new GsonBuilder().create().toJsonTree(restored.metadata())); report.add("assetSha256", hashes);
        report.addProperty("scriptsIncluded", extension.has("behavior"));
        report.add("materials", new GsonBuilder().create().toJsonTree(restored.document().materials()));
        Files.writeString(output.resolveSibling(output.getFileName() + ".validation.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n");
        System.out.println("Validated " + output + " meshes=" + restored.metadata().meshes().size() + " clips=" + restored.metadata().clips().size());
        try (var paths = Files.walk(work)) {
            for (Path file : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file);
        }
    }

    private static void inspectMmdSource(JsonObject job) throws Exception {
        Path archive = path(job, "mmdSource");
        require(Files.size(archive) <= 256L * 1024 * 1024, "Source archive exceeds input limit");
        var report = new JsonObject();
        try (var source = MmdSourceArchive.extract(Files.readAllBytes(archive), archive.getParent().resolve("source-checks"))) {
            report.addProperty("status", "TESTED_SOURCE_ARCHIVE");
            report.addProperty("conversionRun", false);
            report.addProperty("archiveSha256", source.archiveHash());
            report.addProperty("archiveBytes", Files.size(archive));
            report.addProperty("model", source.model().name());
            report.addProperty("modelSha256", CharacterVisualConfig.hash(source.model().bytes()));
            report.addProperty("modelBytes", source.model().bytes().length);
        }
        Files.writeString(archive.resolveSibling(archive.getFileName() + ".validation.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n");
        System.out.println("Validated source-only ZIP without conversion: " + archive);
    }

    private static AvatarScriptBundle behavior(JsonObject job, UUID identity, String profileId) throws Exception {
        String id = job.get("id").getAsString();
        String controller = "rigworks:default_pack_" + id.replace('-', '_');
        var manifest = new JsonObject(); manifest.addProperty("version", 1); manifest.addProperty("id", "rigworks:default_pack/" + id);
        manifest.addProperty("profile", profileId); manifest.addProperty("instanceId", identity.toString()); manifest.addProperty("controller", controller);
        var scripts = new com.google.gson.JsonArray(); scripts.add("scripts/controller.js"); manifest.add("scripts", scripts); manifest.addProperty("mappings", "config/mappings.json");
        var mappings = new JsonObject(); mappings.addProperty("version", 1);
        var files = new LinkedHashMap<String, String>();
        files.put("manifest.json", manifest.toString()); files.put("config/mappings.json", mappings.toString());
        if (job.has("blink") && job.get("blink").getAsBoolean()) {
            var blink = new JsonObject();
            blink.addProperty("leftMorph", "Eyelid_Close_L"); blink.addProperty("rightMorph", "Eyelid_Close_R");
            blink.addProperty("intervalSeconds", 4.2); blink.addProperty("closeSeconds", .065);
            blink.addProperty("holdSeconds", .055); blink.addProperty("openSeconds", .12);
            mappings.add("blink", blink);
            files.put("config/mappings.json", new GsonBuilder().setPrettyPrinting().create().toJson(mappings));
            files.put("scripts/controller.js", "RigWorks.register('" + controller + "', function(frame) {\n" + """
                  frame.defaultMotion();
                  var config = frame.mappings.blink;
                  var input = frame.input();
                  var phase = ((input.time || 0) + 1.2) % config.intervalSeconds;
                  var close = config.closeSeconds, hold = config.holdSeconds;
                  var end = close + hold + config.openSeconds;
                  var weight = phase < close ? phase / close : phase < close + hold ? 1
                    : phase < end ? 1 - (phase - close - hold) / config.openSeconds : 0;
                  if (input.sleeping || input.dead) weight = 1;
                  frame.morph(config.leftMorph, weight);
                  frame.morph(config.rightMorph, weight);
                });
                """);
        } else files.put("scripts/controller.js", "RigWorks.register('" + controller + "', function(frame) {\n  frame.defaultMotion();\n});\n");
        return new AvatarScriptBundle(manifest.get("id").getAsString(), profileId, controller, identity, files);
    }

    private static JsonObject encode(AvatarScriptBundle bundle) {
        var value = new JsonObject(); value.addProperty("id", bundle.id()); value.addProperty("profile", bundle.profile());
        value.addProperty("controller", bundle.controller()); value.addProperty("instanceId", bundle.instanceId().toString());
        var files = new JsonObject(); bundle.files().forEach(files::addProperty); value.add("files", files); return value;
    }
    private static Path path(JsonObject object, String key) { return Path.of(object.get(key).getAsString()).toAbsolutePath().normalize(); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
