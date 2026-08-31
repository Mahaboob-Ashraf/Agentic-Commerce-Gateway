package dev.agenticcommerce.gateway;

import static dev.agenticcommerce.gateway.catalogue.CatalogueModels.*;
import static dev.agenticcommerce.gateway.intent.BuyerModels.*;
import static org.assertj.core.api.Assertions.*;

import dev.agenticcommerce.gateway.agentization.execution.*;
import dev.agenticcommerce.gateway.catalogue.*;
import dev.agenticcommerce.gateway.identity.model.*;
import dev.agenticcommerce.gateway.identity.persistence.*;
import dev.agenticcommerce.gateway.intent.*;
import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.InetAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment=WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(Task008SafeBuyerIntegrationTest.Fakes.class)
class Task008SafeBuyerIntegrationTest {
    @Container @ServiceConnection static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:0.8.1-pg17");
    @Autowired JdbcClient jdbc;@Autowired MerchantRepository merchants;@Autowired ApplicationActorRepository actors;
    @Autowired MerchantAdminMembershipRepository memberships;@Autowired CatalogueService catalogues;
    @Autowired CatalogueRepository catalogueRepository;@Autowired BuyerThreadService threads;
    @Autowired BuyerOrchestrationService orchestration;@Autowired BuyerRepository buyerRepository;
    @Autowired ConstraintCertificateService certificates;@Autowired AuthoritativeQuoteService quoteService;
    @Autowired FakeTransport transport;@Autowired ObjectMapper mapper;
    @Autowired ActorPasswordCredentialRepository credentials;@Autowired PasswordEncoder passwordEncoder;@LocalServerPort int port;

    @BeforeEach void clear(){transport.calls.set(0);transport.mode="PASS";jdbc.sql("TRUNCATE TABLE merchant,application_actor CASCADE").update();}

    @Test void v008CreatesBuyerOwnedVersionedImmutableSchema(){
        assertThat(jdbc.sql("SELECT count(*)::int FROM flyway_schema_history WHERE version='008' AND success").query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*)::int FROM information_schema.tables WHERE table_name IN ('commerce_thread','commerce_thread_message','buyer_intent','buyer_agent_action','merchant_discovery_evidence','candidate_cart','candidate_cart_item','merchant_quote','merchant_quote_item','constraint_certificate','constraint_result')").query(Integer.class).single()).isEqualTo(11);
        ApplicationActor buyer=actors.create("schema-buyer@test",PlatformRole.BUYER);CommerceThread thread=threads.create(buyer.id(),canonicalText());orchestration.advance(buyer.id(),thread.threadId());BuyerIntent intent=threads.intent(buyer.id(),thread.threadId());
        assertThatThrownBy(()->jdbc.sql("UPDATE buyer_intent SET currency='USD' WHERE intent_id=:id").param("id",intent.intentId()).update()).isInstanceOf(RuntimeException.class);
    }

