package dev.agenticcommerce.gateway.intent;

import static dev.agenticcommerce.gateway.intent.BuyerModels.*;

import dev.agenticcommerce.gateway.agentization.service.CanonicalJsonService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class MerchantDiscoveryService {
    private final BuyerRepository repository;private final CanonicalJsonService canonical;private final ObjectMapper mapper;
    public MerchantDiscoveryService(BuyerRepository repository,CanonicalJsonService canonical,ObjectMapper mapper){this.repository=repository;this.canonical=canonical;this.mapper=mapper;}
    public GateResult preRetrievalGate(BuyerIntent intent){CompiledIntent v=intent.compiled();
        if(v.goal()!=IntentGoal.PURCHASE_PRODUCT&&v.goal()!=IntentGoal.PURCHASE_FOOD)return new GateResult(false,"UNSUPPORTED_GOAL",false);
        if(v.currency()!=null&&!"INR".equals(v.currency()))return new GateResult(false,"UNSUPPORTED_CURRENCY",false);
        if(v.ambiguityState()==AmbiguityState.AMBIGUOUS)return new GateResult(false,"CRITICAL_FIELD_AMBIGUOUS",true);
        if(v.prohibitedAllergen()!=null&&v.materialFields().stream().noneMatch(f->f.field().equalsIgnoreCase("ALLERGEN")&&f.classification()==ConstraintClassification.HARD_SAFETY&&f.ambiguity()==AmbiguityState.CLEAR))
            return new GateResult(false,"SAFETY_CONSTRAINT_INVALID",true);
        return new GateResult(true,"PRE_RETRIEVAL_GATE_PASS",false);}
    public MerchantDiscovery discover(CommerceThread thread,BuyerIntent intent){GateResult gate=preRetrievalGate(intent);if(!gate.allowed())
        throw new BuyerException(gate.reasonCode(),gate.clarifiable()?HttpStatus.CONFLICT:HttpStatus.UNPROCESSABLE_ENTITY,"Pre-retrieval gate denied catalogue access");
        List<MerchantCandidate> candidates=repository.eligibleMerchants();DiscoveryOutcome outcome=candidates.isEmpty()?DiscoveryOutcome.NO_ELIGIBLE_MERCHANT:DiscoveryOutcome.ELIGIBLE;
        List<String> required=List.of("SEARCH_PRODUCTS:READY:ADVERTISED");
        List<String> refs=candidates.stream().flatMap(c->java.util.stream.Stream.of("manifest:"+c.manifestId()+":v"+c.manifestVersion(),"catalogue:"+c.catalogueVersionId()+":"+c.catalogueVersion(),c.quoteMappingId()==null?"GET_QUOTE:UNRESOLVED":"GET_QUOTE:READY:"+c.quoteMappingId())).toList();
        var material=mapper.createObjectNode();material.put("threadId",thread.threadId().toString());material.put("intentHash",intent.intentHash());
        material.set("required",mapper.valueToTree(required));material.set("eligible",mapper.valueToTree(candidates));material.put("outcome",outcome.name());
        return repository.createDiscovery(thread,intent,outcome,required,candidates,refs,canonical.hash(material));}
    public record GateResult(boolean allowed,String reasonCode,boolean clarifiable){}
}
