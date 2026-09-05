package dev.agenticcommerce.gateway.intent;

import static dev.agenticcommerce.gateway.catalogue.CatalogueModels.*;
import static dev.agenticcommerce.gateway.intent.BuyerModels.*;

import dev.agenticcommerce.gateway.agentization.service.CanonicalJsonService;
import dev.agenticcommerce.gateway.catalogue.CatalogueModels.Product;
import dev.agenticcommerce.gateway.catalogue.CatalogueRepository;
import dev.agenticcommerce.gateway.catalogue.HybridCatalogueRetrievalService;
import dev.agenticcommerce.gateway.intent.BuyerDecisionProvider.CandidateDecisionContext;
import dev.agenticcommerce.gateway.intent.BuyerDecisionProvider.CandidateOption;
import dev.agenticcommerce.gateway.intent.BuyerDecisionProvider.CandidateSelection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

@Service
public class CandidateCartService {
    private static final Logger log=LoggerFactory.getLogger(CandidateCartService.class);
    private static final int MAX_REASONING_CANDIDATES=32;
    private final HybridCatalogueRetrievalService retrieval;private final BuyerRepository repository;
    private final CatalogueRepository catalogues;private final BuyerDecisionProvider decisions;
    private final CanonicalJsonService canonical;private final ObjectMapper mapper;
    public CandidateCartService(HybridCatalogueRetrievalService retrieval,BuyerRepository repository,
            CatalogueRepository catalogues,BuyerDecisionProvider decisions,CanonicalJsonService canonical,ObjectMapper mapper){
        this.retrieval=retrieval;this.repository=repository;this.catalogues=catalogues;this.decisions=decisions;this.canonical=canonical;this.mapper=mapper;}
    public MerchantSearch search(BuyerIntent intent,MerchantDiscovery discovery){long started=System.nanoTime();List<MerchantResult> results=new ArrayList<>();
        for(MerchantCandidate merchant:discovery.eligibleMerchants()){SearchResponse response=retrieval.search(merchant.merchantId(),request(intent.compiled()));
            results.add(new MerchantResult(merchant,response));}
        results.sort(Comparator.<MerchantResult,Boolean>comparing(r->r.merchant().quoteMappingId()!=null).reversed()
                .thenComparing(Comparator.<MerchantResult>comparingDouble(r->r.response().matches().isEmpty()?0:r.response().matches().getFirst().score()).reversed())
                .thenComparing(r->r.merchant().merchantId()));
        log.info("Grounded catalogue retrieval completed eligibleMerchants={} validCandidates={} elapsedMs={}",
                discovery.eligibleMerchants().size(),results.stream().mapToInt(result->result.response().matches().size()).sum(),elapsedMillis(started));
        return new MerchantSearch(List.copyOf(results));}
    public CandidateCart build(CommerceThread thread,BuyerIntent intent,MerchantDiscovery discovery){MerchantSearch search=search(intent,discovery);
        var prior=repository.priorCartProductIds(thread.buyerActorId(),thread.threadId());
        List<GroundedCandidate> grounded=search.results().stream().flatMap(result->result.response().matches().stream()
                .filter(hit->!prior.contains(hit.product().id())).map(hit->new GroundedCandidate(result.merchant(),hit,result.response().evidence())))
                .limit(MAX_REASONING_CANDIDATES).toList();
        if(grounded.isEmpty()){
            boolean exact=intent.compiled().exactMerchantSku()!=null||intent.compiled().exactGtin()!=null;
            String code=exact&&intent.compiled().substitutionPolicy()==SubstitutionPolicy.UNKNOWN?"EXACT_PRODUCT_NO_MATCH_SUBSTITUTION_UNKNOWN":"NO_SINGLE_MERCHANT";
            throw new BuyerException(code,HttpStatus.CONFLICT,"No single READY merchant has a trustworthy grounded match");}
        CandidateSelection selection=select(intent,grounded,search.evidenceReferences());
        GroundedCandidate selected=grounded.stream().filter(candidate->candidate.hit().product().id().equals(selection.productId()))
                .findFirst().orElseThrow(()->new BuyerException("BUYER_CANDIDATE_DECISION_INVALID",HttpStatus.UNPROCESSABLE_ENTITY,"Candidate decision selected an ID outside the grounded set"));
        SearchHit hit=revalidatedHit(intent,selected);Product product=catalogues.findProduct(selected.merchant().merchantId(),
                selected.merchant().catalogueVersionId(),selection.productId()).filter(Product::active)
                .orElseThrow(()->new BuyerException("SELECTED_PRODUCT_NOT_AUTHORITATIVE",HttpStatus.CONFLICT,"Selected product is no longer active in the authoritative catalogue"));
        if(!product.equals(hit.product()))throw new BuyerException("SELECTED_PRODUCT_CHANGED",HttpStatus.CONFLICT,"Selected product changed during authoritative revalidation");
        int quantity=intent.compiled().quantity()!=null?intent.compiled().quantity():intent.compiled().people()!=null?Math.min(intent.compiled().people(),10):1;
        List<String> evidence=new ArrayList<>(selected.evidence());evidence.addAll(selection.evidenceReferences());evidence.add("product:"+product.id());evidence.add("identityGate:"+hit.identityGate());
        List<String> boundedEvidence=evidence.stream().distinct().limit(64).toList();
        CandidateCartItem item=new CandidateCartItem(null,product.id(),product.merchantSku(),product.variant(),quantity,selection.conciseRationale(),boundedEvidence);
        ArrayNode alternatives=mapper.createArrayNode();grounded.stream().filter(candidate->!candidate.hit().product().id().equals(product.id()))
                .limit(3).forEach(a->{var n=alternatives.addObject();n.put("productId",a.product().id().toString());n.put("merchantSku",a.product().merchantSku());n.put("classification","ALTERNATIVE");});
        var material=mapper.createObjectNode();material.put("threadId",thread.threadId().toString());material.put("intentHash",intent.intentHash());
        material.put("merchantId",selected.merchant().merchantId().toString());material.put("catalogueVersionId",selected.merchant().catalogueVersionId().toString());
        var lines=material.putArray("items");var line=lines.addObject();line.put("productId",item.productId().toString());line.put("merchantSku",item.merchantSku());line.put("variant",item.variant());line.put("quantity",item.quantity());material.set("alternatives",alternatives);
        return repository.createCart(thread,intent,selected.merchant(),List.of(item),boundedEvidence,alternatives,canonical.hash(material));}
    private CandidateSelection select(BuyerIntent intent,List<GroundedCandidate> grounded,List<String> searchEvidence){
        boolean exact=present(intent.compiled().exactMerchantSku())||present(intent.compiled().exactGtin())
                ||present(intent.compiled().exactBrand())&&present(intent.compiled().exactVariant());
        List<String> suppliedEvidence=java.util.stream.Stream.concat(searchEvidence.stream(),grounded.stream()
                .map(candidate->"product:"+candidate.hit().product().id())).distinct().limit(64).toList();
        if(exact||grounded.size()==1){GroundedCandidate selected=grounded.getFirst();
            log.info("Candidate selection completed mode=DETERMINISTIC reason={} candidateCount={} elapsedMs=0",
                    exact?"EXACT_IDENTITY":"SINGLE_CANDIDATE",grounded.size());
            return new CandidateSelection(selected.hit().product().id(),exact?"Authoritative exact identity match":"Only grounded candidate satisfied deterministic filters",
                    suppliedEvidence,"DETERMINISTIC_CANDIDATE_SELECTION","candidate-selection-v1");}
        List<CandidateOption> options=grounded.stream().map(this::option).toList();CandidateDecisionContext context=
                new CandidateDecisionContext(options,intent.compiled().softPreferences(),suppliedEvidence);
        long started=System.nanoTime();String feedback=null;
        for(int attempt=1;attempt<=2;attempt++)try{CandidateSelection selected=decisions.chooseCandidate(context,feedback);validateCandidateSelection(selected,context);
            log.info("Candidate selection completed mode=MODEL attempt={} candidateCount={} elapsedMs={} repair={}",
                    attempt,options.size(),elapsedMillis(started),attempt>1);return selected;
        }catch(BuyerException e){if(e.code().equals("AI_PROVIDER_UNAVAILABLE")||e.code().equals("AI_PROVIDER_RATE_LIMITED"))throw e;feedback=bounded(e.getMessage());}
        catch(RuntimeException e){feedback="Candidate output failed deterministic validation";}
        throw new BuyerException("BUYER_CANDIDATE_DECISION_INVALID",HttpStatus.UNPROCESSABLE_ENTITY,"Candidate decision remained invalid after one retry");}
    private SearchHit revalidatedHit(BuyerIntent intent,GroundedCandidate selected){return retrieval.search(selected.merchant().merchantId(),request(intent.compiled())).matches().stream()
            .filter(candidate->candidate.product().id().equals(selected.hit().product().id())).findFirst()
            .orElseThrow(()->new BuyerException("SELECTED_PRODUCT_REVALIDATION_FAILED",HttpStatus.CONFLICT,"Selected product no longer satisfies authoritative deterministic filters"));}
    private CandidateOption option(GroundedCandidate candidate){Product p=candidate.hit().product();return new CandidateOption(p.id(),p.merchantId(),p.canonicalName(),p.brand(),p.variant(),p.sizeStorage(),p.colour(),p.category(),p.priceMinor(),p.currency(),candidate.hit().score());}
    static void validateCandidateSelection(CandidateSelection selection,CandidateDecisionContext context){if(selection==null||selection.productId()==null)throw new IllegalArgumentException("Candidate product ID is required");
        if(context.candidates().stream().noneMatch(candidate->candidate.productId().equals(selection.productId())))throw new IllegalArgumentException("Candidate product ID is outside the supplied set");
        if(selection.conciseRationale()==null||selection.conciseRationale().isBlank()||selection.conciseRationale().length()>512)throw new IllegalArgumentException("Candidate rationale is invalid");
        if(selection.evidenceReferences()==null||selection.evidenceReferences().size()>32||!Set.copyOf(context.evidenceReferences()).containsAll(selection.evidenceReferences()))throw new IllegalArgumentException("Candidate evidence references are outside the supplied set");}
    private static SearchRequest request(CompiledIntent i){StringBuilder q=new StringBuilder();if(i.categoryRequest()!=null)q.append(i.categoryRequest());
        if(i.exactBrand()!=null)q.append(' ').append(i.exactBrand());if(i.exactVariant()!=null)q.append(' ').append(i.exactVariant());
        if(i.exactSizeStorage()!=null)q.append(' ').append(i.exactSizeStorage());if(i.exactColour()!=null)q.append(' ').append(i.exactColour());
        if(i.softPreferences()!=null&&i.softPreferences().contains("HIGH_PROTEIN"))q.append(" high protein");
        if(q.isEmpty()&&i.goal()==IntentGoal.PURCHASE_FOOD)q.append("food");
        if(q.isEmpty())q.append(i.exactMerchantSku()!=null?i.exactMerchantSku():i.exactGtin());
        String hardCategory=i.materialFields().stream().anyMatch(field->"CATEGORY".equals(field.field())&&field.classification()!=ConstraintClassification.SOFT)?i.categoryRequest():null;
        return new SearchRequest(q.toString().strip(),i.exactMerchantSku(),i.exactGtin(),i.exactBrand(),i.exactVariant(),i.exactSizeStorage(),i.exactColour(),
                hardCategory,null,i.budgetAmountMinor(),i.vegetarian(),i.prohibitedAllergen()==null?null:i.prohibitedAllergen().toLowerCase(java.util.Locale.ROOT),20);}
    public record MerchantResult(MerchantCandidate merchant,SearchResponse response){}
    public record MerchantSearch(List<MerchantResult> results){public List<String> evidenceReferences(){return results.stream().flatMap(r->r.response().evidence().stream()).distinct().limit(64).toList();}}
    private record GroundedCandidate(MerchantCandidate merchant,SearchHit hit,List<String> evidence){Product product(){return hit.product();}}
    private static boolean present(String value){return value!=null&&!value.isBlank();}
    private static String bounded(String value){return value==null?"invalid candidate decision":value.length()<=400?value:value.substring(0,400);}
    private static long elapsedMillis(long startedNanos){return (System.nanoTime()-startedNanos)/1_000_000L;}
}
