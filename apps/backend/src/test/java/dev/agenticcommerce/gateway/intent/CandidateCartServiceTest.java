package dev.agenticcommerce.gateway.intent;

import static dev.agenticcommerce.gateway.catalogue.CatalogueModels.*;
import static dev.agenticcommerce.gateway.intent.BuyerModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.agenticcommerce.gateway.agentization.service.CanonicalJsonService;
import dev.agenticcommerce.gateway.catalogue.CatalogueRepository;
import dev.agenticcommerce.gateway.catalogue.HybridCatalogueRetrievalService;
import dev.agenticcommerce.gateway.intent.BuyerDecisionProvider.CandidateDecisionContext;
import dev.agenticcommerce.gateway.intent.BuyerDecisionProvider.CandidateOption;
import dev.agenticcommerce.gateway.intent.BuyerDecisionProvider.CandidateSelection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class CandidateCartServiceTest {
    private final ObjectMapper mapper=JsonMapper.builder().findAndAddModules().build();
    private final HybridCatalogueRetrievalService retrieval=mock(HybridCatalogueRetrievalService.class);
    private final BuyerRepository repository=mock(BuyerRepository.class);
    private final CatalogueRepository catalogues=mock(CatalogueRepository.class);
    private final BuyerDecisionProvider decisions=mock(BuyerDecisionProvider.class);
    private final CandidateCartService service=new CandidateCartService(retrieval,repository,catalogues,decisions,
            new CanonicalJsonService(mapper),mapper);

    @Test
    void exactIdentitySelectionIsDeterministicAndReloadedWithoutCallingGemini(){
        Fixture fixture=fixture();Product product=product(fixture,"PHONE-BLK","Pixel 10","Black",79_900L);
        when(retrieval.search(eq(fixture.merchant().merchantId()),any())).thenReturn(response(product));
        when(repository.priorCartProductIds(fixture.thread().buyerActorId(),fixture.thread().threadId())).thenReturn(List.of());
        when(catalogues.findProduct(product.merchantId(),product.catalogueVersionId(),product.id())).thenReturn(Optional.of(product));
        when(repository.createCart(any(),any(),any(),anyList(),anyList(),any(),anyString())).thenAnswer(invocation->{
            List<CandidateCartItem> items=invocation.getArgument(3);return new CandidateCart(UUID.randomUUID(),
                    fixture.thread().threadId(),fixture.thread().buyerActorId(),fixture.intent().intentId(),1,
                    fixture.merchant().merchantId(),1,fixture.merchant().catalogueVersionId(),invocation.getArgument(4),
                    invocation.getArgument(5),invocation.getArgument(6),Instant.now(),items);});

        CandidateCart cart=service.build(fixture.thread(),exactIntent(fixture,product),fixture.discovery());

        assertThat(cart.items()).singleElement().satisfies(item->assertThat(item.productId()).isEqualTo(product.id()));
        verifyNoInteractions(decisions);verify(retrieval,times(2)).search(eq(fixture.merchant().merchantId()),any());
        verify(catalogues).findProduct(product.merchantId(),product.catalogueVersionId(),product.id());
    }

    @Test
    void candidateReasoningCannotSelectAnIdOutsideTheSuppliedGroundedSet(){
        Fixture fixture=fixture();Product first=product(fixture,"PHONE-BLK","Pixel 10","Black",79_900L);
        Product second=product(fixture,"PHONE-BLU","Pixel 10a","Blue",69_900L);
        when(retrieval.search(eq(fixture.merchant().merchantId()),any())).thenReturn(response(first,second));
        when(repository.priorCartProductIds(fixture.thread().buyerActorId(),fixture.thread().threadId())).thenReturn(List.of());
        when(decisions.chooseCandidate(any(),any())).thenReturn(new CandidateSelection(UUID.randomUUID(),
                "invented candidate",List.of(),"TEST","test"));

        assertThatThrownBy(()->service.build(fixture.thread(),fixture.intent(),fixture.discovery()))
                .isInstanceOfSatisfying(BuyerException.class,error->assertThat(error.code()).isEqualTo("BUYER_CANDIDATE_DECISION_INVALID"));
        verify(decisions,times(2)).chooseCandidate(any(),any());verifyNoInteractions(catalogues);
    }

    @Test
    void validationRejectsCandidateAndEvidenceIdsOutsideTheSuppliedSet(){UUID allowed=UUID.randomUUID();
        CandidateDecisionContext context=new CandidateDecisionContext(List.of(new CandidateOption(allowed,UUID.randomUUID(),
                "Phone","Brand","Model",null,"Black","Phones",10_000L,"INR",0.9)),List.of(),List.of("product:"+allowed));
        assertThatThrownBy(()->CandidateCartService.validateCandidateSelection(new CandidateSelection(UUID.randomUUID(),
                "outside",List.of(),"TEST","test"),context)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->CandidateCartService.validateCandidateSelection(new CandidateSelection(allowed,
                "inside product, outside evidence",List.of("product:"+UUID.randomUUID()),"TEST","test"),context))
                .isInstanceOf(IllegalArgumentException.class);}

    private BuyerIntent exactIntent(Fixture fixture,Product product){CompiledIntent base=fixture.intent().compiled();
        CompiledIntent exact=new CompiledIntent(base.goal(),null,null,null,null,null,product.brand(),product.variant(),
                null,product.colour(),null,null,1,null,SubstitutionPolicy.PROHIBIT,null,List.of(),List.of(),
                AmbiguityState.CLEAR,null,"TEST","test");return new BuyerIntent(fixture.intent().intentId(),
                fixture.intent().threadId(),fixture.intent().buyerActorId(),1,fixture.intent().sourceMessageId(),exact,
                fixture.intent().modelOutputHash(),fixture.intent().intentHash(),fixture.intent().createdAt());}

    private Fixture fixture(){UUID buyer=UUID.randomUUID(),threadId=UUID.randomUUID(),merchantId=UUID.randomUUID(),versionId=UUID.randomUUID();
        CommerceThread thread=new CommerceThread(threadId,buyer,"test",BuyerState.SEARCHING,1,null,null,null,null,null,null,null,
                null,2,16,0,Instant.now().plusSeconds(60),0,Instant.now(),Instant.now());
        CompiledIntent compiled=new CompiledIntent(IntentGoal.PURCHASE_PRODUCT,"Phones",100_000L,"INR",null,null,null,null,
                null,null,null,null,1,null,SubstitutionPolicy.PROHIBIT,null,List.of("GOOD"),List.of(),AmbiguityState.CLEAR,null,"TEST","test");
        BuyerIntent intent=new BuyerIntent(UUID.randomUUID(),threadId,buyer,1,UUID.randomUUID(),compiled,"a".repeat(64),"b".repeat(64),Instant.now());
        MerchantCandidate merchant=new MerchantCandidate(merchantId,"Merchant",UUID.randomUUID(),1,versionId,"v1:test",UUID.randomUUID(),true);
        MerchantDiscovery discovery=new MerchantDiscovery(UUID.randomUUID(),threadId,buyer,intent.intentId(),1,DiscoveryOutcome.ELIGIBLE,
                List.of("SEARCH_PRODUCTS:READY:ADVERTISED"),List.of(merchant),List.of("manifest:ready"),"c".repeat(64),Instant.now());
        return new Fixture(thread,intent,merchant,discovery);}

    private static Product product(Fixture fixture,String sku,String variant,String colour,long price){return new Product(
            UUID.randomUUID(),fixture.merchant().merchantId(),fixture.merchant().catalogueVersionId(),sku,null,"Acme",
            variant+" Phone",variant.toLowerCase(),variant,"128 GB",colour,"Phones","Grounded product",true,
            "source-"+sku,price,"INR",5L,Availability.IN_STOCK,Instant.now());}
    private static SearchResponse response(Product...products){List<SearchHit> hits=java.util.Arrays.stream(products)
            .map(product->new SearchHit(product,0.9,GateOutcome.PASS,Map.of("fts",0.9),AllergenState.UNKNOWN)).toList();
        return new SearchResponse(MatchClassification.VALID_MATCH,hits,List.of(),false,"v1:test",List.of("catalogue:test","ranker:hybrid-v1"));}
    private record Fixture(CommerceThread thread,BuyerIntent intent,MerchantCandidate merchant,MerchantDiscovery discovery){}
}
