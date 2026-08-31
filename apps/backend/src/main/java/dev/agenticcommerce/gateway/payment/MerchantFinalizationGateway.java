package dev.agenticcommerce.gateway.payment;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

public interface MerchantFinalizationGateway {
    Result placeOrder(UUID merchantId, JsonNode request);
    record Result(UUID mappingProposalId, String merchantOrderId, String responseHash) {}
}
