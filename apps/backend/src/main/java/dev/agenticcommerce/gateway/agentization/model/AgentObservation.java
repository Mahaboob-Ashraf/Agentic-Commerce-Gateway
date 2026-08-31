package dev.agenticcommerce.gateway.agentization.model;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record AgentObservation(
        UUID observationId,
        UUID runId,
        UUID merchantId,
        CanonicalCapability capability,
        int stepNumber,
        AgentizationState orchestrationState,
        AgentToolName toolName,
        String inputHash,
        JsonNode structuredResult,
        ToolOutcome outcome,
        String reasonCode,
        String conciseRationale,
        Integer mappingVersionBefore,
        Integer mappingVersionAfter,
        UUID contractTestRunId,
        ContractTestOutcome contractTestOutcome,
        String contractTestFailureCode,
        JsonNode evidenceReferences,
        Instant createdAt) {
}
