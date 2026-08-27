package dev.agenticcommerce.gateway.identity.persistence;

import dev.agenticcommerce.gateway.identity.model.ActorPasswordCredential;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** PostgreSQL persistence boundary for one active P0 password credential per actor. */
@Repository
public class ActorPasswordCredentialRepository {

    private final JdbcClient jdbcClient;

    public ActorPasswordCredentialRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public ActorPasswordCredential createArgon2Credential(
            UUID actorId, String passwordHash, boolean enabled) {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(passwordHash, "passwordHash");
        if (!passwordHash.startsWith("$argon2")) {
            throw new IllegalArgumentException("Only Argon2 password hashes may be persisted");
        }

        return jdbcClient.sql("""
                        INSERT INTO actor_password_credential (actor_id, password_hash, enabled)
                        VALUES (:actorId, :passwordHash, :enabled)
                        RETURNING actor_id, password_hash, enabled, created_at, password_changed_at
                        """)
                .param("actorId", actorId)
                .param("passwordHash", passwordHash)
                .param("enabled", enabled)
                .query(ActorPasswordCredentialRepository::mapCredential)
                .single();
    }

    public Optional<ActorPasswordCredential> findByActorId(UUID actorId) {
        Objects.requireNonNull(actorId, "actorId");
        return jdbcClient.sql("""
                        SELECT actor_id, password_hash, enabled, created_at, password_changed_at
                        FROM actor_password_credential
                        WHERE actor_id = :actorId
                        """)
                .param("actorId", actorId)
                .query(ActorPasswordCredentialRepository::mapCredential)
                .optional();
    }

    private static ActorPasswordCredential mapCredential(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new ActorPasswordCredential(
                resultSet.getObject("actor_id", UUID.class),
                resultSet.getString("password_hash"),
                resultSet.getBoolean("enabled"),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("password_changed_at", OffsetDateTime.class).toInstant());
    }
}
