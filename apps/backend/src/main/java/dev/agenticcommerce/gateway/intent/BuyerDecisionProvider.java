package dev.agenticcommerce.gateway.intent;

import static dev.agenticcommerce.gateway.intent.BuyerModels.*;
import java.util.List;

/** Bounded action-selection boundary. Application code owns permissions, arguments, and execution. */
public interface BuyerDecisionProvider {
    NextBuyerAction choose(BuyerDecisionContext context,String validationFeedback);
    record BuyerDecisionContext(BuyerState state,List<BuyerTool> permittedTools,List<String> evidenceReferences){}
    record NextBuyerAction(BuyerTool action,String conciseRationale,List<String> evidenceReferences,String provider,String model){}
}