    @Test void canonicalTypedJourneyUsesReadyManifestGroundedProductsAuthoritativeQuoteAndCertificate(){Fixture f=fixture("canonical");CommerceThread thread=threads.create(f.buyer().id(),canonicalText());
        List<BuyerTool> tools=new ArrayList<>();for(int i=0;i<6;i++)tools.add(orchestration.advance(f.buyer().id(),thread.threadId()).action().selectedTool());
        CommerceThread done=threads.require(f.buyer().id(),thread.threadId());BuyerIntent intent=threads.intent(f.buyer().id(),thread.threadId());CandidateCart cart=threads.cart(f.buyer().id(),thread.threadId());MerchantQuote quote=threads.quote(f.buyer().id(),thread.threadId());ConstraintCertificate certificate=threads.certificate(f.buyer().id(),thread.threadId());
        assertThat(tools).containsExactly(BuyerTool.COMPILE_INTENT,BuyerTool.DISCOVER_MERCHANTS,BuyerTool.SEARCH_PRODUCTS,BuyerTool.BUILD_CANDIDATE_CART,BuyerTool.GET_QUOTE,BuyerTool.VERIFY_CONSTRAINTS);
        assertThat(intent.compiled().budgetAmountMinor()).isEqualTo(50_000L);assertThat(intent.compiled().currency()).isEqualTo("INR");assertThat(intent.compiled().vegetarian()).isTrue();assertThat(intent.compiled().prohibitedAllergen()).isEqualTo("PEANUT");assertThat(intent.compiled().softPreferences()).contains("HIGH_PROTEIN");assertThat(intent.compiled().people()).isEqualTo(2);
        assertThat(cart.items()).singleElement().satisfies(i->{assertThat(i.productId()).isEqualTo(f.safeProduct());assertThat(i.merchantSku()).isEqualTo("SAFE-CHANA");assertThat(i.quantity()).isEqualTo(2);});
        assertThat(quote.finalAmountMinor()).isEqualTo(36_000L).isNotEqualTo(19_800L);assertThat(quote.currency()).isEqualTo("INR");assertThat(transport.calls).hasValue(1);
        assertThat(certificate.overallResult()).isEqualTo(ConstraintOutcome.PASS);assertThat(certificate.results()).extracting(ConstraintResult::constraintKey).contains("BUDGET","VEGETARIAN","ALLERGEN_PEANUT","EXACT_IDENTITY","QUOTE_VALIDITY");
        assertThat(done.state()).isEqualTo(BuyerState.CONSTRAINTS_VERIFIED);assertThat(jdbc.sql("SELECT count(*)::int FROM transaction_proposal").query(Integer.class).single()).isZero();
    }

    @Test void discoveryUsesCurrentStepReadinessAndRetainsUnresolvedQuoteCapability(){Fixture ready=fixture("ready");Fixture unready=fixture("unready");jdbc.sql("UPDATE agent_commerce_manifest_capability SET readiness='UNTESTED',advertised=false,executable_mapping_proposal_id=NULL WHERE merchant_id=:m AND capability='GET_QUOTE'").param("m",unready.merchant().id()).update();
        CommerceThread t=threads.create(ready.buyer().id(),canonicalText());orchestration.advance(ready.buyer().id(),t.threadId());orchestration.advance(ready.buyer().id(),t.threadId());
        assertThat(threads.discovery(ready.buyer().id(),t.threadId()).eligibleMerchants()).anySatisfy(m->{assertThat(m.merchantId()).isEqualTo(ready.merchant().id());assertThat(m.quoteMappingId()).isNotNull();})
                .anySatisfy(m->{assertThat(m.merchantId()).isEqualTo(unready.merchant().id());assertThat(m.quoteMappingId()).isNull();});}

    @Test void criticalAmbiguityWaitsWithoutCatalogueOrMerchantCallAndClarificationCreatesNewIntentVersion(){ApplicationActor buyer=actors.create("ambiguous@test",PlatformRole.BUYER);CommerceThread t=threads.create(buyer.id(),"around 500 maybe, and I think no nuts");
        AdvanceResult waiting=orchestration.advance(buyer.id(),t.threadId());assertThat(waiting.thread().state()).isEqualTo(BuyerState.WAITING_FOR_USER);assertThat(threads.intent(buyer.id(),t.threadId()).compiled().clarificationQuestion()).isNotBlank();
        assertThat(jdbc.sql("SELECT count(*)::int FROM merchant_discovery_evidence").query(Integer.class).single()).isZero();assertThat(transport.calls).hasValue(0);
        threads.addMessage(buyer.id(),t.threadId(),"₹500 total and peanuts are completely prohibited");orchestration.advance(buyer.id(),t.threadId());
        assertThat(threads.intent(buyer.id(),t.threadId()).intentVersion()).isEqualTo(2);assertThat(threads.intent(buyer.id(),t.threadId()).compiled().ambiguityState()).isEqualTo(AmbiguityState.CLEAR);}

    @Test void exactMissingSkuWithUnknownSubstitutionNeverSilentlySelectsAlternative(){Fixture f=fixture("exact");CommerceThread t=threads.create(f.buyer().id(),"Order exact SKU MISSING-SKU and do not substitute");
        orchestration.advance(f.buyer().id(),t.threadId());orchestration.advance(f.buyer().id(),t.threadId());AdvanceResult search=orchestration.advance(f.buyer().id(),t.threadId());
        assertThat(search.thread().state()).isEqualTo(BuyerState.WAITING_FOR_USER);assertThat(jdbc.sql("SELECT count(*)::int FROM candidate_cart WHERE thread_id=:t").param("t",t.threadId()).query(Integer.class).single()).isZero();assertThat(transport.calls).hasValue(0);}

