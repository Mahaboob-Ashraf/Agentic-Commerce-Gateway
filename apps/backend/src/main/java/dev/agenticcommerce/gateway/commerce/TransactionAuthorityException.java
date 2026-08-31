package dev.agenticcommerce.gateway.commerce;

import org.springframework.http.HttpStatus;

public final class TransactionAuthorityException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    public TransactionAuthorityException(String code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String code() { return code; }
    public HttpStatus status() { return status; }
}
