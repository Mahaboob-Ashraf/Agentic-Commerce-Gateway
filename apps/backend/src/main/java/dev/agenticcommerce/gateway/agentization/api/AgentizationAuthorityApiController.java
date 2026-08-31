package dev.agenticcommerce.gateway.agentization.api;

import static dev.agenticcommerce.gateway.agentization.authority.AuthorityModels.*;

import dev.agenticcommerce.gateway.agentization.authority.DeterministicReadinessService;
import dev.agenticcommerce.gateway.agentization.authority.MerchantAuthorityService;
import dev.agenticcommerce.gateway.agentization.authority.PolicyAuthorityService;
import dev.agenticcommerce.gateway.identity.authentication.VerifiedActorPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
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
public class AgentizationAuthorityApiController {
    private final MerchantAuthorityService authority;
    private final PolicyAuthorityService policies;
    private final DeterministicReadinessService readiness;

    public AgentizationAuthorityApiController(MerchantAuthorityService authority,
            PolicyAuthorityService policies, DeterministicReadinessService readiness) {
        this.authority=authority; this.policies=policies; this.readiness=readiness;
    }

    @PostMapping("/runs/{runId}/clarifications") @ResponseStatus(HttpStatus.CREATED)
    public MerchantClarification requestClarification(@AuthenticationPrincipal VerifiedActorPrincipal principal,
            @PathVariable UUID merchantId,@PathVariable UUID runId,
            @Valid @RequestBody ClarificationRequest request) {
        authority.listClarifications(principal.actorId(),merchantId,runId);
        // The authenticated admin may initiate the same durable request boundary; it still cannot answer as the model.
        return authority.requestClarification(merchantId,runId,request.mappingProposalId(),
                request.policyDocumentId(),request.policyRuleId(),request.question(),request.evidenceReferences(),
                request.kind(),request.continuationState());
    }

    @GetMapping("/runs/{runId}/clarifications")
    public List<MerchantClarification> clarifications(@AuthenticationPrincipal VerifiedActorPrincipal principal,
            @PathVariable UUID merchantId,@PathVariable UUID runId) {
        return authority.listClarifications(principal.actorId(),merchantId,runId);
    }

    @PostMapping("/runs/{runId}/clarifications/{clarificationId}/answer")
    public MerchantClarification answer(@AuthenticationPrincipal VerifiedActorPrincipal principal,
            @PathVariable UUID merchantId,@PathVariable UUID runId,@PathVariable UUID clarificationId,
            @Valid @RequestBody ClarificationAnswer request) {
        return authority.answerClarification(principal.actorId(),merchantId,runId,clarificationId,request.response());
    }

    @PostMapping("/runs/{runId}/mappings/{mappingId}/decisions") @ResponseStatus(HttpStatus.CREATED)
    public MappingApprovalDecision decideMapping(@AuthenticationPrincipal VerifiedActorPrincipal principal,
            @PathVariable UUID merchantId,@PathVariable UUID runId,@PathVariable UUID mappingId,
            @Valid @RequestBody AuthorityDecisionRequest request) {
        return authority.decideMapping(principal.actorId(),merchantId,runId,mappingId,request.decision(),request.note());
    }

    @GetMapping("/runs/{runId}/mappings/{mappingId}/decisions")
    public List<MappingApprovalDecision> mappingDecisions(@AuthenticationPrincipal VerifiedActorPrincipal principal,
            @PathVariable UUID merchantId,@PathVariable UUID runId,@PathVariable UUID mappingId) {
        return authority.mappingApprovals(principal.actorId(),merchantId,runId,mappingId);
    }

    @PostMapping("/policies") @ResponseStatus(HttpStatus.CREATED)
    public PolicyDocument uploadPolicy(@AuthenticationPrincipal VerifiedActorPrincipal principal,
            @PathVariable UUID merchantId,@Valid @RequestBody PolicyUpload request) {
        return policies.upload(principal.actorId(),merchantId,request.documentType(),request.title(),request.content());
    }

    @GetMapping("/policies")
    public List<PolicyDocument> policies(@AuthenticationPrincipal VerifiedActorPrincipal principal,
            @PathVariable UUID merchantId) { return policies.documents(principal.actorId(),merchantId); }

