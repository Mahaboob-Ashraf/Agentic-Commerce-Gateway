package dev.agenticcommerce.gateway.agentization.tool;

import dev.agenticcommerce.gateway.agentization.model.AgentObservation;
import dev.agenticcommerce.gateway.agentization.model.AgentToolName;
import dev.agenticcommerce.gateway.agentization.model.AgentizationState;
import dev.agenticcommerce.gateway.agentization.model.CanonicalCapability;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record AgentDecisionContext(
        UUID runId,
        UUID merchantId,
        UUID artifactId,
        CanonicalCapability targetCapability,
        AgentizationState state,
        int stepCount,
        int maximumSteps,
        Set<AgentToolName> permittedTools,
        List<AgentObservation> recentObservations) {
}
