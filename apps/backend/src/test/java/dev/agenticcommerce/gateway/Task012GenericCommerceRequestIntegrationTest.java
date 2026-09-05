package dev.agenticcommerce.gateway;

import static dev.agenticcommerce.gateway.catalogue.CatalogueModels.*;
import static dev.agenticcommerce.gateway.intent.BuyerModels.*;
import static dev.agenticcommerce.gateway.intent.CommerceRequestModels.*;
import static org.assertj.core.api.Assertions.*;

import dev.agenticcommerce.gateway.agentization.execution.*;
import dev.agenticcommerce.gateway.catalogue.*;
import dev.agenticcommerce.gateway.identity.model.*;
import dev.agenticcommerce.gateway.identity.persistence.*;
import dev.agenticcommerce.gateway.intent.*;
import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
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
@Import(Task012GenericCommerceRequestIntegrationTest.Fakes.class)
class Task012GenericCommerceRequestIntegrationTest {
    @Container @ServiceConnection static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("pgvector/pgvector:0.8.1-pg17");
    @Autowired JdbcClient jdbc;@Autowired MerchantRepository merchants;@Autowired ApplicationActorRepository actors;
    @Autowired MerchantAdminMembershipRepository memberships;@Autowired CatalogueService catalogueService;
    @Autowired CatalogueRepository catalogueRepository;@Autowired CommerceRequestService commerceRequests;
    @Autowired BuyerThreadService threads;@Autowired ObjectMapper mapper;@Autowired FakeTransport transport;
    @Autowired ActorPasswordCredentialRepository credentials;@Autowired PasswordEncoder passwordEncoder;@LocalServerPort int port;

    @BeforeEach void clear(){transport.calls.set(0);jdbc.sql("TRUNCATE TABLE merchant,application_actor CASCADE").update();}

    @Test void v012MigrationGeneralizesGoalsFactsAndCreatesDurableRequestIdentity(){
        assertThat(jdbc.sql("SELECT count(*)::int FROM flyway_schema_history WHERE version='012' AND success").query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*)::int FROM information_schema.columns WHERE table_name='buyer_intent' AND column_name IN ('exact_brand','exact_size_storage','exact_colour')").query(Integer.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("SELECT count(*)::int FROM information_schema.tables WHERE table_name='buyer_commerce_request'").query(Integer.class).single()).isOne();
    }

    @Test void genericFoodRequestUsesPrimarySafetyFactsQuoteAndStopsBeforeMoney(){Fixture f=fixture("food");UUID request=UUID.randomUUID();
        CommerceRequestResult result=commerceRequests.execute(f.buyer.id(),request,null,"high-protein vegetarian snacks under 500 rupees, peanuts prohibited");
        assertThat(result.requestStatus()).isEqualTo(RequestStatus.COMPLETED);assertThat(result.state()).isEqualTo(BuyerState.CONSTRAINTS_VERIFIED);
        assertThat(result.goal()).isEqualTo(IntentGoal.PURCHASE_PRODUCT);assertThat(result.merchantId()).isEqualTo(f.grocery.id());assertThat(result.catalogueVersion()).startsWith("v1:");assertThat(result.products()).singleElement().satisfies(line->{
            assertThat(line.merchantSku()).isEqualTo("SYN-GROCERY-CHANA-200");assertThat(line.lineAmountMinor()).isEqualTo(21_500L);
            assertThat(line.facts()).anySatisfy(fact->{assertThat(fact.type()).isEqualTo("IMAGE");assertThat(fact.value().asText()).isEqualTo("/demo/products/roasted-chana.svg");});
            assertThat(line.facts()).anySatisfy(fact->{assertThat(fact.type()).isEqualTo("ALLERGEN");assertThat(fact.authorityTier()).isEqualTo("PRIMARY");assertThat(fact.value().path("status").asText()).isEqualTo("ABSENT");});});
        assertThat(result.authoritativeFinalAmountMinor()).isEqualTo(21_500L).isNotEqualTo(19_900L);
        assertThat(result.constraints()).extracting(ConstraintSummary::key).contains("CATEGORY","BUDGET","VEGETARIAN","ALLERGEN_PEANUT","QUOTE_VALIDITY");
        assertMoneyBoundary();
    }

