package dev.agenticcommerce.gateway.agentization.authority;

import dev.agenticcommerce.gateway.agentization.authority.AuthorityModels.PolicyDocument;
import dev.agenticcommerce.gateway.agentization.authority.AuthorityModels.ProposedPolicyRuleInput;
import java.util.List;

/** Reasoning boundary: extracted rules are proposals and never merchant authority. */
public interface PolicyExtractionProvider {
    PolicyExtractionResult extract(PolicyDocument document);

    record PolicyExtractionResult(String provider, String model, List<ProposedPolicyRuleInput> rules) {}
}
