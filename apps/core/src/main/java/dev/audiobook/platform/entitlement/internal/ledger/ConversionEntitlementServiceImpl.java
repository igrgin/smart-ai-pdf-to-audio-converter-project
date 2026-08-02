package dev.audiobook.platform.entitlement.internal.ledger;

import dev.audiobook.platform.entitlement.ConversionEntitlementService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ConversionEntitlementServiceImpl implements ConversionEntitlementService {

    private final JdbcTemplate jdbcTemplate;
    private final EntitlementPolicyProperties properties;
    private final Clock clock;

    @Override
    @Transactional
    public FreeGrant approveFreeGrant(UUID listenerId, String approvalReference, String idempotencyKey) {
        Objects.requireNonNull(listenerId, "listenerId");
        String approval = requiredReference(approvalReference, "approvalReference");
        String operationKey = requiredReference(idempotencyKey, "idempotencyKey");
        String fingerprint = fingerprint(listenerId, approval);
        lockEntitlementState();

        StoredOperation operation = findOperation(operationKey);
        if (operation != null) {
            verifyReplay(operation, "FREE_GRANT", fingerprint);
            return replay(findGrantById(operation.relatedId()));
        }
        if (findGrantByListener(listenerId) != null) {
            throw new IllegalStateException("Listener already has a free Conversion Entitlement");
        }

        UUID grantId = UUID.randomUUID();
        Instant validFrom = clock.instant();
        Instant validUntil = validFrom.plus(properties.freeGrantValidity());
        jdbcTemplate.update(
                """
                INSERT INTO conversion_entitlement_grant (
                    grant_id, listener_id, grant_kind, evidence_reference,
                    granted_characters, valid_from, valid_until, created_at
                ) VALUES (?, ?, 'FREE', ?, ?, ?, ?, ?)
                """,
                grantId,
                listenerId,
                approval,
                properties.freeGrantCharacters(),
                databaseTime(validFrom),
                databaseTime(validUntil),
                databaseTime(validFrom));
        jdbcTemplate.update(
                """
                INSERT INTO free_conversion_grant (
                    grant_id, listener_id, approval_reference, operation_key,
                    granted_characters, valid_from, valid_until, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                grantId,
                listenerId,
                approval,
                operationKey,
                properties.freeGrantCharacters(),
                databaseTime(validFrom),
                databaseTime(validUntil),
                databaseTime(validFrom));
        jdbcTemplate.update(
                """
                INSERT INTO character_entitlement_ledger_entry (
                    entry_id, grant_id, listener_id, operation_key, entry_type,
                    available_delta, reserved_delta, committed_delta, occurred_at
                ) VALUES (?, ?, ?, ?, 'GRANT', ?, 0, 0, ?)
                """,
                UUID.randomUUID(),
                grantId,
                listenerId,
                operationKey,
                properties.freeGrantCharacters(),
                databaseTime(validFrom));
        saveOperation(operationKey, "FREE_GRANT", fingerprint, "GRANTED", grantId, validFrom);
        audit(listenerId, null, null, "FREE_GRANT_APPROVED", "GRANTED", null, validFrom);
        return new FreeGrant(grantId, properties.freeGrantCharacters(), validFrom, validUntil, true);
    }

    @Override
    @Transactional(readOnly = true)
    public Allowance allowance(UUID listenerId) {
        Objects.requireNonNull(listenerId, "listenerId");
        EntitlementGrant activeGrant = findActiveGrant(listenerId, clock.instant());
        if (activeGrant != null) {
            return allowance(activeGrant, false);
        }
        EntitlementGrant latestGrant = findLatestGrant(listenerId);
        if (latestGrant == null) {
            return new Allowance(AllowanceStatus.NO_GRANT, 0, 0, 0, 0, "NO_GRANT");
        }
        return allowance(latestGrant, true);
    }

    private Allowance allowance(EntitlementGrant grant, boolean inactive) {
        return jdbcTemplate.query(
                """
                SELECT g.granted_characters,
                       COALESCE(SUM(e.available_delta), 0) AS available_characters,
                       COALESCE(SUM(e.reserved_delta), 0) AS reserved_characters,
                       COALESCE(SUM(e.committed_delta), 0) AS committed_characters
                FROM conversion_entitlement_grant g
                JOIN character_entitlement_ledger_entry e ON e.grant_id = g.grant_id
                WHERE g.grant_id = ?
                GROUP BY g.granted_characters
                """,
                resultSet -> {
                    resultSet.next();
                    long granted = resultSet.getLong("granted_characters");
                    long available = resultSet.getLong("available_characters");
                    long reserved = resultSet.getLong("reserved_characters");
                    long committed = resultSet.getLong("committed_characters");
                    if (inactive) {
                        return new Allowance(
                                AllowanceStatus.EXPIRED,
                                granted,
                                0,
                                reserved,
                                committed,
                                "GRANT_EXPIRED",
                                source(grant),
                                subscriptionStatus(grant));
                    }
                    if (available <= 0) {
                        return new Allowance(
                                AllowanceStatus.EXHAUSTED,
                                granted,
                                0,
                                reserved,
                                committed,
                                "ALLOWANCE_EXHAUSTED",
                                source(grant),
                                subscriptionStatus(grant));
                    }
                    return new Allowance(
                            AllowanceStatus.AVAILABLE,
                            granted,
                            available,
                            reserved,
                            committed,
                            null,
                            source(grant),
                            subscriptionStatus(grant));
                },
                grant.grantId());
    }

    private EntitlementSource source(EntitlementGrant grant) {
        return "DEMONSTRATION_SUBSCRIPTION".equals(grant.grantKind())
                ? EntitlementSource.DEMONSTRATION_SUBSCRIPTION
                : EntitlementSource.FREE;
    }

    private DemonstrationSubscriptionStatus subscriptionStatus(EntitlementGrant grant) {
        if (!"DEMONSTRATION_SUBSCRIPTION".equals(grant.grantKind())) {
            return null;
        }
        return jdbcTemplate.query(
                """
                SELECT s.subscription_status
                FROM demonstration_subscription s
                JOIN demonstration_subscription_invoice_grant i
                  ON i.stripe_subscription_id = s.stripe_subscription_id
                WHERE i.grant_id = ?
                """,
                resultSet -> resultSet.next()
                        ? DemonstrationSubscriptionStatus.valueOf(resultSet.getString("subscription_status"))
                        : null,
                grant.grantId());
    }

    @Override
    @Transactional
    public AdmissionDecision authorizeSpeech(AdmissionRequest request) {
        ValidatedAdmission admission = validate(request);
        lockEntitlementState();
        StoredOperation operation = findOperation(admission.operationKey());
        if (operation != null) {
            verifyReplay(operation, "ADMISSION", admission.fingerprint());
            return replayAdmission(operation);
        }

        Instant now = clock.instant();
        EntitlementGrant grant = findActiveGrant(admission.listenerId(), now);
        if (grant == null) {
            return deny(
                    admission,
                    findLatestGrant(admission.listenerId()) == null
                            ? AdmissionDenial.NO_GRANT
                            : AdmissionDenial.GRANT_EXPIRED,
                    now);
        }
        Allowance currentAllowance = allowance(admission.listenerId());
        if (currentAllowance.status() == AllowanceStatus.EXPIRED) {
            return deny(admission, AdmissionDenial.GRANT_EXPIRED, now);
        }
        if (hasConversionReservation(admission.conversionId())) {
            return deny(admission, AdmissionDenial.CONVERSION_ALREADY_RESERVED, now);
        }
        if (admission.characters() > properties.perConversionCharacterCeiling()) {
            return deny(admission, AdmissionDenial.PER_CONVERSION_CHARACTER_LIMIT, now);
        }
        if (admission.costMicros() > properties.perConversionSpendCeilingMicros()) {
            return deny(admission, AdmissionDenial.PER_CONVERSION_SPEND_LIMIT, now);
        }
        if (currentAllowance.availableCharacters() < admission.characters()) {
            return deny(admission, AdmissionDenial.INSUFFICIENT_CHARACTERS, now);
        }
        if (activeReservationCount(admission.listenerId()) >= properties.perListenerConcurrency()) {
            return deny(admission, AdmissionDenial.LISTENER_CONCURRENCY_LIMIT, now);
        }
        if (activeReservationCount(null) >= properties.globalConcurrency()) {
            return deny(admission, AdmissionDenial.GLOBAL_CONCURRENCY_LIMIT, now);
        }
        if (spendExposure("listener_id = ?", admission.listenerId()) + admission.costMicros()
                > properties.perListenerSpendCeilingMicros()) {
            return deny(admission, AdmissionDenial.LISTENER_SPEND_LIMIT, now);
        }
        if (spendExposure("provider = ?", admission.provider()) + admission.costMicros()
                > properties.providerSpendCeilingMicros()) {
            return deny(admission, AdmissionDenial.PROVIDER_SPEND_LIMIT, now);
        }
        if (spendExposure(null, null) + admission.costMicros() > properties.globalSpendCeilingMicros()) {
            return deny(admission, AdmissionDenial.GLOBAL_SPEND_LIMIT, now);
        }

        UUID reservationId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO character_entitlement_ledger_entry (
                    entry_id, grant_id, listener_id, conversion_id, reservation_id,
                    operation_key, entry_type, available_delta, reserved_delta,
                    committed_delta, occurred_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'RESERVATION', ?, ?, 0, ?)
                """,
                UUID.randomUUID(),
                grant.grantId(),
                admission.listenerId(),
                admission.conversionId(),
                reservationId,
                admission.operationKey(),
                -admission.characters(),
                admission.characters(),
                databaseTime(now));
        jdbcTemplate.update(
                """
                INSERT INTO provider_spend_ledger_entry (
                    entry_id, listener_id, conversion_id, reservation_id, provider,
                    generation_recipe_reference, rate_card_version, operation_key,
                    entry_type, reserved_delta, committed_delta, occurred_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'RESERVATION', ?, 0, ?)
                """,
                UUID.randomUUID(),
                admission.listenerId(),
                admission.conversionId(),
                reservationId,
                admission.provider(),
                admission.recipeReference(),
                admission.rateCardVersion(),
                admission.operationKey(),
                admission.costMicros(),
                databaseTime(now));
        saveOperation(
                admission.operationKey(), "ADMISSION", admission.fingerprint(), "AUTHORIZED", reservationId, now);
        audit(
                admission.listenerId(),
                admission.conversionId(),
                reservationId,
                "SPEECH_ADMISSION",
                "AUTHORIZED",
                null,
                now);
        return new AdmissionDecision(true, reservationId, null, false);
    }

    @Override
    @Transactional
    public Settlement settle(SettlementRequest request) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(request.reservationId(), "reservationId");
        if (request.committedCharacters() < 0 || request.incurredProviderCostMicros() < 0) {
            throw new IllegalArgumentException("Settlement amounts cannot be negative");
        }
        String operationKey = requiredReference(request.idempotencyKey(), "idempotencyKey");
        String fingerprint = fingerprint(
                request.reservationId(), request.committedCharacters(), request.incurredProviderCostMicros());
        lockEntitlementState();

        StoredOperation operation = findOperation(operationKey);
        if (operation != null) {
            verifyReplay(operation, "SETTLEMENT", fingerprint);
            return new Settlement(
                    request.reservationId(),
                    request.committedCharacters(),
                    request.incurredProviderCostMicros(),
                    true);
        }

        Reservation reservation = findReservation(request.reservationId());
        if (reservation == null || reservation.reservedCharacters() <= 0 || reservation.reservedCostMicros() <= 0) {
            throw new IllegalStateException("Reservation is not active");
        }
        if (request.committedCharacters() > reservation.reservedCharacters()
                || request.incurredProviderCostMicros() > reservation.reservedCostMicros()) {
            throw new IllegalArgumentException("Settlement cannot exceed its reservation");
        }

        Instant now = clock.instant();
        long characterRelease = now.isBefore(reservation.grantValidUntil()) && !isGrantRevoked(reservation.grantId())
                ? reservation.reservedCharacters() - request.committedCharacters()
                : 0;
        jdbcTemplate.update(
                """
                INSERT INTO character_entitlement_ledger_entry (
                    entry_id, grant_id, listener_id, conversion_id, reservation_id,
                    operation_key, entry_type, available_delta, reserved_delta,
                    committed_delta, occurred_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'SETTLEMENT', ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                reservation.grantId(),
                reservation.listenerId(),
                reservation.conversionId(),
                request.reservationId(),
                operationKey,
                characterRelease,
                -reservation.reservedCharacters(),
                request.committedCharacters(),
                databaseTime(now));
        jdbcTemplate.update(
                """
                INSERT INTO provider_spend_ledger_entry (
                    entry_id, listener_id, conversion_id, reservation_id, provider,
                    generation_recipe_reference, rate_card_version, operation_key,
                    entry_type, reserved_delta, committed_delta, occurred_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'SETTLEMENT', ?, ?, ?)
                """,
                UUID.randomUUID(),
                reservation.listenerId(),
                reservation.conversionId(),
                request.reservationId(),
                reservation.provider(),
                reservation.recipeReference(),
                reservation.rateCardVersion(),
                operationKey,
                -reservation.reservedCostMicros(),
                request.incurredProviderCostMicros(),
                databaseTime(now));
        saveOperation(operationKey, "SETTLEMENT", fingerprint, "SETTLED", request.reservationId(), now);
        audit(
                reservation.listenerId(),
                reservation.conversionId(),
                request.reservationId(),
                "RESERVATION_SETTLED",
                "SETTLED",
                null,
                now);
        return new Settlement(
                request.reservationId(),
                request.committedCharacters(),
                request.incurredProviderCostMicros(),
                false);
    }

    @Override
    @Transactional(readOnly = true)
    public ResumeEligibility resumeEligibility(UUID listenerId, UUID conversionId) {
        Objects.requireNonNull(listenerId, "listenerId");
        Objects.requireNonNull(conversionId, "conversionId");
        Instant now = clock.instant();
        Integer eligible = jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM (
                    SELECT c.reservation_id
                    FROM character_entitlement_ledger_entry c
                    JOIN conversion_entitlement_grant g ON g.grant_id = c.grant_id
                    JOIN provider_spend_ledger_entry p ON p.reservation_id = c.reservation_id
                    WHERE c.listener_id = ? AND c.conversion_id = ?
                      AND g.valid_from <= ? AND ? < g.valid_until
                      AND NOT EXISTS (
                          SELECT 1 FROM demonstration_subscription_grant_adjustment a
                          WHERE a.grant_id = g.grant_id
                      )
                    GROUP BY c.reservation_id
                    HAVING SUM(c.reserved_delta) > 0 AND SUM(p.reserved_delta) > 0
                ) active_reservation
                """,
                Integer.class,
                listenerId,
                conversionId,
                databaseTime(now),
                databaseTime(now));
        return eligible != null && eligible == 1
                ? new ResumeEligibility(true, null)
                : new ResumeEligibility(false, "ENTITLEMENT_RESERVATION_INELIGIBLE");
    }

    @Override
    @Transactional(readOnly = true)
    public ProviderSpend providerSpend(String provider) {
        String normalizedProvider = requiredReference(provider, "provider").toLowerCase(Locale.ROOT);
        return jdbcTemplate.queryForObject(
                """
                SELECT COALESCE(SUM(reserved_delta), 0) AS reserved_micros,
                       COALESCE(SUM(committed_delta), 0) AS committed_micros
                FROM provider_spend_ledger_entry
                WHERE provider = ?
                """,
                (resultSet, rowNumber) -> new ProviderSpend(
                        resultSet.getLong("reserved_micros"), resultSet.getLong("committed_micros")),
                normalizedProvider);
    }

    @Override
    @Transactional
    public Correction correctCharacters(CorrectionRequest request) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(request.listenerId(), "listenerId");
        if (request.availableCharacterDelta() == 0) {
            throw new IllegalArgumentException("Correction delta cannot be zero");
        }
        String evidence = requiredReference(request.evidenceReference(), "evidenceReference");
        String operationKey = requiredReference(request.idempotencyKey(), "idempotencyKey");
        String fingerprint = fingerprint(request.listenerId(), request.availableCharacterDelta(), evidence);
        lockEntitlementState();

        StoredOperation operation = findOperation(operationKey);
        if (operation != null) {
            verifyReplay(operation, "CORRECTION", fingerprint);
            return new Correction(request.availableCharacterDelta(), true);
        }
        FreeGrant grant = findGrantByListener(request.listenerId());
        if (grant == null) {
            throw new IllegalStateException("Listener has no free Conversion Entitlement");
        }
        GrantBalance current = grantBalance(grant.grantId());
        if (current.expired() || !clock.instant().isBefore(grant.validUntil())) {
            throw new IllegalStateException("Free Conversion Entitlement is expired");
        }
        if (current.availableCharacters() + request.availableCharacterDelta() < 0) {
            throw new IllegalArgumentException("Correction cannot make available characters negative");
        }
        if (current.availableCharacters() + request.availableCharacterDelta() > grant.grantedCharacters()) {
            throw new IllegalArgumentException("Correction cannot exceed the free grant ceiling");
        }

        Instant now = clock.instant();
        jdbcTemplate.update(
                """
                INSERT INTO character_entitlement_ledger_entry (
                    entry_id, grant_id, listener_id, operation_key, entry_type,
                    available_delta, reserved_delta, committed_delta, occurred_at
                ) VALUES (?, ?, ?, ?, 'CORRECTION', ?, 0, 0, ?)
                """,
                UUID.randomUUID(),
                grant.grantId(),
                request.listenerId(),
                operationKey,
                request.availableCharacterDelta(),
                databaseTime(now));
        saveOperation(operationKey, "CORRECTION", fingerprint, "CORRECTED", grant.grantId(), now);
        audit(request.listenerId(), null, null, "CHARACTER_CORRECTION", "CORRECTED", null, now);
        return new Correction(request.availableCharacterDelta(), false);
    }

    @Override
    @Transactional
    public Expiry expireFreeGrant(UUID listenerId, String evidenceReference, String idempotencyKey) {
        Objects.requireNonNull(listenerId, "listenerId");
        String evidence = requiredReference(evidenceReference, "evidenceReference");
        String operationKey = requiredReference(idempotencyKey, "idempotencyKey");
        String fingerprint = fingerprint(listenerId, evidence);
        lockEntitlementState();

        StoredOperation operation = findOperation(operationKey);
        if (operation != null) {
            verifyReplay(operation, "EXPIRY", fingerprint);
            Long delta = jdbcTemplate.queryForObject(
                    """
                    SELECT -available_delta FROM character_entitlement_ledger_entry
                    WHERE operation_key = ? AND entry_type = 'EXPIRY'
                    """,
                    Long.class,
                    operationKey);
            return new Expiry(delta == null ? 0 : delta, true);
        }
        FreeGrant grant = findGrantByListener(listenerId);
        if (grant == null) {
            throw new IllegalStateException("Listener has no free Conversion Entitlement");
        }
        GrantBalance current = grantBalance(grant.grantId());
        if (current.expired() || !clock.instant().isBefore(grant.validUntil())) {
            throw new IllegalStateException("Free Conversion Entitlement is already expired");
        }

        Instant now = clock.instant();
        long expiredCharacters = current.availableCharacters();
        jdbcTemplate.update(
                """
                INSERT INTO character_entitlement_ledger_entry (
                    entry_id, grant_id, listener_id, operation_key, entry_type,
                    available_delta, reserved_delta, committed_delta, occurred_at
                ) VALUES (?, ?, ?, ?, 'EXPIRY', ?, 0, 0, ?)
                """,
                UUID.randomUUID(),
                grant.grantId(),
                listenerId,
                operationKey,
                -expiredCharacters,
                databaseTime(now));
        saveOperation(operationKey, "EXPIRY", fingerprint, "EXPIRED", grant.grantId(), now);
        audit(listenerId, null, null, "FREE_GRANT_EXPIRED", "EXPIRED", null, now);
        return new Expiry(expiredCharacters, false);
    }

    private AdmissionDecision deny(ValidatedAdmission admission, AdmissionDenial denial, Instant now) {
        saveOperation(
                admission.operationKey(), "ADMISSION", admission.fingerprint(), denial.name(), null, now);
        audit(
                admission.listenerId(),
                admission.conversionId(),
                null,
                "SPEECH_ADMISSION",
                "DENIED",
                denial.name(),
                now);
        return new AdmissionDecision(false, null, denial, false);
    }

    private AdmissionDecision replayAdmission(StoredOperation operation) {
        if ("AUTHORIZED".equals(operation.outcome())) {
            return new AdmissionDecision(true, operation.relatedId(), null, true);
        }
        return new AdmissionDecision(false, null, AdmissionDenial.valueOf(operation.outcome()), true);
    }

    private ValidatedAdmission validate(AdmissionRequest request) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(request.listenerId(), "listenerId");
        Objects.requireNonNull(request.conversionId(), "conversionId");
        if (request.narratableCharacters() <= 0 || request.conservativeProviderCostMicros() <= 0) {
            throw new IllegalArgumentException("Admission amounts must be positive");
        }
        String provider = requiredReference(request.provider(), "provider").toLowerCase(Locale.ROOT);
        String recipe = requiredReference(request.generationRecipeReference(), "generationRecipeReference");
        String rateCard = requiredReference(request.rateCardVersion(), "rateCardVersion");
        String operation = requiredReference(request.idempotencyKey(), "idempotencyKey");
        return new ValidatedAdmission(
                request.listenerId(),
                request.conversionId(),
                provider,
                recipe,
                rateCard,
                request.narratableCharacters(),
                request.conservativeProviderCostMicros(),
                operation,
                fingerprint(
                        request.listenerId(),
                        request.conversionId(),
                        provider,
                        recipe,
                        rateCard,
                        request.narratableCharacters(),
                        request.conservativeProviderCostMicros()));
    }

    private FreeGrant findGrantByListener(UUID listenerId) {
        return findGrant("listener_id = ?", listenerId);
    }

    private FreeGrant findGrantById(UUID grantId) {
        return findGrant("grant_id = ?", grantId);
    }

    private FreeGrant findGrant(String predicate, Object value) {
        return jdbcTemplate.query(
                """
                SELECT f.grant_id, g.granted_characters, g.valid_from, g.valid_until
                FROM free_conversion_grant f
                JOIN conversion_entitlement_grant g ON g.grant_id = f.grant_id
                WHERE f.%s
                """.formatted(predicate),
                resultSet -> resultSet.next()
                        ? new FreeGrant(
                                resultSet.getObject("grant_id", UUID.class),
                                resultSet.getLong("granted_characters"),
                                resultSet.getObject("valid_from", OffsetDateTime.class).toInstant(),
                                resultSet.getObject("valid_until", OffsetDateTime.class).toInstant(),
                                false)
                        : null,
                value);
    }

    private EntitlementGrant findActiveGrant(UUID listenerId, Instant now) {
        return jdbcTemplate.query(
                """
                SELECT g.grant_id, g.grant_kind, g.granted_characters, g.valid_from, g.valid_until
                FROM conversion_entitlement_grant g
                WHERE g.listener_id = ? AND g.valid_from <= ? AND ? < g.valid_until
                  AND NOT EXISTS (
                      SELECT 1 FROM demonstration_subscription_grant_adjustment a
                      WHERE a.grant_id = g.grant_id
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM character_entitlement_ledger_entry e
                      WHERE e.grant_id = g.grant_id AND e.entry_type = 'EXPIRY'
                  )
                ORDER BY CASE g.grant_kind WHEN 'DEMONSTRATION_SUBSCRIPTION' THEN 0 ELSE 1 END,
                         g.valid_from DESC
                LIMIT 1
                """,
                resultSet -> resultSet.next() ? entitlementGrant(resultSet) : null,
                listenerId,
                databaseTime(now),
                databaseTime(now));
    }

    private EntitlementGrant findLatestGrant(UUID listenerId) {
        return jdbcTemplate.query(
                """
                SELECT grant_id, grant_kind, granted_characters, valid_from, valid_until
                FROM conversion_entitlement_grant
                WHERE listener_id = ?
                ORDER BY valid_from DESC
                LIMIT 1
                """,
                resultSet -> resultSet.next() ? entitlementGrant(resultSet) : null,
                listenerId);
    }

    private static EntitlementGrant entitlementGrant(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new EntitlementGrant(
                resultSet.getObject("grant_id", UUID.class),
                resultSet.getString("grant_kind"),
                resultSet.getLong("granted_characters"),
                resultSet.getObject("valid_from", OffsetDateTime.class).toInstant(),
                resultSet.getObject("valid_until", OffsetDateTime.class).toInstant());
    }

    private GrantBalance grantBalance(UUID grantId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT COALESCE(SUM(available_delta), 0),
                       COALESCE(SUM(reserved_delta), 0),
                       COALESCE(SUM(committed_delta), 0),
                       COUNT(*) FILTER (WHERE entry_type = 'EXPIRY') > 0
                FROM character_entitlement_ledger_entry
                WHERE grant_id = ?
                """,
                (resultSet, rowNumber) -> new GrantBalance(
                        resultSet.getLong(1),
                        resultSet.getLong(2),
                        resultSet.getLong(3),
                        resultSet.getBoolean(4)),
                grantId);
    }

    private boolean isGrantRevoked(UUID grantId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM demonstration_subscription_grant_adjustment WHERE grant_id = ?",
                Integer.class,
                grantId);
        return count != null && count > 0;
    }

    private Reservation findReservation(UUID reservationId) {
        return jdbcTemplate.query(
                """
                WITH character_totals AS (
                    SELECT grant_id, listener_id, conversion_id, reservation_id,
                           SUM(reserved_delta) AS reserved_characters
                    FROM character_entitlement_ledger_entry
                    WHERE reservation_id = ?
                    GROUP BY grant_id, listener_id, conversion_id, reservation_id
                ), provider_totals AS (
                    SELECT reservation_id, provider, generation_recipe_reference, rate_card_version,
                           SUM(reserved_delta) AS reserved_cost_micros
                    FROM provider_spend_ledger_entry
                    WHERE reservation_id = ?
                    GROUP BY reservation_id, provider, generation_recipe_reference, rate_card_version
                )
                SELECT c.grant_id, c.listener_id, c.conversion_id, g.valid_until,
                       c.reserved_characters, p.provider, p.generation_recipe_reference,
                       p.rate_card_version, p.reserved_cost_micros
                FROM character_totals c
                JOIN conversion_entitlement_grant g ON g.grant_id = c.grant_id
                JOIN provider_totals p ON p.reservation_id = c.reservation_id
                """,
                resultSet -> resultSet.next()
                        ? new Reservation(
                                resultSet.getObject("grant_id", UUID.class),
                                resultSet.getObject("listener_id", UUID.class),
                                resultSet.getObject("conversion_id", UUID.class),
                                resultSet.getObject("valid_until", OffsetDateTime.class).toInstant(),
                                resultSet.getLong("reserved_characters"),
                                resultSet.getString("provider"),
                                resultSet.getString("generation_recipe_reference"),
                                resultSet.getString("rate_card_version"),
                                resultSet.getLong("reserved_cost_micros"))
                        : null,
                reservationId,
                reservationId);
    }

    private boolean hasConversionReservation(UUID conversionId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM character_entitlement_ledger_entry
                WHERE conversion_id = ? AND entry_type = 'RESERVATION'
                """,
                Integer.class,
                conversionId);
        return count != null && count > 0;
    }

    private int activeReservationCount(UUID listenerId) {
        String listenerPredicate = listenerId == null ? "" : "WHERE listener_id = ?";
        String sql = """
                SELECT count(*)
                FROM (
                    SELECT reservation_id
                    FROM character_entitlement_ledger_entry
                    %s
                    GROUP BY reservation_id
                    HAVING reservation_id IS NOT NULL AND SUM(reserved_delta) > 0
                ) active
                """.formatted(listenerPredicate);
        Integer count = listenerId == null
                ? jdbcTemplate.queryForObject(sql, Integer.class)
                : jdbcTemplate.queryForObject(sql, Integer.class, listenerId);
        return count == null ? 0 : count;
    }

    private long spendExposure(String predicate, Object value) {
        String where = predicate == null ? "" : "WHERE " + predicate;
        String sql = """
                SELECT COALESCE(SUM(reserved_delta + committed_delta), 0)
                FROM provider_spend_ledger_entry
                %s
                """.formatted(where);
        Long exposure = predicate == null
                ? jdbcTemplate.queryForObject(sql, Long.class)
                : jdbcTemplate.queryForObject(sql, Long.class, value);
        return exposure == null ? 0 : exposure;
    }

    private void lockEntitlementState() {
        jdbcTemplate.queryForObject(
                "SELECT lock_id FROM entitlement_transaction_lock WHERE lock_id = 1 FOR UPDATE",
                Short.class);
    }

    private StoredOperation findOperation(String operationKey) {
        return jdbcTemplate.query(
                """
                SELECT operation_type, request_fingerprint, outcome, related_id
                FROM entitlement_operation
                WHERE operation_key = ?
                """,
                resultSet -> resultSet.next()
                        ? new StoredOperation(
                                resultSet.getString("operation_type"),
                                resultSet.getString("request_fingerprint"),
                                resultSet.getString("outcome"),
                                resultSet.getObject("related_id", UUID.class))
                        : null,
                operationKey);
    }

    private void saveOperation(
            String operationKey,
            String operationType,
            String requestFingerprint,
            String outcome,
            UUID relatedId,
            Instant now) {
        jdbcTemplate.update(
                """
                INSERT INTO entitlement_operation (
                    operation_key, operation_type, request_fingerprint, outcome, related_id, created_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                operationKey,
                operationType,
                requestFingerprint,
                outcome,
                relatedId,
                databaseTime(now));
    }

    private void audit(
            UUID listenerId,
            UUID conversionId,
            UUID reservationId,
            String eventType,
            String decision,
            String reasonCode,
            Instant now) {
        jdbcTemplate.update(
                """
                INSERT INTO entitlement_audit_event (
                    event_id, listener_id, conversion_id, reservation_id,
                    event_type, decision, reason_code, occurred_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                listenerId,
                conversionId,
                reservationId,
                eventType,
                decision,
                reasonCode,
                databaseTime(now));
    }

    private static void verifyReplay(StoredOperation operation, String type, String fingerprint) {
        if (!operation.type().equals(type) || !operation.fingerprint().equals(fingerprint)) {
            throw new IllegalArgumentException("Idempotency key was already used for a different operation");
        }
    }

    private static FreeGrant replay(FreeGrant grant) {
        return new FreeGrant(
                grant.grantId(), grant.grantedCharacters(), grant.validFrom(), grant.validUntil(), false);
    }

    private static String requiredReference(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 200) {
            throw new IllegalArgumentException(name + " must contain between 1 and 200 characters");
        }
        return value.strip();
    }

    private static String fingerprint(Object... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object value : values) {
                digest.update(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private static OffsetDateTime databaseTime(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private record StoredOperation(String type, String fingerprint, String outcome, UUID relatedId) {
    }

    private record ValidatedAdmission(
            UUID listenerId,
            UUID conversionId,
            String provider,
            String recipeReference,
            String rateCardVersion,
            long characters,
            long costMicros,
            String operationKey,
            String fingerprint) {
    }

    private record Reservation(
            UUID grantId,
            UUID listenerId,
            UUID conversionId,
            Instant grantValidUntil,
            long reservedCharacters,
            String provider,
            String recipeReference,
            String rateCardVersion,
            long reservedCostMicros) {
    }

    private record EntitlementGrant(
            UUID grantId,
            String grantKind,
            long grantedCharacters,
            Instant validFrom,
            Instant validUntil) {
    }

    private record GrantBalance(
            long availableCharacters,
            long reservedCharacters,
            long committedCharacters,
            boolean expired) {
    }
}
