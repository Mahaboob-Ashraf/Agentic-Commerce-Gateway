package dev.agenticcommerce.gateway.agentization.tool;

import dev.agenticcommerce.gateway.agentization.model.MappingTransformation;
import java.util.UUID;

/** A revision can select only a known transformation; it cannot carry executable expressions. */
public record MappingRevisionInput(
        UUID previousMappingProposalId,
        UUID evidenceContractTestRunId,
        String responseField,
        MappingTransformation transformation,
        String revisionReason,
        String modelProvider,
        String modelName) {

    public MappingRevisionInput {
        if (revisionReason != null && revisionReason.length() > 512) {
            throw new IllegalArgumentException("revisionReason exceeds the revision-schema limit");
        }
        if (responseField != null && responseField.length() > 128) {
            throw new IllegalArgumentException("responseField exceeds the revision-schema limit");
        }
        if (modelProvider != null && modelProvider.length() > 128) {
            throw new IllegalArgumentException("modelProvider exceeds the revision-schema limit");
        }
        if (modelName != null && modelName.length() > 256) {
            throw new IllegalArgumentException("modelName exceeds the revision-schema limit");
        }
    }
}
