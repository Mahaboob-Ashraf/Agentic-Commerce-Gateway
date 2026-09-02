package dev.agenticcommerce.gateway.agentization.persistence;

import dev.agenticcommerce.gateway.agentization.model.ApprovedMerchantEndpoint;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** Persistence seam for an endpoint explicitly approved before OpenAPI artifact registration. */
@Repository
public class ApprovedMerchantEndpointRepository {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public ApprovedMerchantEndpointRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    /** Compatibility seam for already-approved Task 004 fixtures; execution still revalidates DNS. */
    public ApprovedMerchantEndpoint create(UUID merchantId, String baseUri) {
        URI uri = URI.create(baseUri);
        return jdbcClient.sql("""
                        INSERT INTO merchant_approved_endpoint (
                            merchant_id, base_uri, hostname, port, approval_status,
                            approved_at, dns_validated_at)
                        VALUES (:merchantId, :baseUri, :hostname, :port, 'APPROVED',
                                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        RETURNING *
                        """)
                .param("merchantId", merchantId)
                .param("baseUri", baseUri)
                .param("hostname", uri.getHost())
                .param("port", uri.getPort() < 0 ? 443 : uri.getPort())
                .query(this::map)
                .single();
    }

    public ApprovedMerchantEndpoint createApproved(
            UUID merchantId,
            UUID actorId,
            String baseUri,
            String hostname,
            int port,
            Set<String> methods,
            List<String> pathTemplates,
            List<String> resolvedAddresses,
            String credentialReference) {
        return jdbcClient.sql("""
                        INSERT INTO merchant_approved_endpoint (
                            merchant_id, base_uri, hostname, port, approved_methods,
                            approved_path_templates, approved_resolved_addresses,
                            approval_status, approved_by_actor_id, approved_at,
                            dns_validated_at, credential_reference)
                        VALUES (
                            :merchantId, :baseUri, :hostname, :port,
                            CAST(:methods AS jsonb), CAST(:paths AS jsonb),
                            CAST(:addresses AS jsonb), 'APPROVED', :actorId,
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, :credentialReference)
                        RETURNING *
                        """)
                .param("merchantId", merchantId)
                .param("actorId", actorId)
                .param("baseUri", baseUri)
                .param("hostname", hostname)
                .param("port", port)
                .param("methods", objectMapper.writeValueAsString(methods))
                .param("paths", objectMapper.writeValueAsString(pathTemplates))
                .param("addresses", objectMapper.writeValueAsString(resolvedAddresses))
                .param("credentialReference", credentialReference)
                .query(this::map)
                .single();
    }

    public Optional<ApprovedMerchantEndpoint> findByMerchantAndId(UUID merchantId, UUID endpointId) {
        return jdbcClient.sql("""
                        SELECT *
                        FROM merchant_approved_endpoint
                        WHERE merchant_id = :merchantId
                          AND endpoint_id = :endpointId
                          AND approval_status = 'APPROVED'
                        """)
                .param("merchantId", merchantId)
                .param("endpointId", endpointId)
                .query(this::map)
                .optional();
    }

    public Optional<ApprovedMerchantEndpoint> findAnyByMerchantAndId(UUID merchantId, UUID endpointId) {
        return jdbcClient.sql("""
                        SELECT * FROM merchant_approved_endpoint
                        WHERE merchant_id = :merchantId AND endpoint_id = :endpointId
                        """)
                .param("merchantId", merchantId)
                .param("endpointId", endpointId)
                .query(this::map)
                .optional();
    }

    public Optional<ApprovedMerchantEndpoint> findApprovedByIdentity(
            UUID merchantId, String baseUri, String credentialReference) {
        return jdbcClient.sql("""
                        SELECT * FROM merchant_approved_endpoint
                        WHERE merchant_id = :merchantId
                          AND base_uri = :baseUri
                          AND approval_status = 'APPROVED'
                          AND credential_reference IS NOT DISTINCT FROM :credentialReference
                        ORDER BY created_at DESC
                        LIMIT 1
                        """)
                .param("merchantId", merchantId)
                .param("baseUri", baseUri)
                .param("credentialReference", credentialReference)
                .query(this::map)
                .optional();
    }

    private ApprovedMerchantEndpoint map(ResultSet rs, int rowNumber) throws SQLException {
        OffsetDateTime approvedAt = rs.getObject("approved_at", OffsetDateTime.class);
        OffsetDateTime dnsValidatedAt = rs.getObject("dns_validated_at", OffsetDateTime.class);
        return new ApprovedMerchantEndpoint(
                rs.getObject("endpoint_id", UUID.class),
                rs.getObject("merchant_id", UUID.class),
                rs.getString("base_uri"),
                rs.getString("hostname"),
                rs.getInt("port"),
                new LinkedHashSet<>(readStringList(rs.getString("approved_methods"))),
                readStringList(rs.getString("approved_path_templates")),
                readStringList(rs.getString("approved_resolved_addresses")),
                rs.getString("approval_status"),
                rs.getObject("approved_by_actor_id", UUID.class),
                approvedAt == null ? null : approvedAt.toInstant(),
                dnsValidatedAt == null ? null : dnsValidatedAt.toInstant(),
                rs.getString("credential_reference"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private List<String> readStringList(String json) {
        try {
            return Arrays.asList(objectMapper.readValue(json, String[].class));
        } catch (RuntimeException exception) {
            throw exception;
        }
    }
}
