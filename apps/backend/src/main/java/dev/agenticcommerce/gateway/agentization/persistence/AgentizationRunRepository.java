package dev.agenticcommerce.gateway.agentization.persistence;

import dev.agenticcommerce.gateway.agentization.model.AgentizationRun;
import dev.agenticcommerce.gateway.agentization.model.AgentizationState;
import dev.agenticcommerce.gateway.agentization.model.CanonicalCapability;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AgentizationRunRepository {

    private final JdbcClient jdbcClient;

    public AgentizationRunRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public AgentizationRun create(
            UUID merchantId,
            UUID actorId,
            UUID artifactId,
            CanonicalCapability capability,
            int maxSteps,
            Instant deadline) {
        return jdbcClient.sql("""
                        INSERT INTO agentization_run (
                            merchant_id, created_by_actor_id, source_artifact_id,
                            target_capability, current_capability, orchestration_state,
                            max_step_budget, wall_clock_deadline)
                        VALUES (
                            :merchantId, :actorId, :artifactId,
                            :capability, :capability, 'AGENTIZATION_CREATED',
                            :maxSteps, :deadline)
                        RETURNING *
                        """)
                .param("merchantId", merchantId)
                .param("actorId", actorId)
                .param("artifactId", artifactId)
                .param("capability", capability.name())
                .param("maxSteps", maxSteps)
                .param("deadline", OffsetDateTime.ofInstant(deadline, ZoneOffset.UTC))
                .query(AgentizationRunRepository::map)
                .single();
    }

    public Optional<AgentizationRun> findByMerchantAndId(UUID merchantId, UUID runId) {
        return queryByMerchantAndId(merchantId, runId, false);
    }

    public Optional<AgentizationRun> findByMerchantAndIdForUpdate(UUID merchantId, UUID runId) {
        return queryByMerchantAndId(merchantId, runId, true);
    }

    private Optional<AgentizationRun> queryByMerchantAndId(
            UUID merchantId, UUID runId, boolean forUpdate) {
        String lock = forUpdate ? " FOR UPDATE" : "";
        return jdbcClient.sql("""
                        SELECT *
                        FROM agentization_run
                        WHERE merchant_id = :merchantId
                          AND run_id = :runId
                        """ + lock)
                .param("merchantId", merchantId)
                .param("runId", runId)
                .query(AgentizationRunRepository::map)
                .optional();
    }

    public AgentizationRun transition(
            AgentizationRun current,
            AgentizationState next,
            String terminalReason) {
        int updated = jdbcClient.sql("""
                        UPDATE agentization_run
                        SET orchestration_state = :nextState,
                            terminal_reason = :terminalReason,
                            completed_at = CASE WHEN :terminal THEN CURRENT_TIMESTAMP ELSE NULL END,
                            updated_at = CURRENT_TIMESTAMP,
                            version = version + 1
                        WHERE run_id = :runId
                          AND merchant_id = :merchantId
                          AND orchestration_state = :expectedState
                          AND version = :version
                        """)
                .param("nextState", next.name())
                .param("terminalReason", terminalReason)
                .param("terminal", next.terminal())
                .param("runId", current.runId())
                .param("merchantId", current.merchantId())
                .param("expectedState", current.state().name())
                .param("version", current.version())
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Agentization run changed concurrently");
        }
        return findByMerchantAndIdForUpdate(current.merchantId(), current.runId()).orElseThrow();
    }

    public AgentizationRun incrementStep(AgentizationRun current) {
        int updated = jdbcClient.sql("""
                        UPDATE agentization_run
                        SET step_count = step_count + 1,
                            updated_at = CURRENT_TIMESTAMP,
                            version = version + 1
                        WHERE run_id = :runId
                          AND merchant_id = :merchantId
                          AND version = :version
                          AND step_count < max_step_budget
                        """)
                .param("runId", current.runId())
                .param("merchantId", current.merchantId())
                .param("version", current.version())
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Agentization step budget or version changed");
        }
        return findByMerchantAndIdForUpdate(current.merchantId(), current.runId()).orElseThrow();
    }

    public AgentizationRun attachObservation(AgentizationRun current, UUID observationId) {
        int updated = jdbcClient.sql("""
                        UPDATE agentization_run
                        SET last_observation_id = :observationId,
                            updated_at = CURRENT_TIMESTAMP,
                            version = version + 1
                        WHERE run_id = :runId
                          AND merchant_id = :merchantId
                          AND version = :version
                        """)
                .param("observationId", observationId)
                .param("runId", current.runId())
                .param("merchantId", current.merchantId())
                .param("version", current.version())
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Agentization run changed while linking observation");
        }
        return findByMerchantAndIdForUpdate(current.merchantId(), current.runId()).orElseThrow();
    }

    public AgentizationRun setCurrentMappingVersion(AgentizationRun current, int mappingVersion) {
        int updated = jdbcClient.sql("""
                        UPDATE agentization_run
                        SET current_mapping_version = :mappingVersion,
                            updated_at = CURRENT_TIMESTAMP,
                            version = version + 1
                        WHERE run_id = :runId AND merchant_id = :merchantId AND version = :version
                        """)
                .param("mappingVersion", mappingVersion)
                .param("runId", current.runId())
                .param("merchantId", current.merchantId())
                .param("version", current.version())
                .update();
        if (updated != 1) throw new IllegalStateException("Agentization run changed while selecting mapping");
        return findByMerchantAndIdForUpdate(current.merchantId(), current.runId()).orElseThrow();
    }

    public AgentizationRun recordFailure(AgentizationRun current, String signature, int repeatedCount) {
        int updated = jdbcClient.sql("""
                        UPDATE agentization_run
                        SET last_failure_signature = :signature,
                            repeated_failure_count = :repeatedCount,
                            updated_at = CURRENT_TIMESTAMP,
                            version = version + 1
                        WHERE run_id = :runId AND merchant_id = :merchantId AND version = :version
                        """)
                .param("signature", signature)
                .param("repeatedCount", repeatedCount)
                .param("runId", current.runId())
                .param("merchantId", current.merchantId())
                .param("version", current.version())
                .update();
        if (updated != 1) throw new IllegalStateException("Agentization run changed while recording failure");
        return findByMerchantAndIdForUpdate(current.merchantId(), current.runId()).orElseThrow();
    }

    public AgentizationRun clearFailure(AgentizationRun current) {
        int updated = jdbcClient.sql("""
                        UPDATE agentization_run
                        SET last_failure_signature = NULL, repeated_failure_count = 0,
                            updated_at = CURRENT_TIMESTAMP, version = version + 1
                        WHERE run_id = :runId AND merchant_id = :merchantId AND version = :version
                        """)
                .param("runId", current.runId())
                .param("merchantId", current.merchantId())
                .param("version", current.version())
                .update();
        if (updated != 1) throw new IllegalStateException("Agentization run changed while clearing failure");
        return findByMerchantAndIdForUpdate(current.merchantId(), current.runId()).orElseThrow();
    }

    private static AgentizationRun map(ResultSet rs, int rowNumber) throws SQLException {
        OffsetDateTime completed = rs.getObject("completed_at", OffsetDateTime.class);
        return new AgentizationRun(
                rs.getObject("run_id", UUID.class),
                rs.getObject("merchant_id", UUID.class),
                rs.getObject("created_by_actor_id", UUID.class),
                rs.getObject("source_artifact_id", UUID.class),
                CanonicalCapability.valueOf(rs.getString("target_capability")),
                CanonicalCapability.valueOf(rs.getString("current_capability")),
                AgentizationState.valueOf(rs.getString("orchestration_state")),
                rs.getInt("step_count"),
                rs.getInt("max_step_budget"),
                rs.getObject("wall_clock_deadline", OffsetDateTime.class).toInstant(),
                rs.getObject("last_observation_id", UUID.class),
                rs.getString("terminal_reason"),
                (Integer) rs.getObject("current_mapping_version"),
                rs.getString("last_failure_signature"),
                rs.getInt("repeated_failure_count"),
                rs.getInt("version"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                completed == null ? null : completed.toInstant());
    }
}
