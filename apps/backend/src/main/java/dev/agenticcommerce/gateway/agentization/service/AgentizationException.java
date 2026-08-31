package dev.agenticcommerce.gateway.agentization.service;

import org.springframework.http.HttpStatus;

public class AgentizationException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    public AgentizationException(String code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }
}
