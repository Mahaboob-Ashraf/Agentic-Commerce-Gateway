package dev.agenticcommerce.gateway.identity.persistence;

import dev.agenticcommerce.gateway.identity.model.ApplicationActor;
import dev.agenticcommerce.gateway.identity.model.PlatformRole;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** PostgreSQL persistence boundary for application actors and their canonical role. */
@Repository
public class ApplicationActorRepository {

    private final JdbcClient jdbcClient;

    public ApplicationActorRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public ApplicationActor create(String identityHandle, PlatformRole role) {
        String canonicalHandle = Objects.requireNonNull(identityHandle, "identityHandle")
                .strip()
                .toLowerCase(Locale.ROOT);
        Objects.requireNonNull(role, "role");

        return jdbcClient.sql("""
                        INSERT INTO application_actor (identity_handle, platform_role)
                        VALUES (:identityHandle, :platformRole)
                        RETURNING actor_id, identity_handle, platform_role, created_at
                        """)
                .param("identityHandle", canonicalHandle)
                .param("platformRole", role.name())
                .query(ApplicationActorRepository::mapActor)
                .single();
    }

    public Optional<ApplicationActor> findById(UUID actorId) {
        Objects.requireNonNull(actorId, "actorId");
        return jdbcClient.sql("""
                        SELECT actor_id, identity_handle, platform_role, created_at
                        FROM application_actor
                        WHERE actor_id = :actorId
                        """)
                .param("actorId", actorId)
                .query(ApplicationActorRepository::mapActor)
                .optional();
    }

    public Optional<ApplicationActor> findByIdentityHandle(String identityHandle) {
        String canonicalHandle = Objects.requireNonNull(identityHandle, "identityHandle")
                .strip()
                .toLowerCase(Locale.ROOT);
        return jdbcClient.sql("""
                        SELECT actor_id, identity_handle, platform_role, created_at
                        FROM application_actor
                        WHERE identity_handle = :identityHandle
                        """)
                .param("identityHandle", canonicalHandle)
                .query(ApplicationActorRepository::mapActor)
                .optional();
    }

    private static ApplicationActor mapActor(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ApplicationActor(
                resultSet.getObject("actor_id", UUID.class),
                resultSet.getString("identity_handle"),
                PlatformRole.valueOf(resultSet.getString("platform_role")),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
