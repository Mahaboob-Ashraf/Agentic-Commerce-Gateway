package dev.agenticcommerce.gateway.intent;

import dev.agenticcommerce.gateway.intent.BuyerDecisionProvider.BuyerDecisionContext;
import dev.agenticcommerce.gateway.intent.BuyerDecisionProvider.NextBuyerAction;
import org.springframework.stereotype.Component;

@Component
public class FoundationBuyerDecisionProvider implements BuyerDecisionProvider {
    @Override public NextBuyerAction choose(BuyerDecisionContext context,String feedback){
        if(context.permittedTools().size()!=1)throw new IllegalStateException("Safe default requires one deterministic permitted action");
        return new NextBuyerAction(context.permittedTools().getFirst(),"Advance one bounded deterministic buyer step",context.evidenceReferences(),"DETERMINISTIC_FOUNDATION","buyer-state-v1");
    }
}
