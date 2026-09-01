package dev.agenticcommerce.gateway.payment;

import static dev.agenticcommerce.gateway.payment.PaymentModels.*;

import dev.agenticcommerce.gateway.agentization.service.CanonicalJsonService;
import dev.agenticcommerce.gateway.lifecycle.RefundService;
import dev.agenticcommerce.gateway.lifecycle.LifecycleException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class RazorpayWebhookService {
    public static final int MAX_WEBHOOK_BYTES = 256 * 1024;
    private final PaymentProvider provider;
    private final PaymentRepository repository;
    private final PaymentEvidenceReducer reducer;
    private final CanonicalJsonService canonical;
    private final ObjectMapper mapper;
    private final RefundService refunds;

    public RazorpayWebhookService(
            PaymentProvider provider, PaymentRepository repository, PaymentEvidenceReducer reducer,
            CanonicalJsonService canonical, ObjectMapper mapper, RefundService refunds) {
        this.provider = provider;
        this.repository = repository;
        this.reducer = reducer;
        this.canonical = canonical;
        this.mapper = mapper;
        this.refunds = refunds;
    }

    @Transactional
    public WebhookResult ingest(byte[] rawBody, String signature, String suppliedEventId) {
        if (rawBody == null || rawBody.length < 2 || rawBody.length > MAX_WEBHOOK_BYTES)
            throw bad("WEBHOOK_BODY_INVALID", "Webhook body is missing or exceeds the allowed size");
        if (!provider.verifyWebhookSignature(rawBody, signature))
            throw bad("WEBHOOK_SIGNATURE_INVALID", "Razorpay webhook signature is invalid");

        PaymentConfiguration configuration = repository.configurationByReference(provider.configurationReference())
                .orElseThrow(() -> new PaymentControlException("PAYMENT_CONFIGURATION_MISSING",
                        HttpStatus.SERVICE_UNAVAILABLE, "Active merchant payment configuration was not found"));
        JsonNode root;
        try { root = mapper.readTree(rawBody); }
        catch (RuntimeException malformed) { throw bad("WEBHOOK_JSON_INVALID", "Webhook JSON is invalid"); }
        String eventType = text(root, "event");
        if (eventType == null) throw bad("WEBHOOK_EVENT_TYPE_MISSING", "Webhook event type is missing");
        String bodyHash = hashBytes(rawBody);
        String eventId = validId(suppliedEventId) ? suppliedEventId
                : validId(text(root, "id")) ? text(root, "id") : "hash_" + bodyHash;
        String signatureHash = canonical.hashText(signature);
        Instant now = Instant.now();
        if (!repository.insertWebhook(configuration, eventId, eventType, signatureHash, bodyHash, rawBody, now))
            return new WebhookResult(true, true, eventId, "ALREADY_PROCESSED");

        JsonNode refundNode = root.path("payload").path("refund").path("entity");
        if (hasObject(refundNode)) {
            String account = text(root,"account_id");
            if(account!=null&&!configuration.providerAccountReference().equals(account)){
                repository.completeWebhook(configuration.id(),eventId,"PROVIDER_ACCOUNT_MISMATCH",now);
                return new WebhookResult(true,false,eventId,"REJECTED_ACCOUNT_MISMATCH");
            }
            long amount=refundNode.path("amount").asLong(-1);
            try {
                boolean accepted=amount>=0&&refunds.ingestWebhook(new RefundService.JsonNodeRefund(
                        required(refundNode,"id"),required(refundNode,"payment_id"),amount,
                        required(refundNode,"currency"),required(refundNode,"status"),providerInstant(refundNode),
                        configuration.providerAccountReference(),canonical.hash(refundNode)),eventId);
                repository.completeWebhook(configuration.id(),eventId,accepted?null:"EXPECTED_REFUND_NOT_FOUND",now);
                return new WebhookResult(true,false,eventId,accepted?"PROCESSED":"REJECTED_UNBOUND_EVENT");
            } catch (IllegalArgumentException invalid) {
                repository.completeWebhook(configuration.id(),eventId,"NORMALIZATION_FAILED",now);
                return new WebhookResult(true,false,eventId,"REJECTED_NORMALIZATION_FAILED");
            } catch (LifecycleException mismatch) {
                repository.completeWebhook(configuration.id(),eventId,mismatch.code(),now);
                return new WebhookResult(true,false,eventId,"REJECTED_REFUND_EVIDENCE_MISMATCH");
            }
        }

        JsonNode paymentNode = root.path("payload").path("payment").path("entity");
        JsonNode orderNode = root.path("payload").path("order").path("entity");
        String orderId = hasObject(paymentNode) ? text(paymentNode, "order_id") : text(orderNode, "id");
        PaymentControl control = orderId == null ? null : repository.controlByProviderOrder(orderId).orElse(null);
        if (control == null || !control.configurationId().equals(configuration.id())
                || !control.merchantId().equals(configuration.merchantId())) {
            repository.completeWebhook(configuration.id(), eventId, "EXPECTED_ORDER_NOT_FOUND", now);
            return new WebhookResult(true, false, eventId, "REJECTED_UNBOUND_EVENT");
        }
        String account = text(root, "account_id");
        if (account != null && !configuration.providerAccountReference().equals(account)) {
            repository.completeWebhook(configuration.id(), eventId, "PROVIDER_ACCOUNT_MISMATCH", now);
            return new WebhookResult(true, false, eventId, "REJECTED_ACCOUNT_MISMATCH");
        }
        PaymentProvider.ProviderPayment payment = null;
        PaymentProvider.ProviderOrder order = null;
        try {
            if (hasObject(paymentNode)) payment = normalizePayment(paymentNode, configuration);
            if (hasObject(orderNode)) order = normalizeOrder(orderNode, control, configuration);
        } catch (IllegalArgumentException invalid) {
            repository.completeWebhook(configuration.id(), eventId, "NORMALIZATION_FAILED", now);
            return new WebhookResult(true, false, eventId, "REJECTED_NORMALIZATION_FAILED");
        }
        if (payment != null)
            repository.savePaymentEvidence(control, payment, EvidenceSource.WEBHOOK, eventId, now);
        if (order != null)
            repository.saveOrderEvidence(control, order, EvidenceSource.WEBHOOK, eventId, now);
        reducer.reduce(control.id());
        repository.completeWebhook(configuration.id(), eventId, null, now);
        return new WebhookResult(true, false, eventId, "PROCESSED");
    }

    private PaymentProvider.ProviderPayment normalizePayment(
            JsonNode value, PaymentConfiguration configuration) {
        long amount = value.path("amount").asLong(-1);
        if (amount < 0) throw new IllegalArgumentException("payment amount is invalid");
        return new PaymentProvider.ProviderPayment(required(value, "id"), required(value, "order_id"),
                amount, required(value, "currency"), required(value, "status"),
                value.path("captured").asBoolean(false), providerInstant(value),
                configuration.providerAccountReference(), canonical.hash(value));
    }

    private PaymentProvider.ProviderOrder normalizeOrder(
            JsonNode value, PaymentControl control, PaymentConfiguration configuration) {
        long amount = value.path("amount").asLong(-1);
        long amountPaid = value.path("amount_paid").asLong(-1);
        if (amount < 0 || amountPaid < 0) throw new IllegalArgumentException("order amounts are invalid");
        return new PaymentProvider.ProviderOrder(required(value, "id"), amount, amountPaid,
                required(value, "currency"), PaymentControlService.stableReceipt(control.executionId()),
                required(value, "status"), providerInstant(value),
                configuration.providerAccountReference(), canonical.hash(value));
    }

    private static Instant providerInstant(JsonNode entity) {
        long epoch = entity.path("created_at").asLong(0);
        return epoch > 0 ? Instant.ofEpochSecond(epoch) : Instant.now();
    }
    private static boolean hasObject(JsonNode value) { return value != null && value.isObject() && !value.isEmpty(); }
    private static String required(JsonNode value, String field) {
        String text = text(value, field);
        if (text == null) throw new IllegalArgumentException(field + " is required");
        return text;
    }
    private static String text(JsonNode value, String field) {
        if (value == null) return null;
        String result = value.path(field).asText("").strip();
        return result.isEmpty() ? null : result;
    }
    private static boolean validId(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,128}");
    }
    private static PaymentControlException bad(String code, String message) {
        return new PaymentControlException(code, HttpStatus.BAD_REQUEST, message);
    }
    private static String hashBytes(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JVM", impossible);
        }
    }
}
