package dev.audiobook.platform.provider.internal.capability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.audiobook.platform.PlatformApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("itest")
@SpringBootTest(classes = PlatformApplication.class)
class ProviderCapabilityServiceITest {

    private final ProviderCapabilityService capabilityService;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    ProviderCapabilityServiceITest(
            ProviderCapabilityService capabilityService,
            JdbcTemplate jdbcTemplate) {
        this.capabilityService = capabilityService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Test
    void qualifiedSpeechProfileExposesTheCompleteGovernedCapability() {
        ProviderCapabilityService.CapabilityProfile profile = capabilityService.qualified(
                "openai-speech-eu-v2",
                ProviderCapabilityService.ServiceKind.SPEECH,
                ProviderCapabilityService.InputKind.CANONICAL_TEXT);

        assertThat(profile.provider()).isEqualTo("openai");
        assertThat(profile.endpoint()).isEqualTo("https://eu.api.openai.com/v1/audio/speech");
        assertThat(profile.modelSnapshot()).isEqualTo("gpt-4o-mini-tts-2025-12-15");
        assertThat(profile.deliveryMode()).isEqualTo("SYNCHRONOUS");
        assertThat(profile.region()).isEqualTo("eu");
        assertThat(profile.dataPolicyVersion()).isEqualTo("openai-eu-zdr-v1");
        assertThat(profile.maximumInputUnits()).isEqualTo(4_096);
        assertThat(profile.inputUnit()).isEqualTo("UTF8_CHARACTER");
        assertThat(profile.quotaMeter()).isEqualTo("REQUEST_PER_MINUTE");
        assertThat(profile.quotaLimit()).isEqualTo(500);
        assertThat(profile.priceMeter()).isEqualTo("INPUT_CHARACTER");
        assertThat(profile.requestFormat()).isEqualTo("application/json");
        assertThat(profile.responseFormat()).isEqualTo("audio/wav");
        assertThat(profile.nativeControlsSchema()).contains("speed", "instructions", "voice");
        assertThat(profile.validFrom()).isBefore(profile.validUntil());
    }

    @Test
    void staleGovernanceStateFailsClosedWithTheSpecificReason() {
        assertRejectedState("privacy_state", ProviderCapabilityRejectedException.Code.PRIVACY_STALE);
        assertRejectedState("region_state", ProviderCapabilityRejectedException.Code.REGION_STALE);
        assertRejectedState("access_state", ProviderCapabilityRejectedException.Code.ACCESS_STALE);
        assertRejectedState("quota_state", ProviderCapabilityRejectedException.Code.QUOTA_STALE);
        assertRejectedState("evaluation_state", ProviderCapabilityRejectedException.Code.EVALUATION_STALE);
    }

    private void assertRejectedState(
            String column,
            ProviderCapabilityRejectedException.Code expectedCode) {
        jdbcTemplate.update(
                "UPDATE narration.provider_capability_profile SET " + column + " = 'STALE' WHERE profile_version = ?",
                "openai-speech-eu-v2");
        try {
            assertThatThrownBy(() -> capabilityService.qualified(
                            "openai-speech-eu-v2",
                            ProviderCapabilityService.ServiceKind.SPEECH,
                            ProviderCapabilityService.InputKind.CANONICAL_TEXT))
                    .isInstanceOf(ProviderCapabilityRejectedException.class)
                    .extracting(error -> ((ProviderCapabilityRejectedException) error).code())
                    .isEqualTo(expectedCode);
        } finally {
            jdbcTemplate.update(
                    "UPDATE narration.provider_capability_profile SET " + column + " = 'QUALIFIED' WHERE profile_version = ?",
                    "openai-speech-eu-v2");
        }
    }
}
