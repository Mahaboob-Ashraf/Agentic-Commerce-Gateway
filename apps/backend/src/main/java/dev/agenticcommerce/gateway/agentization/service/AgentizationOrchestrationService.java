package dev.agenticcommerce.gateway.agentization.service;

import dev.agenticcommerce.gateway.agentization.model.AgentObservation;
import dev.agenticcommerce.gateway.agentization.model.AgentizationRun;
import dev.agenticcommerce.gateway.agentization.model.AgentizationState;
import dev.agenticcommerce.gateway.agentization.model.ToolOutcome;
import dev.agenticcommerce.gateway.agentization.persistence.AgentObservationRepository;
import dev.agenticcommerce.gateway.agentization.persistence.AgentizationRunRepository;
import dev.agenticcommerce.gateway.agentization.persistence.CapabilityContractTestRunRepository;
import dev.agenticcommerce.gateway.agentization.tool.AgentDecisionContext;
import dev.agenticcommerce.gateway.agentization.tool.AgentToolExecutor;
import dev.agenticcommerce.gateway.agentization.tool.AgentToolRegistry;
import dev.agenticcommerce.gateway.agentization.tool.AgentizationDecisionProvider;
import dev.agenticcommerce.gateway.agentization.tool.NextAgentAction;
import dev.agenticcommerce.gateway.agentization.tool.ToolExecutionResult;
import dev.agenticcommerce.gateway.agentization.authority.DeterministicReadinessService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Performs one bounded state progression or one model-selected deterministic tool action. */
@Service
public class AgentizationOrchestrationService {

    private static final int RECENT_OBSERVATION_LIMIT = 10;
    static final int MAX_IDENTICAL_FAILURES = 3;

    private final AgentizationRunService runService;
    private final AgentizationRunRepository runRepository;
    private final AgentObservationRepository observationRepository;
    private final CapabilityContractTestRunRepository contractTestRepository;
    private final AgentToolRegistry toolRegistry;
    private final AgentToolExecutor toolExecutor;
    private final AgentizationDecisionProvider decisionProvider;
    private final CanonicalJsonService canonicalJsonService;
    private final OpenApiArtifactService artifactService;
    private final ObjectMapper objectMapper;
    private final DeterministicReadinessService readinessService;

