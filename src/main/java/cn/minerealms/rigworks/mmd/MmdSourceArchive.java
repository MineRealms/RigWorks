package cn.minerealms.rigworks.mmd;

import cn.minerealms.rigworks.character.importing.AvatarImportContext;
import cn.minerealms.rigworks.debug.RigWorksDiagnostics;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipFile;

/** A bounded source-only container, separate from the Workbench's ordinary converted asset ZIP. */
public final class MmdSourceArchive {
    public static final String FORMAT = "rigworks.mmd-source";
    private static final String MANIFEST = "manifest.json";
    private static final long MAX_BYTES = 512L * 1024 * 1024;
    private static final int MAX_MANIFEST_BYTES = 4 * 1024 * 1024;
    private static final int MAX_ENTRIES = 10000;
    private static final Charset ZIP_LEGACY_CHARSET = Charset.forName("IBM437");
    private record Check(long bytes, String sha256) {}
    private record Manifest(String model, Map<String, Check> files, long bytes) {}
    private record Member(String name, boolean directory, long bytes) {}

    public static final class Extracted implements AutoCloseable {
        private final Path directory, ownerRoot;
        private final AvatarImportContext model;
        private final String archiveHash;

        private Extracted(Path directory, Path ownerRoot, AvatarImportContext model, String archiveHash) {
            this.directory = directory; this.ownerRoot = ownerRoot; this.model = model; this.archiveHash = archiveHash;
        }
        public Path directory() { return directory; }
        public AvatarImportContext model() { return model; }
        public String archiveHash() { return archiveHash; }
        @Override public void close() throws IOException { removeOwned(directory, ownerRoot); }
    }

    private MmdSourceArchive() {}

    public static boolean matches(String name) { return name.toLowerCase(Locale.ROOT).endsWith(".zip"); }

