package dev.agenticcommerce.gateway.onboarding;

import org.springframework.http.HttpStatus;

public final class OnboardingException extends RuntimeException {
    private final String code;
    private final HttpStatus status;
    public OnboardingException(String code, HttpStatus status, String message) {
        super(message); this.code = code; this.status = status;
    }
    public String code() { return code; }
    public HttpStatus status() { return status; }
}
