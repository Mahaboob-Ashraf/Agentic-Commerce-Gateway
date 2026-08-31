package dev.agenticcommerce.gateway.agentization.tool;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record MappingProposalInput(
        UUID artifactId,
        int mappingVersion,
        String operationId,
        String httpMethod,
        String pathTemplate,
        JsonNode requestBindings,
        JsonNode responseBindings,
        JsonNode transformations,
        JsonNode amountInterpretation,
        JsonNode currencyInterpretation,
        String modelProvider,
        String modelName) {

    private static final int MAX_JSON_CHARACTERS = 16_000;

    public MappingProposalInput {
        requireMaximumLength(operationId, 256, "operationId");
        requireMaximumLength(httpMethod, 16, "httpMethod");
        requireMaximumLength(pathTemplate, 1_024, "pathTemplate");
        requireMaximumLength(modelProvider, 128, "modelProvider");
        requireMaximumLength(modelName, 256, "modelName");
        requireBoundedObject(requestBindings, "requestBindings");
        requireBoundedObject(responseBindings, "responseBindings");
        requireBoundedObject(transformations, "transformations");
        requireBoundedObject(amountInterpretation, "amountInterpretation");
        requireBoundedObject(currencyInterpretation, "currencyInterpretation");
    }

    private static void requireMaximumLength(String value, int maximum, String field) {
        if (value != null && value.length() > maximum) {
            throw new IllegalArgumentException(field + " exceeds the mapping-schema limit");
        }
    }

    private static void requireBoundedObject(JsonNode value, String field) {
        if (value == null || !value.isObject() || value.toString().length() > MAX_JSON_CHARACTERS) {
            throw new IllegalArgumentException(field + " must be a bounded JSON object");
        }
    }
}