    public static Extracted extract(byte[] bytes, Path stagingRoot) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > MmdNative.MAX_SOURCE_BYTES)
            throw new IOException("MMD source ZIP must be between 1 byte and 256 MiB");
        var members = inspectDirectory(bytes);
        Path root = stagingRoot.toAbsolutePath().normalize();
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root)) throw new IOException("MMD source staging root cannot be a symbolic link");
        Path staging = Files.createTempDirectory(root, ".mmd-source-");
        boolean complete = false;
        try {
            Path archive = staging.resolve("source.zip");
            Files.write(archive, bytes);
            Path payload = Files.createDirectory(staging.resolve("payload"));
            Extracted result;
            try (var zip = new ZipFile(archive.toFile(), ZIP_LEGACY_CHARSET)) {
                var entry = zip.getEntry(MANIFEST);
                if (entry == null || entry.isDirectory()) throw new IOException("MMD source ZIP requires manifest.json; use Import avatar for a .rig.zip");
                byte[] metadata;
                try (var input = zip.getInputStream(entry)) {
                    metadata = input.readNBytes(MAX_MANIFEST_BYTES + 1);
                    if (metadata.length > MAX_MANIFEST_BYTES) throw new IOException("MMD source manifest exceeds 4 MiB");
                }
                Manifest manifest = manifest(metadata);
                var actualFiles = new HashSet<String>();
                for (var member : members.values()) if (!member.directory() && !member.name().equals(MANIFEST)) actualFiles.add(member.name());
                if (!actualFiles.equals(manifest.files().keySet())) throw new IOException("MMD source ZIP has missing or unlisted payload files");
                long total = manifest.bytes();
                for (var member : members.values()) {
                    interrupted();
                    if (member.directory() || member.name().equals(MANIFEST)) continue;
                    Check expected = manifest.files().get(member.name());
                    if (member.bytes() != expected.bytes()) throw new IOException("MMD source ZIP entry size differs from manifest: " + member.name());
                    Path target = payload.resolve(member.name()).normalize();
                    if (!target.startsWith(payload)) throw new IOException("MMD source ZIP entry escapes staging");
                    Files.createDirectories(target.getParent());
                    Check actual;
                    try (var input = zip.getInputStream(zip.getEntry(member.name())); var output = Files.newOutputStream(target)) {
                        actual = copy(input, output, Math.min(expected.bytes(), MAX_BYTES - total));
                    }
                    total = add(total, actual.bytes());
                    if (!expected.equals(actual)) throw new IOException("MMD source ZIP checksum or size mismatch: " + member.name());
                }
                Path model = payload.resolve(manifest.model());
                if (!Files.isRegularFile(model, LinkOption.NOFOLLOW_LINKS)) throw new IOException("MMD source ZIP selected model is missing");
                var context = AvatarImportContext.from(model, Files.readAllBytes(model));
                String hash = HexFormat.of().formatHex(digest().digest(bytes));
                RigWorksDiagnostics.debug("MMD source ZIP extracted model={} files={} expandedBytes={} archive={}",
                        manifest.model(), manifest.files().size(), total, hash);
                result = new Extracted(staging, root, context, hash);
            }
            complete = true;
            return result;
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid MMD source ZIP: " + invalid.getMessage(), invalid);
        } finally {
            if (!complete) removeOwned(staging, root);
        }
    }

    private static Manifest manifest(byte[] bytes) throws IOException {
        try (var reader = new JsonReader(new StringReader(decode(bytes, StandardCharsets.UTF_8)))) {
            reader.setLenient(false); reader.beginObject();
            String format = null, model = null; long version = -1;
            Map<String, Check> files = null;
            var seen = new HashSet<String>();
            while (reader.hasNext()) {
                String key = reader.nextName();
                if (!seen.add(key)) throw new IOException("Duplicate MMD manifest field");
                switch (key) {
                    case "format" -> format = text(reader);
                    case "version" -> version = integer(reader);
                    case "model" -> model = path(text(reader), false);
                    case "files" -> {
                        files = new LinkedHashMap<>(); reader.beginObject();
                        var names = new HashSet<String>(); long total = bytes.length;
                        while (reader.hasNext()) {
                            if (files.size() >= MAX_ENTRIES - 1) throw new IOException("MMD source ZIP exceeds 10000 entries");
                            String name = path(reader.nextName(), false);
                            if (name.equals(MANIFEST) || !names.add(name.toLowerCase(Locale.ROOT))) throw new IOException("Duplicate MMD manifest path");
                            reader.beginObject(); long length = -1; String sha = null; var fields = new HashSet<String>();
                            while (reader.hasNext()) {
                                String field = reader.nextName();
                                if (!fields.add(field)) throw new IOException("Duplicate MMD manifest entry field");
                                switch (field) {
                                    case "bytes" -> length = integer(reader);
                                    case "sha256" -> sha = text(reader);
                                    default -> throw new IOException("Unknown MMD manifest entry field: " + field);
                                }
                            }
                            reader.endObject();
                            if (length < 0 || length > MmdNative.MAX_SOURCE_BYTES || sha == null || !sha.matches("[a-f0-9]{64}"))
                                throw new IOException("Invalid MMD manifest checksum or entry size");
                            total = add(total, length); files.put(name, new Check(length, sha));
                        }
                        reader.endObject();
                    }
                    default -> throw new IOException("Unknown MMD manifest field: " + key);
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT || !FORMAT.equals(format) || version != 1 || model == null || files == null)
                throw new IOException("Not a version 1 rigworks.mmd-source ZIP; use Import avatar for a .rig.zip");
            String extension = model.toLowerCase(Locale.ROOT);
            if ((!extension.endsWith(".pmd") && !extension.endsWith(".pmx")) || !files.containsKey(model) || files.get(model).bytes() == 0)
                throw new IOException("MMD source ZIP model must select a nonempty listed PMD or PMX file");
            return new Manifest(model, Map.copyOf(files), bytes.length);
        } catch (IllegalStateException | NumberFormatException invalid) {
            throw new IOException("Invalid MMD source manifest", invalid);
        }
    }

    // Java's public ZIP API does not expose Unix file types; inspect the central directory before extraction.
    private static Map<String, Member> inspectDirectory(byte[] bytes) throws IOException {
        var data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int end = -1;
        for (int at = bytes.length - 22; at >= Math.max(0, bytes.length - 65557); at--)
            if (data.getInt(at) == 0x06054b50 && at + 22L + ushort(data, at + 20) == bytes.length) { end = at; break; }
        if (end < 0) throw new IOException("Invalid MMD source ZIP directory");
        int count = ushort(data, end + 10);
        long size = uint(data, end + 12), offset = uint(data, end + 16);
        if (ushort(data, end + 4) != 0 || ushort(data, end + 6) != 0 || ushort(data, end + 8) != count
                || count < 1 || count > MAX_ENTRIES || size == 0xffffffffL || offset == 0xffffffffL || offset + size != end)
            throw new IOException("Unsupported multipart/ZIP64 or oversized MMD source ZIP");
        var members = new LinkedHashMap<String, Member>(); var spelling = new HashMap<String, String>();
        long cursor = offset, total = 0;
        for (int index = 0; index < count; index++) {
            if (cursor < 0 || cursor + 46 > end || data.getInt((int) cursor) != 0x02014b50) throw new IOException("Invalid MMD source ZIP member directory");
            int at = (int) cursor;
            int flags = ushort(data, at + 8), method = ushort(data, at + 10), nameLength = ushort(data, at + 28);
            long expanded = uint(data, at + 24), compressed = uint(data, at + 20);
            cursor += 46L + nameLength + ushort(data, at + 30) + ushort(data, at + 32);
            if (cursor > end || nameLength == 0 || (flags & 1) != 0 || (method != 0 && method != 8)
                    || ushort(data, at + 34) != 0 || expanded > MmdNative.MAX_SOURCE_BYTES || compressed > bytes.length
                    || uint(data, at + 42) >= offset)
                throw new IOException("Unsupported or oversized MMD source ZIP entry");
            int mode = (int) (uint(data, at + 38) >>> 16) & 0xf000;
            if (mode != 0 && mode != 0x8000 && mode != 0x4000) throw new IOException("MMD source ZIP cannot contain symlinks or special files");
            byte[] nameBytes = java.util.Arrays.copyOfRange(bytes, at + 46, at + 46 + nameLength);
            String raw = decode(nameBytes, (flags & 0x800) != 0 ? StandardCharsets.UTF_8 : ZIP_LEGACY_CHARSET);
            boolean directory = raw.endsWith("/"); String name = path(raw, directory);
            if ((mode == 0x4000 && !directory) || (mode == 0x8000 && directory) || (directory && expanded != 0))
                throw new IOException("Invalid MMD source ZIP directory entry");
            remember(name, spelling);
            if (members.putIfAbsent(name.toLowerCase(Locale.ROOT), new Member(name, directory, expanded)) != null)
                throw new IOException("Duplicate or case-conflicting MMD source ZIP member: " + name);
            total = add(total, expanded);
        }
        if (cursor != end) throw new IOException("MMD source ZIP directory length mismatch");
        var exact = new LinkedHashMap<String, Member>();
        for (var member : members.values()) {
            String[] components = member.name().split("/"); String ancestor = "";
            for (int index = 0; index < components.length - 1; index++) {
                ancestor = ancestor.isEmpty() ? components[index] : ancestor + "/" + components[index];
                var parent = members.get(ancestor.toLowerCase(Locale.ROOT));
                if (parent != null && !parent.directory()) throw new IOException("MMD source ZIP file/directory collision");
            }
            exact.put(member.name(), member);
        }
        return exact;
    }

    private static String path(String value, boolean directory) throws IOException {
        if (value == null || value.length() > 1024 || value.isBlank() || value.indexOf('\\') >= 0 || value.indexOf(':') >= 0 || value.startsWith("/"))
            throw new IOException("Invalid MMD source ZIP relative path");
        String name = directory && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        String[] parts = name.split("/", -1);
        if (parts.length > 32) throw new IOException("MMD source ZIP path is too deep");
        for (String part : parts) {
            if (part.isBlank() || part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" ")
                    || part.chars().anyMatch(c -> c < 32 || c == 127 || "<>\"|?*".indexOf(c) >= 0)) throw new IOException("Invalid MMD source ZIP path component");
            if (part.split("\\.", 2)[0].toUpperCase(Locale.ROOT).matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]"))
                throw new IOException("Reserved MMD source ZIP filename");
        }
        return name;
    }

    private static void remember(String name, Map<String, String> spelling) throws IOException {
        String prefix = "";
        for (String component : name.split("/")) {
            prefix = prefix.isEmpty() ? component : prefix + "/" + component;
            String previous = spelling.putIfAbsent(prefix.toLowerCase(Locale.ROOT), prefix);
            if (previous != null && !previous.equals(prefix)) throw new IOException("Case-conflicting MMD source ZIP path: " + name);
        }
    }
    private static Check copy(InputStream input, OutputStream output, long limit) throws IOException {
        var hash = digest(); byte[] buffer = new byte[65536]; long bytes = 0;
        for (int count; (count = input.read(buffer)) != -1;) {
            interrupted(); bytes += count;
            if (bytes > limit) throw new IOException("Expanded MMD source ZIP entry exceeds its size budget");
            hash.update(buffer, 0, count); output.write(buffer, 0, count);
        }
        return new Check(bytes, HexFormat.of().formatHex(hash.digest()));
    }
    private static long integer(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.NUMBER) throw new IOException("MMD manifest integer required");
        String value = reader.nextString();
        if (!value.matches("0|[1-9][0-9]{0,18}")) throw new IOException("Invalid MMD manifest integer");
        return Long.parseLong(value);
    }
    private static String text(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.STRING) throw new IOException("MMD manifest string required");
        String value = reader.nextString();
        if (value.length() > 1024) throw new IOException("MMD manifest string exceeds its size limit");
        return value;
    }
    private static String decode(byte[] bytes, Charset charset) throws IOException {
        try { return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException invalid) { throw new IOException("Invalid MMD source ZIP text encoding", invalid); }
    }
    private static long add(long total, long size) throws IOException {
        if (size < 0 || size > MAX_BYTES - total) throw new IOException("Expanded MMD source ZIP exceeds 512 MiB");
        return total + size;
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static int ushort(ByteBuffer data, int offset) { return Short.toUnsignedInt(data.getShort(offset)); }
    private static long uint(ByteBuffer data, int offset) { return Integer.toUnsignedLong(data.getInt(offset)); }
    private static void interrupted() throws IOException { if (Thread.currentThread().isInterrupted()) throw new IOException("MMD source ZIP import cancelled"); }
    private static void removeOwned(Path directory, Path ownerRoot) throws IOException {
        Path owned = directory.toAbsolutePath().normalize();
        if (!ownerRoot.toAbsolutePath().normalize().equals(owned.getParent())
                || !owned.getFileName().toString().startsWith(".mmd-source-") || Files.isSymbolicLink(owned))
            throw new IOException("Refusing cleanup outside owned MMD source staging");
        if (!Files.exists(owned, LinkOption.NOFOLLOW_LINKS)) return;
        try (var files = Files.walk(owned)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
        }
    }
}
