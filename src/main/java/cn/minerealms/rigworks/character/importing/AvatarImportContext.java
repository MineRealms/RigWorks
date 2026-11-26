package cn.minerealms.rigworks.character.importing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** A selected source and its dependency root; old self-contained handlers can ignore the path. */
public record AvatarImportContext(String name, byte[] bytes, Path sourcePath) {
    public AvatarImportContext {
        Objects.requireNonNull(name, "Import name");
        Objects.requireNonNull(bytes, "Import bytes");
        if (sourcePath != null) sourcePath = sourcePath.toAbsolutePath().normalize();
    }

    public static AvatarImportContext from(Path file, byte[] bytes) throws IOException {
        Path source = file.toRealPath();
        return new AvatarImportContext(source.getFileName().toString(), bytes, source);
    }

    /** Source-authored paths cannot silently read outside the selected model directory. */
    public Path resolve(String reference) throws IOException {
        if (sourcePath == null) throw new IOException("This import needs a source directory: " + name);
        if (reference == null || reference.isBlank() || reference.indexOf('\0') >= 0)
            throw new IOException("Empty or invalid avatar dependency path");
        String portable = reference.replace('\\', '/');
        if (portable.startsWith("/") || portable.matches("^[A-Za-z]:.*"))
            throw new IOException("Avatar dependency must be relative: " + reference);
        Path root = sourcePath.getParent().toRealPath();
        Path target = root.resolve(portable).normalize();
        if (!target.startsWith(root)) throw new IOException("Avatar dependency leaves its source directory: " + reference);
        if (!Files.isRegularFile(target)) throw new IOException("Missing avatar dependency: " + reference);
        Path real = target.toRealPath();
        if (!real.startsWith(root)) throw new IOException("Avatar dependency symlink leaves its source directory: " + reference);
        return real;
    }
}
