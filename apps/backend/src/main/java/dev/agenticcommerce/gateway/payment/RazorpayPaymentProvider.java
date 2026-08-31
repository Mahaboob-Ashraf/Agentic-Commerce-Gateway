package dev.agenticcommerce.gateway.payment;

import static dev.agenticcommerce.gateway.payment.PaymentProviderException.Category.*;

import dev.agenticcommerce.gateway.agentization.service.CanonicalJsonService;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Direct, bounded Razorpay Payments Test Mode REST adapter. */
@Service
public class RazorpayPaymentProvider implements PaymentProvider {
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private final ObjectMapper mapper;
    private final CanonicalJsonService canonical;
    private final HttpClient client;
    private final String keyId;
    private final String keySecret;
    private final String webhookSecret;
    private final String apiBase;
    private final String configurationReference;
    private final String accountReference;
    private final Duration requestTimeout;

    public RazorpayPaymentProvider(
            ObjectMapper mapper, CanonicalJsonService canonical,
            @Value("${payment.razorpay.key-id:}") String keyId,
            @Value("${payment.razorpay.key-secret:}") String keySecret,
            @Value("${payment.razorpay.webhook-secret:}") String webhookSecret,
            @Value("${payment.razorpay.api-base:https://api.razorpay.com}") String apiBase,
            @Value("${payment.razorpay.configuration-reference:razorpay-test-default}") String configurationReference,
            @Value("${payment.razorpay.account-reference:default-test-account}") String accountReference,
            @Value("${payment.razorpay.connect-timeout:PT3S}") Duration connectTimeout,
            @Value("${payment.razorpay.request-timeout:PT8S}") Duration requestTimeout) {
        this.mapper = mapper;
        this.canonical = canonical;
        this.keyId = keyId.strip();
        this.keySecret = keySecret.strip();
        this.webhookSecret = webhookSecret.strip();
        this.apiBase = apiBase.replaceAll("/+$", "");
        this.configurationReference = configurationReference.strip();
        this.accountReference = accountReference.strip();
        this.requestTimeout = requestTimeout;
        this.client = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    @Override
    public ProviderOrder createOrder(CreateOrderCommand command) {
        requireApiConfiguration();
        var body = mapper.createObjectNode();
        body.put("amount", command.amountMinor());
        body.put("currency", command.currency());
        body.put("receipt", command.receipt());
        return order(exchange("POST", "/v1/orders", mapper.writeValueAsBytes(body), true));
    }

    @Override public ProviderOrder fetchOrder(String id) {
        requireApiConfiguration();
        return order(exchange("GET", "/v1/orders/" + segment(id), null, false));
    }

    @Override public Optional<ProviderOrder> findOrderByReceipt(String receipt) {
        requireApiConfiguration();
        if (receipt == null || !receipt.matches("[A-Za-z0-9_-]{1,40}"))
            throw provider(DEFINITIVE_REJECTION, false, "Invalid provider receipt", null);
        String encoded = java.net.URLEncoder.encode(receipt, StandardCharsets.UTF_8);
        JsonNode items = exchange("GET", "/v1/orders?receipt=" + encoded + "&count=2", null, false).path("items");
        if (!items.isArray()) throw provider(MALFORMED_RESPONSE, false,
                "Malformed Razorpay order-search response", null);
        ProviderOrder match = null;
        for (JsonNode item : items) {
            if (!receipt.equals(item.path("receipt").asText())) continue;
            if (match != null) throw provider(MALFORMED_RESPONSE, false,
                    "Razorpay returned duplicate orders for a unique receipt", null);
            match = order(item);
        }
        return Optional.ofNullable(match);
    }

    @Override public ProviderPayment fetchPayment(String id) {
        requireApiConfiguration();
        JsonNode node = exchange("GET", "/v1/payments/" + segment(id), null, false);
        try {
            return new ProviderPayment(required(node, "id"), required(node, "order_id"),
                    positive(node, "amount"), required(node, "currency"), required(node, "status"),
                    node.path("captured").asBoolean(false), Instant.now(), accountReference,
                    canonical.hash(node));
        } catch (RuntimeException malformed) {
            throw provider(MALFORMED_RESPONSE, false, "Malformed Razorpay payment response", malformed);
        }
    }

    private ProviderOrder order(JsonNode node) {
        try {
            long created = node.path("created_at").asLong(0);
            return new ProviderOrder(required(node, "id"), positive(node, "amount"),
                    node.path("amount_paid").asLong(0), required(node, "currency"),
                    required(node, "receipt"), required(node, "status"),
                    created > 0 ? Instant.ofEpochSecond(created) : null, accountReference, canonical.hash(node));
        } catch (RuntimeException malformed) {
            throw provider(MALFORMED_RESPONSE, false, "Malformed Razorpay order response", malformed);
        }
    }

    private JsonNode exchange(String method, String path, byte[] body, boolean creation) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(apiBase + path))
                .timeout(requestTimeout).header("Accept", "application/json")
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        (keyId + ":" + keySecret).getBytes(StandardCharsets.UTF_8)));
        if (body == null) request.GET();
        else request.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        try {
            HttpResponse<byte[]> response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.body().length > MAX_RESPONSE_BYTES)
                throw provider(MALFORMED_RESPONSE, false, "Razorpay response exceeded the configured limit", null);
            if (response.statusCode() == 401 || response.statusCode() == 403)
                throw provider(AUTHENTICATION_OR_CONFIGURATION, false, "Razorpay authentication failed", null);
            if (response.statusCode() >= 400 && response.statusCode() < 500)
                throw provider(DEFINITIVE_REJECTION, false, "Razorpay rejected the request", null);
            if (response.statusCode() >= 500)
                throw provider(TRANSIENT_FAILURE, creation, "Razorpay temporarily failed the request", null);
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw provider(UNKNOWN_OUTCOME, creation, "Unexpected Razorpay response status", null);
            return mapper.readTree(response.body());
        } catch (java.net.http.HttpTimeoutException timeout) {
            throw provider(TIMEOUT, creation, "Razorpay request timed out", timeout);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw provider(UNKNOWN_OUTCOME, creation, "Razorpay request interrupted", interrupted);
        } catch (IOException connection) {
            throw provider(CONNECTION_FAILURE, creation, "Razorpay connection failed", connection);
        }
    }

    @Override public boolean verifyCheckoutSignature(String orderId, String paymentId, String signature) {
        return !keySecret.isBlank()
                && verify(keySecret, (orderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8), signature);
    }

    @Override public boolean verifyWebhookSignature(byte[] rawBody, String signature) {
        return !webhookSecret.isBlank() && rawBody != null && verify(webhookSecret, rawBody, signature);
    }

    private static boolean verify(String secret, byte[] material, String signature) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] supplied;
            try { supplied = java.util.HexFormat.of().parseHex(signature == null ? "" : signature); }
            catch (IllegalArgumentException invalid) { return false; }
            return java.security.MessageDigest.isEqual(mac.doFinal(material), supplied);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 is required by the JVM", impossible);
        }
    }

    @Override public boolean configured() { return !keyId.isBlank() && !keySecret.isBlank(); }
    @Override public String publicKeyId() { return configured() ? keyId : ""; }
    @Override public String configurationReference() { return configurationReference; }
    @Override public String providerAccountReference() { return accountReference; }

    private void requireApiConfiguration() {
        if (!configured()) throw provider(AUTHENTICATION_OR_CONFIGURATION, false,
                "Razorpay Test Mode credentials are not configured", null);
    }
    private static String segment(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,128}"))
            throw provider(DEFINITIVE_REJECTION, false, "Invalid provider identifier", null);
        return value;
    }
    private static String required(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (value.isBlank()) throw new IllegalArgumentException(field + " is missing");
        return value;
    }
    private static long positive(JsonNode node, String field) {
        long value = node.path(field).asLong(-1);
        if (value <= 0) throw new IllegalArgumentException(field + " is invalid");
        return value;
    }
    private static PaymentProviderException provider(
            PaymentProviderException.Category category, boolean mayHaveReached, String message, Throwable cause) {
        return new PaymentProviderException(category, mayHaveReached, message, cause);
    }
}
