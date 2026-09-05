package dev.agenticcommerce.gateway;

import static dev.agenticcommerce.gateway.commerce.TransactionModels.*;
import static dev.agenticcommerce.gateway.intent.BuyerModels.*;
import static dev.agenticcommerce.gateway.intent.CommerceRequestModels.*;
import static dev.agenticcommerce.gateway.payment.PaymentModels.*;
import static org.assertj.core.api.Assertions.*;

import dev.agenticcommerce.gateway.authorization.AuthorizationService;
import dev.agenticcommerce.gateway.authorization.ExecutionGate;
import dev.agenticcommerce.gateway.demo.DemoBootstrapService;
import dev.agenticcommerce.gateway.identity.model.ApplicationActor;
import dev.agenticcommerce.gateway.identity.model.PlatformRole;
import dev.agenticcommerce.gateway.identity.persistence.ApplicationActorRepository;
import dev.agenticcommerce.gateway.intent.*;
import dev.agenticcommerce.gateway.payment.*;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties="payment.outbox.poll-delay-ms=3600000")
@Import({Task0126DemoCommerceIntegrationTest.Fakes.class,
        Task013SafeBuyerEndToEndIntegrationTest.IntentFake.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Task013SafeBuyerEndToEndIntegrationTest {
    @Container @ServiceConnection static final PostgreSQLContainer POSTGRES=
            new PostgreSQLContainer("pgvector/pgvector:0.8.1-pg17");
    @Autowired JdbcClient jdbc;@Autowired DemoBootstrapService bootstrap;
    @Autowired CommerceRequestService commerce;@Autowired BuyerThreadService threads;
    @Autowired ApplicationActorRepository actors;
    @Autowired Task0126DemoCommerceIntegrationTest.Fakes.AuthenticatedFakeTransport transport;
    @Autowired Task014PaymentProvider paymentProvider;@Autowired PaymentOutboxWorker paymentWorker;
    @LocalServerPort int port;
    private UUID buyerId;

    @BeforeAll void seed()throws Exception{jdbc.sql("TRUNCATE TABLE merchant,application_actor CASCADE").update();
        bootstrap.bootstrap("https://merchant.example.test","task013-evaluator@demo.invalid",
                "task013-not-a-real-password",legacyFixtureRoot());
        var summary=bootstrap.bootstrap("https://merchant.example.test","task013-evaluator@demo.invalid",
                "task013-not-a-real-password",Path.of("..","..","evaluation","demo-data"));
        assertThat(summary.blockers()).isEmpty();buyerId=summary.buyerActorId();}
    @BeforeEach void clearRequests(){transport.resetRuntimeModes();paymentProvider.reset();
        jdbc.sql("TRUNCATE TABLE commerce_thread CASCADE").update();jdbc.sql("TRUNCATE TABLE demo_merchant_order").update();
        jdbc.sql("""
                UPDATE demo_merchant_inventory inventory SET available_quantity=commerce.stock_quantity,
                    inventory_version=1,updated_at=CURRENT_TIMESTAMP
                FROM merchant_product_commerce_state commerce
                WHERE commerce.merchant_id=inventory.merchant_id AND commerce.product_id=inventory.product_id
                """).update();}

    @Test void genericPurchasePreparesImmutableProposalAndWaitsForExplicitCheckout(){UUID requestId=UUID.randomUUID();
        CommerceRequestResult result=commerce.execute(buyerId,requestId,null,"Find me good wireless earphones under 3000 rupees");
        assertThat(result.requestStatus()).isEqualTo(RequestStatus.COMPLETED);
        assertThat(result.state()).isEqualTo(BuyerState.WAITING_FOR_USER);
        assertThat(result.paymentReady()).isTrue();assertThat(result.explicitAuthorizationRequired()).isTrue();
        assertThat(result.authorizationState()).isEqualTo("WAITING_FOR_EXPLICIT_PAYMENT_AUTHORIZATION");
        assertThat(result.nextAction()).isEqualTo("AUTHORIZE_RAZORPAY_CHECKOUT");
        assertThat(result.availabilityOutcome()).isEqualTo(EvidenceOutcome.PASS);
        assertThat(result.serviceabilityOutcome()).isEqualTo(EvidenceOutcome.PASS);
        assertThat(result.constraintOverall()).isEqualTo(ConstraintOutcome.PASS);
        assertThat(result.transactionProposalId()).isNotNull();assertThat(result.transactionProposalHash()).matches("[0-9a-f]{64}");
        assertThat(result.riskOutcome()).isEqualTo(ReversibilityOutcome.EXPLICIT_CONFIRMATION);
        assertThat(result.riskReasonCodes()).contains("P0_RAZORPAY_CHECKOUT_CONFIRMATION_REQUIRED");
        assertThat(result.progress()).extracting(CommerceProgressStep::code).containsExactly(
                "INTENT_COMPILED","MERCHANTS_CHECKED","PRODUCTS_COMPARED","QUOTE_RECEIVED",
                "AVAILABILITY_CHECKED","CONSTRAINTS_VERIFIED","PROPOSAL_PREPARED","PAYMENT_CONFIRMATION_REQUIRED");
        assertThat(result.products()).singleElement().satisfies(line->{assertThat(line.category()).isEqualTo("Earphones");assertThat(line.lineAmountMinor()).isEqualTo(299_900L);});
        assertThat(result.authoritativeFinalAmountMinor()).isEqualTo(299_900L);
        CommerceRequestResult replay=commerce.execute(buyerId,requestId,null,"Find me good wireless earphones under 3000 rupees");
        assertThat(replay).isEqualTo(result);
        assertThat(jdbc.sql("SELECT count(*)::int FROM transaction_proposal").query(Integer.class).single()).isOne();
        assertNoPaymentOrFulfilment();}

    @Test void authenticatedExactSkuRequestUsesRotatedCsrfAndFreshRemoteClockEvidence()throws Exception{
        transport.skewAvailabilityClock(122);UUID requestId=UUID.randomUUID();
        CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);HttpClient client=HttpClient.newBuilder().cookieHandler(cookies).build();
        String preLoginCsrf=csrf(client);HttpResponse<String> login=post(client,"/api/auth/login",preLoginCsrf,
                mapper().writeValueAsString(Map.of("identityHandle","task013-evaluator@demo.invalid","password","task013-not-a-real-password")));
        String postLoginCsrf=csrf(client);
        assertThat(login.statusCode()).isEqualTo(200);HttpResponse<String> me=get(client,"/api/auth/me");assertThat(me.statusCode()).isEqualTo(200);assertThat(me.body()).contains("\"role\":\"BUYER\"");
        String body=mapper().writeValueAsString(Map.of("requestId",requestId,"text","Buy one product with merchant SKU AMZ-AUDIO-032"));
        HttpResponse<String> created=post(client,"/api/buyer/commerce-requests",postLoginCsrf,body);assertThat(created.statusCode()).isEqualTo(200);
        HttpResponse<String> staleCsrf=post(client,"/api/buyer/commerce-requests",preLoginCsrf,body);assertThat(staleCsrf.statusCode()).isEqualTo(403);assertThat(staleCsrf.body()).contains("forbidden");
        CommerceRequestResult result=mapper().readValue(created.body(),CommerceRequestResult.class);
        assertThat(result.products()).as(mapper().writeValueAsString(result)).singleElement()
                .satisfies(line->assertThat(line.merchantSku()).isEqualTo("AMZ-AUDIO-032"));
        assertThat(result.availabilityOutcome()).isEqualTo(EvidenceOutcome.PASS);assertThat(result.constraintOverall()).isEqualTo(ConstraintOutcome.PASS);
        assertThat(result.transactionProposalId()).isNotNull();assertThat(result.explicitAuthorizationRequired()).isTrue();
        CommerceRequestResult replay=mapper().readValue(post(client,"/api/buyer/commerce-requests",csrf(client),body).body(),CommerceRequestResult.class);
        assertThat(replay.transactionProposalId()).isEqualTo(result.transactionProposalId());assertThat(replay.transactionProposalHash()).isEqualTo(result.transactionProposalHash());
        assertThat(jdbc.sql("SELECT count(*)::int FROM transaction_proposal").query(Integer.class).single()).isOne();assertNoPaymentOrFulfilment();}

    @Test void exactAuralinkNaturalLanguageRequestCreatesOneBoundProposalAfterCatalogueRefresh(){
        UUID requestId=UUID.randomUUID();CommerceRequestResult result=commerce.execute(buyerId,requestId,null,
                "Find Auralink Buds Bluetooth Earphones from Amazing");
        assertThat(result.merchantDisplayName()).isEqualTo("Amazing");
        assertThat(result.merchantLogoUrl()).isEqualTo(DemoBootstrapService.AMAZING_LOGO_URL);
        assertThat(result.products()).singleElement().satisfies(line->{assertThat(line.productName()).isEqualTo("Auralink Buds Bluetooth Earphones");
            assertThat(line.merchantSku()).isEqualTo("AMZ-AUDIO-032");assertThat(line.unitAmountMinor()).isEqualTo(299_900L);
            assertThat(line.facts()).filteredOn(fact->fact.type().equals("IMAGE")).singleElement()
                    .satisfies(fact->assertThat(fact.value().asText()).isEqualTo("/demo/products/auralink-buds.svg"));});
        assertThat(result.authoritativeFinalAmountMinor()).isEqualTo(299_900L);assertThat(result.authoritativeCurrency()).isEqualTo("INR");
        assertThat(result.transactionProposalId()).isNotNull();assertThat(result.paymentReady()).isTrue();
        assertThat(result.state()).isEqualTo(BuyerState.WAITING_FOR_USER);
        assertThat(result.authorizationState()).isEqualTo("WAITING_FOR_EXPLICIT_PAYMENT_AUTHORIZATION");
        CommerceRequestResult replay=commerce.execute(buyerId,requestId,null,
                "Find Auralink Buds Bluetooth Earphones from Amazing");
        assertThat(replay.transactionProposalId()).isEqualTo(result.transactionProposalId());
        assertThat(replay.transactionProposalHash()).isEqualTo(result.transactionProposalHash());
        Map<String,Object> proposal=jdbc.sql("""
                SELECT buyer_actor_id,thread_id,proposal_hash,action_type,canonical_material
                FROM transaction_proposal WHERE proposal_id=:proposal
                """).param("proposal",result.transactionProposalId()).query((row,n)->{
                    Map<String,Object> fields=new LinkedHashMap<>();
                    fields.put("buyer",row.getObject("buyer_actor_id",UUID.class));
                    fields.put("thread",row.getObject("thread_id",UUID.class));
                    fields.put("hash",row.getString("proposal_hash"));
                    fields.put("action",row.getString("action_type"));
                    fields.put("material",row.getString("canonical_material"));
                    return fields;
                }).single();
        assertThat(proposal.get("buyer")).isEqualTo(buyerId);
        assertThat(proposal.get("thread")).isEqualTo(result.threadId());
        assertThat(proposal.get("hash")).isEqualTo(result.transactionProposalHash());
        assertThat(proposal.get("action")).isEqualTo("PURCHASE");
        assertThat((String)proposal.get("material")).contains("fulfilmentAuthority");
        assertThat(jdbc.sql("SELECT count(*)::int FROM transaction_proposal WHERE thread_id=:thread")
                .param("thread",result.threadId()).query(Integer.class).single()).isOne();
        assertNoPaymentOrFulfilment();}

    @Test void visualSneakerHypothesisUsesNormalGroundedProposalPipeline(){
        CommerceRequestResult result=commerce.executeVisual(buyerId,UUID.randomUUID(),null,
                "Find something like this under ₹4,000",new MockMultipartFile("image","reference.png","image/png",png()));
        assertThat(result.visualObservation()).isNotNull();assertThat(result.visualMatchType()).isEqualTo("VISUALLY_SIMILAR_GROUNDED_RESULT");
        assertThat(result.products()).singleElement().satisfies(line->{assertThat(line.merchantSku()).isEqualTo("AMZ-SHOE-035");
            assertThat(line.productName()).isEqualTo("Stride Canvas Low-Top Lifestyle Sneakers");assertThat(line.unitAmountMinor()).isEqualTo(349_900L);});
        assertThat(result.constraints()).filteredOn(c->c.key().equals("BUDGET")).allMatch(c->c.result()==ConstraintOutcome.PASS);
        assertThat(result.constraints()).noneMatch(c->c.key().equals("CATEGORY"));
        assertThat(result.transactionProposalId()).isNotNull();assertThat(result.paymentReady()).isTrue();assertNoPaymentOrFulfilment();}

    @Test void authenticatedMultipartVisualRequestUsesTheBuyerSessionAndCsrfRemainsRequired()throws Exception{
        CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);HttpClient client=HttpClient.newBuilder().cookieHandler(cookies).build();
        String anonymous=csrf(client);assertThat(post(client,"/api/auth/login",anonymous,mapper().writeValueAsString(Map.of(
                "identityHandle","task013-evaluator@demo.invalid","password","task013-not-a-real-password"))).statusCode()).isEqualTo(200);
        String boundary="amana-boundary-"+UUID.randomUUID();UUID requestId=UUID.randomUUID();byte[] body=multipart(boundary,requestId,
                "Find something like this under â‚¹4,000",png());
        HttpResponse<String> denied=postMultipart(client,"/api/buyer/commerce-requests/visual",null,boundary,body);
        assertThat(denied.statusCode()).isEqualTo(403);assertThat(denied.body()).contains("forbidden");
        HttpResponse<String> accepted=postMultipart(client,"/api/buyer/commerce-requests/visual",csrf(client),boundary,body);
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
        CommerceRequestResult result=mapper().readValue(accepted.body(),CommerceRequestResult.class);
        assertThat(result.requestId()).isEqualTo(requestId);assertThat(result.visualObservation()).isNotNull();
        assertThat(result.products()).singleElement().satisfies(product->{assertThat(product.merchantSku()).isEqualTo("AMZ-SHOE-035");
            assertThat(product.facts()).filteredOn(fact->fact.type().equals("IMAGE")).singleElement()
                    .satisfies(fact->assertThat(fact.value().asText()).isEqualTo("/demo/products/stride-white-low-top.svg"));});
    }

    @Test void buyerVoicePreferencePersistsAcrossAuthenticatedRequestsAndPutRequiresCsrf()throws Exception{
        CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);HttpClient client=HttpClient.newBuilder().cookieHandler(cookies).build();
        String anonymous=csrf(client);assertThat(post(client,"/api/auth/login",anonymous,mapper().writeValueAsString(Map.of(
                "identityHandle","task013-evaluator@demo.invalid","password","task013-not-a-real-password"))).statusCode()).isEqualTo(200);
        assertThat(mapper().readTree(get(client,"/api/buyer/settings/voice").body()).path("voiceName").asText()).isEqualTo("Kore");
        HttpResponse<String> denied=put(client,"/api/buyer/settings/voice",null,"{\"voiceName\":\"Puck\"}");
        assertThat(denied.statusCode()).isEqualTo(403);
        HttpResponse<String> saved=put(client,"/api/buyer/settings/voice",csrf(client),"{\"voiceName\":\"Puck\"}");
        assertThat(saved.statusCode()).isEqualTo(200);assertThat(mapper().readTree(saved.body()).path("voiceName").asText()).isEqualTo("Puck");
        assertThat(mapper().readTree(get(client,"/api/buyer/settings/voice").body()).path("voiceName").asText()).isEqualTo("Puck");
    }

    @Test void groundedCrossMerchantDiscoveryBindsProposalToTheSelectedAuthoritativeProduct(){
        CommerceRequestResult result=commerce.execute(buyerId,UUID.randomUUID(),null,"Find an apple under 300 rupees");
        assertThat(result.paymentReady()).isTrue();assertThat(threads.discovery(buyerId,result.threadId()).eligibleMerchants()).hasSize(2);
        assertThat(result.products()).singleElement().satisfies(line->assertThat(jdbc.sql(
                "SELECT merchant_id FROM merchant_product WHERE product_id=:product").param("product",line.productId())
                .query(UUID.class).single()).isEqualTo(result.merchantId()));
        assertThat(jdbc.sql("SELECT merchant_id FROM transaction_proposal WHERE proposal_id=:proposal")
                .param("proposal",result.transactionProposalId()).query(UUID.class).single()).isEqualTo(result.merchantId());
        assertNoPaymentOrFulfilment();}

    @Test void hardBudgetSafetyUnknownAndNoMatchNeverProduceProposals(){
        CommerceRequestResult budget=commerce.execute(buyerId,UUID.randomUUID(),null,"Find headphones under 3000 rupees");
        CommerceRequestResult safety=commerce.execute(buyerId,UUID.randomUUID(),null,"Buy peanut-free oats under 500 rupees");
        CommerceRequestResult absent=commerce.execute(buyerId,UUID.randomUUID(),null,"Find shoes under 100 rupees");
        assertThat(List.of(budget,safety,absent)).allSatisfy(result->{assertThat(result.paymentReady()).isFalse();assertThat(result.transactionProposalId()).isNull();});
        assertThat(jdbc.sql("SELECT count(*)::int FROM constraint_result WHERE constraint_key='ALLERGEN_PEANUT' AND result='PASS'")
                .query(Integer.class).single()).isZero();assertNoPaymentOrFulfilment();}

    @Test void nonReadyAvailabilityAndChangedStockFailClosedWithoutFabricatingProposal(){UUID amazing=merchant("amazing");
        String latest="manifest_id=(SELECT manifest_id FROM agent_commerce_manifest WHERE merchant_id=:merchant ORDER BY manifest_version DESC LIMIT 1)";
        Map<String,Object> binding=jdbc.sql("SELECT executable_mapping_proposal_id,readiness_evaluation_id FROM agent_commerce_manifest_capability WHERE merchant_id=:merchant AND capability='GET_AVAILABILITY' AND "+latest)
                .param("merchant",amazing).query().singleRow();
        jdbc.sql("UPDATE agent_commerce_manifest_capability SET readiness='UNTESTED',advertised=false,executable_mapping_proposal_id=NULL WHERE merchant_id=:merchant AND capability='GET_AVAILABILITY' AND "+latest)
                .param("merchant",amazing).update();
        try{CommerceRequestResult notReady=commerce.execute(buyerId,UUID.randomUUID(),null,"Find me wireless earphones under 3000 rupees");
            assertThat(notReady.requestStatus()).isEqualTo(RequestStatus.FAILED);
            assertThat(notReady.paymentReady()).isFalse();assertThat(notReady.transactionProposalId()).isNull();
            assertThat(notReady.availabilityOutcome()).isEqualTo(EvidenceOutcome.UNKNOWN);
        }finally{jdbc.sql("UPDATE agent_commerce_manifest_capability SET readiness='READY',advertised=true,executable_mapping_proposal_id=:mapping,readiness_evaluation_id=:evaluation WHERE merchant_id=:merchant AND capability='GET_AVAILABILITY' AND "+latest)
                .param("mapping",binding.get("executable_mapping_proposal_id")).param("evaluation",binding.get("readiness_evaluation_id"))
                .param("merchant",amazing).update();}
        transport.unavailableNext();CommerceRequestResult changed=commerce.execute(buyerId,UUID.randomUUID(),null,"Find me wireless earphones under 3000 rupees");
        assertThat(changed.requestStatus()).isEqualTo(RequestStatus.FAILED);
        assertThat(changed.paymentReady()).isFalse();assertThat(changed.transactionProposalId()).isNull();
        assertThat(changed.availabilityOutcome()).isEqualTo(EvidenceOutcome.FAIL);assertNoPaymentOrFulfilment();}

    @Test void expiredMerchantQuoteRequiresRequoteAndCannotBecomePaymentReady(){transport.expireQuoteNext();
        CommerceRequestResult result=commerce.execute(buyerId,UUID.randomUUID(),null,"Find me wireless earphones under 3000 rupees");
        assertThat(result.requestStatus()).isEqualTo(RequestStatus.FAILED);
        assertThat(result.paymentReady()).isFalse();assertThat(result.transactionProposalId()).isNull();
        assertThat(result.failureCode()).isEqualTo("QUOTE_EXPIRED");assertThat(result.nextAction()).isEqualTo("REQUOTE_REQUIRED");
        assertNoPaymentOrFulfilment();}

    @Test void buyerOwnershipProtectsRequestThreadAndProposal(){UUID requestId=UUID.randomUUID();
        CommerceRequestResult result=commerce.execute(buyerId,requestId,null,"Find an apple under 300 rupees");
        ApplicationActor other=actors.create("task013-other@demo.invalid",PlatformRole.BUYER);
        assertThatThrownBy(()->commerce.get(other.id(),requestId)).isInstanceOfSatisfying(BuyerException.class,
                e->assertThat(e.code()).isEqualTo("COMMERCE_REQUEST_NOT_FOUND"));
        assertThatThrownBy(()->threads.cart(other.id(),result.threadId())).isInstanceOf(BuyerException.class);
        assertNoPaymentOrFulfilment();}

    @Test void explicitAuthorizationCreatesOneExecutionOneProviderOrderAndConfirmedPaymentFinalizesOnce()throws Exception{
        CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);HttpClient client=HttpClient.newBuilder().cookieHandler(cookies).build();
        String anonymous=csrf(client);assertThat(post(client,"/api/auth/login",anonymous,mapper().writeValueAsString(Map.of(
                "identityHandle","task013-evaluator@demo.invalid","password","task013-not-a-real-password"))).statusCode()).isEqualTo(200);
        assertThat(get(client,"/api/auth/me").statusCode()).isEqualTo(200);
        String request=mapper().writeValueAsString(Map.of("requestId",UUID.randomUUID(),"text","Buy one product with merchant SKU AMZ-AUDIO-032"));
        JsonNode commerceResult=mapper().readTree(post(client,"/api/buyer/commerce-requests",csrf(client),request).body());
        String thread=commerceResult.path("threadId").asText(),proposal=commerceResult.path("transactionProposalId").asText();
        assertThat(commerceResult.path("authorizationState").asText()).as(commerceResult.toPrettyString())
                .isEqualTo("WAITING_FOR_EXPLICIT_PAYMENT_AUTHORIZATION");
        assertThat(commerceResult.path("authoritativeFinalAmountMinor").asLong()).isEqualTo(299_900L);
        String root="/api/buyer/threads/"+thread+"/transaction/proposals/"+proposal;

        JsonNode authorization=mapper().readTree(post(client,root+"/confirm",csrf(client),"{}").body());
        JsonNode authorizationReplay=mapper().readTree(post(client,root+"/confirm",csrf(client),"{}").body());
        assertThat(authorization.path("decision").asText()).isEqualTo("AUTHORIZED");
        assertThat(authorization.path("authorizationMethod").asText()).isEqualTo("EXPLICIT_CONFIRMATION");
        assertThat(authorizationReplay.path("authorizationId").asText()).isEqualTo(authorization.path("authorizationId").asText());

        JsonNode gate=mapper().readTree(post(client,root+"/executions",csrf(client),"{}").body());
        JsonNode gateReplay=mapper().readTree(post(client,root+"/executions",csrf(client),"{}").body());
        String execution=gate.path("execution").path("executionId").asText();
        assertThat(gate.path("decision").asText()).isEqualTo("ALLOW");
        assertThat(gateReplay.path("execution").path("executionId").asText()).isEqualTo(execution);

        JsonNode payment=mapper().readTree(post(client,root+"/payment/order",csrf(client),"{}").body());
        JsonNode paymentReplay=mapper().readTree(post(client,root+"/payment/order",csrf(client),"{}").body());
        String providerOrder=payment.path("providerOrderId").asText();
        assertThat(providerOrder).isNotBlank().isEqualTo(paymentReplay.path("providerOrderId").asText());
        assertThat(paymentProvider.createCalls).isOne();
        JsonNode checkout=mapper().readTree(get(client,root+"/payment/checkout").body());
        assertThat(checkout.path("publicKeyId").asText()).isEqualTo("rzp_test_task014_public");
        assertThat(checkout.path("providerOrderId").asText()).isEqualTo(providerOrder);
        assertThat(checkout.path("amountMinor").asLong()).isEqualTo(299_900L);
        assertThat(checkout.path("currency").asText()).isEqualTo("INR");

        String callbackBody=mapper().writeValueAsString(Map.of("razorpayPaymentId","pay_task014","razorpayOrderId",providerOrder,
                "razorpaySignature","a".repeat(64)));
        JsonNode callback=mapper().readTree(post(client,root+"/payment/callback",csrf(client),callbackBody).body());
        assertThat(callback.path("accepted").asBoolean()).isTrue();assertThat(callback.path("financialConfirmation").asBoolean()).isFalse();
        assertThat(mapper().readTree(get(client,root+"/payment").body()).path("paymentState").asText()).isEqualTo("PAYMENT_UNCERTAIN");

        paymentProvider.capture("pay_task014");
        JsonNode reconciled=mapper().readTree(post(client,root+"/payment/reconcile",csrf(client),"{}").body());
        assertThat(reconciled.path("state").path("paymentState").asText()).isEqualTo("PAYMENT_CONFIRMED");
        assertThat(jdbc.sql("SELECT count(*)::int FROM transactional_outbox WHERE execution_id=:execution AND work_type='FINALIZE_MERCHANT_ORDER'")
                .param("execution",UUID.fromString(execution)).query(Integer.class).single()).isOne();
        paymentWorker.dispatch();paymentWorker.dispatch();
        JsonNode fulfillment=mapper().readTree(get(client,root+"/fulfillment").body());
        assertThat(fulfillment.path("fulfillmentState").asText()).isEqualTo("FULFILLED");
        assertThat(jdbc.sql("SELECT count(*)::int FROM demo_merchant_order WHERE merchant_operation_id=:operation")
                .param("operation",fulfillment.path("merchantOperationId").asText()).query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("SELECT total_minor FROM demo_merchant_order WHERE merchant_operation_id=:operation")
                .param("operation",fulfillment.path("merchantOperationId").asText()).query(Long.class).single()).isEqualTo(299_900L);
        assertThat(jdbc.sql("SELECT count(*)::int FROM authorization_decision WHERE proposal_id=:proposal")
                .param("proposal",UUID.fromString(proposal)).query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*)::int FROM transaction_execution WHERE proposal_id=:proposal")
                .param("proposal",UUID.fromString(proposal)).query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*)::int FROM payment_provider_order WHERE execution_id=:execution")
                .param("execution",UUID.fromString(execution)).query(Integer.class).single()).isOne();
    }

    private UUID merchant(String key){return jdbc.sql("SELECT merchant_id FROM merchant WHERE merchant_key=:key")
            .param("key",key).query(UUID.class).single();}
    private void assertNoPaymentOrFulfilment(){for(String table:List.of("authorization_decision","transaction_execution",
            "payment_provider_order","payment_control","demo_merchant_order"))assertThat(jdbc.sql("SELECT count(*)::int FROM "+table)
            .query(Integer.class).single()).as(table).isZero();}

    @Autowired tools.jackson.databind.ObjectMapper objectMapper;
    private tools.jackson.databind.ObjectMapper mapper(){return objectMapper;}
    private String csrf(HttpClient client)throws Exception{HttpResponse<String> response=get(client,"/api/auth/csrf");assertThat(response.statusCode()).isEqualTo(200);return mapper().readTree(response.body()).path("token").asText();}
    private HttpResponse<String> get(HttpClient client,String path)throws Exception{return client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString());}
    private HttpResponse<String> post(HttpClient client,String path,String csrf,String body)throws Exception{return client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Content-Type","application/json").header("X-CSRF-TOKEN",csrf).POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}
    private HttpResponse<String> put(HttpClient client,String path,String csrf,String body)throws Exception{HttpRequest.Builder request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Content-Type","application/json");if(csrf!=null)request.header("X-CSRF-TOKEN",csrf);return client.send(request.PUT(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}
    private HttpResponse<String> postMultipart(HttpClient client,String path,String csrf,String boundary,byte[] body)throws Exception{
        HttpRequest.Builder request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path))
                .header("Content-Type","multipart/form-data; boundary="+boundary);
        if(csrf!=null)request.header("X-CSRF-TOKEN",csrf);
        return client.send(request.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),HttpResponse.BodyHandlers.ofString());}
    private static byte[] multipart(String boundary,UUID requestId,String text,byte[] image)throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();String prefix="--"+boundary+"\r\n";
        out.write((prefix+"Content-Disposition: form-data; name=\"requestId\"\r\n\r\n"+requestId+"\r\n").getBytes(StandardCharsets.UTF_8));
        out.write((prefix+"Content-Disposition: form-data; name=\"text\"\r\n\r\n"+text+"\r\n").getBytes(StandardCharsets.UTF_8));
        out.write((prefix+"Content-Disposition: form-data; name=\"image\"; filename=\"reference.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(image);out.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));return out.toByteArray();}
    private static byte[] png(){byte[] bytes=new byte[32];byte[] signature={(byte)0x89,0x50,0x4e,0x47,13,10,26,10};System.arraycopy(signature,0,bytes,0,8);bytes[12]='I';bytes[13]='H';bytes[14]='D';bytes[15]='R';java.nio.ByteBuffer.wrap(bytes,16,8).putInt(640).putInt(480);return bytes;}

    private Path legacyFixtureRoot()throws Exception{
        Path source=Path.of("..","..","evaluation","demo-data");Path root=Files.createTempDirectory("task013-legacy-demo-");
        Files.copy(source.resolve("freshbasket-catalogue-v1.json"),root.resolve("freshbasket-catalogue-v1.json"));
        JsonNode amazing=mapper().readTree(Files.readString(source.resolve("amazing-catalogue-v1.json")));
        for(JsonNode product:amazing.path("products"))if(Set.of("AMZ-AUDIO-032","AMZ-SHOE-035").contains(product.path("merchantSku").asText()))
            ((tools.jackson.databind.node.ObjectNode)product).remove("facts");
        Files.writeString(root.resolve("amazing-catalogue-v1.json"),mapper().writeValueAsString(amazing));return root;
    }

    @TestConfiguration(proxyBeanMethods=false)
    static class IntentFake {
        @Bean @Primary BuyerIntentCompiler compiler(){return new BuyerIntentCompiler(){
            @Override public CompiledIntent compile(ThreadMessage message,String feedback){return intent(message);}
            @Override public CompiledIntent compile(ThreadMessage message,ConversationContext context,String feedback){return context.visualObservation()==null?intent(message):visualIntent(message);}};}
        @Bean @Primary VisionObservationProvider vision(){return (image,text)->new VisionObservationProvider.Observed(
                new VisualCommerceModels.VisionObservation("Footwear","low-top lifestyle sneaker",null,null,List.of("white"),List.of("synthetic canvas"),List.of("low-top","lifestyle"),List.of(),new BigDecimal("0.92"),List.of("brand not proven")),
                "FAKE","gemini-3.5-flash-lite");}
        @Bean @Primary Task014PaymentProvider paymentProvider(){return new Task014PaymentProvider();}
        private static CompiledIntent intent(ThreadMessage message){String text=message.normalizedText().toLowerCase(Locale.ROOT);
            EvidenceSpan span=new EvidenceSpan(message.messageId(),0,message.normalizedText().length());String category;
            Long budget;String sku=null;String allergen=null;List<String> soft=List.of("GOOD");
            if(text.contains("amz-audio-032")){category=null;budget=null;sku="AMZ-AUDIO-032";soft=List.of();}
            else if(text.contains("oats")){category="Breakfast";budget=50_000L;allergen="PEANUT";}
            else if(text.contains("headphones")){category="Headphones";budget=300_000L;}
            else if(text.contains("apple")){category="Groceries";budget=30_000L;}
            else if(text.contains("shoes")){category="Mens Shoes";budget=10_000L;}
            else{category="Earphones";budget=300_000L;}
            List<MaterialField> fields=new ArrayList<>();if(category!=null)fields.add(field("CATEGORY",ConstraintClassification.HARD,span));
            if(budget!=null)fields.add(field("BUDGET",ConstraintClassification.HARD,span));if(sku!=null)fields.add(field("MERCHANT_SKU",ConstraintClassification.HARD,span));if(allergen!=null)fields.add(field("ALLERGEN",ConstraintClassification.HARD_SAFETY,span));
            if(!soft.isEmpty())fields.add(field("PREFERENCES",ConstraintClassification.SOFT,span));
            return new CompiledIntent(IntentGoal.PURCHASE_PRODUCT,category,budget,"INR",sku,null,null,null,null,null,
                    null,allergen,1,null,SubstitutionPolicy.PROHIBIT,null,soft,List.copyOf(fields),AmbiguityState.CLEAR,null,"FAKE","task013-intent-v1");}
        private static CompiledIntent visualIntent(ThreadMessage message){EvidenceSpan span=new EvidenceSpan(message.messageId(),0,message.normalizedText().length());
            return new CompiledIntent(IntentGoal.PURCHASE_PRODUCT,"Lifestyle Sneakers",400_000L,"INR",null,null,null,null,null,null,
                    null,null,1,null,SubstitutionPolicy.PROHIBIT,null,List.of(),List.of(),List.of(
                    field("CATEGORY",ConstraintClassification.SOFT,span),field("BUDGET",ConstraintClassification.HARD,span)),
                    AmbiguityState.CLEAR,null,"FAKE","task015-visual-intent-v1");}
        private static MaterialField field(String name,ConstraintClassification classification,EvidenceSpan span){
            return new MaterialField(name,classification,span,BigDecimal.ONE,AmbiguityState.CLEAR);}
    }

    static final class Task014PaymentProvider implements PaymentProvider {
        private final Map<String,ProviderOrder> orders=new HashMap<>();private int createCalls;
        private String paymentId="pay_pending";private boolean captured;
        void reset(){orders.clear();createCalls=0;paymentId="pay_pending";captured=false;}
        void capture(String id){paymentId=id;captured=true;}
        @Override public ProviderOrder createOrder(CreateOrderCommand command){createCalls++;
            ProviderOrder order=new ProviderOrder("order_"+command.receipt().substring(4,16),command.amountMinor(),0,
                    command.currency(),command.receipt(),"created",Instant.now(),providerAccountReference(),"1".repeat(64));
            orders.put(command.receipt(),order);return order;}
        @Override public ProviderOrder fetchOrder(String id){ProviderOrder order=orders.values().stream().filter(value->value.id().equals(id)).findFirst().orElseThrow();
            return new ProviderOrder(order.id(),order.amountMinor(),captured?order.amountMinor():0,order.currency(),order.receipt(),
                    captured?"paid":"created",Instant.now(),providerAccountReference(),"2".repeat(64));}
        @Override public ProviderPayment fetchPayment(String id){ProviderOrder order=orders.values().stream().findFirst().orElseThrow();
            return new ProviderPayment(id,order.id(),order.amountMinor(),order.currency(),captured?"captured":"authorized",captured,
                    Instant.now(),providerAccountReference(),"3".repeat(64));}
        @Override public Optional<ProviderOrder> findOrderByReceipt(String receipt){return Optional.ofNullable(orders.get(receipt));}
        @Override public boolean verifyCheckoutSignature(String order,String payment,String signature){return order.startsWith("order_")&&"a".repeat(64).equals(signature);}
        @Override public boolean verifyWebhookSignature(byte[] body,String signature){return false;}
        @Override public boolean configured(){return true;}@Override public String publicKeyId(){return "rzp_test_task014_public";}
        @Override public String configurationReference(){return "razorpay-test-default";}
        @Override public String providerAccountReference(){return "default-test-account";}
    }
}
