package dev.agenticcommerce.gateway;

import static dev.agenticcommerce.gateway.catalogue.CatalogueModels.*;
import static dev.agenticcommerce.gateway.commerce.TransactionModels.*;
import static dev.agenticcommerce.gateway.intent.BuyerModels.*;
import static org.assertj.core.api.Assertions.*;

import dev.agenticcommerce.gateway.agentization.execution.*;
import dev.agenticcommerce.gateway.agentization.service.CanonicalJsonService;
import dev.agenticcommerce.gateway.authorization.AuthorizationService;
import dev.agenticcommerce.gateway.authorization.ExecutionGate;
import dev.agenticcommerce.gateway.catalogue.*;
import dev.agenticcommerce.gateway.commerce.*;
import dev.agenticcommerce.gateway.commerce.ServiceabilityProvider.ServiceabilityRequest;
import dev.agenticcommerce.gateway.commerce.ServiceabilityProvider.ServiceabilityResult;
import dev.agenticcommerce.gateway.identity.model.*;
import dev.agenticcommerce.gateway.identity.persistence.*;
import dev.agenticcommerce.gateway.intent.*;
import dev.agenticcommerce.gateway.onboarding.*;
import static dev.agenticcommerce.gateway.onboarding.OnboardingModels.*;
import dev.agenticcommerce.gateway.risk.*;
import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
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

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(Task009TransactionAuthorityIntegrationTest.Fakes.class)
class Task009TransactionAuthorityIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:0.8.1-pg17");

    @Autowired JdbcClient jdbc;
    @Autowired MerchantRepository merchants;
    @Autowired ApplicationActorRepository actors;
    @Autowired MerchantAdminMembershipRepository memberships;
    @Autowired CatalogueService catalogues;
    @Autowired CatalogueRepository catalogueRepository;
    @Autowired BuyerThreadService threads;
    @Autowired BuyerOrchestrationService buyer;
    @Autowired BuyerRepository buyerRepository;
    @Autowired AuthoritativeRefreshService refreshes;
    @Autowired TransactionProposalService proposals;
    @Autowired TransactionProposalCanonicalizer canonicalizer;
    @Autowired CanonicalJsonService canonical;
    @Autowired TransactionAuthorityRepository authorityRepository;
    @Autowired ReversibilityEngine riskEngine;
    @Autowired ReversibilityService risks;
    @Autowired AuthorizationService authorizations;
    @Autowired ExecutionGate gate;
    @Autowired AuthoritativeQuoteService quoteService;
    @Autowired Task009Transport transport;
    @Autowired TrustedServiceability serviceability;
    @Autowired ObjectMapper mapper;
    @Autowired OnboardingService onboarding;
    @Autowired ActorPasswordCredentialRepository credentials;
    @Autowired PasswordEncoder passwordEncoder;
    @LocalServerPort int port;

    @BeforeEach
    void clear() {
        transport.reset();
        serviceability.reset();
        jdbc.sql("TRUNCATE TABLE merchant,application_actor CASCADE").update();
    }

    @Test
    void v009CreatesImmutableAuthoritySchemaAndExactlyOneExecutionConstraint() {
        assertThat(jdbc.sql("SELECT count(*)::int FROM flyway_schema_history WHERE version='009' AND success")
                .query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("""
                SELECT count(*)::int FROM information_schema.tables WHERE table_name IN (
                  'authoritative_availability_refresh','authoritative_availability_item',
                  'authoritative_serviceability_evidence','transaction_authority_refresh',
                  'transaction_proposal','transaction_proposal_line_item','reversibility_evaluation',
                  'authorization_decision','transaction_execution','authorization_consumption',
                  'execution_gate_evidence')
                """).query(Integer.class).single()).isEqualTo(11);
        assertThat(jdbc.sql("""
                SELECT count(*)::int FROM pg_constraint
                WHERE conrelid='transaction_execution'::regclass
                  AND contype='u' AND pg_get_constraintdef(oid) LIKE '%(proposal_id)%'
                """).query(Integer.class).single()).isOne();
    }

    @Test
    void availabilityAndServiceabilityAreAuthoritativeExplicitAndFailClosed() {
        Fixture fixture = fixture("evidence");
        CommerceThread thread = reachConstraints(fixture);
        AuthorityRefresh pass = refreshes.refresh(fixture.buyer().id(), thread.threadId());
        assertThat(pass.outcome()).isEqualTo(EvidenceOutcome.PASS);
        assertThat(pass.availability().items()).singleElement().satisfies(item -> {
            assertThat(item.requestedQuantity()).isEqualTo(2);
            assertThat(item.authoritativeQuantity()).isEqualTo(8);
            assertThat(item.outcome()).isEqualTo(EvidenceOutcome.PASS);
        });
        assertThat(pass.serviceability().sourceType()).isEqualTo(ServiceabilitySource.TRUSTED_DEMO_FIXTURE);
        assertThat(pass.serviceability().outcome()).isEqualTo(EvidenceOutcome.PASS);

        transport.availabilityMode = "FAIL";
        AuthorityRefresh failed = refreshes.refresh(fixture.buyer().id(), thread.threadId());
        assertThat(failed.availability().outcome()).isEqualTo(EvidenceOutcome.FAIL);
        assertThat(failed.outcome()).isEqualTo(EvidenceOutcome.FAIL);

        transport.availabilityMode = "UNKNOWN";
        AuthorityRefresh unknown = refreshes.refresh(fixture.buyer().id(), thread.threadId());
        assertThat(unknown.availability().outcome()).isEqualTo(EvidenceOutcome.UNKNOWN);
        assertThat(unknown.outcome()).isEqualTo(EvidenceOutcome.UNKNOWN);
        assertThat(jdbc.sql("SELECT stock_quantity FROM merchant_product_commerce_state LIMIT 1")
                .query(Long.class).single()).isPositive();

        transport.availabilityMode = "PASS";
        serviceability.outcome = EvidenceOutcome.FAIL;
        assertThat(refreshes.refresh(fixture.buyer().id(), thread.threadId()).serviceability().outcome())
                .isEqualTo(EvidenceOutcome.FAIL);
        serviceability.outcome = EvidenceOutcome.UNKNOWN;
        assertThat(refreshes.refresh(fixture.buyer().id(), thread.threadId()).serviceability().outcome())
                .isEqualTo(EvidenceOutcome.UNKNOWN);
    }

    @Test
    void proposalIsImmutableIntegerPaiseAndCanonicalHashCoversEveryMaterialBinding() {
        Prepared prepared = prepareProposal("material");
        TransactionProposal proposal = prepared.proposal();
        assertThat(proposal.finalAmountMinor()).isEqualTo(36_000L);
        assertThat(proposal.currency()).isEqualTo("INR");
        assertThat(proposal.policySnapshotId()).isEqualTo(prepared.fixture().policySnapshot());
        assertThat(proposal.lineItems()).singleElement().satisfies(line -> {
            assertThat(line.merchantSku()).isEqualTo("SAFE-CHANA");
            assertThat(line.variant()).isEqualTo("Masala");
            assertThat(line.quantity()).isEqualTo(2);
        });
        assertThat(canonical.hash(proposal.canonicalMaterial())).isEqualTo(proposal.proposalHash());
        assertThat(proposal.canonicalMaterial().path("fulfilmentAuthority").path("snapshotHash").asText())
                .matches("[0-9a-f]{64}");
        assertThat(proposal.canonicalMaterial().path("fulfilmentAuthority").path("merchantAccountLinkHash").asText())
                .matches("[0-9a-f]{64}");
        assertThat(canonicalizer.canonicalize(proposal).hash()).isNotEqualTo(proposal.proposalHash());
        assertThatThrownBy(() -> jdbc.sql("UPDATE transaction_proposal SET final_amount_minor=1 WHERE proposal_id=:id")
                .param("id", proposal.proposalId()).update()).isInstanceOf(RuntimeException.class);

        ProposalDraft base = draft(proposal, proposal.merchantId(), proposal.policySnapshotVersion(),
                proposal.quoteRecordId(), proposal.finalAmountMinor(), proposal.lineItems());
        String hash = canonicalizer.canonicalize(base).hash();
        assertThat(canonicalizer.canonicalize(draft(proposal, UUID.randomUUID(),
                proposal.policySnapshotVersion(), proposal.quoteRecordId(), proposal.finalAmountMinor(),
                proposal.lineItems())).hash()).isNotEqualTo(hash);
        assertThat(canonicalizer.canonicalize(draft(proposal, proposal.merchantId(),
                proposal.policySnapshotVersion() + 1, proposal.quoteRecordId(),
                proposal.finalAmountMinor(), proposal.lineItems())).hash()).isNotEqualTo(hash);
        assertThat(canonicalizer.canonicalize(draft(proposal, proposal.merchantId(),
                proposal.policySnapshotVersion(), UUID.randomUUID(), proposal.finalAmountMinor(),
                proposal.lineItems())).hash()).isNotEqualTo(hash);
        assertThat(canonicalizer.canonicalize(draft(proposal, proposal.merchantId(),
                proposal.policySnapshotVersion(), proposal.quoteRecordId(), proposal.finalAmountMinor() + 1,
                proposal.lineItems())).hash()).isNotEqualTo(hash);
        ProposalLineItem line = proposal.lineItems().getFirst();
        List<ProposalLineItem> quantityChanged = List.of(new ProposalLineItem(null, 0, line.productId(),
                line.merchantSku(), line.variant(), line.quantity() + 1,
                line.unitAmountMinor(), line.lineAmountMinor() + line.unitAmountMinor()));
        assertThat(canonicalizer.canonicalize(draft(proposal, proposal.merchantId(),
                proposal.policySnapshotVersion(), proposal.quoteRecordId(), proposal.finalAmountMinor(),
                quantityChanged)).hash()).isNotEqualTo(hash);
        List<ProposalLineItem> productChanged = List.of(new ProposalLineItem(null, 0, UUID.randomUUID(),
                "OTHER-SKU", "Other", line.quantity(), line.unitAmountMinor(), line.lineAmountMinor()));
        assertThat(canonicalizer.canonicalize(draft(proposal, proposal.merchantId(),
                proposal.policySnapshotVersion(), proposal.quoteRecordId(), proposal.finalAmountMinor(),
                productChanged)).hash()).isNotEqualTo(hash);
    }

    @Test
    void deterministicReversibilityRulesCoverBlockClarifyExplicitAndAutoExecute() {
        RiskInput validLow = riskInput(10_000, EvidenceOutcome.PASS, false,
                EvidenceOutcome.PASS, EvidenceOutcome.PASS, false, false);
        assertThat(riskEngine.evaluate(validLow).outcome()).isEqualTo(ReversibilityOutcome.AUTO_EXECUTE);
        assertThat(riskEngine.evaluate(riskInput(36_000, EvidenceOutcome.PASS, false,
                EvidenceOutcome.PASS, EvidenceOutcome.PASS, false, false)).outcome())
                .isEqualTo(ReversibilityOutcome.EXPLICIT_CONFIRMATION);
        assertThat(riskEngine.evaluate(riskInput(10_000, EvidenceOutcome.PASS, false,
                EvidenceOutcome.PASS, EvidenceOutcome.PASS, true, false)).outcome())
                .isEqualTo(ReversibilityOutcome.CLARIFY);
        assertThat(riskEngine.evaluate(riskInput(10_000, EvidenceOutcome.FAIL, false,
                EvidenceOutcome.PASS, EvidenceOutcome.PASS, false, false)).outcome())
                .isEqualTo(ReversibilityOutcome.BLOCK);
        assertThat(riskEngine.evaluate(riskInput(10_000, EvidenceOutcome.PASS, true,
                EvidenceOutcome.PASS, EvidenceOutcome.PASS, false, false)).outcome())
                .isEqualTo(ReversibilityOutcome.BLOCK);
        assertThat(riskEngine.evaluate(riskInput(10_000, EvidenceOutcome.PASS, false,
                EvidenceOutcome.UNKNOWN, EvidenceOutcome.PASS, false, false)).outcome())
                .isEqualTo(ReversibilityOutcome.BLOCK);
    }

    @Test
    void canonicalJourneyRequiresExactConfirmationThenReservesWithoutPaymentSuccess() {
        Prepared prepared = prepareProposal("canonical");
        String session = authorizations.bindSession("canonical-session");
        ReversibilityEvaluation risk = risks.evaluate(prepared.fixture().buyer().id(),
                prepared.proposal().proposalId(), session);
        assertThat(risk.outcome()).isEqualTo(ReversibilityOutcome.EXPLICIT_CONFIRMATION);
        assertThat(risk.paymentAuthorizationStillRequired()).isTrue();
        assertThat(threads.require(prepared.fixture().buyer().id(), prepared.thread().threadId()).state())
                .isEqualTo(BuyerState.WAITING_FOR_USER);
        AuthorizationDecision authorization = authorizations.confirm(prepared.fixture().buyer().id(),
                prepared.proposal().proposalId(), session);
        assertThat(authorization.decision()).isEqualTo(AuthorizationDecisionType.AUTHORIZED);
        assertThat(authorization.proposalHash()).isEqualTo(prepared.proposal().proposalHash());
        assertThat(authorization.actionType()).isEqualTo(ActionType.PURCHASE);
        assertThat(threads.require(prepared.fixture().buyer().id(), prepared.thread().threadId()).state())
                .isEqualTo(BuyerState.READY_TO_EXECUTE);
        ExecutionGateResult result = gate.reserve(prepared.fixture().buyer().id(),
                prepared.proposal().proposalId(), session);
        assertThat(result.decision()).isEqualTo(GateDecision.ALLOW);
        assertThat(result.execution().status()).isEqualTo(ExecutionStatus.RESERVED);
        assertThat(result.execution().providerOrderReference()).isNull();
        assertThat(jdbc.sql("SELECT count(*)::int FROM transaction_execution WHERE proposal_id=:proposal")
                .param("proposal", prepared.proposal().proposalId()).query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("SELECT to_regclass('razorpay_order') IS NULL")
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void lowRiskAutoExecuteCreatesBoundedAuthorityButStillOnlyReservesExecution() {
        transport.finalAmount = 20_000;
        transport.subtotal = 18_000;
        Prepared prepared = prepareProposal("auto");
        String session = authorizations.bindSession("auto-session");
        ReversibilityEvaluation risk = risks.evaluate(prepared.fixture().buyer().id(),
                prepared.proposal().proposalId(), session);
        assertThat(risk.outcome()).isEqualTo(ReversibilityOutcome.AUTO_EXECUTE);
        AuthorizationDecision authorization = authorizations.current(prepared.fixture().buyer().id(),
                prepared.proposal().proposalId());
        assertThat(authorization.authorizationMethod()).isEqualTo(AuthorizationMethod.AUTO_EXECUTE_POLICY);
        assertThat(authorization.decision()).isEqualTo(AuthorizationDecisionType.AUTHORIZED);
        assertThat(gate.reserve(prepared.fixture().buyer().id(), prepared.proposal().proposalId(), session)
                .execution().status()).isEqualTo(ExecutionStatus.RESERVED);
        assertThat(jdbc.sql("SELECT count(*)::int FROM transaction_execution WHERE status='RESERVED'")
                .query(Integer.class).single()).isOne();
    }

    @Test
    void authorizationIsActorSessionProposalHashActionAndExpiryBound() {
        Prepared prepared = prepareProposal("auth");
        String session = authorizations.bindSession("auth-session");
        risks.evaluate(prepared.fixture().buyer().id(), prepared.proposal().proposalId(), session);
        AuthorizationDecision authorization = authorizations.confirm(prepared.fixture().buyer().id(),
                prepared.proposal().proposalId(), session);
        assertThat(authorization.buyerActorId()).isEqualTo(prepared.fixture().buyer().id());
        assertThat(authorization.proposalId()).isEqualTo(prepared.proposal().proposalId());
        assertThat(authorization.proposalHash()).isEqualTo(prepared.proposal().proposalHash());
        assertThat(authorization.expiresAt()).isAfter(authorization.issuedAt())
                .isBeforeOrEqualTo(prepared.proposal().proposalExpiresAt());
        assertThat(gate.reserve(prepared.fixture().buyer().id(), prepared.proposal().proposalId(),
                authorizations.bindSession("different-session")).reasonCode())
                .isEqualTo("AUTHORIZATION_SESSION_MISMATCH");
        ApplicationActor other = actors.create("wrong-auth-buyer@test", PlatformRole.BUYER);
        assertThatThrownBy(() -> authorizations.confirm(other.id(), prepared.proposal().proposalId(), session))
                .isInstanceOf(TransactionAuthorityException.class);
    }

    @Test
    void denialAndExpiredAuthorizationCanNeverOpenExecution() {
        Prepared denied = prepareProposal("denied");
        String deniedSession = authorizations.bindSession("denied-session");
        risks.evaluate(denied.fixture().buyer().id(), denied.proposal().proposalId(), deniedSession);
        AuthorizationDecision decision = authorizations.deny(denied.fixture().buyer().id(),
                denied.proposal().proposalId(), deniedSession);
        assertThat(decision.decision()).isEqualTo(AuthorizationDecisionType.DENIED);
        assertThat(gate.reserve(denied.fixture().buyer().id(), denied.proposal().proposalId(), deniedSession)
                .reasonCode()).isEqualTo("AUTHORIZATION_DENIED");
        assertThat(jdbc.sql("SELECT count(*)::int FROM transaction_execution")
                .query(Integer.class).single()).isZero();

        clear();
        Prepared expired = prepareProposal("expired-auth");
        String expiredSession = authorizations.bindSession("expired-session");
        Instant issued = Instant.now().minus(2, ChronoUnit.MINUTES);
        Instant expires = Instant.now().minus(1, ChronoUnit.MINUTES);
        var material = mapper.createObjectNode().put("failureInjection", "expired-authorization");
        AuthorizationDecision stale = authorityRepository.createAuthorization(expired.proposal(),
                expiredSession, AuthorizationDecisionType.AUTHORIZED,
                AuthorizationMethod.EXPLICIT_CONFIRMATION, issued, expires, material, "a".repeat(64));
        authorityRepository.attachAuthorization(expired.thread().threadId(), expired.fixture().buyer().id(),
                expired.proposal().proposalId(), stale.authorizationId(), BuyerState.READY_TO_EXECUTE.name());
        assertThat(gate.reserve(expired.fixture().buyer().id(), expired.proposal().proposalId(), expiredSession)
                .reasonCode()).isEqualTo("AUTHORIZATION_EXPIRED");
    }

    @Test
    void executionGateDeniesTamperedMutableTruthUnknownCertificateAndNonReadyCapability() {
        Prepared prepared = authorized("gate-denials");
        UUID availability = prepared.refresh().availability().availabilityRefreshId();
        disable("authoritative_availability_refresh", "trg_availability_refresh_immutable");
        jdbc.sql("UPDATE authoritative_availability_refresh SET outcome='UNKNOWN' WHERE availability_refresh_id=:id")
                .param("id", availability).update();
        enable("authoritative_availability_refresh", "trg_availability_refresh_immutable");
        assertThat(gate.reserve(prepared.fixture().buyer().id(), prepared.proposal().proposalId(),
                prepared.session()).reasonCode()).isEqualTo("STOCK_UNKNOWN");

        disable("authoritative_availability_refresh", "trg_availability_refresh_immutable");
        jdbc.sql("UPDATE authoritative_availability_refresh SET outcome='PASS' WHERE availability_refresh_id=:id")
                .param("id", availability).update();
        enable("authoritative_availability_refresh", "trg_availability_refresh_immutable");
        disable("constraint_certificate", "trg_constraint_certificate_immutable");
        jdbc.sql("UPDATE constraint_certificate SET overall_result='UNKNOWN' WHERE certificate_id=:id")
                .param("id", prepared.proposal().constraintCertificateId()).update();
        enable("constraint_certificate", "trg_constraint_certificate_immutable");
        assertThat(gate.reserve(prepared.fixture().buyer().id(), prepared.proposal().proposalId(),
                prepared.session()).reasonCode()).isEqualTo("CONSTRAINT_CERTIFICATE_NOT_PASS");

        disable("constraint_certificate", "trg_constraint_certificate_immutable");
        jdbc.sql("UPDATE constraint_certificate SET overall_result='PASS' WHERE certificate_id=:id")
                .param("id", prepared.proposal().constraintCertificateId()).update();
        enable("constraint_certificate", "trg_constraint_certificate_immutable");
        jdbc.sql("""
                UPDATE agent_commerce_manifest_capability
                SET readiness='UNTESTED',advertised=false,executable_mapping_proposal_id=NULL
                WHERE merchant_id=:merchant AND capability='GET_AVAILABILITY'
                """).param("merchant", prepared.fixture().merchant().id()).update();
        assertThat(gate.reserve(prepared.fixture().buyer().id(), prepared.proposal().proposalId(),
                prepared.session()).reasonCode()).isEqualTo("MERCHANT_CAPABILITY_NOT_READY");
    }

    @Test
    void sequentialAndConcurrentDuplicatesResolveOneStableDatabaseExecution() throws Exception {
        Prepared prepared = authorized("concurrency");
        int attempts = 8;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        try {
            CountDownLatch ready = new CountDownLatch(attempts);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<ExecutionGateResult>> futures = new ArrayList<>();
            for (int index = 0; index < attempts; index++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    return gate.reserve(prepared.fixture().buyer().id(),
                            prepared.proposal().proposalId(), prepared.session());
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<ExecutionGateResult> results = new ArrayList<>();
            for (Future<ExecutionGateResult> future : futures) results.add(future.get(20, TimeUnit.SECONDS));
            assertThat(results).allSatisfy(result -> assertThat(result.decision()).isEqualTo(GateDecision.ALLOW));
            assertThat(results).extracting(result -> result.execution().executionId()).containsOnly(
                    results.getFirst().execution().executionId());
            assertThat(results).extracting(result -> result.execution().idempotencyKey()).containsOnly(
                    results.getFirst().execution().idempotencyKey());
        } finally {
            pool.shutdownNow();
        }
        ExecutionGateResult sequential = gate.reserve(prepared.fixture().buyer().id(),
                prepared.proposal().proposalId(), prepared.session());
        assertThat(sequential.duplicateResolution()).isTrue();
        assertThat(jdbc.sql("SELECT count(*)::int FROM transaction_execution WHERE proposal_id=:proposal")
                .param("proposal", prepared.proposal().proposalId()).query(Integer.class).single()).isOne();
    }

    @Test
    void refreshedMaterialQuoteCreatesNewProposalAndOldProposalRemainsAuditable() {
        Prepared first = prepareProposal("refresh-change");
        disable("merchant_quote", "trg_merchant_quote_immutable");
        jdbc.sql("UPDATE merchant_quote SET expires_at=:expired WHERE quote_record_id=:id")
                .param("expired", Instant.now().minus(1, ChronoUnit.MINUTES).atOffset(java.time.ZoneOffset.UTC),
                        java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .param("id", first.proposal().quoteRecordId()).update();
        enable("merchant_quote", "trg_merchant_quote_immutable");
        transport.finalAmount = 37_000;
        transport.subtotal = 35_000;
        AuthorityRefresh secondRefresh = refreshes.refresh(first.fixture().buyer().id(), first.thread().threadId());
        TransactionProposal second = proposals.create(first.fixture().buyer().id(), first.thread().threadId());
        assertThat(second.quoteRecordId()).isNotEqualTo(first.proposal().quoteRecordId());
        assertThat(second.proposalHash()).isNotEqualTo(first.proposal().proposalHash());
        assertThat(authorityRepository.findProposal(first.fixture().buyer().id(), first.proposal().proposalId()))
                .isPresent();
        assertThat(jdbc.sql("SELECT count(*)::int FROM transaction_proposal WHERE thread_id=:thread")
                .param("thread", first.thread().threadId()).query(Integer.class).single()).isEqualTo(2);
        assertThat(secondRefresh.quote().finalAmountMinor()).isEqualTo(37_000L);
    }

    @Test
    void buyerHttpAuthorityApiKeepsCsrfOwnershipAndServerSideProposalMaterial() throws Exception {
        Prepared prepared = prepareProposal("http");
        risks.evaluate(prepared.fixture().buyer().id(), prepared.proposal().proposalId(),
                authorizations.bindSession("direct-risk-session"));
        ApplicationActor other = actors.create("task009-http-other@test", PlatformRole.BUYER);
        for (ApplicationActor actor : List.of(prepared.fixture().buyer(), other)) {
            credentials.createArgon2Credential(actor.id(), passwordEncoder.encode("task009-password"), true);
        }
        CookieManager ownerCookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient owner = HttpClient.newBuilder().cookieHandler(ownerCookies).build();
        login(owner, prepared.fixture().buyer());
        String path = "/api/buyer/threads/" + prepared.thread().threadId() + "/transaction/proposals/"
                + prepared.proposal().proposalId() + "/confirm";
        assertThat(post(owner, path, null, "{\"amount\":1}").statusCode()).isEqualTo(403);
        HttpResponse<String> confirmed = post(owner, path, csrf(owner), "{}");
        assertThat(confirmed.statusCode()).isEqualTo(200);
        JsonNode response = mapper.readTree(confirmed.body());
        assertThat(response.path("proposalHash").asText()).isEqualTo(prepared.proposal().proposalHash());

        CookieManager otherCookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient otherClient = HttpClient.newBuilder().cookieHandler(otherCookies).build();
        login(otherClient, other);
        assertThat(get(otherClient, "/api/buyer/threads/" + prepared.thread().threadId()
                + "/transaction/proposal").statusCode()).isEqualTo(404);
    }

    @Test
    void purchaseReadinessRemainsUntestedAndNoPaymentProviderObjectExists() {
        Prepared prepared = authorized("purchase-readiness");
        gate.reserve(prepared.fixture().buyer().id(), prepared.proposal().proposalId(), prepared.session());
        assertThat(jdbc.sql("""
                SELECT readiness FROM agent_commerce_manifest_capability
                WHERE merchant_id=:merchant AND capability='PURCHASE'
                """).param("merchant", prepared.fixture().merchant().id()).query(String.class).single())
                .isEqualTo("UNTESTED");
        assertThat(jdbc.sql("SELECT to_regclass('razorpay_order') IS NULL AND to_regclass('payment') IS NULL")
                .query(Boolean.class).single()).isTrue();
        assertThat(EnumSet.allOf(ExecutionStatus.class))
                .containsExactlyInAnyOrder(ExecutionStatus.RESERVED, ExecutionStatus.PAYMENT_PENDING,
                        ExecutionStatus.FAILED);
    }

    private Prepared prepareProposal(String key) {
        Fixture fixture = fixture(key);
        CommerceThread thread = reachConstraints(fixture);
        AuthorityRefresh refresh = refreshes.refresh(fixture.buyer().id(), thread.threadId());
        TransactionProposal proposal = proposals.create(fixture.buyer().id(), thread.threadId());
        return new Prepared(fixture, thread, refresh, proposal, null);
    }

    private Prepared authorized(String key) {
        Prepared prepared = prepareProposal(key);
        String session = authorizations.bindSession(key + "-session");
        risks.evaluate(prepared.fixture().buyer().id(), prepared.proposal().proposalId(), session);
        if (risks.require(prepared.fixture().buyer().id(), prepared.proposal().proposalId()).outcome()
                == ReversibilityOutcome.EXPLICIT_CONFIRMATION) {
            authorizations.confirm(prepared.fixture().buyer().id(), prepared.proposal().proposalId(), session);
        }
        return new Prepared(prepared.fixture(), prepared.thread(), prepared.refresh(), prepared.proposal(), session);
    }

    private CommerceThread reachConstraints(Fixture fixture) {
        CommerceThread thread = threads.create(fixture.buyer().id(), canonicalText());
        for (int step = 0; step < 6; step++) buyer.advance(fixture.buyer().id(), thread.threadId());
        CommerceThread result = threads.require(fixture.buyer().id(), thread.threadId());
        assertThat(result.state()).isEqualTo(BuyerState.CONSTRAINTS_VERIFIED);
        return result;
    }

    private Fixture fixture(String key) {
        Merchant merchant = merchants.create("authority-" + key, "Authority " + key);
        ApplicationActor admin = actors.create(key + "-authority-admin@test", PlatformRole.MERCHANT_ADMIN);
        memberships.create(merchant.id(), admin.id());
        ApplicationActor buyerActor = actors.create(key + "-authority-buyer@test", PlatformRole.BUYER);
        onboard(buyerActor,merchant,key);
        CatalogueVersion version = catalogues.ingest(admin.id(), merchant.id(), "JSON", cataloguePayload()).version();
        List<Product> products = catalogueRepository.products(merchant.id(), version.id(), 20);
        UUID safe = product(products, "SAFE-CHANA");
        UUID resolution = jdbc.sql("""
                SELECT identity_resolution_id FROM product_identity_resolution
                WHERE product_id=:product AND external_source='MERCHANT'
                """).param("product", safe).query(UUID.class).single();
        var absent = mapper.createObjectNode().put("allergen", "peanut").put("status", "ABSENT");
        catalogueRepository.insertFact(merchant.id(), version.id(), safe, resolution, "ALLERGEN",
                absent, "MERCHANT", "safe-absence", "v1", "PRIMARY", "ACTIVE", Instant.now(),
                Instant.now().plus(30, ChronoUnit.DAYS), hash(key + "-allergen"));
        Published published = publishReady(merchant, admin, version);
        return new Fixture(merchant, admin, buyerActor, version.id(), safe,
                published.quoteMapping(), published.availabilityMapping(), published.policySnapshot());
    }

    private void onboard(ApplicationActor buyerActor,Merchant merchant,String key){
        onboarding.updateProfile(buyerActor.id(),new ProfileInput("Buyer "+key,"+919900000001",key+"@buyer.test"));
        var address=onboarding.addAddress(buyerActor.id(),new AddressInput("HOME","Buyer "+key,"+919900000001",
                "1 Demo Street",null,"Demo Locality","Bengaluru","Karnataka","560001"));
        onboarding.selectAddress(buyerActor.id(),address.id());
        onboarding.link(buyerActor.id(),new LinkRequest(merchant.id(),"demo-user","demo-password"));
    }

    private Published publishReady(Merchant merchant, ApplicationActor admin, CatalogueVersion version) {
        UUID endpoint = jdbc.sql("""
                INSERT INTO merchant_approved_endpoint(
                    merchant_id,base_uri,hostname,approved_at,dns_validated_at)
                VALUES(:merchant,'https://merchant.example.test','merchant.example.test',
                    CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING endpoint_id
                """).param("merchant", merchant.id()).query(UUID.class).single();
        UUID artifact = jdbc.sql("""
                INSERT INTO openapi_artifact(
                    merchant_id,endpoint_id,artifact_type,artifact_version,content_hash,document)
                VALUES(:merchant,:endpoint,'OPENAPI','v1',:hash,'{}') RETURNING artifact_id
                """).param("merchant", merchant.id()).param("endpoint", endpoint)
                .param("hash", hash("artifact-" + merchant.id())).query(UUID.class).single();
        UUID run = jdbc.sql("""
                INSERT INTO agentization_run(
                    merchant_id,created_by_actor_id,source_artifact_id,target_capability,
                    current_capability,orchestration_state,max_step_budget,wall_clock_deadline)
                VALUES(:merchant,:actor,:artifact,'GET_QUOTE','GET_QUOTE','READY_CANDIDATE',20,
                    CURRENT_TIMESTAMP+interval '1 hour') RETURNING run_id
                """).param("merchant", merchant.id()).param("actor", admin.id())
                .param("artifact", artifact).query(UUID.class).single();
        UUID quote = mapping(merchant.id(), run, artifact, endpoint, "GET_QUOTE", "/quotes",
                "{\"cartId\":\"body.cartId\"}",
                "{\"amount\":\"body.finalAmountMinor\",\"currency\":\"body.currency\",\"quoteId\":\"body.quoteId\"}");
        UUID availability = mapping(merchant.id(), run, artifact, endpoint, "GET_AVAILABILITY", "/products",
                "{\"merchantSku\":\"body.merchantSku\",\"requestedQuantity\":\"body.requestedQuantity\"}",
                "{\"availableQuantity\":\"body.availableQuantity\",\"available\":\"body.available\",\"observedAt\":\"body.observedAt\"}");
        UUID snapshot = jdbc.sql("""
                INSERT INTO merchant_policy_snapshot(
                    merchant_id,snapshot_version,snapshot_hash,published_by_actor_id)
                VALUES(:merchant,1,:hash,:actor) RETURNING policy_snapshot_id
                """).param("merchant", merchant.id()).param("hash", hash("policy-" + merchant.id()))
                .param("actor", admin.id()).query(UUID.class).single();
        UUID searchEval = readiness(merchant.id(), run, snapshot, "SEARCH_PRODUCTS", "READY", null);
        UUID quoteEval = readiness(merchant.id(), run, snapshot, "GET_QUOTE", "READY", quote);
        UUID availabilityEval = readiness(merchant.id(), run, snapshot, "GET_AVAILABILITY", "READY", availability);
        UUID purchaseEval = readiness(merchant.id(), run, snapshot, "PURCHASE", "UNTESTED", null);
        UUID manifest = jdbc.sql("""
                INSERT INTO agent_commerce_manifest(
                    merchant_id,agentization_run_id,manifest_version,policy_snapshot_id,catalogue_version,
                    publication_actor_id,publication_component,manifest_hash)
                VALUES(:merchant,:run,1,:snapshot,:catalogue,:actor,
                    'DETERMINISTIC_READINESS_REDUCER',:hash) RETURNING manifest_id
                """).param("merchant", merchant.id()).param("run", run).param("snapshot", snapshot)
                .param("catalogue", "v" + version.version() + ":" + version.contentHash())
                .param("actor", admin.id()).param("hash", hash("manifest-" + merchant.id()))
                .query(UUID.class).single();
        manifestCapability(manifest, merchant.id(), "SEARCH_PRODUCTS", "READY", null, searchEval);
        manifestCapability(manifest, merchant.id(), "GET_QUOTE", "READY", quote, quoteEval);
        manifestCapability(manifest, merchant.id(), "GET_AVAILABILITY", "READY", availability, availabilityEval);
        manifestCapability(manifest, merchant.id(), "PURCHASE", "UNTESTED", null, purchaseEval);
        return new Published(quote, availability, snapshot);
    }

    private UUID mapping(UUID merchant, UUID run, UUID artifact, UUID endpoint,
            String capability, String path, String request, String response) {
        return jdbc.sql("""
                INSERT INTO capability_mapping_proposal(
                    merchant_id,run_id,capability,mapping_version,source_artifact_id,endpoint_id,
                    source_operation_id,http_method,path_template,request_bindings,response_bindings,
                    transformations,amount_interpretation,currency_interpretation,model_provider,
                    model_name,proposal_status,validation_status)
                VALUES(:merchant,:run,:capability,1,:artifact,:endpoint,:operation,'POST',:path,
                    CAST(:request AS jsonb),CAST(:response AS jsonb),'{}','{"unit":"minor"}',
                    '{"field":""}','TEST','fixture','AWAITING_APPROVAL','VALID')
                RETURNING mapping_proposal_id
                """).param("merchant", merchant).param("run", run).param("capability", capability)
                .param("artifact", artifact).param("endpoint", endpoint).param("operation", capability.toLowerCase())
                .param("path", path).param("request", request).param("response", response)
                .query(UUID.class).single();
    }

    private UUID readiness(UUID merchant, UUID run, UUID snapshot, String capability,
            String value, UUID mapping) {
        return jdbc.sql("""
                INSERT INTO capability_readiness_evaluation(
                    merchant_id,agentization_run_id,capability,readiness,mapping_proposal_id,
                    mapping_version,mapping_content_hash,policy_snapshot_id,required_evidence,
                    satisfied_evidence,missing_requirements,blocking_evidence,evidence_references,
                    evaluation_hash)
                VALUES(:merchant,:run,:capability,:readiness,:mapping,:version,:mappingHash,:snapshot,
                    '[]','[]','[]','[]','[]',:hash) RETURNING readiness_evaluation_id
                """).param("merchant", merchant).param("run", run).param("capability", capability)
                .param("readiness", value).param("mapping", mapping)
                .param("version", mapping == null ? null : 1)
                .param("mappingHash", mapping == null ? null : hash("mapping-" + mapping))
                .param("snapshot", snapshot).param("hash", hash("readiness-" + capability + merchant))
                .query(UUID.class).single();
    }

    private void manifestCapability(UUID manifest, UUID merchant, String capability,
            String readiness, UUID mapping, UUID evaluation) {
        jdbc.sql("""
                INSERT INTO agent_commerce_manifest_capability(
                    manifest_id,merchant_id,capability,advertised,readiness,
                    executable_mapping_proposal_id,readiness_evaluation_id)
                VALUES(:manifest,:merchant,:capability,:advertised,:readiness,:mapping,:evaluation)
                """).param("manifest", manifest).param("merchant", merchant).param("capability", capability)
                .param("advertised", readiness.equals("READY")).param("readiness", readiness)
                .param("mapping", mapping).param("evaluation", evaluation).update();
    }

    private static RiskInput riskInput(long amount, EvidenceOutcome constraints, boolean safetyUnknown,
            EvidenceOutcome stock, EvidenceOutcome serviceability, boolean ambiguous,
            boolean substitution) {
        return new RiskInput(ActionType.PURCHASE, amount, true, false, constraints, safetyUnknown,
                EvidenceOutcome.PASS, stock, serviceability, true, true, ambiguous, substitution, true);
    }

    private static ProposalDraft draft(TransactionProposal proposal, UUID merchant, int policyVersion,
            UUID quote, long finalAmount, List<ProposalLineItem> lines) {
        return new ProposalDraft(proposal.buyerActorId(), proposal.threadId(), merchant,
                proposal.authorityRefreshId(), proposal.authorityRefreshHash(), proposal.intentId(),
                proposal.intentVersion(), proposal.intentHash(), proposal.cartId(), proposal.cartVersion(),
                proposal.cartHash(), proposal.constraintCertificateId(), proposal.constraintCertificateHash(),
                quote, proposal.quoteHash(), proposal.merchantQuoteId(), proposal.merchantQuoteVersion(),
                proposal.availabilityRefreshId(), proposal.availabilityHash(),
                proposal.serviceabilityEvidenceId(), proposal.serviceabilityHash(),
                proposal.policySnapshotId(), policyVersion, proposal.policySnapshotHash(),
                proposal.catalogueVersionId(), proposal.actionType(), proposal.subtotalMinor(),
                proposal.taxMinor(), proposal.feesMinor(), proposal.deliveryMinor(), finalAmount,
                proposal.currency(), proposal.quoteExpiresAt(), proposal.proposalExpiresAt(), lines);
    }

    private void disable(String table, String trigger) {
        jdbc.sql("ALTER TABLE " + table + " DISABLE TRIGGER " + trigger).update();
    }
    private void enable(String table, String trigger) {
        jdbc.sql("ALTER TABLE " + table + " ENABLE TRIGGER " + trigger).update();
    }

    private void login(HttpClient client, ApplicationActor actor) throws Exception {
        HttpResponse<String> response = post(client, "/api/auth/login", csrf(client),
                mapper.writeValueAsString(Map.of("identityHandle", actor.identityHandle(),
                        "password", "task009-password")));
        assertThat(response.statusCode()).isEqualTo(200);
    }
    private String csrf(HttpClient client) throws Exception {
        HttpResponse<String> response = get(client, "/api/auth/csrf");
        assertThat(response.statusCode()).isEqualTo(200);
        return mapper.readTree(response.body()).path("token").asText();
    }
    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> post(HttpClient client, String path, String csrf, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        if (csrf != null) request.header("X-CSRF-TOKEN", csrf);
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static UUID product(List<Product> products, String sku) {
        return products.stream().filter(value -> value.merchantSku().equals(sku))
                .findFirst().orElseThrow().id();
    }
    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
    private static String canonicalText() {
        return "500 ke andar do logon ke liye high-protein vegetarian snacks order karo, peanuts bilkul nahi.";
    }
    private static String cataloguePayload() {
        return """
                {"products":[
                  {"merchantSku":"SAFE-CHANA","gtin":"8901234500001","brand":"Safe Foods","canonicalName":"High Protein Roasted Chana","variant":"Masala","sizeStorage":"200 g","category":"Snacks","description":"vegetarian high protein snack","priceMinor":9900,"currency":"INR","stockQuantity":20,"availability":"IN_STOCK"},
                  {"merchantSku":"PEANUT-BAR","gtin":"8901234500002","brand":"Safe Foods","canonicalName":"Peanut Protein Bar","variant":"Crunch","sizeStorage":"60 g","category":"Snacks","description":"high protein peanut snack","priceMinor":8000,"currency":"INR","stockQuantity":20,"availability":"IN_STOCK"}
                ]}
                """;
    }

    record Fixture(Merchant merchant, ApplicationActor admin, ApplicationActor buyer,
            UUID catalogueVersion, UUID safeProduct, UUID quoteMapping,
            UUID availabilityMapping, UUID policySnapshot) {}
    record Published(UUID quoteMapping, UUID availabilityMapping, UUID policySnapshot) {}
    record Prepared(Fixture fixture, CommerceThread thread, AuthorityRefresh refresh,
            TransactionProposal proposal, String session) {}

    @TestConfiguration
    static class Fakes {
        @Bean @Primary
        BuyerIntentCompiler buyerIntentCompiler() {
            return (message, feedback) -> {
                EvidenceSpan span = new EvidenceSpan(message.messageId(), 0, message.normalizedText().length());
                return new CompiledIntent(IntentGoal.PURCHASE_FOOD, "Snacks", 50_000L, "INR",
                        null, null, null, true, "PEANUT", null, 2, SubstitutionPolicy.UNKNOWN,
                        "trusted-demo-location", List.of("HIGH_PROTEIN"), List.of(
                        new MaterialField("GOAL", ConstraintClassification.HARD, span, BigDecimal.ONE, AmbiguityState.CLEAR),
                        new MaterialField("BUDGET", ConstraintClassification.HARD, span, BigDecimal.ONE, AmbiguityState.CLEAR),
                        new MaterialField("VEGETARIAN", ConstraintClassification.HARD, span, BigDecimal.ONE, AmbiguityState.CLEAR),
                        new MaterialField("ALLERGEN", ConstraintClassification.HARD_SAFETY, span, BigDecimal.ONE, AmbiguityState.CLEAR),
                        new MaterialField("PREFERENCES", ConstraintClassification.SOFT, span, BigDecimal.ONE, AmbiguityState.CLEAR),
                        new MaterialField("PEOPLE", ConstraintClassification.HARD, span, BigDecimal.ONE, AmbiguityState.CLEAR)),
                        AmbiguityState.CLEAR, null, "FAKE", "intent-v1");
            };
        }

        @Bean @Primary
        CatalogueProvider catalogueProvider() {
            return barcode -> Optional.of(new CatalogueProvider.ExternalProduct(barcode, barcode,
                    "Safe Foods", barcode.endsWith("1") ? "High Protein Roasted Chana" : "Peanut Protein Bar",
                    barcode.endsWith("1") ? "Masala" : "Crunch", barcode.endsWith("1") ? "200 g" : "60 g",
                    List.of("captured ingredient"), barcode.endsWith("1") ? List.of() : List.of("peanut"),
                    true, 20.0, "https://images.example.test/" + barcode, "OFF-test-v1",
                    Instant.now().minus(1, ChronoUnit.DAYS)));
        }

        @Bean @Primary
        EmbeddingProvider embeddingProvider() {
            return input -> {
                List<Float> vector = new ArrayList<>(Collections.nCopies(768, 0f));
                vector.set(Math.floorMod(input.hashCode(), 32), 1f);
                return List.copyOf(vector);
            };
        }

        @Bean @Primary
        MerchantDnsResolver merchantDnsResolver() {
            return host -> List.of(InetAddress.getByName("93.184.216.34"));
        }

        @Bean @Primary
        Task009Transport merchantTransport(ObjectMapper mapper) {
            return new Task009Transport(mapper);
        }

        @Bean @Primary
        TrustedServiceability trustedServiceability(ObjectMapper mapper) {
            return new TrustedServiceability(mapper);
        }

        @Bean @Primary
        MerchantCustomerLinkProvider merchantCustomerLinkProvider(){
            return (merchant,username,password)->new MerchantCustomerLinkProvider.LinkResult(true,
                    "customer_"+merchant.toString().substring(0,8),"credential_"+merchant.toString().substring(0,8),
                    "TRUSTED_DEMO",Instant.now().plus(30,ChronoUnit.DAYS),null);
        }
    }

    static class Task009Transport implements MerchantTransport {
        final ObjectMapper mapper;
        final AtomicInteger quoteCalls = new AtomicInteger();
        final AtomicInteger availabilityCalls = new AtomicInteger();
        volatile String availabilityMode = "PASS";
        volatile long finalAmount = 36_000;
        volatile long subtotal = 34_000;
        volatile long tax = 0;
        volatile long delivery = 2_000;
        volatile long fees = 0;
        volatile long quoteExpirySeconds = 600;

        Task009Transport(ObjectMapper mapper) { this.mapper = mapper; }

        void reset() {
            quoteCalls.set(0);
            availabilityCalls.set(0);
            availabilityMode = "PASS";
            finalAmount = 36_000;
            subtotal = 34_000;
            tax = 0;
            delivery = 2_000;
            fees = 0;
            quoteExpirySeconds = 600;
        }

        @Override
        public MerchantTransportResponse execute(
                ValidatedEndpointResolution resolution, MerchantTransportRequest request) {
            JsonNode input = mapper.readTree(request.jsonBody());
            if (request.uri().getPath().endsWith("/quotes")) {
                int call = quoteCalls.incrementAndGet();
                var output = mapper.createObjectNode();
                output.put("quoteId", "quote-" + input.path("cartId").asText() + "-" + call);
                output.put("quoteVersion", "v" + call);
                output.put("cartId", input.path("cartId").asText());
                output.put("subtotalMinor", subtotal);
                output.put("taxMinor", tax);
                output.put("deliveryMinor", delivery);
                output.put("feesMinor", fees);
                output.put("finalAmountMinor", finalAmount);
                output.put("currency", "INR");
                output.put("expiresAt", Instant.now().plusSeconds(quoteExpirySeconds).toString());
                output.put("stockGuaranteed", true);
                output.put("priceGuaranteed", true);
                var lines = output.putArray("lineItems");
                for (JsonNode row : input.path("lineItems")) {
                    var line = lines.addObject();
                    line.put("merchantSku", row.path("merchantSku").asText());
                    line.put("quantity", row.path("quantity").asInt());
                    line.put("unitAmountMinor", subtotal / row.path("quantity").asInt());
                    line.put("lineAmountMinor", subtotal);
                }
                return json(output);
            }
            availabilityCalls.incrementAndGet();
            var output = mapper.createObjectNode();
            output.put("merchantId", input.path("merchantId").asText());
            output.put("productId", input.path("productId").asText());
            output.put("merchantSku", input.path("merchantSku").asText());
            output.set("variant", input.path("variant"));
            output.put("requestedQuantity", input.path("requestedQuantity").asInt());
            output.put("observedAt", Instant.now().toString());
            output.put("expiresAt", Instant.now().plusSeconds(120).toString());
            if (availabilityMode.equals("PASS")) output.put("availableQuantity", 8);
            else if (availabilityMode.equals("FAIL")) output.put("availableQuantity", 0);
            return json(output);
        }

        private MerchantTransportResponse json(JsonNode value) {
            return new MerchantTransportResponse(200, "application/json", mapper.writeValueAsBytes(value));
        }
    }

    static class TrustedServiceability implements ServiceabilityProvider {
        final ObjectMapper mapper;
        volatile EvidenceOutcome outcome = EvidenceOutcome.PASS;
        TrustedServiceability(ObjectMapper mapper) { this.mapper = mapper; }
        void reset() { outcome = EvidenceOutcome.PASS; }

        @Override
        public ServiceabilityResult evaluate(ServiceabilityRequest request) {
            Instant now = Instant.now();
            return new ServiceabilityResult(outcome,
                    outcome == EvidenceOutcome.UNKNOWN ? ServiceabilitySource.UNRESOLVED
                            : ServiceabilitySource.TRUSTED_DEMO_FIXTURE,
                    "trusted-demo-serviceability-v1", "trusted-demo-location",
                    outcome == EvidenceOutcome.PASS ? "TRUSTED_DEMO_SERVICEABLE"
                            : outcome == EvidenceOutcome.FAIL ? "TRUSTED_DEMO_NOT_SERVICEABLE"
                            : "TRUSTED_DEMO_SERVICEABILITY_UNKNOWN",
                    now, outcome == EvidenceOutcome.UNKNOWN ? null : now.plusSeconds(300),
                    mapper.createObjectNode().put("fixture", "trusted-demo-v1"));
        }
    }
}
