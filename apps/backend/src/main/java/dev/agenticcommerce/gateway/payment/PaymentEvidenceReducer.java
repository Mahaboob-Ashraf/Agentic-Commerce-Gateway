package dev.agenticcommerce.gateway.payment;

import static dev.agenticcommerce.gateway.payment.PaymentModels.*;

import dev.agenticcommerce.gateway.agentization.service.CanonicalJsonService;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Sole deterministic reducer from immutable provider evidence to internal payment truth. */
@Service
public class PaymentEvidenceReducer {
    private final PaymentRepository repository;
    private final CanonicalJsonService canonical;
    private final ObjectMapper mapper;

    public PaymentEvidenceReducer(
            PaymentRepository repository, CanonicalJsonService canonical, ObjectMapper mapper) {
        this.repository = repository;
        this.canonical = canonical;
        this.mapper = mapper;
    }

    @Transactional
    public PaymentState reduce(java.util.UUID controlId) {
        PaymentControl control = repository.lockControl(controlId).orElseThrow();
        if (control.state() == PaymentState.PAYMENT_CONFIRMED) return control.state();
        PaymentEvidence payment = repository.latestPaymentEvidence(controlId).orElse(null);
        OrderEvidence order = repository.latestOrderEvidence(controlId).orElse(null);
        PaymentState state = PaymentState.PAYMENT_UNCERTAIN;
        String reason = "AUTHORITATIVE_EVIDENCE_INCOMPLETE";
        PaymentConfiguration configuration = repository.configuration(control.configurationId()).orElseThrow();
        if (payment != null && order != null && identitiesMatch(control, configuration, payment, order)
                && "captured".equalsIgnoreCase(payment.providerStatus()) && payment.captured()
                && "paid".equalsIgnoreCase(order.providerStatus())) {
            state = PaymentState.PAYMENT_CONFIRMED;
            reason = "CAPTURED_PAYMENT_AND_PAID_ORDER_VERIFIED";
        } else if (payment != null && identitiesMatchPayment(control, payment)
                && "failed".equalsIgnoreCase(payment.providerStatus())) {
            state = PaymentState.PAYMENT_FAILED;
            reason = "PROVIDER_PAYMENT_DEFINITIVELY_FAILED";
        } else if ((payment != null && !identitiesMatchPayment(control, payment))
                || (order != null && !identitiesMatchOrder(control, order))) {
            reason = "PROVIDER_EVIDENCE_IDENTITY_MISMATCH";
        }
        var material = mapper.createObjectNode();
        material.put("controlId", control.id().toString());
        material.put("previousState", control.state().name());
        material.put("reducedState", state.name());
        material.put("reason", reason);
        if (payment != null) material.put("paymentEvidenceHash", payment.evidenceHash());
        if (order != null) material.put("orderEvidenceHash", order.evidenceHash());
        repository.saveReductionAndState(control, state, reason, payment, order, canonical.hash(material), Instant.now());
        return state;
    }

    private static boolean identitiesMatch(
            PaymentControl control, PaymentConfiguration configuration,
            PaymentEvidence payment, OrderEvidence order) {
        return identitiesMatchPayment(control, payment) && identitiesMatchOrder(control, order)
                && payment.providerOrderId().equals(order.providerOrderId())
                && payment.amountMinor() != null && order.amountMinor() != null && order.amountPaidMinor() != null
                && payment.amountMinor() == control.expectedAmountMinor()
                && order.amountMinor() == control.expectedAmountMinor()
                && order.amountPaidMinor() == control.expectedAmountMinor()
                && control.expectedCurrency().equals(payment.currency())
                && control.expectedCurrency().equals(order.currency())
                && payment.accountReference() != null && order.accountReference() != null
                && payment.accountReference().equals(order.accountReference())
                && configuration.providerAccountReference().equals(payment.accountReference());
    }

    private static boolean identitiesMatchPayment(PaymentControl control, PaymentEvidence payment) {
        return control.expectedProviderOrderId() != null
                && control.expectedProviderOrderId().equals(payment.providerOrderId());
    }
    private static boolean identitiesMatchOrder(PaymentControl control, OrderEvidence order) {
        return control.expectedProviderOrderId() != null
                && control.expectedProviderOrderId().equals(order.providerOrderId());
    }
}
