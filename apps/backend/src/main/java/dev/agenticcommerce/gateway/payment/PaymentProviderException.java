package dev.agenticcommerce.gateway.payment;

public final class PaymentProviderException extends RuntimeException {
    public enum Category {
        TIMEOUT, CONNECTION_FAILURE, DEFINITIVE_REJECTION, TRANSIENT_FAILURE,
        MALFORMED_RESPONSE, AUTHENTICATION_OR_CONFIGURATION, UNKNOWN_OUTCOME
    }

    private final Category category;
    private final boolean requestMayHaveReachedProvider;

    public PaymentProviderException(
            Category category, boolean requestMayHaveReachedProvider, String message, Throwable cause) {
        super(message, cause);
        this.category = category;
        this.requestMayHaveReachedProvider = requestMayHaveReachedProvider;
    }

    public Category category() { return category; }
    public boolean requestMayHaveReachedProvider() { return requestMayHaveReachedProvider; }
    public boolean uncertain() {
        return category == Category.TIMEOUT || category == Category.CONNECTION_FAILURE
                || category == Category.TRANSIENT_FAILURE || category == Category.UNKNOWN_OUTCOME;
    }
}
