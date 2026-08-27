package dev.agenticcommerce.gateway.identity.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Explicit relationship granting one merchant-admin actor administration of one merchant. */
public record MerchantAdminMembership(UUID merchantId, UUID actorId, Instant createdAt) {

    public MerchantAdminMembership {
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
