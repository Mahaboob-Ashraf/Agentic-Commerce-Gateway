package dev.agenticcommerce.gateway.payment;

import java.time.Instant;
import java.util.Optional;

/** Server-side provider boundary. No provider credential or raw network operation crosses this API. */
public interface PaymentProvider {
    ProviderOrder createOrder(CreateOrderCommand command);
    ProviderOrder fetchOrder(String providerOrderId);
    ProviderPayment fetchPayment(String providerPaymentId);

    /** Razorpay does not guarantee lookup by receipt; fakes may implement this for lost-response tests. */
    default Optional<ProviderOrder> findOrderByReceipt(String receipt) { return Optional.empty(); }

    boolean verifyCheckoutSignature(String providerOrderId, String providerPaymentId, String signature);
    boolean verifyWebhookSignature(byte[] rawBody, String signature);
    boolean configured();
    String publicKeyId();
    String configurationReference();
    String providerAccountReference();

    record CreateOrderCommand(long amountMinor, String currency, String receipt, String idempotencyReference) {}
    record ProviderOrder(
            String id, long amountMinor, long amountPaidMinor, String currency, String receipt,
            String status, Instant createdAt, String accountReference, String evidenceHash) {}
    record ProviderPayment(
            String id, String orderId, long amountMinor, String currency, String status,
            boolean captured, Instant observedAt, String accountReference, String evidenceHash) {}
}
