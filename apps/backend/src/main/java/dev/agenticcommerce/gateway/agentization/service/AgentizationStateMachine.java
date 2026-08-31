package dev.agenticcommerce.gateway.agentization.service;

import dev.agenticcommerce.gateway.agentization.model.AgentizationState;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Explicit transition boundary. Later-state transitions remain unavailable until implemented. */
@Component
public class AgentizationStateMachine {

    private static final Set<AgentizationState> TERMINAL_STATES = Set.of(
            AgentizationState.BLOCKED,
            AgentizationState.BUDGET_EXHAUSTED,
            AgentizationState.FAILED);

    private static final Map<AgentizationState, Set<AgentizationState>> TRANSITIONS = Map.ofEntries(
            Map.entry(AgentizationState.AGENTIZATION_CREATED, Set.of(AgentizationState.INPUTS_VALIDATING)),
            Map.entry(AgentizationState.INPUTS_VALIDATING, Set.of(AgentizationState.INSPECTING_API)),
            Map.entry(AgentizationState.INSPECTING_API, Set.of(AgentizationState.MAPPING_CAPABILITY)),
            Map.entry(AgentizationState.MAPPING_CAPABILITY, Set.of(AgentizationState.TESTING_CAPABILITY,
                    AgentizationState.EXTRACTING_POLICY, AgentizationState.WAITING_FOR_MERCHANT_APPROVAL)),
            Map.entry(AgentizationState.TESTING_CAPABILITY, Set.of(AgentizationState.DIAGNOSING_FAILURE,
                    AgentizationState.READY_CANDIDATE)),
            Map.entry(AgentizationState.DIAGNOSING_FAILURE, Set.of(AgentizationState.REVISING_MAPPING)),
            Map.entry(AgentizationState.REVISING_MAPPING, Set.of(AgentizationState.TESTING_CAPABILITY,
                    AgentizationState.WAITING_FOR_MERCHANT_APPROVAL)),
            Map.entry(AgentizationState.EXTRACTING_POLICY, Set.of(AgentizationState.WAITING_FOR_MERCHANT_APPROVAL,
                    AgentizationState.READY_CANDIDATE)),
            Map.entry(AgentizationState.WAITING_FOR_MERCHANT_APPROVAL, Set.of(
                    AgentizationState.EXTRACTING_POLICY, AgentizationState.READY_CANDIDATE,
                    AgentizationState.COMPLETE)),
            Map.entry(AgentizationState.WAITING_FOR_MERCHANT_CLARIFICATION, Set.of(
                    AgentizationState.DIAGNOSING_FAILURE, AgentizationState.REVISING_MAPPING,
                    AgentizationState.EXTRACTING_POLICY, AgentizationState.WAITING_FOR_MERCHANT_APPROVAL,
                    AgentizationState.READY_CANDIDATE)),
            Map.entry(AgentizationState.READY_CANDIDATE, Set.of(
                    AgentizationState.WAITING_FOR_MERCHANT_APPROVAL, AgentizationState.EXTRACTING_POLICY,
                    AgentizationState.COMPLETE)));

    public void requireTransition(AgentizationState current, AgentizationState next) {
        if (current == null || next == null || current.terminal()) {
            throw invalid(current, next);
        }
        if (TERMINAL_STATES.contains(next)
                || next == AgentizationState.WAITING_FOR_MERCHANT_CLARIFICATION
                || TRANSITIONS.getOrDefault(current, Set.of()).contains(next)) {
            return;
        }
        throw invalid(current, next);
    }

    private static AgentizationException invalid(
            AgentizationState current, AgentizationState next) {
        return new AgentizationException(
                "INVALID_AGENTIZATION_TRANSITION",
                HttpStatus.CONFLICT,
                "Agentization transition is not permitted: " + current + " -> " + next);
    }
}
