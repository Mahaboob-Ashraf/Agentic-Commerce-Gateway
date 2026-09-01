package dev.agenticcommerce.gateway.onboarding;

import static dev.agenticcommerce.gateway.onboarding.OnboardingModels.*;
import dev.agenticcommerce.gateway.identity.authentication.VerifiedActorPrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/buyer/onboarding")
public class OnboardingApiController {
    private final OnboardingService service; public OnboardingApiController(OnboardingService service){this.service=service;}
    @GetMapping("/profile") public BuyerProfile profile(@AuthenticationPrincipal VerifiedActorPrincipal p){return service.profile(p.actorId());}
    @PutMapping("/profile") public BuyerProfile profile(@AuthenticationPrincipal VerifiedActorPrincipal p,@RequestBody ProfileInput i){return service.updateProfile(p.actorId(),i);}
    @PostMapping("/addresses") public BuyerAddress add(@AuthenticationPrincipal VerifiedActorPrincipal p,@RequestBody AddressInput i){return service.addAddress(p.actorId(),i);}
    @GetMapping("/addresses") public List<BuyerAddress> addresses(@AuthenticationPrincipal VerifiedActorPrincipal p){return service.addresses(p.actorId());}
    @PutMapping("/addresses/{id}") public BuyerAddress update(@AuthenticationPrincipal VerifiedActorPrincipal p,@PathVariable UUID id,@RequestBody AddressInput i){return service.updateAddress(p.actorId(),id,i);}
    @PostMapping("/addresses/{id}/select") public BuyerAddress select(@AuthenticationPrincipal VerifiedActorPrincipal p,@PathVariable UUID id){return service.selectAddress(p.actorId(),id);}
    @PostMapping("/merchant-links") public MerchantAccountLink link(@AuthenticationPrincipal VerifiedActorPrincipal p,@RequestBody LinkRequest r){return service.link(p.actorId(),r);}
    @GetMapping("/merchant-links") public List<MerchantAccountLink> links(@AuthenticationPrincipal VerifiedActorPrincipal p){return service.links(p.actorId());}
    @PostMapping("/merchant-links/{id}/revoke") public MerchantAccountLink revoke(@AuthenticationPrincipal VerifiedActorPrincipal p,@PathVariable UUID id){return service.revoke(p.actorId(),id);}
    @GetMapping("/status") public OnboardingStatus status(@AuthenticationPrincipal VerifiedActorPrincipal p){return service.status(p.actorId());}
}