    @Test void nonInrIntentIsPersistedForAuditButScopeGatePreventsRetrieval(){ApplicationActor buyer=actors.create("currency@test",PlatformRole.BUYER);CommerceThread t=threads.create(buyer.id(),"Buy snacks with a USD 10 total budget");AdvanceResult result=orchestration.advance(buyer.id(),t.threadId());
        assertThat(result.thread().state()).isEqualTo(BuyerState.WAITING_FOR_USER);assertThat(threads.intent(buyer.id(),t.threadId()).compiled().currency()).isEqualTo("USD");assertThat(jdbc.sql("SELECT count(*)::int FROM merchant_discovery_evidence").query(Integer.class).single()).isZero();assertThat(transport.calls).hasValue(0);}

    @Test void unknownPeanutEvidenceCannotPassCertificate(){Fixture f=fixture("unknown");CommerceThread t=threads.create(f.buyer().id(),canonicalText());orchestration.advance(f.buyer().id(),t.threadId());orchestration.advance(f.buyer().id(),t.threadId());BuyerIntent intent=threads.intent(f.buyer().id(),t.threadId());MerchantCandidate merchant=threads.discovery(f.buyer().id(),t.threadId()).eligibleMerchants().getFirst();
        CandidateCartItem item=new CandidateCartItem(null,f.unknownProduct(),"UNKNOWN-SNACK","Plain",1,"test grounded unknown",List.of("product:"+f.unknownProduct()));var material=mapper.createObjectNode().put("product",f.unknownProduct().toString());
        CandidateCart cart=buyerRepository.createCart(t,intent,merchant,List.of(item),List.of("product:"+f.unknownProduct()),mapper.createArrayNode(),"a".repeat(64));MerchantQuote quote=quoteService.quote(cart,merchant);ConstraintCertificate certificate=certificates.evaluate(t,intent,cart,quote);
        assertThat(certificate.overallResult()).isEqualTo(ConstraintOutcome.UNKNOWN);assertThat(certificate.results()).filteredOn(r->r.constraintKey().equals("ALLERGEN_PEANUT")).singleElement().extracting(ConstraintResult::result).isEqualTo(ConstraintOutcome.UNKNOWN);}

    @Test void threadOwnershipAndBuyerRoleAreEnforcedWithoutAdminOrPlatformBypass(){ApplicationActor a=actors.create("owner-a@test",PlatformRole.BUYER),b=actors.create("owner-b@test",PlatformRole.BUYER),admin=actors.create("owner-admin@test",PlatformRole.MERCHANT_ADMIN),platform=actors.create("owner-platform@test",PlatformRole.PLATFORM_ADMIN);CommerceThread t=threads.create(a.id(),canonicalText());
        assertThatThrownBy(()->threads.require(b.id(),t.threadId())).isInstanceOf(BuyerException.class);assertThatThrownBy(()->threads.require(admin.id(),t.threadId())).isInstanceOf(BuyerException.class);assertThatThrownBy(()->threads.require(platform.id(),t.threadId())).isInstanceOf(BuyerException.class);}

