package dev.agenticcommerce.gateway.identity.persistence;

import dev.agenticcommerce.gateway.identity.model.Merchant;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** PostgreSQL persistence boundary for merchant tenant identities. */
@Repository
public class MerchantRepository {

    private final JdbcClient jdbcClient;

    public MerchantRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Merchant create(String merchantKey, String displayName) {
        String canonicalKey = Objects.requireNonNull(merchantKey, "merchantKey")
                .strip()
                .toLowerCase(Locale.ROOT);
        String canonicalDisplayName = Objects.requireNonNull(displayName, "displayName").strip();

        return jdbcClient.sql("""
                        INSERT INTO merchant (merchant_key, display_name)
                        VALUES (:merchantKey, :displayName)
                        RETURNING merchant_id, merchant_key, display_name, created_at
                        """)
                .param("merchantKey", canonicalKey)
                .param("displayName", canonicalDisplayName)
                .query(MerchantRepository::mapMerchant)
                .single();
    }

    public Optional<Merchant> findById(UUID merchantId) {
        Objects.requireNonNull(merchantId, "merchantId");
        return jdbcClient.sql("""
                        SELECT merchant_id, merchant_key, display_name, created_at
                        FROM merchant
                        WHERE merchant_id = :merchantId
                        """)
                .param("merchantId", merchantId)
                .query(MerchantRepository::mapMerchant)
                .optional();
    }

    public Optional<Merchant> findByKey(String merchantKey) {
        String canonicalKey = Objects.requireNonNull(merchantKey, "merchantKey")
                .strip().toLowerCase(Locale.ROOT);
        return jdbcClient.sql("""
                        SELECT merchant_id, merchant_key, display_name, created_at
                        FROM merchant WHERE merchant_key = :merchantKey
                        """)
                .param("merchantKey", canonicalKey)
                .query(MerchantRepository::mapMerchant)
                .optional();
    }

    /** Returns only merchant tenants explicitly administered by the authenticated actor. */
    public List<Merchant> findAllAdministeredByActor(UUID actorId) {
        Objects.requireNonNull(actorId, "actorId");
        return jdbcClient.sql("""
                        SELECT m.merchant_id, m.merchant_key, m.display_name, m.created_at
                        FROM merchant m
                        INNER JOIN merchant_admin_membership membership
                            ON membership.merchant_id = m.merchant_id
                        WHERE membership.actor_id = :actorId
                        ORDER BY lower(m.display_name), m.merchant_id
                        """)
                .param("actorId", actorId)
                .query(MerchantRepository::mapMerchant)
                .list();
    }

    private static Merchant mapMerchant(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Merchant(
                resultSet.getObject("merchant_id", UUID.class),
                resultSet.getString("merchant_key"),
                resultSet.getString("display_name"),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
