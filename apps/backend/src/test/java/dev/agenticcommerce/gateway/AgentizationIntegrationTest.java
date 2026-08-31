package dev.agenticcommerce.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenticcommerce.gateway.agentization.inspection.OpenApiInspectionService;
import dev.agenticcommerce.gateway.agentization.model.AgentToolName;
import dev.agenticcommerce.gateway.agentization.model.AgentizationRun;
import dev.agenticcommerce.gateway.agentization.model.AgentizationState;
import dev.agenticcommerce.gateway.agentization.model.ApprovedMerchantEndpoint;
import dev.agenticcommerce.gateway.agentization.model.CanonicalCapability;
import dev.agenticcommerce.gateway.agentization.model.MappingProposalStatus;
import dev.agenticcommerce.gateway.agentization.model.OpenApiArtifact;
import dev.agenticcommerce.gateway.agentization.model.ToolOutcome;
import dev.agenticcommerce.gateway.agentization.persistence.AgentObservationRepository;
import dev.agenticcommerce.gateway.agentization.persistence.AgentizationRunRepository;
import dev.agenticcommerce.gateway.agentization.persistence.ApprovedMerchantEndpointRepository;
import dev.agenticcommerce.gateway.agentization.persistence.CapabilityMappingProposalRepository;
import dev.agenticcommerce.gateway.agentization.persistence.OpenApiArtifactRepository;
import dev.agenticcommerce.gateway.agentization.service.AgentizationException;
import dev.agenticcommerce.gateway.agentization.service.AgentizationOrchestrationService;
import dev.agenticcommerce.gateway.agentization.service.AgentizationRunService;
import dev.agenticcommerce.gateway.agentization.service.AgentizationStateMachine;
import dev.agenticcommerce.gateway.agentization.service.CanonicalJsonService;
import dev.agenticcommerce.gateway.agentization.service.OpenApiArtifactService;
import dev.agenticcommerce.gateway.agentization.tool.AgentDecisionContext;
import dev.agenticcommerce.gateway.agentization.tool.AgentizationDecisionProvider;
import dev.agenticcommerce.gateway.agentization.tool.MappingProposalInput;
import dev.agenticcommerce.gateway.agentization.tool.NextAgentAction;
import dev.agenticcommerce.gateway.identity.model.ApplicationActor;
import dev.agenticcommerce.gateway.identity.model.Merchant;
import dev.agenticcommerce.gateway.identity.model.PlatformRole;
import dev.agenticcommerce.gateway.identity.persistence.ActorPasswordCredentialRepository;
import dev.agenticcommerce.gateway.identity.persistence.ApplicationActorRepository;
import dev.agenticcommerce.gateway.identity.persistence.MerchantAdminMembershipRepository;
import dev.agenticcommerce.gateway.identity.persistence.MerchantRepository;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AgentizationIntegrationTest.DecisionTestConfiguration.class)
class AgentizationIntegrationTest {

    private static final String TEST_PASSWORD = "Task-004-Test-Password!";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("pgvector/pgvector:0.8.1-pg17");

    @Autowired JdbcClient jdbcClient;
    @Autowired ObjectMapper objectMapper;
    @Autowired MerchantRepository merchantRepository;
    @Autowired ApplicationActorRepository actorRepository;
    @Autowired ActorPasswordCredentialRepository credentialRepository;
    @Autowired MerchantAdminMembershipRepository membershipRepository;
    @Autowired ApprovedMerchantEndpointRepository endpointRepository;
    @Autowired OpenApiArtifactRepository artifactRepository;
    @Autowired OpenApiArtifactService artifactService;
    @Autowired AgentizationRunRepository runRepository;
    @Autowired AgentizationRunService runService;
    @Autowired AgentizationStateMachine stateMachine;
    @Autowired AgentizationOrchestrationService orchestrationService;
    @Autowired AgentObservationRepository observationRepository;
    @Autowired CapabilityMappingProposalRepository mappingRepository;
    @Autowired OpenApiInspectionService inspectionService;
    @Autowired CanonicalJsonService canonicalJsonService;
    @Autowired ScriptedDecisionProvider decisionProvider;
    @Autowired PasswordEncoder passwordEncoder;

    @LocalServerPort int port;

