package dev.audiobook.platform.generation;

import java.util.UUID;

public final class GenerationRestartedException extends RuntimeException {

    private final UUID replacementRecipeId;
    private final UUID replacementManifestId;

    public GenerationRestartedException(UUID replacementRecipeId, UUID replacementManifestId) {
        super("Speech generation restarted under a qualified equivalent route");
        this.replacementRecipeId = replacementRecipeId;
        this.replacementManifestId = replacementManifestId;
    }

    public UUID replacementRecipeId() {
        return replacementRecipeId;
    }

    public UUID replacementManifestId() {
        return replacementManifestId;
    }
}
