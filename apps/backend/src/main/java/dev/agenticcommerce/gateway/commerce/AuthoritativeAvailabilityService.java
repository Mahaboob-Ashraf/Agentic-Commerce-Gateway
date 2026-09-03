package dev.agenticcommerce.gateway.commerce;

import static dev.agenticcommerce.gateway.commerce.TransactionModels.*;
import static dev.agenticcommerce.gateway.intent.BuyerModels.*;

import dev.agenticcommerce.gateway.agentization.execution.ApprovedMerchantExecutor;
import dev.agenticcommerce.gateway.agentization.execution.MerchantExecutionMode;
import dev.agenticcommerce.gateway.agentization.execution.MerchantTransportResponse;
import dev.agenticcommerce.gateway.agentization.model.CanonicalCapability;
import dev.agenticcommerce.gateway.agentization.persistence.CapabilityMappingProposalRepository;
import dev.agenticcommerce.gateway.agentization.service.CanonicalJsonService;
import dev.agenticcommerce.gateway.risk.TransactionAuthorityPolicy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class AuthoritativeAvailabilityService {
    private final ApprovedMerchantExecutor executor;
    private final CapabilityMappingProposalRepository mappings;
    private final TransactionAuthorityRepository repository;
    private final TransactionAuthorityPolicy policy;
    private final CanonicalJsonService canonical;
    private final ObjectMapper mapper;

    public AuthoritativeAvailabilityService(
            ApprovedMerchantExecutor executor, CapabilityMappingProposalRepository mappings,
            TransactionAuthorityRepository repository, TransactionAuthorityPolicy policy,
            CanonicalJsonService canonical, ObjectMapper mapper) {
        this.executor = executor;
        this.mappings = mappings;
        this.repository = repository;
        this.policy = policy;
        this.canonical = canonical;
        this.mapper = mapper;
    }

    public AvailabilityRefresh refresh(CandidateCart cart, MerchantAuthorityContext context) {
        Instant now = Instant.now();
        CapabilityBinding binding = context.availabilityCapability();
        List<AvailabilityItemEvidence> items = new ArrayList<>();
        if (binding == null || !binding.ready()) {
            for (CandidateCartItem item : cart.items()) {
                items.add(unknown(cart, item, "GET_AVAILABILITY_NOT_READY", now));
            }
            return persist(cart, context, items, now, "GET_AVAILABILITY_NOT_READY");
        }
        var mapping = mappings.findByMerchantAndId(cart.merchantId(), binding.executableMappingProposalId())
                .orElse(null);
        if (mapping == null || mapping.capability() != CanonicalCapability.GET_AVAILABILITY
                || !"VALID".equals(mapping.validationStatus())) {
            for (CandidateCartItem item : cart.items()) {
                items.add(unknown(cart, item, "GET_AVAILABILITY_MAPPING_INVALID", now));
            }
            return persist(cart, context, items, now, "GET_AVAILABILITY_MAPPING_INVALID");
        }
        for (CandidateCartItem item : cart.items()) {
            items.add(invoke(cart, item, mapping, now));
        }
        return persist(cart, context, items, now, overallReason(items));
    }

    private AvailabilityItemEvidence invoke(
            CandidateCart cart, CandidateCartItem item,
            dev.agenticcommerce.gateway.agentization.model.CapabilityMappingProposal mapping,
            Instant now) {
        try {
            var request = mapper.createObjectNode();
            request.put("merchantId", cart.merchantId().toString());
            request.put("cartId", cart.cartId().toString());
            request.put("cartHash", cart.cartHash());
            request.put("productId", item.productId().toString());
            request.put("merchantSku", item.merchantSku());
            if (item.variant() == null) request.putNull("variant"); else request.put("variant", item.variant());
            request.put("requestedQuantity", item.quantity());
            var response = executor.execute(cart.merchantId(), mapping, Map.of(),
                    mapper.writeValueAsBytes(request), MerchantExecutionMode.RUNTIME);
            if (response.statusCode() < 200 || response.statusCode() >= 300
                    || response.contentType() == null
                    || !response.contentType().toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
                return unknown(cart, item, "AVAILABILITY_RESPONSE_INVALID", now);
            }
            JsonNode value = mapper.readTree(response.body());
            String responseHash = canonical.hash(value);
            if (!cart.merchantId().toString().equals(value.path("merchantId").asText())
                    || !item.merchantSku().equals(value.path("merchantSku").asText())
                    || item.quantity() != value.path("requestedQuantity").asInt(-1)
                    || value.hasNonNull("productId")
                    && !item.productId().toString().equals(value.path("productId").asText())
                    || item.variant() != null
                    && !item.variant().equals(value.path("variant").asText(null))) {
                return new AvailabilityItemEvidence(null, item.productId(), cart.catalogueVersionId(),
                        item.merchantSku(), item.variant(), item.quantity(), null, null,
                        EvidenceOutcome.UNKNOWN, "AVAILABILITY_IDENTITY_MISMATCH", now, null, responseHash);
            }
            Instant observed = parseInstant(value.path("observedAt").asText(null));
            Instant expires = parseInstant(value.path("expiresAt").asText(null));
            if (!isFresh(observed, expires, response, now, policy.availabilityMaximumAge())) {
                return new AvailabilityItemEvidence(null, item.productId(), cart.catalogueVersionId(),
                        item.merchantSku(), item.variant(), item.quantity(), null, null,
                        EvidenceOutcome.UNKNOWN, "AVAILABILITY_STALE_OR_UNDATED", observed, expires, responseHash);
            }
            Boolean available = value.path("available").isBoolean()
                    ? value.path("available").booleanValue() : null;
            Long quantity = integral(value.path("availableQuantity"));
            EvidenceOutcome outcome;
            String reason;
            if (quantity != null) {
                outcome = quantity >= item.quantity() ? EvidenceOutcome.PASS : EvidenceOutcome.FAIL;
                reason = outcome == EvidenceOutcome.PASS ? "AUTHORITATIVE_QUANTITY_SUFFICIENT"
                        : "AUTHORITATIVE_QUANTITY_INSUFFICIENT";
            } else if (available != null) {
                outcome = available ? EvidenceOutcome.PASS : EvidenceOutcome.FAIL;
                reason = available ? "AUTHORITATIVE_AVAILABLE" : "AUTHORITATIVE_UNAVAILABLE";
            } else {
                outcome = EvidenceOutcome.UNKNOWN;
                reason = "AUTHORITATIVE_STOCK_UNESTABLISHED";
            }
            return new AvailabilityItemEvidence(null, item.productId(), cart.catalogueVersionId(),
                    item.merchantSku(), item.variant(), item.quantity(), available, quantity,
                    outcome, reason, observed, expires, responseHash);
        } catch (RuntimeException failure) {
            return unknown(cart, item, "AVAILABILITY_PROVIDER_FAILURE", now);
        }
    }

    private AvailabilityRefresh persist(
            CandidateCart cart, MerchantAuthorityContext context,
            List<AvailabilityItemEvidence> items, Instant now, String reason) {
        EvidenceOutcome outcome = reduce(items.stream().map(AvailabilityItemEvidence::outcome).toList());
        Instant expires = items.stream().map(AvailabilityItemEvidence::merchantExpiresAt)
                .filter(java.util.Objects::nonNull).min(Comparator.naturalOrder())
                .orElse(now.plus(policy.availabilityMaximumAge()));
        var material = mapper.createObjectNode();
        material.put("merchantId", cart.merchantId().toString());
        material.put("cartId", cart.cartId().toString());
        material.put("cartHash", cart.cartHash());
        material.put("manifestId", context.manifestId().toString());
        material.put("manifestVersion", context.manifestVersion());
        material.put("mappingId", context.availabilityCapability() == null
                || context.availabilityCapability().executableMappingProposalId() == null ? null
                : context.availabilityCapability().executableMappingProposalId().toString());
        material.put("outcome", outcome.name());
        material.put("observedAt", now.toString());
        material.put("expiresAt", expires.toString());
        material.set("items", mapper.valueToTree(items));
        return repository.createAvailability(cart, context, outcome, reason, now, expires,
                canonical.hash(material), List.copyOf(items));
    }

    private static AvailabilityItemEvidence unknown(
            CandidateCart cart, CandidateCartItem item, String reason, Instant now) {
        String hash = CanonicalHash.sha256(reason + "|" + cart.cartHash() + "|" + item.productId());
        return new AvailabilityItemEvidence(null, item.productId(), cart.catalogueVersionId(),
                item.merchantSku(), item.variant(), item.quantity(), null, null,
                EvidenceOutcome.UNKNOWN, reason, now, null, hash);
    }

    private static Long integral(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0
                ? value.longValue() : null;
    }

    private static Instant parseInstant(String value) {
        try { return value == null ? null : Instant.parse(value); }
        catch (RuntimeException ignored) { return null; }
    }

    static boolean isFresh(
            Instant observed,
            Instant expires,
            MerchantTransportResponse response,
            Instant fallbackNow,
            java.time.Duration maximumAge) {
        Instant reference = response.responseDate() != null
                ? response.responseDate()
                : response.receivedAt() != null ? response.receivedAt() : fallbackNow;
        return observed != null
                && !observed.isAfter(reference.plusSeconds(30))
                && !observed.isBefore(reference.minus(maximumAge))
                && (expires == null || expires.isAfter(reference));
    }

    private static EvidenceOutcome reduce(List<EvidenceOutcome> outcomes) {
        if (outcomes.stream().anyMatch(value -> value == EvidenceOutcome.FAIL)) return EvidenceOutcome.FAIL;
        if (outcomes.stream().anyMatch(value -> value == EvidenceOutcome.UNKNOWN)) return EvidenceOutcome.UNKNOWN;
        return EvidenceOutcome.PASS;
    }

    private static String overallReason(List<AvailabilityItemEvidence> items) {
        EvidenceOutcome outcome = reduce(items.stream().map(AvailabilityItemEvidence::outcome).toList());
        return switch (outcome) {
            case PASS -> "ALL_AUTHORITATIVE_STOCK_PASS";
            case FAIL -> "AUTHORITATIVE_STOCK_FAIL";
            case UNKNOWN -> "AUTHORITATIVE_STOCK_UNKNOWN";
        };
    }

    private static final class CanonicalHash {
        private static String sha256(String value) {
            try {
                return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            } catch (java.security.NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }
}
