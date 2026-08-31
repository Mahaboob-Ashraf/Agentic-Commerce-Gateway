package dev.agenticcommerce.gateway.catalogue;

import static dev.agenticcommerce.gateway.catalogue.CatalogueModels.*;

import dev.agenticcommerce.gateway.identity.authentication.VerifiedActorPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/merchants/{merchantId}/catalogue")
public class CatalogueApiController {
    private final CatalogueService catalogues;
    public CatalogueApiController(CatalogueService catalogues){this.catalogues=catalogues;}
    @PostMapping("/ingestions") @ResponseStatus(HttpStatus.CREATED)
    IngestionResult ingest(@AuthenticationPrincipal VerifiedActorPrincipal principal,@PathVariable UUID merchantId,
            @Valid @RequestBody IngestRequest request){return catalogues.ingest(principal.actorId(),merchantId,request.format(),request.payload());}
    @GetMapping("/versions") List<CatalogueVersion> versions(@AuthenticationPrincipal VerifiedActorPrincipal p,@PathVariable UUID merchantId){return catalogues.versions(p.actorId(),merchantId);}
    @GetMapping("/versions/{versionId}/products") List<Product> products(@AuthenticationPrincipal VerifiedActorPrincipal p,@PathVariable UUID merchantId,@PathVariable UUID versionId,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) int limit){return catalogues.products(p.actorId(),merchantId,versionId,limit);}
    @GetMapping("/health") CatalogueHealth health(@AuthenticationPrincipal VerifiedActorPrincipal p,@PathVariable UUID merchantId){return catalogues.health(p.actorId(),merchantId);}
    @PostMapping("/versions/{versionId}/products/{productId}/enrichment")
    CatalogueService.EnrichmentStatus enrich(@AuthenticationPrincipal VerifiedActorPrincipal p,@PathVariable UUID merchantId,
            @PathVariable UUID versionId,@PathVariable UUID productId){return catalogues.enrich(p.actorId(),merchantId,versionId,productId);}
    public record IngestRequest(@NotBlank String format,@NotBlank @Size(max=1_000_000) String payload){}
}
