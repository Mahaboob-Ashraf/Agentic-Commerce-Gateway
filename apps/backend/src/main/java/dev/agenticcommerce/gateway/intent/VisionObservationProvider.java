package dev.agenticcommerce.gateway.intent;

import dev.agenticcommerce.gateway.intent.VisualCommerceModels.ValidatedImage;
import dev.agenticcommerce.gateway.intent.VisualCommerceModels.VisionObservation;

public interface VisionObservationProvider {
    Observed observe(ValidatedImage image,String buyerText);
    record Observed(VisionObservation observation,String provider,String model) {}
}
