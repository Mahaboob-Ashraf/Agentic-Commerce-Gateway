package dev.agenticcommerce.gateway.agentization.tool;

import dev.agenticcommerce.gateway.agentization.model.CapabilityMappingProposal;
import dev.agenticcommerce.gateway.agentization.model.CapabilityContractTestRun;
import tools.jackson.databind.JsonNode;

public record ToolExecutionResult(
        JsonNode structuredResult,
        String reasonCode,
        CapabilityMappingProposal mappingProposal,
        CapabilityContractTestRun contractTestRun,
        Integer mappingVersionBefore,
        Integer mappingVersionAfter) {

    public static ToolExecutionResult simple(JsonNode result, String reasonCode) {
        return new ToolExecutionResult(result, reasonCode, null, null, null, null);
    }
}
