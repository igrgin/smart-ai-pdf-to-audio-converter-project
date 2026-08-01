package dev.audiobook.platform.narration;

import java.util.List;
import java.util.UUID;

public interface NarrationSelectionService {

    VoiceCatalog catalog();

    ConfirmedRecipe confirm(ConfirmCommand command);

    NarrationChoiceStatus narrationChoice(UUID listenerId, UUID conversionId);

    GenerationAuthorization authorizeGeneration(UUID listenerId, UUID conversionId);

    FailoverAuthorization failoverGeneration(
            UUID listenerId, UUID conversionId, UUID failedRecipeId);

    record VoiceCatalog(List<NarratorVoice> voices, List<NarrationPace> paces, NarrationPace defaultPace) {
        public VoiceCatalog {
            voices = List.copyOf(voices);
            paces = List.copyOf(paces);
        }
    }

    record NarratorVoice(
            UUID id,
            String displayName,
            String englishVariety,
            List<String> descriptors,
            String descriptorReviewVersion,
            VoiceAvailability availability,
            VoicePreview preview) {
        public NarratorVoice {
            descriptors = List.copyOf(descriptors);
        }
    }

    record VoicePreview(String uri, String passageVersion, int durationSeconds, boolean aiGenerated) {
    }

    record ConfirmCommand(
            UUID listenerId,
            UUID conversionId,
            UUID voiceId,
            NarrationPace pace,
            long expectedConversionVersion,
            String operationKey) {
    }

    record ConfirmedRecipe(
            UUID recipeId,
            UUID conversionId,
            UUID voiceId,
            String voiceDisplayName,
            NarrationPace pace,
            String recipeDigest,
            long conversionVersion) {
    }

    record GenerationAuthorization(UUID recipeId, String recipeDigest) {
    }

    record FailoverAuthorization(
            UUID failedRecipeId,
            UUID replacementRecipeId,
            String replacementRecipeDigest,
            String capabilityProfileVersion,
            String voiceEquivalenceVersion,
            String paceEquivalenceVersion) {
    }

    record NarrationChoiceStatus(
            long conversionVersion,
            UUID recipeId,
            UUID voiceId,
            String voiceDisplayName,
            NarrationPace pace,
            boolean explicitChoiceRequired) {
    }

    enum VoiceAvailability {
        AVAILABLE,
        TEMPORARILY_UNAVAILABLE,
        RETIRED
    }

    enum NarrationPace {
        MEASURED,
        NATURAL,
        BRISK
    }
}
