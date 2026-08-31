package dev.agenticcommerce.gateway.agentization.model;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record CapabilityContractTestRun(
        UUID contractTestRunId,
        UUID merchantId,
        UUID agentizationRunId,
        UUID mappingProposalId,
        CanonicalCapability capability,
        int mappingVersion,
        String testCaseId,
        int testVersion,
        int attemptNumber,
        Instant startedAt,
        Instant completedAt,
        ContractTestOutcome outcome,
        String failureCode,
        JsonNode structuredEvidence,
        String responseHash,
        String evidenceHash,
        String failureSignature) {
}
