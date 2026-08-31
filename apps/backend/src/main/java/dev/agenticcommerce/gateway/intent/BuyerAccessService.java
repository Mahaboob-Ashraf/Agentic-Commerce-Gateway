package dev.agenticcommerce.gateway.intent;

import dev.agenticcommerce.gateway.identity.model.PlatformRole;
import dev.agenticcommerce.gateway.identity.persistence.ApplicationActorRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class BuyerAccessService {
    private final ApplicationActorRepository actors;
    public BuyerAccessService(ApplicationActorRepository actors){this.actors=actors;}
    public void requireBuyer(UUID actorId){var actor=actors.findById(actorId).orElseThrow(()->new BuyerException("BUYER_NOT_FOUND",HttpStatus.UNAUTHORIZED,"Buyer identity was not found"));
        if(actor.role()!=PlatformRole.BUYER)throw new BuyerException("BUYER_ROLE_REQUIRED",HttpStatus.FORBIDDEN,"Canonical BUYER role is required");}
}
