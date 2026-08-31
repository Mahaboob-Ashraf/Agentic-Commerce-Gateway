package dev.agenticcommerce.gateway.agentization.inspection;

import java.util.List;
import java.util.Map;

public record OperationObservation(
        String path,
        String method,
        String operationId,
        String summary,
        String description,
        List<ParameterObservation> parameters,
        String requestSchemaReference,
        Map<String, String> responseSchemaReferences,
        List<String> tags) {
}