    public AgentizationOrchestrationService(
            AgentizationRunService runService,
            AgentizationRunRepository runRepository,
            AgentObservationRepository observationRepository,
            CapabilityContractTestRunRepository contractTestRepository,
            AgentToolRegistry toolRegistry,
            AgentToolExecutor toolExecutor,
            AgentizationDecisionProvider decisionProvider,
            CanonicalJsonService canonicalJsonService,
            OpenApiArtifactService artifactService,
            DeterministicReadinessService readinessService,
            ObjectMapper objectMapper) {
        this.runService = runService;
        this.runRepository = runRepository;
        this.observationRepository = observationRepository;
        this.contractTestRepository = contractTestRepository;
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        this.decisionProvider = decisionProvider;
        this.canonicalJsonService = canonicalJsonService;
        this.artifactService = artifactService;
        this.readinessService = readinessService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AdvanceAgentizationResult advance(UUID actorId, UUID merchantId, UUID runId) {
        AgentizationRun run = runService.requireForUpdate(actorId, merchantId, runId);
        if (run.state().terminal()) {
            throw new AgentizationException(
                    "AGENTIZATION_RUN_TERMINAL", HttpStatus.CONFLICT,
                    "A terminal agentization run cannot advance");
        }
        if (!Instant.now().isBefore(run.wallClockDeadline())) {
            return terminalBudget(run, "WALL_CLOCK_DEADLINE_EXCEEDED");
        }
        if (run.stepCount() >= run.maxStepBudget()) {
            return terminalBudget(run, "STEP_BUDGET_EXHAUSTED");
        }

        if (run.state() == AgentizationState.AGENTIZATION_CREATED) {
            return new AdvanceAgentizationResult(
                    runService.transition(run, AgentizationState.INPUTS_VALIDATING, null),
                    null,
                    null);
        }
        if (run.state() == AgentizationState.INPUTS_VALIDATING) {
            artifactService.requireArtifact(run.merchantId(), run.sourceArtifactId());
            return new AdvanceAgentizationResult(
                    runService.transition(run, AgentizationState.INSPECTING_API, null),
                    null,
                    null);
        }

        var permittedTools = toolRegistry.permittedTools(run.state());
        var recentObservations = observationRepository.findRecentByMerchantAndRun(
                merchantId, runId, RECENT_OBSERVATION_LIMIT);
        NextAgentAction action = decisionProvider.chooseNextAction(new AgentDecisionContext(
                run.runId(),
                run.merchantId(),
                run.sourceArtifactId(),
                run.targetCapability(),
                run.state(),
                run.stepCount(),
                run.maxStepBudget(),
                permittedTools,
                recentObservations));
        if (action == null || action.tool() == null) {
            throw new AgentizationException(
                    "INVALID_AGENT_DECISION", HttpStatus.UNPROCESSABLE_ENTITY,
                    "The decision provider did not return a typed tool action");
        }

        AgentizationState toolState = run.state();
        run = runRepository.incrementStep(run);
        String inputHash = canonicalJsonService.hash(objectMapper.valueToTree(action));
        ToolExecutionResult execution = null;
        AgentObservation observation;

        if (!toolRegistry.isPermitted(toolState, action.tool())) {
            observation = observationRepository.create(
                    run,
                    action.tool(),
                    inputHash,
                    errorResult("TOOL_NOT_PERMITTED"),
                    ToolOutcome.DENIED,
                    "TOOL_NOT_PERMITTED",
                    boundedRationale(action.conciseReason()),
                    null);
        } else {
            try {
                execution = toolExecutor.execute(run, action);
                observation = observationRepository.create(
                        run,
                        action.tool(),
                        inputHash,
                        execution.structuredResult(),
                        ToolOutcome.SUCCESS,
                        boundedCode(execution.reasonCode()),
                        boundedRationale(action.conciseReason()),
                        execution);
            } catch (AgentizationException exception) {
                observation = observationRepository.create(
                        run,
                        action.tool(),
                        inputHash,
                        errorResult(exception.code()),
                        ToolOutcome.FAILURE,
                        boundedCode(exception.code()),
                        boundedRationale(action.conciseReason()),
                        null);
            }
        }

        run = runRepository.attachObservation(run, observation.observationId());
        if (observation.outcome() == ToolOutcome.SUCCESS
                && toolState == AgentizationState.INSPECTING_API) {
            run = runService.transition(run, AgentizationState.MAPPING_CAPABILITY, null);
        }
        if (observation.outcome() == ToolOutcome.SUCCESS && execution != null) {
            switch (action.tool()) {
                case VALIDATE_MAPPING -> {
                    run = runRepository.setCurrentMappingVersion(
                            run, execution.mappingProposal().mappingVersion());
                    run = runService.transition(run, AgentizationState.TESTING_CAPABILITY, null);
                }
                case RUN_CONTRACT_TEST -> {
                    var testRun = execution.contractTestRun();
                    if (testRun.outcome()
                            == dev.agenticcommerce.gateway.agentization.model.ContractTestOutcome.PASS) {
                        run = runRepository.clearFailure(run);
                        run = runService.transition(run, AgentizationState.READY_CANDIDATE, null);
                    } else {
                        int repeated = contractTestRepository.countFailureSignature(
                                run.runId(), testRun.failureSignature());
                        run = runRepository.recordFailure(run, testRun.failureSignature(), repeated);
                        AgentizationState next = repeated >= MAX_IDENTICAL_FAILURES
                                ? AgentizationState.WAITING_FOR_MERCHANT_CLARIFICATION
                                : AgentizationState.DIAGNOSING_FAILURE;
                        run = runService.transition(
                                run,
                                next,
                                next == AgentizationState.WAITING_FOR_MERCHANT_CLARIFICATION
                                        ? "REPEATED_IDENTICAL_FAILURE" : null);
                    }
                }
                case INSPECT_TEST_FAILURE ->
                    run = runService.transition(run, AgentizationState.REVISING_MAPPING, null);
                case REQUEST_MERCHANT_CLARIFICATION ->
                    run = runService.transition(run, AgentizationState.WAITING_FOR_MERCHANT_CLARIFICATION,
                            "OPEN_AGENT_REQUESTED_CLARIFICATION");
                case REQUEST_MERCHANT_APPROVAL, EXTRACT_POLICY_RULES ->
                    run = runService.transition(run, AgentizationState.WAITING_FOR_MERCHANT_APPROVAL, null);
                case PUBLISH_MANIFEST_CANDIDATE -> {
                    readinessService.publishManifestCandidate(actorId, merchantId, runId);
                    run = runService.requireForUpdate(actorId, merchantId, runId);
                }
                default -> {
                    // Other tools do not cause an implicit state transition.
                }
            }
        }
        if (!run.state().terminal() && run.stepCount() >= run.maxStepBudget()) {
            run = runService.transition(
                    run, AgentizationState.BUDGET_EXHAUSTED, "STEP_BUDGET_EXHAUSTED");
        }
        return new AdvanceAgentizationResult(
                run,
                observation,
                execution == null ? null : execution.mappingProposal());
    }

    private AdvanceAgentizationResult terminalBudget(AgentizationRun run, String reason) {
        return new AdvanceAgentizationResult(
                runService.transition(run, AgentizationState.BUDGET_EXHAUSTED, reason),
                null,
                null);
    }

    private tools.jackson.databind.JsonNode errorResult(String code) {
        var result = objectMapper.createObjectNode();
        result.put("errorCode", boundedCode(code));
        return result;
    }

    private static String boundedCode(String code) {
        if (code == null || code.isBlank()) {
            return "UNSPECIFIED_TOOL_RESULT";
        }
        String normalized = code.strip();
        return normalized.length() <= 64 ? normalized : normalized.substring(0, 64);
    }

    private static String boundedRationale(String rationale) {
        if (rationale == null || rationale.isBlank()) {
            return null;
        }
        String normalized = rationale.strip();
        return normalized.length() <= 512 ? normalized : normalized.substring(0, 512);
    }
}
