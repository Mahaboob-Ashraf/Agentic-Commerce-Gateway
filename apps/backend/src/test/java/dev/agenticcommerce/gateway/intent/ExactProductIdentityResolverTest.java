package dev.agenticcommerce.gateway.intent;

import static dev.agenticcommerce.gateway.catalogue.CatalogueModels.*;
import static dev.agenticcommerce.gateway.intent.BuyerModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.agenticcommerce.gateway.catalogue.CatalogueRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExactProductIdentityResolverTest {
    @Test
    void multipleCanonicalIdentitiesRemainAmbiguous(){
        CatalogueRepository repository=mock(CatalogueRepository.class);
        when(repository.exactIdentityCandidates(anyString(),anyInt())).thenReturn(List.of(
                product("BLK","Black"),product("WHT","White")));

        var result=new ExactProductIdentityResolver(repository).resolve(intent(null));

        assertThat(result.outcome()).isEqualTo(ExactProductIdentityResolver.ResolutionOutcome.MULTIPLE);
        assertThat(result.intent().ambiguityState()).isEqualTo(AmbiguityState.AMBIGUOUS);
        assertThat(result.intent().clarificationQuestion()).isNotBlank();
    }

    @Test
    void zeroCanonicalIdentitiesRemainAmbiguous(){
        CatalogueRepository repository=mock(CatalogueRepository.class);
        when(repository.exactIdentityCandidates(anyString(),anyInt())).thenReturn(List.of());

        var result=new ExactProductIdentityResolver(repository).resolve(intent("Black"));

        assertThat(result.outcome()).isEqualTo(ExactProductIdentityResolver.ResolutionOutcome.ZERO);
        assertThat(result.intent().ambiguityState()).isEqualTo(AmbiguityState.AMBIGUOUS);
    }

    @Test
    void clearPartialModelAndCatalogueNameCategoryResolveToOneAuthoritativeIdentity(){
        CatalogueRepository repository=mock(CatalogueRepository.class);
        Product auralink=new Product(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"AMZ-AUDIO-032",
                null,"Auralink","Auralink Buds Bluetooth Earphones","auralink buds bluetooth earphones",
                "Buds Pro",null,"White","Electronics","Bluetooth Earphones",true,"source",299900L,
                "INR",21L,Availability.IN_STOCK,Instant.parse("2026-08-15T00:00:00Z"));
        when(repository.exactIdentityCandidates(anyString(),anyInt())).thenReturn(List.of(auralink));
        UUID messageId=UUID.randomUUID();EvidenceSpan evidence=new EvidenceSpan(messageId,5,38);
        CompiledIntent clear=new CompiledIntent(IntentGoal.PURCHASE_PRODUCT,"Bluetooth Earphones",null,null,
                null,null,"Auralink","Buds",null,null,null,null,1,null,SubstitutionPolicy.UNKNOWN,null,List.of(),
                List.of(clearField("CATEGORY",evidence),clearField("BRAND",evidence),clearField("VARIANT",evidence)),
                AmbiguityState.CLEAR,null,"GEMINI","test");

        var result=new ExactProductIdentityResolver(repository).resolve(clear);

        assertThat(result.outcome()).isEqualTo(ExactProductIdentityResolver.ResolutionOutcome.UNIQUE);
        assertThat(result.intent().exactBrand()).isEqualTo("Auralink");
        assertThat(result.intent().exactVariant()).isEqualTo("Buds Pro");
        assertThat(result.intent().categoryRequest()).isEqualTo("Electronics");
        assertThat(result.intent().ambiguityState()).isEqualTo(AmbiguityState.CLEAR);
    }

    private static CompiledIntent intent(String colour){
        UUID messageId=UUID.randomUUID();EvidenceSpan evidence=new EvidenceSpan(messageId,0,32);
        List<MaterialField> fields=colour==null
                ?List.of(field("BRAND",evidence),field("VARIANT",evidence))
                :List.of(field("BRAND",evidence),field("VARIANT",evidence),field("COLOUR",evidence));
        return new CompiledIntent(IntentGoal.PURCHASE_PRODUCT,null,null,null,null,null,"Synthetic","Sonic A1",
                null,colour,null,null,1,null,SubstitutionPolicy.UNKNOWN,null,List.of(),fields,
                AmbiguityState.AMBIGUOUS,"Please clarify the exact product",null,null);
    }

    private static MaterialField field(String key,EvidenceSpan evidence){
        return new MaterialField(key,ConstraintClassification.HARD,evidence,BigDecimal.ONE,AmbiguityState.AMBIGUOUS);
    }

    private static MaterialField clearField(String key,EvidenceSpan evidence){
        return new MaterialField(key,ConstraintClassification.HARD,evidence,BigDecimal.ONE,AmbiguityState.CLEAR);
    }

    private static Product product(String suffix,String colour){
        return new Product(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"SYN-A1-"+suffix,
                "890900100000"+("BLK".equals(suffix)?"1":"2"),"Synthetic Sonic","Sonic A1 Wireless Earphones",
                "sonic a1 wireless earphones","A1","Standard",colour,"Earphones","Catalogue product",true,
                "source-"+suffix,449900L,"INR",10L,Availability.IN_STOCK,Instant.parse("2026-08-15T00:00:00Z"));
    }
}
