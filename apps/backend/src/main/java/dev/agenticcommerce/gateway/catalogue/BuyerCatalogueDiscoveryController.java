package dev.agenticcommerce.gateway.catalogue;

import static dev.agenticcommerce.gateway.catalogue.CatalogueModels.*;

import jakarta.validation.Valid;
import java.util.UUID;
import dev.agenticcommerce.gateway.agentization.authority.DeterministicReadinessService;
import dev.agenticcommerce.gateway.agentization.authority.AuthorityModels.ReadinessCapability;
import dev.agenticcommerce.gateway.agentization.service.AgentizationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/discovery/merchants/{merchantId}/catalogue")
public class BuyerCatalogueDiscoveryController {
    private final HybridCatalogueRetrievalService retrieval;
    private final DeterministicReadinessService readiness;
    public BuyerCatalogueDiscoveryController(HybridCatalogueRetrievalService retrieval,DeterministicReadinessService readiness){this.retrieval=retrieval;this.readiness=readiness;}
    @PostMapping("/search") SearchResponse search(@PathVariable UUID merchantId,@Valid @RequestBody SearchRequest request){requireReady(merchantId);return retrieval.search(merchantId,request);}
    @GetMapping("/exact") SearchResponse exact(@PathVariable UUID merchantId,@RequestParam(required=false) String merchantSku,@RequestParam(required=false) String gtin){requireReady(merchantId);return retrieval.exact(merchantId,merchantSku,gtin);}
    private void requireReady(UUID merchantId){if(readiness.buyerReady(merchantId).stream().noneMatch(c->c.capability()==ReadinessCapability.SEARCH_PRODUCTS))
        throw new AgentizationException("CATALOGUE_SEARCH_NOT_READY",HttpStatus.CONFLICT,"Buyer catalogue search is not advertised READY");}
}