    @Test void milkshakeCategoryCannotBeSubstitutedWithProteinSnacks(){Fixture f=fixture("milkshake");CommerceRequestResult result=commerceRequests.execute(f.buyer.id(),UUID.randomUUID(),null,"mango milkshake under 300 rupees");
        assertThat(result.requestStatus()).isEqualTo(RequestStatus.COMPLETED);assertThat(result.products()).singleElement().satisfies(line->{assertThat(line.category()).isEqualTo("Milkshake");assertThat(line.merchantSku()).isEqualTo("SYN-GROCERY-MANGO-SHAKE-250");});
        assertThat(result.products()).noneMatch(line->line.category().equals("Snacks"));}

    @Test void electronicsUsesExactBrandModelColourAndReturnsSpecificationsAndTrustedRatings(){Fixture f=fixture("electronics");CommerceRequestResult result=commerceRequests.execute(f.buyer.id(),UUID.randomUUID(),null,"Synthetic Sonic A1 black earphones under 5000 rupees");
        assertThat(result.requestStatus()).isEqualTo(RequestStatus.COMPLETED);assertThat(result.merchantId()).isEqualTo(f.electronics.id());
        assertThat(result.products()).singleElement().satisfies(line->{assertThat(line.merchantSku()).isEqualTo("SYN-ELEC-SONIC-A1-BLK");assertThat(line.colour()).isEqualTo("Black");
            assertThat(line.facts()).anySatisfy(fact->{assertThat(fact.type()).isEqualTo("SPECIFICATION");assertThat(fact.value().path("bluetooth").asText()).isEqualTo("5.3");})
                    .anySatisfy(fact->{assertThat(fact.type()).isEqualTo("RATING");assertThat(fact.value().decimalValue()).isEqualByComparingTo("4.7");})
                    .anySatisfy(fact->assertThat(fact.type()).isEqualTo("REVIEW_COUNT"));});
        assertThat(result.constraints()).filteredOn(c->Set.of("CATEGORY","BRAND","VARIANT","COLOUR").contains(c.key())).allMatch(c->c.result()==ConstraintOutcome.PASS);}

    @Test void ambiguousNamedProductUsesUniqueCatalogueIdentityWithoutRequestingCategory(){Fixture f=fixture("exact-resolution");
        CommerceRequestResult result=commerceRequests.execute(f.buyer.id(),UUID.randomUUID(),null,"Buy one Synthetic Sonic A1 black");
        assertThat(result.requestStatus()).isEqualTo(RequestStatus.COMPLETED);
        assertThat(result.clarificationRequired()).isFalse();assertThat(result.category()).isNull();
        assertThat(result.products()).singleElement().satisfies(line->{assertThat(line.merchantSku()).isEqualTo("SYN-ELEC-SONIC-A1-BLK");assertThat(line.colour()).isEqualTo("Black");});
        assertThat(result.hardRequirements()).extracting(MaterialRequirement::field).containsExactly("BRAND","VARIANT","COLOUR");
        assertThat(result.hardRequirements()).allMatch(field->field.ambiguity()==AmbiguityState.CLEAR);}

    @Test void crossCategoryAndHardIdentityMismatchesProduceGroundedNoMatch(){Fixture f=fixture("nomatch");CommerceRequestResult shoes=commerceRequests.execute(f.buyer.id(),UUID.randomUUID(),null,"shoes under 5000 rupees");
        CommerceRequestResult purple=commerceRequests.execute(f.buyer.id(),UUID.randomUUID(),null,"Synthetic Sonic A1 purple earphones under 5000 rupees");
        assertThat(shoes.requestStatus()).isEqualTo(RequestStatus.WAITING_FOR_USER);assertThat(shoes.products()).isEmpty();assertThat(shoes.clarificationQuestion()).contains("No trustworthy product match");
        assertThat(purple.requestStatus()).isEqualTo(RequestStatus.WAITING_FOR_USER);assertThat(purple.products()).isEmpty();assertThat(transport.calls).hasValue(0);}

