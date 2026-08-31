package dev.agenticcommerce.gateway.agentization.inspection;

import java.math.BigDecimal;
import java.util.List;

public record SchemaFieldObservation(
        String path,
        String type,
        boolean required,
        String localReference,
        List<String> enumValues,
        Integer minLength,
        Integer maxLength,
        BigDecimal minimum,
        BigDecimal maximum) {
}
