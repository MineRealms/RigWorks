package cn.minerealms.rigworks.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.file.FileWatcher;
import com.electronwill.nightconfig.toml.TomlFormat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Serializes edits and watcher reloads without mutating NightConfig's autosave map. */
public final class RuntimeConfigFile {
    private final Path path;
    private final Consumer<CommentedConfig> install;
    private final Predicate<CommentedConfig> valid;
    private final Consumer<CommentedConfig> correct;
    private final Runnable refreshCaches, changed;
    private final FileReplacer fileReplacer;
    private CommentedConfig snapshot;
    private String diskText;

    @FunctionalInterface
    interface FileReplacer {
        void replace(Path pending, Path target) throws IOException;
    }

    public RuntimeConfigFile(CommentedFileConfig live, Predicate<CommentedConfig> valid,
                             Consumer<CommentedConfig> correct, Runnable refreshCaches, Runnable changed) throws IOException {
        this(live, live.getNioPath(), candidate -> live.load(), valid, correct, refreshCaches, changed,
                RuntimeConfigFile::moveReplacing);
    }

    public RuntimeConfigFile(CommentedConfig live, Path path, Predicate<CommentedConfig> valid,
                             Consumer<CommentedConfig> correct, Runnable refreshCaches, Runnable changed) throws IOException {
        this(() -> live, path, valid, correct, refreshCaches, changed, RuntimeConfigFile::moveReplacing);
    }

    public RuntimeConfigFile(Supplier<CommentedConfig> live, Path path, Predicate<CommentedConfig> valid,
                             Consumer<CommentedConfig> correct, Runnable refreshCaches, Runnable changed) throws IOException {
        this(live.get(), path, candidate -> {
            // NeoForge replaces its loaded config after an external reload.
            var current = live.get();
            current.clear();
            current.putAll(candidate);
            current.putAllComments(candidate);
        }, valid, correct, refreshCaches, changed, RuntimeConfigFile::moveReplacing);
    }

    RuntimeConfigFile(CommentedConfig live, Path path, Predicate<CommentedConfig> valid,
                      Consumer<CommentedConfig> correct, Runnable refreshCaches, Runnable changed,
                      FileReplacer fileReplacer) throws IOException {
        this(() -> live, path, valid, correct, refreshCaches, changed, fileReplacer);
    }

    RuntimeConfigFile(Supplier<CommentedConfig> live, Path path, Predicate<CommentedConfig> valid,
                      Consumer<CommentedConfig> correct, Runnable refreshCaches, Runnable changed,
                      FileReplacer fileReplacer) throws IOException {
        this(live.get(), path, candidate -> {
            // NeoForge replaces its loaded config after an external reload.
            var current = live.get();
            current.clear();
            current.putAll(candidate);
            current.putAllComments(candidate);
        }, valid, correct, refreshCaches, changed, fileReplacer);
    }

    private RuntimeConfigFile(CommentedConfig live, Path path, Consumer<CommentedConfig> install,
                              Predicate<CommentedConfig> valid, Consumer<CommentedConfig> correct,
                              Runnable refreshCaches, Runnable changed, FileReplacer fileReplacer) throws IOException {
        Objects.requireNonNull(live);
        this.path = Objects.requireNonNull(path).toAbsolutePath();
        this.install = Objects.requireNonNull(install);
        this.valid = Objects.requireNonNull(valid);
        this.correct = Objects.requireNonNull(correct);
        this.refreshCaches = Objects.requireNonNull(refreshCaches);
        this.changed = Objects.requireNonNull(changed);
        this.fileReplacer = Objects.requireNonNull(fileReplacer);
        snapshot = copy(live);
        diskText = Files.readString(this.path, StandardCharsets.UTF_8);
    }

    /** Replaces only this file's Forge watcher; normal Forge config events are retained. */
    public void watch(Executor executor, Consumer<Exception> failure) throws IOException {
        FileWatcher.defaultInstance().setWatch(path, () -> executor.execute(() -> {
            try { reload(); }
            catch (Exception error) { failure.accept(error); }
        }));
    }

    public synchronized void update(Consumer<CommentedConfig> edit) throws IOException {
        var candidate = copy(snapshot);
        edit.accept(candidate);
        if (!valid.test(candidate)) throw new IllegalArgumentException("Invalid client configuration edit");
        String text = encode(candidate);
        if (text.equals(diskText)) return;
        replace(text);
        publish(candidate, text);
    }

    public synchronized void reload() throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        if (text.equals(diskText)) return;
        // Parse separately: an incomplete external edit must not clear the active config.
        var candidate = TomlFormat.instance().createParser().parse(text);
        if (!valid.test(candidate)) {
            correct.accept(candidate);
            text = encode(candidate);
            replace(text);
        }
        publish(candidate, text);
    }

    private void publish(CommentedConfig candidate, String text) {
        // The watcher uses this same monitor, and its executor is the client thread.
        // load() writes NightConfig's backing map without per-key autosave callbacks.
        install.accept(candidate);
        snapshot = candidate;
        diskText = text;
        refreshCaches.run();
        changed.run();
    }

    private void replace(String text) throws IOException {
        Path pending = Files.createTempFile(path.getParent(), path.getFileName().toString(), ".pending");
        try {
            Files.writeString(pending, text, StandardCharsets.UTF_8);
            for (int attempt = 0; ; attempt++) {
                try {
                    fileReplacer.replace(pending, path);
                    return;
                } catch (AccessDeniedException denied) {
                    if (attempt >= 2) throw denied;
                    try {
                        Thread.sleep(20L << attempt);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted while retrying client config replacement", interrupted);
                    }
                }
            }
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    private static void moveReplacing(Path pending, Path target) throws IOException {
        try {
            Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static CommentedConfig copy(CommentedConfig source) {
        // CommentedConfig.copy is shallow for nested tables; TOML makes a detached copy.
        return TomlFormat.instance().createParser().parse(encode(source));
    }

    private static String encode(CommentedConfig value) {
        return TomlFormat.instance().createWriter().writeToString(value);
    }
}
