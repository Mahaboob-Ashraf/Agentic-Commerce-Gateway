package dev.agenticcommerce.gateway.payment;

import dev.agenticcommerce.gateway.agentization.authority.MerchantAuthorityService;
import dev.agenticcommerce.gateway.agentization.execution.ApprovedMerchantExecutor;
import dev.agenticcommerce.gateway.agentization.execution.MerchantExecutionMode;
import dev.agenticcommerce.gateway.agentization.model.CanonicalCapability;
import dev.agenticcommerce.gateway.agentization.persistence.CapabilityMappingProposalRepository;
import dev.agenticcommerce.gateway.agentization.service.CanonicalJsonService;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Executes only a merchant-approved, deterministically validated PLACE_ORDER mapping. */
@Service
public class ApprovedMerchantFinalizationGateway implements MerchantFinalizationGateway {
    private final JdbcClient jdbc;
    private final CapabilityMappingProposalRepository mappings;
    private final MerchantAuthorityService authority;
    private final ApprovedMerchantExecutor executor;
    private final ObjectMapper mapper;
    private final CanonicalJsonService canonical;

    public ApprovedMerchantFinalizationGateway(
            JdbcClient jdbc, CapabilityMappingProposalRepository mappings, MerchantAuthorityService authority,
            ApprovedMerchantExecutor executor, ObjectMapper mapper, CanonicalJsonService canonical) {
        this.jdbc = jdbc; this.mappings = mappings; this.authority = authority;
        this.executor = executor; this.mapper = mapper; this.canonical = canonical;
    }

    @Override
    public Result placeOrder(UUID merchantId, JsonNode request) {
        ApprovedBinding binding = jdbc.sql("""
                SELECT mapping.mapping_proposal_id,approval.mapping_content_hash
                FROM capability_mapping_proposal mapping
                JOIN mapping_approval_decision approval
                  ON approval.mapping_proposal_id=mapping.mapping_proposal_id
                 AND approval.merchant_id=mapping.merchant_id AND approval.decision='APPROVE'
                WHERE mapping.merchant_id=:merchant AND mapping.capability='PLACE_ORDER'
                    AND mapping.validation_status='VALID'
                ORDER BY approval.decided_at DESC LIMIT 1
                """).param("merchant", merchantId).query((rs, row) -> new ApprovedBinding(
                        rs.getObject("mapping_proposal_id", UUID.class),
                        rs.getString("mapping_content_hash").strip())).optional()
                .orElseThrow(() -> terminal("PLACE_ORDER_CONTRACT_NOT_APPROVED",
                        "Merchant has no approved PLACE_ORDER contract"));
        var mapping = mappings.findByMerchantAndId(merchantId, binding.mappingId())
                .orElseThrow(() -> terminal("PLACE_ORDER_MAPPING_NOT_FOUND", "Approved mapping was not found"));
        if (mapping.capability() != CanonicalCapability.PLACE_ORDER
                || !binding.mappingHash().equals(authority.mappingHash(mapping)))
            throw terminal("PLACE_ORDER_APPROVAL_STALE", "PLACE_ORDER approval does not match mapping material");
        var response = executor.execute(merchantId, mapping, Map.of(), mapper.writeValueAsBytes(request),
                MerchantExecutionMode.RUNTIME);
        if (response.statusCode() == 408 || response.statusCode() == 429 || response.statusCode() >= 500)
            throw retryable("MERCHANT_TEMPORARY_FAILURE", "Merchant PLACE_ORDER failed temporarily");
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw terminal("MERCHANT_DEFINITIVE_REJECTION", "Merchant PLACE_ORDER was rejected");
        if (response.contentType() == null
                || !response.contentType().toLowerCase(Locale.ROOT).startsWith("application/json"))
            throw terminal("MERCHANT_RESPONSE_CONTENT_TYPE_INVALID", "Merchant response must be JSON");
        JsonNode body;
        try { body = mapper.readTree(response.body()); }
        catch (RuntimeException invalid) {
            throw terminal("MERCHANT_RESPONSE_JSON_INVALID", "Merchant response JSON is invalid");
        }
        String bindingPath = mapping.responseBindings().path("orderId").asText("body.orderId");
        JsonNode identity = extract(body, bindingPath);
        String merchantOrderId = identity == null ? null : identity.asText("").strip();
        if (merchantOrderId == null || merchantOrderId.isEmpty() || merchantOrderId.length() > 256)
            throw terminal("MERCHANT_ORDER_ID_INVALID", "Merchant response lacks a bounded order identity");
        return new Result(mapping.mappingProposalId(), merchantOrderId, canonical.hash(body));
    }

    private static JsonNode extract(JsonNode root, String binding) {
        if (binding == null || !binding.startsWith("body.")) return null;
        JsonNode current = root;
        for (String part : binding.substring(5).split("\\.")) current = current.path(part);
        return current;
    }
    private static MerchantFinalizationException retryable(String code, String message) {
        return new MerchantFinalizationException(code, true, message);
    }
    private static MerchantFinalizationException terminal(String code, String message) {
        return new MerchantFinalizationException(code, false, message);
    }
    private record ApprovedBinding(UUID mappingId, String mappingHash) {}
}
