package dev.audiobook.platform.identity;

import java.net.URI;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class JdbcListenerIdentityRepository implements ListenerIdentityRepository {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public Optional<ListenerSession> findByExternalIdentity(URI issuer, String subject) {
        return jdbcTemplate.query(
                """
                SELECT listener_id
                FROM external_identity_link
                WHERE issuer = ? AND subject = ?
                """,
                resultSet -> resultSet.next()
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
                    return Optional.of(new ListenerSession(listenerId, displayName, contactEmail, providers));
                },
                listenerId);
    }

    @Override
    public ListenerSession create(UUID listenerId, ExternalIdentity externalIdentity) {
        jdbcTemplate.update(
                "INSERT INTO listener_identity (listener_id, display_name, contact_email) VALUES (?, ?, ?)",
                listenerId,
                externalIdentity.displayName(),
                externalIdentity.email());
        insertLink(listenerId, externalIdentity);
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
