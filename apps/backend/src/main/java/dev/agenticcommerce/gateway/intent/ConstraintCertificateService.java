package dev.agenticcommerce.gateway.intent;

import static dev.agenticcommerce.gateway.catalogue.CatalogueModels.*;
import static dev.agenticcommerce.gateway.intent.BuyerModels.*;

import dev.agenticcommerce.gateway.agentization.authority.AgentizationAuthorityRepository;
import dev.agenticcommerce.gateway.agentization.service.CanonicalJsonService;
import dev.agenticcommerce.gateway.catalogue.CatalogueRepository;
import dev.agenticcommerce.gateway.catalogue.HybridCatalogueRetrievalService;
import dev.agenticcommerce.gateway.commerce.TransactionModels.AvailabilityRefresh;
import dev.agenticcommerce.gateway.commerce.TransactionModels.EvidenceOutcome;
import dev.agenticcommerce.gateway.commerce.TransactionModels.ServiceabilityEvidence;
import java.time.Instant;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class ConstraintCertificateService {
    private final BuyerRepository repository;private final CatalogueRepository catalogues;
    private final HybridCatalogueRetrievalService retrieval;private final AgentizationAuthorityRepository authority;
    private final CanonicalJsonService canonical;private final ObjectMapper mapper;
    public ConstraintCertificateService(BuyerRepository repository,CatalogueRepository catalogues,
            HybridCatalogueRetrievalService retrieval,AgentizationAuthorityRepository authority,
            CanonicalJsonService canonical,ObjectMapper mapper){this.repository=repository;this.catalogues=catalogues;
        this.retrieval=retrieval;this.authority=authority;this.canonical=canonical;this.mapper=mapper;}
    public ConstraintCertificate evaluate(CommerceThread thread,BuyerIntent intent,CandidateCart cart,MerchantQuote quote){
        var snapshot=authority.findLatestPolicySnapshot(cart.merchantId()).orElse(null);
        return evaluate(thread,intent,cart,quote,null,null,snapshot==null?null:snapshot.policySnapshotId(),false);
    }
    public ConstraintCertificate evaluateExecutable(CommerceThread thread,BuyerIntent intent,CandidateCart cart,
            MerchantQuote quote,AvailabilityRefresh availability,ServiceabilityEvidence serviceability,UUID policySnapshotId){
        if(availability==null||serviceability==null||policySnapshotId==null)throw new IllegalArgumentException("Executable authority evidence is required");
        return evaluate(thread,intent,cart,quote,availability,serviceability,policySnapshotId,true);
    }
    private ConstraintCertificate evaluate(CommerceThread thread,BuyerIntent intent,CandidateCart cart,MerchantQuote quote,
            AvailabilityRefresh availability,ServiceabilityEvidence serviceability,UUID policySnapshotId,boolean executable){Instant now=Instant.now();List<ConstraintResult> results=new ArrayList<>();
        if(intent.compiled().budgetAmountMinor()!=null){ConstraintOutcome outcome=quote.finalAmountMinor()==null?ConstraintOutcome.UNKNOWN:
                quote.finalAmountMinor()<=intent.compiled().budgetAmountMinor()?ConstraintOutcome.PASS:ConstraintOutcome.FAIL;
            ObjectNode req=mapper.createObjectNode().put("maximumAmountMinor",intent.compiled().budgetAmountMinor()).put("currency","INR");
            results.add(result("BUDGET",ConstraintType.USER,req,outcome,false,List.of("intent:"+intent.intentId(),"quote:"+quote.quoteRecordId()+":"+quote.evidenceHash()),now));}
        if(Boolean.TRUE.equals(intent.compiled().vegetarian())){List<ConstraintOutcome> outcomes=cart.items().stream().map(i->gate(retrieval.vegetarianOutcome(cart.merchantId(),cart.catalogueVersionId(),i.productId()))).toList();
            results.add(result("VEGETARIAN",ConstraintType.MERCHANT_PRODUCT,mapper.createObjectNode().put("required",true),reduce(outcomes),false,productRefs(cart,"vegetarian"),now));}
        if(intent.compiled().prohibitedAllergen()!=null){List<ConstraintOutcome> outcomes=cart.items().stream().map(i->{AllergenState state=retrieval.allergen(cart.merchantId(),cart.catalogueVersionId(),i.productId(),intent.compiled().prohibitedAllergen());
                    return state==AllergenState.PRESENT?ConstraintOutcome.FAIL:state==AllergenState.ABSENT?ConstraintOutcome.PASS:ConstraintOutcome.UNKNOWN;}).toList();
            ObjectNode req=mapper.createObjectNode().put("allergen",intent.compiled().prohibitedAllergen()).put("requirement","PROHIBITED");
            results.add(result("ALLERGEN_"+intent.compiled().prohibitedAllergen(),ConstraintType.SAFETY_COMPLIANCE,req,reduce(outcomes),true,productRefs(cart,"allergen"),now));}
        addIdentity(results,cart,"CATEGORY",intent.compiled().categoryRequest(),Product::category,now);
        addIdentity(results,cart,"MERCHANT_SKU",intent.compiled().exactMerchantSku(),Product::merchantSku,now);
        addIdentity(results,cart,"GTIN",intent.compiled().exactGtin(),Product::gtin,now);
        addIdentity(results,cart,"BRAND",intent.compiled().exactBrand(),Product::brand,now);
        addIdentity(results,cart,"VARIANT",intent.compiled().exactVariant(),Product::variant,now);
        addIdentity(results,cart,"SIZE_STORAGE",intent.compiled().exactSizeStorage(),Product::sizeStorage,now);
        addIdentity(results,cart,"COLOUR",intent.compiled().exactColour(),Product::colour,now);
        List<ConstraintOutcome> identities=cart.items().stream().map(i->{IdentityOutcome identity=catalogues.latestIdentity(cart.merchantId(),cart.catalogueVersionId(),i.productId());
            return identity==IdentityOutcome.EXACT?ConstraintOutcome.PASS:identity==IdentityOutcome.CONFLICT?ConstraintOutcome.UNKNOWN:ConstraintOutcome.UNKNOWN;}).toList();
        results.add(result("EXACT_IDENTITY",ConstraintType.MERCHANT_PRODUCT,mapper.createObjectNode().put("required","EXACT"),reduce(identities),false,productRefs(cart,"identity"),now));
        ConstraintOutcome quoteValidity=quote.cartId().equals(cart.cartId())&&quote.cartHash().equals(cart.cartHash())&&quote.expiresAt().isAfter(now)?ConstraintOutcome.PASS:ConstraintOutcome.FAIL;
        results.add(result("QUOTE_VALIDITY",ConstraintType.BUYER_AUTHORITY,mapper.createObjectNode().put("cartHash",cart.cartHash()).put("mustBeUnexpired",true),quoteValidity,false,List.of("quote:"+quote.quoteRecordId()+":"+quote.evidenceHash()),now));
        if(executable){
            results.add(result("AUTHORITATIVE_AVAILABILITY",ConstraintType.MERCHANT_PRODUCT,
                    mapper.createObjectNode().put("requestedCartHash",cart.cartHash()),outcome(availability.outcome()),false,
                    List.of("availability:"+availability.availabilityRefreshId()+":"+availability.evidenceHash()),now));
            results.add(result("SERVICEABILITY",ConstraintType.BUYER_AUTHORITY,
                    mapper.createObjectNode().put("required","PASS"),outcome(serviceability.outcome()),false,
                    List.of("serviceability:"+serviceability.serviceabilityEvidenceId()+":"+serviceability.evidenceHash()),now));
        }
        ConstraintOutcome overall=reduce(results.stream().map(ConstraintResult::result).toList());var freshness=mapper.createObjectNode();freshness.put("catalogueVersionId",cart.catalogueVersionId().toString());freshness.put("quoteObservedAt",quote.observedAt().toString());freshness.put("quoteExpiresAt",quote.expiresAt().toString());
        if(executable){freshness.put("availabilityObservedAt",availability.observedAt().toString());freshness.put("availabilityExpiresAt",availability.expiresAt().toString());freshness.put("serviceabilityObservedAt",serviceability.observedAt().toString());if(serviceability.expiresAt()!=null)freshness.put("serviceabilityExpiresAt",serviceability.expiresAt().toString());else freshness.putNull("serviceabilityExpiresAt");}
        List<String> refs=results.stream().flatMap(r->r.evidenceReferences().stream()).distinct().limit(128).toList();var material=mapper.createObjectNode();material.put("intentHash",intent.intentHash());material.put("cartHash",cart.cartHash());material.put("quoteHash",quote.evidenceHash());material.put("overall",overall.name());material.set("results",mapper.valueToTree(results));material.set("freshness",freshness);
        if(executable){material.put("availabilityHash",availability.evidenceHash());material.put("serviceabilityHash",serviceability.evidenceHash());material.put("policySnapshotId",policySnapshotId.toString());}
        return repository.createCertificate(thread,intent,cart,quote,policySnapshotId,
                executable?availability.availabilityRefreshId():null,executable?availability.evidenceHash():null,
                executable?serviceability.serviceabilityEvidenceId():null,executable?serviceability.evidenceHash():null,
                executable,freshness,refs,overall,canonical.hash(material),now,results);}
    private ConstraintResult result(String key,ConstraintType type,ObjectNode requirement,ConstraintOutcome outcome,boolean safety,List<String> refs,Instant at){return new ConstraintResult(null,key,type,requirement,outcome,safety,refs,at);}
    private void addIdentity(List<ConstraintResult> results,CandidateCart cart,String key,String requested,Function<Product,String> field,Instant now){if(requested==null||requested.isBlank())return;
        List<ConstraintOutcome> outcomes=cart.items().stream().map(item->catalogues.findProduct(cart.merchantId(),cart.catalogueVersionId(),item.productId())
                .map(product->compare(requested,field.apply(product))).orElse(ConstraintOutcome.UNKNOWN)).toList();
        results.add(result(key,ConstraintType.MERCHANT_PRODUCT,mapper.createObjectNode().put("required",requested),reduce(outcomes),false,productRefs(cart,key.toLowerCase(Locale.ROOT)),now));}
    private static ConstraintOutcome compare(String required,String actual){if(actual==null||actual.isBlank())return ConstraintOutcome.UNKNOWN;return normalize(required).equals(normalize(actual))?ConstraintOutcome.PASS:ConstraintOutcome.FAIL;}
    private static String normalize(String value){return Normalizer.normalize(value,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+"," ").strip();}
    private static ConstraintOutcome gate(GateOutcome value){return value==GateOutcome.PASS?ConstraintOutcome.PASS:value==GateOutcome.FAIL?ConstraintOutcome.FAIL:ConstraintOutcome.UNKNOWN;}
    private static ConstraintOutcome outcome(EvidenceOutcome value){return value==EvidenceOutcome.PASS?ConstraintOutcome.PASS:value==EvidenceOutcome.FAIL?ConstraintOutcome.FAIL:ConstraintOutcome.UNKNOWN;}
    private static ConstraintOutcome reduce(List<ConstraintOutcome> values){if(values.stream().anyMatch(v->v==ConstraintOutcome.FAIL))return ConstraintOutcome.FAIL;if(values.stream().anyMatch(v->v==ConstraintOutcome.UNKNOWN))return ConstraintOutcome.UNKNOWN;return ConstraintOutcome.PASS;}
    private static List<String> productRefs(CandidateCart cart,String kind){return cart.items().stream().map(i->"product:"+i.productId()+":"+kind).toList();}
}
