package dev.agenticcommerce.gateway.agentization.model;

public enum AgentizationState {
    AGENTIZATION_CREATED,
    INPUTS_VALIDATING,
    INSPECTING_API,
    MAPPING_CAPABILITY,
    EXTRACTING_POLICY,
    WAITING_FOR_MERCHANT_APPROVAL,
    TESTING_CAPABILITY,
    DIAGNOSING_FAILURE,
    REVISING_MAPPING,
    WAITING_FOR_MERCHANT_CLARIFICATION,
    READY_CANDIDATE,
    COMPLETE,
    BLOCKED,
    BUDGET_EXHAUSTED,
    FAILED;

    public boolean terminal() {
        return this == COMPLETE || this == BLOCKED || this == BUDGET_EXHAUSTED || this == FAILED;
    }
}
