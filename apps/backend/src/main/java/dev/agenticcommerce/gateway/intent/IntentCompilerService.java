package dev.agenticcommerce.gateway.intent;

import static dev.agenticcommerce.gateway.intent.BuyerModels.*;

import dev.agenticcommerce.gateway.agentization.service.CanonicalJsonService;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class IntentCompilerService {
    private final BuyerIntentCompiler compiler;private final CanonicalJsonService canonical;private final ObjectMapper mapper;
    public IntentCompilerService(BuyerIntentCompiler compiler,CanonicalJsonService canonical,ObjectMapper mapper){this.compiler=compiler;this.canonical=canonical;this.mapper=mapper;}
    public Compiled compile(ThreadMessage message){String feedback=null;
        for(int attempt=1;attempt<=2;attempt++)try{CompiledIntent value=compiler.compile(message,feedback);validate(message,value);
            var material=mapper.valueToTree(value);return new Compiled(value,canonical.hash(material),canonical.hash(material));
        }catch(BuyerException e){if(e.code().equals("INTENT_COMPILER_UNAVAILABLE"))throw e;feedback=bounded(e.getMessage());}
         catch(RuntimeException e){feedback=bounded(e.getMessage());}
        throw new BuyerException("INVALID_BUYER_INTENT",HttpStatus.UNPROCESSABLE_ENTITY,"Intent compiler output remained invalid after one retry");}
    private static void validate(ThreadMessage message,CompiledIntent v){if(v==null||v.goal()!=IntentGoal.PURCHASE_FOOD)invalid("Unsupported intent goal");
        if(v.substitutionPolicy()==null||v.ambiguityState()==null)invalid("Substitution and ambiguity states are required");
        if(v.budgetAmountMinor()!=null&&v.budgetAmountMinor()<0)invalid("Budget cannot be negative");
        if(v.currency()!=null&&!v.currency().matches("^[A-Z]{3}$"))invalid("Currency must be a canonical three-letter code");
        if(v.quantity()!=null&&(v.quantity()<1||v.quantity()>100)||v.people()!=null&&(v.people()<1||v.people()>100))invalid("Quantity context is outside bounds");
        if(v.prohibitedAllergen()!=null&&!"PEANUT".equals(v.prohibitedAllergen().toUpperCase(Locale.ROOT)))invalid("Unsupported allergen");
        if(v.ambiguityState()==AmbiguityState.AMBIGUOUS&&(v.clarificationQuestion()==null||v.clarificationQuestion().isBlank()))invalid("Ambiguity requires one clarification question");
        if(v.ambiguityState()==AmbiguityState.CLEAR&&v.clarificationQuestion()!=null)invalid("Clear intent cannot retain a clarification question");
        if(v.softPreferences()==null||v.softPreferences().size()>16||v.materialFields()==null||v.materialFields().isEmpty()||v.materialFields().size()>32)invalid("Intent evidence is missing or unbounded");
        Map<String,MaterialField> fields=new HashMap<>();for(MaterialField f:v.materialFields()){if(f==null||f.field()==null||f.classification()==null||f.ambiguity()==null||f.evidence()==null)invalid("Material field evidence is incomplete");
            if(!message.messageId().equals(f.evidence().sourceMessageId())||f.evidence().startOffset()<0||f.evidence().endOffset()<=f.evidence().startOffset()||f.evidence().endOffset()>message.normalizedText().length())invalid("Material field evidence span is invalid");
            if(f.modelSignal()==null||f.modelSignal().compareTo(BigDecimal.ZERO)<0||f.modelSignal().compareTo(BigDecimal.ONE)>0)invalid("Model signal must be an application signal from zero to one");
            fields.put(f.field().toUpperCase(Locale.ROOT),f);}
        requireClass(fields,v.budgetAmountMinor()!=null,"BUDGET",ConstraintClassification.HARD);
        requireClass(fields,v.vegetarian()!=null,"VEGETARIAN",ConstraintClassification.HARD);
        requireClass(fields,v.prohibitedAllergen()!=null,"ALLERGEN",ConstraintClassification.HARD_SAFETY);
        if(!v.softPreferences().isEmpty()){MaterialField f=fields.get("PREFERENCES");if(f==null||f.classification()!=ConstraintClassification.SOFT)invalid("Preferences require SOFT evidence");}
        if(v.ambiguityState()==AmbiguityState.CLEAR&&v.materialFields().stream().anyMatch(f->f.ambiguity()==AmbiguityState.AMBIGUOUS))invalid("Clear intent contains ambiguous material fields");}
    private static void requireClass(Map<String,MaterialField> fields,boolean required,String key,ConstraintClassification classification){if(required&&(fields.get(key)==null||fields.get(key).classification()!=classification))invalid(key+" classification is invalid");}
    private static void invalid(String message){throw new BuyerException("INTENT_DOMAIN_INVALID",HttpStatus.UNPROCESSABLE_ENTITY,message);}
    private static String bounded(String v){if(v==null)return "validation failed";return v.length()<=400?v:v.substring(0,400);}
    public record Compiled(CompiledIntent intent,String modelOutputHash,String intentHash){}
}
