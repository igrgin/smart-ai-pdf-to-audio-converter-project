package dev.audiobook.platform.identity.listener;

import dev.audiobook.platform.identity.SignInProvider;
import dev.audiobook.platform.identity.linking.IdentityLinkConflictException;
import dev.audiobook.platform.identity.listener.service.*;
import dev.audiobook.platform.identity.session.ListenerSession;
import dev.audiobook.platform.identity.signin.ExternalIdentity;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.net.URI;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcListenerIdentityRepository implements ListenerIdentityRepository {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public Optional<ListenerSession> findByExternalIdentity(URI issuer, String subject) {
        return jdbcTemplate.query(
                """
                SELECT listener_id
                FROM external_identity_link
                WHERE issuer = ? AND subject = ?
                """,
                resultSet ->
                        resultSet.next()
                                ? findById(resultSet.getObject("listener_id", UUID.class))
                                : Optional.empty(),
                issuer.toString(),
                subject);
    }

    @Override
    public Optional<ListenerSession> findById(UUID listenerId) {
        return jdbcTemplate.query(
                """
                SELECT i.listener_id, i.display_name, i.contact_email, l.provider
                FROM listener_identity i
                JOIN external_identity_link l ON l.listener_id = i.listener_id
                WHERE i.listener_id = ?
                ORDER BY l.provider
                """,
                resultSet -> {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    String displayName = resultSet.getString("display_name");
                    String contactEmail = resultSet.getString("contact_email");
                    EnumSet<SignInProvider> providers = EnumSet.noneOf(SignInProvider.class);
                    do {
                        providers.add(SignInProvider.valueOf(resultSet.getString("provider")));
                    } while (resultSet.next());
                    return Optional.of(
                            new ListenerSession(listenerId, displayName, contactEmail, providers));
                },
                listenerId);
    }

    @Override
    public ListenerSession create(UUID listenerId, ExternalIdentity externalIdentity) {
        jdbcTemplate.update(
                "INSERT INTO listener_identity (listener_id, display_name, contact_email) VALUES"
                        + " (?, ?, ?)",
                listenerId,
                externalIdentity.displayName(),
                externalIdentity.email());
        int created =
                jdbcTemplate.update(
                        """
                        INSERT INTO external_identity_link (issuer, subject, listener_id, provider)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT (issuer, subject) DO NOTHING
                        """,
                        externalIdentity.issuer().toString(),
                        externalIdentity.subject(),
                        listenerId,
                        externalIdentity.provider().name());
        if (created == 0) {
            jdbcTemplate.update("DELETE FROM listener_identity WHERE listener_id = ?", listenerId);
            return findByExternalIdentity(externalIdentity.issuer(), externalIdentity.subject())
                    .orElseThrow();
        }
        return findById(listenerId).orElseThrow();
    }

    @Override
    public ListenerSession link(UUID listenerId, ExternalIdentity externalIdentity) {
        insertLink(listenerId, externalIdentity);
        return findById(listenerId).orElseThrow(IdentityLinkConflictException::new);
    }

    private void insertLink(UUID listenerId, ExternalIdentity externalIdentity) {
        jdbcTemplate.update(
                """
                INSERT INTO external_identity_link (issuer, subject, listener_id, provider)
                VALUES (?, ?, ?, ?)
                """,
                externalIdentity.issuer().toString(),
                externalIdentity.subject(),
                listenerId,
                externalIdentity.provider().name());
    }
}
