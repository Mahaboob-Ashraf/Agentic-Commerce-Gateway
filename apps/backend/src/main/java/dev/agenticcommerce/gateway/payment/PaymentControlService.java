package dev.agenticcommerce.gateway.payment;

import static dev.agenticcommerce.gateway.payment.PaymentModels.*;

import dev.agenticcommerce.gateway.agentization.service.CanonicalJsonService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class PaymentControlService {
    private final PaymentRepository repository;
    private final PaymentProvider provider;
    private final PaymentEvidenceReducer reducer;
    private final CanonicalJsonService canonical;
    private final ObjectMapper mapper;
    private final int maximumReconciliationAttempts;

    public PaymentControlService(
            PaymentRepository repository, PaymentProvider provider, PaymentEvidenceReducer reducer,
            CanonicalJsonService canonical, ObjectMapper mapper,
            @Value("${payment.reconciliation.maximum-attempts:5}") int maximumReconciliationAttempts) {
        this.repository = repository;
        this.provider = provider;
        this.reducer = reducer;
        this.canonical = canonical;
        this.mapper = mapper;
        this.maximumReconciliationAttempts = maximumReconciliationAttempts;
    }

    @Transactional
    public PaymentStateView initiate(UUID buyerId, UUID threadId, UUID proposalId) {
        Instant now = Instant.now();
        PaymentRepository.StartContext context = requireContext(buyerId, threadId, proposalId);
        requireConfiguration(context);
        if (!"RESERVED".equals(context.executionStatus()) && !"PAYMENT_PENDING".equals(context.executionStatus()))
            throw conflict("EXECUTION_NOT_PAYMENT_ELIGIBLE", "Execution is not eligible for payment initiation");
        PaymentControl control = repository.controlForExecution(context.executionId())
                .orElseGet(() -> repository.createControl(context, now));
        if (control.state() == PaymentState.PAYMENT_FAILED)
            return requireState(buyerId, threadId, proposalId);
        ProviderOrderRecord existing = repository.orderForExecution(context.executionId()).orElse(null);
        if (existing != null) return requireState(buyerId, threadId, proposalId);

        String receipt = stableReceipt(context.executionId());
        if (repository.hasUncertainOrderAttempt(context.executionId())) {
            java.util.Optional<PaymentProvider.ProviderOrder> recovered;
            try {
                recovered = provider.findOrderByReceipt(receipt);
            } catch (PaymentProviderException failure) {
                repository.markUncertain(control, "ORDER_RECOVERY_" + failure.category().name(), null,
                        maximumReconciliationAttempts, now);
                return requireState(buyerId, threadId, proposalId);
            }
            if (recovered.isEmpty()) {
                repository.markUncertain(control, "ORDER_CREATION_OUTCOME_UNCERTAIN", null,
                        maximumReconciliationAttempts, now);
                return requireState(buyerId, threadId, proposalId);
            }
            validateOrder(context, recovered.orElseThrow(), receipt);
            repository.saveProviderOrder(control, context.proposalHash(), recovered.orElseThrow(),
                    idempotency(context.executionId()), now);
            return requireState(buyerId, threadId, proposalId);
        }

        var material = mapper.createObjectNode();
        material.put("executionId", context.executionId().toString());
        material.put("amountMinor", context.amountMinor());
        material.put("currency", context.currency());
        material.put("receipt", receipt);
        String requestHash = canonical.hash(material);
        UUID attempt = repository.beginOrderAttempt(control, receipt, requestHash, now);
        try {
            PaymentProvider.ProviderOrder order = provider.createOrder(new PaymentProvider.CreateOrderCommand(
                    context.amountMinor(), context.currency(), receipt, idempotency(context.executionId())));
            validateOrder(context, order, receipt);
            repository.completeOrderAttempt(attempt, "CREATED", null, order.id(), order.evidenceHash(), now);
            repository.saveProviderOrder(control, context.proposalHash(), order,
                    idempotency(context.executionId()), now);
        } catch (PaymentProviderException failure) {
            if (failure.uncertain() && failure.requestMayHaveReachedProvider()) {
                repository.completeOrderAttempt(attempt, "UNCERTAIN", failure.category().name(), null, null, now);
                repository.markUncertain(control, "ORDER_CREATION_" + failure.category().name(), null,
                        maximumReconciliationAttempts, now);
            } else {
                repository.completeOrderAttempt(attempt, "DEFINITIVE_FAILURE", failure.category().name(), null, null, now);
                repository.markDefinitiveFailure(control, "ORDER_CREATION_" + failure.category().name(), now);
            }
        }
        return requireState(buyerId, threadId, proposalId);
    }

    public CheckoutInitialization checkout(UUID buyerId, UUID threadId, UUID proposalId) {
        PaymentRepository.StartContext context = requireContext(buyerId, threadId, proposalId);
        requireConfiguration(context);
        ProviderOrderRecord order = repository.orderForExecution(context.executionId())
                .orElseThrow(() -> conflict("PROVIDER_ORDER_NOT_CREATED", "Payment order has not been created"));
        if (!provider.configured()) throw unavailable("PAYMENT_PROVIDER_NOT_CONFIGURED",
                "Razorpay Test Mode is not configured");
        return new CheckoutInitialization(provider.publicKeyId(), order.providerOrderId(),
                context.amountMinor(), context.currency(), context.executionId(), context.proposalId(),
                context.merchantDisplayName());
    }

    @Transactional
    public CallbackResult callback(
            UUID buyerId, UUID threadId, UUID proposalId, CallbackSubmission submission) {
        validateCallbackFields(submission);
        PaymentRepository.StartContext context = requireContext(buyerId, threadId, proposalId);
        requireConfiguration(context);
        PaymentControl control = repository.controlForExecution(context.executionId())
                .orElseThrow(() -> conflict("PAYMENT_NOT_STARTED", "Payment has not been initiated"));
        ProviderOrderRecord order = repository.orderForExecution(context.executionId())
                .orElseThrow(() -> conflict("PROVIDER_ORDER_NOT_CREATED", "Payment order has not been created"));
        if (!order.providerOrderId().equals(submission.razorpayOrderId()))
            throw bad("CALLBACK_ORDER_MISMATCH", "Callback does not belong to the expected provider order");
        if (!provider.verifyCheckoutSignature(
                order.providerOrderId(), submission.razorpayPaymentId(), submission.razorpaySignature()))
            throw bad("CALLBACK_SIGNATURE_INVALID", "Razorpay callback signature is invalid");
        Instant now = Instant.now();
        var evidence = mapper.createObjectNode();
        evidence.put("controlId", control.id().toString());
        evidence.put("orderId", submission.razorpayOrderId());
        evidence.put("paymentId", submission.razorpayPaymentId());
        repository.saveCallback(control, submission.razorpayOrderId(), submission.razorpayPaymentId(),
                canonical.hashText(submission.razorpaySignature()), canonical.hash(evidence), now);
        repository.markUncertain(control, "VALID_CALLBACK_AWAITING_PROVIDER_TRUTH",
                submission.razorpayPaymentId(), maximumReconciliationAttempts, now);
        return new CallbackResult(true, false, "RECONCILE_PROVIDER_EVIDENCE");
    }

    public PaymentStateView state(UUID buyerId, UUID threadId, UUID proposalId) {
        return requireState(buyerId, threadId, proposalId);
    }

    public FulfillmentView fulfillment(UUID buyerId, UUID threadId, UUID proposalId) {
        return repository.fulfillment(buyerId, threadId, proposalId)
                .orElseThrow(() -> new PaymentControlException("FULFILLMENT_NOT_FOUND", HttpStatus.NOT_FOUND,
                        "Fulfillment state was not found"));
    }

    @Transactional
    public ReconciliationResult reconcile(UUID buyerId, UUID threadId, UUID proposalId) {
        PaymentRepository.StartContext context = requireContext(buyerId, threadId, proposalId);
        requireConfiguration(context);
        PaymentControl control = repository.controlForExecution(context.executionId())
                .orElseThrow(() -> conflict("PAYMENT_NOT_STARTED", "Payment has not been initiated"));
        if (control.state() == PaymentState.PAYMENT_CONFIRMED)
            return new ReconciliationResult(requireState(buyerId, threadId, proposalId), 0,
                    maximumReconciliationAttempts, "COMPLETED");

        var work = repository.beginReconciliation(control, null, maximumReconciliationAttempts, Instant.now())
                .orElseThrow(() -> conflict("RECONCILIATION_BUDGET_EXHAUSTED",
                        "Payment requires manual review after bounded reconciliation attempts"));
        try {
            ProviderOrderRecord orderRecord = repository.orderForExecution(context.executionId()).orElse(null);
            if (orderRecord == null) {
                var recovered = provider.findOrderByReceipt(stableReceipt(context.executionId()));
                if (recovered.isPresent()) {
                    validateOrder(context, recovered.orElseThrow(), stableReceipt(context.executionId()));
                    orderRecord = repository.saveProviderOrder(control, context.proposalHash(), recovered.orElseThrow(),
                            idempotency(context.executionId()), Instant.now());
                }
            }
            if (orderRecord == null)
                throw new PaymentProviderException(PaymentProviderException.Category.UNKNOWN_OUTCOME,
                        false, "Provider order identity is not yet recoverable", null);
            PaymentProvider.ProviderOrder order = provider.fetchOrder(orderRecord.providerOrderId());
            validateOrder(context, order, orderRecord.receipt());
            repository.saveOrderEvidence(control, order, EvidenceSource.API_RECONCILIATION,
                    "attempt-" + work.attemptCount(), Instant.now());
            if (work.providerPaymentId() != null) {
                PaymentProvider.ProviderPayment payment = provider.fetchPayment(work.providerPaymentId());
                validatePayment(control, context.configuration(), payment);
                repository.savePaymentEvidence(control, payment, EvidenceSource.API_RECONCILIATION,
                        "attempt-" + work.attemptCount(), Instant.now());
            }
            PaymentState reduced = reducer.reduce(control.id());
            boolean completed = reduced == PaymentState.PAYMENT_CONFIRMED || reduced == PaymentState.PAYMENT_FAILED;
            repository.completeReconciliation(control, completed, completed ? null : "EVIDENCE_INCOMPLETE", Instant.now());
        } catch (PaymentProviderException failure) {
            repository.markUncertain(control, "RECONCILIATION_" + failure.category().name(),
                    work.providerPaymentId(), maximumReconciliationAttempts, Instant.now());
            repository.completeReconciliation(control, false, failure.category().name(), Instant.now());
        }
        PaymentStateView state = requireState(buyerId, threadId, proposalId);
        String status = state.paymentState() == PaymentState.PAYMENT_CONFIRMED ? "COMPLETED"
                : state.reconciliationAttempts() >= state.reconciliationMaximumAttempts() ? "MANUAL_REVIEW" : "PENDING";
        return new ReconciliationResult(state, state.reconciliationAttempts(),
                state.reconciliationMaximumAttempts(), status);
    }

    private PaymentRepository.StartContext requireContext(UUID buyerId, UUID threadId, UUID proposalId) {
        return repository.lockStartContext(buyerId, threadId, proposalId)
                .orElseThrow(() -> new PaymentControlException("EXECUTION_NOT_FOUND", HttpStatus.NOT_FOUND,
                        "Authorized execution was not found in this buyer thread"));
    }
    private PaymentStateView requireState(UUID buyerId, UUID threadId, UUID proposalId) {
        return repository.state(buyerId, threadId, proposalId)
                .orElseThrow(() -> new PaymentControlException("PAYMENT_NOT_STARTED", HttpStatus.NOT_FOUND,
                        "Payment state was not found"));
    }
    private void requireConfiguration(PaymentRepository.StartContext context) {
        PaymentConfiguration configuration = context.configuration();
        if (configuration == null || !configuration.active())
            throw unavailable("MERCHANT_PAYMENT_CONFIGURATION_MISSING",
                    "Merchant Razorpay Test configuration is unavailable");
        if (!configuration.configurationReference().equals(provider.configurationReference())
                || !configuration.providerAccountReference().equals(provider.providerAccountReference()))
            throw conflict("PAYMENT_CONFIGURATION_MISMATCH",
                    "Execution merchant is not bound to the active Razorpay configuration");
    }
    private void validateOrder(
            PaymentRepository.StartContext context, PaymentProvider.ProviderOrder order, String receipt) {
        if (order == null || order.id() == null || order.id().isBlank()
                || order.amountMinor() != context.amountMinor() || !context.currency().equals(order.currency())
                || !receipt.equals(order.receipt())
                || !context.configuration().providerAccountReference().equals(order.accountReference()))
            throw new PaymentProviderException(PaymentProviderException.Category.MALFORMED_RESPONSE,
                    false, "Provider order did not match immutable execution authority", null);
    }
    private static void validatePayment(
            PaymentControl control, PaymentConfiguration configuration,
            PaymentProvider.ProviderPayment payment) {
        if (payment == null || !control.expectedProviderOrderId().equals(payment.orderId())
                || payment.amountMinor() != control.expectedAmountMinor()
                || !control.expectedCurrency().equals(payment.currency())
                || !configuration.providerAccountReference().equals(payment.accountReference()))
            throw new PaymentProviderException(PaymentProviderException.Category.MALFORMED_RESPONSE,
                    false, "Provider payment did not match immutable execution authority", null);
    }
    private static void validateCallbackFields(CallbackSubmission value) {
        if (value == null || invalidId(value.razorpayPaymentId()) || invalidId(value.razorpayOrderId())
                || value.razorpaySignature() == null || !value.razorpaySignature().matches("[0-9a-fA-F]{64}"))
            throw bad("CALLBACK_MATERIAL_INVALID", "Razorpay callback material is invalid");
    }
    private static boolean invalidId(String value) {
        return value == null || !value.matches("[A-Za-z0-9_-]{1,128}");
    }
    public static String stableReceipt(UUID executionId) {
        return "acg_" + executionId.toString().replace("-", "");
    }
    private static String idempotency(UUID executionId) { return "execution:" + executionId; }
    private static PaymentControlException bad(String code, String message) {
        return new PaymentControlException(code, HttpStatus.BAD_REQUEST, message);
    }
    private static PaymentControlException conflict(String code, String message) {
        return new PaymentControlException(code, HttpStatus.CONFLICT, message);
    }
    private static PaymentControlException unavailable(String code, String message) {
        return new PaymentControlException(code, HttpStatus.SERVICE_UNAVAILABLE, message);
    }
}