    @Test void missingSafetyEvidenceNeverBecomesPass(){Fixture f=fixture("unknown-safety");jdbc.sql("DELETE FROM product_external_fact WHERE merchant_id=:m AND fact_type='ALLERGEN'").param("m",f.grocery.id()).update();
        CommerceRequestResult result=commerceRequests.execute(f.buyer.id(),UUID.randomUUID(),null,"high-protein vegetarian snacks under 500 rupees, peanuts prohibited");
        assertThat(result.requestStatus()).isEqualTo(RequestStatus.WAITING_FOR_USER);assertThat(result.products()).isEmpty();
        assertThat(jdbc.sql("SELECT count(*)::int FROM constraint_result WHERE constraint_key='ALLERGEN_PEANUT' AND result='PASS'").query(Integer.class).single()).isZero();}

    @Test void merchantPrimaryFactsOutrankSecondaryOffAndOffIsNotRequired(){Fixture f=fixture("precedence");UUID product=product(f.grocery,"SYN-GROCERY-CHANA-200");UUID resolution=catalogueRepository.insertResolution(f.grocery.id(),f.groceryVersion,product,"OPEN_FOOD_FACTS","off-conflict",IdentityOutcome.EXACT,mapper.createObjectNode().put("barcode","8909000000001"),mapper.createObjectNode(),"9".repeat(64)).id();
        catalogueRepository.insertFact(f.grocery.id(),f.groceryVersion,product,resolution,"RATING",mapper.getNodeFactory().numberNode(1.0),"OPEN_FOOD_FACTS","off-rating","off-v1","SECONDARY","ACTIVE",Instant.now(),Instant.now().plus(30,ChronoUnit.DAYS),"8".repeat(64));
        CommerceRequestResult result=commerceRequests.execute(f.buyer.id(),UUID.randomUUID(),null,"high-protein vegetarian snacks under 500 rupees, peanuts prohibited");
        assertThat(result.products().getFirst().facts()).filteredOn(fact->fact.type().equals("RATING")).singleElement().satisfies(fact->{assertThat(fact.authorityTier()).isEqualTo("PRIMARY");assertThat(fact.value().decimalValue()).isEqualByComparingTo("4.6");});
        assertThat(jdbc.sql("SELECT count(*)::int FROM product_external_fact WHERE merchant_id=:m AND source='OPEN_FOOD_FACTS'").param("m",f.electronics.id()).query(Integer.class).single()).isZero();}

    @Test void ratingsRemainAbsentWhenMissingAndCannotOverrideHardMismatch(){Fixture f=fixture("ratings");CommerceRequestResult white=commerceRequests.execute(f.buyer.id(),UUID.randomUUID(),null,"Synthetic Sonic A1 white earphones under 5000 rupees");
        assertThat(white.products()).singleElement().satisfies(line->{assertThat(line.colour()).isEqualTo("White");assertThat(line.facts()).noneMatch(fact->Set.of("RATING","REVIEW_COUNT").contains(fact.type()));});
        CommerceRequestResult shoes=commerceRequests.execute(f.buyer.id(),UUID.randomUUID(),null,"shoes under 5000 rupees");assertThat(shoes.products()).isEmpty();}

    @Test void requestIdempotencyReturnsStableResultAndRejectsMaterialMismatch(){Fixture f=fixture("idempotency");UUID request=UUID.randomUUID();CommerceRequestResult first=commerceRequests.execute(f.buyer.id(),request,null,"mango milkshake under 300 rupees");CommerceRequestResult retry=commerceRequests.execute(f.buyer.id(),request,null,"mango milkshake under 300 rupees");
        assertThat(retry).isEqualTo(first);assertThat(jdbc.sql("SELECT count(*)::int FROM buyer_commerce_request WHERE buyer_actor_id=:b AND request_id=:r").param("b",f.buyer.id()).param("r",request).query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*)::int FROM commerce_thread WHERE buyer_actor_id=:b").param("b",f.buyer.id()).query(Integer.class).single()).isOne();
        assertThatThrownBy(()->commerceRequests.execute(f.buyer.id(),request,null,"shoes under 5000 rupees")).isInstanceOfSatisfying(BuyerException.class,e->assertThat(e.code()).isEqualTo("COMMERCE_REQUEST_IDEMPOTENCY_CONFLICT"));}

