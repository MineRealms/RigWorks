package cn.minerealms.rigworks.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.file.FileWatcher;
import com.electronwill.nightconfig.toml.TomlFormat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
    private CommentedConfig snapshot;
    private String diskText;

    public RuntimeConfigFile(CommentedFileConfig live, Predicate<CommentedConfig> valid,
                             Consumer<CommentedConfig> correct, Runnable refreshCaches, Runnable changed) throws IOException {
        this(live, live.getNioPath(), candidate -> live.load(), valid, correct, refreshCaches, changed);
    }

    public RuntimeConfigFile(CommentedConfig live, Path path, Predicate<CommentedConfig> valid,
                             Consumer<CommentedConfig> correct, Runnable refreshCaches, Runnable changed) throws IOException {
        this(() -> live, path, valid, correct, refreshCaches, changed);
    }

    public RuntimeConfigFile(Supplier<CommentedConfig> live, Path path, Predicate<CommentedConfig> valid,
                             Consumer<CommentedConfig> correct, Runnable refreshCaches, Runnable changed) throws IOException {
        this(live.get(), path, candidate -> {
            // NeoForge replaces its loaded config after an external reload.
            var current = live.get();
            current.clear();
            current.putAll(candidate);
            current.putAllComments(candidate);
        }, valid, correct, refreshCaches, changed);
    }

    private RuntimeConfigFile(CommentedConfig live, Path path, Consumer<CommentedConfig> install,
                              Predicate<CommentedConfig> valid, Consumer<CommentedConfig> correct,
                              Runnable refreshCaches, Runnable changed) throws IOException {
        Objects.requireNonNull(live);
        this.path = Objects.requireNonNull(path).toAbsolutePath();
        this.install = Objects.requireNonNull(install);
        this.valid = Objects.requireNonNull(valid);
        this.correct = Objects.requireNonNull(correct);
        this.refreshCaches = Objects.requireNonNull(refreshCaches);
        this.changed = Objects.requireNonNull(changed);
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
            try {
                Files.move(pending, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(pending, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(pending);
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
