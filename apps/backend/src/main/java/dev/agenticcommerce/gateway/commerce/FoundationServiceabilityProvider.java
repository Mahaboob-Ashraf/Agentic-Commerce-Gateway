package dev.agenticcommerce.gateway.commerce;

import static dev.agenticcommerce.gateway.commerce.TransactionModels.*;

import java.time.Instant;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class FoundationServiceabilityProvider implements ServiceabilityProvider {
    private final ObjectMapper mapper;

    public FoundationServiceabilityProvider(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ServiceabilityResult evaluate(ServiceabilityRequest request) {
        Instant now = Instant.now();
        return new ServiceabilityResult(
                EvidenceOutcome.UNKNOWN,
                ServiceabilitySource.UNRESOLVED,
                null,
                request.deliveryHint() == null ? "UNSPECIFIED" : request.deliveryHint(),
                "SERVICEABILITY_CAPABILITY_UNRESOLVED",
                now,
                null,
                mapper.createObjectNode().put("authority", "UNRESOLVED"));
    }
}
