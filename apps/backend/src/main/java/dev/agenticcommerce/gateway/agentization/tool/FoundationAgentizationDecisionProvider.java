package dev.agenticcommerce.gateway.agentization.tool;

import dev.agenticcommerce.gateway.agentization.model.AgentizationState;
import dev.agenticcommerce.gateway.agentization.service.AgentizationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Safe non-model default: it can inspect the approved artifact once but cannot invent a mapping.
 */
@Component
public class FoundationAgentizationDecisionProvider implements AgentizationDecisionProvider {

    @Override
    public NextAgentAction chooseNextAction(AgentDecisionContext context) {
        if (context.state() == AgentizationState.INSPECTING_API) {
            return NextAgentAction.inspectSpec(
                    context.artifactId(), 20, "Inspect approved OpenAPI operations");
        }
        throw new AgentizationException(
                "AGENT_DECISION_UNAVAILABLE", HttpStatus.CONFLICT,
                "No live mapping-reasoning provider is configured");
    }
}
