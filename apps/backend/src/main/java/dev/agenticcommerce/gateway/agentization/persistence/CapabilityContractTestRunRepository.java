package dev.agenticcommerce.gateway.agentization.persistence;

import dev.agenticcommerce.gateway.agentization.model.CapabilityContractTestRun;
import dev.agenticcommerce.gateway.agentization.model.CapabilityMappingProposal;
import dev.agenticcommerce.gateway.agentization.model.ContractTestOutcome;
import dev.agenticcommerce.gateway.agentization.model.GetQuoteTestCase;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class CapabilityContractTestRunRepository {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public CapabilityContractTestRunRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public CapabilityContractTestRun createCompleted(
            UUID merchantId,
            UUID runId,
            CapabilityMappingProposal mapping,
            GetQuoteTestCase testCase,
            Instant startedAt,
            ContractTestOutcome outcome,
            String failureCode,
            JsonNode evidence,
            String responseHash,
            String evidenceHash,
            String failureSignature) {
        return jdbcClient.sql("""
                        INSERT INTO capability_contract_test_run (
                            merchant_id, agentization_run_id, mapping_proposal_id,
                            capability, mapping_version, test_case_id, test_version,
                            attempt_number, started_at, completed_at, outcome, failure_code,
                            structured_evidence, response_hash, evidence_hash, failure_signature)
                        VALUES (
                            :merchantId, :runId, :mappingId, :capability, :mappingVersion,
                            :testCaseId, :testVersion,
                            (SELECT COALESCE(MAX(attempt_number), 0) + 1
                             FROM capability_contract_test_run
                             WHERE agentization_run_id = :runId
                               AND mapping_proposal_id = :mappingId
                               AND test_case_id = :testCaseId),
                            :startedAt, CURRENT_TIMESTAMP, :outcome, :failureCode,
                            CAST(:evidence AS jsonb), :responseHash, :evidenceHash,
                            :failureSignature)
                        RETURNING *
                        """)
                .param("merchantId", merchantId)
                .param("runId", runId)
                .param("mappingId", mapping.mappingProposalId())
                .param("capability", mapping.capability().name())
                .param("mappingVersion", mapping.mappingVersion())
                .param("testCaseId", testCase.testCaseId())
                .param("testVersion", testCase.testVersion())
                .param("startedAt", OffsetDateTime.ofInstant(startedAt, ZoneOffset.UTC))
                .param("outcome", outcome.name())
                .param("failureCode", failureCode)
                .param("evidence", objectMapper.writeValueAsString(evidence))
                .param("responseHash", responseHash)
                .param("evidenceHash", evidenceHash)
                .param("failureSignature", failureSignature)
                .query(this::map)
                .single();
    }

    public List<CapabilityContractTestRun> findAllByMerchantAndRun(UUID merchantId, UUID runId) {
        return jdbcClient.sql("""
                        SELECT * FROM capability_contract_test_run
                        WHERE merchant_id = :merchantId AND agentization_run_id = :runId
                        ORDER BY started_at, attempt_number
                        """)
                .param("merchantId", merchantId)
                .param("runId", runId)
                .query(this::map)
                .list();
    }

    public Optional<CapabilityContractTestRun> findByMerchantRunAndId(
            UUID merchantId, UUID runId, UUID contractTestRunId) {
        return jdbcClient.sql("""
                        SELECT * FROM capability_contract_test_run
                        WHERE merchant_id = :merchantId AND agentization_run_id = :runId
                          AND contract_test_run_id = :contractTestRunId
                        """)
                .param("merchantId", merchantId)
                .param("runId", runId)
                .param("contractTestRunId", contractTestRunId)
                .query(this::map)
                .optional();
    }

    public Optional<CapabilityContractTestRun> findLatestForMapping(
            UUID merchantId, UUID runId, UUID mappingProposalId) {
        return jdbcClient.sql("""
                        SELECT * FROM capability_contract_test_run
                        WHERE merchant_id = :merchantId AND agentization_run_id = :runId
                          AND mapping_proposal_id = :mappingProposalId
                        ORDER BY completed_at DESC, attempt_number DESC
                        LIMIT 1
                        """)
                .param("merchantId", merchantId)
                .param("runId", runId)
                .param("mappingProposalId", mappingProposalId)
                .query(this::map)
                .optional();
    }

    public int countFailureSignature(UUID runId, String signature) {
        if (signature == null) {
            return 0;
        }
        return jdbcClient.sql("""
                        SELECT COUNT(*)::integer FROM capability_contract_test_run
                        WHERE agentization_run_id = :runId AND failure_signature = :signature
                        """)
                .param("runId", runId)
                .param("signature", signature)
                .query(Integer.class)
                .single();
    }

    private CapabilityContractTestRun map(ResultSet rs, int rowNumber) throws SQLException {
        return new CapabilityContractTestRun(
                rs.getObject("contract_test_run_id", UUID.class),
                rs.getObject("merchant_id", UUID.class),
                rs.getObject("agentization_run_id", UUID.class),
                rs.getObject("mapping_proposal_id", UUID.class),
                dev.agenticcommerce.gateway.agentization.model.CanonicalCapability.valueOf(
                        rs.getString("capability")),
                rs.getInt("mapping_version"),
                rs.getString("test_case_id"),
                rs.getInt("test_version"),
                rs.getInt("attempt_number"),
                rs.getObject("started_at", OffsetDateTime.class).toInstant(),
                rs.getObject("completed_at", OffsetDateTime.class).toInstant(),
                ContractTestOutcome.valueOf(rs.getString("outcome")),
                rs.getString("failure_code"),
                objectMapper.readTree(rs.getString("structured_evidence")),
                rs.getString("response_hash"),
                rs.getString("evidence_hash"),
                rs.getString("failure_signature"));
    }
}
