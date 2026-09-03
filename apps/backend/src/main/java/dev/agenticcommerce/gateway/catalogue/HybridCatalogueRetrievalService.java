package dev.agenticcommerce.gateway.catalogue;

import static dev.agenticcommerce.gateway.catalogue.CatalogueModels.*;

import dev.agenticcommerce.gateway.agentization.service.CanonicalJsonService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class HybridCatalogueRetrievalService {
    private final CatalogueRepository repository;
    private final CatalogueService catalogues;
    private final EmbeddingProvider embeddings;
    private final CanonicalJsonService canonical;
    private final ObjectMapper mapper;
    public HybridCatalogueRetrievalService(CatalogueRepository repository,CatalogueService catalogues,
            EmbeddingProvider embeddings,CanonicalJsonService canonical,ObjectMapper mapper){this.repository=repository;
        this.catalogues=catalogues;this.embeddings=embeddings;this.canonical=canonical;this.mapper=mapper;}

    public SearchResponse search(UUID merchantId,SearchRequest request){
        validate(request);var version=catalogues.requirePublished(merchantId);String query=normalizedQuery(request);
        List<CatalogueRepository.ScoredProduct> lexical=repository.lexicalCandidates(merchantId,version.id(),query,
                blank(request.merchantSku()),blank(request.gtin()),blank(request.category()),request.minimumPriceMinor(),request.maximumPriceMinor(),RetrievalThresholds.MAX_CANDIDATES);
        boolean vectorFallback=false;List<CatalogueRepository.VectorScore> vectors=List.of();
        if(!query.isBlank())try{vectors=repository.vectorCandidates(merchantId,version.id(),embeddings.embed(query),RetrievalThresholds.MAX_CANDIDATES);}
        catch(RuntimeException failure){vectorFallback=true;}
        Map<UUID,MutableCandidate> candidates=new LinkedHashMap<>();
        for(var c:lexical)candidates.put(c.product().id(),new MutableCandidate(c.product(),c.exact(),c.fts(),c.trigram(),0));
        for(var v:vectors){var c=candidates.get(v.productId());if(c!=null)c.vector=v.score();else repository.findProduct(merchantId,version.id(),v.productId())
                .filter(Product::active).ifPresent(p->candidates.put(p.id(),new MutableCandidate(p,0,0,0,v.score())));}
        List<SearchHit> ranked=new ArrayList<>();
        for(var c:candidates.values()){
            GateOutcome gate=identityGate(merchantId,version.id(),c.product,request);if(gate==GateOutcome.FAIL)continue;
            if(Boolean.TRUE.equals(request.vegetarian())&&!vegetarian(merchantId,version.id(),c.product.id()))continue;
            AllergenState allergen=allergen(merchantId,version.id(),c.product.id(),request.prohibitedAllergen());
            if(request.prohibitedAllergen()!=null&&allergen!=AllergenState.ABSENT)continue;
            boolean authoritativeExact=c.exact>0||exactBrandVariant(request,c.product,gate);
            double completeness=completeness(c.product);double score=authoritativeExact?1.0:clamp(c.fts)*.35+clamp(c.trigram)*.30+clamp(c.vector)*.30+completeness*.05;
            Map<String,Double> evidence=new LinkedHashMap<>();evidence.put("exact",c.exact);evidence.put("fts",c.fts);evidence.put("trigram",c.trigram);evidence.put("vector",c.vector);evidence.put("completeness",completeness);
            ranked.add(new SearchHit(c.product,score,gate,Map.copyOf(evidence),allergen));
        }
        ranked.sort(Comparator.comparingDouble(SearchHit::score).reversed().thenComparing(h->h.product().merchantSku()));
        boolean explicit=request.merchantSku()!=null||request.gtin()!=null;List<SearchHit> matches=ranked.stream()
                .filter(h->h.score()>=RetrievalThresholds.VALID_MATCH&&(!explicit||h.score()==1.0)).limit(limit(request)).toList();
        List<SearchHit> related=ranked.stream().filter(h->!matches.contains(h)&&h.score()>=RetrievalThresholds.RELATED_ALTERNATIVE).limit(limit(request)).toList();
        MatchClassification classification=!matches.isEmpty()?MatchClassification.VALID_MATCH:
                !related.isEmpty()?MatchClassification.RELATED_ALTERNATIVES:MatchClassification.NO_TRUSTWORTHY_MATCH;
        if(explicit&&matches.isEmpty())classification=MatchClassification.NO_TRUSTWORTHY_MATCH;
        var evidence=List.of("catalogue:"+version.id()+":"+version.contentHash(),"ranker:hybrid-v1",
                "thresholds:valid="+RetrievalThresholds.VALID_MATCH+",related="+RetrievalThresholds.RELATED_ALTERNATIVE,
                vectorFallback?"vector:FAILED_LEXICAL_FALLBACK":"vector:READY");
        var queryEvidence=mapper.valueToTree(request);var refs=mapper.createArrayNode();matches.forEach(h->refs.add("product:"+h.product().id()));
        String type=explicit?"EXACT_PRODUCT_RETRIEVAL":classification==MatchClassification.NO_TRUSTWORTHY_MATCH?"NO_MATCH":"IDENTITY_GATE";
        repository.insertEvidence(merchantId,version.id(),type,matches.isEmpty()&&explicit?"FAIL":"PASS",queryEvidence,refs,canonical.hash(queryEvidence));
        return new SearchResponse(classification,matches,related,vectorFallback,"v"+version.version()+":"+version.contentHash(),evidence);
    }

    public SearchResponse exact(UUID merchantId,String sku,String gtin){return search(merchantId,new SearchRequest("",sku,gtin,null,null,null,null,null,null,null,null,null,1));}

    GateOutcome identityGate(UUID merchantId,UUID versionId,Product p,SearchRequest r){
        if(r.merchantSku()!=null&&!p.merchantSku().equalsIgnoreCase(r.merchantSku()))return GateOutcome.FAIL;
        if(r.gtin()!=null&&!r.gtin().equals(p.gtin()))return GateOutcome.FAIL;
        if(mismatch(r.category(),p.category())||mismatch(r.brand(),p.brand())||mismatch(r.variant(),p.variant())||mismatch(r.sizeStorage(),p.sizeStorage())||mismatch(r.colour(),p.colour()))return GateOutcome.FAIL;
        if(r.minimumPriceMinor()!=null&&(p.priceMinor()==null||p.priceMinor()<r.minimumPriceMinor()))return GateOutcome.FAIL;
        if(r.maximumPriceMinor()!=null&&(p.priceMinor()==null||p.priceMinor()>r.maximumPriceMinor()))return GateOutcome.FAIL;
        if(r.merchantSku()==null&&r.gtin()==null)return repository.latestIdentity(merchantId,versionId,p.id())==IdentityOutcome.CONFLICT?GateOutcome.UNKNOWN:GateOutcome.PASS;
        return repository.latestIdentity(merchantId,versionId,p.id())==IdentityOutcome.CONFLICT?GateOutcome.FAIL:GateOutcome.PASS;
    }

    public AllergenState allergen(UUID merchantId,UUID versionId,UUID productId,String prohibited){
        if(prohibited==null||prohibited.isBlank())return AllergenState.UNKNOWN;
        var facts=repository.facts(merchantId,versionId,productId,"ALLERGEN");if(facts.isEmpty())return AllergenState.UNKNOWN;
        String highest=facts.stream().anyMatch(f->"PRIMARY".equals(f.authority()))?"PRIMARY":"SECONDARY";
        var authoritative=facts.stream().filter(f->highest.equals(f.authority())).toList();
        if(authoritative.stream().anyMatch(f->"CONFLICT".equals(f.state())))return AllergenState.CONFLICT;
        if(authoritative.stream().anyMatch(f->"STALE".equals(f.state())||(f.expiresAt()!=null&&!f.expiresAt().isAfter(Instant.now()))))return AllergenState.UNKNOWN;
        String target=CatalogueService.normalizeText(prohibited);
        var targetStatuses=authoritative.stream().filter(f->factAllergen(f.value()).equals(target))
                .map(f->factStatus(f.value())).distinct().toList();
        if(targetStatuses.size()>1)return AllergenState.CONFLICT;
        if(authoritative.stream().anyMatch(f->factAllergen(f.value()).equals(target)&&factStatus(f.value()).equals("PRESENT")))return AllergenState.PRESENT;
        if(authoritative.stream().anyMatch(f->factAllergen(f.value()).equals(target)&&factStatus(f.value()).equals("ABSENT")))return AllergenState.ABSENT;
        // A list of present allergens never proves absence of an unlisted allergen.
        return AllergenState.UNKNOWN;
    }

    public GateOutcome vegetarianOutcome(UUID merchantId,UUID versionId,UUID productId){
        var facts=repository.facts(merchantId,versionId,productId,"VEGETARIAN");
        if(facts.isEmpty())return GateOutcome.UNKNOWN;
        String highest=facts.stream().anyMatch(f->"PRIMARY".equals(f.authority()))?"PRIMARY":"SECONDARY";
        var selected=facts.stream().filter(f->highest.equals(f.authority())).toList();
        if(selected.stream().anyMatch(f->!"ACTIVE".equals(f.state())||(f.expiresAt()!=null&&!f.expiresAt().isAfter(Instant.now()))))return GateOutcome.UNKNOWN;
        var values=selected.stream().map(f->f.value().asBoolean()).distinct().toList();
        if(values.size()!=1)return GateOutcome.UNKNOWN;
        return values.getFirst()?GateOutcome.PASS:GateOutcome.FAIL;
    }

    private boolean vegetarian(UUID merchantId,UUID versionId,UUID productId){return vegetarianOutcome(merchantId,versionId,productId)==GateOutcome.PASS;}
    private static double completeness(Product p){int present=0;if(p.brand()!=null)present++;if(p.variant()!=null)present++;if(p.sizeStorage()!=null)present++;if(p.category()!=null)present++;if(p.description()!=null)present++;return present/5.0;}
    private static boolean exactBrandVariant(SearchRequest request,Product product,GateOutcome gate){return gate==GateOutcome.PASS
            &&request.brand()!=null&&!request.brand().isBlank()&&request.variant()!=null&&!request.variant().isBlank()
            &&!mismatch(request.brand(),product.brand())&&!mismatch(request.variant(),product.variant());}
    private static double clamp(double v){return Math.max(0,Math.min(1,v));}
    private static boolean mismatch(String requested,String actual){return requested!=null&&!requested.isBlank()&&(actual==null||!CatalogueService.normalizeText(requested).equals(CatalogueService.normalizeText(actual)));}
    private static String factAllergen(tools.jackson.databind.JsonNode value){return CatalogueService.normalizeText(value.isObject()?value.path("allergen").asText():value.asText());}
    private static String factStatus(tools.jackson.databind.JsonNode value){return value.isObject()?value.path("status").asText("PRESENT").toUpperCase(Locale.ROOT):"PRESENT";}
    private static int limit(SearchRequest r){return Math.min(Math.max(r.limit(),1),RetrievalThresholds.MAX_RESULTS);}
    private static String blank(String v){return v==null||v.isBlank()?null:v.strip();}
    private static String normalizedQuery(SearchRequest r){String q=blank(r.query());if(q!=null)return CatalogueService.normalizeText(q);if(r.merchantSku()!=null)return CatalogueService.normalizeText(r.merchantSku());if(r.gtin()!=null)return r.gtin();return "";}
    private static void validate(SearchRequest r){if(r==null)throw new IllegalArgumentException("Search request is required");if((r.query()==null||r.query().isBlank())&&(r.merchantSku()==null||r.merchantSku().isBlank())&&(r.gtin()==null||r.gtin().isBlank()))throw new IllegalArgumentException("query, merchantSku, or gtin is required");
        if(r.query()!=null&&r.query().length()>512)throw new IllegalArgumentException("query exceeds 512 characters");if(r.minimumPriceMinor()!=null&&r.minimumPriceMinor()<0||r.maximumPriceMinor()!=null&&r.maximumPriceMinor()<0)throw new IllegalArgumentException("price filters cannot be negative");}
    private static final class MutableCandidate{final Product product;final double exact,fts,trigram;double vector;MutableCandidate(Product p,double e,double f,double t,double v){product=p;exact=e;fts=f;trigram=t;vector=v;}}
}
