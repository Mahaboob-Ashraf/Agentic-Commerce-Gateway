package dev.agenticcommerce.gateway.intent;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class UnavailableVisionObservationProvider implements VisionObservationProvider {
    @Override public Observed observe(VisualCommerceModels.ValidatedImage image,String buyerText){
        throw new BuyerException("AI_PROVIDER_UNAVAILABLE",HttpStatus.SERVICE_UNAVAILABLE,
                "Amana's visual reasoning service is temporarily unavailable. Nothing was authorized.");
    }
}
