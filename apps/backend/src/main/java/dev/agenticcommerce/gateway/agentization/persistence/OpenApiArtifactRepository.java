package dev.agenticcommerce.gateway.agentization.persistence;

import dev.agenticcommerce.gateway.agentization.model.OpenApiArtifact;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class OpenApiArtifactRepository {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public OpenApiArtifactRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public OpenApiArtifact create(
            UUID merchantId,
            UUID endpointId,
            String artifactVersion,
            String contentHash,
            String canonicalDocument) {
        return jdbcClient.sql("""
                        INSERT INTO openapi_artifact (
                            merchant_id, endpoint_id, artifact_type, artifact_version,
                            content_hash, document)
                        VALUES (
                            :merchantId, :endpointId, 'OPENAPI', :artifactVersion,
                            :contentHash, CAST(:document AS jsonb))
                        RETURNING artifact_id, merchant_id, endpoint_id, artifact_type,
                                  artifact_version, content_hash, document::text, created_at
                        """)
                .param("merchantId", merchantId)
                .param("endpointId", endpointId)
                .param("artifactVersion", artifactVersion)
                .param("contentHash", contentHash)
                .param("document", canonicalDocument)
                .query(this::map)
                .single();
    }

    public Optional<OpenApiArtifact> findByMerchantAndId(UUID merchantId, UUID artifactId) {
        return jdbcClient.sql("""
                        SELECT artifact_id, merchant_id, endpoint_id, artifact_type,
                               artifact_version, content_hash, document::text, created_at
                        FROM openapi_artifact
                        WHERE merchant_id = :merchantId
                          AND artifact_id = :artifactId
                        """)
                .param("merchantId", merchantId)
                .param("artifactId", artifactId)
                .query(this::map)
                .optional();
    }

    public Optional<OpenApiArtifact> findByApprovedContent(
            UUID merchantId, UUID endpointId, String artifactVersion, String contentHash) {
        return jdbcClient.sql("""
                        SELECT artifact_id, merchant_id, endpoint_id, artifact_type,
                               artifact_version, content_hash, document::text, created_at
                        FROM openapi_artifact
                        WHERE merchant_id = :merchantId
                          AND endpoint_id = :endpointId
                          AND artifact_version = :artifactVersion
                          AND content_hash = :contentHash
                        """)
                .param("merchantId", merchantId)
                .param("endpointId", endpointId)
                .param("artifactVersion", artifactVersion)
                .param("contentHash", contentHash)
                .query(this::map)
                .optional();
    }

    private OpenApiArtifact map(ResultSet rs, int rowNumber) throws SQLException {
        return new OpenApiArtifact(
                rs.getObject("artifact_id", UUID.class),
                rs.getObject("merchant_id", UUID.class),
                rs.getObject("endpoint_id", UUID.class),
                rs.getString("artifact_type"),
                rs.getString("artifact_version"),
                rs.getString("content_hash").strip(),
                objectMapper.readTree(rs.getString("document")),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
