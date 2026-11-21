package cn.minerealms.rigworks.character.importing;

import cn.minerealms.assimploader.model.AssimpScene;

import java.io.IOException;
import java.util.List;

/**
 * Format-agnostic avatar import contract. RigWorks owns the UI, configuration and application;
 * an implementation only knows how to turn bytes of its formats into an {@link AssimpScene}
 * ({@link #load}) and how to install that scene as a character ({@link #apply}).
 */
public interface AvatarImportHandler {
    /** Stable identifier used for handler-scoped picking and dispatch. */
    String id();

    /** Lower-case file extensions this handler accepts, without the leading dot. */
    List<String> extensions();

    /** Decode on the import worker, without touching client state; return {@code null} on failure. */
    AssimpScene load(String name, byte[] bytes) throws IOException;

    /** Apply the prepared scene to the current character on the client thread. */
    void apply(String name, byte[] bytes, AssimpScene scene) throws IOException;
}
