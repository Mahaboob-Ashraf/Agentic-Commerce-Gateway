package dev.agenticcommerce.gateway.commerce;

import static dev.agenticcommerce.gateway.commerce.TransactionModels.*;

import dev.agenticcommerce.gateway.demo.DemoMerchantRepository;
import java.time.Instant;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class FoundationServiceabilityProvider implements ServiceabilityProvider {
    private final DemoMerchantRepository demoMerchants;
    private final ObjectMapper mapper;

    public FoundationServiceabilityProvider(DemoMerchantRepository demoMerchants,ObjectMapper mapper) {
        this.demoMerchants = demoMerchants;
        this.mapper = mapper;
    }

    @Override
    public ServiceabilityResult evaluate(ServiceabilityRequest request) {
        Instant now = Instant.now();
        if(demoMerchants.isDemoMerchant(request.merchantId()))return new ServiceabilityResult(
                EvidenceOutcome.PASS,ServiceabilitySource.TRUSTED_DEMO_FIXTURE,
                "demo-bengaluru-delivery-v1",request.postalCode(),"DEMO_DELIVERY_AREA_PASS",now,
                now.plusSeconds(300),mapper.createObjectNode().put("fixture","demo-bengaluru-delivery-v1")
                        .put("postalCode",request.postalCode()));
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