    @Test void runningRequestReadReturnsAttachableDurableStateInsteadOfRawConflict(){Fixture f=fixture("running-recovery");CommerceThread thread=threads.create(f.buyer.id(),"mango milkshake under 300 rupees");UUID request=UUID.randomUUID();
        jdbc.sql("INSERT INTO buyer_commerce_request(request_id,buyer_actor_id,requested_thread_id,thread_id,normalized_text,material_hash) VALUES(:request,:buyer,:thread,:thread,:text,:hash)")
                .param("request",request).param("buyer",f.buyer.id()).param("thread",thread.threadId()).param("text","mango milkshake under 300 rupees").param("hash","a".repeat(64)).update();
        CommerceRequestResult running=commerceRequests.get(f.buyer.id(),request);
        assertThat(running.requestId()).isEqualTo(request);assertThat(running.threadId()).isEqualTo(thread.threadId());assertThat(running.requestStatus()).isEqualTo(RequestStatus.RUNNING);
        assertThat(running.products()).isEmpty();assertThat(running.paymentReady()).isFalse();}

    @Test void correctionCreatesFreshIntentAndLegacyFoodRemainsReadable(){Fixture f=fixture("correction");CommerceRequestResult first=commerceRequests.execute(f.buyer.id(),UUID.randomUUID(),null,"mango milkshake under 300 rupees");CommerceRequestResult corrected=commerceRequests.execute(f.buyer.id(),UUID.randomUUID(),first.threadId(),"change to high-protein vegetarian snacks under 500 rupees, peanuts prohibited");
        assertThat(corrected.currentIntentVersion()).isEqualTo(2);assertThat(jdbc.sql("SELECT count(*)::int FROM buyer_intent WHERE thread_id=:t").param("t",first.threadId()).query(Integer.class).single()).isEqualTo(2);
        assertThat(corrected.products().getFirst().category()).isEqualTo("Snacks");CommerceRequestResult legacy=commerceRequests.execute(f.buyer.id(),UUID.randomUUID(),null,"legacy food purchase");assertThat(legacy.goal()).isEqualTo(IntentGoal.PURCHASE_FOOD);}

    @Test void statusFollowUpReturnsCurrentThreadEvidenceWithoutDiscardingOrRecompilingIntent(){Fixture f=fixture("status-follow-up");CommerceRequestResult first=commerceRequests.execute(f.buyer.id(),UUID.randomUUID(),null,"mango milkshake under 300 rupees");
        CommerceRequestResult status=commerceRequests.execute(f.buyer.id(),UUID.randomUUID(),first.threadId(),"what happened?");
        assertThat(status.threadId()).isEqualTo(first.threadId());assertThat(status.currentIntentVersion()).isEqualTo(first.currentIntentVersion());
        assertThat(status.products()).extracting(AuthoritativeProductLine::merchantSku).containsExactly("SYN-GROCERY-MANGO-SHAKE-250");
        assertThat(jdbc.sql("SELECT count(*)::int FROM buyer_intent WHERE thread_id=:t").param("t",first.threadId()).query(Integer.class).single()).isOne();
        assertThat(threads.messages(f.buyer.id(),first.threadId())).extracting(ThreadMessage::normalizedText).containsExactly("mango milkshake under 300 rupees","what happened?");}

    @Test void deterministicCompilerFailureIsPersistedAndIdempotentlyRecoverable(){Fixture f=fixture("failure");UUID request=UUID.randomUUID();
        CommerceRequestResult failed=commerceRequests.execute(f.buyer.id(),request,null,"underspecified request");
        assertThat(failed.requestStatus()).isEqualTo(RequestStatus.FAILED);assertThat(failed.failureCode()).isEqualTo("INVALID_BUYER_INTENT");
        assertThat(commerceRequests.get(f.buyer.id(),request)).isEqualTo(failed);assertThat(commerceRequests.execute(f.buyer.id(),request,null,"underspecified request")).isEqualTo(failed);}

