package dev.agenticcommerce.gateway.lifecycle;

import static dev.agenticcommerce.gateway.lifecycle.LifecycleModels.*;
import dev.agenticcommerce.gateway.agentization.service.CanonicalJsonService;
import dev.agenticcommerce.gateway.payment.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefundService {
    private static final long[] RETRY_SECONDS={0,2,5,15,30};
    private final LifecycleRepository repository;
    private final PaymentRepository payments;
    private final PaymentProvider provider;
    private final CanonicalJsonService canonical;
    private final LifecycleAuditService audit;
    public RefundService(LifecycleRepository repository,PaymentRepository payments,PaymentProvider provider,
            CanonicalJsonService canonical,LifecycleAuditService audit){this.repository=repository;this.payments=payments;this.provider=provider;this.canonical=canonical;this.audit=audit;}

    public void process(PaymentRepository.OutboxItem item){
        if(item.refundExecutionId()==null){payments.failOutbox(item.id(),false,"REFUND_REFERENCE_MISSING",Instant.now(),Instant.now());return;}
        RefundExecution refund=repository.refund(item.refundExecutionId()).orElse(null);
        if(refund==null){payments.failOutbox(item.id(),false,"REFUND_NOT_FOUND",Instant.now(),Instant.now());return;}
        if(refund.state()==RefundState.REFUNDED||refund.state()==RefundState.REFUND_FAILED){payments.completeOutbox(item.id(),Instant.now());return;}
        Instant now=Instant.now();
        if(refund.attemptCount()>=5||!refund.deadlineAt().isAfter(now)){repository.refundFailure(refund.id(),"REFUND_ATTEMPT_BUDGET_EXHAUSTED",false,now,now);payments.failOutbox(item.id(),false,"REFUND_ATTEMPT_BUDGET_EXHAUSTED",now,now);return;}
        try{
            repository.startRefundAttempt(refund.id(),now);
            byte[] body=canonical.canonicalize(refund.requestBody()).getBytes(StandardCharsets.UTF_8);
            audit.record(refund.buyerId(),refund.merchantId(),null,"REFUND_PROVIDER_REQUESTED",refund.id().toString(),
                    audit.reference("requestHash",refund.requestHash()));
            PaymentProvider.ProviderRefund evidence=provider.createRefund(new PaymentProvider.CreateRefundCommand(
                    refund.providerPaymentId(),refund.amountMinor(),refund.currency(),refund.idempotencyKey(),body));
            accept(refund,evidence,"CREATE_RESPONSE","attempt-"+(refund.attemptCount()+1),now);
            audit.record(refund.buyerId(),refund.merchantId(),null,"REFUND_PROVIDER_RESPONSE",refund.id().toString(),
                    audit.reference("providerEvidenceHash",evidence.evidenceHash()));
            RefundExecution reduced=repository.reduceRefund(refund.id(),now);
            if(reduced.state()==RefundState.REFUNDED)audit.record(refund.buyerId(),refund.merchantId(),null,"REFUND_FINALIZED",refund.id().toString(),
                    audit.reference("state",reduced.state().name()));
            if(reduced.state()==RefundState.REFUNDED||reduced.state()==RefundState.REFUND_FAILED)payments.completeOutbox(item.id(),now);
            else retry(item,refund,"REFUND_PROVIDER_PENDING",now);
        }catch(PaymentProviderException ex){retry(item,refund,"REFUND_PROVIDER_"+ex.category().name(),now);}
        catch(RuntimeException ex){retry(item,refund,"REFUND_PROCESSING_FAILURE",now);}
    }

    @Transactional public RefundExecution reconcile(UUID buyer,UUID lifecycleProposal){
        RefundExecution refund=repository.refundForProposal(buyer,lifecycleProposal)
                .orElseThrow(()->new LifecycleException("REFUND_EXECUTION_NOT_FOUND",HttpStatus.NOT_FOUND,"Refund execution was not found"));
        if(refund.providerRefundId()==null)return refund;
        PaymentProvider.ProviderRefund evidence=provider.fetchRefund(refund.providerPaymentId(),refund.providerRefundId());
        accept(refund,evidence,"API_RECONCILIATION","reconcile-"+evidence.evidenceHash(),Instant.now());
        RefundExecution reduced=repository.reduceRefund(refund.id(),Instant.now());
        audit.record(refund.buyerId(),refund.merchantId(),null,"REFUND_RECONCILED",refund.id().toString(),
                audit.reference("state",reduced.state().name()));return reduced;
    }

    @Transactional(noRollbackFor=LifecycleException.class) public boolean ingestWebhook(JsonNodeRefund value,String eventId){
        RefundExecution refund=repository.refundByProvider(value.paymentId(),value.refundId())
                .or(()->repository.uniquePendingRefund(value.paymentId(),value.amountMinor())).orElse(null);
        if(refund==null)return false;
        PaymentProvider.ProviderRefund evidence=new PaymentProvider.ProviderRefund(value.refundId(),value.paymentId(),
                value.amountMinor(),value.currency(),value.status(),value.observedAt(),value.accountReference(),value.evidenceHash());
        accept(refund,evidence,"WEBHOOK",eventId,value.observedAt());RefundExecution reduced=repository.reduceRefund(refund.id(),Instant.now());
        audit.record(refund.buyerId(),refund.merchantId(),null,"REFUND_WEBHOOK_REDUCED",refund.id().toString(),
                audit.reference("state",reduced.state().name()));return true;
    }

    private void accept(RefundExecution refund,PaymentProvider.ProviderRefund evidence,String source,String reference,Instant now){
        var config=repository.paymentConfiguration(refund.paymentControlId());
        if(!refund.providerPaymentId().equals(evidence.paymentId())||refund.amountMinor()!=evidence.amountMinor()
                ||!refund.currency().equals(evidence.currency())||!config.account().equals(evidence.accountReference()))
            throw new LifecycleException("REFUND_EVIDENCE_MISMATCH",HttpStatus.CONFLICT,"Refund evidence does not match reserved authority");
        repository.saveRefundEvidence(refund,config.id(),evidence.id(),evidence.paymentId(),evidence.amountMinor(),
                evidence.currency(),evidence.status(),evidence.accountReference(),source,reference,evidence.evidenceHash(),now);
    }
    private void retry(PaymentRepository.OutboxItem item,RefundExecution refund,String code,Instant now){
        int attempt=Math.min(refund.attemptCount()+1,5);boolean retryable=attempt<5&&refund.deadlineAt().isAfter(now);
        long delay=RETRY_SECONDS[Math.min(attempt,RETRY_SECONDS.length-1)];Instant next=now.plusSeconds(delay);
        repository.refundFailure(refund.id(),code,retryable,next,now);payments.failOutbox(item.id(),retryable,code,next,now);
    }
    public record JsonNodeRefund(String refundId,String paymentId,long amountMinor,String currency,String status,
            Instant observedAt,String accountReference,String evidenceHash){}
}
