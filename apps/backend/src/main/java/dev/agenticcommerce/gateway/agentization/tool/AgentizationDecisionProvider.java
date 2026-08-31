package dev.agenticcommerce.gateway.agentization.tool;

/** Reasoning boundary for Gemini or a deterministic test stub; it has no execution authority. */
public interface AgentizationDecisionProvider {

    NextAgentAction chooseNextAction(AgentDecisionContext context);
}
