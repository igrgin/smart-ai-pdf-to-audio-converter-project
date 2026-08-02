package dev.audiobook.platform.generation;

import dev.audiobook.platform.generation.service.*;

public enum SpeechBoundaryKind {
    LIMIT_CONTINUATION,
    PARAGRAPH,
    STRUCTURAL_SECTION,
    CHAPTER
}
