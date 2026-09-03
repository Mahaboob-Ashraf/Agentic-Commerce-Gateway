package dev.agenticcommerce.gateway.intent;

import java.util.List;
import java.util.UUID;

/** Bounded grounded-candidate reasoning boundary. Application code owns transitions, validation, and execution. */
public interface BuyerDecisionProvider {
    CandidateSelection chooseCandidate(CandidateDecisionContext context,String validationFeedback);
    record CandidateOption(UUID productId,UUID merchantId,String productName,String brand,String variant,
            String sizeStorage,String colour,String category,Long priceMinor,String currency,double retrievalScore){}
    record CandidateDecisionContext(List<CandidateOption> candidates,List<String> softPreferences,
            List<String> evidenceReferences){}
    record CandidateSelection(UUID productId,String conciseRationale,List<String> evidenceReferences,
            String provider,String model){}
}
