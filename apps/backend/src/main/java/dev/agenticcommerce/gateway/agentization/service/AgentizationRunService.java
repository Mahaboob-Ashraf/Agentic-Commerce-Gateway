package dev.agenticcommerce.gateway.agentization.service;

import dev.agenticcommerce.gateway.agentization.model.AgentizationRun;
import dev.agenticcommerce.gateway.agentization.model.AgentizationState;
import dev.agenticcommerce.gateway.agentization.model.CanonicalCapability;
import dev.agenticcommerce.gateway.agentization.persistence.AgentizationRunRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentizationRunService {

    public static final int MAX_STEP_BUDGET = 100;
    public static final Duration MAX_RUN_DURATION = Duration.ofHours(24);

    private final AgentizationRunRepository runRepository;
    private final OpenApiArtifactService artifactService;
    private final MerchantAgentizationAccessService accessService;
    private final AgentizationStateMachine stateMachine;

    public AgentizationRunService(
            AgentizationRunRepository runRepository,
            OpenApiArtifactService artifactService,
            MerchantAgentizationAccessService accessService,
            AgentizationStateMachine stateMachine) {
        this.runRepository = runRepository;
        this.artifactService = artifactService;
        this.accessService = accessService;
        this.stateMachine = stateMachine;
    }

    @Transactional
    public AgentizationRun start(
            UUID actorId,
            UUID merchantId,
            UUID artifactId,
            CanonicalCapability capability,
            int maximumSteps,
            Instant deadline) {
        accessService.requireMerchantAdmin(actorId, merchantId);
        if (capability == null || maximumSteps < 1 || maximumSteps > MAX_STEP_BUDGET) {
            throw invalid("INVALID_AGENTIZATION_BUDGET", "Capability and bounded step budget are required");
        }
        Instant now = Instant.now();
        if (deadline == null || !deadline.isAfter(now) || deadline.isAfter(now.plus(MAX_RUN_DURATION))) {
            throw invalid("INVALID_AGENTIZATION_DEADLINE", "Deadline must be within the next 24 hours");
        }
        artifactService.requireArtifact(merchantId, artifactId);
        return runRepository.create(
                merchantId, actorId, artifactId, capability, maximumSteps, deadline);
    }

    public AgentizationRun require(UUID actorId, UUID merchantId, UUID runId) {
        accessService.requireMerchantAdmin(actorId, merchantId);
        return runRepository.findByMerchantAndId(merchantId, runId)
                .orElseThrow(AgentizationRunService::notFound);
    }

    public AgentizationRun requireForUpdate(UUID actorId, UUID merchantId, UUID runId) {
        accessService.requireMerchantAdmin(actorId, merchantId);
        return runRepository.findByMerchantAndIdForUpdate(merchantId, runId)
                .orElseThrow(AgentizationRunService::notFound);
    }

    @Transactional
    public AgentizationRun transition(
            AgentizationRun current, AgentizationState next, String terminalReason) {
        stateMachine.requireTransition(current.state(), next);
        String reason = next.terminal()
                ? boundedReason(terminalReason)
                : terminalReason == null ? null : boundedReason(terminalReason);
        return runRepository.transition(current, next, reason);
    }

    private static String boundedReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw invalid("TERMINAL_REASON_REQUIRED", "Terminal transitions require a reason");
        }
        String normalized = reason.strip();
        return normalized.length() <= 512 ? normalized : normalized.substring(0, 512);
    }

    private static AgentizationException notFound() {
        return new AgentizationException(
                "AGENTIZATION_RUN_NOT_FOUND", HttpStatus.NOT_FOUND,
                "Agentization run was not found");
    }

    private static AgentizationException invalid(String code, String message) {
        return new AgentizationException(code, HttpStatus.BAD_REQUEST, message);
    }
}
