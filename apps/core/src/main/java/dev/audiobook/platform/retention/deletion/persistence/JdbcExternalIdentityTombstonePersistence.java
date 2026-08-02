package dev.audiobook.platform.retention.deletion.persistence;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JdbcExternalIdentityTombstonePersistence
        implements ExternalIdentityTombstonePersistence {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public boolean exists(String identityDigest) {
        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM retention.external_identity_tombstone"
                                + " WHERE identity_digest = ?",
                        Integer.class,
                        identityDigest);
        return count != null && count > 0;
    }
}
