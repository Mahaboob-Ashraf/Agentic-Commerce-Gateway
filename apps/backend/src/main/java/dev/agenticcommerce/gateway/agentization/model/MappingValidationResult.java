package dev.agenticcommerce.gateway.agentization.model;

import java.util.UUID;

public record MappingValidationResult(
        UUID mappingProposalId, int mappingVersion, boolean valid, String reasonCode) {
}
