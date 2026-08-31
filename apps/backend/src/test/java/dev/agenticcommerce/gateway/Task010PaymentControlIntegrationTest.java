package dev.agenticcommerce.gateway;

import static dev.agenticcommerce.gateway.catalogue.CatalogueModels.*;
import static dev.agenticcommerce.gateway.commerce.TransactionModels.*;
import static dev.agenticcommerce.gateway.intent.BuyerModels.*;
import static dev.agenticcommerce.gateway.payment.PaymentModels.*;
import static org.assertj.core.api.Assertions.*;

import dev.agenticcommerce.gateway.agentization.authority.MerchantAuthorityService;
import dev.agenticcommerce.gateway.agentization.persistence.CapabilityMappingProposalRepository;
import dev.agenticcommerce.gateway.authorization.AuthorizationService;
import dev.agenticcommerce.gateway.authorization.ExecutionGate;
import dev.agenticcommerce.gateway.catalogue.*;
import dev.agenticcommerce.gateway.commerce.*;
import dev.agenticcommerce.gateway.identity.model.*;
import dev.agenticcommerce.gateway.identity.persistence.*;
import dev.agenticcommerce.gateway.intent.*;
import dev.agenticcommerce.gateway.payment.*;
import dev.agenticcommerce.gateway.risk.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "payment.outbox.poll-delay-ms=3600000")
@Testcontainers
@Import({Task009TransactionAuthorityIntegrationTest.Fakes.class, Task010PaymentControlIntegrationTest.PaymentFakes.class})
class Task010PaymentControlIntegrationTest {
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
    @Autowired AuthoritativeRefreshService refreshes;
    @Autowired TransactionProposalService proposals;
    @Autowired ReversibilityService risks;
    @Autowired AuthorizationService authorizations;
    @Autowired ExecutionGate gate;
    @Autowired PaymentControlService payments;
    @Autowired PaymentRepository paymentRepository;
    @Autowired PaymentEvidenceReducer reducer;
    @Autowired RazorpayWebhookService webhooks;
    @Autowired PaymentOutboxWorker worker;
    @Autowired CapabilityMappingProposalRepository mappings;
    @Autowired MerchantAuthorityService merchantAuthority;
    @Autowired ObjectMapper mapper;
    @Autowired TestPaymentProvider provider;
    @Autowired TestMerchantGateway merchantGateway;

    @BeforeEach
    void clear() {
        provider.reset();
        merchantGateway.reset();
        jdbc.sql("TRUNCATE TABLE merchant,application_actor CASCADE").update();
    }

