package dev.audiobook.platform.provider.internal.speech;

import dev.audiobook.platform.provider.GovernedSpeechService;
import dev.audiobook.platform.provider.internal.governance.ProviderCapabilityService;

import dev.audiobook.platform.provider.SpeechProvider;
import dev.audiobook.platform.provider.SpeechProviderException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GovernedSpeechServiceImpl implements GovernedSpeechService {

    private final ProviderCapabilityService capabilityService;
    private final List<ProviderSpeechAdapter> adapters;
    private final JdbcTemplate jdbcTemplate;
    private final Clock identityClock;

    @Override
    @Transactional
    public SpeechOutcome synthesize(SpeechCommand command) {
        validate(command);
        FrozenRoute route = frozenRoute(command.generationRecipeId());
        ProviderCapabilityService.CapabilityProfile capability = capabilityService.qualified(
                route.profileVersion(),
                ProviderCapabilityService.ServiceKind.SPEECH,
                ProviderCapabilityService.InputKind.CANONICAL_TEXT);
        if (!route.matches(capability)) {
            throw failed(SpeechProviderException.Code.INVALID_REQUEST, false);
        }
        long submittedUnits = switch (capability.inputUnit()) {
            case "UTF8_CHARACTER" -> command.canonicalText().codePointCount(0, command.canonicalText().length());
            case "UTF8_BYTE" -> command.canonicalText().getBytes(StandardCharsets.UTF_8).length;
            default -> throw failed(SpeechProviderException.Code.INVALID_REQUEST, false);
        };
        if (submittedUnits <= 0 || submittedUnits > capability.maximumInputUnits()) {
            throw failed(SpeechProviderException.Code.INVALID_REQUEST, false);
        }
        ProviderSpeechAdapter adapter = adapters.stream()
                .filter(candidate -> capability.provider().equals(candidate.provider()))
                .findFirst()
                .orElseThrow(() -> failed(SpeechProviderException.Code.CONFIGURATION_UNAVAILABLE, false));
        ProviderSpeechAdapter.SpeechResult result = adapter.synthesize(
                new ProviderSpeechAdapter.SpeechRequest(
                        command.operationId(), capability, route.providerVoice(),
                        route.nativeControls(), command.canonicalText()));
        if (result == null
                || result.audio().length == 0
                || !Objects.equals(capability.modelSnapshot(), result.actualModel())
                || !Objects.equals(capability.region(), result.actualRegion())
                || !Objects.equals(route.providerVoice(), result.actualVoice())
                || result.usage() == null) {
            throw failed(SpeechProviderException.Code.INVALID_RESPONSE, false);
        }
        String digest = sha256(result.audio());
        jdbcTemplate.update(
                """
                INSERT INTO provider.operation_evidence (
                    operation_id, service, capability_profile_id, capability_profile_version,
                    generation_recipe_id, provider_request_id, actual_model, model_evidence_source,
                    actual_region,
                    input_meter, input_units, output_meter, output_units,
                    price_meter, outcome_sha256, recorded_at
                ) VALUES (?, 'SPEECH', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                command.operationId(), capability.profileId(), capability.profileVersion(),
                command.generationRecipeId(), result.providerRequestId(), result.actualModel(),
                result.modelEvidenceSource().name(), result.actualRegion(),
                result.usage().inputMeter(), result.usage().inputUnits(),
                result.usage().outputMeter(), result.usage().outputUnits(), capability.priceMeter(),
                digest, Timestamp.from(identityClock.instant()));
        SpeechProvider.SpeechResult speech = new SpeechProvider.SpeechResult(
                result.providerRequestId(), result.actualModel(), result.actualRegion(),
                result.actualVoice(), result.audio());
        return new SpeechOutcome(speech, capability.profileVersion(), result.usage());
    }

    private FrozenRoute frozenRoute(UUID recipeId) {
        List<FrozenRoute> routes = jdbcTemplate.query(
                """
                SELECT capability_profile_version, provider, endpoint, model_snapshot, region,
                       data_policy_version, provider_voice, native_controls::text AS native_controls
                FROM narration.generation_recipe WHERE recipe_id = ?
                """,
                (resultSet, row) -> new FrozenRoute(
                        resultSet.getString("capability_profile_version"),
                        resultSet.getString("provider"), resultSet.getString("endpoint"),
                        resultSet.getString("model_snapshot"), resultSet.getString("region"),
                        resultSet.getString("data_policy_version"), resultSet.getString("provider_voice"),
                        resultSet.getString("native_controls")),
                recipeId);
        if (routes.isEmpty()) {
            throw failed(SpeechProviderException.Code.INVALID_REQUEST, false);
        }
        return routes.getFirst();
    }

    private static void validate(SpeechCommand command) {
        if (command == null || command.generationRecipeId() == null
                || blank(command.operationId()) || command.operationId().length() > 100
                || blank(command.canonicalText())) {
            throw failed(SpeechProviderException.Code.INVALID_REQUEST, false);
        }
    }

    private static SpeechProviderException failed(SpeechProviderException.Code code, boolean retryable) {
        return new SpeechProviderException(code, retryable);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private record FrozenRoute(
            String profileVersion, String provider, String endpoint, String model,
            String region, String dataPolicyVersion, String providerVoice, String nativeControls) {
        boolean matches(ProviderCapabilityService.CapabilityProfile capability) {
            return provider.equals(capability.provider())
                    && endpoint.equals(capability.endpoint())
                    && model.equals(capability.modelSnapshot())
                    && region.equals(capability.region())
                    && dataPolicyVersion.equals(capability.dataPolicyVersion());
        }
    }
}
