package dev.agenticcommerce.gateway.agentization.execution;

public record MerchantTransportResponse(int statusCode, String contentType, byte[] body) {
}
