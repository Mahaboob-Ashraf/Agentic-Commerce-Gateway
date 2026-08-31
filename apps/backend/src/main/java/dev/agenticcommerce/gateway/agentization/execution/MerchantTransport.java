package dev.agenticcommerce.gateway.agentization.execution;

public interface MerchantTransport {
    MerchantTransportResponse execute(
            ValidatedEndpointResolution resolution, MerchantTransportRequest request);
}
