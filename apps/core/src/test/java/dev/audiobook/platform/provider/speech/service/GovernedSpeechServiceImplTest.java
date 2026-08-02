package dev.audiobook.platform.provider.speech.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import dev.audiobook.platform.provider.ProviderUsage;
import dev.audiobook.platform.provider.governance.service.ProviderCapabilityService;
import dev.audiobook.platform.provider.speech.ProviderSpeechAdapter;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

class GovernedSpeechServiceImplTest {

    @Test
    @SuppressWarnings("unchecked")
    void resolvesOnlyTheFrozenQualifiedRouteAndReturnsMeteredEvidence() throws Exception {
        UUID recipeId = UUID.randomUUID();
        String operationId = UUID.randomUUID().toString();
        ProviderCapabilityService capabilityService = mock(ProviderCapabilityService.class);
        ProviderSpeechAdapter adapter = mock(ProviderSpeechAdapter.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ProviderCapabilityService.CapabilityProfile capability = capability();
        given(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(recipeId)))
                .willAnswer(
                        invocation -> {
                            RowMapper<Object> mapper = invocation.getArgument(1);
                            ResultSet resultSet = mock(ResultSet.class);
                            given(resultSet.getString("capability_profile_version"))
                                    .willReturn(capability.profileVersion());
                            given(resultSet.getString("provider"))
                                    .willReturn(capability.provider());
                            given(resultSet.getString("endpoint"))
                                    .willReturn(capability.endpoint());
                            given(resultSet.getString("model_snapshot"))
                                    .willReturn(capability.modelSnapshot());
                            given(resultSet.getString("region")).willReturn(capability.region());
                            given(resultSet.getString("data_policy_version"))
                                    .willReturn(capability.dataPolicyVersion());
                            given(resultSet.getString("provider_voice")).willReturn("cedar");
                            given(resultSet.getString("native_controls"))
                                    .willReturn(
                                            "{\"speed\":1.0,\"instructions\":\"Measured"
                                                    + " narration.\"}");
                            return List.of(mapper.mapRow(resultSet, 0));
                        });
        given(
                        capabilityService.qualified(
                                capability.profileVersion(),
                                ProviderCapabilityService.ServiceKind.SPEECH,
                                ProviderCapabilityService.InputKind.CANONICAL_TEXT))
                .willReturn(capability);
        given(adapter.provider()).willReturn("openai");
        given(adapter.synthesize(any()))
                .willReturn(
                        new ProviderSpeechAdapter.SpeechResult(
                                "provider-request",
                                capability.modelSnapshot(),
                                capability.region(),
                                "cedar",
                                new byte[] {1, 2},
                                new ProviderUsage("INPUT_CHARACTER", 17, "AUDIO_BYTE", 2),
                                ProviderSpeechAdapter.ModelEvidenceSource.REQUESTED_MODEL));
        given(jdbcTemplate.update(anyString(), any(Object[].class))).willReturn(1);
        GovernedSpeechService service =
                new GovernedSpeechServiceImpl(
                        capabilityService,
                        List.of(adapter),
                        jdbcTemplate,
                        Clock.fixed(Instant.parse("2026-08-01T12:00:00Z"), ZoneOffset.UTC));

        GovernedSpeechService.SpeechOutcome outcome =
                service.synthesize(
                        new GovernedSpeechService.SpeechCommand(
                                recipeId, operationId, "Necessary prose."));

        assertThat(outcome.capabilityProfileVersion()).isEqualTo("openai-speech-eu-v2");
        assertThat(outcome.usage())
                .isEqualTo(new ProviderUsage("INPUT_CHARACTER", 17, "AUDIO_BYTE", 2));
        assertThat(outcome.speech().audio()).containsExactly(1, 2);
    }

    private static ProviderCapabilityService.CapabilityProfile capability() {
        return new ProviderCapabilityService.CapabilityProfile(
                UUID.randomUUID(),
                "openai-speech-eu-v2",
                "openai",
                ProviderCapabilityService.ServiceKind.SPEECH,
                "https://eu.api.openai.com/v1/audio/speech",
                "gpt-4o-mini-tts-2025-12-15",
                "SYNCHRONOUS",
                "eu",
                "openai-eu-zdr-v1",
                Set.of(ProviderCapabilityService.InputKind.CANONICAL_TEXT),
                4_096,
                "UTF8_CHARACTER",
                "REQUEST_PER_MINUTE",
                500,
                60,
                "INPUT_CHARACTER",
                "application/json",
                "audio/wav",
                "{\"response_format\":\"wav\"}",
                "{\"speed\":{\"minimum\":0.25,\"maximum\":4.0}}",
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2027-02-01T00:00:00Z"));
    }
}
