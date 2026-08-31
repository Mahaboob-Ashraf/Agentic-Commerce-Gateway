package dev.agenticcommerce.gateway.intent;

import dev.agenticcommerce.gateway.intent.BuyerModels.CompiledIntent;
import dev.agenticcommerce.gateway.intent.BuyerModels.ThreadMessage;

/** Structured reasoning boundary only; returned material is validated before persistence or use. */
public interface BuyerIntentCompiler {
    CompiledIntent compile(ThreadMessage message,String validationFeedback);
}
