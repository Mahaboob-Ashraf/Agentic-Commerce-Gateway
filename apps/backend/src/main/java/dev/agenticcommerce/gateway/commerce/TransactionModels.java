package dev.agenticcommerce.gateway.commerce;

import dev.agenticcommerce.gateway.intent.BuyerModels.ConstraintCertificate;
import dev.agenticcommerce.gateway.intent.BuyerModels.MerchantQuote;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public final class TransactionModels {
    private TransactionModels() {}

    public enum EvidenceOutcome { PASS, FAIL, UNKNOWN }
    public enum ActionType { PURCHASE }
    public enum ReversibilityOutcome { AUTO_EXECUTE, CLARIFY, EXPLICIT_CONFIRMATION, BLOCK }
    public enum AuthorizationDecisionType { AUTHORIZED, DENIED }
    public enum AuthorizationMethod { EXPLICIT_CONFIRMATION, AUTO_EXECUTE_POLICY, BUYER_DENIAL }
    public enum ExecutionStatus { RESERVED, PAYMENT_PENDING, FAILED }
    public enum GateDecision { ALLOW, DENY }
    public enum ServiceabilitySource { MERCHANT_API, TRUSTED_DEMO_FIXTURE, UNRESOLVED }

    public record CapabilityBinding(
            String capability, boolean advertised, String readiness,
            UUID executableMappingProposalId, UUID readinessEvaluationId) {
        public boolean ready() {
            return advertised && "READY".equals(readiness) && executableMappingProposalId != null;
        }
    }

    public record MerchantAuthorityContext(
            UUID merchantId, UUID manifestId, int manifestVersion, String manifestHash,
            UUID policySnapshotId, Integer policySnapshotVersion, String policySnapshotHash,
            String catalogueVersion, CapabilityBinding quoteCapability,
            CapabilityBinding availabilityCapability, String purchaseReadiness) {}

    public record AvailabilityItemEvidence(
            UUID availabilityItemId, UUID productId, UUID catalogueVersionId,
            String merchantSku, String variant, int requestedQuantity,
            Boolean available, Long authoritativeQuantity, EvidenceOutcome outcome,
            String reasonCode, Instant merchantObservedAt, Instant merchantExpiresAt,
            String responseHash) {}

    public record AvailabilityRefresh(
            UUID availabilityRefreshId, UUID threadId, UUID buyerActorId, UUID merchantId,
            UUID cartId, int cartVersion, String cartHash, UUID manifestId, int manifestVersion,
            UUID readinessEvaluationId, UUID executableMappingProposalId,
            EvidenceOutcome outcome, String reasonCode, Instant observedAt, Instant expiresAt,
            String evidenceHash, Instant createdAt, List<AvailabilityItemEvidence> items) {}

    public record ServiceabilityEvidence(
            UUID serviceabilityEvidenceId, UUID threadId, UUID buyerActorId, UUID merchantId,
            UUID cartId, int cartVersion, String cartHash, UUID manifestId, int manifestVersion,
            EvidenceOutcome outcome, ServiceabilitySource sourceType, String sourceReference,
            String locationReferenceHash, String reasonCode, Instant observedAt, Instant expiresAt,
            String evidenceHash, Instant createdAt) {}

    public record AuthorityRefresh(
            UUID authorityRefreshId, UUID threadId, UUID buyerActorId, UUID merchantId,
            UUID cartId, int cartVersion, String cartHash, MerchantQuote quote,
            AvailabilityRefresh availability, ServiceabilityEvidence serviceability,
            ConstraintCertificate constraintCertificate, UUID manifestId, int manifestVersion,
            UUID policySnapshotId, int policySnapshotVersion, String policySnapshotHash,
            EvidenceOutcome outcome, List<String> evidenceReferences, String refreshHash,
            Instant refreshedAt, Instant createdAt) {}

    public record ProposalLineItem(
            UUID proposalLineItemId, int lineNumber, UUID productId,
            String merchantSku, String variant, int quantity,
            long unitAmountMinor, long lineAmountMinor) {}

    public record ProposalDraft(
            UUID buyerActorId, UUID threadId, UUID merchantId,
            UUID authorityRefreshId, String authorityRefreshHash,
            UUID intentId, int intentVersion, String intentHash,
            UUID cartId, int cartVersion, String cartHash,
            UUID constraintCertificateId, String constraintCertificateHash,
            UUID quoteRecordId, String quoteHash, String merchantQuoteId,
            String merchantQuoteVersion, UUID availabilityRefreshId, String availabilityHash,
            UUID serviceabilityEvidenceId, String serviceabilityHash,
            UUID policySnapshotId, int policySnapshotVersion, String policySnapshotHash,
            UUID catalogueVersionId, ActionType actionType,
            long subtotalMinor, long taxMinor, long feesMinor, long deliveryMinor,
            long finalAmountMinor, String currency, Instant quoteExpiresAt,
            Instant proposalExpiresAt, List<ProposalLineItem> lineItems) {}

    public record TransactionProposal(
            UUID proposalId, UUID buyerActorId, UUID threadId, UUID merchantId,
            UUID authorityRefreshId, String authorityRefreshHash,
            UUID intentId, int intentVersion, String intentHash,
            UUID cartId, int cartVersion, String cartHash,
            UUID constraintCertificateId, String constraintCertificateHash,
            UUID quoteRecordId, String quoteHash, String merchantQuoteId,
            String merchantQuoteVersion, UUID availabilityRefreshId, String availabilityHash,
            UUID serviceabilityEvidenceId, String serviceabilityHash,
            UUID policySnapshotId, int policySnapshotVersion, String policySnapshotHash,
            UUID catalogueVersionId, ActionType actionType,
            long subtotalMinor, long taxMinor, long feesMinor, long deliveryMinor,
            long finalAmountMinor, String currency, Instant quoteExpiresAt,
            Instant proposalExpiresAt, int canonicalSchemaVersion, JsonNode canonicalMaterial,
            String proposalHash, Instant createdAt, List<ProposalLineItem> lineItems) {}

    public record RiskInput(
            ActionType actionType, long finalAmountMinor, boolean proposalValid,
            boolean proposalExpired, EvidenceOutcome hardConstraints,
            boolean safetyCriticalUnknown, EvidenceOutcome exactIdentity,
            EvidenceOutcome availability, EvidenceOutcome serviceability,
            boolean merchantCapabilitiesReady, boolean policyCoverageCurrent,
            boolean unresolvedMaterialAmbiguity, boolean substitutionDecisionRequired,
            boolean reversible) {}

    public record ReversibilityEvaluation(
            UUID reversibilityEvaluationId, UUID proposalId, UUID buyerActorId, UUID threadId,
            UUID merchantId, String proposalHash, ActionType actionType, String policyVersion,
            ReversibilityOutcome outcome, List<String> reasonCodes, JsonNode normalizedInputs,
            String inputHash, boolean additionalConfirmationRequired,
            boolean paymentAuthorizationStillRequired, Instant evaluatedAt) {}

    public record AuthorizationDecision(
            UUID authorizationId, UUID buyerActorId, String sessionBindingHash,
            UUID proposalId, String proposalHash, ActionType actionType,
            AuthorizationDecisionType decision, AuthorizationMethod authorizationMethod,
            Instant issuedAt, Instant expiresAt, JsonNode authorizationMaterial,
            String authorizationHash, Instant consumedAt, UUID consumedByExecutionId) {}

    public record TransactionExecution(
            UUID executionId, UUID proposalId, String proposalHash, UUID buyerActorId,
            UUID merchantId, ActionType actionType, UUID authorizationId,
            AuthorizationDecisionType authorizationDecision, ExecutionStatus status,
            String idempotencyKey, String providerOrderReference,
            Instant createdAt, Instant updatedAt) {}

    public record ExecutionGateResult(
            GateDecision decision, String reasonCode, TransactionExecution execution,
            boolean duplicateResolution, List<String> evidenceReferences) {}
}
