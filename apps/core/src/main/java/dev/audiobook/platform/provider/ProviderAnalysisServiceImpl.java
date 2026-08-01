package dev.audiobook.platform.provider;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProviderAnalysisServiceImpl implements ProviderAnalysisService {

    private final ProviderCapabilityService capabilityService;
    private final List<ProviderAnalysisAdapter> adapters;
    private final JdbcTemplate jdbcTemplate;
    private final Clock identityClock;

    @Override
    @Transactional
    public AnalysisOutcome analyze(AnalysisCommand command) {
        validate(command);
        ProviderCapabilityService.CapabilityProfile capability = capabilityService.qualified(
                command.capabilityProfileVersion(),
                ProviderCapabilityService.ServiceKind.ANALYSIS,
                command.unit().kind());
        long submittedUnits = command.unit().units(capability.inputUnit());
        if (submittedUnits <= 0 || submittedUnits > capability.maximumInputUnits()) {
            throw rejected(ProviderAnalysisException.Code.INPUT_LIMIT_EXCEEDED);
        }
        ProviderAnalysisAdapter adapter = adapters.stream()
                .filter(candidate -> capability.provider().equals(candidate.provider()))
                .findFirst()
                .orElseThrow(() -> rejected(ProviderAnalysisException.Code.ADAPTER_UNAVAILABLE));
        ProviderAnalysisAdapter.AnalysisResult result = adapter.analyze(
                new ProviderAnalysisAdapter.AnalysisRequest(
                        command.operationId(), capability, command.unit(), command.outputSchema()));
        if (result == null
                || !Objects.equals(capability.modelSnapshot(), result.actualModel())
                || !Objects.equals(capability.region(), result.actualRegion())) {
            throw rejected(ProviderAnalysisException.Code.PROVIDER_DRIFT);
        }
        validateSchema(command.outputSchema(), result.result());
        ProviderEvidence evidence = new ProviderEvidence(
                result.providerRequestId(),
                capability.profileVersion(),
                result.actualModel(),
                result.actualRegion(),
                capability.priceMeter(),
                result.usage());
        persistEvidence(
                command.operationId(), command.generationRecipeId(),
                capability, result, digest(result.result()));
        return new AnalysisOutcome(result.result().deepCopy(), evidence);
    }

    private void persistEvidence(
            String operationId,
            java.util.UUID generationRecipeId,
            ProviderCapabilityService.CapabilityProfile capability,
            ProviderAnalysisAdapter.AnalysisResult result,
            String outcomeDigest) {
        jdbcTemplate.update(
                """
                INSERT INTO provider.operation_evidence (
                    operation_id, service, capability_profile_id, capability_profile_version,
                    generation_recipe_id, provider_request_id, actual_model, model_evidence_source,
                    actual_region,
                    input_meter, input_units, output_meter, output_units,
                    price_meter, outcome_sha256, recorded_at
                ) VALUES (?, 'ANALYSIS', ?, ?, ?, ?, ?, 'PROVIDER_RESPONSE', ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                operationId,
                capability.profileId(),
                capability.profileVersion(),
                generationRecipeId,
                result.providerRequestId(),
                result.actualModel(),
                result.actualRegion(),
                result.usage().inputMeter(),
                result.usage().inputUnits(),
                result.usage().outputMeter(),
                result.usage().outputUnits(),
                capability.priceMeter(),
                outcomeDigest,
                Timestamp.from(identityClock.instant()));
    }

    private static void validate(AnalysisCommand command) {
        if (command == null
                || blank(command.operationId())
                || command.operationId().length() > 100
                || !opaque(command.operationId())
                || command.generationRecipeId() == null
                || blank(command.capabilityProfileVersion())
                || command.unit() == null
                || command.outputSchema() == null
                || blank(command.outputSchema().version())
                || command.outputSchema().fields().isEmpty()
                || !command.outputSchema().fields().keySet().containsAll(command.outputSchema().requiredFields())) {
            throw rejected(ProviderAnalysisException.Code.INVALID_COMMAND);
        }
    }

    private static void validateSchema(OutputSchema schema, JsonNode result) {
        if (result == null || !result.isObject()) {
            throw rejected(ProviderAnalysisException.Code.INVALID_SCHEMA_OUTCOME);
        }
        for (String required : schema.requiredFields()) {
            if (!result.has(required) || result.get(required).isNull()) {
                throw rejected(ProviderAnalysisException.Code.INVALID_SCHEMA_OUTCOME);
            }
        }
        if (!schema.allowAdditionalFields()) {
            result.fieldNames().forEachRemaining(field -> {
                if (!schema.fields().containsKey(field)) {
                    throw rejected(ProviderAnalysisException.Code.INVALID_SCHEMA_OUTCOME);
                }
            });
        }
        for (Map.Entry<String, ValueType> field : schema.fields().entrySet()) {
            JsonNode value = result.get(field.getKey());
            if (value != null && !value.isNull() && !matches(field.getValue(), value)) {
                throw rejected(ProviderAnalysisException.Code.INVALID_SCHEMA_OUTCOME);
            }
        }
    }

    private static boolean matches(ValueType type, JsonNode value) {
        return switch (type) {
            case STRING -> value.isTextual();
            case NUMBER -> value.isNumber();
            case INTEGER -> value.isIntegralNumber();
            case BOOLEAN -> value.isBoolean();
        };
    }

    private static String digest(JsonNode result) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(result.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean opaque(String value) {
        try {
            java.util.UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static ProviderAnalysisException rejected(ProviderAnalysisException.Code code) {
        return new ProviderAnalysisException(code);
    }
}
