package dev.agenticcommerce.gateway.agentization.model;

import java.time.Instant;
import java.util.UUID;

public record AgentizationRun(
        UUID runId,
        UUID merchantId,
        UUID createdByActorId,
        UUID sourceArtifactId,
        CanonicalCapability targetCapability,
        CanonicalCapability currentCapability,
        AgentizationState state,
        int stepCount,
        int maxStepBudget,
        Instant wallClockDeadline,
        UUID lastObservationId,
        String terminalReason,
        Integer currentMappingVersion,
        String lastFailureSignature,
        int repeatedFailureCount,
        int version,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {
}
