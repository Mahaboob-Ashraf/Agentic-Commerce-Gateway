package dev.agenticcommerce.gateway.agentization.model;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record CapabilityMappingProposal(
        UUID mappingProposalId,
        UUID merchantId,
        UUID runId,
        CanonicalCapability capability,
        int mappingVersion,
        UUID sourceArtifactId,
        UUID endpointId,
        String sourceOperationId,
        String httpMethod,
        String pathTemplate,
        JsonNode requestBindings,
        JsonNode responseBindings,
        JsonNode transformations,
        JsonNode amountInterpretation,
        JsonNode currencyInterpretation,
        JsonNode statusNormalization,
        JsonNode idempotencySemantics,
        JsonNode retrySemantics,
        String modelProvider,
        String modelName,
        MappingProposalStatus status,
        String validationStatus,
        int connectTimeoutMs,
        int requestTimeoutMs,
        int maximumRequestBytes,
        int maximumResponseBytes,
        UUID previousMappingProposalId,
        String revisionReason,
        UUID revisionEvidenceTestRunId,
        Instant createdAt) {
}
