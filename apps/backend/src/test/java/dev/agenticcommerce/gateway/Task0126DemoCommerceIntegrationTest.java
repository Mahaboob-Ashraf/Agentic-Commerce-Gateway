package dev.agenticcommerce.gateway;

import static dev.agenticcommerce.gateway.catalogue.CatalogueModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.agenticcommerce.gateway.agentization.execution.*;
import dev.agenticcommerce.gateway.catalogue.*;
import dev.agenticcommerce.gateway.demo.*;
import dev.agenticcommerce.gateway.identity.persistence.ActorPasswordCredentialRepository;
import dev.agenticcommerce.gateway.identity.persistence.ApplicationActorRepository;
import dev.agenticcommerce.gateway.lifecycle.MerchantLifecycleGateway;
import dev.agenticcommerce.gateway.onboarding.MerchantCustomerLinkProvider;
import java.net.InetAddress;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(Task0126DemoCommerceIntegrationTest.Fakes.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Task0126DemoCommerceIntegrationTest {
    @Container @ServiceConnection static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("pgvector/pgvector:0.8.1-pg17");
    @Autowired JdbcClient jdbc;@Autowired DemoBootstrapService bootstrap;@Autowired DemoMerchantService runtime;
    @Autowired CatalogueRepository catalogues;@Autowired HybridCatalogueRetrievalService retrieval;
    @Autowired ObjectMapper mapper;@Autowired DemoMerchantApiAuthenticationFilter authenticationFilter;
    @Autowired Fakes.AuthenticatedFakeTransport transport;@Autowired MerchantLifecycleGateway lifecycle;
    @Autowired WebApplicationContext webApplicationContext;
    @Autowired ActorPasswordCredentialRepository passwordCredentials;@Autowired ApplicationActorRepository actors;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    @LocalServerPort int port;
    private DemoMerchantModels.BootstrapSummary summary;
    private DemoMerchantModels.BootstrapSummary failedSummary;private int completionRowsAfterFailure;
    private String merchantAdminPassword;

    @BeforeAll void seed(){jdbc.sql("TRUNCATE TABLE merchant,application_actor CASCADE").update();
        merchantAdminPassword=UUID.randomUUID().toString();
        transport.timeoutNext();failedSummary=bootstrap.bootstrap("https://merchant.example.test","evaluator@demo.invalid","not-a-tracked-demo-password",
                merchantAdminPassword,Path.of("..","..","evaluation","demo-data"));
        completionRowsAfterFailure=jdbc.sql("SELECT count(*)::int FROM demo_bootstrap_completion").query(Integer.class).single();
        summary=bootstrap.bootstrap("https://merchant.example.test","evaluator@demo.invalid","not-a-tracked-demo-password",
                merchantAdminPassword,Path.of("..","..","evaluation","demo-data"));}

    @Test void failedBootstrapIsNotCompletedAndRerunRecoversWithoutDuplicatingValidAuthority(){var reused=bootstrap.bootstrap("https://merchant.example.test",
            "evaluator@demo.invalid","not-a-tracked-demo-password",merchantAdminPassword,Path.of("..","..","evaluation","demo-data"));
        assertThat(failedSummary.reused()).isFalse();assertThat(failedSummary.blockers()).contains(
                "amazing:SEARCH_PRODUCTS:MERCHANT_TIMEOUT","EXPECTED_14_READY_CAPABILITIES_ACTUAL_13");assertThat(completionRowsAfterFailure).isZero();
        assertThat(summary.reused()).isFalse();assertThat(summary.blockers()).isEmpty();assertThat(summary.merchants()).isEqualTo(2);
        assertThat(summary.merchantPublicBaseUrl()).isEqualTo("https://merchant.example.test");
        assertThat(summary.deploymentPrecondition()).isEqualTo(DemoBootstrapService.DEPLOYMENT_PRECONDITION);
        assertThat(reused.reused()).isTrue();assertThat(reused.buyerCreated()).isFalse();assertThat(reused.merchantsReused()).isEqualTo(2);assertThat(reused.buyerActorId()).isEqualTo(summary.buyerActorId());
        assertThat(reused.merchantPublicBaseUrl()).isEqualTo(summary.merchantPublicBaseUrl());
        assertThat(jdbc.sql("SELECT count(*)::int FROM demo_merchant_profile").query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*)::int FROM catalogue_version WHERE status='PUBLISHED'").query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*)::int FROM merchant_approved_endpoint").query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*)::int FROM openapi_artifact").query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*)::int FROM capability_mapping_proposal").query(Integer.class).single()).isEqualTo(15);
        assertThat(jdbc.sql("SELECT count(*)::int FROM agent_commerce_manifest").query(Integer.class).single()).isEqualTo(14);}

    @Test void merchantAdminCredentialIsArgon2AndReusableBootstrapRepairsADeletedCredentialAndAuthenticates()throws Exception{
        var amazingAdmin=actors.findByIdentityHandle("demo-amazing-admin@agentic-commerce.invalid").orElseThrow();
        var initial=passwordCredentials.findByActorId(amazingAdmin.id()).orElseThrow();
        assertThat(initial.passwordHash()).startsWith("$argon2").isNotEqualTo(merchantAdminPassword);
        assertThat(passwordEncoder.matches(merchantAdminPassword,initial.passwordHash())).isTrue();
        var freshAdmin=actors.findByIdentityHandle("demo-freshbasket-admin@agentic-commerce.invalid").orElseThrow();
        var freshCredential=passwordCredentials.findByActorId(freshAdmin.id()).orElseThrow();
        assertThat(freshCredential.passwordHash()).startsWith("$argon2").isNotEqualTo(merchantAdminPassword);
        assertThat(passwordEncoder.matches(merchantAdminPassword,freshCredential.passwordHash())).isTrue();
        jdbc.sql("DELETE FROM actor_password_credential WHERE actor_id=:actor").param("actor",amazingAdmin.id()).update();

        var reused=bootstrap.bootstrap("https://merchant.example.test","evaluator@demo.invalid","not-a-tracked-demo-password",
                merchantAdminPassword,Path.of("..","..","evaluation","demo-data"));
        var repaired=passwordCredentials.findByActorId(amazingAdmin.id()).orElseThrow();
        assertThat(reused.reused()).isTrue();
        assertThat(repaired.passwordHash()).startsWith("$argon2").isNotEqualTo(merchantAdminPassword);
        assertThat(passwordEncoder.matches(merchantAdminPassword,repaired.passwordHash())).isTrue();

        CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
        HttpClient client=HttpClient.newBuilder().cookieHandler(cookies).build();
        HttpResponse<String> csrf=client.send(HttpRequest.newBuilder(uri("/api/auth/csrf")).GET().build(),HttpResponse.BodyHandlers.ofString());
        String token=mapper.readTree(csrf.body()).path("token").asText();
        var login=mapper.createObjectNode().put("identityHandle","demo-amazing-admin@agentic-commerce.invalid").put("password",merchantAdminPassword);
        HttpResponse<String> authenticated=client.send(HttpRequest.newBuilder(uri("/api/auth/login"))
                .header("Content-Type","application/json").header("X-CSRF-TOKEN",token)
                .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(login))).build(),HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> me=client.send(HttpRequest.newBuilder(uri("/api/auth/me")).GET().build(),HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> merchantList=client.send(HttpRequest.newBuilder(uri("/api/merchants")).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(authenticated.statusCode()).isEqualTo(200);
        assertThat(me.statusCode()).isEqualTo(200);assertThat(me.body()).contains("\"role\":\"MERCHANT_ADMIN\"");
        assertThat(merchantList.statusCode()).isEqualTo(200);assertThat(mapper.readTree(merchantList.body()).size()).isOne();
        assertThat(merchantList.body()).contains("\"merchantKey\":\"amazing\"").doesNotContain("freshbasket");}

    private URI uri(String path){return URI.create("http://localhost:"+port+path);}

    @Test void demoSearchContractIsDeterministicWhileNormalSearchAndFailuresRemainReal(){
        assertThat(jdbc.sql("SELECT request_timeout_ms FROM capability_mapping_proposal WHERE capability='SEARCH_PRODUCTS' ORDER BY created_at")
                .query(Integer.class).list()).hasSize(3).containsOnly(5_000);
        assertThat(jdbc.sql("SELECT request_timeout_ms FROM capability_mapping_proposal WHERE capability='GET_AVAILABILITY' ORDER BY created_at")
                .query(Integer.class).list()).hasSize(2).containsOnly(5_000);
        assertThat(transport.contractSearchObserved()).isTrue();
        int versions=jdbc.sql("SELECT count(*)::int FROM catalogue_version").query(Integer.class).single();
        var smoke=runtime.search("amazing",json("{\"query\":\"contract smoke test\",\"limit\":1,\"contractTest\":true}"));
        assertThat(smoke.path("classification").asText()).isEqualTo("NO_TRUSTWORTHY_MATCH");
        assertThat(smoke.path("catalogueVersion").asText()).isEqualTo("contract-test");
        assertThat(smoke.path("matches").isEmpty()).isTrue();
        assertThat(jdbc.sql("SELECT count(*)::int FROM catalogue_version").query(Integer.class).single()).isEqualTo(versions);
        var normal=runtime.search("amazing",json("{\"query\":\"apple\",\"limit\":5}"));
        assertThat(normal.path("classification").asText()).isNotEqualTo("NO_TRUSTWORTHY_MATCH");
        assertThat(normal.path("catalogueVersion").asText()).isNotEqualTo("contract-test");
        assertThat(jdbc.sql("""
                SELECT count(*)::int FROM capability_mapping_proposal mapping
                JOIN capability_contract_test_run test USING(merchant_id,mapping_proposal_id)
                JOIN capability_readiness_evaluation ready ON ready.merchant_id=mapping.merchant_id
                  AND ready.mapping_proposal_id=mapping.mapping_proposal_id
                WHERE mapping.capability='SEARCH_PRODUCTS' AND test.failure_code='MERCHANT_TIMEOUT'
                """).query(Integer.class).single()).isZero();}

    @Test void cataloguesHaveRequiredBreadthRichFactsAndBoundedOverlap(){assertThat(summary.amazingProducts()).isEqualTo(50);assertThat(summary.freshBasketProducts()).isEqualTo(30);
        assertThat(jdbc.sql("""
                SELECT count(DISTINCT p.category)::int FROM merchant_product p JOIN merchant m USING(merchant_id)
                WHERE m.merchant_key='amazing'
                """).query(Integer.class).single()).isGreaterThanOrEqualTo(8);
        assertThat(jdbc.sql("""
                SELECT count(*)::int FROM product_external_fact f JOIN merchant m USING(merchant_id)
                WHERE m.merchant_key='freshbasket' AND f.fact_type IN ('INGREDIENTS','ALLERGEN','VEGETARIAN','NUTRITION','PROTEIN')
                """).query(Integer.class).single()).isGreaterThanOrEqualTo(35);
        assertThat(jdbc.sql("""
                SELECT count(*)::int FROM (SELECT lower(p.canonical_name) n FROM merchant_product p JOIN merchant m USING(merchant_id)
                WHERE m.merchant_key IN ('amazing','freshbasket') GROUP BY lower(p.canonical_name) HAVING count(DISTINCT m.merchant_key)=2) overlap
                """)
                .query(Integer.class).single()).isEqualTo(10);
        var groceryQuery=json("{\"query\":\"apple\",\"limit\":5}");
        assertThat(runtime.search("amazing",groceryQuery).path("classification").asText()).isNotEqualTo("NO_TRUSTWORTHY_MATCH");
        assertThat(runtime.search("freshbasket",groceryQuery).path("classification").asText()).isNotEqualTo("NO_TRUSTWORTHY_MATCH");}

    @Test void provenanceAndAuthorityRemainExplicitAndUnknownSafetyFailsClosed(){assertThat(jdbc.sql("""
                SELECT count(*)::int FROM merchant_product_commerce_state c
                JOIN merchant m USING(merchant_id) WHERE m.merchant_key='freshbasket' AND c.observation_source IN
                ('DUMMYJSON_SNAPSHOT','OPEN_FOOD_FACTS_SNAPSHOT','MERCHANT_DEMO_SNAPSHOT')
                """).query(Integer.class).single()).isEqualTo(30);
        assertThat(jdbc.sql("""
                SELECT count(*)::int FROM merchant_product p JOIN merchant m USING(merchant_id)
                WHERE m.merchant_key='freshbasket' AND p.source_record_id LIKE 'openfoodfacts-%'
                """).query(Integer.class).single()).isEqualTo(12);
        assertThat(jdbc.sql("""
                SELECT count(DISTINCT f.product_id)::int FROM product_external_fact f JOIN merchant m USING(merchant_id)
                WHERE m.merchant_key='freshbasket' AND f.source='OPEN_FOOD_FACTS' AND f.authority_tier='SECONDARY'
                """).query(Integer.class).single()).isEqualTo(12);
        assertThat(jdbc.sql("""
                SELECT v.enriched_count FROM catalogue_version v JOIN merchant m USING(merchant_id)
                WHERE m.merchant_key='freshbasket' AND v.status='PUBLISHED'
                """).query(Integer.class).single()).isEqualTo(12);
        assertThat(jdbc.sql("""
                SELECT count(*)::int FROM product_external_fact f JOIN merchant m USING(merchant_id)
                WHERE m.merchant_key='freshbasket' AND f.source='OPEN_FOOD_FACTS' AND f.fact_type='ALLERGEN'
                  AND f.normalized_value->>'status'='ABSENT'
                """).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("""
                SELECT count(*)::int FROM merchant_product_commerce_state c
                JOIN merchant_product p USING(merchant_id,catalogue_version_id,product_id)
                JOIN merchant m USING(merchant_id)
                WHERE m.merchant_key='freshbasket' AND p.source_record_id LIKE 'openfoodfacts-%'
                  AND c.observation_source='MERCHANT_DEMO_SNAPSHOT'
                """).query(Integer.class).single()).isEqualTo(12);
        assertThat(jdbc.sql("""
                SELECT count(*)::int FROM product_external_fact f JOIN merchant m USING(merchant_id)
                WHERE m.merchant_key='freshbasket' AND f.authority_tier='PRIMARY' AND f.source='MERCHANT'
                """).query(Integer.class).single()).isGreaterThan(30);
        var merchant=jdbc.sql("SELECT merchant_id FROM merchant WHERE merchant_key='freshbasket'").query(UUID.class).single();
        var version=catalogues.latestPublished(merchant).orElseThrow();var lentil=catalogues.products(merchant,version.id(),100).stream().filter(p->p.merchantSku().equals("FB-LENTIL-017")).findFirst().orElseThrow();
        var chana=catalogues.products(merchant,version.id(),100).stream().filter(p->p.merchantSku().equals("FB-CHANA-016")).findFirst().orElseThrow();
        assertThat(retrieval.allergen(merchant,version.id(),lentil.id(),"peanut")).isEqualTo(AllergenState.UNKNOWN);
        assertThat(retrieval.allergen(merchant,version.id(),chana.id(),"peanut")).isEqualTo(AllergenState.ABSENT);}

    @Test void allRequiredCapabilitiesAreValidatedApprovedTestedAndCumulativelyReady(){assertThat(summary.capabilitiesMapped()).isEqualTo(14);assertThat(summary.capabilitiesReady()).isEqualTo(14);assertThat(summary.blockers()).isEmpty();
        assertThat(jdbc.sql("""
                SELECT count(DISTINCT (mapping.merchant_id,mapping.capability))::int FROM capability_mapping_proposal mapping
                JOIN capability_contract_test_run test USING(mapping_proposal_id,merchant_id)
                WHERE mapping.validation_status='VALID' AND test.outcome='PASS'
                """).query(Integer.class).single()).isEqualTo(14);
        assertThat(jdbc.sql("SELECT count(*)::int FROM capability_contract_test_run WHERE outcome='PASS'").query(Integer.class).single()).isEqualTo(14);
        var latestStates=jdbc.sql("""
                SELECT c.merchant_id||':'||c.capability||':'||c.readiness FROM agent_commerce_manifest_capability c
                JOIN agent_commerce_manifest m USING(manifest_id)
                WHERE m.manifest_version=7 AND c.capability<>'PURCHASE' ORDER BY c.merchant_id,c.capability
                """).query(String.class).list();
        assertThat(latestStates).hasSize(16);
        assertThat(latestStates).filteredOn(state->state.endsWith(":READY")).hasSize(14);
        assertThat(latestStates).filteredOn(state->state.endsWith(":REFUND:UNTESTED")).hasSize(2);}

    @Test void runtimeSearchAvailabilityQuoteAndIdempotentOrderUsePersistentInventory(){var headphones=runtime.search("amazing",json("{\"query\":\"black ANC Bluetooth headphones\",\"maximumPriceMinor\":600000,\"limit\":5}"));
        assertThat(headphones.path("classification").asText()).isNotEqualTo("NO_TRUSTWORTHY_MATCH");
        UUID product=jdbc.sql("""
                SELECT p.product_id FROM merchant_product p JOIN merchant m USING(merchant_id)
                WHERE m.merchant_key='amazing' AND p.merchant_sku='AMZ-AUDIO-031'
                """).query(UUID.class).single();
        var availability=runtime.availability("amazing",json("{\"productId\":\""+product+"\",\"merchantSku\":\"AMZ-AUDIO-031\",\"requestedQuantity\":1}"));assertThat(availability.path("available").asBoolean()).isTrue();
        var quote=runtime.quote("amazing",json("{\"cartId\":\"demo-cart\",\"cartHash\":\"demo-cart-hash\",\"lineItems\":[{\"productId\":\""+product+"\",\"merchantSku\":\"AMZ-AUDIO-031\",\"quantity\":1}]}"));
        assertThat(quote.path("finalAmountMinor").asLong()).isEqualTo(499900);
        String body="{\"merchantOperationId\":\"stable-operation-1\",\"merchantCustomerReference\":\"demo-customer\",\"amountMinor\":499900,\"currency\":\"INR\",\"lineItems\":[{\"productId\":\""+product+"\",\"merchantSku\":\"AMZ-AUDIO-031\",\"quantity\":1,\"unitAmountMinor\":499900}]}";
        var first=runtime.placeOrder("amazing",json(body));var second=runtime.placeOrder("amazing",json(body));assertThat(second.path("orderId").asText()).isEqualTo(first.path("orderId").asText());
        assertThat(jdbc.sql("SELECT count(*)::int FROM demo_merchant_order WHERE merchant_operation_id='stable-operation-1'").query(Integer.class).single()).isOne();
        assertThatThrownBy(()->runtime.placeOrder("amazing",json(body.replace("\"quantity\":1","\"quantity\":2")))).isInstanceOf(RuntimeException.class);}

    @Test void merchantPolicyDifferencesLinksAndBootstrapMoneyBoundaryAreEnforced(){assertThat(jdbc.sql("SELECT count(*)::int FROM merchant_account_link WHERE buyer_actor_id=:b AND status='LINKED'")
                .param("b",summary.buyerActorId()).query(Integer.class).single()).isEqualTo(2);
        UUID amazingProduct=product("amazing","AMZ-GROC-016"),freshProduct=product("freshbasket","FB-APPLE-001");
        String amazingOrder=runtime.placeOrder("amazing",orderBody("policy-amazing",amazingProduct,"AMZ-GROC-016",19900,27800)).path("orderId").asText();
        assertThat(runtime.cancel("amazing",amazingOrder).path("state").asText()).isEqualTo("CANCELLED");
        String freshOrder=runtime.placeOrder("freshbasket",orderBody("policy-fresh",freshProduct,"FB-APPLE-001",17900,22800)).path("orderId").asText();
        assertThatThrownBy(()->runtime.cancel("freshbasket",freshOrder)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->runtime.requestReturn("freshbasket",freshOrder)).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.sql("SELECT count(*)::int FROM transaction_proposal").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*)::int FROM payment_control").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*)::int FROM payment_provider_order").query(Integer.class).single()).isZero();}

    @Test void groceryDeliveryAmountIsReconciledAndMismatchCannotMutate(){UUID product=product("freshbasket","FB-APPLE-001");
        var quote=runtime.quote("freshbasket",json("{\"cartId\":\"delivery-cart\",\"cartHash\":\"delivery-hash\",\"lineItems\":[{\"productId\":\""+product+"\",\"merchantSku\":\"FB-APPLE-001\",\"quantity\":1}]}"));
        assertThat(quote.path("deliveryMinor").asLong()).isEqualTo(4900);assertThat(quote.path("finalAmountMinor").asLong()).isEqualTo(22800);
        var accepted=runtime.placeOrder("freshbasket",orderBody("delivery-total-match",product,"FB-APPLE-001",17900,quote.path("finalAmountMinor").asLong()));
        assertThat(accepted.path("totalMinor").asLong()).isEqualTo(quote.path("finalAmountMinor").asLong());
        assertThat(jdbc.sql("SELECT total_minor FROM demo_merchant_order WHERE merchant_operation_id='delivery-total-match'").query(Long.class).single()).isEqualTo(22800L);
        long stock=jdbc.sql("SELECT available_quantity FROM demo_merchant_inventory WHERE product_id=:p").param("p",product).query(Long.class).single();
        assertThatThrownBy(()->runtime.placeOrder("freshbasket",orderBody("delivery-total-mismatch",product,"FB-APPLE-001",17900,22799)))
                .isInstanceOf(RuntimeException.class);
        var wrongCurrency=(tools.jackson.databind.node.ObjectNode)orderBody("delivery-currency-mismatch",product,"FB-APPLE-001",17900,22800);
        wrongCurrency.put("currency","USD");assertThatThrownBy(()->runtime.placeOrder("freshbasket",wrongCurrency)).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.sql("SELECT available_quantity FROM demo_merchant_inventory WHERE product_id=:p").param("p",product).query(Long.class).single()).isEqualTo(stock);
        assertThat(jdbc.sql("SELECT count(*)::int FROM demo_merchant_order WHERE merchant_operation_id IN ('delivery-total-mismatch','delivery-currency-mismatch')").query(Integer.class).single()).isZero();}

    @Test void unauthenticatedPublicMutationIsStoppedAndApprovedExecutorSuppliesCredential()throws Exception{UUID product=product("amazing","AMZ-GROC-016");
        long stock=jdbc.sql("SELECT available_quantity FROM demo_merchant_inventory WHERE product_id=:p").param("p",product).query(Long.class).single();
        var client=MockMvcBuilders.webAppContextSetup(webApplicationContext).addFilters(authenticationFilter).build();
        client.perform(post("/api/demo-merchants/amazing/orders").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(orderBody("unauthenticated-operation",product,"AMZ-GROC-016",19900,27800))))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.sql("SELECT available_quantity FROM demo_merchant_inventory WHERE product_id=:p").param("p",product).query(Long.class).single()).isEqualTo(stock);
        assertThat(jdbc.sql("SELECT count(*)::int FROM demo_merchant_order WHERE merchant_operation_id='unauthenticated-operation'").query(Integer.class).single()).isZero();
        assertThat(transport.authenticatedCalls()).isGreaterThanOrEqualTo(15);}

    @Test void terminalLifecycleResponseIsNotMisclassifiedAsRetryable(){UUID amazing=jdbc.sql("SELECT merchant_id FROM merchant WHERE merchant_key='amazing'").query(UUID.class).single();
        var invalid=lifecycle.cancel(amazing,"not-contract-order","lifecycle-terminal","demo-customer");
        assertThat(invalid.success()).isFalse();assertThat(invalid.retryable()).isFalse();assertThat(invalid.errorCode()).isEqualTo("MERCHANT_LIFECYCLE_RESPONSE_INVALID");
        transport.failNext();var transientFailure=lifecycle.cancel(amazing,"contract-order","lifecycle-transient","demo-customer");
        assertThat(transientFailure.success()).isFalse();assertThat(transientFailure.retryable()).isTrue();assertThat(transientFailure.errorCode()).isEqualTo("MERCHANT_LIFECYCLE_REJECTED");}

    private UUID product(String key,String sku){return jdbc.sql("SELECT p.product_id FROM merchant_product p JOIN merchant m USING(merchant_id) WHERE m.merchant_key=:k AND p.merchant_sku=:s").param("k",key).param("s",sku).query(UUID.class).single();}
    private tools.jackson.databind.JsonNode orderBody(String operation,UUID product,String sku,long price,long amount){return json("{\"merchantOperationId\":\""+operation+"\",\"amountMinor\":"+amount+",\"currency\":\"INR\",\"lineItems\":[{\"productId\":\""+product+"\",\"merchantSku\":\""+sku+"\",\"quantity\":1,\"unitAmountMinor\":"+price+"}]}");}
    private tools.jackson.databind.JsonNode json(String value){return mapper.readTree(value);}

    @TestConfiguration(proxyBeanMethods=false)
    static class Fakes {
        @Bean @Primary MerchantDnsResolver dns()throws Exception{return h->List.of(InetAddress.getByName("93.184.216.34"));}
        @Bean @Primary MerchantCredentialProvider credentials(){return reference->{assertThat(reference).isEqualTo(EnvironmentMerchantCredentialProvider.DEMO_CREDENTIAL_REFERENCE);
            return new MerchantCredentialProvider.MerchantCredential("Authorization","Bearer task-0126-test-secret");};}
        @Bean @Primary AuthenticatedFakeTransport transport(ObjectProvider<DemoMerchantService> runtime,ObjectMapper mapper){return new AuthenticatedFakeTransport(runtime,mapper);}
        static final class AuthenticatedFakeTransport implements MerchantTransport{private final ObjectProvider<DemoMerchantService> runtime;private final ObjectMapper mapper;private boolean failNext;private boolean timeoutNext;private boolean expireQuoteNext;private boolean unavailableNext;private boolean contractSearchObserved;private int authenticatedCalls;private long availabilityClockSkewSeconds;
            AuthenticatedFakeTransport(ObjectProvider<DemoMerchantService> runtime,ObjectMapper mapper){this.runtime=runtime;this.mapper=mapper;}
            void failNext(){failNext=true;}void timeoutNext(){timeoutNext=true;}void expireQuoteNext(){expireQuoteNext=true;}void unavailableNext(){unavailableNext=true;}
            void resetRuntimeModes(){expireQuoteNext=false;unavailableNext=false;availabilityClockSkewSeconds=0;}void skewAvailabilityClock(long seconds){availabilityClockSkewSeconds=seconds;}boolean contractSearchObserved(){return contractSearchObserved;}int authenticatedCalls(){return authenticatedCalls;}
            @Override public MerchantTransportResponse execute(ValidatedEndpointResolution resolution,MerchantTransportRequest request){
            if(!"Bearer task-0126-test-secret".equals(request.headers().get("Authorization")))return new MerchantTransportResponse(401,"application/json","{}".getBytes(StandardCharsets.UTF_8));
            if(request.uri().getPath().endsWith("/products/search"))contractSearchObserved=new String(request.jsonBody(),StandardCharsets.UTF_8).contains("\"contractTest\":true");
            authenticatedCalls++;if(timeoutNext){timeoutNext=false;throw new MerchantExecutionException("MERCHANT_TIMEOUT","Injected demo search timeout");}
            if(failNext){failNext=false;return new MerchantTransportResponse(503,"application/json","{}".getBytes(StandardCharsets.UTF_8));}
            String path=request.uri().getPath();JsonNode input=mapper.readTree(request.jsonBody());String[] parts=path.split("/");String merchantKey=parts.length>3?parts[3]:"";
            if(path.endsWith("/availability")&&!"demo-product".equals(input.path("productId").asText())){JsonNode value=runtime.getObject().availability(merchantKey,input);
                if(unavailableNext){unavailableNext=false;((tools.jackson.databind.node.ObjectNode)value).put("available",false).put("availableQuantity",0);}
                if(availabilityClockSkewSeconds!=0){var object=(tools.jackson.databind.node.ObjectNode)value;object.put("observedAt",Instant.parse(object.path("observedAt").asText()).plusSeconds(availabilityClockSkewSeconds).toString());object.put("expiresAt",Instant.parse(object.path("expiresAt").asText()).plusSeconds(availabilityClockSkewSeconds).toString());return json(value,Instant.now().plusSeconds(availabilityClockSkewSeconds));}return json(value);}
            if(path.endsWith("/quotes")&&input.path("lineItems").isArray()){JsonNode value=runtime.getObject().quote(merchantKey,input);
                if(expireQuoteNext){expireQuoteNext=false;((tools.jackson.databind.node.ObjectNode)value).put("expiresAt",Instant.now().minusSeconds(1).toString());}return json(value);}
            if(path.endsWith("/orders")&&!input.path("contractTest").asBoolean(false))return json(runtime.getObject().placeOrder(merchantKey,input));
            String body;
            if(path.endsWith("/products/search"))body="{\"classification\":\"NO_TRUSTWORTHY_MATCH\",\"matches\":[],\"relatedAlternatives\":[]}";
            else if(path.endsWith("/availability"))body="{\"available\":true,\"availableQuantity\":10}";
            else if(path.endsWith("/quotes"))body="{\"quoteId\":\"contract-quote-v1\",\"finalAmountMinor\":49900,\"currency\":\"INR\"}";
            else if(path.endsWith("/returns"))body="{\"orderId\":\"contract-order\",\"state\":\"RETURN_REQUESTED\"}";
            else if(path.endsWith("/cancel"))body="{\"orderId\":\"contract-order\",\"state\":\"CANCELLED\"}";
            else body="{\"orderId\":\"contract-order\",\"state\":\"PLACED\"}";
            return new MerchantTransportResponse(200,"application/json",body.getBytes(StandardCharsets.UTF_8));}
            private MerchantTransportResponse json(JsonNode value){return new MerchantTransportResponse(200,"application/json",mapper.writeValueAsBytes(value));}
            private MerchantTransportResponse json(JsonNode value,Instant responseDate){return new MerchantTransportResponse(200,"application/json",mapper.writeValueAsBytes(value),Instant.now(),responseDate);}}
        @Bean @Primary EmbeddingProvider embeddings(){return input->Collections.nCopies(768,0.01f);}
        @Bean @Primary CatalogueProvider catalogueProvider(){return barcode->Optional.empty();}
        @Bean @Primary MerchantCustomerLinkProvider links(){return (merchant,user,password)->new MerchantCustomerLinkProvider.LinkResult(true,
                "demo-customer-"+merchant,"demo-credential-"+merchant,"TRUSTED_DEMO",Instant.now().plusSeconds(86400),null);}
    }
}
