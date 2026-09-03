package dev.agenticcommerce.gateway.agentization.execution;

import java.time.Instant;

public record MerchantTransportResponse(
        int statusCode,
        String contentType,
        byte[] body,
        Instant receivedAt,
        Instant responseDate) {

    public MerchantTransportResponse(int statusCode, String contentType, byte[] body) {
        this(statusCode, contentType, body, Instant.now(), null);
    }
}
