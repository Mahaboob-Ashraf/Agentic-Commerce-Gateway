package dev.agenticcommerce.gateway.agentization.inspection;

import java.util.List;

public record SchemaInspectionResult(
        String schemaReference,
        List<SchemaFieldObservation> fields,
        int maximumDepth,
        boolean truncated) {
}
