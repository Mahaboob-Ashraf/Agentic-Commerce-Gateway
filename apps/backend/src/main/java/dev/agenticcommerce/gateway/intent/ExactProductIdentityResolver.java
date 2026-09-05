package dev.agenticcommerce.gateway.intent;

import static dev.agenticcommerce.gateway.intent.BuyerModels.*;

import dev.agenticcommerce.gateway.catalogue.CatalogueModels.Product;
import dev.agenticcommerce.gateway.catalogue.CatalogueRepository;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ExactProductIdentityResolver {
    private static final Logger log=LoggerFactory.getLogger(ExactProductIdentityResolver.class);
    private static final int QUERY_LIMIT=33;
    private static final Set<String> RESOLVABLE_FIELDS=Set.of("CATEGORY","BRAND","VARIANT","SIZE_STORAGE","COLOUR");
    private final CatalogueRepository catalogues;

    public ExactProductIdentityResolver(CatalogueRepository catalogues){this.catalogues=catalogues;}

    public Resolution resolve(CompiledIntent intent){
        long started=System.nanoTime();
        if(!eligible(intent))return new Resolution(intent,ResolutionOutcome.NOT_APPLICABLE,0,elapsedMillis(started));
        String identityTokens=normalize(intent.exactBrand()+" "+intent.exactVariant());
        long queryStarted=System.nanoTime();
        List<Product> candidates=catalogues.exactIdentityCandidates(identityTokens,QUERY_LIMIT).stream()
                .filter(product->matches(intent,product)).toList();
        long queryElapsed=elapsedMillis(queryStarted);
        if(candidates.size()>=QUERY_LIMIT){
            log.info("Exact product identity resolution outcome=BOUNDED_OVERFLOW candidateRows={} queryElapsedMs={} totalElapsedMs={}",
                    candidates.size(),queryElapsed,elapsedMillis(started));
            return new Resolution(intent,ResolutionOutcome.MULTIPLE, candidates.size(),elapsedMillis(started));
        }
        Map<String,List<Product>> identities=new LinkedHashMap<>();
        for(Product product:candidates)identities.computeIfAbsent(identityKey(product),ignored->new java.util.ArrayList<>()).add(product);
        if(identities.size()!=1){
            ResolutionOutcome outcome=identities.isEmpty()?ResolutionOutcome.ZERO:ResolutionOutcome.MULTIPLE;
            log.info("Exact product identity resolution outcome={} candidateRows={} canonicalIdentities={} queryElapsedMs={} totalElapsedMs={}",
                    outcome,candidates.size(),identities.size(),queryElapsed,elapsedMillis(started));
            return new Resolution(intent,outcome,candidates.size(),elapsedMillis(started));
        }
        Product canonical=identities.values().iterator().next().getFirst();
        CompiledIntent resolved=canonicalize(intent,canonical);
        log.info("Exact product identity resolution outcome=UNIQUE candidateRows={} canonicalIdentities=1 queryElapsedMs={} totalElapsedMs={}",
                candidates.size(),queryElapsed,elapsedMillis(started));
        return new Resolution(resolved,ResolutionOutcome.UNIQUE,candidates.size(),elapsedMillis(started));
    }

    private static boolean eligible(CompiledIntent intent){
        if(intent==null||intent.goal()!=IntentGoal.PURCHASE_PRODUCT
                ||blank(intent.exactBrand())||blank(intent.exactVariant())||intent.materialFields()==null)return false;
        return intent.materialFields().stream().filter(field->field.ambiguity()==AmbiguityState.AMBIGUOUS)
                .allMatch(field->field.field()!=null&&RESOLVABLE_FIELDS.contains(field.field().toUpperCase(Locale.ROOT)));
    }

    private static boolean matches(CompiledIntent intent,Product product){
        Set<String> requested=tokens(intent.exactBrand()+" "+intent.exactVariant());
        Set<String> authoritative=tokens(product.brand()+" "+product.canonicalName()+" "+product.variant());
        return !requested.isEmpty()&&authoritative.containsAll(requested)
                &&categoryCompatible(intent.categoryRequest(),product)
                &&sameWhenRequired(intent.exactColour(),product.colour())
                &&sameWhenRequired(intent.exactSizeStorage(),product.sizeStorage());
    }

    private static boolean categoryCompatible(String requested,Product product){
        if(blank(requested))return true;
        Set<String> categoryTokens=tokens(requested);
        Set<String> authoritative=tokens(product.canonicalName()+" "+product.category()+" "+product.description());
        return !categoryTokens.isEmpty()&&authoritative.containsAll(categoryTokens);
    }

    private static boolean sameWhenRequired(String requested,String authoritative){
        return blank(requested)||!blank(authoritative)
                &&normalize(requested).equals(normalize(authoritative));
    }

    private static Set<String> tokens(String value){
        String normalized=normalize(value);
        return normalized.isBlank()?Set.of():Set.copyOf(List.of(normalized.split("\\s+")));
    }

    private static String identityKey(Product product){
        return String.join("|",normalized(product.brand()),normalized(product.canonicalName()),normalized(product.variant()),
                normalized(product.sizeStorage()),normalized(product.colour()),normalized(product.gtin()));
    }

    private static CompiledIntent canonicalize(CompiledIntent intent,Product product){
        List<MaterialField> fields=intent.materialFields().stream().map(field->RESOLVABLE_FIELDS.contains(field.field().toUpperCase(Locale.ROOT))
                ?new MaterialField(field.field(),field.classification(),field.evidence(),field.modelSignal(),AmbiguityState.CLEAR):field).toList();
        return new CompiledIntent(intent.goal(),blank(intent.categoryRequest())?null:product.category(),intent.budgetAmountMinor(),intent.currency(),
                intent.exactMerchantSku(),intent.exactGtin(),product.brand(),product.variant(),
                blank(intent.exactSizeStorage())?null:product.sizeStorage(),blank(intent.exactColour())?null:product.colour(),
                intent.vegetarian(),intent.prohibitedAllergen(),intent.quantity(),intent.people(),intent.substitutionPolicy(),
                intent.deliveryHint(),intent.excludedMaterials(),intent.softPreferences(),fields,AmbiguityState.CLEAR,null,intent.provider(),intent.model());
    }

    private static String normalized(String value){return blank(value)?"":normalize(value);}
    private static String normalize(String value){return value==null?"":Normalizer.normalize(value,Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+"," ").strip();}
    private static boolean blank(String value){return value==null||value.isBlank();}
    private static long elapsedMillis(long startedNanos){return (System.nanoTime()-startedNanos)/1_000_000L;}

    public enum ResolutionOutcome{NOT_APPLICABLE,UNIQUE,ZERO,MULTIPLE}
    public record Resolution(CompiledIntent intent,ResolutionOutcome outcome,int candidateRows,long elapsedMs){}
}
