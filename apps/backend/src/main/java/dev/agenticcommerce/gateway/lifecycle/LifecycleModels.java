package dev.agenticcommerce.gateway.lifecycle;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public final class LifecycleModels {
    private LifecycleModels() {}
    public enum Action { TRACK_ORDER, CANCEL_ORDER, RETURN_ORDER, REFUND_ORDER, REORDER, REPLACE_ITEM }
    public enum Resolution { RESOLVED, CLARIFICATION_REQUIRED, UNSUPPORTED }
    public enum PolicyOutcome { PASS, FAIL, UNKNOWN }
    public enum RefundState { REFUND_PROPOSED, REFUND_INITIATED, REFUND_PENDING, REFUNDED, REFUND_FAILED, MANUAL_REVIEW }
    public record Intent(UUID id, UUID buyerId, UUID threadId, UUID finalizationId, Action action,
            String targetScope, Resolution resolution, String sourceTextHash, JsonNode evidence,
            Double confidence, Instant createdAt) {}
    public record Proposal(UUID id, UUID intentId, UUID policyEvaluationId, UUID buyerId, UUID threadId,
            UUID merchantId, UUID finalizationId, UUID originalProposalId, UUID originalExecutionId,
            UUID paymentControlId, Action action, Long refundableAmountMinor, String currency,
            UUID policySnapshotId, int policySnapshotVersion, UUID merchantLinkId, int merchantLinkVersion,
            JsonNode material, String hash, Instant createdAt, Instant expiresAt) {}
    public record Authorization(UUID id, UUID buyerId, String sessionBindingHash, UUID proposalId,
            String proposalHash, Action action, String decision, String authorizationHash,
            Instant issuedAt, Instant expiresAt, Instant consumedAt) {}
    public record Execution(UUID id, UUID proposalId, UUID authorizationId, UUID buyerId, UUID merchantId,
            UUID finalizationId, Action action, String merchantOperationId, String state,
            String responseReference, Instant createdAt, Instant updatedAt) {}
    public record RefundExecution(UUID id, UUID lifecycleProposalId, String lifecycleProposalHash,
            UUID lifecycleExecutionId, UUID paymentControlId, UUID buyerId, UUID merchantId,
            String providerPaymentId, long amountMinor, String currency, String idempotencyKey,
            JsonNode requestBody, String requestHash, RefundState state, String providerRefundId,
            int attemptCount, Instant nextAttemptAt, Instant deadlineAt, String lastErrorCode,
            Instant createdAt, Instant updatedAt) {}
    public record LifecycleRequest(String text) {}
    public record LifecycleState(Intent intent, Proposal proposal, Authorization authorization,
            Execution execution, RefundExecution refund, String merchantOrderStatus) {}
    public record OrderTracking(UUID merchantFinalizationId, UUID merchantId, String merchantOrderId,
            String status, String externalCustomerReference) {}
}
