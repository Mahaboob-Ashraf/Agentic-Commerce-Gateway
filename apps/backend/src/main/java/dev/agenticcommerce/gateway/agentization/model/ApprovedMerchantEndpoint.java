package dev.agenticcommerce.gateway.agentization.model;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record ApprovedMerchantEndpoint(
        UUID endpointId,
        UUID merchantId,
        String baseUri,
        String hostname,
        int port,
        Set<String> approvedMethods,
        List<String> approvedPathTemplates,
        List<String> approvedResolvedAddresses,
        String approvalStatus,
        UUID approvedByActorId,
        Instant approvedAt,
        Instant dnsValidatedAt,
        String credentialReference,
        Instant createdAt) {
}
