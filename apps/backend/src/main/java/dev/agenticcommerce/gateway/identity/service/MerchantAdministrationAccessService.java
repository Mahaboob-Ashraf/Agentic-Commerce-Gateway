package dev.agenticcommerce.gateway.identity.service;

import dev.agenticcommerce.gateway.identity.model.PlatformRole;
import dev.agenticcommerce.gateway.identity.persistence.ApplicationActorRepository;
import dev.agenticcommerce.gateway.identity.persistence.MerchantAdminMembershipRepository;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Performs the explicit role-and-membership check for merchant administration. */
@Service
public class MerchantAdministrationAccessService {

    private final ApplicationActorRepository actorRepository;
    private final MerchantAdminMembershipRepository membershipRepository;

    public MerchantAdministrationAccessService(
            ApplicationActorRepository actorRepository,
            MerchantAdminMembershipRepository membershipRepository) {
        this.actorRepository = actorRepository;
        this.membershipRepository = membershipRepository;
    }

    public boolean canAdminister(UUID actorId, UUID merchantId) {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(merchantId, "merchantId");
        return actorRepository.findById(actorId)
                .filter(actor -> actor.role() == PlatformRole.MERCHANT_ADMIN)
                .filter(actor -> membershipRepository.existsByMerchantAndActor(merchantId, actor.id()))
                .isPresent();
    }
}
