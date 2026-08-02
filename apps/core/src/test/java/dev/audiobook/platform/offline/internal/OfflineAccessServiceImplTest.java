package dev.audiobook.platform.offline.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import dev.audiobook.platform.library.PrivateAudiobookLibraryService;
import dev.audiobook.platform.offline.internal.OfflineAccessProperties;
import dev.audiobook.platform.offline.OfflineAccessService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class OfflineAccessServiceImplTest {

    @Test
    void normalizesAuthorizationTimesBeforeSigningAndReturningThem() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PrivateAudiobookLibraryService libraryService = mock(PrivateAudiobookLibraryService.class);
        OfflineAuthorizationSigner signer = mock(OfflineAuthorizationSigner.class);
        Instant clockTime = Instant.parse("2026-08-02T12:34:56.123456789Z");
        var properties = new OfflineAccessProperties(Duration.ofDays(30), 4, null, null, null);
        var command = new OfflineAccessService.IssueAuthorization(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "operation-1");
        var playback = new PrivateAudiobookLibraryService.PlaybackManifest(
                command.audiobookId(),
                command.assetVersionId(),
                UUID.randomUUID(),
                "application/pdf",
                "voice-1",
                "manifest-digest",
                1_000,
                new PrivateAudiobookLibraryService.ResumePosition(0, 0),
                List.of());
        given(libraryService.manifest(
                command.listenerId(), command.audiobookId(), command.assetVersionId())).willReturn(playback);
        given(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class))).willReturn(List.of());
        given(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).willReturn(1L);
        given(jdbcTemplate.update(anyString(), any(Object[].class))).willReturn(1);
        given(signer.sign(any())).willAnswer(invocation -> {
            OfflineAccessService.AuthorizationClaims claims = invocation.getArgument(0);
            return new OfflineAccessService.SignedAuthorization(
                    "ES256", "offline-v1", "public", "payload", "signature", claims);
        });
        var service = new OfflineAccessServiceImpl(
                jdbcTemplate,
                libraryService,
                signer,
                properties,
                Clock.fixed(clockTime, ZoneOffset.UTC));

        var authorization = service.issue(command);

        Instant databaseTime = Instant.parse("2026-08-02T12:34:56.123456Z");
        assertThat(authorization.serverTime()).isEqualTo(databaseTime);
        assertThat(authorization.authorization().claims().issuedAt()).isEqualTo(databaseTime);
        assertThat(authorization.authorization().claims().expiresAt()).isEqualTo(databaseTime.plus(Duration.ofDays(30)));
        var claims = ArgumentCaptor.forClass(OfflineAccessService.AuthorizationClaims.class);
        verify(signer).sign(claims.capture());
        assertThat(claims.getValue()).isEqualTo(authorization.authorization().claims());
    }
}
