package dev.agenticcommerce.gateway.agentization.authority;

import dev.agenticcommerce.gateway.agentization.authority.AuthorityModels.PolicyDocument;
import dev.agenticcommerce.gateway.agentization.service.AgentizationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Safe default. Policy extraction requires an explicit provider or deterministic test double. */
@Component
public class FoundationPolicyExtractionProvider implements PolicyExtractionProvider {
    @Override
    public PolicyExtractionResult extract(PolicyDocument document) {
        throw new AgentizationException(
                "POLICY_EXTRACTION_PROVIDER_UNAVAILABLE", HttpStatus.CONFLICT,
                "No policy extraction provider is configured");
    }
}
