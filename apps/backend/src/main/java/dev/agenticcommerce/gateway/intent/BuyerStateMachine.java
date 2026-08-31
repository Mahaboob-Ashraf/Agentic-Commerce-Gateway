package dev.agenticcommerce.gateway.intent;

import static dev.agenticcommerce.gateway.intent.BuyerModels.BuyerState;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class BuyerStateMachine {
    private static final Map<BuyerState,Set<BuyerState>> ALLOWED=Map.of(
            BuyerState.UNDERSTANDING,Set.of(BuyerState.SEARCHING,BuyerState.WAITING_FOR_USER),
            BuyerState.SEARCHING,Set.of(BuyerState.UNDERSTANDING,BuyerState.SEARCHING,BuyerState.CART_PROPOSED,BuyerState.WAITING_FOR_USER),
            BuyerState.CART_PROPOSED,Set.of(BuyerState.UNDERSTANDING,BuyerState.CART_PROPOSED,BuyerState.SEARCHING,BuyerState.CONSTRAINTS_VERIFIED,BuyerState.WAITING_FOR_USER),
            BuyerState.CONSTRAINTS_VERIFIED,Set.of(BuyerState.UNDERSTANDING,BuyerState.TRANSACTION_PROPOSED),
            BuyerState.TRANSACTION_PROPOSED,Set.of(BuyerState.UNDERSTANDING,BuyerState.TRANSACTION_PROPOSED,BuyerState.RISK_EVALUATED),
            BuyerState.RISK_EVALUATED,Set.of(BuyerState.UNDERSTANDING,BuyerState.TRANSACTION_PROPOSED,BuyerState.WAITING_FOR_USER,BuyerState.READY_TO_EXECUTE),
            BuyerState.WAITING_FOR_USER,Set.of(BuyerState.UNDERSTANDING,BuyerState.TRANSACTION_PROPOSED,BuyerState.READY_TO_EXECUTE),
            BuyerState.READY_TO_EXECUTE,Set.of(BuyerState.UNDERSTANDING,BuyerState.TRANSACTION_PROPOSED));
    public void require(BuyerState from,BuyerState to){if(!ALLOWED.getOrDefault(from,Set.of()).contains(to))
        throw new BuyerException("BUYER_STATE_TRANSITION_INVALID",HttpStatus.CONFLICT,"Transition "+from+" -> "+to+" is not allowed");}
}
