package dev.agenticcommerce.gateway.identity.api;

import dev.agenticcommerce.gateway.identity.authentication.VerifiedActorPrincipal;
import dev.agenticcommerce.gateway.identity.model.PlatformRole;
import java.util.UUID;

/** Safe authenticated-actor fields; credential and session secrets are intentionally absent. */
public record ActorSessionResponse(UUID actorId, String identityHandle, PlatformRole role) {

    static ActorSessionResponse from(VerifiedActorPrincipal principal) {
        return new ActorSessionResponse(
                principal.actorId(), principal.identityHandle(), principal.role());
    }
}