    @PostMapping("/policies/{documentId}/extract") @ResponseStatus(HttpStatus.CREATED)
    public List<ProposedPolicyRule> extractPolicy(@AuthenticationPrincipal VerifiedActorPrincipal principal,
            @PathVariable UUID merchantId,@PathVariable UUID documentId) {
        return policies.extract(principal.actorId(),merchantId,documentId);
    }

    @GetMapping("/policies/{documentId}/rules")
    public List<ProposedPolicyRule> rules(@AuthenticationPrincipal VerifiedActorPrincipal principal,
            @PathVariable UUID merchantId,@PathVariable UUID documentId) {
        return policies.rules(principal.actorId(),merchantId,documentId);
    }

    @PostMapping("/policy-rules/{ruleId}/decisions") @ResponseStatus(HttpStatus.CREATED)
    public PolicyRuleApprovalDecision decideRule(@AuthenticationPrincipal VerifiedActorPrincipal principal,
            @PathVariable UUID merchantId,@PathVariable UUID ruleId,
            @Valid @RequestBody AuthorityDecisionRequest request) {
        return policies.decideRule(principal.actorId(),merchantId,ruleId,request.decision(),request.note());
    }

    @PostMapping("/policy-snapshots") @ResponseStatus(HttpStatus.CREATED)
    public PolicySnapshot publishPolicySnapshot(@AuthenticationPrincipal VerifiedActorPrincipal principal,
            @PathVariable UUID merchantId) { return policies.publishSnapshot(principal.actorId(),merchantId); }

    @PostMapping("/policy-snapshots/{snapshotId}/resolve")
    public PolicyResolution resolvePolicy(@AuthenticationPrincipal VerifiedActorPrincipal principal,
            @PathVariable UUID merchantId,@PathVariable UUID snapshotId,
            @Valid @RequestBody PolicyResolutionRequest request) {
        // Membership is proven by reading the merchant's documents first.
        policies.documents(principal.actorId(),merchantId);
        return policies.resolve(merchantId,snapshotId,request);
    }

    @PostMapping("/runs/{runId}/readiness/{capability}") @ResponseStatus(HttpStatus.CREATED)
    public ReadinessEvaluation evaluate(@AuthenticationPrincipal VerifiedActorPrincipal principal,
            @PathVariable UUID merchantId,@PathVariable UUID runId,@PathVariable ReadinessCapability capability) {
        return readiness.evaluate(principal.actorId(),merchantId,runId,capability);
    }

    @GetMapping("/runs/{runId}/readiness")
    public List<ReadinessEvaluation> evaluations(@AuthenticationPrincipal VerifiedActorPrincipal principal,
            @PathVariable UUID merchantId,@PathVariable UUID runId) {
        return readiness.evaluations(principal.actorId(),merchantId,runId);
    }

    @PostMapping("/runs/{runId}/manifests") @ResponseStatus(HttpStatus.CREATED)
    public AgentCommerceManifest publishManifest(@AuthenticationPrincipal VerifiedActorPrincipal principal,
            @PathVariable UUID merchantId,@PathVariable UUID runId) {
        return readiness.publishManifestCandidate(principal.actorId(),merchantId,runId);
    }

    @GetMapping("/manifests")
    public List<AgentCommerceManifest> manifests(@AuthenticationPrincipal VerifiedActorPrincipal principal,
            @PathVariable UUID merchantId) { return readiness.manifests(principal.actorId(),merchantId); }

    @GetMapping("/manifests/latest")
    public AgentCommerceManifest latestManifest(@AuthenticationPrincipal VerifiedActorPrincipal principal,
            @PathVariable UUID merchantId) { return readiness.latestManifest(principal.actorId(),merchantId); }

    public record ClarificationRequest(UUID mappingProposalId,UUID policyDocumentId,UUID policyRuleId,
            @NotBlank @Size(max=512) String question,@NotNull JsonNode evidenceReferences,
            @NotNull ClarificationKind kind,@NotNull dev.agenticcommerce.gateway.agentization.model.AgentizationState continuationState) {}
    public record ClarificationAnswer(@NotBlank @Size(max=2000) String response) {}
    public record AuthorityDecisionRequest(@NotNull AuthorityDecision decision,@Size(max=512) String note) {}
    public record PolicyUpload(@NotNull PolicyDocumentType documentType,@NotBlank @Size(max=256) String title,
            @NotBlank @Size(max=PolicyAuthorityService.MAX_POLICY_CHARACTERS) String content) {}
}
