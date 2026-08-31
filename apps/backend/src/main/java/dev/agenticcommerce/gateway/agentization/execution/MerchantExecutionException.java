package dev.agenticcommerce.gateway.agentization.execution;

public class MerchantExecutionException extends RuntimeException {
    private final String code;

    public MerchantExecutionException(String code, String message) {
        super(message);
        this.code = code;
    }

    public MerchantExecutionException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
