package dev.agenticcommerce.gateway.commerce;

import static dev.agenticcommerce.gateway.commerce.TransactionModels.*;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Typed authority boundary. Implementations return evidence, never an inferred serviceability PASS. */
public interface ServiceabilityProvider {
    ServiceabilityResult evaluate(ServiceabilityRequest request);

    record ServiceabilityRequest(
            UUID merchantId, UUID threadId, UUID cartId, String cartHash,
            UUID fulfilmentSnapshotId, String fulfilmentSnapshotHash, String postalCode,
            String city, String deliveryOption, String deliveryHint, Instant requestedAt) {}

    record ServiceabilityResult(
            EvidenceOutcome outcome, ServiceabilitySource sourceType,
            String sourceReference, String locationReference,
            String reasonCode, Instant observedAt, Instant expiresAt,
            JsonNode evidence) {}
}
