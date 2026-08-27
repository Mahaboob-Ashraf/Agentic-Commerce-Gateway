package dev.agenticcommerce.gateway.identity.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Internal password credential metadata. It must never be returned from an API. */
public record ActorPasswordCredential(
        UUID actorId,
        String passwordHash,
        boolean enabled,
        Instant createdAt,
        Instant passwordChangedAt) {

    public ActorPasswordCredential {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(passwordHash, "passwordHash");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(passwordChangedAt, "passwordChangedAt");
    }

    @Override
    public String toString() {
        return "ActorPasswordCredential[actorId=" + actorId
                + ", passwordHash=<redacted>, enabled=" + enabled
                + ", createdAt=" + createdAt
                + ", passwordChangedAt=" + passwordChangedAt + "]";
    }
}
