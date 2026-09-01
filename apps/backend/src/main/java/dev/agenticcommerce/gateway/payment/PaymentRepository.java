package dev.agenticcommerce.gateway.payment;

import static dev.agenticcommerce.gateway.payment.PaymentModels.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class PaymentRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public PaymentRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public record StartContext(
            UUID executionId, UUID proposalId, String proposalHash, UUID buyerId, UUID merchantId,
            long amountMinor, String currency, String executionStatus, String merchantDisplayName,
            PaymentConfiguration configuration) {}

    public Optional<StartContext> lockStartContext(UUID buyerId, UUID threadId, UUID proposalId) {
        return jdbc.sql("""
                SELECT execution.execution_id,execution.proposal_id,execution.proposal_hash,
                    execution.buyer_actor_id,execution.merchant_id,execution.status,
                    proposal.final_amount_minor,proposal.currency,merchant.display_name,
                    config.payment_configuration_id,config.configuration_reference,
                    config.provider_account_reference,config.active
                FROM transaction_execution execution
                JOIN transaction_proposal proposal ON proposal.proposal_id=execution.proposal_id
                JOIN merchant ON merchant.merchant_id=execution.merchant_id
                LEFT JOIN merchant_payment_configuration config
                    ON config.merchant_id=execution.merchant_id
                    AND config.provider='RAZORPAY' AND config.environment='TEST'
                WHERE execution.buyer_actor_id=:buyer AND execution.proposal_id=:proposal
                    AND proposal.thread_id=:thread
                FOR UPDATE OF execution
                """).param("buyer", buyerId).param("thread", threadId).param("proposal", proposalId)
                .query((rs, row) -> new StartContext(
                        rs.getObject("execution_id", UUID.class), rs.getObject("proposal_id", UUID.class),
                        rs.getString("proposal_hash").strip(), rs.getObject("buyer_actor_id", UUID.class),
                        rs.getObject("merchant_id", UUID.class), rs.getLong("final_amount_minor"),
                        rs.getString("currency"), rs.getString("status"), rs.getString("display_name"),
                        rs.getObject("payment_configuration_id") == null ? null : new PaymentConfiguration(
                                rs.getObject("payment_configuration_id", UUID.class),
                                rs.getObject("merchant_id", UUID.class), rs.getString("configuration_reference"),
                                rs.getString("provider_account_reference"), rs.getBoolean("active"))))
                .optional();
    }

    public Optional<PaymentConfiguration> configurationByReference(String reference) {
        return jdbc.sql("""
                SELECT * FROM merchant_payment_configuration
                WHERE configuration_reference=:reference AND provider='RAZORPAY'
                    AND environment='TEST' AND active=TRUE
                """).param("reference", reference).query(this::mapConfiguration).optional();
    }

    public Optional<PaymentConfiguration> configuration(UUID id) {
        return jdbc.sql("SELECT * FROM merchant_payment_configuration WHERE payment_configuration_id=:id")
                .param("id", id).query(this::mapConfiguration).optional();
    }

    public PaymentControl createControl(StartContext context, Instant now) {
        return jdbc.sql("""
                INSERT INTO payment_control(
                    execution_id,proposal_id,buyer_actor_id,merchant_id,payment_configuration_id,
                    state,expected_amount_minor,expected_currency,version,reason_code,created_at,updated_at)
                VALUES(:execution,:proposal,:buyer,:merchant,:configuration,'NOT_STARTED',:amount,
                    :currency,0,'PAYMENT_NOT_STARTED',:now,:now)
                ON CONFLICT (execution_id) DO UPDATE SET execution_id=EXCLUDED.execution_id
                RETURNING *
                """).param("execution", context.executionId()).param("proposal", context.proposalId())
                .param("buyer", context.buyerId()).param("merchant", context.merchantId())
                .param("configuration", context.configuration().id()).param("amount", context.amountMinor())
                .param("currency", context.currency()).param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .query(this::control).single();
    }

    public Optional<PaymentControl> controlForExecution(UUID executionId) {
        return jdbc.sql("SELECT * FROM payment_control WHERE execution_id=:execution")
                .param("execution", executionId).query(this::control).optional();
    }

    public Optional<PaymentControl> controlForBuyerProposal(UUID buyerId, UUID threadId, UUID proposalId) {
        return jdbc.sql("""
                SELECT control.* FROM payment_control control
                JOIN transaction_proposal proposal ON proposal.proposal_id=control.proposal_id
                WHERE control.buyer_actor_id=:buyer AND control.proposal_id=:proposal
                    AND proposal.thread_id=:thread
                """).param("buyer", buyerId).param("proposal", proposalId).param("thread", threadId)
                .query(this::control).optional();
    }

    public Optional<PaymentControl> lockControl(UUID id) {
        return jdbc.sql("SELECT * FROM payment_control WHERE payment_control_id=:id FOR UPDATE")
                .param("id", id).query(this::control).optional();
    }

    public Optional<ProviderOrderRecord> orderForExecution(UUID executionId) {
        return jdbc.sql("SELECT * FROM payment_provider_order WHERE execution_id=:execution")
                .param("execution", executionId).query(this::providerOrder).optional();
    }

    public UUID beginOrderAttempt(
            PaymentControl control, String receipt, String requestHash, Instant now) {
        return jdbc.sql("""
                INSERT INTO provider_order_creation_attempt(
                    payment_control_id,execution_id,merchant_id,stable_receipt,attempt_number,
                    outcome,request_hash,started_at)
                VALUES(:control,:execution,:merchant,:receipt,1,'IN_PROGRESS',:hash,:now)
                RETURNING order_attempt_id
                """).param("control", control.id()).param("execution", control.executionId())
                .param("merchant", control.merchantId()).param("receipt", receipt).param("hash", requestHash)
                .param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE).query(UUID.class).single();
    }

    public boolean hasUncertainOrderAttempt(UUID executionId) {
        return jdbc.sql("""
                SELECT count(*) FROM provider_order_creation_attempt
                WHERE execution_id=:execution AND outcome IN ('IN_PROGRESS','UNCERTAIN')
                """).param("execution", executionId).query(Long.class).single() > 0;
    }

    public void completeOrderAttempt(
            UUID attemptId, String outcome, String errorCategory, String providerOrderId,
            String responseHash, Instant now) {
        jdbc.sql("""
                UPDATE provider_order_creation_attempt SET outcome=:outcome,error_category=:error,
                    provider_order_id=:providerOrder,response_hash=:responseHash,completed_at=:now
                WHERE order_attempt_id=:id AND outcome='IN_PROGRESS'
                """).param("outcome", outcome).param("error", errorCategory)
                .param("providerOrder", providerOrderId).param("responseHash", responseHash)
                .param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE).param("id", attemptId).update();
    }

    public ProviderOrderRecord saveProviderOrder(
            PaymentControl control, String proposalHash, PaymentProvider.ProviderOrder order,
            String idempotencyReference, Instant now) {
        ProviderOrderRecord stored = jdbc.sql("""
                INSERT INTO payment_provider_order(
                    payment_control_id,execution_id,proposal_id,proposal_hash,merchant_id,
                    payment_configuration_id,provider_order_id,amount_minor,currency,receipt,
                    provider_status,provider_created_at,idempotency_reference,response_hash,created_at)
                VALUES(:control,:execution,:proposal,:proposalHash,:merchant,:configuration,:providerOrder,
                    :amount,:currency,:receipt,:status,:providerCreated,:idempotency,:responseHash,:now)
                RETURNING *
                """).param("control", control.id()).param("execution", control.executionId())
                .param("proposal", control.proposalId()).param("proposalHash", proposalHash)
                .param("merchant", control.merchantId()).param("configuration", control.configurationId())
                .param("providerOrder", order.id()).param("amount", order.amountMinor())
                .param("currency", order.currency()).param("receipt", order.receipt())
                .param("status", order.status())
                .param("providerCreated", utc(order.createdAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("idempotency", idempotencyReference).param("responseHash", order.evidenceHash())
                .param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE).query(this::providerOrder).single();
        jdbc.sql("""
                UPDATE payment_control SET state='PAYMENT_PENDING',expected_provider_order_id=:order,
                    reason_code='PROVIDER_ORDER_CREATED',version=version+1,updated_at=:now
                WHERE payment_control_id=:control
                """).param("order", order.id()).param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("control", control.id()).update();
        jdbc.sql("""
                UPDATE transaction_execution SET status='PAYMENT_PENDING',provider_order_reference=:order,
                    updated_at=:now WHERE execution_id=:execution AND status='RESERVED'
                """).param("order", order.id()).param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("execution", control.executionId()).update();
        return stored;
    }

    public void markUncertain(PaymentControl control, String reason, String providerPaymentId,
            int maximumAttempts, Instant now) {
        jdbc.sql("""
                UPDATE payment_control SET state='PAYMENT_UNCERTAIN',reason_code=:reason,
                    version=version+1,updated_at=:now WHERE payment_control_id=:control
                    AND state <> 'PAYMENT_CONFIRMED'
                """).param("reason", reason).param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("control", control.id()).update();
        jdbc.sql("""
                INSERT INTO payment_reconciliation(
                    payment_control_id,execution_id,provider_payment_id,maximum_attempts,status,
                    next_attempt_at,created_at,updated_at)
                VALUES(:control,:execution,:payment,:maximum,'PENDING',:now,:now,:now)
                ON CONFLICT (payment_control_id) DO UPDATE SET
                    provider_payment_id=COALESCE(EXCLUDED.provider_payment_id,payment_reconciliation.provider_payment_id),
                    status=CASE WHEN payment_reconciliation.status='COMPLETED' THEN 'COMPLETED' ELSE 'PENDING' END,
                    next_attempt_at=EXCLUDED.next_attempt_at,updated_at=EXCLUDED.updated_at
                """).param("control", control.id()).param("execution", control.executionId())
                .param("payment", providerPaymentId).param("maximum", maximumAttempts)
                .param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE).update();
    }

    public void markDefinitiveFailure(PaymentControl control, String reason, Instant now) {
        jdbc.sql("""
                UPDATE payment_control SET state='PAYMENT_FAILED',reason_code=:reason,
                    version=version+1,updated_at=:now WHERE payment_control_id=:control
                    AND state <> 'PAYMENT_CONFIRMED'
                """).param("reason", reason).param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("control", control.id()).update();
    }

    public void saveCallback(
            PaymentControl control, String orderId, String paymentId, String signatureHash,
            String evidenceHash, Instant now) {
        jdbc.sql("""
                INSERT INTO payment_callback_evidence(
                    payment_control_id,execution_id,merchant_id,provider_order_id,
                    provider_payment_id,signature_hash,valid,evidence_hash,observed_at)
                VALUES(:control,:execution,:merchant,:order,:payment,:signature,TRUE,:hash,:now)
                ON CONFLICT DO NOTHING
                """).param("control", control.id()).param("execution", control.executionId())
                .param("merchant", control.merchantId()).param("order", orderId).param("payment", paymentId)
                .param("signature", signatureHash).param("hash", evidenceHash)
                .param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE).update();
    }

    public PaymentEvidence savePaymentEvidence(
            PaymentControl control, PaymentProvider.ProviderPayment payment, EvidenceSource source,
            String sourceReference, Instant now) {
        return jdbc.sql("""
                INSERT INTO provider_payment_evidence(
                    payment_control_id,execution_id,merchant_id,payment_configuration_id,
                    provider_payment_id,provider_order_id,provider_status,amount_minor,currency,captured,
                    provider_account_reference,source,source_reference,evidence_hash,observed_at)
                VALUES(:control,:execution,:merchant,:configuration,:payment,:providerOrder,:status,
                    :amount,:currency,:captured,:account,:source,:sourceReference,:hash,:observed)
                ON CONFLICT (payment_control_id,source,source_reference,evidence_hash)
                    DO UPDATE SET evidence_hash=EXCLUDED.evidence_hash
                RETURNING *
                """).param("control", control.id()).param("execution", control.executionId())
                .param("merchant", control.merchantId()).param("configuration", control.configurationId())
                .param("payment", payment.id()).param("providerOrder", payment.orderId())
                .param("status", payment.status()).param("amount", payment.amountMinor())
                .param("currency", payment.currency()).param("captured", payment.captured())
                .param("account", payment.accountReference()).param("source", source.name())
                .param("sourceReference", sourceReference).param("hash", payment.evidenceHash())
                .param("observed", utc(payment.observedAt() == null ? now : payment.observedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .query(this::paymentEvidence).single();
    }

    public OrderEvidence saveOrderEvidence(
            PaymentControl control, PaymentProvider.ProviderOrder order, EvidenceSource source,
            String sourceReference, Instant now) {
        return jdbc.sql("""
                INSERT INTO provider_order_evidence(
                    payment_control_id,execution_id,merchant_id,payment_configuration_id,
                    provider_order_id,provider_status,amount_minor,amount_paid_minor,currency,
                    provider_account_reference,source,source_reference,evidence_hash,observed_at)
                VALUES(:control,:execution,:merchant,:configuration,:order,:status,:amount,:amountPaid,
                    :currency,:account,:source,:sourceReference,:hash,:observed)
                ON CONFLICT (payment_control_id,source,source_reference,evidence_hash)
                    DO UPDATE SET evidence_hash=EXCLUDED.evidence_hash
                RETURNING *
                """).param("control", control.id()).param("execution", control.executionId())
                .param("merchant", control.merchantId()).param("configuration", control.configurationId())
                .param("order", order.id()).param("status", order.status()).param("amount", order.amountMinor())
                .param("amountPaid", order.amountPaidMinor()).param("currency", order.currency())
                .param("account", order.accountReference()).param("source", source.name())
                .param("sourceReference", sourceReference).param("hash", order.evidenceHash())
                .param("observed", utc(now), Types.TIMESTAMP_WITH_TIMEZONE).query(this::orderEvidence).single();
    }

    public Optional<PaymentEvidence> latestPaymentEvidence(UUID controlId) {
        return jdbc.sql("""
                SELECT * FROM provider_payment_evidence WHERE payment_control_id=:control
                ORDER BY observed_at DESC,created_at DESC LIMIT 1
                """).param("control", controlId).query(this::paymentEvidence).optional();
    }

    public Optional<OrderEvidence> latestOrderEvidence(UUID controlId) {
        return jdbc.sql("""
                SELECT * FROM provider_order_evidence WHERE payment_control_id=:control
                ORDER BY observed_at DESC,created_at DESC LIMIT 1
                """).param("control", controlId).query(this::orderEvidence).optional();
    }

    public void saveReductionAndState(
            PaymentControl control, PaymentState reduced, String reason, PaymentEvidence payment,
            OrderEvidence order, String inputHash, Instant now) {
        jdbc.sql("""
                INSERT INTO payment_reduction_evidence(
                    payment_control_id,execution_id,previous_state,reduced_state,reason_code,
                    payment_evidence_id,order_evidence_id,input_hash,reduced_at)
                VALUES(:control,:execution,:previous,:reduced,:reason,:payment,:order,:hash,:now)
                """).param("control", control.id()).param("execution", control.executionId())
                .param("previous", control.state().name()).param("reduced", reduced.name()).param("reason", reason)
                .param("payment", payment == null ? null : payment.id()).param("order", order == null ? null : order.id())
                .param("hash", inputHash).param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE).update();
        jdbc.sql("""
                UPDATE payment_control SET state=:state,reason_code=:reason,
                    confirmed_payment_id=CASE WHEN :state='PAYMENT_CONFIRMED' THEN :paymentId ELSE confirmed_payment_id END,
                    confirmed_at=CASE WHEN :state='PAYMENT_CONFIRMED' THEN COALESCE(confirmed_at,:now) ELSE NULL END,
                    version=version+1,updated_at=:now WHERE payment_control_id=:control
                """).param("state", reduced.name()).param("reason", reason)
                .param("paymentId", payment == null ? null : payment.providerPaymentId())
                .param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE).param("control", control.id()).update();
        if (reduced == PaymentState.PAYMENT_CONFIRMED) createFinalizationAndOutbox(control, payment, inputHash, now);
    }

    private void createFinalizationAndOutbox(
            PaymentControl control, PaymentEvidence payment, String requestHash, Instant now) {
        String operationId = "mop_" + control.executionId().toString().replace("-", "");
        jdbc.sql("""
                INSERT INTO merchant_finalization(
                    execution_id,proposal_id,merchant_id,merchant_operation_id,state,
                    provider_order_id,provider_payment_id,request_hash,created_at,updated_at)
                VALUES(:execution,:proposal,:merchant,:operation,'PENDING',:order,:payment,:hash,:now,:now)
                ON CONFLICT (execution_id) DO NOTHING
                """).param("execution", control.executionId()).param("proposal", control.proposalId())
                .param("merchant", control.merchantId()).param("operation", operationId)
                .param("order", control.expectedProviderOrderId()).param("payment", payment.providerPaymentId())
                .param("hash", requestHash).param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE).update();
        var payload = mapper.createObjectNode();
        payload.put("executionId", control.executionId().toString());
        payload.put("merchantOperationId", operationId);
        jdbc.sql("""
                INSERT INTO transactional_outbox(
                    merchant_id,execution_id,work_type,payload,status,next_attempt_at,created_at)
                VALUES(:merchant,:execution,'FINALIZE_MERCHANT_ORDER',CAST(:payload AS jsonb),'PENDING',:now,:now)
                ON CONFLICT (execution_id,work_type) DO NOTHING
                """).param("merchant", control.merchantId()).param("execution", control.executionId())
                .param("payload", mapper.writeValueAsString(payload))
                .param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE).update();
    }

    public Optional<PaymentStateView> state(UUID buyerId, UUID threadId, UUID proposalId) {
        return jdbc.sql("""
                SELECT control.*,merchant_order.provider_order_id,
                    finalization.state fulfillment_state,finalization.merchant_order_id,
                    COALESCE(reconciliation.attempt_count,0) reconciliation_attempts,
                    COALESCE(reconciliation.maximum_attempts,0) reconciliation_maximum
                FROM payment_control control
                JOIN transaction_proposal proposal ON proposal.proposal_id=control.proposal_id
                LEFT JOIN payment_provider_order merchant_order ON merchant_order.execution_id=control.execution_id
                LEFT JOIN merchant_finalization finalization ON finalization.execution_id=control.execution_id
                LEFT JOIN payment_reconciliation reconciliation ON reconciliation.execution_id=control.execution_id
                WHERE control.buyer_actor_id=:buyer AND control.proposal_id=:proposal AND proposal.thread_id=:thread
                """).param("buyer", buyerId).param("proposal", proposalId).param("thread", threadId)
                .query((rs, row) -> new PaymentStateView(
                        rs.getObject("execution_id", UUID.class), rs.getObject("proposal_id", UUID.class),
                        PaymentState.valueOf(rs.getString("state")), rs.getString("reason_code"),
                        rs.getString("provider_order_id"), rs.getString("confirmed_payment_id"),
                        rs.getLong("expected_amount_minor"), rs.getString("expected_currency"),
                        fulfillment(rs.getString("fulfillment_state")), rs.getString("merchant_order_id"),
                        rs.getInt("reconciliation_attempts"), rs.getInt("reconciliation_maximum"),
                        instant(rs, "updated_at"))).optional();
    }

    public Optional<FulfillmentView> fulfillment(UUID buyerId, UUID threadId, UUID proposalId) {
        return jdbc.sql("""
                SELECT control.execution_id,control.state payment_state,finalization.state,
                    finalization.merchant_operation_id,finalization.merchant_order_id,
                    finalization.attempt_count,finalization.last_error_code
                FROM payment_control control
                JOIN transaction_proposal proposal ON proposal.proposal_id=control.proposal_id
                LEFT JOIN merchant_finalization finalization ON finalization.execution_id=control.execution_id
                WHERE control.buyer_actor_id=:buyer AND control.proposal_id=:proposal AND proposal.thread_id=:thread
                """).param("buyer", buyerId).param("proposal", proposalId).param("thread", threadId)
                .query((rs, row) -> new FulfillmentView(
                        rs.getObject("execution_id", UUID.class), PaymentState.valueOf(rs.getString("payment_state")),
                        fulfillment(rs.getString("state")), rs.getString("merchant_operation_id"),
                        rs.getString("merchant_order_id"), rs.getInt("attempt_count"),
                        rs.getString("last_error_code"))).optional();
    }

    /** Returns only PURCHASE requirements supported by persisted merchant-specific runtime evidence. */
    public Set<String> purchaseReadinessEvidence(UUID merchantId) {
        Set<String> result = new LinkedHashSet<>();
        boolean created = jdbc.sql("""
                SELECT EXISTS(
                    SELECT 1 FROM payment_provider_order provider_order
                    JOIN payment_control control ON control.payment_control_id=provider_order.payment_control_id
                    WHERE provider_order.merchant_id=:merchant
                      AND provider_order.amount_minor=control.expected_amount_minor
                      AND provider_order.currency=control.expected_currency)
                """).param("merchant", merchantId).query(Boolean.class).single();
        if (created) {
            result.add("ORDER_CREATION_CONTRACT_TEST");
            result.add("MONEY_NORMALIZATION_TEST");
        }
        boolean recovered = jdbc.sql("""
                SELECT EXISTS(
                    SELECT 1 FROM provider_order_creation_attempt attempt
                    JOIN payment_provider_order provider_order ON provider_order.execution_id=attempt.execution_id
                    WHERE attempt.merchant_id=:merchant AND attempt.outcome='UNCERTAIN')
                """).param("merchant", merchantId).query(Boolean.class).single();
        if (recovered) {
            result.add("TIMEOUT_RECONCILIATION_TEST");
            result.add("DUPLICATE_ORDER_PROTECTION_TEST");
        }
        boolean fulfilled = jdbc.sql("""
                SELECT EXISTS(
                    SELECT 1 FROM merchant_finalization
                    WHERE merchant_id=:merchant AND state='FULFILLED'
                      AND mapping_proposal_id IS NOT NULL AND merchant_order_id IS NOT NULL)
                """).param("merchant", merchantId).query(Boolean.class).single();
        if (fulfilled) {
            result.add("ORDER_STATE_CONTRACT_TEST");
            result.add("SCHEMA_VALIDATION");
        }
        boolean retried = jdbc.sql("""
                SELECT EXISTS(
                    SELECT 1 FROM merchant_finalization
                    WHERE merchant_id=:merchant AND state='FULFILLED' AND attempt_count > 1
                      AND merchant_operation_id IS NOT NULL)
                """).param("merchant", merchantId).query(Boolean.class).single();
        if (retried) result.add("ORDER_IDEMPOTENCY_TEST");
        boolean policyBound = jdbc.sql("""
                SELECT EXISTS(
                    SELECT 1 FROM transaction_execution execution
                    JOIN transaction_proposal proposal ON proposal.proposal_id=execution.proposal_id
                    WHERE execution.merchant_id=:merchant AND proposal.policy_snapshot_id IS NOT NULL)
                """).param("merchant", merchantId).query(Boolean.class).single();
        if (policyBound) result.add("APPROVED_POLICY_COVERAGE");
        return Set.copyOf(result);
    }

    public Optional<PaymentControl> controlByProviderOrder(String providerOrderId) {
        return jdbc.sql("""
                SELECT control.* FROM payment_control control
                JOIN payment_provider_order provider_order ON provider_order.payment_control_id=control.payment_control_id
                WHERE provider_order.provider_order_id=:providerOrder
                """).param("providerOrder", providerOrderId).query(this::control).optional();
    }

    public record ReconciliationWork(int attemptCount, int maximumAttempts, String providerPaymentId) {}

    public Optional<ReconciliationWork> beginReconciliation(
            PaymentControl control, String providerPaymentId, int maximumAttempts, Instant now) {
        jdbc.sql("""
                UPDATE payment_control SET state='PAYMENT_UNCERTAIN',reason_code='RECONCILIATION_REQUIRED',
                    version=version+1,updated_at=:now WHERE payment_control_id=:control
                    AND state <> 'PAYMENT_CONFIRMED'
                """).param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("control", control.id()).update();
        jdbc.sql("""
                INSERT INTO payment_reconciliation(
                    payment_control_id,execution_id,provider_payment_id,maximum_attempts,status,
                    next_attempt_at,created_at,updated_at)
                VALUES(:control,:execution,:payment,:maximum,'PENDING',:now,:now,:now)
                ON CONFLICT (payment_control_id) DO UPDATE SET provider_payment_id=
                    COALESCE(EXCLUDED.provider_payment_id,payment_reconciliation.provider_payment_id)
                """).param("control", control.id()).param("execution", control.executionId())
                .param("payment", providerPaymentId).param("maximum", maximumAttempts)
                .param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE).update();
        return jdbc.sql("""
                UPDATE payment_reconciliation SET attempt_count=attempt_count+1,last_attempt_at=:now,
                    updated_at=:now,next_attempt_at=:next
                WHERE payment_control_id=:control AND status='PENDING' AND next_attempt_at <= :now
                    AND attempt_count < maximum_attempts
                RETURNING attempt_count,maximum_attempts,provider_payment_id
                """).param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("next", utc(now.plusSeconds(30)), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("control", control.id()).query((rs, row) -> new ReconciliationWork(
                        rs.getInt("attempt_count"), rs.getInt("maximum_attempts"),
                        rs.getString("provider_payment_id"))).optional();
    }

    public void completeReconciliation(PaymentControl control, boolean completed, String error, Instant now) {
        jdbc.sql("""
                UPDATE payment_reconciliation SET
                    status=CASE WHEN :completed THEN 'COMPLETED'
                        WHEN attempt_count >= maximum_attempts THEN 'MANUAL_REVIEW' ELSE 'PENDING' END,
                    next_attempt_at=CASE WHEN :completed OR attempt_count >= maximum_attempts
                        THEN NULL ELSE :next END,
                    last_error_code=:error,updated_at=:now
                WHERE payment_control_id=:control
                """).param("completed", completed)
                .param("next", utc(now.plusSeconds(30)), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("error", error).param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("control", control.id()).update();
    }

    public boolean insertWebhook(
            PaymentConfiguration configuration, String eventId, String eventType,
            String signatureHash, String bodyHash, byte[] body, Instant now) {
        return jdbc.sql("""
                INSERT INTO provider_webhook_event(
                    payment_configuration_id,merchant_id,provider_event_id,event_type,
                    signature_hash,raw_body_hash,raw_body,processing_status,received_at)
                VALUES(:configuration,:merchant,:event,:type,:signature,:bodyHash,:body,'RECEIVED',:now)
                ON CONFLICT (payment_configuration_id,provider_event_id) DO NOTHING
                """).param("configuration", configuration.id()).param("merchant", configuration.merchantId())
                .param("event", eventId).param("type", eventType).param("signature", signatureHash)
                .param("bodyHash", bodyHash).param("body", body)
                .param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE).update() == 1;
    }

    public void completeWebhook(UUID configurationId, String eventId, String error, Instant now) {
        jdbc.sql("""
                UPDATE provider_webhook_event SET processing_status=:status,processed_at=:now,
                    last_error_code=:error
                WHERE payment_configuration_id=:configuration AND provider_event_id=:event
                """).param("status", error == null ? "PROCESSED" : "REJECTED")
                .param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE).param("error", error)
                .param("configuration", configurationId).param("event", eventId).update();
    }

    public record OutboxItem(UUID id, UUID merchantId, UUID executionId, UUID refundExecutionId,
            String workType, int attemptCount) {}
    public record FinalizationWork(
            UUID id, UUID executionId, UUID proposalId, UUID merchantId, String merchantOperationId,
            String providerOrderId, String providerPaymentId, FulfillmentState state, int attemptCount) {}
    public record FinalizationHeader(long amountMinor, String currency, String proposalHash) {}
    public record FinalizationFulfilment(String externalCustomerReference, String recipientName,
            String phone, String addressLine1, String addressLine2, String locality, String city,
            String state, String postalCode, String country, String deliveryOption,
            String fulfilmentSnapshotHash, String merchantAccountLinkHash) {}
    public record FinalizationLine(
            int lineNumber, UUID productId, String merchantSku, String variant,
            int quantity, long unitAmountMinor, long lineAmountMinor) {}

    @org.springframework.transaction.annotation.Transactional
    public List<OutboxItem> claimOutbox(int limit, Instant now, Instant leaseExpiry) {
        return jdbc.sql("""
                WITH candidates AS (
                    SELECT outbox_id FROM transactional_outbox
                    WHERE (status IN ('PENDING','FAILED_RETRYABLE') AND next_attempt_at <= :now)
                       OR (status='PROCESSING' AND lease_expires_at < :now)
                    ORDER BY next_attempt_at,created_at
                    FOR UPDATE SKIP LOCKED LIMIT :limit
                )
                UPDATE transactional_outbox outbox SET status='PROCESSING',
                    attempt_count=attempt_count+1,lease_expires_at=:lease,last_error_code=NULL
                FROM candidates WHERE outbox.outbox_id=candidates.outbox_id
                RETURNING outbox.outbox_id,outbox.merchant_id,outbox.execution_id,
                    outbox.refund_execution_id,outbox.work_type,outbox.attempt_count
                """).param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE).param("limit", limit)
                .param("lease", utc(leaseExpiry), Types.TIMESTAMP_WITH_TIMEZONE)
                .query((rs, row) -> new OutboxItem(rs.getObject("outbox_id", UUID.class),
                        rs.getObject("merchant_id", UUID.class), rs.getObject("execution_id", UUID.class),
                        rs.getObject("refund_execution_id", UUID.class),rs.getString("work_type"),
                        rs.getInt("attempt_count"))).list();
    }

    public Optional<FinalizationWork> finalization(UUID executionId) {
        return jdbc.sql("SELECT * FROM merchant_finalization WHERE execution_id=:execution")
                .param("execution", executionId).query((rs, row) -> new FinalizationWork(
                        rs.getObject("merchant_finalization_id", UUID.class), rs.getObject("execution_id", UUID.class),
                        rs.getObject("proposal_id", UUID.class), rs.getObject("merchant_id", UUID.class),
                        rs.getString("merchant_operation_id"), rs.getString("provider_order_id"),
                        rs.getString("provider_payment_id"), FulfillmentState.valueOf(rs.getString("state")),
                        rs.getInt("attempt_count"))).optional();
    }

    public FinalizationHeader finalizationHeader(UUID proposalId) {
        return jdbc.sql("""
                SELECT final_amount_minor,currency,proposal_hash FROM transaction_proposal WHERE proposal_id=:proposal
                """).param("proposal", proposalId).query((rs, row) -> new FinalizationHeader(
                        rs.getLong("final_amount_minor"), rs.getString("currency"),
                        rs.getString("proposal_hash").strip())).single();
    }

    public FinalizationFulfilment finalizationFulfilment(UUID proposalId) {
        return jdbc.sql("""
                SELECT snapshot.external_customer_reference,snapshot.recipient_name,snapshot.phone,
                  snapshot.address_line_1,snapshot.address_line_2,snapshot.locality,snapshot.city,
                  snapshot.state,snapshot.postal_code,snapshot.country,snapshot.delivery_option,
                  snapshot.snapshot_hash,binding.merchant_account_link_hash
                FROM transaction_proposal_fulfilment binding
                JOIN fulfilment_snapshot snapshot ON snapshot.fulfilment_snapshot_id=binding.fulfilment_snapshot_id
                WHERE binding.proposal_id=:proposal
                """).param("proposal", proposalId).query((rs,row) -> new FinalizationFulfilment(
                        rs.getString("external_customer_reference"),rs.getString("recipient_name"),rs.getString("phone"),
                        rs.getString("address_line_1"),rs.getString("address_line_2"),rs.getString("locality"),
                        rs.getString("city"),rs.getString("state"),rs.getString("postal_code"),rs.getString("country"),
                        rs.getString("delivery_option"),rs.getString("snapshot_hash").strip(),
                        rs.getString("merchant_account_link_hash").strip())).single();
    }

    public List<FinalizationLine> finalizationLines(UUID proposalId) {
        return jdbc.sql("""
                SELECT line_number,product_id,merchant_sku,variant,quantity,unit_amount_minor,line_amount_minor
                FROM transaction_proposal_line_item WHERE proposal_id=:proposal ORDER BY line_number
                """).param("proposal", proposalId).query((rs, row) -> new FinalizationLine(
                        rs.getInt("line_number"), rs.getObject("product_id", UUID.class),
                        rs.getString("merchant_sku"), rs.getString("variant"), rs.getInt("quantity"),
                        rs.getLong("unit_amount_minor"), rs.getLong("line_amount_minor"))).list();
    }

    public int beginFinalizationAttempt(FinalizationWork work, Instant now) {
        int attempt = work.attemptCount() + 1;
        jdbc.sql("""
                UPDATE merchant_finalization SET state='IN_PROGRESS',attempt_count=:attempt,
                    updated_at=:now WHERE merchant_finalization_id=:id AND state <> 'FULFILLED'
                """).param("attempt", attempt).param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("id", work.id()).update();
        jdbc.sql("""
                INSERT INTO merchant_finalization_attempt(
                    merchant_finalization_id,attempt_number,outcome,started_at)
                VALUES(:id,:attempt,'IN_PROGRESS',:now) ON CONFLICT DO NOTHING
                """).param("id", work.id()).param("attempt", attempt)
                .param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE).update();
        return attempt;
    }

    public void completeFinalization(
            FinalizationWork work, int attempt, UUID mappingId, String merchantOrderId,
            String responseHash, Instant now) {
        jdbc.sql("""
                UPDATE merchant_finalization SET mapping_proposal_id=:mapping,state='FULFILLED',
                    merchant_order_id=:merchantOrder,response_hash=:responseHash,last_error_code=NULL,
                    updated_at=:now,fulfilled_at=:now WHERE merchant_finalization_id=:id
                """).param("mapping", mappingId).param("merchantOrder", merchantOrderId)
                .param("responseHash", responseHash).param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("id", work.id()).update();
        jdbc.sql("""
                UPDATE merchant_finalization_attempt SET outcome='FULFILLED',response_hash=:hash,
                    completed_at=:now WHERE merchant_finalization_id=:id AND attempt_number=:attempt
                """).param("hash", responseHash).param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("id", work.id()).param("attempt", attempt).update();
        jdbc.sql("""
                INSERT INTO merchant_order_observation(execution_id,merchant_finalization_id,buyer_actor_id,
                  merchant_id,merchant_order_id,external_customer_reference,status,source,source_reference,
                  evidence_hash,observed_at)
                SELECT finalization.execution_id,finalization.merchant_finalization_id,proposal.buyer_actor_id,
                  finalization.merchant_id,:merchantOrder,snapshot.external_customer_reference,'PLACED',
                  'MERCHANT_RESPONSE',finalization.merchant_operation_id,:hash,:now
                FROM merchant_finalization finalization
                JOIN transaction_proposal proposal ON proposal.proposal_id=finalization.proposal_id
                JOIN transaction_proposal_fulfilment binding ON binding.proposal_id=proposal.proposal_id
                JOIN fulfilment_snapshot snapshot ON snapshot.fulfilment_snapshot_id=binding.fulfilment_snapshot_id
                WHERE finalization.merchant_finalization_id=:id ON CONFLICT DO NOTHING
                """).param("merchantOrder",merchantOrderId).param("hash",responseHash)
                .param("now",utc(now),Types.TIMESTAMP_WITH_TIMEZONE).param("id",work.id()).update();
    }

    public void failFinalization(
            FinalizationWork work, int attempt, boolean retryable, String error, Instant now) {
        jdbc.sql("""
                UPDATE merchant_finalization SET state=:state,last_error_code=:error,updated_at=:now
                WHERE merchant_finalization_id=:id
                """).param("state", retryable ? "RETRYABLE_FAILURE" : "TERMINAL_FAILURE")
                .param("error", error).param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("id", work.id()).update();
        jdbc.sql("""
                UPDATE merchant_finalization_attempt SET outcome=:outcome,error_code=:error,completed_at=:now
                WHERE merchant_finalization_id=:id AND attempt_number=:attempt
                """).param("outcome", retryable ? "RETRYABLE_FAILURE" : "TERMINAL_FAILURE")
                .param("error", error).param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("id", work.id()).param("attempt", attempt).update();
    }

    public void completeOutbox(UUID outboxId, Instant now) {
        jdbc.sql("""
                UPDATE transactional_outbox SET status='COMPLETED',processed_at=:now,
                    lease_expires_at=NULL,last_error_code=NULL WHERE outbox_id=:id AND status='PROCESSING'
                """).param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE).param("id", outboxId).update();
    }

    public void failOutbox(UUID outboxId, boolean retryable, String error, Instant next, Instant now) {
        jdbc.sql("""
                UPDATE transactional_outbox SET status=:status,next_attempt_at=:next,
                    lease_expires_at=NULL,last_error_code=:error,
                    processed_at=CASE WHEN :retryable THEN NULL ELSE :now END
                WHERE outbox_id=:id AND status='PROCESSING'
                """).param("status", retryable ? "FAILED_RETRYABLE" : "FAILED_TERMINAL")
                .param("next", utc(next), Types.TIMESTAMP_WITH_TIMEZONE).param("error", error)
                .param("retryable", retryable).param("now", utc(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("id", outboxId).update();
    }

    private PaymentConfiguration mapConfiguration(ResultSet rs, int row) throws SQLException {
        return new PaymentConfiguration(rs.getObject("payment_configuration_id", UUID.class),
                rs.getObject("merchant_id", UUID.class), rs.getString("configuration_reference"),
                rs.getString("provider_account_reference"), rs.getBoolean("active"));
    }
    private PaymentControl control(ResultSet rs, int row) throws SQLException {
        return new PaymentControl(rs.getObject("payment_control_id", UUID.class),
                rs.getObject("execution_id", UUID.class), rs.getObject("proposal_id", UUID.class),
                rs.getObject("buyer_actor_id", UUID.class), rs.getObject("merchant_id", UUID.class),
                rs.getObject("payment_configuration_id", UUID.class), PaymentState.valueOf(rs.getString("state")),
                rs.getLong("expected_amount_minor"), rs.getString("expected_currency"),
                rs.getString("expected_provider_order_id"), rs.getString("confirmed_payment_id"),
                rs.getInt("version"), rs.getString("reason_code"), instant(rs, "created_at"),
                instant(rs, "updated_at"), instant(rs, "confirmed_at"));
    }
    private ProviderOrderRecord providerOrder(ResultSet rs, int row) throws SQLException {
        return new ProviderOrderRecord(rs.getObject("provider_order_record_id", UUID.class),
                rs.getObject("payment_control_id", UUID.class), rs.getObject("execution_id", UUID.class),
                rs.getObject("proposal_id", UUID.class), rs.getString("proposal_hash").strip(),
                rs.getObject("merchant_id", UUID.class), rs.getObject("payment_configuration_id", UUID.class),
                rs.getString("provider_order_id"), rs.getLong("amount_minor"), rs.getString("currency"),
                rs.getString("receipt"), rs.getString("provider_status"), instant(rs, "provider_created_at"),
                rs.getString("idempotency_reference"), rs.getString("response_hash").strip(), instant(rs, "created_at"));
    }
    private PaymentEvidence paymentEvidence(ResultSet rs, int row) throws SQLException {
        return new PaymentEvidence(rs.getObject("payment_evidence_id", UUID.class),
                rs.getObject("payment_control_id", UUID.class), rs.getString("provider_payment_id"),
                rs.getString("provider_order_id"), rs.getString("provider_status"),
                (Long) rs.getObject("amount_minor"), rs.getString("currency"), rs.getBoolean("captured"),
                rs.getString("provider_account_reference"), EvidenceSource.valueOf(rs.getString("source")),
                rs.getString("evidence_hash").strip(), instant(rs, "observed_at"));
    }
    private OrderEvidence orderEvidence(ResultSet rs, int row) throws SQLException {
        return new OrderEvidence(rs.getObject("order_evidence_id", UUID.class),
                rs.getObject("payment_control_id", UUID.class), rs.getString("provider_order_id"),
                rs.getString("provider_status"), (Long) rs.getObject("amount_minor"),
                (Long) rs.getObject("amount_paid_minor"), rs.getString("currency"),
                rs.getString("provider_account_reference"), EvidenceSource.valueOf(rs.getString("source")),
                rs.getString("evidence_hash").strip(), instant(rs, "observed_at"));
    }
    private static FulfillmentState fulfillment(String state) {
        return state == null ? FulfillmentState.PENDING : FulfillmentState.valueOf(state);
    }
    private static OffsetDateTime utc(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
