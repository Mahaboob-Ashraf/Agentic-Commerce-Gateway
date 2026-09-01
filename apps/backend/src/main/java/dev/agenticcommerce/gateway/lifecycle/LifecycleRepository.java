package dev.agenticcommerce.gateway.lifecycle;

import static dev.agenticcommerce.gateway.lifecycle.LifecycleModels.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.*;

@Repository
public class LifecycleRepository {
    private final JdbcClient jdbc; private final ObjectMapper mapper;
    public LifecycleRepository(JdbcClient jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}

    public record OrderContext(UUID finalizationId,UUID buyerId,UUID threadId,UUID merchantId,String merchantOrderId,
            UUID proposalId,UUID executionId,UUID paymentControlId,String providerPaymentId,long capturedAmount,String currency,
            UUID policySnapshotId,int policyVersion,UUID linkId,int linkVersion,String linkHash,String customerReference,String status){}
    public List<OrderContext> ownedOrders(UUID buyer,UUID thread){return jdbc.sql("""
            SELECT f.merchant_finalization_id,p.buyer_actor_id,p.thread_id,p.merchant_id,f.merchant_order_id,
              p.proposal_id,f.execution_id,pc.payment_control_id,pc.confirmed_payment_id,pc.expected_amount_minor,
              pc.expected_currency,p.policy_snapshot_id,p.policy_snapshot_version,pf.merchant_account_link_id,
              pf.merchant_account_link_version,pf.merchant_account_link_hash,s.external_customer_reference,
              COALESCE((SELECT o.status FROM merchant_order_observation o WHERE o.merchant_finalization_id=f.merchant_finalization_id
                ORDER BY o.observed_at DESC,o.created_at DESC LIMIT 1),'PLACED') current_status
            FROM merchant_finalization f JOIN transaction_proposal p ON p.proposal_id=f.proposal_id
            JOIN payment_control pc ON pc.execution_id=f.execution_id
            JOIN transaction_proposal_fulfilment pf ON pf.proposal_id=p.proposal_id
            JOIN fulfilment_snapshot s ON s.fulfilment_snapshot_id=pf.fulfilment_snapshot_id
            WHERE p.buyer_actor_id=:buyer AND p.thread_id=:thread AND f.state='FULFILLED'
              AND pc.state='PAYMENT_CONFIRMED' ORDER BY f.fulfilled_at DESC
            """).param("buyer",buyer).param("thread",thread).query((rs,row)->context(rs)).list();}
    public Optional<OrderContext> context(UUID buyer,UUID finalization){return jdbc.sql("""
            SELECT f.merchant_finalization_id,p.buyer_actor_id,p.thread_id,p.merchant_id,f.merchant_order_id,
              p.proposal_id,f.execution_id,pc.payment_control_id,pc.confirmed_payment_id,pc.expected_amount_minor,
              pc.expected_currency,p.policy_snapshot_id,p.policy_snapshot_version,pf.merchant_account_link_id,
              pf.merchant_account_link_version,pf.merchant_account_link_hash,s.external_customer_reference,
              COALESCE((SELECT o.status FROM merchant_order_observation o WHERE o.merchant_finalization_id=f.merchant_finalization_id
                ORDER BY o.observed_at DESC,o.created_at DESC LIMIT 1),'PLACED') current_status
            FROM merchant_finalization f JOIN transaction_proposal p ON p.proposal_id=f.proposal_id
            JOIN payment_control pc ON pc.execution_id=f.execution_id
            JOIN transaction_proposal_fulfilment pf ON pf.proposal_id=p.proposal_id
            JOIN fulfilment_snapshot s ON s.fulfilment_snapshot_id=pf.fulfilment_snapshot_id
            WHERE p.buyer_actor_id=:buyer AND f.merchant_finalization_id=:id AND f.state='FULFILLED'
            """).param("buyer",buyer).param("id",finalization).query((rs,row)->context(rs)).optional();}
    public Intent insertIntent(UUID buyer,UUID thread,UUID finalization,Action action,String scope,Resolution resolution,
            String textHash,JsonNode evidence,Double confidence,Instant now){return jdbc.sql("""
            INSERT INTO lifecycle_intent(buyer_actor_id,thread_id,merchant_finalization_id,action_type,target_scope,
              resolution_status,source_text_hash,source_evidence,confidence,created_at)
            VALUES(:buyer,:thread,:finalization,:action,:scope,:resolution,:hash,CAST(:evidence AS jsonb),:confidence,:now)
            RETURNING *
            """).param("buyer",buyer).param("thread",thread).param("finalization",finalization).param("action",action.name())
            .param("scope",scope).param("resolution",resolution.name()).param("hash",textHash)
            .param("evidence",mapper.writeValueAsString(evidence)).param("confidence",confidence)
            .param("now",utc(now),Types.TIMESTAMP_WITH_TIMEZONE).query(this::intent).single();}
    public Optional<Intent> intent(UUID buyer,UUID id){return jdbc.sql("SELECT * FROM lifecycle_intent WHERE buyer_actor_id=:buyer AND lifecycle_intent_id=:id")
            .param("buyer",buyer).param("id",id).query(this::intent).optional();}
    public List<String> historicalPolicyEffects(UUID snapshot,String ruleType){return jdbc.sql("""
            SELECT rule.outcome_effect::text FROM merchant_policy_snapshot_rule member
            JOIN proposed_policy_rule rule ON rule.policy_rule_id=member.policy_rule_id AND rule.merchant_id=member.merchant_id
            WHERE member.policy_snapshot_id=:snapshot AND rule.rule_type=:type AND rule.approval_state='APPROVED'
            ORDER BY COALESCE(rule.precedence_priority,0) DESC,rule.rule_content_hash
            """).param("snapshot",snapshot).param("type",ruleType).query(String.class).list();}
    public boolean capabilityReady(UUID merchant,String capability){return jdbc.sql("""
            SELECT EXISTS(SELECT 1 FROM agent_commerce_manifest m JOIN agent_commerce_manifest_capability c ON c.manifest_id=m.manifest_id
              WHERE m.merchant_id=:merchant AND c.capability=:capability AND c.advertised AND c.readiness='READY'
                AND m.manifest_version=(SELECT MAX(m2.manifest_version) FROM agent_commerce_manifest m2 WHERE m2.merchant_id=:merchant))
            """).param("merchant",merchant).param("capability",capability).query(Boolean.class).single();}
    public boolean activeHistoricalLink(UUID buyer,UUID link){return jdbc.sql("""
            SELECT EXISTS(SELECT 1 FROM merchant_account_link WHERE buyer_actor_id=:buyer
              AND merchant_account_link_id=:link AND status='LINKED' AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP))
            """).param("buyer",buyer).param("link",link).query(Boolean.class).single();}
    public String originalThreadText(UUID buyer,UUID thread){return jdbc.sql("""
            SELECT normalized_text FROM commerce_thread_message WHERE buyer_actor_id=:buyer AND thread_id=:thread
              ORDER BY message_number LIMIT 1
            """).param("buyer",buyer).param("thread",thread).query(String.class).single();}
    public record OrderSeedLine(UUID productId,String merchantSku,String variant,int quantity){}
    public List<OrderSeedLine> originalOrderLines(UUID buyer,UUID finalization){return jdbc.sql("""
            SELECT line.product_id,line.merchant_sku,line.variant,line.quantity
            FROM merchant_finalization finalization
            JOIN transaction_proposal proposal ON proposal.proposal_id=finalization.proposal_id
            JOIN transaction_proposal_line_item line ON line.proposal_id=proposal.proposal_id
            WHERE finalization.merchant_finalization_id=:finalization AND proposal.buyer_actor_id=:buyer
            ORDER BY line.line_number
            """).param("buyer",buyer).param("finalization",finalization)
            .query((rs,row)->new OrderSeedLine(rs.getObject("product_id",UUID.class),rs.getString("merchant_sku"),rs.getString("variant"),rs.getInt("quantity"))).list();}
    public UUID insertPolicyEvaluation(Intent intent,OrderContext c,PolicyOutcome outcome,String reason,List<String> refs,String hash,Instant now){return jdbc.sql("""
            INSERT INTO lifecycle_policy_evaluation(lifecycle_intent_id,buyer_actor_id,merchant_id,policy_snapshot_id,
              policy_snapshot_version,action_type,outcome,reason_code,evidence_references,evaluation_hash,evaluated_at)
            VALUES(:intent,:buyer,:merchant,:snapshot,:version,:action,:outcome,:reason,CAST(:refs AS jsonb),:hash,:now)
            RETURNING lifecycle_policy_evaluation_id
            """).param("intent",intent.id()).param("buyer",intent.buyerId()).param("merchant",c.merchantId())
            .param("snapshot",c.policySnapshotId()).param("version",c.policyVersion()).param("action",intent.action().name())
            .param("outcome",outcome.name()).param("reason",reason).param("refs",mapper.writeValueAsString(refs)).param("hash",hash)
            .param("now",utc(now),Types.TIMESTAMP_WITH_TIMEZONE).query(UUID.class).single();}
    public Proposal insertProposal(Intent intent,OrderContext c,UUID evaluation,Long amount,JsonNode material,String hash,Instant now,Instant expiry){return jdbc.sql("""
            INSERT INTO lifecycle_proposal(lifecycle_intent_id,lifecycle_policy_evaluation_id,buyer_actor_id,thread_id,
              merchant_id,merchant_finalization_id,original_proposal_id,original_execution_id,original_payment_control_id,
              action_type,target_scope,refundable_amount_minor,currency,policy_snapshot_id,policy_snapshot_version,
              merchant_account_link_id,merchant_account_link_version,canonical_schema_version,canonical_material,
              proposal_hash,created_at,expires_at)
            VALUES(:intent,:evaluation,:buyer,:thread,:merchant,:finalization,:originalProposal,:execution,:payment,
              :action,'FULL_ORDER',:amount,:currency,:snapshot,:policyVersion,:link,:linkVersion,1,CAST(:material AS jsonb),
              :hash,:now,:expiry) RETURNING *
            """).param("intent",intent.id()).param("evaluation",evaluation).param("buyer",intent.buyerId())
            .param("thread",intent.threadId()).param("merchant",c.merchantId()).param("finalization",c.finalizationId())
            .param("originalProposal",c.proposalId()).param("execution",c.executionId()).param("payment",c.paymentControlId())
            .param("action",intent.action().name()).param("amount",amount).param("currency",c.currency())
            .param("snapshot",c.policySnapshotId()).param("policyVersion",c.policyVersion()).param("link",c.linkId())
            .param("linkVersion",c.linkVersion()).param("material",mapper.writeValueAsString(material)).param("hash",hash)
            .param("now",utc(now),Types.TIMESTAMP_WITH_TIMEZONE).param("expiry",utc(expiry),Types.TIMESTAMP_WITH_TIMEZONE)
            .query(this::proposal).single();}
    public Optional<Proposal> proposal(UUID buyer,UUID id){return jdbc.sql("SELECT * FROM lifecycle_proposal WHERE buyer_actor_id=:buyer AND lifecycle_proposal_id=:id")
            .param("buyer",buyer).param("id",id).query(this::proposal).optional();}
    public Optional<Proposal> proposalForIntent(UUID buyer,UUID intent){return jdbc.sql("SELECT * FROM lifecycle_proposal WHERE buyer_actor_id=:buyer AND lifecycle_intent_id=:intent")
            .param("buyer",buyer).param("intent",intent).query(this::proposal).optional();}
    public Authorization insertAuthorization(Proposal p,String session,String decision,String hash,Instant now,Instant expiry){return jdbc.sql("""
            INSERT INTO lifecycle_authorization_decision(buyer_actor_id,session_binding_hash,lifecycle_proposal_id,
              lifecycle_proposal_hash,action_type,decision,authorization_hash,issued_at,expires_at)
            VALUES(:buyer,:session,:proposal,:proposalHash,:action,:decision,:hash,:now,:expiry) RETURNING *
            """).param("buyer",p.buyerId()).param("session",session).param("proposal",p.id()).param("proposalHash",p.hash())
            .param("action",p.action().name()).param("decision",decision).param("hash",hash)
            .param("now",utc(now),Types.TIMESTAMP_WITH_TIMEZONE).param("expiry",utc(expiry),Types.TIMESTAMP_WITH_TIMEZONE)
            .query(this::authorization).single();}
    public Optional<Authorization> authorization(UUID buyer,UUID proposal){return jdbc.sql("""
            SELECT a.*,c.consumed_at FROM lifecycle_authorization_decision a LEFT JOIN lifecycle_authorization_consumption c
              ON c.lifecycle_authorization_id=a.lifecycle_authorization_id
            WHERE a.buyer_actor_id=:buyer AND a.lifecycle_proposal_id=:proposal
            """).param("buyer",buyer).param("proposal",proposal).query(this::authorization).optional();}
    public Execution reserveExecution(Proposal p,Authorization a,Instant now){
        Execution e=jdbc.sql("""
            INSERT INTO lifecycle_execution(lifecycle_proposal_id,lifecycle_proposal_hash,lifecycle_authorization_id,
              buyer_actor_id,merchant_id,merchant_finalization_id,action_type,merchant_operation_id,state,created_at,updated_at)
            VALUES(:proposal,:hash,:authorization,:buyer,:merchant,:finalization,:action,:operation,'RESERVED',:now,:now)
            ON CONFLICT (lifecycle_proposal_id) DO UPDATE SET lifecycle_proposal_id=EXCLUDED.lifecycle_proposal_id RETURNING *
            """).param("proposal",p.id()).param("hash",p.hash()).param("authorization",a.id()).param("buyer",p.buyerId())
            .param("merchant",p.merchantId()).param("finalization",p.finalizationId()).param("action",p.action().name())
            .param("operation","lop_"+p.id().toString().replace("-","")).param("now",utc(now),Types.TIMESTAMP_WITH_TIMEZONE)
            .query(this::execution).single();
        jdbc.sql("INSERT INTO lifecycle_authorization_consumption(lifecycle_authorization_id,lifecycle_execution_id,consumed_at) VALUES(:a,:e,:now) ON CONFLICT DO NOTHING")
            .param("a",a.id()).param("e",e.id()).param("now",utc(now),Types.TIMESTAMP_WITH_TIMEZONE).update();return e;}
    public Optional<Execution> executionForProposal(UUID buyer,UUID proposal){return jdbc.sql("SELECT * FROM lifecycle_execution WHERE buyer_actor_id=:buyer AND lifecycle_proposal_id=:proposal")
            .param("buyer",buyer).param("proposal",proposal).query(this::execution).optional();}
    public void completeExecution(UUID id,boolean success,boolean retryable,String reference,Instant now){jdbc.sql("""
            UPDATE lifecycle_execution SET state=:state,response_reference=:reference,updated_at=:now WHERE lifecycle_execution_id=:id
            """).param("state",success?"SUCCEEDED":retryable?"FAILED_RETRYABLE":"FAILED_TERMINAL").param("reference",reference)
            .param("now",utc(now),Types.TIMESTAMP_WITH_TIMEZONE).param("id",id).update();}
    public void observe(OrderContext c,String status,String source,String reference,String hash,Instant now){jdbc.sql("""
            INSERT INTO merchant_order_observation(execution_id,merchant_finalization_id,buyer_actor_id,merchant_id,
              merchant_order_id,external_customer_reference,status,source,source_reference,evidence_hash,observed_at)
            VALUES(:execution,:finalization,:buyer,:merchant,:order,:customer,:status,:source,:reference,:hash,:now)
            ON CONFLICT DO NOTHING
            """).param("execution",c.executionId()).param("finalization",c.finalizationId()).param("buyer",c.buyerId())
            .param("merchant",c.merchantId()).param("order",c.merchantOrderId()).param("customer",c.customerReference())
            .param("status",status).param("source",source).param("reference",reference).param("hash",hash)
            .param("now",utc(now),Types.TIMESTAMP_WITH_TIMEZONE).update();}

