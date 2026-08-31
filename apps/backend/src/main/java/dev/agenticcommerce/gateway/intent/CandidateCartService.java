package dev.agenticcommerce.gateway.intent;

import static dev.agenticcommerce.gateway.catalogue.CatalogueModels.*;
import static dev.agenticcommerce.gateway.intent.BuyerModels.*;

import dev.agenticcommerce.gateway.agentization.service.CanonicalJsonService;
import dev.agenticcommerce.gateway.catalogue.HybridCatalogueRetrievalService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

@Service
public class CandidateCartService {
    private final HybridCatalogueRetrievalService retrieval;private final BuyerRepository repository;
    private final CanonicalJsonService canonical;private final ObjectMapper mapper;
    public CandidateCartService(HybridCatalogueRetrievalService retrieval,BuyerRepository repository,CanonicalJsonService canonical,ObjectMapper mapper){this.retrieval=retrieval;this.repository=repository;this.canonical=canonical;this.mapper=mapper;}
    public MerchantSearch search(BuyerIntent intent,MerchantDiscovery discovery){List<MerchantResult> results=new ArrayList<>();
        for(MerchantCandidate merchant:discovery.eligibleMerchants()){SearchResponse response=retrieval.search(merchant.merchantId(),request(intent.compiled()));
            results.add(new MerchantResult(merchant,response));}
        results.sort(Comparator.<MerchantResult,Boolean>comparing(r->r.merchant().quoteMappingId()!=null).reversed()
                .thenComparing(Comparator.<MerchantResult>comparingDouble(r->r.response().matches().isEmpty()?0:r.response().matches().getFirst().score()).reversed())
                .thenComparing(r->r.merchant().merchantId()));
        return new MerchantSearch(List.copyOf(results));}
    public CandidateCart build(CommerceThread thread,BuyerIntent intent,MerchantDiscovery discovery){MerchantSearch search=search(intent,discovery);
        var prior=repository.priorCartProductIds(thread.buyerActorId(),thread.threadId());
        MerchantResult selected=search.results().stream().filter(r->r.response().matches().stream().anyMatch(h->!prior.contains(h.product().id()))).findFirst().orElseThrow(()->{
            boolean exact=intent.compiled().exactMerchantSku()!=null||intent.compiled().exactGtin()!=null;
            String code=exact&&intent.compiled().substitutionPolicy()==SubstitutionPolicy.UNKNOWN?"EXACT_PRODUCT_NO_MATCH_SUBSTITUTION_UNKNOWN":"NO_SINGLE_MERCHANT";
            return new BuyerException(code,HttpStatus.CONFLICT,"No single READY merchant has a trustworthy grounded match");});
        SearchHit hit=selected.response().matches().stream().filter(h->!prior.contains(h.product().id())).findFirst().orElseThrow();int quantity=intent.compiled().quantity()!=null?intent.compiled().quantity():intent.compiled().people()!=null?Math.min(intent.compiled().people(),10):1;
        List<String> evidence=new ArrayList<>(selected.response().evidence());evidence.add("product:"+hit.product().id());evidence.add("identityGate:"+hit.identityGate());
        CandidateCartItem item=new CandidateCartItem(null,hit.product().id(),hit.product().merchantSku(),hit.product().variant(),quantity,"Grounded deterministic top match; hard filters preserved",List.copyOf(evidence));
        ArrayNode alternatives=mapper.createArrayNode();java.util.stream.Stream.concat(selected.response().matches().stream().skip(1),selected.response().relatedAlternatives().stream())
                .limit(3).forEach(a->{var n=alternatives.addObject();n.put("productId",a.product().id().toString());n.put("merchantSku",a.product().merchantSku());n.put("classification","ALTERNATIVE");});
        var material=mapper.createObjectNode();material.put("threadId",thread.threadId().toString());material.put("intentHash",intent.intentHash());
        material.put("merchantId",selected.merchant().merchantId().toString());material.put("catalogueVersionId",selected.merchant().catalogueVersionId().toString());
        var lines=material.putArray("items");var line=lines.addObject();line.put("productId",item.productId().toString());line.put("merchantSku",item.merchantSku());line.put("variant",item.variant());line.put("quantity",item.quantity());material.set("alternatives",alternatives);
        return repository.createCart(thread,intent,selected.merchant(),List.of(item),List.copyOf(evidence),alternatives,canonical.hash(material));}
    private static SearchRequest request(CompiledIntent i){StringBuilder q=new StringBuilder();if(i.categoryRequest()!=null)q.append(i.categoryRequest());
        if(i.softPreferences()!=null&&i.softPreferences().contains("HIGH_PROTEIN"))q.append(" high protein");if(q.isEmpty())q.append(i.exactVariant()!=null?i.exactVariant():"food");
        return new SearchRequest(q.toString().strip(),i.exactMerchantSku(),i.exactGtin(),null,i.exactVariant(),null,null,
                i.categoryRequest(),null,i.budgetAmountMinor(),i.vegetarian(),i.prohibitedAllergen()==null?null:i.prohibitedAllergen().toLowerCase(java.util.Locale.ROOT),20);}
    public record MerchantResult(MerchantCandidate merchant,SearchResponse response){}
    public record MerchantSearch(List<MerchantResult> results){public List<String> evidenceReferences(){return results.stream().flatMap(r->r.response().evidence().stream()).distinct().limit(64).toList();}}
}