    @Test void unsafeOrUnboundedMerchantFactsAreRejected(){Merchant merchant=merchants.create("unsafe-facts","Unsafe Facts");ApplicationActor admin=actors.create("unsafe-facts-admin@test",PlatformRole.MERCHANT_ADMIN);memberships.create(merchant.id(),admin.id());String payload="""
            {"products":[{"merchantSku":"BAD-1","canonicalName":"Bad Fact","category":"Test","facts":[{"type":"IMAGE","value":"javascript:alert(1)"}]}]}
            """;assertThatThrownBy(()->catalogueService.ingest(admin.id(),merchant.id(),"JSON",payload)).isInstanceOf(RuntimeException.class);}

    @Test void apiUsesVerifiedBuyerOwnershipRoleAndCsrf()throws Exception{Fixture f=fixture("http");ApplicationActor other=actors.create("task012-other@test",PlatformRole.BUYER),admin=actors.create("task012-role-admin@test",PlatformRole.MERCHANT_ADMIN);
        for(ApplicationActor actor:List.of(f.buyer,other,admin))credentials.createArgon2Credential(actor.id(),passwordEncoder.encode("task012-password"),true);
        CookieManager ownerCookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);HttpClient owner=HttpClient.newBuilder().cookieHandler(ownerCookies).build();login(owner,f.buyer);UUID request=UUID.randomUUID();String body=mapper.writeValueAsString(Map.of("requestId",request,"text","mango milkshake under 300 rupees"));
        assertThat(post(owner,"/api/buyer/commerce-requests",null,body).statusCode()).isEqualTo(403);String token=csrf(owner);HttpResponse<String> response=post(owner,"/api/buyer/commerce-requests",token,body);assertThat(response.statusCode()).isEqualTo(200);
        CommerceRequestResult created=mapper.readValue(response.body(),CommerceRequestResult.class);
        HttpResponse<String> current=get(owner,"/api/buyer/commerce-requests/thread/"+created.threadId());assertThat(current.statusCode()).isEqualTo(200);assertThat(mapper.readValue(current.body(),CommerceRequestResult.class).requestId()).isEqualTo(request);
        CookieManager otherCookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);HttpClient otherClient=HttpClient.newBuilder().cookieHandler(otherCookies).build();login(otherClient,other);assertThat(get(otherClient,"/api/buyer/commerce-requests/"+request).statusCode()).isEqualTo(404);assertThat(get(otherClient,"/api/buyer/commerce-requests/thread/"+created.threadId()).statusCode()).isEqualTo(404);
        CookieManager adminCookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);HttpClient adminClient=HttpClient.newBuilder().cookieHandler(adminCookies).build();login(adminClient,admin);assertThat(post(adminClient,"/api/buyer/commerce-requests",csrf(adminClient),body).statusCode()).isEqualTo(403);}

    private Fixture fixture(String key){Merchant grocery=merchants.create("synthetic-grocery-"+key,"Synthetic Grocery "+key),electronics=merchants.create("synthetic-electronics-"+key,"Synthetic Electronics "+key);ApplicationActor groceryAdmin=actors.create(key+"-grocery-admin@test",PlatformRole.MERCHANT_ADMIN),electronicsAdmin=actors.create(key+"-electronics-admin@test",PlatformRole.MERCHANT_ADMIN),buyer=actors.create(key+"-buyer@test",PlatformRole.BUYER);memberships.create(grocery.id(),groceryAdmin.id());memberships.create(electronics.id(),electronicsAdmin.id());
        CatalogueVersion groceryVersion=catalogueService.ingest(groceryAdmin.id(),grocery.id(),"JSON",demo("synthetic-grocery-catalogue-v1.json")).version();CatalogueVersion electronicsVersion=catalogueService.ingest(electronicsAdmin.id(),electronics.id(),"JSON",demo("synthetic-electronics-catalogue-v1.json")).version();publishReady(grocery,groceryAdmin,groceryVersion);publishReady(electronics,electronicsAdmin,electronicsVersion);return new Fixture(grocery,electronics,buyer,groceryVersion.id(),electronicsVersion.id());}
    private String demo(String name){try{return Files.readString(Path.of("..","..","evaluation","demo-data",name));}catch(Exception e){throw new IllegalStateException(e);}}
    private UUID product(Merchant merchant,String sku){CatalogueVersion version=catalogueRepository.latestPublished(merchant.id()).orElseThrow();return catalogueRepository.products(merchant.id(),version.id(),20).stream().filter(p->p.merchantSku().equals(sku)).findFirst().orElseThrow().id();}

    private UUID publishReady(Merchant merchant,ApplicationActor admin,CatalogueVersion version){UUID endpoint=jdbc.sql("INSERT INTO merchant_approved_endpoint(merchant_id,base_uri,hostname,approved_at,dns_validated_at) VALUES(:m,'https://merchant.example.test','merchant.example.test',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING endpoint_id").param("m",merchant.id()).query(UUID.class).single();UUID artifact=jdbc.sql("INSERT INTO openapi_artifact(merchant_id,endpoint_id,artifact_type,artifact_version,content_hash,document) VALUES(:m,:e,'OPENAPI','v1',:h,'{}') RETURNING artifact_id").param("m",merchant.id()).param("e",endpoint).param("h","1".repeat(64)).query(UUID.class).single();UUID run=jdbc.sql("INSERT INTO agentization_run(merchant_id,created_by_actor_id,source_artifact_id,target_capability,current_capability,orchestration_state,max_step_budget,wall_clock_deadline) VALUES(:m,:a,:artifact,'GET_QUOTE','GET_QUOTE','READY_CANDIDATE',20,CURRENT_TIMESTAMP+interval '1 hour') RETURNING run_id").param("m",merchant.id()).param("a",admin.id()).param("artifact",artifact).query(UUID.class).single();UUID mapping=jdbc.sql("""
            INSERT INTO capability_mapping_proposal(merchant_id,run_id,capability,mapping_version,source_artifact_id,endpoint_id,source_operation_id,http_method,path_template,request_bindings,response_bindings,transformations,amount_interpretation,currency_interpretation,model_provider,model_name,proposal_status,validation_status)
            VALUES(:m,:run,'GET_QUOTE',1,:artifact,:endpoint,'quote','POST','/quotes','{"cartId":"body.cartId"}','{"amount":"body.finalAmountMinor","currency":"body.currency","quoteId":"body.quoteId"}','{"amount":"IDENTITY"}','{"unit":"minor"}','{"field":"body.currency"}','TEST','fixture','AWAITING_APPROVAL','VALID') RETURNING mapping_proposal_id
            """).param("m",merchant.id()).param("run",run).param("artifact",artifact).param("endpoint",endpoint).query(UUID.class).single();UUID searchEval=readiness(merchant.id(),run,"SEARCH_PRODUCTS",null),quoteEval=readiness(merchant.id(),run,"GET_QUOTE",mapping);UUID manifest=jdbc.sql("INSERT INTO agent_commerce_manifest(merchant_id,agentization_run_id,manifest_version,catalogue_version,publication_actor_id,publication_component,manifest_hash) VALUES(:m,:run,1,:catalogue,:actor,'DETERMINISTIC_READINESS_REDUCER',:hash) RETURNING manifest_id").param("m",merchant.id()).param("run",run).param("catalogue","v"+version.version()+":"+version.contentHash()).param("actor",admin.id()).param("hash","3".repeat(64)).query(UUID.class).single();
        jdbc.sql("INSERT INTO agent_commerce_manifest_capability(manifest_id,merchant_id,capability,advertised,readiness,executable_mapping_proposal_id,readiness_evaluation_id) VALUES(:manifest,:m,'SEARCH_PRODUCTS',true,'READY',NULL,:evaluation)").param("manifest",manifest).param("m",merchant.id()).param("evaluation",searchEval).update();jdbc.sql("INSERT INTO agent_commerce_manifest_capability(manifest_id,merchant_id,capability,advertised,readiness,executable_mapping_proposal_id,readiness_evaluation_id) VALUES(:manifest,:m,'GET_QUOTE',true,'READY',:mapping,:evaluation)").param("manifest",manifest).param("m",merchant.id()).param("mapping",mapping).param("evaluation",quoteEval).update();return mapping;}
    private UUID readiness(UUID merchant,UUID run,String capability,UUID mapping){return jdbc.sql("INSERT INTO capability_readiness_evaluation(merchant_id,agentization_run_id,capability,readiness,mapping_proposal_id,mapping_version,mapping_content_hash,required_evidence,satisfied_evidence,missing_requirements,blocking_evidence,evidence_references,evaluation_hash) VALUES(:m,:run,:capability,'READY',:mapping,:version,:mappingHash,'[]','[]','[]','[]','[]',:hash) RETURNING readiness_evaluation_id").param("m",merchant).param("run",run).param("capability",capability).param("mapping",mapping).param("version",mapping==null?null:1).param("mappingHash",mapping==null?null:"4".repeat(64)).param("hash",capability.equals("SEARCH_PRODUCTS")?"5".repeat(64):"6".repeat(64)).query(UUID.class).single();}
    private void assertMoneyBoundary(){for(String table:List.of("transaction_proposal","authorization_decision","transaction_execution","payment_provider_order","payment_control"))assertThat(jdbc.sql("SELECT count(*)::int FROM "+table).query(Integer.class).single()).as(table).isZero();}
    private void login(HttpClient client,ApplicationActor actor)throws Exception{String token=csrf(client);assertThat(post(client,"/api/auth/login",token,mapper.writeValueAsString(Map.of("identityHandle",actor.identityHandle(),"password","task012-password"))).statusCode()).isEqualTo(200);}
    private String csrf(HttpClient client)throws Exception{HttpResponse<String> response=get(client,"/api/auth/csrf");assertThat(response.statusCode()).isEqualTo(200);return mapper.readTree(response.body()).path("token").asText();}
    private HttpResponse<String> get(HttpClient client,String path)throws Exception{return client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString());}
    private HttpResponse<String> post(HttpClient client,String path,String csrf,String body)throws Exception{var builder=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Content-Type","application/json");if(csrf!=null)builder.header("X-CSRF-TOKEN",csrf);return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}
    record Fixture(Merchant grocery,Merchant electronics,ApplicationActor buyer,UUID groceryVersion,UUID electronicsVersion){}

    @TestConfiguration static class Fakes {
        @Bean @Primary BuyerIntentCompiler compiler(){return (message,feedback)->compile(message);}
        private static CompiledIntent compile(ThreadMessage message){String lower=message.normalizedText().toLowerCase(Locale.ROOT);EvidenceSpan span=new EvidenceSpan(message.messageId(),0,message.normalizedText().length());
            if(lower.equals("buy one synthetic sonic a1 black"))return ambiguousExact(span);
            if(lower.contains("legacy"))return intent(IntentGoal.PURCHASE_FOOD,"Snacks",null,null,null,null,null,null,null,false,null,List.of(),span);
            if(lower.contains("underspecified"))return intent(IntentGoal.PURCHASE_PRODUCT,null,null,null,null,null,null,null,null,false,null,List.of(),span);
            if(lower.contains("shoes"))return intent(IntentGoal.PURCHASE_PRODUCT,"Shoes",500_000L,null,null,null,null,null,null,false,null,List.of(),span);
            if(lower.contains("earphones")){String colour=lower.contains("purple")?"Purple":lower.contains("white")?"White":"Black";return intent(IntentGoal.PURCHASE_PRODUCT,"Earphones",500_000L,"Synthetic Sonic","A1",null,colour,null,null,false,null,List.of(),span);}
            if(lower.contains("milkshake"))return intent(IntentGoal.PURCHASE_PRODUCT,"Milkshake",30_000L,null,null,null,null,null,null,false,null,List.of(),span);
            return intent(IntentGoal.PURCHASE_PRODUCT,"Snacks",50_000L,null,null,null,null,null,null,true,"PEANUT",List.of("HIGH_PROTEIN"),span);}
        private static CompiledIntent intent(IntentGoal goal,String category,Long budget,String brand,String variant,String size,String colour,String sku,String gtin,boolean vegetarian,String allergen,List<String> preferences,EvidenceSpan span){List<MaterialField> fields=new ArrayList<>();fields.add(field("CATEGORY",ConstraintClassification.HARD,span));if(budget!=null)fields.add(field("BUDGET",ConstraintClassification.HARD,span));if(brand!=null)fields.add(field("BRAND",ConstraintClassification.HARD,span));if(variant!=null)fields.add(field("VARIANT",ConstraintClassification.HARD,span));if(size!=null)fields.add(field("SIZE_STORAGE",ConstraintClassification.HARD,span));if(colour!=null)fields.add(field("COLOUR",ConstraintClassification.HARD,span));if(sku!=null)fields.add(field("MERCHANT_SKU",ConstraintClassification.HARD,span));if(gtin!=null)fields.add(field("GTIN",ConstraintClassification.HARD,span));if(vegetarian)fields.add(field("VEGETARIAN",ConstraintClassification.HARD,span));if(allergen!=null)fields.add(field("ALLERGEN",ConstraintClassification.HARD_SAFETY,span));if(!preferences.isEmpty())fields.add(field("PREFERENCES",ConstraintClassification.SOFT,span));
            return new CompiledIntent(goal,category,budget,budget==null?null:"INR",sku,gtin,brand,variant,size,colour,vegetarian?true:null,allergen,1,null,SubstitutionPolicy.PROHIBIT,null,preferences,List.copyOf(fields),AmbiguityState.CLEAR,null,"FAKE","task012-intent-v1");}
        private static CompiledIntent ambiguousExact(EvidenceSpan span){List<MaterialField> fields=List.of(
                ambiguous("BRAND",span),ambiguous("VARIANT",span),ambiguous("COLOUR",span));
            return new CompiledIntent(IntentGoal.PURCHASE_PRODUCT,null,null,null,null,null,"Synthetic","Sonic A1",null,"black",
                    null,null,1,null,SubstitutionPolicy.UNKNOWN,null,List.of(),fields,AmbiguityState.AMBIGUOUS,
                    "Could you please specify the product category for the Synthetic Sonic A1 black?","FAKE","task0134-intent-v1");}
        private static MaterialField field(String name,ConstraintClassification classification,EvidenceSpan span){return new MaterialField(name,classification,span,BigDecimal.ONE,AmbiguityState.CLEAR);}
        private static MaterialField ambiguous(String name,EvidenceSpan span){return new MaterialField(name,ConstraintClassification.HARD,span,BigDecimal.ONE,AmbiguityState.AMBIGUOUS);}
        @Bean @Primary CatalogueProvider catalogueProvider(){return barcode->Optional.empty();}
        @Bean @Primary EmbeddingProvider embeddings(){return input->{List<Float> values=new ArrayList<>(Collections.nCopies(768,0f));values.set(Math.floorMod(input.hashCode(),32),1f);return List.copyOf(values);};}
        @Bean @Primary MerchantDnsResolver dns()throws Exception{return host->List.of(InetAddress.getByName("93.184.216.34"));}
        @Bean @Primary FakeTransport transport(ObjectMapper mapper){return new FakeTransport(mapper);}
    }
    static class FakeTransport implements MerchantTransport {final ObjectMapper mapper;final AtomicInteger calls=new AtomicInteger();FakeTransport(ObjectMapper mapper){this.mapper=mapper;}public MerchantTransportResponse execute(ValidatedEndpointResolution resolution,MerchantTransportRequest request){calls.incrementAndGet();JsonNode input=mapper.readTree(request.jsonBody());var output=mapper.createObjectNode();output.put("quoteId","task012-"+input.path("cartId").asText());output.put("quoteVersion","v1");output.put("cartId",input.path("cartId").asText());output.put("currency","INR");output.put("expiresAt",Instant.now().plusSeconds(600).toString());output.put("stockGuaranteed",true);output.put("priceGuaranteed",true);var lines=output.putArray("lineItems");long total=0;for(JsonNode item:input.path("lineItems")){String sku=item.path("merchantSku").asText();int quantity=item.path("quantity").asInt();long unit=sku.contains("CHANA")?21_500:sku.contains("MANGO")?13_900:sku.contains("WHT")?429_900:sku.contains("A1-BLK")?459_900:319_900;total+=unit*quantity;var line=lines.addObject();line.put("merchantSku",sku);line.put("quantity",quantity);line.put("unitAmountMinor",unit);line.put("lineAmountMinor",unit*quantity);}output.put("subtotalMinor",total);output.put("finalAmountMinor",total);return new MerchantTransportResponse(200,"application/json",mapper.writeValueAsBytes(output));}}
}