    @org.springframework.transaction.annotation.Transactional
    public RefundExecution reserveRefund(Proposal p,Execution e,OrderContext c,JsonNode body,String requestHash,Instant now){
        jdbc.sql("""
            INSERT INTO refund_ledger(payment_control_id,buyer_actor_id,merchant_id,captured_refundable_amount_minor,currency,updated_at)
            VALUES(:payment,:buyer,:merchant,:amount,:currency,:now) ON CONFLICT DO NOTHING
            """).param("payment",c.paymentControlId()).param("buyer",c.buyerId()).param("merchant",c.merchantId())
            .param("amount",c.capturedAmount()).param("currency",c.currency()).param("now",utc(now),Types.TIMESTAMP_WITH_TIMEZONE).update();
        var ledger=jdbc.sql("SELECT * FROM refund_ledger WHERE payment_control_id=:payment FOR UPDATE")
            .param("payment",c.paymentControlId()).query((rs,row)->new long[]{rs.getLong("captured_refundable_amount_minor"),rs.getLong("reserved_amount_minor"),rs.getLong("completed_amount_minor")}).single();
        Optional<RefundExecution> existing=refundForProposal(p.buyerId(),p.id());if(existing.isPresent())return existing.orElseThrow();
        if(ledger[1]!=0||ledger[2]!=0||ledger[0]!=c.capturedAmount())throw new LifecycleException("FULL_REFUND_ALREADY_RESERVED",org.springframework.http.HttpStatus.CONFLICT,"Full refundable value is already reserved or refunded");
        UUID id=UUID.randomUUID();String key="refund_"+id.toString().replace("-","");
        RefundExecution r=jdbc.sql("""
            INSERT INTO refund_execution(refund_execution_id,lifecycle_proposal_id,lifecycle_proposal_hash,lifecycle_execution_id,
              payment_control_id,buyer_actor_id,merchant_id,provider_payment_id,amount_minor,currency,provider_idempotency_key,
              request_body,request_hash,state,next_attempt_at,deadline_at,created_at,updated_at)
            VALUES(:id,:proposal,:hash,:execution,:payment,:buyer,:merchant,:providerPayment,:amount,:currency,:key,
              CAST(:body AS jsonb),:requestHash,'REFUND_PROPOSED',:now,:deadline,:now,:now) RETURNING *
            """).param("id",id).param("proposal",p.id()).param("hash",p.hash()).param("execution",e.id())
            .param("payment",c.paymentControlId()).param("buyer",c.buyerId()).param("merchant",c.merchantId())
            .param("providerPayment",c.providerPaymentId()).param("amount",c.capturedAmount()).param("currency",c.currency())
            .param("key",key).param("body",mapper.writeValueAsString(body)).param("requestHash",requestHash)
            .param("now",utc(now),Types.TIMESTAMP_WITH_TIMEZONE).param("deadline",utc(now.plusSeconds(60)),Types.TIMESTAMP_WITH_TIMEZONE)
            .query((rs,row)->refund(rs,row)).single();
        jdbc.sql("UPDATE refund_ledger SET reserved_amount_minor=:amount,version=version+1,updated_at=:now WHERE payment_control_id=:payment")
            .param("amount",c.capturedAmount()).param("now",utc(now),Types.TIMESTAMP_WITH_TIMEZONE).param("payment",c.paymentControlId()).update();
        var payload=mapper.createObjectNode();payload.put("refundExecutionId",r.id().toString());
        jdbc.sql("""
            INSERT INTO transactional_outbox(merchant_id,execution_id,refund_execution_id,work_type,payload,status,next_attempt_at,created_at)
            VALUES(:merchant,:execution,:refund,'SUBMIT_REFUND',CAST(:payload AS jsonb),'PENDING',:now,:now)
            """).param("merchant",c.merchantId()).param("execution",c.executionId()).param("refund",r.id())
            .param("payload",mapper.writeValueAsString(payload)).param("now",utc(now),Types.TIMESTAMP_WITH_TIMEZONE).update();return r;}
    public Optional<RefundExecution> refundForProposal(UUID buyer,UUID proposal){return jdbc.sql("SELECT * FROM refund_execution WHERE buyer_actor_id=:buyer AND lifecycle_proposal_id=:proposal")
            .param("buyer",buyer).param("proposal",proposal).query((rs,row)->refund(rs,row)).optional();}
    public Optional<RefundExecution> refund(UUID id){return jdbc.sql("SELECT * FROM refund_execution WHERE refund_execution_id=:id")
            .param("id",id).query((rs,row)->refund(rs,row)).optional();}
    public Optional<RefundExecution> refundByProvider(String payment,String refund){return jdbc.sql("SELECT * FROM refund_execution WHERE provider_payment_id=:payment AND provider_refund_id=:refund")
            .param("payment",payment).param("refund",refund).query((rs,row)->refund(rs,row)).optional();}
    public Optional<RefundExecution> uniquePendingRefund(String payment,long amount){var values=jdbc.sql("""
            SELECT * FROM refund_execution WHERE provider_payment_id=:payment AND amount_minor=:amount
              AND state IN ('REFUND_PROPOSED','REFUND_INITIATED','REFUND_PENDING') ORDER BY created_at DESC LIMIT 2
            """).param("payment",payment).param("amount",amount).query((rs,row)->refund(rs,row)).list();
        return values.size()==1?Optional.of(values.getFirst()):Optional.empty();}
    public void startRefundAttempt(UUID id,Instant now){jdbc.sql("""
            UPDATE refund_execution SET state='REFUND_INITIATED',attempt_count=attempt_count+1,updated_at=:now
            WHERE refund_execution_id=:id AND state NOT IN ('REFUNDED','REFUND_FAILED','MANUAL_REVIEW') AND attempt_count<5
            """).param("id",id).param("now",utc(now),Types.TIMESTAMP_WITH_TIMEZONE).update();}
    public void saveRefundEvidence(RefundExecution r,UUID config,String refundId,String paymentId,long amount,String currency,
            String status,String account,String source,String reference,String hash,Instant now){jdbc.sql("""
            INSERT INTO refund_provider_evidence(refund_execution_id,payment_configuration_id,provider_refund_id,
              provider_payment_id,amount_minor,currency,provider_status,provider_account_reference,source,
              source_reference,evidence_hash,observed_at)
            VALUES(:refund,:config,:providerRefund,:payment,:amount,:currency,:status,:account,:source,:reference,:hash,:now)
            ON CONFLICT DO NOTHING
            """).param("refund",r.id()).param("config",config).param("providerRefund",refundId).param("payment",paymentId)
            .param("amount",amount).param("currency",currency).param("status",status).param("account",account)
            .param("source",source).param("reference",reference).param("hash",hash).param("now",utc(now),Types.TIMESTAMP_WITH_TIMEZONE).update();}
    @org.springframework.transaction.annotation.Transactional public RefundExecution reduceRefund(UUID id,Instant now){
        RefundExecution r=jdbc.sql("SELECT * FROM refund_execution WHERE refund_execution_id=:id FOR UPDATE").param("id",id).query((rs,row)->refund(rs,row)).single();
        var ev=jdbc.sql("SELECT * FROM refund_provider_evidence WHERE refund_execution_id=:id ORDER BY observed_at DESC,created_at DESC LIMIT 1")
            .param("id",id).query((rs,row)->new Object[]{rs.getString("provider_refund_id"),rs.getString("provider_payment_id"),rs.getLong("amount_minor"),rs.getString("currency"),rs.getString("provider_status"),rs.getString("provider_account_reference")}).optional();
        if(ev.isEmpty())return r;Object[] e=ev.orElseThrow();boolean match=r.providerPaymentId().equals(e[1])&&r.amountMinor()==(Long)e[2]&&r.currency().equals(e[3]);
        String status=(String)e[4];if(!match)return r;
        if("processed".equalsIgnoreCase(status)&&r.state()!=RefundState.REFUNDED){jdbc.sql("UPDATE refund_execution SET state='REFUNDED',provider_refund_id=:provider,updated_at=:now,last_error_code=NULL WHERE refund_execution_id=:id")
            .param("provider",e[0]).param("now",utc(now),Types.TIMESTAMP_WITH_TIMEZONE).param("id",id).update();
            jdbc.sql("UPDATE refund_ledger SET reserved_amount_minor=reserved_amount_minor-:amount,completed_amount_minor=completed_amount_minor+:amount,version=version+1,updated_at=:now WHERE payment_control_id=:payment")
                .param("amount",r.amountMinor()).param("now",utc(now),Types.TIMESTAMP_WITH_TIMEZONE).param("payment",r.paymentControlId()).update();}
        else if("failed".equalsIgnoreCase(status)&&r.state()!=RefundState.REFUND_FAILED){jdbc.sql("UPDATE refund_execution SET state='REFUND_FAILED',provider_refund_id=:provider,updated_at=:now WHERE refund_execution_id=:id")
            .param("provider",e[0]).param("now",utc(now),Types.TIMESTAMP_WITH_TIMEZONE).param("id",id).update();
            jdbc.sql("UPDATE refund_ledger SET reserved_amount_minor=reserved_amount_minor-:amount,version=version+1,updated_at=:now WHERE payment_control_id=:payment")
                .param("amount",r.amountMinor()).param("now",utc(now),Types.TIMESTAMP_WITH_TIMEZONE).param("payment",r.paymentControlId()).update();}
        else jdbc.sql("UPDATE refund_execution SET state='REFUND_PENDING',provider_refund_id=:provider,updated_at=:now WHERE refund_execution_id=:id")
            .param("provider",e[0]).param("now",utc(now),Types.TIMESTAMP_WITH_TIMEZONE).param("id",id).update();
        return refund(id).orElseThrow();}
    public record PaymentConfigurationRef(UUID id,String account){}
    public PaymentConfigurationRef paymentConfiguration(UUID paymentControl){return jdbc.sql("""
            SELECT c.payment_configuration_id,c.provider_account_reference FROM payment_control p
              JOIN merchant_payment_configuration c ON c.payment_configuration_id=p.payment_configuration_id
              WHERE p.payment_control_id=:payment
            """).param("payment",paymentControl).query((rs,row)->new PaymentConfigurationRef(rs.getObject(1,UUID.class),rs.getString(2))).single();}
    public void refundFailure(UUID id,String error,boolean retryable,Instant next,Instant now){jdbc.sql("""
            UPDATE refund_execution SET state=CASE WHEN :retryable AND attempt_count<5 AND deadline_at>:now THEN 'REFUND_PENDING' ELSE 'MANUAL_REVIEW' END,
              next_attempt_at=:next,last_error_code=:error,updated_at=:now WHERE refund_execution_id=:id
            """).param("retryable",retryable).param("now",utc(now),Types.TIMESTAMP_WITH_TIMEZONE).param("next",utc(next),Types.TIMESTAMP_WITH_TIMEZONE)
            .param("error",error).param("id",id).update();}

