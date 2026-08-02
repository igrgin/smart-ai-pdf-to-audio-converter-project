package dev.audiobook.platform.retention.deletion.persistence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.audiobook.platform.identifier.PlatformIdentifierGenerator;
import dev.audiobook.platform.retention.RetentionDigest;
import dev.audiobook.platform.retention.RetentionProperties;
import dev.audiobook.platform.retention.deletion.DeletionRequest;
import dev.audiobook.platform.retention.deletion.error.exception.DeletionConflictException;
import dev.audiobook.platform.retention.deletion.error.exception.DeletionPreconditionFailedException;
import dev.audiobook.platform.retention.deletion.error.exception.DeletionUnavailableException;
import dev.audiobook.platform.retention.tombstone.TombstoneRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

class JdbcDeletionRequestPersistenceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final RetentionDigest digest = mock(RetentionDigest.class);
    private final JdbcDeletionRequestPersistence persistence =
            new JdbcDeletionRequestPersistence(
                    jdbcTemplate,
                    mock(PlatformIdentifierGenerator.class),
                    properties(),
                    digest,
                    mock(TombstoneRegistry.class),
                    Clock.fixed(Instant.parse("2026-08-02T12:00:00Z"), ZoneOffset.UTC));

    @Test
    void rejectsInvalidInputBeforeTouchingPersistence() {
        assertThatThrownBy(
                        () ->
                                persistence.deleteAudiobook(
                                        new DeletionRequest.DeleteAudiobookCommand(
                                                UUID.randomUUID(), UUID.randomUUID(), -1, "delete")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                persistence.deleteAccount(
                                        new DeletionRequest.DeleteAccountCommand(
                                                UUID.randomUUID(), " ")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void deniesCrossOwnerOrUnavailableAudiobookWithoutCreatingARequest() {
        when(digest.digest(anyString(), anyString())).thenReturn("a".repeat(64));
        doReturn(List.of(), List.of(), List.of())
                .when(jdbcTemplate)
                .query(anyString(), any(RowMapper.class), any(Object[].class));

        assertThatThrownBy(
                        () ->
                                persistence.deleteAudiobook(
                                        new DeletionRequest.DeleteAudiobookCommand(
                                                UUID.randomUUID(), UUID.randomUUID(), 0, "delete")))
                .isInstanceOf(DeletionUnavailableException.class);
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void rejectsAStaleAudiobookVersionBeforeCreatingARequest() {
        when(digest.digest(anyString(), anyString())).thenReturn("b".repeat(64));
        doReturn(List.of(), List.of(), List.of(8L))
                .when(jdbcTemplate)
                .query(anyString(), any(RowMapper.class), any(Object[].class));

        assertThatThrownBy(
                        () ->
                                persistence.deleteAudiobook(
                                        new DeletionRequest.DeleteAudiobookCommand(
                                                UUID.randomUUID(), UUID.randomUUID(), 7, "delete")))
                .isInstanceOf(DeletionPreconditionFailedException.class);
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void rejectsReusingAnIdempotencyKeyForDifferentInput() throws Exception {
        when(digest.digest(anyString(), anyString())).thenReturn("c".repeat(64));
        Class<?> storedType =
                Class.forName(
                        JdbcDeletionRequestPersistence.class.getName() + "$StoredRequest");
        var constructor = storedType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Instant now = Instant.parse("2026-08-02T12:00:00Z");
        Object replay =
                constructor.newInstance(
                        UUID.randomUUID(),
                        DeletionRequest.DeletionScope.AUDIOBOOK,
                        DeletionRequest.DeletionState.ACCEPTED,
                        "different-fingerprint",
                        now,
                        now.plusSeconds(1),
                        now.plusSeconds(2),
                        now.plusSeconds(3),
                        now.plusSeconds(4),
                        null);
        doReturn(List.of(replay))
                .when(jdbcTemplate)
                .query(anyString(), any(RowMapper.class), any(Object[].class));

        assertThatThrownBy(
                        () ->
                                persistence.deleteAudiobook(
                                        new DeletionRequest.DeleteAudiobookCommand(
                                                UUID.randomUUID(), UUID.randomUUID(), 0, "reused")))
                .isInstanceOf(DeletionConflictException.class);
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    private static RetentionProperties properties() {
        return new RetentionProperties(
                "retention-test-key-with-32-characters",
                Path.of("retention-test"),
                "retention-test-bucket",
                Duration.ofHours(24),
                Duration.ofDays(23),
                Duration.ofDays(30),
                Duration.ofDays(90),
                Duration.ofDays(365),
                100,
                5,
                true);
    }
}
