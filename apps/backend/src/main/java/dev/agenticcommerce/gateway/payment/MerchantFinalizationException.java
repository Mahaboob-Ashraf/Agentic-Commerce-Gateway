package dev.agenticcommerce.gateway.payment;

public final class MerchantFinalizationException extends RuntimeException {
    private final String code;
    private final boolean retryable;
    public MerchantFinalizationException(String code, boolean retryable, String message) {
        super(message); this.code = code; this.retryable = retryable;
    }
    public String code() { return code; }
    public boolean retryable() { return retryable; }
}
