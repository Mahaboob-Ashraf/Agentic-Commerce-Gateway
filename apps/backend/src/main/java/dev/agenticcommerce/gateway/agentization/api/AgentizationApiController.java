package dev.agenticcommerce.gateway.agentization.api;

import dev.agenticcommerce.gateway.agentization.model.AgentObservation;
import dev.agenticcommerce.gateway.agentization.model.AgentizationRun;
import dev.agenticcommerce.gateway.agentization.model.ApprovedMerchantEndpoint;
import dev.agenticcommerce.gateway.agentization.model.CapabilityContractTestRun;
import dev.agenticcommerce.gateway.agentization.model.CanonicalCapability;
import dev.agenticcommerce.gateway.agentization.model.CapabilityMappingProposal;
import dev.agenticcommerce.gateway.agentization.model.OpenApiArtifact;
import dev.agenticcommerce.gateway.agentization.persistence.AgentObservationRepository;
import dev.agenticcommerce.gateway.agentization.persistence.ApprovedMerchantEndpointRepository;
import dev.agenticcommerce.gateway.agentization.persistence.CapabilityContractTestRunRepository;
import dev.agenticcommerce.gateway.agentization.persistence.CapabilityMappingProposalRepository;
import dev.agenticcommerce.gateway.agentization.service.AdvanceAgentizationResult;
import dev.agenticcommerce.gateway.agentization.service.AgentizationOrchestrationService;
import dev.agenticcommerce.gateway.agentization.service.AgentizationRunService;
import dev.agenticcommerce.gateway.agentization.service.ApprovedMerchantEndpointService;
import dev.agenticcommerce.gateway.agentization.service.MerchantAgentizationAccessService;
import dev.agenticcommerce.gateway.agentization.service.OpenApiArtifactService;
import dev.agenticcommerce.gateway.identity.authentication.VerifiedActorPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/merchants/{merchantId}/agentization")
public class AgentizationApiController {

    private final MerchantAgentizationAccessService accessService;
    private final OpenApiArtifactService artifactService;
    private final AgentizationRunService runService;
    private final AgentizationOrchestrationService orchestrationService;
    private final AgentObservationRepository observationRepository;
    private final CapabilityMappingProposalRepository mappingRepository;
    private final ApprovedMerchantEndpointService endpointService;
    private final ApprovedMerchantEndpointRepository endpointRepository;
    private final CapabilityContractTestRunRepository contractTestRepository;

    public AgentizationApiController(
            MerchantAgentizationAccessService accessService,
            OpenApiArtifactService artifactService,
            AgentizationRunService runService,
            AgentizationOrchestrationService orchestrationService,
            AgentObservationRepository observationRepository,
            CapabilityMappingProposalRepository mappingRepository,
            ApprovedMerchantEndpointService endpointService,
            ApprovedMerchantEndpointRepository endpointRepository,
            CapabilityContractTestRunRepository contractTestRepository) {
        this.accessService = accessService;
        this.artifactService = artifactService;
        this.runService = runService;
        this.orchestrationService = orchestrationService;
        this.observationRepository = observationRepository;
        this.mappingRepository = mappingRepository;
        this.endpointService = endpointService;
        this.endpointRepository = endpointRepository;
        this.contractTestRepository = contractTestRepository;
    }

    @PostMapping("/endpoints")
    @ResponseStatus(HttpStatus.CREATED)
    public ApprovedMerchantEndpoint registerAndApproveEndpoint(
            @AuthenticationPrincipal VerifiedActorPrincipal principal,
            @PathVariable UUID merchantId,
            @Valid @RequestBody RegisterApprovedEndpointRequest request) {
        return endpointService.registerAndApprove(
                principal.actorId(), merchantId, request.baseUri(), request.methods(), request.pathTemplates());
    }

    @GetMapping("/endpoints/{endpointId}")
    public ApprovedMerchantEndpoint getEndpoint(
            @AuthenticationPrincipal VerifiedActorPrincipal principal,
            @PathVariable UUID merchantId,
            @PathVariable UUID endpointId) {
        accessService.requireMerchantAdmin(principal.actorId(), merchantId);
        return endpointRepository.findAnyByMerchantAndId(merchantId, endpointId)
                .orElseThrow(() -> new dev.agenticcommerce.gateway.agentization.service.AgentizationException(
                        "ENDPOINT_NOT_FOUND", HttpStatus.NOT_FOUND, "Merchant endpoint was not found"));
    }

