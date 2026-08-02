package dev.audiobook.platform.narration.internal.review.assets;

import dev.audiobook.platform.narration.internal.planning.assets.NarrationPlanAssetIdentity;

import java.util.UUID;

public final class NarrationReviewAssetIdentity {

    private NarrationReviewAssetIdentity() {
    }

    static Identity identify(UUID conversionId, UUID decisionId, byte[] content) {
        return new Identity(reference(conversionId, decisionId), NarrationPlanAssetIdentity.sha256(content));
    }

    static String reference(UUID conversionId, UUID decisionId) {
        return "narration-plans/" + conversionId + "/reviews/" + decisionId + ".json";
    }

    static void requireReference(UUID conversionId, UUID decisionId, String candidate) {
        if (!reference(conversionId, decisionId).equals(candidate)) {
            throw new IllegalArgumentException("Narration Review Working Asset reference does not match its decision");
        }
    }

    record Identity(String reference, String sha256) {
    }
}
