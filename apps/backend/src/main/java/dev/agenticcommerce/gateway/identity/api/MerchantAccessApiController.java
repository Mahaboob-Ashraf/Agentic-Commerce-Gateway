package dev.agenticcommerce.gateway.identity.api;

import dev.agenticcommerce.gateway.identity.authentication.VerifiedActorPrincipal;
import dev.agenticcommerce.gateway.identity.model.Merchant;
import dev.agenticcommerce.gateway.identity.persistence.MerchantRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated, actor-scoped merchant workspace discovery. */
@RestController
@RequestMapping("/api/merchants")
public class MerchantAccessApiController {

    private final MerchantRepository merchants;

    public MerchantAccessApiController(MerchantRepository merchants) {
        this.merchants = merchants;
    }

    @GetMapping
    public List<MerchantAccessView> listMine(
            @AuthenticationPrincipal VerifiedActorPrincipal principal) {
        return merchants.findAllAdministeredByActor(principal.actorId()).stream()
                .map(MerchantAccessView::from)
                .toList();
    }

    public record MerchantAccessView(UUID merchantId, String merchantKey, String displayName) {
        static MerchantAccessView from(Merchant merchant) {
            return new MerchantAccessView(merchant.id(), merchant.merchantKey(), merchant.displayName());
        }
    }
}
