package dev.agenticcommerce.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenticcommerce.gateway.agentization.execution.ApprovedMerchantExecutor;
import dev.agenticcommerce.gateway.agentization.execution.MerchantDnsResolver;
import dev.agenticcommerce.gateway.agentization.execution.MerchantEndpointSafetyService;
import dev.agenticcommerce.gateway.agentization.execution.MerchantExecutionException;
import dev.agenticcommerce.gateway.agentization.execution.MerchantTransport;
import dev.agenticcommerce.gateway.agentization.execution.MerchantTransportRequest;
import dev.agenticcommerce.gateway.agentization.execution.MerchantTransportResponse;
import dev.agenticcommerce.gateway.agentization.execution.ValidatedEndpointResolution;
import dev.agenticcommerce.gateway.agentization.model.AgentToolName;
import dev.agenticcommerce.gateway.agentization.model.AgentizationRun;
import dev.agenticcommerce.gateway.agentization.model.AgentizationState;
import dev.agenticcommerce.gateway.agentization.model.CapabilityContractTestRun;
import dev.agenticcommerce.gateway.agentization.model.CapabilityMappingProposal;
import dev.agenticcommerce.gateway.agentization.model.CanonicalCapability;
import dev.agenticcommerce.gateway.agentization.model.ContractTestOutcome;
import dev.agenticcommerce.gateway.agentization.model.GetQuoteTestCase;
import dev.agenticcommerce.gateway.agentization.model.MappingTransformation;
import dev.agenticcommerce.gateway.agentization.persistence.AgentObservationRepository;
import dev.agenticcommerce.gateway.agentization.persistence.AgentizationRunRepository;
import dev.agenticcommerce.gateway.agentization.persistence.CapabilityContractTestRunRepository;
import dev.agenticcommerce.gateway.agentization.persistence.CapabilityMappingProposalRepository;
import dev.agenticcommerce.gateway.agentization.service.AgentizationException;
import dev.agenticcommerce.gateway.agentization.service.AgentizationOrchestrationService;
import dev.agenticcommerce.gateway.agentization.service.AgentizationRunService;
import dev.agenticcommerce.gateway.agentization.service.ApprovedMerchantEndpointService;
import dev.agenticcommerce.gateway.agentization.service.ExecutableMappingValidator;
import dev.agenticcommerce.gateway.agentization.service.GetQuoteContractTestService;
import dev.agenticcommerce.gateway.agentization.service.OpenApiArtifactService;
import dev.agenticcommerce.gateway.agentization.tool.AgentDecisionContext;
import dev.agenticcommerce.gateway.agentization.tool.AgentizationDecisionProvider;
import dev.agenticcommerce.gateway.agentization.tool.MappingProposalInput;
import dev.agenticcommerce.gateway.agentization.tool.MappingRevisionInput;
import dev.agenticcommerce.gateway.agentization.tool.NextAgentAction;
import dev.agenticcommerce.gateway.identity.model.ApplicationActor;
import dev.agenticcommerce.gateway.identity.model.Merchant;
import dev.agenticcommerce.gateway.identity.model.PlatformRole;
import dev.agenticcommerce.gateway.identity.persistence.ApplicationActorRepository;
import dev.agenticcommerce.gateway.identity.persistence.MerchantAdminMembershipRepository;
import dev.agenticcommerce.gateway.identity.persistence.MerchantRepository;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest
@Import(Task005AgentizationIntegrationTest.DeterministicBoundaryConfiguration.class)
class Task005AgentizationIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:0.8.1-pg17");

    @Autowired JdbcClient jdbcClient;
    @Autowired ObjectMapper objectMapper;
    @Autowired MerchantRepository merchantRepository;
    @Autowired ApplicationActorRepository actorRepository;
    @Autowired MerchantAdminMembershipRepository membershipRepository;
    @Autowired ApprovedMerchantEndpointService endpointService;
    @Autowired MerchantEndpointSafetyService safetyService;
    @Autowired OpenApiArtifactService artifactService;
    @Autowired AgentizationRunService runService;
    @Autowired AgentizationRunRepository runRepository;
    @Autowired CapabilityMappingProposalRepository mappingRepository;
    @Autowired CapabilityContractTestRunRepository contractTestRepository;
    @Autowired ExecutableMappingValidator mappingValidator;
    @Autowired GetQuoteContractTestService contractTestService;
    @Autowired ApprovedMerchantExecutor approvedExecutor;
    @Autowired AgentizationOrchestrationService orchestrationService;
    @Autowired AgentObservationRepository observationRepository;
    @Autowired DeterministicDnsResolver dnsResolver;
    @Autowired DeterministicMerchantTransport merchantTransport;
    @Autowired ScriptedTask005DecisionProvider decisionProvider;

    @BeforeEach
    void clearData() {
        decisionProvider.reset();
        dnsResolver.reset();
        merchantTransport.reset();
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
                        """).update();
    }

    @Test
    void validPublicHttpsEndpointIsExplicitlyApprovedWithDnsEvidence() throws Exception {
        IdentityFixture identity = identity("safe-endpoint");
        dnsResolver.answer("merchant.example", "93.184.216.34", "2606:2800:220:1:248:1893:25c8:1946");

        var endpoint = endpointService.registerAndApprove(
                identity.admin().id(), identity.merchant().id(), "https://merchant.example",
                Set.of("POST"), List.of("/cart/price"));

        assertThat(endpoint.approvalStatus()).isEqualTo("APPROVED");
        assertThat(endpoint.approvedByActorId()).isEqualTo(identity.admin().id());
        assertThat(endpoint.approvedResolvedAddresses()).hasSize(2);
        assertThat(endpoint.baseUri()).isEqualTo("https://merchant.example");
        assertThat(endpoint.approvedAt()).isNotNull();
    }

    @ParameterizedTest
    @MethodSource("invalidEndpointUrls")
    void rejectsMalformedNonHttpsAndEmbeddedCredentialUrls(String candidate, String code) throws Exception {
        dnsResolver.answer("merchant.example", "93.184.216.34");

        assertThatThrownBy(() -> safetyService.validateAndResolve(candidate))
                .isInstanceOf(AgentizationException.class)
                .extracting(error -> ((AgentizationException) error).code())
                .isEqualTo(code);
    }

    static List<Arguments> invalidEndpointUrls() {
        return List.of(
                Arguments.of("http://merchant.example", "ENDPOINT_HTTPS_REQUIRED"),
                Arguments.of("not a url", "ENDPOINT_URL_MALFORMED"),
                Arguments.of("https://user:secret@merchant.example", "ENDPOINT_USER_INFO_FORBIDDEN"));
    }

    @ParameterizedTest
    @MethodSource("unsafeAddresses")
    void rejectsEveryRequiredUnsafeAddressClass(String address) throws Exception {
        dnsResolver.answer("unsafe.example", address);

        assertThatThrownBy(() -> safetyService.validateAndResolve("https://unsafe.example"))
                .isInstanceOf(AgentizationException.class)
                .extracting(error -> ((AgentizationException) error).code())
                .isEqualTo("ENDPOINT_ADDRESS_UNSAFE");
    }

    static List<String> unsafeAddresses() {
        return List.of(
                "127.0.0.1", "10.1.2.3", "172.16.1.2", "192.168.2.3", "169.254.169.254",
                "0.0.0.0", "224.0.0.1", "::1", "fc00::1", "fe80::1", "ff02::1");
    }

    @Test
    void rejectsMixedSafeAndUnsafeDnsAnswers() throws Exception {
        dnsResolver.answer("mixed.example", "93.184.216.34", "127.0.0.1");

        assertThatThrownBy(() -> safetyService.validateAndResolve("https://mixed.example"))
                .isInstanceOf(AgentizationException.class)
                .extracting(error -> ((AgentizationException) error).code())
                .isEqualTo("ENDPOINT_ADDRESS_UNSAFE");
    }

    @Test
    void dnsFailureFailsClosed() {
        dnsResolver.fail("missing.example");

        assertThatThrownBy(() -> safetyService.validateAndResolve("https://missing.example"))
                .isInstanceOf(AgentizationException.class)
                .extracting(error -> ((AgentizationException) error).code())
                .isEqualTo("ENDPOINT_DNS_RESOLUTION_FAILED");
    }

    @Test
    void executionRevalidatesDnsAndUsesOnlyTheApprovedHost() throws Exception {
        ExecutionFixture fixture = testingFixture("runtime-dns");
        int afterApproval = dnsResolver.resolveCount("runtime-dns.example");

        contractTestService.run(fixture.run(), fixture.mapping(), GetQuoteTestCase.canonicalRupeesFixture());

        assertThat(dnsResolver.resolveCount("runtime-dns.example")).isEqualTo(afterApproval + 1);
        assertThat(merchantTransport.lastRequest().uri().getHost()).isEqualTo("runtime-dns.example");
        assertThat(merchantTransport.lastResolution().addresses()).containsExactly(
                InetAddress.getByName("93.184.216.34"));
    }

    @Test
    void runtimeDnsRebindingToUnsafeAddressFailsBeforeTransport() throws Exception {
        ExecutionFixture fixture = testingFixture("rebind");
        dnsResolver.answer("rebind.example", "127.0.0.1");

        CapabilityContractTestRun result = contractTestService.run(
                fixture.run(), fixture.mapping(), GetQuoteTestCase.canonicalRupeesFixture());
        assertThat(result.outcome()).isEqualTo(ContractTestOutcome.UNKNOWN);
        assertThat(result.failureCode()).isEqualTo("ENDPOINT_ADDRESS_UNSAFE");
        assertThat(merchantTransport.executionCount()).isZero();
    }

    @Test
    void endpointOwnershipAndMappingTenantAreBothEnforced() throws Exception {
        ExecutionFixture first = testingFixture("tenant-owner-a");
        IdentityFixture second = identity("tenant-owner-b");

        assertThatThrownBy(() -> approvedExecutor.execute(
                second.merchant().id(), first.mapping(), Map.of(), new byte[0],
                dev.agenticcommerce.gateway.agentization.execution.MerchantExecutionMode.CONTRACT_TEST))
                .isInstanceOf(AgentizationException.class)
                .extracting(error -> ((AgentizationException) error).code())
                .isEqualTo("MAPPING_TENANT_MISMATCH");
    }

    @Test
    void agentActionAndExecutorExposeNoArbitraryUrlAuthority() {
        assertThat(List.of(NextAgentAction.class.getRecordComponents()))
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("url", "uri", "headers");
        assertThat(List.of(ApprovedMerchantExecutor.class.getDeclaredMethods()))
                .filteredOn(method -> method.getName().equals("execute"))
                .allSatisfy(method -> assertThat(method.getParameterTypes())
                        .doesNotContain(java.net.URI.class));
    }

    @Test
    void mappingValidationRejectsRevokedEndpointAndUnapprovedMethodPath() throws Exception {
        ExecutionFixture fixture = testingFixture("scope-check");
        CapabilityMappingProposal wrongMethod = createMapping(
                fixture.identity(), fixture.run(), fixture.artifact(), 2, "GET", "/cart/price", objectMapper.createObjectNode());
        CapabilityMappingProposal wrongPath = createMapping(
                fixture.identity(), fixture.run(), fixture.artifact(), 3, "POST", "/quotes", objectMapper.createObjectNode());

        assertThatThrownBy(() -> mappingValidator.validate(wrongMethod))
                .isInstanceOf(AgentizationException.class).hasMessageContaining("method");
        assertThatThrownBy(() -> mappingValidator.validate(wrongPath))
                .isInstanceOf(AgentizationException.class).hasMessageContaining("path");

        jdbcClient.sql("UPDATE merchant_approved_endpoint SET approval_status = 'REVOKED', revoked_at = CURRENT_TIMESTAMP")
                .update();
        assertThatThrownBy(() -> mappingValidator.validate(fixture.mapping()))
                .isInstanceOf(AgentizationException.class)
                .extracting(error -> ((AgentizationException) error).code())
                .isEqualTo("MAPPING_ENDPOINT_NOT_APPROVED");
    }

    @Test
    void unsupportedScriptTransformationIsRejected() throws Exception {
        ExecutionFixture fixture = baseFixture("script-reject");
        JsonNode transformations = objectMapper.createObjectNode().put("amount", "javascript:amount*100");
        CapabilityMappingProposal mapping = createMapping(
                fixture.identity(), fixture.run(), fixture.artifact(), 1, "POST", "/cart/price", transformations);

        assertThatThrownBy(() -> mappingValidator.validate(mapping))
                .isInstanceOf(AgentizationException.class)
                .extracting(error -> ((AgentizationException) error).code())
                .isEqualTo("MAPPING_TRANSFORMATION_UNSUPPORTED");
    }

    @Test
    void responseLimitAndTimeoutBecomeDurableFailClosedOutcomes() throws Exception {
        ExecutionFixture fixture = testingFixture("transport-failures");
        merchantTransport.fail(new MerchantExecutionException(
                "MERCHANT_RESPONSE_TOO_LARGE", "bounded response"));
        CapabilityContractTestRun tooLarge = contractTestService.run(
                fixture.run(), fixture.mapping(), GetQuoteTestCase.canonicalRupeesFixture());
        merchantTransport.fail(new MerchantExecutionException("MERCHANT_TIMEOUT", "bounded timeout"));
        CapabilityContractTestRun timeout = contractTestService.run(
                fixture.run(), fixture.mapping(), GetQuoteTestCase.canonicalRupeesFixture());

        assertThat(tooLarge.outcome()).isEqualTo(ContractTestOutcome.FAIL);
        assertThat(tooLarge.failureCode()).isEqualTo("MERCHANT_RESPONSE_TOO_LARGE");
        assertThat(timeout.outcome()).isEqualTo(ContractTestOutcome.UNKNOWN);
        assertThat(timeout.failureCode()).isEqualTo("MERCHANT_TIMEOUT");
        assertThat(contractTestRepository.findAllByMerchantAndRun(
                fixture.identity().merchant().id(), fixture.run().runId())).hasSize(2);
    }

    @ParameterizedTest
    @MethodSource("invalidQuoteResponses")
    void quoteContractDetectsMissingMalformedAndWrongCurrency(
            String response, String failureCode) throws Exception {
        ExecutionFixture fixture = testingFixture(
                "quote-" + failureCode.toLowerCase().replace('_', '-'));
        merchantTransport.respond(200, "application/json", response);

        CapabilityContractTestRun testRun = contractTestService.run(
                fixture.run(), fixture.mapping(), GetQuoteTestCase.canonicalRupeesFixture());

        assertThat(testRun.outcome()).isEqualTo(ContractTestOutcome.FAIL);
        assertThat(testRun.failureCode()).isEqualTo(failureCode);
        assertThat(testRun.structuredEvidence().has("expectedAmountPaise")).isTrue();
    }

    static List<Arguments> invalidQuoteResponses() {
        return List.of(
                Arguments.of("{\"currency\":\"INR\"}", "MISSING_AMOUNT"),
                Arguments.of("{\"amount\":\"499\",\"currency\":\"INR\"}", "INVALID_AMOUNT"),
                Arguments.of("{\"amount\":499,\"currency\":\"USD\"}", "WRONG_CURRENCY"));
    }

    @Test
    void redirectResponseIsNotFollowedAndCannotPassTheContract() throws Exception {
        ExecutionFixture fixture = testingFixture("redirect");
        merchantTransport.respond(302, "application/json", "{\"amount\":499,\"currency\":\"INR\"}");

        CapabilityContractTestRun testRun = contractTestService.run(
                fixture.run(), fixture.mapping(), GetQuoteTestCase.canonicalRupeesFixture());

        assertThat(testRun.outcome()).isEqualTo(ContractTestOutcome.FAIL);
        assertThat(testRun.failureCode()).isEqualTo("HTTP_STATUS_UNEXPECTED");
        assertThat(merchantTransport.executionCount()).isOne();
    }

    @Test
    void canonicalMismatchIsDataDrivenAndConvertedMappingPassesAt49900Paise() throws Exception {
        ExecutionFixture fixture = testingFixture("money-contract");

        CapabilityContractTestRun first = contractTestService.run(
                fixture.run(), fixture.mapping(), GetQuoteTestCase.canonicalRupeesFixture());
        var converted = objectMapper.createObjectNode()
                .put("amount", MappingTransformation.MONEY_RUPEES_TO_PAISE.name());
        CapabilityMappingProposal revision = createMapping(
                fixture.identity(), fixture.run(), fixture.artifact(), 2, "POST", "/cart/price", converted);
        mappingValidator.validate(revision);
        revision = mappingRepository.markValidation(
                revision.merchantId(), revision.runId(), revision.mappingProposalId(), true);
        CapabilityContractTestRun second = contractTestService.run(
                fixture.run(), revision, GetQuoteTestCase.canonicalRupeesFixture());

        assertThat(first.outcome()).isEqualTo(ContractTestOutcome.FAIL);
        assertThat(first.failureCode()).isEqualTo("MONEY_UNIT_MISMATCH");
        assertThat(second.outcome()).isEqualTo(ContractTestOutcome.PASS);
        assertThat(second.structuredEvidence().path("normalizedAmountPaise").longValue()).isEqualTo(49_900L);
    }

    @Test
    void autonomousFailureDiagnosisRevisionAndRetestIsAuditable() throws Exception {
        ExecutionFixture fixture = baseFixture("autonomous-loop");
        CapabilityMappingProposal initial = createMapping(
                fixture.identity(), fixture.run(), fixture.artifact(), 1, "POST", "/cart/price",
                objectMapper.createObjectNode());
        forceState(fixture.run(), AgentizationState.MAPPING_CAPABILITY, null);

        decisionProvider.enqueue(NextAgentAction.validateMapping(
                fixture.artifact().artifactId(), initial.mappingProposalId(), "Validate version one"));
        var validated = advance(fixture);
        decisionProvider.enqueue(NextAgentAction.runContractTest(
                fixture.artifact().artifactId(), initial.mappingProposalId(),
                "GET_QUOTE_RUPEES_499", "Run deterministic quote contract"));
        var failed = advance(fixture);
        UUID failedTestId = failed.observation().contractTestRunId();
        decisionProvider.enqueue(NextAgentAction.inspectTestFailure(
                fixture.artifact().artifactId(), failedTestId, "Inspect bounded failure evidence"));
        var diagnosed = advance(fixture);
        decisionProvider.enqueue(NextAgentAction.reviseMapping(
                fixture.artifact().artifactId(),
                new MappingRevisionInput(
                        initial.mappingProposalId(), failedTestId, "amount",
                        MappingTransformation.MONEY_RUPEES_TO_PAISE,
                        "Merchant schema evidence says INR amount is rupees",
                        "test-gemini", "gemini-3.6-flash"),
                "Apply the only evidence-supported money normalization"));
        var revised = advance(fixture);
        CapabilityMappingProposal versionTwo = revised.mappingProposal();
        decisionProvider.enqueue(NextAgentAction.validateMapping(
                fixture.artifact().artifactId(), versionTwo.mappingProposalId(), "Validate revision"));
        var revalidated = advance(fixture);
        decisionProvider.enqueue(NextAgentAction.runContractTest(
                fixture.artifact().artifactId(), versionTwo.mappingProposalId(),
                "GET_QUOTE_RUPEES_499", "Retest unchanged contract truth"));
        var passed = advance(fixture);

        assertThat(validated.run().state()).isEqualTo(AgentizationState.TESTING_CAPABILITY);
        assertThat(failed.run().state()).isEqualTo(AgentizationState.DIAGNOSING_FAILURE);
        assertThat(failed.observation().contractTestFailureCode()).isEqualTo("MONEY_UNIT_MISMATCH");
        assertThat(diagnosed.run().state()).isEqualTo(AgentizationState.REVISING_MAPPING);
        assertThat(diagnosed.observation().structuredResult().toString()).doesNotContain("secret", "credential");
        assertThat(versionTwo.mappingVersion()).isEqualTo(2);
        assertThat(versionTwo.previousMappingProposalId()).isEqualTo(initial.mappingProposalId());
        assertThat(versionTwo.transformations().path("amount").asText())
                .isEqualTo("MONEY_RUPEES_TO_PAISE");
        assertThat(revalidated.run().state()).isEqualTo(AgentizationState.TESTING_CAPABILITY);
        assertThat(passed.run().state()).isEqualTo(AgentizationState.READY_CANDIDATE);
        assertThat(passed.observation().structuredResult().path("normalizedAmountPaise").longValue())
                .isEqualTo(49_900L);
        assertThat(mappingRepository.findByMerchantRunAndId(
                initial.merchantId(), initial.runId(), initial.mappingProposalId()).orElseThrow()
                .transformations()).isEmpty();
        assertThat(observationRepository.findAllByMerchantAndRun(
                fixture.identity().merchant().id(), fixture.run().runId()))
                .extracting(observation -> observation.toolName())
                .containsExactly(
                        AgentToolName.VALIDATE_MAPPING,
                        AgentToolName.RUN_CONTRACT_TEST,
                        AgentToolName.INSPECT_TEST_FAILURE,
                        AgentToolName.REVISE_MAPPING,
                        AgentToolName.VALIDATE_MAPPING,
                        AgentToolName.RUN_CONTRACT_TEST);
    }

    @Test
    void invalidRevisionCannotMutateTestTruthOrPublishReady() throws Exception {
        ExecutionFixture fixture = testingFixture("authority-boundary");
        CapabilityContractTestRun failure = contractTestService.run(
                fixture.run(), fixture.mapping(), GetQuoteTestCase.canonicalRupeesFixture());
        forceState(fixture.run(), AgentizationState.REVISING_MAPPING, fixture.mapping().mappingVersion());
        decisionProvider.enqueue(NextAgentAction.reviseMapping(
                fixture.artifact().artifactId(),
                new MappingRevisionInput(
                        fixture.mapping().mappingProposalId(), failure.contractTestRunId(), "currency",
                        MappingTransformation.MONEY_RUPEES_TO_PAISE, "Change test instead", "stub", "stub"),
                "Invalid authority attempt"));

        var result = advance(fixture);

        assertThat(result.observation().outcome().name()).isEqualTo("FAILURE");
        assertThat(result.observation().reasonCode()).isEqualTo("MAPPING_REVISION_NOT_SUPPORTED");
        assertThat(result.run().state()).isEqualTo(AgentizationState.REVISING_MAPPING);
        assertThat(contractTestRepository.findByMerchantRunAndId(
                fixture.identity().merchant().id(), fixture.run().runId(), failure.contractTestRunId())
                .orElseThrow().structuredEvidence().path("expectedAmountPaise").longValue()).isEqualTo(49_900L);
    }

    @Test
    void repeatedIdenticalFailureSignatureEscalatesAtDeterministicThreshold() throws Exception {
        ExecutionFixture fixture = testingFixture("repeat-budget");
        for (int attempt = 1; attempt <= 3; attempt++) {
            forceState(fixture.run(), AgentizationState.TESTING_CAPABILITY, fixture.mapping().mappingVersion());
            decisionProvider.enqueue(NextAgentAction.runContractTest(
                    fixture.artifact().artifactId(), fixture.mapping().mappingProposalId(),
                    "GET_QUOTE_RUPEES_499", "Repeat deterministic failure"));
            var result = advance(fixture);
            AgentizationState expected = attempt < 3
                    ? AgentizationState.DIAGNOSING_FAILURE
                    : AgentizationState.WAITING_FOR_MERCHANT_CLARIFICATION;
            assertThat(result.run().state()).isEqualTo(expected);
        }
        AgentizationRun persisted = runRepository.findByMerchantAndId(
                fixture.identity().merchant().id(), fixture.run().runId()).orElseThrow();
        assertThat(persisted.repeatedFailureCount()).isEqualTo(3);
        assertThat(persisted.terminalReason()).isEqualTo("REPEATED_IDENTICAL_FAILURE");
    }

    @Test
    void v005MigrationAndContractEvidenceConstraintsAreApplied() {
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*)::integer FROM flyway_schema_history
                        WHERE script = 'V005__hardened_merchant_execution_and_contract_testing.sql' AND success
                        """).query(Integer.class).single()).isOne();
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*)::integer FROM information_schema.tables
                        WHERE table_name = 'capability_contract_test_run'
                        """).query(Integer.class).single()).isOne();
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*)::integer FROM information_schema.tables
                        WHERE table_name = 'merchant_approved_endpoint'
                        """).query(Integer.class).single()).isOne();
    }

    private ExecutionFixture testingFixture(String key) throws Exception {
        ExecutionFixture fixture = baseFixture(key);
        CapabilityMappingProposal mapping = createMapping(
                fixture.identity(), fixture.run(), fixture.artifact(), 1, "POST", "/cart/price",
                objectMapper.createObjectNode());
        mappingValidator.validate(mapping);
        mapping = mappingRepository.markValidation(
                mapping.merchantId(), mapping.runId(), mapping.mappingProposalId(), true);
        forceState(fixture.run(), AgentizationState.TESTING_CAPABILITY, mapping.mappingVersion());
        return new ExecutionFixture(fixture.identity(), fixture.endpoint(), fixture.artifact(), fixture.run(), mapping);
    }

    private ExecutionFixture baseFixture(String key) throws Exception {
        IdentityFixture identity = identity(key);
        String hostname = key + ".example";
        dnsResolver.answer(hostname, "93.184.216.34");
        var endpoint = endpointService.registerAndApprove(
                identity.admin().id(), identity.merchant().id(), "https://" + hostname,
                Set.of("POST"), List.of("/cart/price"));
        var artifact = artifactService.register(
                identity.merchant().id(), endpoint.endpointId(), "3.1.0", quoteOpenApi(hostname));
        AgentizationRun run = runService.start(
                identity.admin().id(), identity.merchant().id(), artifact.artifactId(),
                CanonicalCapability.GET_QUOTE, 30, Instant.now().plusSeconds(1800));
        return new ExecutionFixture(identity, endpoint, artifact, run, null);
    }

    private CapabilityMappingProposal createMapping(
            IdentityFixture identity,
            AgentizationRun run,
            dev.agenticcommerce.gateway.agentization.model.OpenApiArtifact artifact,
            int version,
            String method,
            String path,
            JsonNode transformations) {
        return mappingRepository.create(
                identity.merchant().id(), run.runId(), CanonicalCapability.GET_QUOTE,
                new MappingProposalInput(
                        artifact.artifactId(), version, "priceCart", method, path,
                        objectMapper.createObjectNode().put("productId", "body.productId"),
                        objectMapper.createObjectNode()
                                .put("amount", "body.amount")
                                .put("currency", "body.currency")
                                .put("quoteId", "body.quoteId"),
                        transformations,
                        objectMapper.createObjectNode().put("unit", "minor"),
                        objectMapper.createObjectNode().put("field", "currency"),
                        "deterministic-test", "stub"));
    }

    private IdentityFixture identity(String key) {
        Merchant merchant = merchantRepository.create(key, "Merchant " + key);
        ApplicationActor admin = actorRepository.create(key + "-admin", PlatformRole.MERCHANT_ADMIN);
        membershipRepository.create(merchant.id(), admin.id());
        return new IdentityFixture(merchant, admin);
    }

    private JsonNode quoteOpenApi(String hostname) {
        return objectMapper.readTree("""
                {"openapi":"3.1.0","servers":[{"url":"https://%s"}],"paths":{
                  "/cart/price":{"post":{"operationId":"priceCart","responses":{"200":{
                    "description":"Amount example is 499 Indian rupees, not paise",
                    "content":{"application/json":{"schema":{"type":"object","required":["amount","currency"],
                    "properties":{"amount":{"type":"integer","example":499,"x-money-unit":"rupees"},
                    "currency":{"type":"string","enum":["INR"]},"quoteId":{"type":"string"}}}}}}}}}}
                }
                """.formatted(hostname));
    }

    private void forceState(AgentizationRun run, AgentizationState state, Integer mappingVersion) {
        jdbcClient.sql("""
                        UPDATE agentization_run
                        SET orchestration_state = :state, current_mapping_version = :mappingVersion,
                            completed_at = NULL, terminal_reason = NULL,
                            updated_at = CURRENT_TIMESTAMP, version = version + 1
                        WHERE run_id = :runId
                        """)
                .param("state", state.name())
                .param("mappingVersion", mappingVersion)
                .param("runId", run.runId())
                .update();
    }

    private dev.agenticcommerce.gateway.agentization.service.AdvanceAgentizationResult advance(
            ExecutionFixture fixture) {
        return orchestrationService.advance(
                fixture.identity().admin().id(), fixture.identity().merchant().id(), fixture.run().runId());
    }

    private record IdentityFixture(Merchant merchant, ApplicationActor admin) {
    }

    private record ExecutionFixture(
            IdentityFixture identity,
            dev.agenticcommerce.gateway.agentization.model.ApprovedMerchantEndpoint endpoint,
            dev.agenticcommerce.gateway.agentization.model.OpenApiArtifact artifact,
            AgentizationRun run,
            CapabilityMappingProposal mapping) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DeterministicBoundaryConfiguration {
        @Bean @Primary DeterministicDnsResolver deterministicDnsResolver() {
            return new DeterministicDnsResolver();
        }

        @Bean @Primary DeterministicMerchantTransport deterministicMerchantTransport() {
            return new DeterministicMerchantTransport();
        }

        @Bean @Primary ScriptedTask005DecisionProvider scriptedTask005DecisionProvider() {
            return new ScriptedTask005DecisionProvider();
        }
    }

    static final class DeterministicDnsResolver implements MerchantDnsResolver {
        private final Map<String, List<InetAddress>> answers = new HashMap<>();
        private final Set<String> failures = new java.util.HashSet<>();
        private final Map<String, AtomicInteger> counts = new HashMap<>();

        void answer(String hostname, String... addresses) throws UnknownHostException {
            List<InetAddress> parsed = new ArrayList<>();
            for (String address : addresses) parsed.add(InetAddress.getByName(address));
            answers.put(hostname, List.copyOf(parsed));
            failures.remove(hostname);
        }

        void fail(String hostname) {
            failures.add(hostname);
            answers.remove(hostname);
        }

        int resolveCount(String hostname) {
            return counts.getOrDefault(hostname, new AtomicInteger()).get();
        }

        void reset() {
            answers.clear(); failures.clear(); counts.clear();
        }

        @Override
        public List<InetAddress> resolve(String hostname) throws UnknownHostException {
            counts.computeIfAbsent(hostname, ignored -> new AtomicInteger()).incrementAndGet();
            if (failures.contains(hostname) || !answers.containsKey(hostname)) {
                throw new UnknownHostException(hostname);
            }
            return answers.get(hostname);
        }
    }

    static final class DeterministicMerchantTransport implements MerchantTransport {
        private MerchantTransportResponse response;
        private MerchantExecutionException failure;
        private MerchantTransportRequest lastRequest;
        private ValidatedEndpointResolution lastResolution;
        private int executions;

        void reset() {
            response = json("{\"amount\":499,\"currency\":\"INR\",\"quoteId\":\"quote-499\",\"lineItems\":[]}");
            failure = null; lastRequest = null; lastResolution = null; executions = 0;
        }

        void respond(int status, String contentType, String body) {
            response = new MerchantTransportResponse(status, contentType, body.getBytes(StandardCharsets.UTF_8));
            failure = null;
        }

        void fail(MerchantExecutionException exception) {
            failure = exception;
        }

        MerchantTransportRequest lastRequest() { return lastRequest; }
        ValidatedEndpointResolution lastResolution() { return lastResolution; }
        int executionCount() { return executions; }

        @Override
        public MerchantTransportResponse execute(
                ValidatedEndpointResolution resolution, MerchantTransportRequest request) {
            executions++; lastResolution = resolution; lastRequest = request;
            if (failure != null) throw failure;
            return response;
        }

        private static MerchantTransportResponse json(String body) {
            return new MerchantTransportResponse(
                    200, "application/json", body.getBytes(StandardCharsets.UTF_8));
        }
    }

    static final class ScriptedTask005DecisionProvider implements AgentizationDecisionProvider {
        private final ArrayDeque<NextAgentAction> actions = new ArrayDeque<>();
        void enqueue(NextAgentAction action) { actions.addLast(action); }
        void reset() { actions.clear(); }
        @Override public NextAgentAction chooseNextAction(AgentDecisionContext context) {
            NextAgentAction action = actions.pollFirst();
            if (action == null) throw new AgentizationException(
                    "TEST_DECISION_MISSING", org.springframework.http.HttpStatus.CONFLICT,
                    "No deterministic Task 005 decision is queued");
            return action;
        }
    }
}