    @PostMapping("/artifacts/openapi")
    @ResponseStatus(HttpStatus.CREATED)
    public OpenApiArtifactResponse registerOpenApiArtifact(
            @AuthenticationPrincipal VerifiedActorPrincipal principal,
            @PathVariable UUID merchantId,
            @Valid @RequestBody RegisterOpenApiArtifactRequest request) {
        accessService.requireMerchantAdmin(principal.actorId(), merchantId);
        return OpenApiArtifactResponse.from(artifactService.register(
                merchantId, request.endpointId(), request.artifactVersion(), request.document()));
    }

    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentizationRun startRun(
            @AuthenticationPrincipal VerifiedActorPrincipal principal,
            @PathVariable UUID merchantId,
            @Valid @RequestBody StartAgentizationRunRequest request) {
        return runService.start(
                principal.actorId(),
                merchantId,
                request.artifactId(),
                request.capability(),
                request.maximumSteps(),
                request.deadline());
    }

    @GetMapping("/runs/{runId}")
    public AgentizationRun getRun(
            @AuthenticationPrincipal VerifiedActorPrincipal principal,
            @PathVariable UUID merchantId,
            @PathVariable UUID runId) {
        return runService.require(principal.actorId(), merchantId, runId);
    }

    @PostMapping("/runs/{runId}/advance")
    public AdvanceAgentizationResult advanceRun(
            @AuthenticationPrincipal VerifiedActorPrincipal principal,
            @PathVariable UUID merchantId,
            @PathVariable UUID runId) {
        return orchestrationService.advance(principal.actorId(), merchantId, runId);
    }

    @GetMapping("/runs/{runId}/observations")
    public List<AgentObservation> listObservations(
            @AuthenticationPrincipal VerifiedActorPrincipal principal,
            @PathVariable UUID merchantId,
            @PathVariable UUID runId) {
        runService.require(principal.actorId(), merchantId, runId);
        return observationRepository.findAllByMerchantAndRun(merchantId, runId);
    }

    @GetMapping("/runs/{runId}/mapping-proposals")
    public List<CapabilityMappingProposal> listMappingProposals(
            @AuthenticationPrincipal VerifiedActorPrincipal principal,
            @PathVariable UUID merchantId,
            @PathVariable UUID runId) {
        runService.require(principal.actorId(), merchantId, runId);
        return mappingRepository.findAllByMerchantAndRun(merchantId, runId);
    }

    @GetMapping("/runs/{runId}/contract-tests")
    public List<CapabilityContractTestRun> listContractTests(
            @AuthenticationPrincipal VerifiedActorPrincipal principal,
            @PathVariable UUID merchantId,
            @PathVariable UUID runId) {
        runService.require(principal.actorId(), merchantId, runId);
        return contractTestRepository.findAllByMerchantAndRun(merchantId, runId);
    }

    public record RegisterApprovedEndpointRequest(
            @NotBlank @Size(max = 2048) String baseUri,
            @NotEmpty @Size(max = 5) Set<@NotBlank @Size(max = 16) String> methods,
            @NotEmpty @Size(max = 32) List<@NotBlank @Size(max = 1024) String> pathTemplates) {
    }

    public record RegisterOpenApiArtifactRequest(
            @NotNull UUID endpointId,
            @NotBlank @Size(max = 64) String artifactVersion,
            @NotNull JsonNode document) {
    }

    public record StartAgentizationRunRequest(
            @NotNull UUID artifactId,
            @NotNull CanonicalCapability capability,
            @Min(1) @Max(AgentizationRunService.MAX_STEP_BUDGET) int maximumSteps,
            @NotNull @Future Instant deadline) {
    }

    public record OpenApiArtifactResponse(
            UUID artifactId,
            UUID merchantId,
            UUID endpointId,
            String artifactType,
            String artifactVersion,
            String contentHash,
            Instant createdAt) {

        static OpenApiArtifactResponse from(OpenApiArtifact artifact) {
            return new OpenApiArtifactResponse(
                    artifact.artifactId(),
                    artifact.merchantId(),
                    artifact.endpointId(),
                    artifact.artifactType(),
                    artifact.artifactVersion(),
                    artifact.contentHash(),
                    artifact.createdAt());
        }
    }
}
