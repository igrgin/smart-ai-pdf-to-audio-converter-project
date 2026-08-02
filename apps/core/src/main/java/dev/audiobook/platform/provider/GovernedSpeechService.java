package dev.audiobook.platform.provider;

import dev.audiobook.platform.provider.SpeechProvider;
import java.util.UUID;

public interface GovernedSpeechService {

    SpeechOutcome synthesize(SpeechCommand command);

    record SpeechCommand(UUID generationRecipeId, String operationId, String canonicalText) {
    }

    record SpeechOutcome(
            SpeechProvider.SpeechResult speech,
            String capabilityProfileVersion,
            ProviderUsage usage) {
    }
}
