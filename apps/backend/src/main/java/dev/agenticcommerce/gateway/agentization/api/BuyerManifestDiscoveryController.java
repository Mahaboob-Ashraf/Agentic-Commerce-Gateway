package dev.agenticcommerce.gateway.agentization.api;

import dev.agenticcommerce.gateway.agentization.authority.AuthorityModels.ManifestCapability;
import dev.agenticcommerce.gateway.agentization.authority.DeterministicReadinessService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Safe discovery view: never returns proposals, policy text, credentials, or non-ready entries. */
@RestController
@RequestMapping("/api/discovery/merchants/{merchantId}")
public class BuyerManifestDiscoveryController {
    private final DeterministicReadinessService readiness;
    public BuyerManifestDiscoveryController(DeterministicReadinessService readiness) { this.readiness=readiness; }
    @GetMapping("/ready-capabilities")
    public List<ManifestCapability> readyCapabilities(@PathVariable UUID merchantId) {
        return readiness.buyerReady(merchantId);
    }
}
