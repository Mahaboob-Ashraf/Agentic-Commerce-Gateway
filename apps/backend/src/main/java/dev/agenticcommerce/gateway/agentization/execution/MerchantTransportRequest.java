package dev.agenticcommerce.gateway.agentization.execution;

import java.net.URI;
import java.util.Map;

public record MerchantTransportRequest(
        URI uri,
        String method,
        byte[] jsonBody,
        Map<String, String> headers,
        int connectTimeoutMs,
        int requestTimeoutMs,
        int maximumResponseBytes) {

    public MerchantTransportRequest {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
}
