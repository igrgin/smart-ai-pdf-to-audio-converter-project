package dev.audiobook.platform.identity.internal.session;

import dev.audiobook.platform.identity.internal.oidc.ExternalIdentity;
import dev.audiobook.platform.identity.SignInProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.net.URI;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcListenerIdentityRepositoryTest {

    private static final UUID CANDIDATE_ID = UUID.fromString("01985f42-5f8d-7000-8000-000000000001");
    private static final UUID WINNER_ID = UUID.fromString("01985f42-5f8d-7000-8000-000000000002");
    private static final ExternalIdentity EXTERNAL_IDENTITY = new ExternalIdentity(
            URI.create("https://accounts.google.com"),
            "google-subject",
            SignInProvider.GOOGLE,
            "listener@example.test",
            "A Listener");

    private JdbcTemplate jdbcTemplate;
    private JdbcListenerIdentityRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        repository = spy(new JdbcListenerIdentityRepository(jdbcTemplate));
    }

    @Test
    void createdExternalLinkReturnsTheNewListenerWithoutCleanup() {
        ListenerSession created = session(CANDIDATE_ID);
        given(jdbcTemplate.update(anyString(), any(Object[].class))).willReturn(1, 1);
        doReturn(Optional.of(created)).when(repository).findById(CANDIDATE_ID);

        assertThat(repository.create(CANDIDATE_ID, EXTERNAL_IDENTITY)).isEqualTo(created);

        verify(jdbcTemplate, never()).update("DELETE FROM listener_identity WHERE listener_id = ?", CANDIDATE_ID);
    }

    @Test
    void conflictingExternalLinkDeletesTheCandidateAndReturnsTheWinner() {
        ListenerSession winner = session(WINNER_ID);
        given(jdbcTemplate.update(anyString(), any(Object[].class))).willReturn(1, 0, 1);
        doReturn(Optional.of(winner)).when(repository)
                .findByExternalIdentity(EXTERNAL_IDENTITY.issuer(), EXTERNAL_IDENTITY.subject());

        assertThat(repository.create(CANDIDATE_ID, EXTERNAL_IDENTITY)).isEqualTo(winner);

        verify(jdbcTemplate).update("DELETE FROM listener_identity WHERE listener_id = ?", CANDIDATE_ID);
    }

    private static ListenerSession session(UUID listenerId) {
        return new ListenerSession(
                listenerId,
                EXTERNAL_IDENTITY.displayName(),
                EXTERNAL_IDENTITY.email(),
                Set.of(SignInProvider.GOOGLE));
    }
}
