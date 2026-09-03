package dev.agenticcommerce.gateway.intent;

import dev.agenticcommerce.gateway.intent.BuyerDecisionProvider.CandidateDecisionContext;
import dev.agenticcommerce.gateway.intent.BuyerDecisionProvider.CandidateSelection;
import org.springframework.stereotype.Component;

@Component
public class FoundationBuyerDecisionProvider implements BuyerDecisionProvider {
    @Override public CandidateSelection chooseCandidate(CandidateDecisionContext context,String feedback){
        if(context.candidates().isEmpty())throw new IllegalStateException("Grounded candidate set is empty");
        return new CandidateSelection(context.candidates().getFirst().productId(),
                "Select the highest-ranked grounded candidate",context.evidenceReferences(),
                "DETERMINISTIC_FOUNDATION","candidate-ranker-v1");
    }
}
