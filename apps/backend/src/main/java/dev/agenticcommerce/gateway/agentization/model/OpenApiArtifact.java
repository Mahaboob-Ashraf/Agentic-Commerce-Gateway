package dev.agenticcommerce.gateway.agentization.model;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record OpenApiArtifact(
        UUID artifactId,
        UUID merchantId,
        UUID endpointId,
        String artifactType,
        String artifactVersion,
        String contentHash,
        JsonNode document,
        Instant createdAt) {
}
