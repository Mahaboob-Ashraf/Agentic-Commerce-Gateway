package dev.agenticcommerce.gateway.payment;

/**
 * Boundary for canonical payment-provider operations.
 *
 * <p>The bounded Razorpay payment task will introduce methods only after payment semantics,
 * idempotency, evidence, and reconciliation contracts are specified. There is deliberately no
 * provider implementation in the bootstrap.</p>
 */
public interface PaymentProvider {
}
