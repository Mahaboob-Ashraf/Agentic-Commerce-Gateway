package dev.agenticcommerce.gateway.identity.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Stable tenant identity for merchant-owned resources. */
public record Merchant(UUID id, String merchantKey, String displayName, Instant createdAt) {

    public Merchant {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(merchantKey, "merchantKey");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
