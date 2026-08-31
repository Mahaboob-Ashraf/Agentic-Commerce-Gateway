package dev.agenticcommerce.gateway.agentization.tool;

import dev.agenticcommerce.gateway.agentization.model.AgentToolName;
import dev.agenticcommerce.gateway.agentization.model.AgentizationState;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class AgentToolRegistry {

    private static final Map<AgentizationState, Set<AgentToolName>> PERMITTED = Map.ofEntries(
            Map.entry(AgentizationState.INSPECTING_API,
                    Set.of(AgentToolName.INSPECT_SPEC, AgentToolName.INSPECT_SCHEMA,
                            AgentToolName.INSPECT_CATALOG_SAMPLE)),
            Map.entry(AgentizationState.MAPPING_CAPABILITY,
                    Set.of(AgentToolName.INSPECT_SPEC, AgentToolName.INSPECT_SCHEMA,
                            AgentToolName.PROPOSE_MAPPING, AgentToolName.VALIDATE_MAPPING,
                            AgentToolName.INSPECT_CATALOG_SAMPLE, AgentToolName.REQUEST_MERCHANT_CLARIFICATION)),
            Map.entry(AgentizationState.TESTING_CAPABILITY, Set.of(AgentToolName.RUN_CONTRACT_TEST)),
            Map.entry(AgentizationState.DIAGNOSING_FAILURE,
                    Set.of(AgentToolName.INSPECT_TEST_FAILURE, AgentToolName.INSPECT_SPEC,
                            AgentToolName.INSPECT_SCHEMA, AgentToolName.INSPECT_CATALOG_SAMPLE,
                            AgentToolName.REQUEST_MERCHANT_CLARIFICATION)),
            Map.entry(AgentizationState.REVISING_MAPPING,
                    Set.of(AgentToolName.REVISE_MAPPING, AgentToolName.VALIDATE_MAPPING,
                            AgentToolName.REQUEST_MERCHANT_APPROVAL, AgentToolName.REQUEST_MERCHANT_CLARIFICATION)),
            Map.entry(AgentizationState.EXTRACTING_POLICY,
                    Set.of(AgentToolName.INSPECT_POLICY, AgentToolName.EXTRACT_POLICY_RULES,
                            AgentToolName.INSPECT_CATALOG_SAMPLE, AgentToolName.REQUEST_MERCHANT_CLARIFICATION,
                            AgentToolName.REQUEST_MERCHANT_APPROVAL)),
            Map.entry(AgentizationState.READY_CANDIDATE,
                    Set.of(AgentToolName.REQUEST_MERCHANT_APPROVAL,
                            AgentToolName.INSPECT_CATALOG_SAMPLE,
                            AgentToolName.PUBLISH_MANIFEST_CANDIDATE)),
            Map.entry(AgentizationState.WAITING_FOR_MERCHANT_APPROVAL,
                    Set.of(AgentToolName.PUBLISH_MANIFEST_CANDIDATE)));

    public Set<AgentToolName> permittedTools(AgentizationState state) {
        return PERMITTED.getOrDefault(state, Set.of());
    }

    public boolean isPermitted(AgentizationState state, AgentToolName toolName) {
        return permittedTools(state).contains(toolName);
    }
}