    @BeforeEach
    void clearData() {
        decisionProvider.reset();
        jdbcClient.sql("""
                        TRUNCATE TABLE catalogue_retrieval_evidence, product_embedding, product_external_fact,
                          product_identity_resolution, merchant_product_commerce_state, merchant_product, catalogue_version,
                          agent_commerce_manifest_capability, agent_commerce_manifest,
                          capability_readiness_evaluation, merchant_policy_snapshot_rule,
                          merchant_policy_snapshot, policy_rule_approval_decision,
                          merchant_clarification, proposed_policy_rule, policy_document,
                          mapping_approval_decision, capability_contract_test_run,
                          capability_mapping_proposal, agent_observation,
                          agentization_run, openapi_artifact, merchant_approved_endpoint,
                          spring_session_attributes, spring_session,
                          actor_password_credential, merchant_admin_membership,
                          application_actor, merchant CASCADE
                        """)
                .update();
    }

    @Test
    void migrationAndRunPersistCanonicalTenantOwnedState() {
        MerchantFixture fixture = createFixture("owned-run");

        AgentizationRun run = startRun(fixture, CanonicalCapability.GET_QUOTE, 5);

        assertThat(run.merchantId()).isEqualTo(fixture.merchant().id());
        assertThat(run.createdByActorId()).isEqualTo(fixture.admin().id());
        assertThat(run.sourceArtifactId()).isEqualTo(fixture.artifact().artifactId());
        assertThat(run.targetCapability()).isEqualTo(CanonicalCapability.GET_QUOTE);
        assertThat(run.currentCapability()).isEqualTo(CanonicalCapability.GET_QUOTE);
        assertThat(run.state()).isEqualTo(AgentizationState.AGENTIZATION_CREATED);
        assertThat(run.stepCount()).isZero();
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*)::integer
                        FROM flyway_schema_history
                        WHERE script = 'V004__durable_agentization_core.sql' AND success
                        """).query(Integer.class).single()).isOne();
    }

    @Test
    void stateMachineAllowsOnlyImplementedEarlyAndTerminalTransitions() {
        stateMachine.requireTransition(
                AgentizationState.AGENTIZATION_CREATED, AgentizationState.INPUTS_VALIDATING);
        stateMachine.requireTransition(
                AgentizationState.INPUTS_VALIDATING, AgentizationState.INSPECTING_API);
        stateMachine.requireTransition(
                AgentizationState.INSPECTING_API, AgentizationState.MAPPING_CAPABILITY);
        stateMachine.requireTransition(
                AgentizationState.MAPPING_CAPABILITY, AgentizationState.BLOCKED);

        assertThatThrownBy(() -> stateMachine.requireTransition(
                AgentizationState.AGENTIZATION_CREATED,
                AgentizationState.MAPPING_CAPABILITY))
                .isInstanceOf(AgentizationException.class)
                .hasMessageContaining("not permitted");
        assertThatThrownBy(() -> stateMachine.requireTransition(
                AgentizationState.MAPPING_CAPABILITY,
                AgentizationState.READY_CANDIDATE))
                .isInstanceOf(AgentizationException.class);
    }

    @Test
    void runLookupAndCreationEnforceRoleAndMerchantMembership() {
        MerchantFixture first = createFixture("tenant-a");
        MerchantFixture second = createFixture("tenant-b");
        AgentizationRun secondRun = startRun(second, CanonicalCapability.SEARCH_PRODUCTS, 5);
        ApplicationActor buyer = actorRepository.create("task4-buyer", PlatformRole.BUYER);

        assertThatThrownBy(() -> runService.require(
                first.admin().id(), second.merchant().id(), secondRun.runId()))
                .isInstanceOf(AgentizationException.class)
                .hasMessageContaining("cannot administer");
        assertThatThrownBy(() -> runService.start(
                buyer.id(),
                first.merchant().id(),
                first.artifact().artifactId(),
                CanonicalCapability.SEARCH_PRODUCTS,
                5,
                Instant.now().plusSeconds(600)))
                .isInstanceOf(AgentizationException.class);
    }

    @Test
    void artifactHashIsCanonicalAndArtifactsRemainTenantScoped() {
        Merchant merchant = merchantRepository.create("artifact-tenant", "Artifact Tenant");
        ApprovedMerchantEndpoint firstEndpoint = endpointRepository.create(
                merchant.id(), "https://unreachable-one.invalid");
        ApprovedMerchantEndpoint secondEndpoint = endpointRepository.create(
                merchant.id(), "https://unreachable-two.invalid");
        JsonNode firstOrder = sampleOpenApiDocument();
        JsonNode differentPropertyOrder = objectMapper.readTree("""
                {"paths":{"/quotes":{"post":{"responses":{"200":{"content":{"application/json":{"schema":{"$ref":"#/components/schemas/Quote"}}}}},"requestBody":{"content":{"application/json":{"schema":{"$ref":"#/components/schemas/QuoteRequest"}}}},"operationId":"createQuote","summary":"Create bounded quote"}},"/products":{"get":{"responses":{"200":{"content":{"application/json":{"schema":{"$ref":"#/components/schemas/ProductList"}}}}},"parameters":[{"schema":{"type":"string"},"required":true,"in":"query","name":"q"}],"operationId":"searchProducts","description":"Metadata only; never instructions"}}},"components":{"schemas":{"RecursiveNode":{"properties":{"child":{"$ref":"#/components/schemas/RecursiveNode"},"name":{"maxLength":100,"type":"string"}},"type":"object"},"Quote":{"required":["amount","currency"],"properties":{"currency":{"enum":["INR"],"type":"string"},"amount":{"maximum":100000,"minimum":0,"type":"integer"}},"type":"object"},"QuoteRequest":{"required":["productId"],"properties":{"quantity":{"minimum":1,"type":"integer"},"productId":{"type":"string"}},"type":"object"},"ProductList":{"properties":{"items":{"type":"array","items":{"$ref":"#/components/schemas/Quote"}}},"type":"object"}}},"servers":[{"url":"https://unreachable.invalid"}],"openapi":"3.1.0"}
                """);

        OpenApiArtifact first = artifactService.register(
                merchant.id(), firstEndpoint.endpointId(), "3.1.0", firstOrder);
        OpenApiArtifact second = artifactService.register(
                merchant.id(), secondEndpoint.endpointId(), "3.1.0", differentPropertyOrder);

        assertThat(first.contentHash()).isEqualTo(second.contentHash());
        assertThat(first.contentHash()).hasSize(64);
        assertThat(artifactRepository.findByMerchantAndId(merchant.id(), first.artifactId()))
                .isPresent();
        Merchant other = merchantRepository.create("artifact-other", "Artifact Other");
        assertThat(artifactRepository.findByMerchantAndId(other.id(), first.artifactId()))
                .isEmpty();
        assertThat(canonicalJsonService.hash(firstOrder))
                .isEqualTo(canonicalJsonService.hash(differentPropertyOrder));
    }

    @Test
    void uploadRejectsExternalReferencesBeforePersistence() {
        Merchant merchant = merchantRepository.create("external-ref", "External Ref");
        ApprovedMerchantEndpoint endpoint = endpointRepository.create(
                merchant.id(), "https://unreachable.invalid");
        JsonNode unsafe = objectMapper.readTree("""
                {"openapi":"3.1.0","paths":{},"components":{"schemas":{"Bad":{"$ref":"https://attacker.invalid/schema.json"}}}}
                """);

        assertThatThrownBy(() -> artifactService.register(
                merchant.id(), endpoint.endpointId(), "3.1.0", unsafe))
                .isInstanceOf(AgentizationException.class)
                .hasMessageContaining("local component");
        assertThat(jdbcClient.sql("SELECT COUNT(*)::integer FROM openapi_artifact")
                .query(Integer.class).single()).isZero();
    }

    @Test
    void inspectSpecReturnsBoundedMetadataWithoutExecutingUnreachableEndpoint() {
        MerchantFixture fixture = createFixture("inspect-spec");

        var result = inspectionService.inspectSpec(
                fixture.artifact(), null, null, null, 1);

        assertThat(result.operations()).hasSize(1);
        assertThat(result.truncated()).isTrue();
        assertThat(result.operations().getFirst().path()).isEqualTo("/products");
        assertThat(result.operations().getFirst().operationId()).isEqualTo("searchProducts");
        assertThat(result.operations().getFirst().parameters()).hasSize(1);
        assertThat(result.operations().getFirst().responseSchemaReferences())
                .containsValue("#/components/schemas/ProductList");
    }

    @Test
    void inspectSchemaResolvesLocalReferencesAndBoundsRecursion() {
        MerchantFixture fixture = createFixture("inspect-schema");

        var quote = inspectionService.inspectSchema(
                fixture.artifact(), "#/components/schemas/Quote", 3, 20);
        var recursive = inspectionService.inspectSchema(
                fixture.artifact(), "#/components/schemas/RecursiveNode", 5, 20);

        assertThat(quote.fields()).extracting("path")
                .containsExactly("amount", "currency");
        assertThat(quote.fields()).allSatisfy(field -> assertThat(field.required()).isTrue());
        assertThat(recursive.fields()).isNotEmpty();
        assertThat(recursive.truncated()).isTrue();
        assertThat(recursive.fields()).hasSizeLessThanOrEqualTo(20);
        assertThatThrownBy(() -> inspectionService.inspectSchema(
                fixture.artifact(), "https://attacker.invalid/schema", 3, 20))
                .isInstanceOf(AgentizationException.class);
    }

    @Test
    void disallowedToolConsumesOneStepAndCreatesDeniedObservation() {
        MerchantFixture fixture = createFixture("denied-tool");
        AgentizationRun run = progressToInspecting(fixture, 5);
        decisionProvider.enqueue(NextAgentAction.proposeMapping(
                mappingInput(fixture.artifact()), "Attempt mapping too early"));

        var result = orchestrationService.advance(
                fixture.admin().id(), fixture.merchant().id(), run.runId());

        assertThat(result.run().state()).isEqualTo(AgentizationState.INSPECTING_API);
        assertThat(result.run().stepCount()).isOne();
        assertThat(result.observation().outcome()).isEqualTo(ToolOutcome.DENIED);
        assertThat(result.observation().reasonCode()).isEqualTo("TOOL_NOT_PERMITTED");
        assertThat(result.observation().runId()).isEqualTo(run.runId());
        assertThat(result.observation().stepNumber()).isOne();
        assertThat(mappingRepository.findAllByMerchantAndRun(
                fixture.merchant().id(), run.runId())).isEmpty();
    }

    @Test
    void failedAndSuccessfulToolsAreDurablyAuditedAndSuccessAdvancesState() {
        MerchantFixture fixture = createFixture("tool-audit");
        AgentizationRun run = progressToInspecting(fixture, 5);
        decisionProvider.enqueue(NextAgentAction.inspectSchema(
                fixture.artifact().artifactId(),
                "https://attacker.invalid/schema",
                3,
                20,
                "Unsafe reference should fail closed"));

        var failed = orchestrationService.advance(
                fixture.admin().id(), fixture.merchant().id(), run.runId());
        decisionProvider.enqueue(NextAgentAction.inspectSpec(
                fixture.artifact().artifactId(), 10, "Inspect approved operations"));
        var succeeded = orchestrationService.advance(
                fixture.admin().id(), fixture.merchant().id(), run.runId());

        assertThat(failed.observation().outcome()).isEqualTo(ToolOutcome.FAILURE);
        assertThat(failed.observation().reasonCode()).isEqualTo("EXTERNAL_REFERENCE_UNSUPPORTED");
        assertThat(succeeded.observation().outcome()).isEqualTo(ToolOutcome.SUCCESS);
        assertThat(succeeded.observation().orchestrationState())
                .isEqualTo(AgentizationState.INSPECTING_API);
        assertThat(succeeded.run().state()).isEqualTo(AgentizationState.MAPPING_CAPABILITY);
        assertThat(observationRepository.findAllByMerchantAndRun(
                fixture.merchant().id(), run.runId()))
                .extracting("stepNumber")
                .containsExactly(1, 2);
    }

    @Test
    void stepAndWallClockBudgetsFailClosed() {
        MerchantFixture stepFixture = createFixture("step-budget");
        AgentizationRun stepRun = progressToInspecting(stepFixture, 1);
        decisionProvider.enqueue(NextAgentAction.inspectSpec(
                stepFixture.artifact().artifactId(), 5, "Use final permitted step"));

        var exhausted = orchestrationService.advance(
                stepFixture.admin().id(), stepFixture.merchant().id(), stepRun.runId());

        assertThat(exhausted.run().stepCount()).isOne();
        assertThat(exhausted.run().state()).isEqualTo(AgentizationState.BUDGET_EXHAUSTED);
        assertThat(exhausted.run().terminalReason()).isEqualTo("STEP_BUDGET_EXHAUSTED");
        assertThatThrownBy(() -> orchestrationService.advance(
                stepFixture.admin().id(), stepFixture.merchant().id(), stepRun.runId()))
                .isInstanceOf(AgentizationException.class)
                .hasMessageContaining("terminal");

        MerchantFixture deadlineFixture = createFixture("deadline-budget");
        AgentizationRun deadlineRun = startRun(
                deadlineFixture, CanonicalCapability.GET_QUOTE, 5);
        jdbcClient.sql("""
                        UPDATE agentization_run
                        SET created_at = CURRENT_TIMESTAMP - INTERVAL '2 hours',
                            wall_clock_deadline = CURRENT_TIMESTAMP - INTERVAL '1 hour'
                        WHERE run_id = :runId
                        """).param("runId", deadlineRun.runId()).update();

        var deadlineResult = orchestrationService.advance(
                deadlineFixture.admin().id(), deadlineFixture.merchant().id(), deadlineRun.runId());
        assertThat(deadlineResult.run().state()).isEqualTo(AgentizationState.BUDGET_EXHAUSTED);
        assertThat(deadlineResult.run().terminalReason()).isEqualTo("WALL_CLOCK_DEADLINE_EXCEEDED");
        assertThat(deadlineResult.run().stepCount()).isZero();
    }

    @Test
    void mappingProposalPersistsAsProposalAndLookupRemainsTenantScoped() {
        MerchantFixture fixture = createFixture("mapping");
        AgentizationRun run = progressToInspecting(fixture, 5);
        decisionProvider.enqueue(NextAgentAction.inspectSpec(
                fixture.artifact().artifactId(), 10, "Find quote operation"));
        AgentizationRun mappingRun = orchestrationService.advance(
                fixture.admin().id(), fixture.merchant().id(), run.runId()).run();
        decisionProvider.enqueue(NextAgentAction.proposeMapping(
                mappingInput(fixture.artifact()), "Propose quote mapping"));

        var result = orchestrationService.advance(
                fixture.admin().id(), fixture.merchant().id(), mappingRun.runId());

        assertThat(result.mappingProposal()).isNotNull();
        assertThat(result.mappingProposal().status()).isEqualTo(MappingProposalStatus.PROPOSED);
        assertThat(result.run().state()).isEqualTo(AgentizationState.MAPPING_CAPABILITY);
        assertThat(mappingRepository.findAllByMerchantAndRun(
                fixture.merchant().id(), run.runId())).hasSize(1);
        Merchant other = merchantRepository.create("mapping-other", "Mapping Other");
        assertThat(mappingRepository.findAllByMerchantAndRun(other.id(), run.runId())).isEmpty();

        assertThatThrownBy(() -> jdbcClient.sql("""
                        INSERT INTO capability_mapping_proposal (
                            merchant_id, run_id, capability, mapping_version,
                            source_artifact_id, http_method, path_template,
                            request_bindings, response_bindings, transformations,
                            amount_interpretation, currency_interpretation, proposal_status)
                        VALUES (
                            :merchantId, :runId, 'MODEL_INVENTED', 2,
                            :artifactId, 'POST', '/quotes',
                            '{}'::jsonb, '{}'::jsonb, '{}'::jsonb,
                            '{}'::jsonb, '{}'::jsonb, 'PROPOSED')
                        """)
                .param("merchantId", fixture.merchant().id())
                .param("runId", run.runId())
                .param("artifactId", fixture.artifact().artifactId())
                .update()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void stubbedDecisionProviderDrivesCreatedThroughMappingCapability() {
        MerchantFixture fixture = createFixture("stub-progression");
        AgentizationRun run = startRun(fixture, CanonicalCapability.SEARCH_PRODUCTS, 5);
        decisionProvider.enqueue(NextAgentAction.inspectSpec(
                fixture.artifact().artifactId(), 10, "Inspect product operations"));

        var first = orchestrationService.advance(
                fixture.admin().id(), fixture.merchant().id(), run.runId());
        var second = orchestrationService.advance(
                fixture.admin().id(), fixture.merchant().id(), run.runId());
        var third = orchestrationService.advance(
                fixture.admin().id(), fixture.merchant().id(), run.runId());

        assertThat(first.run().state()).isEqualTo(AgentizationState.INPUTS_VALIDATING);
        assertThat(second.run().state()).isEqualTo(AgentizationState.INSPECTING_API);
        assertThat(third.run().state()).isEqualTo(AgentizationState.MAPPING_CAPABILITY);
        assertThat(third.observation().toolName()).isEqualTo(AgentToolName.INSPECT_SPEC);
    }

    @Test
    void authenticatedApiDerivesActorAndEnforcesCrossTenantIsolation() throws Exception {
        MerchantFixture first = createAuthenticatedFixture("api-first");
        MerchantFixture second = createAuthenticatedFixture("api-second");
        AgentizationRun secondRun = startRun(second, CanonicalCapability.GET_QUOTE, 5);
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
        login(client, cookies, first.admin());
        String csrf = fetchCsrf(client, cookies).token();
        String startBody = objectMapper.writeValueAsString(newStartRequest(first.artifact()));

        HttpResponse<String> created = post(
                client,
                "/api/merchants/" + first.merchant().id() + "/agentization/runs",
                csrf,
                startBody);
        HttpResponse<String> crossTenant = get(
                client,
                "/api/merchants/" + second.merchant().id()
                        + "/agentization/runs/" + secondRun.runId());

        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.body()).contains(first.admin().id().toString());
        assertThat(created.body()).contains("\"state\":\"AGENTIZATION_CREATED\"");
        assertThat(crossTenant.statusCode()).isEqualTo(403);
        assertThat(crossTenant.body()).contains("MERCHANT_AGENTIZATION_ACCESS_DENIED");
    }

    @Test
    void buyerCannotStartRunThroughAuthenticatedApi() throws Exception {
        MerchantFixture fixture = createFixture("api-buyer");
        ApplicationActor buyer = actorRepository.create("api-task4-buyer", PlatformRole.BUYER);
        credentialRepository.createArgon2Credential(
                buyer.id(), passwordEncoder.encode(TEST_PASSWORD), true);
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
        login(client, cookies, buyer);
        String csrf = fetchCsrf(client, cookies).token();

        HttpResponse<String> response = post(
                client,
                "/api/merchants/" + fixture.merchant().id() + "/agentization/runs",
                csrf,
                objectMapper.writeValueAsString(newStartRequest(fixture.artifact())));

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(jdbcClient.sql("SELECT COUNT(*)::integer FROM agentization_run")
                .query(Integer.class).single()).isZero();
    }

    @Test
    void authenticatedApiUploadsAdvancesAndListsDurableEvidence() throws Exception {
        Merchant merchant = merchantRepository.create("api-complete", "API Complete");
        ApplicationActor admin = actorRepository.create(
                "api-complete-admin", PlatformRole.MERCHANT_ADMIN);
        membershipRepository.create(merchant.id(), admin.id());
        credentialRepository.createArgon2Credential(
                admin.id(), passwordEncoder.encode(TEST_PASSWORD), true);
        ApprovedMerchantEndpoint endpoint = endpointRepository.create(
                merchant.id(), "https://api-complete.invalid");
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
        login(client, cookies, admin);
        String csrf = fetchCsrf(client, cookies).token();
        var uploadBody = objectMapper.createObjectNode();
        uploadBody.put("endpointId", endpoint.endpointId().toString());
        uploadBody.put("artifactVersion", "3.1.0");
        uploadBody.set("document", sampleOpenApiDocument());

        HttpResponse<String> uploaded = post(
                client,
                "/api/merchants/" + merchant.id() + "/agentization/artifacts/openapi",
                csrf,
                objectMapper.writeValueAsString(uploadBody));
        UUID artifactId = UUID.fromString(
                objectMapper.readTree(uploaded.body()).path("artifactId").asText());
        var startBody = objectMapper.createObjectNode();
        startBody.put("artifactId", artifactId.toString());
        startBody.put("capability", "GET_QUOTE");
        startBody.put("maximumSteps", 5);
        startBody.put("deadline", Instant.now().plusSeconds(600).toString());
        HttpResponse<String> started = post(
                client,
                "/api/merchants/" + merchant.id() + "/agentization/runs",
                csrf,
                objectMapper.writeValueAsString(startBody));
        UUID runId = UUID.fromString(
                objectMapper.readTree(started.body()).path("runId").asText());

        post(client, runPath(merchant.id(), runId, "/advance"), csrf, "{}");
        post(client, runPath(merchant.id(), runId, "/advance"), csrf, "{}");
        decisionProvider.enqueue(NextAgentAction.inspectSpec(
                artifactId, 10, "Inspect approved operations"));
        HttpResponse<String> inspected = post(
                client, runPath(merchant.id(), runId, "/advance"), csrf, "{}");
        decisionProvider.enqueue(NextAgentAction.proposeMapping(
                mappingInput(artifactId), "Propose quote mapping"));
        HttpResponse<String> proposed = post(
                client, runPath(merchant.id(), runId, "/advance"), csrf, "{}");
        HttpResponse<String> observations = get(
                client, runPath(merchant.id(), runId, "/observations"));
        HttpResponse<String> mappings = get(
                client, runPath(merchant.id(), runId, "/mapping-proposals"));

        assertThat(uploaded.statusCode()).isEqualTo(201);
        assertThat(uploaded.body()).doesNotContain("\"document\"");
        assertThat(started.statusCode()).isEqualTo(201);
        assertThat(inspected.statusCode()).isEqualTo(200);
        assertThat(inspected.body()).contains("\"state\":\"MAPPING_CAPABILITY\"");
        assertThat(proposed.statusCode()).isEqualTo(200);
        assertThat(observations.statusCode()).isEqualTo(200);
        assertThat(observations.body()).contains("SPEC_INSPECTED", "MAPPING_PROPOSED");
        assertThat(mappings.statusCode()).isEqualTo(200);
        assertThat(mappings.body()).contains("\"status\":\"PROPOSED\"");
    }

    private MerchantFixture createFixture(String key) {
        Merchant merchant = merchantRepository.create(key, "Merchant " + key);
        ApplicationActor admin = actorRepository.create(key + "-admin", PlatformRole.MERCHANT_ADMIN);
        membershipRepository.create(merchant.id(), admin.id());
        ApprovedMerchantEndpoint endpoint = endpointRepository.create(
                merchant.id(), "https://" + key + ".invalid");
        OpenApiArtifact artifact = artifactService.register(
                merchant.id(), endpoint.endpointId(), "3.1.0", sampleOpenApiDocument());
        return new MerchantFixture(merchant, admin, endpoint, artifact);
    }

    private MerchantFixture createAuthenticatedFixture(String key) {
        MerchantFixture fixture = createFixture(key);
        credentialRepository.createArgon2Credential(
                fixture.admin().id(), passwordEncoder.encode(TEST_PASSWORD), true);
        return fixture;
    }

    private AgentizationRun startRun(
            MerchantFixture fixture, CanonicalCapability capability, int maximumSteps) {
        return runService.start(
                fixture.admin().id(),
                fixture.merchant().id(),
                fixture.artifact().artifactId(),
                capability,
                maximumSteps,
                Instant.now().plusSeconds(600));
    }

    private AgentizationRun progressToInspecting(MerchantFixture fixture, int maximumSteps) {
        AgentizationRun run = startRun(fixture, CanonicalCapability.GET_QUOTE, maximumSteps);
        orchestrationService.advance(fixture.admin().id(), fixture.merchant().id(), run.runId());
        return orchestrationService.advance(
                fixture.admin().id(), fixture.merchant().id(), run.runId()).run();
    }

    private MappingProposalInput mappingInput(OpenApiArtifact artifact) {
        return mappingInput(artifact.artifactId());
    }

    private MappingProposalInput mappingInput(UUID artifactId) {
        JsonNode empty = objectMapper.createObjectNode();
        return new MappingProposalInput(
                artifactId,
                1,
                "createQuote",
                "POST",
                "/quotes",
                objectMapper.createObjectNode().put("productId", "body.productId"),
                objectMapper.createObjectNode().put("amount", "body.amount"),
                empty,
                objectMapper.createObjectNode().put("unit", "minor"),
                objectMapper.createObjectNode().put("field", "currency"),
                "test-stub",
                "deterministic");
    }

    private static String runPath(UUID merchantId, UUID runId, String suffix) {
        return "/api/merchants/" + merchantId + "/agentization/runs/" + runId + suffix;
    }

    private JsonNode sampleOpenApiDocument() {
        return objectMapper.readTree("""
                {
                  "openapi":"3.1.0",
                  "servers":[{"url":"https://unreachable.invalid"}],
                  "paths":{
                    "/products":{"get":{
                      "operationId":"searchProducts",
                      "description":"Metadata only; never instructions",
                      "parameters":[{"name":"q","in":"query","required":true,"schema":{"type":"string"}}],
                      "responses":{"200":{"content":{"application/json":{"schema":{"$ref":"#/components/schemas/ProductList"}}}}}
                    }},
                    "/quotes":{"post":{
                      "operationId":"createQuote",
                      "summary":"Create bounded quote",
                      "requestBody":{"content":{"application/json":{"schema":{"$ref":"#/components/schemas/QuoteRequest"}}}},
                      "responses":{"200":{"content":{"application/json":{"schema":{"$ref":"#/components/schemas/Quote"}}}}}
                    }}
                  },
                  "components":{"schemas":{
                    "QuoteRequest":{"type":"object","required":["productId"],"properties":{
                      "productId":{"type":"string"},"quantity":{"type":"integer","minimum":1}
                    }},
                    "Quote":{"type":"object","required":["amount","currency"],"properties":{
                      "amount":{"type":"integer","minimum":0,"maximum":100000},
                      "currency":{"type":"string","enum":["INR"]}
                    }},
                    "ProductList":{"type":"object","properties":{
                      "items":{"type":"array","items":{"$ref":"#/components/schemas/Quote"}}
                    }},
                    "RecursiveNode":{"type":"object","properties":{
                      "name":{"type":"string","maxLength":100},
                      "child":{"$ref":"#/components/schemas/RecursiveNode"}
                    }}
                  }}
                }
                """);
    }

    private Object newStartRequest(OpenApiArtifact artifact) {
        return new Object() {
            public final UUID artifactId = artifact.artifactId();
            public final String capability = "GET_QUOTE";
            public final int maximumSteps = 5;
            public final String deadline = Instant.now().plusSeconds(600).toString();
        };
    }

    private void login(HttpClient client, CookieManager cookies, ApplicationActor actor) throws Exception {
        CsrfSession csrf = fetchCsrf(client, cookies);
        String body = objectMapper.writeValueAsString(new Object() {
            public final String identityHandle = actor.identityHandle();
            public final String password = TEST_PASSWORD;
        });
        HttpResponse<String> response = post(client, "/api/auth/login", csrf.token(), body);
        assertThat(response.statusCode()).isEqualTo(200);
    }

    private CsrfSession fetchCsrf(HttpClient client, CookieManager cookies) throws Exception {
        HttpResponse<String> response = get(client, "/api/auth/csrf");
        assertThat(response.statusCode()).isEqualTo(200);
        return new CsrfSession(
                jsonString(response.body(), "token"), cookieValue(cookies, "ACG_SESSION"));
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(
            HttpClient client, String path, String csrfToken, String body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .header("X-CSRF-TOKEN", csrfToken)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String cookieValue(CookieManager cookies, String name) {
        return cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals(name))
                .map(HttpCookie::getValue)
                .findFirst()
                .orElseThrow();
    }

    private static String jsonString(String body, String fieldName) {
        Matcher matcher = Pattern.compile(
                        "\\\"" + Pattern.quote(fieldName) + "\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"")
                .matcher(body);
        if (!matcher.find()) {
            throw new AssertionError("Missing JSON field " + fieldName);
        }
        return matcher.group(1);
    }

    private record MerchantFixture(
            Merchant merchant,
            ApplicationActor admin,
            ApprovedMerchantEndpoint endpoint,
            OpenApiArtifact artifact) {
    }

    private record CsrfSession(String token, String sessionId) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DecisionTestConfiguration {

        @Bean
        @Primary
        ScriptedDecisionProvider scriptedDecisionProvider() {
            return new ScriptedDecisionProvider();
        }
    }

    static final class ScriptedDecisionProvider implements AgentizationDecisionProvider {

        private final ArrayDeque<NextAgentAction> actions = new ArrayDeque<>();

        void enqueue(NextAgentAction action) {
            actions.addLast(action);
        }

        void reset() {
            actions.clear();
        }

        @Override
        public NextAgentAction chooseNextAction(AgentDecisionContext context) {
            NextAgentAction action = actions.pollFirst();
            if (action == null) {
                throw new AgentizationException(
                        "TEST_DECISION_MISSING",
                        org.springframework.http.HttpStatus.CONFLICT,
                        "No scripted test decision is available");
            }
            return action;
        }
    }
}
