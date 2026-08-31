package dev.agenticcommerce.gateway.intent;

import dev.agenticcommerce.gateway.intent.BuyerModels.CompiledIntent;
import dev.agenticcommerce.gateway.intent.BuyerModels.ThreadMessage;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Safe default: typed input is retained, but interpretation requires an explicit provider. */
@Component
public class FoundationBuyerIntentCompiler implements BuyerIntentCompiler {
    @Override public CompiledIntent compile(ThreadMessage message,String feedback){
        throw new BuyerException("INTENT_COMPILER_UNAVAILABLE",HttpStatus.CONFLICT,
                "No Safe AI Buyer intent compiler is configured");
    }
}
