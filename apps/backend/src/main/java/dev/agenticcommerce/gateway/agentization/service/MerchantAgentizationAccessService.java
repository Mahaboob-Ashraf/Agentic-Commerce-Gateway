package dev.agenticcommerce.gateway.agentization.service;

import dev.agenticcommerce.gateway.identity.service.MerchantAdministrationAccessService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class MerchantAgentizationAccessService {

    private final MerchantAdministrationAccessService merchantAccessService;

    public MerchantAgentizationAccessService(
            MerchantAdministrationAccessService merchantAccessService) {
        this.merchantAccessService = merchantAccessService;
    }

    public void requireMerchantAdmin(UUID actorId, UUID merchantId) {
        if (!merchantAccessService.canAdminister(actorId, merchantId)) {
            throw new AgentizationException(
                    "MERCHANT_AGENTIZATION_ACCESS_DENIED",
                    HttpStatus.FORBIDDEN,
                    "The authenticated actor cannot administer this merchant");
        }
    }
}
