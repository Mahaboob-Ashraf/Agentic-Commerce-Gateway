package dev.agenticcommerce.gateway.identity.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Application identity suitable for binding to a future authenticated principal. */
public record ApplicationActor(UUID id, String identityHandle, PlatformRole role, Instant createdAt) {

    public ApplicationActor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(identityHandle, "identityHandle");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
