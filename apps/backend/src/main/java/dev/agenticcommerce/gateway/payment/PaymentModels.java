package dev.agenticcommerce.gateway.payment;

import java.time.Instant;
import java.util.UUID;

public final class PaymentModels {
    private PaymentModels() {}

    public enum PaymentState {
        NOT_STARTED, ORDER_CREATED, PAYMENT_PENDING, PAYMENT_UNCERTAIN, PAYMENT_CONFIRMED, PAYMENT_FAILED
    }
    public enum EvidenceSource { CALLBACK, WEBHOOK, API_RECONCILIATION }
    public enum FulfillmentState {
        PENDING, IN_PROGRESS, FULFILLED, RETRYABLE_FAILURE, TERMINAL_FAILURE, COMPENSATION_REQUIRED
    }

    public record PaymentConfiguration(
            UUID id, UUID merchantId, String configurationReference,
            String providerAccountReference, boolean active) {}

    public record PaymentControl(
            UUID id, UUID executionId, UUID proposalId, UUID buyerActorId, UUID merchantId,
            UUID configurationId, PaymentState state, long expectedAmountMinor,
            String expectedCurrency, String expectedProviderOrderId, String confirmedPaymentId,
            int version, String reasonCode, Instant createdAt, Instant updatedAt, Instant confirmedAt) {}

    public record ProviderOrderRecord(
            UUID id, UUID paymentControlId, UUID executionId, UUID proposalId,
            String proposalHash, UUID merchantId, UUID configurationId, String providerOrderId,
            long amountMinor, String currency, String receipt, String providerStatus,
            Instant providerCreatedAt, String idempotencyReference, String responseHash, Instant createdAt) {}

    public record PaymentEvidence(
            UUID id, UUID paymentControlId, String providerPaymentId, String providerOrderId,
            String providerStatus, Long amountMinor, String currency, boolean captured,
            String accountReference, EvidenceSource source, String evidenceHash, Instant observedAt) {}

    public record OrderEvidence(
            UUID id, UUID paymentControlId, String providerOrderId, String providerStatus,
            Long amountMinor, Long amountPaidMinor, String currency, String accountReference,
            EvidenceSource source, String evidenceHash, Instant observedAt) {}

    public record CheckoutInitialization(
            String publicKeyId, String providerOrderId, long amountMinor, String currency,
            UUID executionId, UUID proposalId, String merchantDisplayName) {}

    public record CallbackSubmission(
            String razorpayPaymentId, String razorpayOrderId, String razorpaySignature) {}

    public record CallbackResult(boolean accepted, boolean financialConfirmation, String nextAction) {}

    public record PaymentStateView(
            UUID executionId, UUID proposalId, PaymentState paymentState, String reasonCode,
            String providerOrderId, String confirmedPaymentId, long amountMinor, String currency,
            FulfillmentState fulfillmentState, String merchantOrderId, int reconciliationAttempts,
            int reconciliationMaximumAttempts, Instant updatedAt) {}

    public record ReconciliationResult(
            PaymentStateView state, int attemptCount, int maximumAttempts, String reconciliationStatus) {}

    public record FulfillmentView(
            UUID executionId, PaymentState paymentState, FulfillmentState fulfillmentState,
            String merchantOperationId, String merchantOrderId, int attemptCount, String lastErrorCode) {}

    public record WebhookResult(boolean accepted, boolean duplicate, String eventId, String processingStatus) {}
}