    @Test
    void v010CreatesPaymentEvidenceOutboxAndUniquenessSchema() {
        assertThat(jdbc.sql("SELECT count(*)::int FROM flyway_schema_history WHERE version='010' AND success")
                .query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("""
                SELECT count(*)::int FROM information_schema.tables WHERE table_name IN (
                  'merchant_payment_configuration','payment_control','provider_order_creation_attempt',
                  'payment_provider_order','payment_callback_evidence','provider_webhook_event',
                  'provider_payment_evidence','provider_order_evidence','payment_reduction_evidence',
                  'payment_reconciliation','transactional_outbox','merchant_finalization',
                  'merchant_finalization_attempt')
                """).query(Integer.class).single()).isEqualTo(13);
        assertThat(jdbc.sql("""
                SELECT count(*)::int FROM pg_constraint WHERE conrelid='payment_provider_order'::regclass
                    AND contype='u' AND pg_get_constraintdef(oid) LIKE '%(execution_id)%'
                """).query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("SELECT to_regclass('razorpay_order') IS NULL AND to_regclass('payment') IS NULL")
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void providerOrderIsExactlyOnceStableAndCheckoutContainsNoSecret() {
        Ready ready = ready("order");
        PaymentStateView first = payments.initiate(ready.buyerId(), ready.threadId(), ready.proposalId());
        PaymentStateView second = payments.initiate(ready.buyerId(), ready.threadId(), ready.proposalId());
        assertThat(first.paymentState()).isEqualTo(PaymentState.PAYMENT_PENDING);
        assertThat(second.providerOrderId()).isEqualTo(first.providerOrderId());
        assertThat(provider.createCalls).hasValue(1);
        assertThat(jdbc.sql("SELECT count(*)::int FROM payment_provider_order WHERE execution_id=:id")
                .param("id", ready.executionId()).query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("SELECT receipt FROM payment_provider_order WHERE execution_id=:id")
                .param("id", ready.executionId()).query(String.class).single())
                .isEqualTo(PaymentControlService.stableReceipt(ready.executionId()));
        CheckoutInitialization checkout = payments.checkout(ready.buyerId(), ready.threadId(), ready.proposalId());
        assertThat(checkout.publicKeyId()).isEqualTo("rzp_test_public");
        assertThat(checkout.amountMinor()).isEqualTo(36_000);
        assertThat(mapper.writeValueAsString(checkout)).doesNotContain("secret", "webhook");
    }

    @Test
    void concurrentProviderOrderInitiationConvergesOnOneOrder() throws Exception {
        Ready ready = ready("concurrent-order");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            Callable<PaymentStateView> initiate = () -> {
                start.await();
                return payments.initiate(ready.buyerId(), ready.threadId(), ready.proposalId());
            };
            Future<PaymentStateView> first = pool.submit(initiate);
            Future<PaymentStateView> second = pool.submit(initiate);
            start.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS).providerOrderId())
                    .isEqualTo(second.get(15, TimeUnit.SECONDS).providerOrderId());
            assertThat(provider.createCalls).hasValue(1);
            assertThat(jdbc.sql("SELECT count(*)::int FROM payment_provider_order WHERE execution_id=:id")
                    .param("id", ready.executionId()).query(Integer.class).single()).isOne();
        } finally { pool.shutdownNow(); }
    }

    @Test
    void definitiveOrderRejectionIsDurableAndRepeatInitiationIsIdempotent() {
        Ready ready = ready("rejected-order");
        provider.rejectCreate = true;
        assertThat(payments.initiate(ready.buyerId(), ready.threadId(), ready.proposalId()).paymentState())
                .isEqualTo(PaymentState.PAYMENT_FAILED);
        assertThat(payments.initiate(ready.buyerId(), ready.threadId(), ready.proposalId()).paymentState())
                .isEqualTo(PaymentState.PAYMENT_FAILED);
        assertThat(provider.createCalls).hasValue(1);
    }

    @Test
    void lostCreateResponseIsReconciledByStableReceiptWithoutBlindRecreation() {
        Ready ready = ready("lost");
        provider.loseCreateResponse = true;
        PaymentStateView uncertain = payments.initiate(ready.buyerId(), ready.threadId(), ready.proposalId());
        assertThat(uncertain.paymentState()).isEqualTo(PaymentState.PAYMENT_UNCERTAIN);
        assertThat(provider.createCalls).hasValue(1);
        provider.loseCreateResponse = false;
        PaymentStateView recovered = payments.initiate(ready.buyerId(), ready.threadId(), ready.proposalId());
        assertThat(recovered.paymentState()).isEqualTo(PaymentState.PAYMENT_PENDING);
        assertThat(recovered.providerOrderId()).isNotBlank();
        assertThat(provider.createCalls).hasValue(1);
        assertThat(jdbc.sql("SELECT count(*)::int FROM provider_order_creation_attempt WHERE execution_id=:id")
                .param("id", ready.executionId()).query(Integer.class).single()).isOne();
    }

    @Test
    void unknownOrderRecoveryConsumesBoundedReconciliationBudget() {
        Ready ready = ready("reconcile-budget");
        provider.loseCreateResponse = true;
        payments.initiate(ready.buyerId(), ready.threadId(), ready.proposalId());
        provider.receipts.clear();
        provider.findFails = true;
        for (int attempt = 1; attempt <= 5; attempt++) {
            jdbc.sql("""
                    UPDATE payment_reconciliation
                    SET next_attempt_at=TIMESTAMPTZ '2000-01-01 00:00:00+00'
                    WHERE execution_id=:execution
                    """).param("execution", ready.executionId()).update();
            ReconciliationResult result = payments.reconcile(
                    ready.buyerId(), ready.threadId(), ready.proposalId());
            assertThat(result.attemptCount()).isEqualTo(attempt);
        }
        assertThat(payments.state(ready.buyerId(), ready.threadId(), ready.proposalId())
                .reconciliationAttempts()).isEqualTo(5);
        assertThatThrownBy(() -> payments.reconcile(
                ready.buyerId(), ready.threadId(), ready.proposalId()))
                .isInstanceOf(PaymentControlException.class)
                .hasMessageContaining("manual review");
    }

    @Test
    void callbackSignatureIsRequiredAndValidCallbackRemainsEvidenceOnly() {
        Ready ready = ready("callback");
        PaymentStateView state = payments.initiate(ready.buyerId(), ready.threadId(), ready.proposalId());
        assertThatThrownBy(() -> payments.callback(ready.buyerId(), ready.threadId(), ready.proposalId(),
                new CallbackSubmission("pay_callback", state.providerOrderId(), "b".repeat(64))))
                .isInstanceOf(PaymentControlException.class)
                .hasMessageContaining("signature");
        CallbackResult accepted = payments.callback(ready.buyerId(), ready.threadId(), ready.proposalId(),
                new CallbackSubmission("pay_callback", state.providerOrderId(), "a".repeat(64)));
        assertThat(accepted.accepted()).isTrue();
        assertThat(accepted.financialConfirmation()).isFalse();
        assertThat(payments.state(ready.buyerId(), ready.threadId(), ready.proposalId()).paymentState())
                .isEqualTo(PaymentState.PAYMENT_UNCERTAIN);
        assertThat(jdbc.sql("SELECT count(*)::int FROM payment_callback_evidence")
                .query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*)::int FROM transactional_outbox")
                .query(Integer.class).single()).isZero();
    }

    @Test
    void reconciliationRequiresCapturedPaymentPaidOrderAndAtomicallyCreatesOutbox() {
        Ready ready = ready("reconcile");
        PaymentStateView pending = payments.initiate(ready.buyerId(), ready.threadId(), ready.proposalId());
        payments.callback(ready.buyerId(), ready.threadId(), ready.proposalId(),
                new CallbackSubmission("pay_reconcile", pending.providerOrderId(), "a".repeat(64)));
        provider.paymentId = "pay_reconcile";
        provider.paymentStatus = "captured";
        provider.paymentCaptured = true;
        provider.orderStatus = "paid";
        provider.orderAmountPaid = 36_000;
        ReconciliationResult result = payments.reconcile(ready.buyerId(), ready.threadId(), ready.proposalId());
        assertThat(result.state().paymentState()).isEqualTo(PaymentState.PAYMENT_CONFIRMED);
        assertThat(result.reconciliationStatus()).isEqualTo("COMPLETED");
        assertThat(jdbc.sql("SELECT count(*)::int FROM transactional_outbox WHERE execution_id=:id")
                .param("id", ready.executionId()).query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("SELECT state FROM merchant_finalization WHERE execution_id=:id")
                .param("id", ready.executionId()).query(String.class).single()).isEqualTo("PENDING");
        assertThat(jdbc.sql("SELECT confirmed_payment_id FROM payment_control WHERE execution_id=:id")
                .param("id", ready.executionId()).query(String.class).single()).isEqualTo("pay_reconcile");
    }

    @Test
    void mismatchedFinancialEvidenceFailsClosed() {
        Ready ready = ready("mismatch");
        PaymentStateView pending = payments.initiate(ready.buyerId(), ready.threadId(), ready.proposalId());
        PaymentControl control = paymentRepository.controlForExecution(ready.executionId()).orElseThrow();
        var payment = provider.payment("pay_wrong", pending.providerOrderId(), 35_999, "INR", "captured", true);
        var order = provider.order(pending.providerOrderId(), 36_000, 36_000, "INR", "paid",
                PaymentControlService.stableReceipt(ready.executionId()));
        paymentRepository.savePaymentEvidence(control, payment, EvidenceSource.API_RECONCILIATION, "wrong-pay", Instant.now());
        paymentRepository.saveOrderEvidence(control, order, EvidenceSource.API_RECONCILIATION, "right-order", Instant.now());
        assertThat(reducer.reduce(control.id())).isEqualTo(PaymentState.PAYMENT_UNCERTAIN);
        assertThat(jdbc.sql("SELECT count(*)::int FROM transactional_outbox").query(Integer.class).single()).isZero();
        assertThat(payments.state(ready.buyerId(), ready.threadId(), ready.proposalId()).reasonCode())
                .isEqualTo("AUTHORITATIVE_EVIDENCE_INCOMPLETE");
    }

    @Test
    void webhookIsRawVerifiedIdempotentAndOrderIndependent() {
        Ready ready = ready("webhook");
        PaymentStateView pending = payments.initiate(ready.buyerId(), ready.threadId(), ready.proposalId());
        String paymentEvent = webhook("evt_payment", "payment.captured", pending.providerOrderId(), true, false);
        assertThatThrownBy(() -> webhooks.ingest(paymentEvent.getBytes(), "invalid", "evt_payment"))
                .isInstanceOf(PaymentControlException.class);
        WebhookResult first = webhooks.ingest(paymentEvent.getBytes(), "valid", "evt_payment");
        WebhookResult duplicate = webhooks.ingest(paymentEvent.getBytes(), "valid", "evt_payment");
        assertThat(first.processingStatus()).isEqualTo("PROCESSED");
        assertThat(duplicate.duplicate()).isTrue();
        assertThat(payments.state(ready.buyerId(), ready.threadId(), ready.proposalId()).paymentState())
                .isEqualTo(PaymentState.PAYMENT_UNCERTAIN);
        String orderEvent = webhook("evt_order", "order.paid", pending.providerOrderId(), false, true);
        webhooks.ingest(orderEvent.getBytes(), "valid", "evt_order");
        assertThat(payments.state(ready.buyerId(), ready.threadId(), ready.proposalId()).paymentState())
                .isEqualTo(PaymentState.PAYMENT_CONFIRMED);
        assertThat(jdbc.sql("SELECT count(*)::int FROM provider_webhook_event").query(Integer.class).single())
                .isEqualTo(2);
    }

    @Test
    void capturedPaymentMerchantTimeoutRetriesSameOperationAndEventuallyFulfills() {
        Ready ready = confirmed("graceful");
        merchantGateway.failFirst = true;
        worker.dispatch();
        FulfillmentView failed = payments.fulfillment(ready.buyerId(), ready.threadId(), ready.proposalId());
        assertThat(failed.paymentState()).isEqualTo(PaymentState.PAYMENT_CONFIRMED);
        assertThat(failed.fulfillmentState()).isEqualTo(FulfillmentState.RETRYABLE_FAILURE);
        String operation = failed.merchantOperationId();
        assertThat(provider.createCalls).hasValue(1);
        assertThat(jdbc.sql("SELECT status FROM transactional_outbox WHERE execution_id=:id")
                .param("id", ready.executionId()).query(String.class).single()).isEqualTo("FAILED_RETRYABLE");
        jdbc.sql("""
                UPDATE transactional_outbox SET next_attempt_at=TIMESTAMPTZ '2000-01-01 00:00:00+00'
                WHERE execution_id=:id
                """)
                .param("id", ready.executionId()).update();
        worker.dispatch();
        FulfillmentView fulfilled = payments.fulfillment(ready.buyerId(), ready.threadId(), ready.proposalId());
        assertThat(fulfilled.fulfillmentState()).isEqualTo(FulfillmentState.FULFILLED);
        assertThat(fulfilled.merchantOperationId()).isEqualTo(operation);
        assertThat(merchantGateway.operations).containsExactly(operation, operation);
        assertThat(provider.createCalls).hasValue(1);
        assertThat(jdbc.sql("SELECT status FROM transactional_outbox WHERE execution_id=:id")
                .param("id", ready.executionId()).query(String.class).single()).isEqualTo("COMPLETED");
    }

    @Test
    void skipLockedClaimingPreventsConcurrentDuplicateWork() throws Exception {
        Ready ready = confirmed("claim");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            Callable<List<PaymentRepository.OutboxItem>> claim = () -> {
                start.await();
                Instant now = Instant.now();
                return paymentRepository.claimOutbox(10, now, now.plusSeconds(60));
            };
            Future<List<PaymentRepository.OutboxItem>> one = pool.submit(claim);
            Future<List<PaymentRepository.OutboxItem>> two = pool.submit(claim);
            start.countDown();
            List<PaymentRepository.OutboxItem> combined = new ArrayList<>();
            combined.addAll(one.get(10, TimeUnit.SECONDS));
            combined.addAll(two.get(10, TimeUnit.SECONDS));
            assertThat(combined).singleElement().extracting(PaymentRepository.OutboxItem::executionId)
                    .isEqualTo(ready.executionId());
        } finally { pool.shutdownNow(); }
    }

    @Test
    void purchaseReadinessRemainsHonestlyUntested() {
        Ready ready = ready("readiness");
        payments.initiate(ready.buyerId(), ready.threadId(), ready.proposalId());
        assertThat(jdbc.sql("""
                SELECT readiness FROM agent_commerce_manifest_capability
                WHERE merchant_id=:merchant AND capability='PURCHASE'
                """).param("merchant", ready.merchantId()).query(String.class).single()).isEqualTo("UNTESTED");
    }

    private Ready confirmed(String key) {
        Ready ready = ready(key);
        PaymentStateView pending = payments.initiate(ready.buyerId(), ready.threadId(), ready.proposalId());
        payments.callback(ready.buyerId(), ready.threadId(), ready.proposalId(),
                new CallbackSubmission("pay_" + key, pending.providerOrderId(), "a".repeat(64)));
        provider.paymentId = "pay_" + key;
        provider.paymentStatus = "captured";
        provider.paymentCaptured = true;
        provider.orderStatus = "paid";
        provider.orderAmountPaid = 36_000;
        payments.reconcile(ready.buyerId(), ready.threadId(), ready.proposalId());
        return ready;
    }

    private Ready ready(String key) {
        Fixture fixture = fixture(key);
        CommerceThread thread = threads.create(fixture.buyer().id(), canonicalText());
        for (int step = 0; step < 6; step++) buyer.advance(fixture.buyer().id(), thread.threadId());
        refreshes.refresh(fixture.buyer().id(), thread.threadId());
        TransactionProposal proposal = proposals.create(fixture.buyer().id(), thread.threadId());
        String session = authorizations.bindSession(key + "-session");
        risks.evaluate(fixture.buyer().id(), proposal.proposalId(), session);
        authorizations.confirm(fixture.buyer().id(), proposal.proposalId(), session);
        TransactionExecution execution = gate.reserve(fixture.buyer().id(), proposal.proposalId(), session).execution();
        jdbc.sql("""
                INSERT INTO merchant_payment_configuration(
                    merchant_id,provider,environment,configuration_reference,provider_account_reference)
                VALUES(:merchant,'RAZORPAY','TEST','razorpay-test-default','acct_test')
                """).param("merchant", fixture.merchant().id()).update();
        merchantGateway.mappingId = fixture.placeOrderMapping();
        return new Ready(fixture.merchant().id(), fixture.buyer().id(), thread.threadId(),
                proposal.proposalId(), execution.executionId());
    }

    private Fixture fixture(String key) {
        Merchant merchant = merchants.create("payment-" + key, "Payment " + key);
        ApplicationActor admin = actors.create(key + "-payment-admin@test", PlatformRole.MERCHANT_ADMIN);
        memberships.create(merchant.id(), admin.id());
        ApplicationActor buyerActor = actors.create(key + "-payment-buyer@test", PlatformRole.BUYER);
        CatalogueVersion version = catalogues.ingest(admin.id(), merchant.id(), "JSON", cataloguePayload()).version();
        List<Product> products = catalogueRepository.products(merchant.id(), version.id(), 20);
        UUID safe = products.stream().filter(value -> value.merchantSku().equals("SAFE-CHANA"))
                .findFirst().orElseThrow().id();
        UUID resolution = jdbc.sql("""
                SELECT identity_resolution_id FROM product_identity_resolution
                WHERE product_id=:product AND external_source='MERCHANT'
                """).param("product", safe).query(UUID.class).single();
        catalogueRepository.insertFact(merchant.id(), version.id(), safe, resolution, "ALLERGEN",
                mapper.createObjectNode().put("allergen", "peanut").put("status", "ABSENT"),
                "MERCHANT", "safe-absence", "v1", "PRIMARY", "ACTIVE", Instant.now(),
                Instant.now().plus(30, ChronoUnit.DAYS), hash(key + "-allergen"));
        return publishReady(merchant, admin, buyerActor, version);
    }

    private Fixture publishReady(
            Merchant merchant, ApplicationActor admin, ApplicationActor buyerActor, CatalogueVersion version) {
        UUID endpoint = jdbc.sql("""
                INSERT INTO merchant_approved_endpoint(
                    merchant_id,base_uri,hostname,approved_at,dns_validated_at)
                VALUES(:merchant,'https://merchant.example.test','merchant.example.test',
                    CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING endpoint_id
                """).param("merchant", merchant.id()).query(UUID.class).single();
        UUID artifact = jdbc.sql("""
                INSERT INTO openapi_artifact(merchant_id,endpoint_id,artifact_type,artifact_version,content_hash,document)
                VALUES(:merchant,:endpoint,'OPENAPI','v1',:hash,'{}') RETURNING artifact_id
                """).param("merchant", merchant.id()).param("endpoint", endpoint)
                .param("hash", hash("artifact-" + merchant.id())).query(UUID.class).single();
        UUID run = jdbc.sql("""
                INSERT INTO agentization_run(
                    merchant_id,created_by_actor_id,source_artifact_id,target_capability,current_capability,
                    orchestration_state,max_step_budget,wall_clock_deadline)
                VALUES(:merchant,:actor,:artifact,'GET_QUOTE','GET_QUOTE','READY_CANDIDATE',20,
                    CURRENT_TIMESTAMP+interval '1 hour') RETURNING run_id
                """).param("merchant", merchant.id()).param("actor", admin.id()).param("artifact", artifact)
                .query(UUID.class).single();
        UUID quote = mapping(merchant.id(), run, artifact, endpoint, "GET_QUOTE", "/quotes",
                "{\"cartId\":\"body.cartId\"}",
                "{\"amount\":\"body.finalAmountMinor\",\"currency\":\"body.currency\",\"quoteId\":\"body.quoteId\"}");
        UUID availability = mapping(merchant.id(), run, artifact, endpoint, "GET_AVAILABILITY", "/products",
                "{\"merchantSku\":\"body.merchantSku\",\"requestedQuantity\":\"body.requestedQuantity\"}",
                "{\"availableQuantity\":\"body.availableQuantity\",\"available\":\"body.available\",\"observedAt\":\"body.observedAt\"}");
        UUID place = mapping(merchant.id(), run, artifact, endpoint, "PLACE_ORDER", "/orders",
                "{\"merchantOperationId\":\"body.merchantOperationId\"}", "{\"orderId\":\"body.orderId\"}");
        String mappingHash = merchantAuthority.mappingHash(mappings.findByMerchantAndId(merchant.id(), place).orElseThrow());
        jdbc.sql("""
                INSERT INTO mapping_approval_decision(
                    merchant_id,agentization_run_id,mapping_proposal_id,mapping_version,
                    mapping_content_hash,decision,approving_actor_id)
                VALUES(:merchant,:run,:mapping,1,:hash,'APPROVE',:actor)
                """).param("merchant", merchant.id()).param("run", run).param("mapping", place)
                .param("hash", mappingHash).param("actor", admin.id()).update();
        UUID snapshot = jdbc.sql("""
                INSERT INTO merchant_policy_snapshot(merchant_id,snapshot_version,snapshot_hash,published_by_actor_id)
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
        return new Fixture(merchant, admin, buyerActor, place);
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
                    satisfied_evidence,missing_requirements,blocking_evidence,evidence_references,evaluation_hash)
                VALUES(:merchant,:run,:capability,:readiness,:mapping,:version,:mappingHash,:snapshot,
                    '[]','[]','[]','[]','[]',:hash) RETURNING readiness_evaluation_id
                """).param("merchant", merchant).param("run", run).param("capability", capability)
                .param("readiness", value).param("mapping", mapping).param("version", mapping == null ? null : 1)
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

    private String webhook(String event, String type, String orderId, boolean payment, boolean order) {
        var root = mapper.createObjectNode();
        root.put("id", event); root.put("event", type); root.put("account_id", "acct_test");
        var payload = root.putObject("payload");
        if (payment) {
            var value = payload.putObject("payment").putObject("entity");
            value.put("id", "pay_webhook"); value.put("order_id", orderId); value.put("amount", 36_000);
            value.put("currency", "INR"); value.put("status", "captured"); value.put("captured", true);
            value.put("created_at", Instant.now().getEpochSecond());
        }
        if (order) {
            var value = payload.putObject("order").putObject("entity");
            value.put("id", orderId); value.put("amount", 36_000); value.put("amount_paid", 36_000);
            value.put("currency", "INR"); value.put("status", "paid");
            value.put("created_at", Instant.now().getEpochSecond());
        }
        return mapper.writeValueAsString(root);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
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

    record Fixture(Merchant merchant, ApplicationActor admin, ApplicationActor buyer, UUID placeOrderMapping) {}
    record Ready(UUID merchantId, UUID buyerId, UUID threadId, UUID proposalId, UUID executionId) {}

    @TestConfiguration
    static class PaymentFakes {
        @Bean @Primary TestPaymentProvider paymentProvider() { return new TestPaymentProvider(); }
        @Bean @Primary TestMerchantGateway merchantFinalizationGateway() { return new TestMerchantGateway(); }
    }

    static final class TestPaymentProvider implements PaymentProvider {
        final AtomicInteger createCalls = new AtomicInteger();
        final Map<String, ProviderOrder> receipts = new ConcurrentHashMap<>();
        volatile boolean loseCreateResponse;
        volatile boolean rejectCreate;
        volatile boolean findFails;
        volatile String paymentId = "pay_default";
        volatile String paymentStatus = "authorized";
        volatile boolean paymentCaptured;
        volatile String orderStatus = "created";
        volatile long orderAmountPaid;

        void reset() {
            createCalls.set(0); receipts.clear(); loseCreateResponse = false;
            rejectCreate = false; findFails = false;
            paymentId = "pay_default"; paymentStatus = "authorized"; paymentCaptured = false;
            orderStatus = "created"; orderAmountPaid = 0;
        }
        @Override public ProviderOrder createOrder(CreateOrderCommand command) {
            createCalls.incrementAndGet();
            if (rejectCreate) throw new PaymentProviderException(
                    PaymentProviderException.Category.DEFINITIVE_REJECTION, false, "rejected", null);
            ProviderOrder result = order("order_" + command.receipt().substring(4, 16),
                    command.amountMinor(), 0, command.currency(), "created", command.receipt());
            receipts.put(command.receipt(), result);
            if (loseCreateResponse) throw new PaymentProviderException(
                    PaymentProviderException.Category.TIMEOUT, true, "response lost", null);
            return result;
        }
        @Override public ProviderOrder fetchOrder(String id) {
            ProviderOrder original = receipts.values().stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow();
            return order(id, original.amountMinor(), orderAmountPaid, original.currency(), orderStatus, original.receipt());
        }
        @Override public ProviderPayment fetchPayment(String id) {
            ProviderOrder order = receipts.values().stream().findFirst().orElseThrow();
            return payment(id, order.id(), order.amountMinor(), order.currency(), paymentStatus, paymentCaptured);
        }
        @Override public Optional<ProviderOrder> findOrderByReceipt(String receipt) {
            if (findFails) throw new PaymentProviderException(
                    PaymentProviderException.Category.CONNECTION_FAILURE, false, "lookup failed", null);
            return Optional.ofNullable(receipts.get(receipt));
        }
        @Override public boolean verifyCheckoutSignature(String orderId, String paymentId, String signature) {
            return "a".repeat(64).equals(signature);
        }
        @Override public boolean verifyWebhookSignature(byte[] body, String signature) { return "valid".equals(signature); }
        @Override public boolean configured() { return true; }
        @Override public String publicKeyId() { return "rzp_test_public"; }
        @Override public String configurationReference() { return "razorpay-test-default"; }
        @Override public String providerAccountReference() { return "acct_test"; }
        ProviderOrder order(String id, long amount, long paid, String currency, String status, String receipt) {
            return new ProviderOrder(id, amount, paid, currency, receipt, status, Instant.now(), "acct_test",
                    hash(id + amount + paid + status));
        }
        ProviderPayment payment(String id, String order, long amount, String currency, String status, boolean captured) {
            return new ProviderPayment(id, order, amount, currency, status, captured, Instant.now(), "acct_test",
                    hash(id + order + amount + status + captured));
        }
    }

    static final class TestMerchantGateway implements MerchantFinalizationGateway {
        volatile UUID mappingId;
        volatile boolean failFirst;
        final List<String> operations = new CopyOnWriteArrayList<>();
        void reset() { mappingId = null; failFirst = false; operations.clear(); }
        @Override public Result placeOrder(UUID merchantId, tools.jackson.databind.JsonNode request) {
            String operation = request.path("merchantOperationId").asText();
            operations.add(operation);
            if (failFirst && operations.size() == 1)
                throw new MerchantFinalizationException("MERCHANT_TIMEOUT", true, "lost response");
            return new Result(mappingId, "merchant-order-" + operation.substring(4, 12), hash(operation));
        }
    }
}
