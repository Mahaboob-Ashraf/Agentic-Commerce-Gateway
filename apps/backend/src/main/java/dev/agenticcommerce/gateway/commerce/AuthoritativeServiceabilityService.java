package dev.agenticcommerce.gateway.commerce;

import static dev.agenticcommerce.gateway.commerce.TransactionModels.*;
import static dev.agenticcommerce.gateway.intent.BuyerModels.*;

import dev.agenticcommerce.gateway.agentization.service.CanonicalJsonService;
import dev.agenticcommerce.gateway.commerce.ServiceabilityProvider.ServiceabilityRequest;
import dev.agenticcommerce.gateway.risk.TransactionAuthorityPolicy;
import java.time.Instant;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class AuthoritativeServiceabilityService {
    private final ServiceabilityProvider provider;
    private final TransactionAuthorityRepository repository;
    private final TransactionAuthorityPolicy policy;
    private final CanonicalJsonService canonical;
    private final ObjectMapper mapper;

    public AuthoritativeServiceabilityService(
            ServiceabilityProvider provider, TransactionAuthorityRepository repository,
            TransactionAuthorityPolicy policy, CanonicalJsonService canonical, ObjectMapper mapper) {
        this.provider = provider;
        this.repository = repository;
        this.policy = policy;
        this.canonical = canonical;
        this.mapper = mapper;
    }

    public ServiceabilityEvidence refresh(
            CandidateCart cart, BuyerIntent intent, MerchantAuthorityContext context) {
        Instant now = Instant.now();
        var result = provider.evaluate(new ServiceabilityRequest(
                cart.merchantId(), cart.threadId(), cart.cartId(), cart.cartHash(),
                intent.compiled().deliveryHint(), now));
        EvidenceOutcome outcome = result == null || result.outcome() == null
                ? EvidenceOutcome.UNKNOWN : result.outcome();
        ServiceabilitySource source = result == null || result.sourceType() == null
                ? ServiceabilitySource.UNRESOLVED : result.sourceType();
        String reason = result == null || result.reasonCode() == null
                ? "SERVICEABILITY_PROVIDER_INVALID" : bounded(result.reasonCode(), 128);
        Instant observed = result == null ? now : result.observedAt();
        Instant expires = result == null ? null : result.expiresAt();
        if (observed == null || observed.isAfter(now.plusSeconds(30))
                || observed.isBefore(now.minus(policy.serviceabilityMaximumAge()))
                || expires != null && !expires.isAfter(now)
                || outcome != EvidenceOutcome.UNKNOWN && source == ServiceabilitySource.UNRESOLVED) {
            outcome = EvidenceOutcome.UNKNOWN;
            source = ServiceabilitySource.UNRESOLVED;
            reason = "SERVICEABILITY_EVIDENCE_INVALID_OR_STALE";
        }
        String location = result == null || result.locationReference() == null
                ? "UNSPECIFIED" : bounded(result.locationReference(), 512);
        String locationHash = canonical.hashText("serviceability-location-v1|" + location);
        var material = mapper.createObjectNode();
        material.put("merchantId", cart.merchantId().toString());
        material.put("cartId", cart.cartId().toString());
        material.put("cartHash", cart.cartHash());
        material.put("manifestId", context.manifestId().toString());
        material.put("manifestVersion", context.manifestVersion());
        material.put("outcome", outcome.name());
        material.put("source", source.name());
        material.put("sourceReference", result == null ? null : bounded(result.sourceReference(), 256));
        material.put("locationReferenceHash", locationHash);
        material.put("reasonCode", reason);
        material.put("observedAt", observed == null ? now.toString() : observed.toString());
        if (expires == null) material.putNull("expiresAt"); else material.put("expiresAt", expires.toString());
        if (result != null && result.evidence() != null) material.set("providerEvidence", result.evidence());
        return repository.createServiceability(cart, context, outcome, source,
                result == null ? null : bounded(result.sourceReference(), 256), locationHash, reason,
                observed == null ? now : observed, expires, canonical.hash(material));
    }

    private static String bounded(String value, int maximum) {
        if (value == null) return null;
        String normalized = value.strip();
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
    }
}
