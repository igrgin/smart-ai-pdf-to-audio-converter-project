package dev.audiobook.platform.provider.governance.service;

import dev.audiobook.platform.provider.governance.*;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProviderCapabilityServiceImpl implements ProviderCapabilityService {

    private final JdbcTemplate jdbcTemplate;
    private final Clock identityClock;

    @Override
    @Transactional(readOnly = true)
    public CapabilityProfile qualified(
            String profileVersion, ServiceKind service, InputKind input) {
        if (profileVersion == null
                || profileVersion.isBlank()
                || service == null
                || input == null) {
            throw new IllegalArgumentException(
                    "A complete provider capability request is required");
        }
        List<StoredProfile> profiles =
                jdbcTemplate.query(
                        """
                        SELECT profile_id, profile_version, provider, service, endpoint, model_snapshot,
                               delivery_mode, region, data_policy_version, supported_inputs,
                               maximum_input_units, input_unit, quota_meter, quota_limit,
                               quota_window_seconds, price_meter, request_format, response_format,
                               native_controls::text, native_controls_schema::text, checked_at, expires_at,
                               profile_state, privacy_state, region_state, access_state,
                               quota_state, evaluation_state
                        FROM narration.provider_capability_profile
                        WHERE profile_version = ?
                        """,
                        (resultSet, row) ->
                                new StoredProfile(
                                        resultSet.getObject("profile_id", UUID.class),
                                        resultSet.getString("profile_version"),
                                        resultSet.getString("provider"),
                                        resultSet.getString("service"),
                                        resultSet.getString("endpoint"),
                                        resultSet.getString("model_snapshot"),
                                        resultSet.getString("delivery_mode"),
                                        resultSet.getString("region"),
                                        resultSet.getString("data_policy_version"),
                                        inputKinds(resultSet.getArray("supported_inputs")),
                                        resultSet.getLong("maximum_input_units"),
                                        resultSet.getString("input_unit"),
                                        resultSet.getString("quota_meter"),
                                        resultSet.getLong("quota_limit"),
                                        resultSet.getInt("quota_window_seconds"),
                                        resultSet.getString("price_meter"),
                                        resultSet.getString("request_format"),
                                        resultSet.getString("response_format"),
                                        resultSet.getString("native_controls"),
                                        resultSet.getString("native_controls_schema"),
                                        resultSet.getTimestamp("checked_at").toInstant(),
                                        resultSet.getTimestamp("expires_at").toInstant(),
                                        resultSet.getString("profile_state"),
                                        resultSet.getString("privacy_state"),
                                        resultSet.getString("region_state"),
                                        resultSet.getString("access_state"),
                                        resultSet.getString("quota_state"),
                                        resultSet.getString("evaluation_state")),
                        profileVersion);
        if (profiles.isEmpty()) {
            throw rejected(ProviderCapabilityRejectedException.Code.PROFILE_UNAVAILABLE);
        }
        StoredProfile profile = profiles.getFirst();
        Instant now = identityClock.instant();
        if (!profile.service().equalsIgnoreCase(service.name())) {
            throw rejected(ProviderCapabilityRejectedException.Code.SERVICE_UNSUPPORTED);
        }
        if (!profile.supportedInputs().contains(input)) {
            throw rejected(ProviderCapabilityRejectedException.Code.INPUT_UNSUPPORTED);
        }
        if (!"CURRENT".equals(profile.profileState())
                || now.isBefore(profile.validFrom())
                || !now.isBefore(profile.validUntil())) {
            throw rejected(ProviderCapabilityRejectedException.Code.PROFILE_STALE);
        }
        if (!qualified(profile.privacyState())) {
            throw rejected(ProviderCapabilityRejectedException.Code.PRIVACY_STALE);
        }
        if (!qualified(profile.regionState())) {
            throw rejected(ProviderCapabilityRejectedException.Code.REGION_STALE);
        }
        if (!qualified(profile.accessState())) {
            throw rejected(ProviderCapabilityRejectedException.Code.ACCESS_STALE);
        }
        if (!qualified(profile.quotaState())) {
            throw rejected(ProviderCapabilityRejectedException.Code.QUOTA_STALE);
        }
        if (!qualified(profile.evaluationState())) {
            throw rejected(ProviderCapabilityRejectedException.Code.EVALUATION_STALE);
        }
        return profile.capability();
    }

    private static boolean qualified(String state) {
        return "QUALIFIED".equals(state);
    }

    private static ProviderCapabilityRejectedException rejected(
            ProviderCapabilityRejectedException.Code code) {
        return new ProviderCapabilityRejectedException(code);
    }

    private static Set<InputKind> inputKinds(Array array) throws SQLException {
        return Arrays.stream((String[]) array.getArray())
                .map(InputKind::valueOf)
                .collect(Collectors.toUnmodifiableSet());
    }

    private record StoredProfile(
            UUID profileId,
            String profileVersion,
            String provider,
            String service,
            String endpoint,
            String modelSnapshot,
            String deliveryMode,
            String region,
            String dataPolicyVersion,
            Set<InputKind> supportedInputs,
            long maximumInputUnits,
            String inputUnit,
            String quotaMeter,
            long quotaLimit,
            int quotaWindowSeconds,
            String priceMeter,
            String requestFormat,
            String responseFormat,
            String nativeControls,
            String nativeControlsSchema,
            Instant validFrom,
            Instant validUntil,
            String profileState,
            String privacyState,
            String regionState,
            String accessState,
            String quotaState,
            String evaluationState) {

        CapabilityProfile capability() {
            return new CapabilityProfile(
                    profileId,
                    profileVersion,
                    provider,
                    ServiceKind.valueOf(service.toUpperCase(java.util.Locale.ROOT)),
                    endpoint,
                    modelSnapshot,
                    deliveryMode,
                    region,
                    dataPolicyVersion,
                    supportedInputs,
                    maximumInputUnits,
                    inputUnit,
                    quotaMeter,
                    quotaLimit,
                    quotaWindowSeconds,
                    priceMeter,
                    requestFormat,
                    responseFormat,
                    nativeControls,
                    nativeControlsSchema,
                    validFrom,
                    validUntil);
        }
    }
}
