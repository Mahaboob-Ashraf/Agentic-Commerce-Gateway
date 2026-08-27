package dev.agenticcommerce.gateway.identity.service;

import dev.agenticcommerce.gateway.identity.model.PlatformRole;
import dev.agenticcommerce.gateway.identity.persistence.ApplicationActorRepository;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Explicit platform-role check; this service does not bypass tenant-scoped repositories. */
@Service
public class PlatformAdministrationAccessService {

    private final ApplicationActorRepository actorRepository;

    public PlatformAdministrationAccessService(ApplicationActorRepository actorRepository) {
        this.actorRepository = actorRepository;
    }

    public boolean isPlatformAdministrator(UUID actorId) {
        Objects.requireNonNull(actorId, "actorId");
        return actorRepository.findById(actorId)
                .map(actor -> actor.role() == PlatformRole.PLATFORM_ADMIN)
                .orElse(false);
    }
}