    @Test void buyerHttpApiUsesVerifiedSessionCsrfAndOwnerPredicate() throws Exception {ApplicationActor owner=actors.create("http-owner@test",PlatformRole.BUYER),other=actors.create("http-other@test",PlatformRole.BUYER),admin=actors.create("http-admin@test",PlatformRole.MERCHANT_ADMIN);
        for(ApplicationActor actor:List.of(owner,other,admin))credentials.createArgon2Credential(actor.id(),passwordEncoder.encode("task008-password"),true);
        CookieManager ownerCookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);HttpClient ownerClient=HttpClient.newBuilder().cookieHandler(ownerCookies).build();login(ownerClient,owner);
        HttpResponse<String> noCsrf=post(ownerClient,"/api/buyer/threads",null,mapper.writeValueAsString(Map.of("text",canonicalText())));assertThat(noCsrf.statusCode()).isEqualTo(403);
        String ownerCsrf=csrf(ownerClient);HttpResponse<String> created=post(ownerClient,"/api/buyer/threads",ownerCsrf,mapper.writeValueAsString(Map.of("text",canonicalText())));assertThat(created.statusCode()).isEqualTo(200);UUID threadId=UUID.fromString(mapper.readTree(created.body()).path("threadId").asText());
        CookieManager otherCookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);HttpClient otherClient=HttpClient.newBuilder().cookieHandler(otherCookies).build();login(otherClient,other);assertThat(get(otherClient,"/api/buyer/threads/"+threadId).statusCode()).isEqualTo(404);
        CookieManager adminCookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);HttpClient adminClient=HttpClient.newBuilder().cookieHandler(adminCookies).build();login(adminClient,admin);assertThat(get(adminClient,"/api/buyer/threads").statusCode()).isEqualTo(403);}

    @Test void quoteFailsClosedForWrongCartCurrencyExpiryAndSubstitution(){Fixture f=fixture("quote-guards");CommerceThread t=threads.create(f.buyer().id(),canonicalText());orchestration.advance(f.buyer().id(),t.threadId());orchestration.advance(f.buyer().id(),t.threadId());orchestration.advance(f.buyer().id(),t.threadId());orchestration.advance(f.buyer().id(),t.threadId());CandidateCart cart=threads.cart(f.buyer().id(),t.threadId());MerchantCandidate merchant=threads.discovery(f.buyer().id(),t.threadId()).eligibleMerchants().getFirst();
        for(String mode:List.of("WRONG_CART","USD","EXPIRED","SUBSTITUTION")){transport.mode=mode;assertThatThrownBy(()->quoteService.quote(cart,merchant)).isInstanceOf(BuyerException.class);}assertThat(jdbc.sql("SELECT count(*)::int FROM merchant_quote WHERE thread_id=:t").param("t",t.threadId()).query(Integer.class).single()).isZero();}

    private Fixture fixture(String key){Merchant merchant=merchants.create("buyer-"+key,"Buyer "+key);ApplicationActor admin=actors.create(key+"-admin@test",PlatformRole.MERCHANT_ADMIN);memberships.create(merchant.id(),admin.id());ApplicationActor buyer=actors.create(key+"-buyer@test",PlatformRole.BUYER);
        CatalogueVersion version=catalogues.ingest(admin.id(),merchant.id(),"JSON",cataloguePayload()).version();List<Product> products=catalogueRepository.products(merchant.id(),version.id(),20);UUID safe=product(products,"SAFE-CHANA"),unknown=product(products,"UNKNOWN-SNACK");
        UUID resolution=jdbc.sql("SELECT identity_resolution_id FROM product_identity_resolution WHERE product_id=:p AND external_source='MERCHANT'").param("p",safe).query(UUID.class).single();var absent=mapper.createObjectNode().put("allergen","peanut").put("status","ABSENT");
        catalogueRepository.insertFact(merchant.id(),version.id(),safe,resolution,"ALLERGEN",absent,"MERCHANT","safe-absence","v1","PRIMARY","ACTIVE",Instant.now(),Instant.now().plus(30,ChronoUnit.DAYS),("f"+key.hashCode()+"0".repeat(64)).replace("-","").substring(0,64).replaceAll("[^0-9a-f]","a"));
        UUID mapping=publishReady(merchant,admin,version);return new Fixture(merchant,admin,buyer,version.id(),safe,unknown,mapping);}

    private UUID publishReady(Merchant merchant,ApplicationActor admin,CatalogueVersion version){UUID endpoint=jdbc.sql("""
            INSERT INTO merchant_approved_endpoint(merchant_id,base_uri,hostname,approved_at,dns_validated_at)
            VALUES(:m,'https://merchant.example.test','merchant.example.test',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING endpoint_id
            """).param("m",merchant.id()).query(UUID.class).single();UUID artifact=jdbc.sql("""
            INSERT INTO openapi_artifact(merchant_id,endpoint_id,artifact_type,artifact_version,content_hash,document)
            VALUES(:m,:e,'OPENAPI','v1',:h,'{}') RETURNING artifact_id
            """).param("m",merchant.id()).param("e",endpoint).param("h","1".repeat(64)).query(UUID.class).single();UUID run=jdbc.sql("""
            INSERT INTO agentization_run(merchant_id,created_by_actor_id,source_artifact_id,target_capability,current_capability,orchestration_state,max_step_budget,wall_clock_deadline)
            VALUES(:m,:a,:artifact,'GET_QUOTE','GET_QUOTE','READY_CANDIDATE',20,CURRENT_TIMESTAMP+interval '1 hour') RETURNING run_id
            """).param("m",merchant.id()).param("a",admin.id()).param("artifact",artifact).query(UUID.class).single();UUID mapping=jdbc.sql("""
            INSERT INTO capability_mapping_proposal(merchant_id,run_id,capability,mapping_version,source_artifact_id,endpoint_id,source_operation_id,http_method,path_template,
              request_bindings,response_bindings,transformations,amount_interpretation,currency_interpretation,model_provider,model_name,proposal_status,validation_status)
            VALUES(:m,:run,'GET_QUOTE',1,:artifact,:endpoint,'quote','POST','/quotes',CAST(:request AS jsonb),CAST(:response AS jsonb),CAST(:transform AS jsonb),
              '{"unit":"minor"}','{"field":"body.currency"}','TEST','fixture','AWAITING_APPROVAL','VALID') RETURNING mapping_proposal_id
            """).param("m",merchant.id()).param("run",run).param("artifact",artifact).param("endpoint",endpoint)
            .param("request","{\"cartId\":\"body.cartId\"}").param("response","{\"amount\":\"body.finalAmountMinor\",\"currency\":\"body.currency\",\"quoteId\":\"body.quoteId\"}").param("transform","{\"amount\":\"IDENTITY\"}").query(UUID.class).single();
        UUID searchEval=readiness(merchant.id(),run,"SEARCH_PRODUCTS",null),quoteEval=readiness(merchant.id(),run,"GET_QUOTE",mapping);UUID manifest=jdbc.sql("""
            INSERT INTO agent_commerce_manifest(merchant_id,agentization_run_id,manifest_version,catalogue_version,publication_actor_id,publication_component,manifest_hash)
            VALUES(:m,:run,1,:catalogue,:actor,'DETERMINISTIC_READINESS_REDUCER',:hash) RETURNING manifest_id
            """).param("m",merchant.id()).param("run",run).param("catalogue","v"+version.version()+":"+version.contentHash()).param("actor",admin.id()).param("hash","3".repeat(64)).query(UUID.class).single();
        jdbc.sql("INSERT INTO agent_commerce_manifest_capability(manifest_id,merchant_id,capability,advertised,readiness,executable_mapping_proposal_id,readiness_evaluation_id) VALUES(:manifest,:m,'SEARCH_PRODUCTS',true,'READY',NULL,:evaluation)").param("manifest",manifest).param("m",merchant.id()).param("evaluation",searchEval).update();
        jdbc.sql("INSERT INTO agent_commerce_manifest_capability(manifest_id,merchant_id,capability,advertised,readiness,executable_mapping_proposal_id,readiness_evaluation_id) VALUES(:manifest,:m,'GET_QUOTE',true,'READY',:mapping,:evaluation)").param("manifest",manifest).param("m",merchant.id()).param("mapping",mapping).param("evaluation",quoteEval).update();return mapping;}
    private UUID readiness(UUID merchant,UUID run,String capability,UUID mapping){return jdbc.sql("""
            INSERT INTO capability_readiness_evaluation(merchant_id,agentization_run_id,capability,readiness,mapping_proposal_id,mapping_version,mapping_content_hash,
              required_evidence,satisfied_evidence,missing_requirements,blocking_evidence,evidence_references,evaluation_hash)
            VALUES(:m,:run,:capability,'READY',:mapping,:version,:mappingHash,'[]','[]','[]','[]','[]',:hash) RETURNING readiness_evaluation_id
            """).param("m",merchant).param("run",run).param("capability",capability).param("mapping",mapping).param("version",mapping==null?null:1)
            .param("mappingHash",mapping==null?null:"4".repeat(64)).param("hash",capability.equals("SEARCH_PRODUCTS")?"5".repeat(64):"6".repeat(64)).query(UUID.class).single();}
    private static UUID product(List<Product> products,String sku){return products.stream().filter(p->p.merchantSku().equals(sku)).findFirst().orElseThrow().id();}
    private static String canonicalText(){return "500 ke andar do logon ke liye high-protein vegetarian snacks order karo, peanuts bilkul nahi.";}
    private static String cataloguePayload(){return """
            {"products":[
              {"merchantSku":"SAFE-CHANA","gtin":"8901234500001","brand":"Safe Foods","canonicalName":"High Protein Roasted Chana","variant":"Masala","sizeStorage":"200 g","category":"Snacks","description":"vegetarian high protein snack","priceMinor":9900,"currency":"INR","stockQuantity":20,"availability":"IN_STOCK"},
              {"merchantSku":"PEANUT-BAR","gtin":"8901234500002","brand":"Safe Foods","canonicalName":"Peanut Protein Bar","variant":"Crunch","sizeStorage":"60 g","category":"Snacks","description":"high protein peanut snack","priceMinor":8000,"currency":"INR","stockQuantity":20,"availability":"IN_STOCK"},
              {"merchantSku":"UNKNOWN-SNACK","gtin":"8901234500003","brand":"Safe Foods","canonicalName":"High Protein Lentil Bites","variant":"Plain","sizeStorage":"100 g","category":"Snacks","description":"vegetarian protein snack","priceMinor":7000,"currency":"INR","stockQuantity":20,"availability":"IN_STOCK"}
            ]}
            """;}
    private void login(HttpClient client,ApplicationActor actor)throws Exception{String token=csrf(client);HttpResponse<String> response=post(client,"/api/auth/login",token,mapper.writeValueAsString(Map.of("identityHandle",actor.identityHandle(),"password","task008-password")));assertThat(response.statusCode()).isEqualTo(200);}
    private String csrf(HttpClient client)throws Exception{HttpResponse<String> response=get(client,"/api/auth/csrf");assertThat(response.statusCode()).isEqualTo(200);return mapper.readTree(response.body()).path("token").asText();}
    private HttpResponse<String> get(HttpClient client,String path)throws Exception{return client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString());}
    private HttpResponse<String> post(HttpClient client,String path,String csrf,String body)throws Exception{var builder=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Content-Type","application/json");if(csrf!=null)builder.header("X-CSRF-TOKEN",csrf);return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}
    record Fixture(Merchant merchant,ApplicationActor admin,ApplicationActor buyer,UUID catalogueVersion,UUID safeProduct,UUID unknownProduct,UUID quoteMapping){}

    @TestConfiguration static class Fakes {
        @Bean @Primary BuyerIntentCompiler buyerIntentCompiler(){return (message,feedback)->{String text=message.normalizedText();int end=text.length();EvidenceSpan span=new EvidenceSpan(message.messageId(),0,end);
            if(text.startsWith("around 500"))return new CompiledIntent(IntentGoal.PURCHASE_FOOD,null,50_000L,"INR",null,null,null,null,"PEANUT",null,null,SubstitutionPolicy.UNKNOWN,null,List.of(),
                    List.of(new MaterialField("BUDGET",ConstraintClassification.HARD,span,new BigDecimal("0.4"),AmbiguityState.AMBIGUOUS),new MaterialField("ALLERGEN",ConstraintClassification.HARD_SAFETY,span,new BigDecimal("0.4"),AmbiguityState.AMBIGUOUS)),AmbiguityState.AMBIGUOUS,"Do you mean ₹500 total, with peanuts completely prohibited?","FAKE","intent-v1");
            if(text.contains("USD 10"))return new CompiledIntent(IntentGoal.PURCHASE_FOOD,"Snacks",1_000L,"USD",null,null,null,null,null,null,null,SubstitutionPolicy.UNKNOWN,null,List.of(),
                    List.of(new MaterialField("BUDGET",ConstraintClassification.HARD,span,BigDecimal.ONE,AmbiguityState.CLEAR)),AmbiguityState.CLEAR,null,"FAKE","intent-v1");
            if(text.contains("MISSING-SKU"))return new CompiledIntent(IntentGoal.PURCHASE_FOOD,null,null,null,"MISSING-SKU",null,null,null,null,1,null,SubstitutionPolicy.UNKNOWN,null,List.of(),List.of(new MaterialField("EXACT_IDENTITY",ConstraintClassification.HARD,span,BigDecimal.ONE,AmbiguityState.CLEAR)),AmbiguityState.CLEAR,null,"FAKE","intent-v1");
            return new CompiledIntent(IntentGoal.PURCHASE_FOOD,"Snacks",50_000L,"INR",null,null,null,true,"PEANUT",null,2,SubstitutionPolicy.UNKNOWN,null,List.of("HIGH_PROTEIN"),
                    List.of(new MaterialField("GOAL",ConstraintClassification.HARD,span,BigDecimal.ONE,AmbiguityState.CLEAR),new MaterialField("BUDGET",ConstraintClassification.HARD,span,new BigDecimal("0.99"),AmbiguityState.CLEAR),new MaterialField("VEGETARIAN",ConstraintClassification.HARD,span,new BigDecimal("0.99"),AmbiguityState.CLEAR),new MaterialField("ALLERGEN",ConstraintClassification.HARD_SAFETY,span,new BigDecimal("0.99"),AmbiguityState.CLEAR),new MaterialField("PREFERENCES",ConstraintClassification.SOFT,span,new BigDecimal("0.9"),AmbiguityState.CLEAR),new MaterialField("PEOPLE",ConstraintClassification.HARD,span,new BigDecimal("0.95"),AmbiguityState.CLEAR)),AmbiguityState.CLEAR,null,"FAKE","intent-v1");};}
        @Bean @Primary CatalogueProvider catalogueProvider(){return barcode->Optional.of(new CatalogueProvider.ExternalProduct(barcode,barcode,"Safe Foods",barcode.endsWith("1")?"High Protein Roasted Chana":barcode.endsWith("2")?"Peanut Protein Bar":"High Protein Lentil Bites",barcode.endsWith("1")?"Masala":barcode.endsWith("2")?"Crunch":"Plain",barcode.endsWith("2")?"60 g":barcode.endsWith("1")?"200 g":"100 g",List.of("captured ingredient"),barcode.endsWith("2")?List.of("peanut"):List.of(),true,20.0,"https://images.example.test/"+barcode,"OFF-test-v1",Instant.now().minus(1,ChronoUnit.DAYS)));}
        @Bean @Primary EmbeddingProvider embeddingProvider(){return input->{List<Float> v=new ArrayList<>(Collections.nCopies(768,0f));v.set(Math.floorMod(input.hashCode(),32),1f);return List.copyOf(v);};}
        @Bean @Primary MerchantDnsResolver merchantDnsResolver(){return host->List.of(InetAddress.getByName("93.184.216.34"));}
        @Bean @Primary FakeTransport fakeTransport(ObjectMapper mapper){return new FakeTransport(mapper);}
    }
    static class FakeTransport implements MerchantTransport {final ObjectMapper mapper;final AtomicInteger calls=new AtomicInteger();volatile String mode="PASS";FakeTransport(ObjectMapper mapper){this.mapper=mapper;}
        public MerchantTransportResponse execute(ValidatedEndpointResolution resolution,MerchantTransportRequest request){calls.incrementAndGet();var in=mapper.readTree(request.jsonBody());var out=mapper.createObjectNode();out.put("quoteId","quote-"+in.path("cartId").asText());out.put("quoteVersion","v1");out.put("cartId",mode.equals("WRONG_CART")?UUID.randomUUID().toString():in.path("cartId").asText());out.put("finalAmountMinor",36_000);out.put("currency",mode.equals("USD")?"USD":"INR");out.put("expiresAt",(mode.equals("EXPIRED")?Instant.now().minusSeconds(10):Instant.now().plusSeconds(600)).toString());out.put("stockGuaranteed",true);out.put("priceGuaranteed",true);var lines=out.putArray("lineItems");
            for(JsonNode row:in.path("lineItems")){var line=lines.addObject();line.put("merchantSku",mode.equals("SUBSTITUTION")?"OTHER-SKU":row.path("merchantSku").asText());line.put("quantity",row.path("quantity").asInt());line.put("unitAmountMinor",18_000);line.put("lineAmountMinor",18_000L*row.path("quantity").asInt());}
            return new MerchantTransportResponse(200,"application/json",mapper.writeValueAsBytes(out));}}
}
