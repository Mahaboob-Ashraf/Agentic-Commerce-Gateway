package dev.agenticcommerce.gateway.agentization.persistence;

import dev.agenticcommerce.gateway.agentization.model.AgentObservation;
import dev.agenticcommerce.gateway.agentization.model.AgentizationRun;
import dev.agenticcommerce.gateway.agentization.model.AgentToolName;
import dev.agenticcommerce.gateway.agentization.model.ToolOutcome;
import dev.agenticcommerce.gateway.agentization.tool.ToolExecutionResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class AgentObservationRepository {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public AgentObservationRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public AgentObservation create(
            AgentizationRun run,
            AgentToolName toolName,
            String inputHash,
            JsonNode structuredResult,
            ToolOutcome outcome,
            String reasonCode,
            String conciseRationale,
            ToolExecutionResult execution) {
        Integer mappingBefore = execution == null ? null : execution.mappingVersionBefore();
        Integer mappingAfter = execution == null ? null : execution.mappingVersionAfter();
        var testRun = execution == null ? null : execution.contractTestRun();
        var evidenceReferences = objectMapper.createArrayNode();
        if (testRun != null) evidenceReferences.add(testRun.evidenceHash());
        return jdbcClient.sql("""
                        INSERT INTO agent_observation (
                            run_id, merchant_id, capability, step_number, orchestration_state,
                            tool_name, input_hash, structured_result, outcome, reason_code,
                            concise_rationale, mapping_version_before, mapping_version_after,
                            contract_test_run_id, contract_test_outcome,
                            contract_test_failure_code, evidence_references)
                        VALUES (
                            :runId, :merchantId, :capability, :stepNumber, :state,
                            :toolName, :inputHash, CAST(:structuredResult AS jsonb), :outcome,
                            :reasonCode, :rationale, :mappingBefore, :mappingAfter,
                            :testRunId, :testOutcome, :testFailureCode,
                            CAST(:evidenceReferences AS jsonb))
                        RETURNING *
                        """)
                .param("runId", run.runId())
                .param("merchantId", run.merchantId())
                .param("capability", run.currentCapability().name())
                .param("stepNumber", run.stepCount())
                .param("state", run.state().name())
                .param("toolName", toolName.name())
                .param("inputHash", inputHash)
                .param("structuredResult", objectMapper.writeValueAsString(structuredResult))
                .param("outcome", outcome.name())
                .param("reasonCode", reasonCode)
                .param("rationale", conciseRationale)
                .param("mappingBefore", mappingBefore)
                .param("mappingAfter", mappingAfter)
                .param("testRunId", testRun == null ? null : testRun.contractTestRunId())
                .param("testOutcome", testRun == null ? null : testRun.outcome().name())
                .param("testFailureCode", testRun == null ? null : testRun.failureCode())
                .param("evidenceReferences", objectMapper.writeValueAsString(evidenceReferences))
                .query(this::map)
                .single();
    }

    public List<AgentObservation> findAllByMerchantAndRun(UUID merchantId, UUID runId) {
        return jdbcClient.sql("""
                        SELECT *
                        FROM agent_observation
                        WHERE merchant_id = :merchantId
                          AND run_id = :runId
                        ORDER BY step_number
                        """)
                .param("merchantId", merchantId)
                .param("runId", runId)
                .query(this::map)
                .list();
    }

    public List<AgentObservation> findRecentByMerchantAndRun(
            UUID merchantId, UUID runId, int maximumResults) {
        int boundedMaximum = Math.max(1, Math.min(maximumResults, 20));
        return jdbcClient.sql("""
                        SELECT *
                        FROM agent_observation
                        WHERE merchant_id = :merchantId
                          AND run_id = :runId
                        ORDER BY step_number DESC
                        LIMIT :maximumResults
                        """)
                .param("merchantId", merchantId)
                .param("runId", runId)
                .param("maximumResults", boundedMaximum)
                .query(this::map)
                .list();
    }

    private AgentObservation map(ResultSet rs, int rowNumber) throws SQLException {
        return new AgentObservation(
                rs.getObject("observation_id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getObject("merchant_id", UUID.class),
                dev.agenticcommerce.gateway.agentization.model.CanonicalCapability.valueOf(
                        rs.getString("capability")),
                rs.getInt("step_number"),
                dev.agenticcommerce.gateway.agentization.model.AgentizationState.valueOf(
                        rs.getString("orchestration_state")),
                AgentToolName.valueOf(rs.getString("tool_name")),
                rs.getString("input_hash").strip(),
                objectMapper.readTree(rs.getString("structured_result")),
                ToolOutcome.valueOf(rs.getString("outcome")),
                rs.getString("reason_code"),
                rs.getString("concise_rationale"),
                (Integer) rs.getObject("mapping_version_before"),
                (Integer) rs.getObject("mapping_version_after"),
                rs.getObject("contract_test_run_id", UUID.class),
                rs.getString("contract_test_outcome") == null ? null
                        : dev.agenticcommerce.gateway.agentization.model.ContractTestOutcome.valueOf(
                                rs.getString("contract_test_outcome")),
                rs.getString("contract_test_failure_code"),
                objectMapper.readTree(rs.getString("evidence_references")),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
