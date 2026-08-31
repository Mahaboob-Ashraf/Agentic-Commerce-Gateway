package dev.agenticcommerce.gateway.agentization.inspection;

import java.util.List;

public record SpecInspectionResult(String openApiVersion, List<OperationObservation> operations, boolean truncated) {
}
