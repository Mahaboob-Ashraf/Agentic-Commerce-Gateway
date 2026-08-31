package dev.agenticcommerce.gateway.agentization.authority;

import dev.agenticcommerce.gateway.agentization.model.AgentizationState;
import dev.agenticcommerce.gateway.agentization.model.CanonicalCapability;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Typed, non-executable authority records persisted by the Task 006 boundary. */
public final class AuthorityModels {
    private AuthorityModels() {}

    public enum AuthorityDecision { APPROVE, REJECT }
    public enum ClarificationStatus { OPEN, ANSWERED, CANCELLED }
    public enum ClarificationKind { MAPPING, POLICY, LIFECYCLE, IDEMPOTENCY, MONEY_SEMANTICS }
    public enum PolicyDocumentType { CANCELLATION, RETURN, REFUND, SHIPPING, REPLACEMENT, GENERAL_COMMERCE }
    public enum PolicyRuleType {
        CANCELLATION_WINDOW, RETURN_WINDOW, REFUND_ELIGIBILITY,
        NON_RETURNABLE, SHIPPING_RULE, REPLACEMENT_ELIGIBILITY
    }
    public enum PolicyApprovalState { PROPOSED, APPROVED, REJECTED }
    public enum PolicyDecisionOutcome { PASS, FAIL, UNKNOWN }
    public enum CapabilityReadiness { READY, BLOCKED, UNTESTED }
    public enum ReadinessCapability {
        SEARCH_PRODUCTS, GET_AVAILABILITY, GET_QUOTE, PLACE_ORDER,
        GET_ORDER_STATE, CANCEL_ORDER, RETURN_ITEM, REFUND, PURCHASE;

        public static ReadinessCapability from(CanonicalCapability capability) {
            return valueOf(capability.name());
        }
    }

    public record MerchantClarification(
            UUID clarificationId, UUID merchantId, UUID runId, CanonicalCapability capability,
            UUID mappingProposalId, UUID policyDocumentId, UUID policyRuleId,
            String question, JsonNode evidenceReferences, ClarificationKind kind,
            AgentizationState continuationState, ClarificationStatus status,
            String merchantResponse, UUID respondingActorId, Instant createdAt,
            Instant answeredAt, Instant cancelledAt) {}

    public record MappingApprovalDecision(
            UUID approvalId, UUID merchantId, UUID runId, UUID mappingProposalId,
            int mappingVersion, String mappingContentHash, AuthorityDecision decision,
            UUID approvingActorId, String merchantNote, Instant decidedAt) {}

    public record PolicyDocument(
            UUID policyDocumentId, UUID merchantId, PolicyDocumentType documentType,
            int documentVersion, String title, String normalizedContent, String contentHash,
            UUID uploadedByActorId, Instant createdAt) {}

    public record ProposedPolicyRuleInput(
            PolicyRuleType ruleType, String sourceClause, JsonNode applicabilityConditions,
            JsonNode outcomeEffect, BigDecimal modelConfidence, Integer precedencePriority,
            Instant effectiveFrom, Instant effectiveTo) {}

    public record ProposedPolicyRule(
            UUID policyRuleId, UUID merchantId, UUID policyDocumentId, int documentVersion,
            int ruleVersion, PolicyRuleType ruleType, String sourceClause,
            JsonNode applicabilityConditions, JsonNode outcomeEffect, BigDecimal modelConfidence,
            Integer precedencePriority, Instant effectiveFrom, Instant effectiveTo,
            String extractionProvider, String extractionModel, String ruleContentHash,
            PolicyApprovalState approvalState, Instant createdAt) {}

    public record PolicyRuleApprovalDecision(
            UUID approvalId, UUID merchantId, UUID policyRuleId, int ruleVersion,
            String ruleContentHash, AuthorityDecision decision, UUID approvingActorId,
            String merchantNote, Instant decidedAt) {}

    public record PolicySnapshot(
            UUID policySnapshotId, UUID merchantId, int snapshotVersion, String snapshotHash,
            UUID publishedByActorId, Instant publishedAt, List<ProposedPolicyRule> rules) {}

    public record PolicyResolutionRequest(
            String action, Integer itemAgeDays, String itemCondition, Instant at) {}

    public record PolicyResolution(
            PolicyDecisionOutcome outcome, String reasonCode, UUID policySnapshotId,
            List<UUID> ruleIds, List<UUID> documentIds) {}

    public record ReadinessEvaluation(
            UUID readinessEvaluationId, UUID merchantId, UUID runId,
            ReadinessCapability capability, CapabilityReadiness readiness,
            UUID mappingProposalId, Integer mappingVersion, String mappingContentHash,
            UUID contractTestRunId, UUID policySnapshotId, JsonNode requiredEvidence,
            JsonNode satisfiedEvidence, JsonNode missingRequirements, JsonNode blockingEvidence,
            JsonNode evidenceReferences, String evaluationHash, Instant evaluatedAt) {}

    public record ManifestCapability(
            ReadinessCapability capability, boolean advertised, CapabilityReadiness readiness,
            UUID executableMappingProposalId, UUID readinessEvaluationId) {}

    public record AgentCommerceManifest(
            UUID manifestId, int schemaVersion, UUID merchantId, UUID runId, int manifestVersion,
            UUID policySnapshotId, String catalogueVersion, UUID publicationActorId,
            String publicationComponent, Instant publishedAt, String manifestHash,
            List<ManifestCapability> capabilities) {}
}
