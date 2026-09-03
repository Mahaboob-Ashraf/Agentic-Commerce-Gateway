package dev.agenticcommerce.gateway.intent;

import static dev.agenticcommerce.gateway.intent.BuyerModels.*;

import dev.agenticcommerce.gateway.agentization.service.CanonicalJsonService;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class IntentCompilerService {
    private static final Logger log = LoggerFactory.getLogger(IntentCompilerService.class);
    private final BuyerIntentCompiler compiler;private final CanonicalJsonService canonical;private final ObjectMapper mapper;
    private final ExactProductIdentityResolver identityResolver;
    @Autowired
    public IntentCompilerService(BuyerIntentCompiler compiler,CanonicalJsonService canonical,ObjectMapper mapper,
            ExactProductIdentityResolver identityResolver){this.compiler=compiler;this.canonical=canonical;this.mapper=mapper;this.identityResolver=identityResolver;}
    IntentCompilerService(BuyerIntentCompiler compiler,CanonicalJsonService canonical,ObjectMapper mapper){this(compiler,canonical,mapper,null);}
    public Compiled compile(ThreadMessage message){
        long totalStarted=System.nanoTime();String feedback=null;
        for(int attempt=1;attempt<=2;attempt++){
            long attemptStarted=System.nanoTime();long compilerStarted=System.nanoTime();
            try{
                CompiledIntent modelValue=compiler.compile(message,feedback);long compilerElapsed=elapsedMillis(compilerStarted);
                long validationStarted=System.nanoTime();validate(message,modelValue);long validationElapsed=elapsedMillis(validationStarted);
                var modelMaterial=mapper.valueToTree(modelValue);
                long resolutionStarted=System.nanoTime();ExactProductIdentityResolver.Resolution resolution=identityResolver==null
                        ?new ExactProductIdentityResolver.Resolution(modelValue,ExactProductIdentityResolver.ResolutionOutcome.NOT_APPLICABLE,0,0)
                        :identityResolver.resolve(modelValue);
                long resolutionElapsed=elapsedMillis(resolutionStarted);CompiledIntent value=resolution.intent();
                validationStarted=System.nanoTime();validate(message,value);validationElapsed+=elapsedMillis(validationStarted);
                var material=mapper.valueToTree(value);Compiled result=new Compiled(value,canonical.hash(modelMaterial),canonical.hash(material));
                log.info("Buyer intent compilation accepted attempt={} compilerElapsedMs={} validationElapsedMs={} identityResolutionElapsedMs={} identityResolutionOutcome={} totalElapsedMs={} repair={}",
                        attempt,compilerElapsed,validationElapsed,resolutionElapsed,resolution.outcome(),elapsedMillis(totalStarted),attempt>1);
                return result;
            }catch(BuyerException e){
                if(e.code().equals("INTENT_COMPILER_UNAVAILABLE")){
                    log.warn("Buyer intent compilation unavailable attempt={} reason={} attemptElapsedMs={} totalElapsedMs={} repair={}",
                            attempt,bounded(e.getMessage()),elapsedMillis(attemptStarted),elapsedMillis(totalStarted),attempt>1);
                    throw e;
                }
                feedback=bounded(e.getMessage());
                log.warn("Buyer intent output rejected attempt={} code={} reason={} attemptElapsedMs={} totalElapsedMs={} repairWillRun={}",
                        attempt,e.code(),feedback,elapsedMillis(attemptStarted),elapsedMillis(totalStarted),attempt<2);
            }catch(RuntimeException e){
                feedback="Intent output failed deterministic validation";
                log.warn("Buyer intent output rejected attempt={} exceptionClass={} reason={} attemptElapsedMs={} totalElapsedMs={} repairWillRun={}",
                        attempt,e.getClass().getName(),feedback,elapsedMillis(attemptStarted),elapsedMillis(totalStarted),attempt<2);
            }
        }
        log.warn("Buyer intent compilation failed attempts=2 repair=true totalElapsedMs={} finalCode=INVALID_BUYER_INTENT",
                elapsedMillis(totalStarted));
        throw new BuyerException("INVALID_BUYER_INTENT",HttpStatus.UNPROCESSABLE_ENTITY,"Intent compiler output remained invalid after one retry");}
    private static void validate(ThreadMessage message,CompiledIntent v){if(v==null||!java.util.Set.of(IntentGoal.PURCHASE_PRODUCT,IntentGoal.PURCHASE_FOOD).contains(v.goal()))invalid("Unsupported intent goal");
        if(v.substitutionPolicy()==null||v.ambiguityState()==null)invalid("Substitution and ambiguity states are required");
        if(v.budgetAmountMinor()!=null&&v.budgetAmountMinor()<0)invalid("Budget cannot be negative");
        if(v.currency()!=null&&!v.currency().matches("^[A-Z]{3}$"))invalid("Currency must be a canonical three-letter code");
        if(v.quantity()!=null&&(v.quantity()<1||v.quantity()>100)||v.people()!=null&&(v.people()<1||v.people()>100))invalid("Quantity context is outside bounds");
        if(v.prohibitedAllergen()!=null&&!"PEANUT".equals(v.prohibitedAllergen().toUpperCase(Locale.ROOT)))invalid("Unsupported allergen");
        if(v.ambiguityState()==AmbiguityState.AMBIGUOUS&&(v.clarificationQuestion()==null||v.clarificationQuestion().isBlank()))invalid("Ambiguity requires one clarification question");
        if(v.ambiguityState()==AmbiguityState.CLEAR&&v.clarificationQuestion()!=null)invalid("Clear intent cannot retain a clarification question");
        if(v.softPreferences()==null||v.softPreferences().size()>16||v.materialFields()==null||v.materialFields().isEmpty()||v.materialFields().size()>32)invalid("Intent evidence is missing or unbounded");
        if(v.goal()==IntentGoal.PURCHASE_PRODUCT&&!meaningfulProductIdentity(v))invalid("Generic purchase requires a category or sufficient exact product identity");
        Map<String,MaterialField> fields=new HashMap<>();for(MaterialField f:v.materialFields()){if(f==null||f.field()==null||f.classification()==null||f.ambiguity()==null||f.evidence()==null)invalid("Material field evidence is incomplete");
            if(!message.messageId().equals(f.evidence().sourceMessageId())||f.evidence().startOffset()<0||f.evidence().endOffset()<=f.evidence().startOffset()||f.evidence().endOffset()>message.normalizedText().length())invalid("Material field evidence span is invalid");
            if(f.modelSignal()==null||f.modelSignal().compareTo(BigDecimal.ZERO)<0||f.modelSignal().compareTo(BigDecimal.ONE)>0)invalid("Model signal must be an application signal from zero to one");
            MaterialFieldKey key;try{key=MaterialFieldKey.valueOf(f.field());}catch(IllegalArgumentException e){invalid("Unsupported canonical material field");return;}
            if(f.classification()!=classification(key))invalid(key+" classification is invalid");
            if(fields.put(key.name(),f)!=null)invalid("Duplicate canonical material field");}
        requireClass(fields,v.budgetAmountMinor()!=null,"BUDGET",ConstraintClassification.HARD);
        if(v.goal()==IntentGoal.PURCHASE_PRODUCT){requireClass(fields,v.categoryRequest()!=null,"CATEGORY",ConstraintClassification.HARD);
            requireClass(fields,v.exactMerchantSku()!=null,"MERCHANT_SKU",ConstraintClassification.HARD);
            requireClass(fields,v.exactGtin()!=null,"GTIN",ConstraintClassification.HARD);
            requireClass(fields,v.exactBrand()!=null,"BRAND",ConstraintClassification.HARD);
            requireClass(fields,v.exactVariant()!=null,"VARIANT",ConstraintClassification.HARD);
            requireClass(fields,v.exactSizeStorage()!=null,"SIZE_STORAGE",ConstraintClassification.HARD);
            requireClass(fields,v.exactColour()!=null,"COLOUR",ConstraintClassification.HARD);}
        requireClass(fields,v.vegetarian()!=null,"VEGETARIAN",ConstraintClassification.HARD);
        requireClass(fields,v.prohibitedAllergen()!=null,"ALLERGEN",ConstraintClassification.HARD_SAFETY);
        if(!v.softPreferences().isEmpty()){MaterialField f=fields.get("PREFERENCES");if(f==null||f.classification()!=ConstraintClassification.SOFT)invalid("Preferences require SOFT evidence");}
        if(v.ambiguityState()==AmbiguityState.CLEAR&&v.materialFields().stream().anyMatch(f->f.ambiguity()==AmbiguityState.AMBIGUOUS))invalid("Clear intent contains ambiguous material fields");}
    private static boolean meaningfulProductIdentity(CompiledIntent v){return present(v.categoryRequest())||present(v.exactMerchantSku())||present(v.exactGtin())||(present(v.exactBrand())&&present(v.exactVariant()));}
    private static ConstraintClassification classification(MaterialFieldKey key){return switch(key){
        case ALLERGEN->ConstraintClassification.HARD_SAFETY;
        case PREFERENCES->ConstraintClassification.SOFT;
        default->ConstraintClassification.HARD;};}
    private static boolean present(String value){return value!=null&&!value.isBlank();}
    private static void requireClass(Map<String,MaterialField> fields,boolean required,String key,ConstraintClassification classification){if(required&&(fields.get(key)==null||fields.get(key).classification()!=classification))invalid(key+" classification is invalid");}
    private static void invalid(String message){throw new BuyerException("INTENT_DOMAIN_INVALID",HttpStatus.UNPROCESSABLE_ENTITY,message);}
    private static long elapsedMillis(long startedNanos){return (System.nanoTime()-startedNanos)/1_000_000L;}
    private static String bounded(String v){if(v==null)return "validation failed";return v.length()<=400?v:v.substring(0,400);}
    public record Compiled(CompiledIntent intent,String modelOutputHash,String intentHash){}
}
