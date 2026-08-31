package dev.agenticcommerce.gateway.agentization.service;

import dev.agenticcommerce.gateway.agentization.model.AgentObservation;
import dev.agenticcommerce.gateway.agentization.model.AgentizationRun;
import dev.agenticcommerce.gateway.agentization.model.CapabilityMappingProposal;

public record AdvanceAgentizationResult(
        AgentizationRun run,
        AgentObservation observation,
        CapabilityMappingProposal mappingProposal) {
}