    private OrderContext context(ResultSet rs)throws SQLException{return new OrderContext(rs.getObject("merchant_finalization_id",UUID.class),rs.getObject("buyer_actor_id",UUID.class),rs.getObject("thread_id",UUID.class),rs.getObject("merchant_id",UUID.class),rs.getString("merchant_order_id"),rs.getObject("proposal_id",UUID.class),rs.getObject("execution_id",UUID.class),rs.getObject("payment_control_id",UUID.class),rs.getString("confirmed_payment_id"),rs.getLong("expected_amount_minor"),rs.getString("expected_currency"),rs.getObject("policy_snapshot_id",UUID.class),rs.getInt("policy_snapshot_version"),rs.getObject("merchant_account_link_id",UUID.class),rs.getInt("merchant_account_link_version"),rs.getString("merchant_account_link_hash").strip(),rs.getString("external_customer_reference"),rs.getString("current_status"));}
    private Intent intent(ResultSet rs,int row)throws SQLException{
        Number confidence=(Number)rs.getObject("confidence");
        return new Intent(rs.getObject("lifecycle_intent_id",UUID.class),rs.getObject("buyer_actor_id",UUID.class),rs.getObject("thread_id",UUID.class),rs.getObject("merchant_finalization_id",UUID.class),Action.valueOf(rs.getString("action_type")),rs.getString("target_scope"),Resolution.valueOf(rs.getString("resolution_status")),rs.getString("source_text_hash").strip(),mapper.readTree(rs.getString("source_evidence")),confidence==null?null:confidence.doubleValue(),instant(rs,"created_at"));}
    private Proposal proposal(ResultSet rs,int row)throws SQLException{return new Proposal(rs.getObject("lifecycle_proposal_id",UUID.class),rs.getObject("lifecycle_intent_id",UUID.class),rs.getObject("lifecycle_policy_evaluation_id",UUID.class),rs.getObject("buyer_actor_id",UUID.class),rs.getObject("thread_id",UUID.class),rs.getObject("merchant_id",UUID.class),rs.getObject("merchant_finalization_id",UUID.class),rs.getObject("original_proposal_id",UUID.class),rs.getObject("original_execution_id",UUID.class),rs.getObject("original_payment_control_id",UUID.class),Action.valueOf(rs.getString("action_type")),(Long)rs.getObject("refundable_amount_minor"),rs.getString("currency"),rs.getObject("policy_snapshot_id",UUID.class),rs.getInt("policy_snapshot_version"),rs.getObject("merchant_account_link_id",UUID.class),rs.getInt("merchant_account_link_version"),mapper.readTree(rs.getString("canonical_material")),rs.getString("proposal_hash").strip(),instant(rs,"created_at"),instant(rs,"expires_at"));}
    private Authorization authorization(ResultSet rs,int row)throws SQLException{return new Authorization(rs.getObject("lifecycle_authorization_id",UUID.class),rs.getObject("buyer_actor_id",UUID.class),rs.getString("session_binding_hash").strip(),rs.getObject("lifecycle_proposal_id",UUID.class),rs.getString("lifecycle_proposal_hash").strip(),Action.valueOf(rs.getString("action_type")),rs.getString("decision"),rs.getString("authorization_hash").strip(),instant(rs,"issued_at"),instant(rs,"expires_at"),instant(rs,"consumed_at"));}
    private Execution execution(ResultSet rs,int row)throws SQLException{return new Execution(rs.getObject("lifecycle_execution_id",UUID.class),rs.getObject("lifecycle_proposal_id",UUID.class),rs.getObject("lifecycle_authorization_id",UUID.class),rs.getObject("buyer_actor_id",UUID.class),rs.getObject("merchant_id",UUID.class),rs.getObject("merchant_finalization_id",UUID.class),Action.valueOf(rs.getString("action_type")),rs.getString("merchant_operation_id"),rs.getString("state"),rs.getString("response_reference"),instant(rs,"created_at"),instant(rs,"updated_at"));}
    private RefundExecution refund(ResultSet rs,int row)throws SQLException{return new RefundExecution(rs.getObject("refund_execution_id",UUID.class),rs.getObject("lifecycle_proposal_id",UUID.class),rs.getString("lifecycle_proposal_hash").strip(),rs.getObject("lifecycle_execution_id",UUID.class),rs.getObject("payment_control_id",UUID.class),rs.getObject("buyer_actor_id",UUID.class),rs.getObject("merchant_id",UUID.class),rs.getString("provider_payment_id"),rs.getLong("amount_minor"),rs.getString("currency"),rs.getString("provider_idempotency_key"),mapper.readTree(rs.getString("request_body")),rs.getString("request_hash").strip(),RefundState.valueOf(rs.getString("state")),rs.getString("provider_refund_id"),rs.getInt("attempt_count"),instant(rs,"next_attempt_at"),instant(rs,"deadline_at"),rs.getString("last_error_code"),instant(rs,"created_at"),instant(rs,"updated_at"));}
    private static OffsetDateTime utc(Instant v){return v==null?null:v.atOffset(ZoneOffset.UTC);}private static Instant instant(ResultSet rs,String c)throws SQLException{OffsetDateTime v=rs.getObject(c,OffsetDateTime.class);return v==null?null:v.toInstant();}
}
