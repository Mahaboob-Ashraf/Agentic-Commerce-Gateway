package dev.agenticcommerce.gateway.identity.persistence;

import dev.agenticcommerce.gateway.identity.model.MerchantAdminMembership;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Tenant-scoped persistence boundary for merchant administration relationships.
 * Every lookup requires both merchant and actor identity; no actor-only resolver is exposed.
 */
@Repository
public class MerchantAdminMembershipRepository {

    private final JdbcClient jdbcClient;

    public MerchantAdminMembershipRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public MerchantAdminMembership create(UUID merchantId, UUID actorId) {
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(actorId, "actorId");
        return jdbcClient.sql("""
                        INSERT INTO merchant_admin_membership (merchant_id, actor_id)
                        VALUES (:merchantId, :actorId)
                        RETURNING merchant_id, actor_id, created_at
                        """)
                .param("merchantId", merchantId)
                .param("actorId", actorId)
                .query(MerchantAdminMembershipRepository::mapMembership)
                .single();
    }

    public Optional<MerchantAdminMembership> findByMerchantAndActor(UUID merchantId, UUID actorId) {
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(actorId, "actorId");
        return jdbcClient.sql("""
                        SELECT merchant_id, actor_id, created_at
                        FROM merchant_admin_membership
                        WHERE merchant_id = :merchantId
                          AND actor_id = :actorId
                        """)
                .param("merchantId", merchantId)
                .param("actorId", actorId)
                .query(MerchantAdminMembershipRepository::mapMembership)
                .optional();
    }

    public boolean existsByMerchantAndActor(UUID merchantId, UUID actorId) {
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(actorId, "actorId");
        return jdbcClient.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM merchant_admin_membership
                            WHERE merchant_id = :merchantId
                              AND actor_id = :actorId
                        )
                        """)
                .param("merchantId", merchantId)
                .param("actorId", actorId)
                .query(Boolean.class)
                .single();
    }

    private static MerchantAdminMembership mapMembership(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new MerchantAdminMembership(
                resultSet.getObject("merchant_id", UUID.class),
                resultSet.getObject("actor_id", UUID.class),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
