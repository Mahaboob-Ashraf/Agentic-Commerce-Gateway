package dev.agenticcommerce.gateway.agentization.execution;

import java.net.URI;

public record MerchantTransportRequest(
        URI uri,
        String method,
        byte[] jsonBody,
        int connectTimeoutMs,
        int requestTimeoutMs,
        int maximumResponseBytes) {
}
