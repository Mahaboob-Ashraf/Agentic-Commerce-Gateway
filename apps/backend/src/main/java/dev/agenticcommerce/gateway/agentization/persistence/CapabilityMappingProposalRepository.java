package dev.agenticcommerce.gateway.agentization.persistence;

import dev.agenticcommerce.gateway.agentization.model.CapabilityMappingProposal;
import dev.agenticcommerce.gateway.agentization.model.CanonicalCapability;
import dev.agenticcommerce.gateway.agentization.model.MappingProposalStatus;
import dev.agenticcommerce.gateway.agentization.tool.MappingProposalInput;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class CapabilityMappingProposalRepository {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public CapabilityMappingProposalRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public CapabilityMappingProposal create(
            UUID merchantId,
            UUID runId,
            CanonicalCapability capability,
            MappingProposalInput input) {
        return jdbcClient.sql("""
                        INSERT INTO capability_mapping_proposal (
                            merchant_id, run_id, capability, mapping_version,
                            source_artifact_id, endpoint_id, source_operation_id, http_method, path_template,
                            request_bindings, response_bindings, transformations,
                            amount_interpretation, currency_interpretation,
                            model_provider, model_name, proposal_status)
                        VALUES (
                            :merchantId, :runId, :capability, :mappingVersion,
                            :artifactId,
                            (SELECT endpoint_id FROM openapi_artifact
                             WHERE artifact_id = :artifactId AND merchant_id = :merchantId),
                            :operationId, :httpMethod, :pathTemplate,
                            CAST(:requestBindings AS jsonb), CAST(:responseBindings AS jsonb),
                            CAST(:transformations AS jsonb), CAST(:amountInterpretation AS jsonb),
                            CAST(:currencyInterpretation AS jsonb),
                            :modelProvider, :modelName, 'PROPOSED')
                        RETURNING *
                        """)
                .param("merchantId", merchantId)
                .param("runId", runId)
                .param("capability", capability.name())
                .param("mappingVersion", input.mappingVersion())
                .param("artifactId", input.artifactId())
                .param("operationId", input.operationId())
                .param("httpMethod", input.httpMethod().toUpperCase())
                .param("pathTemplate", input.pathTemplate())
                .param("requestBindings", objectMapper.writeValueAsString(input.requestBindings()))
                .param("responseBindings", objectMapper.writeValueAsString(input.responseBindings()))
                .param("transformations", objectMapper.writeValueAsString(input.transformations()))
                .param("amountInterpretation", objectMapper.writeValueAsString(input.amountInterpretation()))
                .param("currencyInterpretation", objectMapper.writeValueAsString(input.currencyInterpretation()))
                .param("modelProvider", input.modelProvider())
                .param("modelName", input.modelName())
                .query(this::map)
                .single();
    }

    public CapabilityMappingProposal createRevision(
            CapabilityMappingProposal previous,
            tools.jackson.databind.JsonNode transformations,
            String revisionReason,
            UUID evidenceTestRunId,
            String modelProvider,
            String modelName) {
        return jdbcClient.sql("""
                        INSERT INTO capability_mapping_proposal (
                            merchant_id, run_id, capability, mapping_version,
                            source_artifact_id, endpoint_id, source_operation_id,
                            http_method, path_template, request_bindings, response_bindings,
                            transformations, amount_interpretation, currency_interpretation,
                            status_normalization, idempotency_semantics, retry_semantics,
                            model_provider, model_name, proposal_status, validation_status,
                            connect_timeout_ms, request_timeout_ms, maximum_request_bytes,
                            maximum_response_bytes, previous_mapping_proposal_id,
                            revision_reason, revision_evidence_test_run_id)
                        VALUES (
                            :merchantId, :runId, :capability, :mappingVersion,
                            :artifactId, :endpointId, :operationId, :httpMethod, :pathTemplate,
                            CAST(:requestBindings AS jsonb), CAST(:responseBindings AS jsonb),
                            CAST(:transformations AS jsonb), CAST(:amountInterpretation AS jsonb),
                            CAST(:currencyInterpretation AS jsonb), CAST(:statusNormalization AS jsonb),
                            CAST(:idempotencySemantics AS jsonb), CAST(:retrySemantics AS jsonb),
                            :modelProvider, :modelName,
                            'PROPOSED', 'PENDING', :connectTimeout, :requestTimeout,
                            :maximumRequest, :maximumResponse, :previousId, :revisionReason,
                            :evidenceTestRunId)
                        RETURNING *
                        """)
                .param("merchantId", previous.merchantId())
                .param("runId", previous.runId())
                .param("capability", previous.capability().name())
                .param("mappingVersion", previous.mappingVersion() + 1)
                .param("artifactId", previous.sourceArtifactId())
                .param("endpointId", previous.endpointId())
                .param("operationId", previous.sourceOperationId())
                .param("httpMethod", previous.httpMethod())
                .param("pathTemplate", previous.pathTemplate())
                .param("requestBindings", objectMapper.writeValueAsString(previous.requestBindings()))
                .param("responseBindings", objectMapper.writeValueAsString(previous.responseBindings()))
                .param("transformations", objectMapper.writeValueAsString(transformations))
                .param("amountInterpretation", objectMapper.writeValueAsString(previous.amountInterpretation()))
                .param("currencyInterpretation", objectMapper.writeValueAsString(previous.currencyInterpretation()))
                .param("statusNormalization", objectMapper.writeValueAsString(previous.statusNormalization()))
                .param("idempotencySemantics", objectMapper.writeValueAsString(previous.idempotencySemantics()))
                .param("retrySemantics", objectMapper.writeValueAsString(previous.retrySemantics()))
                .param("modelProvider", modelProvider)
                .param("modelName", modelName)
                .param("connectTimeout", previous.connectTimeoutMs())
                .param("requestTimeout", previous.requestTimeoutMs())
                .param("maximumRequest", previous.maximumRequestBytes())
                .param("maximumResponse", previous.maximumResponseBytes())
                .param("previousId", previous.mappingProposalId())
                .param("revisionReason", revisionReason)
                .param("evidenceTestRunId", evidenceTestRunId)
                .query(this::map)
                .single();
    }

    public List<CapabilityMappingProposal> findAllByMerchantAndRun(UUID merchantId, UUID runId) {
        return jdbcClient.sql("""
                        SELECT *
                        FROM capability_mapping_proposal
                        WHERE merchant_id = :merchantId
                          AND run_id = :runId
                        ORDER BY mapping_version, created_at
                        """)
                .param("merchantId", merchantId)
                .param("runId", runId)
                .query(this::map)
                .list();
    }

    public java.util.Optional<CapabilityMappingProposal> findByMerchantRunAndVersion(
            UUID merchantId, UUID runId, int mappingVersion) {
        return jdbcClient.sql("""
                        SELECT * FROM capability_mapping_proposal
                        WHERE merchant_id = :merchantId AND run_id = :runId
                          AND mapping_version = :mappingVersion
                        """)
                .param("merchantId", merchantId)
                .param("runId", runId)
                .param("mappingVersion", mappingVersion)
                .query(this::map)
                .optional();
    }

    public java.util.Optional<CapabilityMappingProposal> findByMerchantRunAndId(
            UUID merchantId, UUID runId, UUID mappingProposalId) {
        return jdbcClient.sql("""
                        SELECT * FROM capability_mapping_proposal
                        WHERE merchant_id = :merchantId AND run_id = :runId
                          AND mapping_proposal_id = :mappingProposalId
                        """)
                .param("merchantId", merchantId)
                .param("runId", runId)
                .param("mappingProposalId", mappingProposalId)
                .query(this::map)
                .optional();
    }

    public java.util.Optional<CapabilityMappingProposal> findByMerchantAndId(
            UUID merchantId, UUID mappingProposalId) {
        return jdbcClient.sql("""
                        SELECT * FROM capability_mapping_proposal
                        WHERE merchant_id = :merchantId
                          AND mapping_proposal_id = :mappingProposalId
                        """)
                .param("merchantId", merchantId)
                .param("mappingProposalId", mappingProposalId)
                .query(this::map)
                .optional();
    }

    public CapabilityMappingProposal markValidation(
            UUID merchantId, UUID runId, UUID mappingProposalId, boolean valid) {
        return jdbcClient.sql("""
                        UPDATE capability_mapping_proposal
                        SET validation_status = :status
                        WHERE merchant_id = :merchantId AND run_id = :runId
                          AND mapping_proposal_id = :mappingProposalId
                          AND validation_status = 'PENDING'
                        RETURNING *
                        """)
                .param("status", valid ? "VALID" : "INVALID")
                .param("merchantId", merchantId)
                .param("runId", runId)
                .param("mappingProposalId", mappingProposalId)
                .query(this::map)
                .single();
    }

    public CapabilityMappingProposal markAwaitingApproval(UUID merchantId, UUID runId, UUID mappingProposalId) {
        return jdbcClient.sql("""
                        UPDATE capability_mapping_proposal SET proposal_status = 'AWAITING_APPROVAL'
                        WHERE merchant_id = :merchantId AND run_id = :runId
                          AND mapping_proposal_id = :mappingProposalId
                          AND validation_status = 'VALID'
                          AND proposal_status = 'PROPOSED'
                        RETURNING *
                        """)
                .param("merchantId", merchantId).param("runId", runId)
                .param("mappingProposalId", mappingProposalId)
                .query(this::map).optional()
                .orElseThrow(() -> new IllegalStateException("Mapping is not eligible for merchant approval"));
    }

    private CapabilityMappingProposal map(ResultSet rs, int rowNumber) throws SQLException {
        return new CapabilityMappingProposal(
                rs.getObject("mapping_proposal_id", UUID.class),
                rs.getObject("merchant_id", UUID.class),
                rs.getObject("run_id", UUID.class),
                CanonicalCapability.valueOf(rs.getString("capability")),
                rs.getInt("mapping_version"),
                rs.getObject("source_artifact_id", UUID.class),
                rs.getObject("endpoint_id", UUID.class),
                rs.getString("source_operation_id"),
                rs.getString("http_method"),
                rs.getString("path_template"),
                objectMapper.readTree(rs.getString("request_bindings")),
                objectMapper.readTree(rs.getString("response_bindings")),
                objectMapper.readTree(rs.getString("transformations")),
                objectMapper.readTree(rs.getString("amount_interpretation")),
                objectMapper.readTree(rs.getString("currency_interpretation")),
                objectMapper.readTree(rs.getString("status_normalization")),
                objectMapper.readTree(rs.getString("idempotency_semantics")),
                objectMapper.readTree(rs.getString("retry_semantics")),
                rs.getString("model_provider"),
                rs.getString("model_name"),
                MappingProposalStatus.valueOf(rs.getString("proposal_status")),
                rs.getString("validation_status"),
                rs.getInt("connect_timeout_ms"),
                rs.getInt("request_timeout_ms"),
                rs.getInt("maximum_request_bytes"),
                rs.getInt("maximum_response_bytes"),
                rs.getObject("previous_mapping_proposal_id", UUID.class),
                rs.getString("revision_reason"),
                rs.getObject("revision_evidence_test_run_id", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
