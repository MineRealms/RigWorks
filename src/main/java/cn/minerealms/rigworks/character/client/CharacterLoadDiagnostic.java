package cn.minerealms.rigworks.character.client;

import java.nio.file.Path;

/** Keeps asynchronous load failures tied to the source that was actually attempted. */
record CharacterLoadDiagnostic(String profile, String declaredModel, String model, Path source,
                               String expectedHash, String actualHash) {
    CharacterLoadDiagnostic resolved(String resolvedModel, Path resolvedSource) {
        return new CharacterLoadDiagnostic(profile, declaredModel, resolvedModel, resolvedSource, expectedHash, actualHash);
    }

    CharacterLoadDiagnostic hashed(String hash) {
        return new CharacterLoadDiagnostic(profile, declaredModel, model, source, expectedHash, hash);
    }

    String message(String action) {
        StringBuilder text = new StringBuilder(action).append(" character profile ").append(profile)
                .append(" [model=").append(model);
        if (!declaredModel.equals(model)) text.append("; declared model=").append(declaredModel);
        text.append("; source=").append(source == null ? "resource:" + model : source.toAbsolutePath().normalize());
        if (expectedHash != null && !expectedHash.isBlank()) text.append("; expected SHA-256=").append(expectedHash);
        if (actualHash != null && !actualHash.isBlank()) text.append("; actual SHA-256=").append(actualHash);
        return text.append(']').toString();
    }
}
