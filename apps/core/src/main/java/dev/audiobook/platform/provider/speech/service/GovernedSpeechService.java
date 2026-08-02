package dev.audiobook.platform.provider.speech.service;

import dev.audiobook.platform.provider.ProviderUsage;
import dev.audiobook.platform.provider.SpeechProvider;
import dev.audiobook.platform.provider.speech.*;

import java.util.UUID;

public interface GovernedSpeechService {

    SpeechOutcome synthesize(SpeechCommand command);

    record SpeechCommand(UUID generationRecipeId, String operationId, String canonicalText) {}

    record SpeechOutcome(
            SpeechProvider.SpeechResult speech,
            String capabilityProfileVersion,
            ProviderUsage usage) {}
}
