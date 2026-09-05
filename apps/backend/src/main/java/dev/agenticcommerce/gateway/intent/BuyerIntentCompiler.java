package dev.agenticcommerce.gateway.intent;

import dev.agenticcommerce.gateway.intent.BuyerModels.CompiledIntent;
import dev.agenticcommerce.gateway.intent.BuyerModels.ThreadMessage;
import java.util.List;

/** Structured reasoning boundary only; returned material is validated before persistence or use. */
public interface BuyerIntentCompiler {
    CompiledIntent compile(ThreadMessage message,String validationFeedback);

    default CompiledIntent compile(ThreadMessage message,ConversationContext context,String validationFeedback) {
        return compile(message,validationFeedback);
    }

    record ConversationContext(List<String> priorMessages,CompiledIntent priorIntent,
            VisualCommerceModels.VisionObservation visualObservation) {
        public ConversationContext {
            priorMessages=priorMessages==null?List.of():List.copyOf(priorMessages);
        }
        public ConversationContext(List<String> priorMessages,CompiledIntent priorIntent){this(priorMessages,priorIntent,null);}
        public static ConversationContext empty(){return new ConversationContext(List.of(),null,null);}
    }
}
