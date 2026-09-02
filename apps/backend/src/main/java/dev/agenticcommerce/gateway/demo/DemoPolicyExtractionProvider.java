package dev.agenticcommerce.gateway.demo;

import static dev.agenticcommerce.gateway.agentization.authority.AuthorityModels.*;

import dev.agenticcommerce.gateway.agentization.authority.PolicyExtractionProvider;
import dev.agenticcommerce.gateway.agentization.authority.PolicyExtractionProvider.PolicyExtractionResult;
import java.math.BigDecimal;
import java.util.List;
import tools.jackson.databind.ObjectMapper;

/** Non-bean deterministic proposal generator owned exclusively by the demo bootstrap service. */
final class DemoPolicyExtractionProvider {
    private final ObjectMapper mapper;
    public DemoPolicyExtractionProvider(ObjectMapper mapper){this.mapper=mapper;}
    public PolicyExtractionResult extract(PolicyDocument document){boolean fresh=document.normalizedContent().contains("FRESH_BASKET");
        var cancelConditions=mapper.createObjectNode().put("action","CANCEL_ORDER");
        var cancelOutcome=mapper.createObjectNode().put("eligible",!fresh).put("maximumAgeDays",0);
        var returnConditions=mapper.createObjectNode().put("action","RETURN_ORDER");
        var returnOutcome=mapper.createObjectNode().put("eligible",!fresh).put("maximumAgeDays",fresh?0:14);
        var shippingConditions=mapper.createObjectNode().put("action","SHIPPING");
        var shippingOutcome=mapper.createObjectNode().put("eligible",true).put("shippingDays",fresh?1:5);
        return new PolicyExtractionResult("DETERMINISTIC_DEMO_POLICY","demo-policy-v1",List.of(
                new ProposedPolicyRuleInput(PolicyRuleType.CANCELLATION_WINDOW,"Explicit demo cancellation clause",cancelConditions,cancelOutcome,BigDecimal.ONE,100,null,null),
                new ProposedPolicyRuleInput(fresh?PolicyRuleType.NON_RETURNABLE:PolicyRuleType.RETURN_WINDOW,"Explicit demo return clause",returnConditions,returnOutcome,BigDecimal.ONE,100,null,null),
                new ProposedPolicyRuleInput(PolicyRuleType.SHIPPING_RULE,"Explicit demo delivery clause",shippingConditions,shippingOutcome,BigDecimal.ONE,50,null,null)));
    }
}
