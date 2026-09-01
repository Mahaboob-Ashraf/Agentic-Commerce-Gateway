package dev.agenticcommerce.gateway.lifecycle;

import static dev.agenticcommerce.gateway.agentization.authority.AuthorityModels.*;
import static dev.agenticcommerce.gateway.lifecycle.LifecycleModels.*;
import dev.agenticcommerce.gateway.agentization.authority.PolicyAuthorityService;
import dev.agenticcommerce.gateway.agentization.service.CanonicalJsonService;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.*;

@Service
public class LifecycleService {
    private final LifecycleRepository repository;private final PolicyAuthorityService policies;
    private final CanonicalJsonService canonical;private final ObjectMapper mapper;private final MerchantLifecycleGateway gateway;
    private final LifecycleAuditService audit;
    public LifecycleService(LifecycleRepository r,PolicyAuthorityService p,CanonicalJsonService c,ObjectMapper m,
            MerchantLifecycleGateway g,LifecycleAuditService audit){repository=r;policies=p;canonical=c;mapper=m;gateway=g;this.audit=audit;}

    @Transactional public Intent compile(UUID buyer,UUID thread,String text){
        String normalized=text==null?"":text.strip();if(normalized.isBlank()||normalized.length()>2000)throw bad("LIFECYCLE_TEXT_INVALID");
        Action action=action(normalized);boolean partial=action==Action.RETURN_ORDER&&normalized.toLowerCase(Locale.ROOT).matches(".*(second|item|one product|ek item).*" );
        List<LifecycleRepository.OrderContext> orders=repository.ownedOrders(buyer,thread);LifecycleRepository.OrderContext target=null;
        for(var order:orders)if(normalized.contains(order.merchantOrderId())||normalized.contains(order.finalizationId().toString())){target=order;break;}
        if(target==null&&orders.size()==1)target=orders.getFirst();
        Resolution resolution=partial?Resolution.UNSUPPORTED:target==null?Resolution.CLARIFICATION_REQUIRED:Resolution.RESOLVED;
        String scope=partial?"PARTIAL_UNSUPPORTED":target==null?"UNRESOLVED":"FULL_ORDER";
        var evidence=mapper.createObjectNode();evidence.put("compiler","DETERMINISTIC_LIFECYCLE_V1");
        evidence.put("candidateCount",orders.size());evidence.put("ownershipChecked",true);evidence.put("rawTextRetained",false);
        if(target!=null)evidence.put("resolvedFinalizationId",target.finalizationId().toString());
        Intent intent=repository.insertIntent(buyer,thread,target==null?null:target.finalizationId(),action,scope,resolution,
                canonical.hashText(normalized),evidence,target==null?0.0:1.0,Instant.now());
        var auditEvidence=mapper.createObjectNode().put("actionType",action.name()).put("resolution",resolution.name())
                .put("sourceTextHash",intent.sourceTextHash());
        audit.record(buyer,target==null?null:target.merchantId(),thread,"LIFECYCLE_INTENT_RESOLVED",intent.id().toString(),auditEvidence);
        return intent;}

    public Proposal propose(UUID buyer,UUID intentId){
        Proposal existing=repository.proposalForIntent(buyer,intentId).orElse(null);if(existing!=null)return existing;
        Intent intent=requireIntent(buyer,intentId);if(intent.resolution()!=Resolution.RESOLVED)throw conflict("LIFECYCLE_TARGET_UNRESOLVED");
        if(intent.action()==Action.TRACK_ORDER||intent.action()==Action.REORDER||intent.action()==Action.REPLACE_ITEM)throw conflict("LIFECYCLE_PROPOSAL_NOT_APPLICABLE");
        var c=requireContext(buyer,intent.finalizationId());String capability=capability(intent.action());
        if(!repository.capabilityReady(c.merchantId(),capability))throw conflict("LIFECYCLE_CAPABILITY_NOT_READY");
        PolicyResolution policy=policies.resolve(c.merchantId(),c.policySnapshotId(),new PolicyResolutionRequest(
                policyAction(intent.action()),0,c.status(),Instant.now()));
        PolicyOutcome outcome=PolicyOutcome.valueOf(policy.outcome().name());
        var refs=policy.ruleIds().stream().map(v->"policy-rule:"+v).toList();Instant now=Instant.now();
        var evalMaterial=mapper.createObjectNode();evalMaterial.put("intentId",intent.id().toString());
        evalMaterial.put("policySnapshotId",c.policySnapshotId().toString());evalMaterial.put("outcome",outcome.name());
        evalMaterial.put("reason",policy.reasonCode());
        UUID evaluation=repository.insertPolicyEvaluation(intent,c,outcome,policy.reasonCode(),refs,canonical.hash(evalMaterial),now);
        var policyAudit=mapper.createObjectNode().put("policyEvaluationId",evaluation.toString())
                .put("policySnapshotId",c.policySnapshotId().toString()).put("policySnapshotVersion",c.policyVersion())
                .put("outcome",outcome.name()).put("reasonCode",policy.reasonCode());
        audit.record(buyer,c.merchantId(),intent.threadId(),"LIFECYCLE_POLICY_EVALUATED",evaluation.toString(),policyAudit);
        if(outcome!=PolicyOutcome.PASS)throw conflict("HISTORICAL_POLICY_"+outcome.name());
        if(!eligible(intent.action(),c.status()))throw conflict("MERCHANT_ORDER_STATE_INELIGIBLE");
        Long amount=intent.action()==Action.REFUND_ORDER?c.capturedAmount():null;
        var material=mapper.createObjectNode();material.put("schemaVersion",1);material.put("buyerActorId",buyer.toString());
        material.put("threadId",intent.threadId().toString());material.put("merchantId",c.merchantId().toString());
        material.put("merchantFinalizationId",c.finalizationId().toString());material.put("merchantOrderId",c.merchantOrderId());
        material.put("originalProposalId",c.proposalId().toString());material.put("originalExecutionId",c.executionId().toString());
        material.put("paymentControlId",c.paymentControlId().toString());material.put("providerPaymentId",c.providerPaymentId());
        material.put("actionType",intent.action().name());material.put("targetScope","FULL_ORDER");
        if(amount==null)material.putNull("refundableAmountMinor");else material.put("refundableAmountMinor",amount);
        material.put("currency",c.currency());material.put("policySnapshotId",c.policySnapshotId().toString());
        material.put("policySnapshotVersion",c.policyVersion());material.put("merchantAccountLinkId",c.linkId().toString());
        material.put("merchantAccountLinkVersion",c.linkVersion());material.put("merchantAccountLinkHash",c.linkHash());
        material.put("policyEvaluationHash",canonical.hash(evalMaterial));
        Proposal proposal=repository.insertProposal(intent,c,evaluation,amount,material,canonical.hash(material),now,now.plus(Duration.ofMinutes(10)));
        audit.record(buyer,c.merchantId(),intent.threadId(),"LIFECYCLE_PROPOSAL_CREATED",proposal.id().toString(),
                mapper.createObjectNode().put("proposalHash",proposal.hash()).put("actionType",proposal.action().name()));
        audit.record(buyer,c.merchantId(),intent.threadId(),"LIFECYCLE_REVERSIBILITY_EVALUATED",proposal.id().toString(),
                mapper.createObjectNode().put("outcome","EXPLICIT_CONFIRMATION").put("proposalHash",proposal.hash()));
        return proposal;}

    @Transactional public Authorization authorize(UUID buyer,UUID proposalId,String sessionId,boolean approve){
        Proposal p=requireProposal(buyer,proposalId);Authorization existing=repository.authorization(buyer,proposalId).orElse(null);if(existing!=null)return existing;
        if(!p.expiresAt().isAfter(Instant.now()))throw conflict("LIFECYCLE_PROPOSAL_EXPIRED");
        if(sessionId==null||sessionId.isBlank())throw bad("LIFECYCLE_SESSION_REQUIRED");
        String session=canonical.hashText("transaction-session-binding-v1|"+sessionId);
        Instant now=Instant.now(),expiry=min(p.expiresAt(),now.plus(Duration.ofMinutes(5)));var m=mapper.createObjectNode();
        m.put("buyerActorId",buyer.toString());m.put("sessionBindingHash",session);m.put("lifecycleProposalId",p.id().toString());
        m.put("proposalHash",p.hash());m.put("actionType",p.action().name());m.put("decision",approve?"AUTHORIZED":"DENIED");m.put("expiresAt",expiry.toString());
        Authorization authorization=repository.insertAuthorization(p,session,approve?"AUTHORIZED":"DENIED",canonical.hash(m),now,expiry);
        audit.record(buyer,p.merchantId(),p.threadId(),"LIFECYCLE_AUTHORIZATION_DECIDED",authorization.id().toString(),
                mapper.createObjectNode().put("decision",authorization.decision()).put("authorizationHash",authorization.authorizationHash()));
        return authorization;}

    @Transactional public Execution execute(UUID buyer,UUID proposalId,String sessionId){
        Proposal p=requireProposal(buyer,proposalId);Authorization a=repository.authorization(buyer,proposalId).orElseThrow(()->conflict("LIFECYCLE_AUTHORIZATION_REQUIRED"));
        String bound=canonical.hashText("transaction-session-binding-v1|"+sessionId);if(!a.sessionBindingHash().equals(bound)||!"AUTHORIZED".equals(a.decision())||!a.expiresAt().isAfter(Instant.now())||!a.proposalHash().equals(p.hash())||a.action()!=p.action())throw conflict("LIFECYCLE_AUTHORIZATION_INVALID");
        Execution existing=repository.executionForProposal(buyer,proposalId).orElse(null);if(existing!=null)return existing;
        var c=requireContext(buyer,p.finalizationId());if(!repository.activeHistoricalLink(buyer,c.linkId()))throw conflict("ACTIVE_MERCHANT_LINK_REQUIRED");
        Execution e=repository.reserveExecution(p,a,Instant.now());
        if(p.action()==Action.REFUND_ORDER){var body=mapper.createObjectNode();body.put("amount",c.capturedAmount());
            body.put("currency",c.currency());body.putObject("notes").put("lifecycleProposalId",p.id().toString());
            RefundExecution refund=repository.reserveRefund(p,e,c,body,canonical.hash(body),Instant.now());
            audit.record(buyer,p.merchantId(),p.threadId(),"REFUND_RESERVED",refund.id().toString(),
                    mapper.createObjectNode().put("amountMinor",refund.amountMinor()).put("currency",refund.currency()).put("requestHash",refund.requestHash()));
            return e;}
        MerchantLifecycleGateway.Result result=p.action()==Action.CANCEL_ORDER
                ?gateway.cancel(c.merchantId(),c.merchantOrderId(),e.merchantOperationId(),c.customerReference())
                :gateway.requestFullReturn(c.merchantId(),c.merchantOrderId(),e.merchantOperationId(),c.customerReference());
        repository.completeExecution(e.id(),result.success(),result.retryable(),result.reference(),Instant.now());
        if(result.success())repository.observe(c,p.action()==Action.CANCEL_ORDER?"CANCELLED":"RETURN_REQUESTED",
                "MERCHANT_RESPONSE",e.merchantOperationId(),result.evidenceHash(),Instant.now());
        audit.record(buyer,p.merchantId(),p.threadId(),p.action()==Action.CANCEL_ORDER?"ORDER_CANCEL_ATTEMPT":"FULL_RETURN_ATTEMPT",
                e.id().toString(),mapper.createObjectNode().put("state",result.success()?"SUCCEEDED":result.retryable()?"FAILED_RETRYABLE":"FAILED_TERMINAL")
                        .put("merchantOperationId",e.merchantOperationId()).put("evidenceHash",result.evidenceHash()));
        return repository.executionForProposal(buyer,proposalId).orElseThrow();}

    @Transactional public String advanceReturn(UUID buyer,UUID finalization){var c=requireContext(buyer,finalization);
        String next=switch(c.status()){case "RETURN_REQUESTED"->"RETURN_APPROVED";case "RETURN_APPROVED"->"RETURN_RECEIVED";default->throw conflict("RETURN_STATE_TRANSITION_INVALID");};
        repository.observe(c,next,"TRUSTED_DEMO_FIXTURE","return-"+next.toLowerCase(Locale.ROOT),canonical.hashText(c.finalizationId()+"|"+next),Instant.now());
        audit.record(buyer,c.merchantId(),c.threadId(),"RETURN_STATE_CHANGED",finalization.toString(),mapper.createObjectNode().put("status",next));return next;}
    public OrderTracking track(UUID buyer,UUID finalizationId){var c=requireContext(buyer,finalizationId);
        return new OrderTracking(c.finalizationId(),c.merchantId(),c.merchantOrderId(),c.status(),c.customerReference());}
    public LifecycleState state(UUID buyer,UUID intentId){Intent i=requireIntent(buyer,intentId);Proposal p=repository.proposalForIntent(buyer,intentId).orElse(null);
        Authorization a=p==null?null:repository.authorization(buyer,p.id()).orElse(null);Execution e=p==null?null:repository.executionForProposal(buyer,p.id()).orElse(null);
        RefundExecution r=p==null?null:repository.refundForProposal(buyer,p.id()).orElse(null);String status=i.finalizationId()==null?null:requireContext(buyer,i.finalizationId()).status();return new LifecycleState(i,p,a,e,r,status);}
    public Intent requireIntent(UUID buyer,UUID id){return repository.intent(buyer,id).orElseThrow(()->notFound("LIFECYCLE_INTENT_NOT_FOUND"));}
    public Proposal requireProposal(UUID buyer,UUID id){return repository.proposal(buyer,id).orElseThrow(()->notFound("LIFECYCLE_PROPOSAL_NOT_FOUND"));}
    private LifecycleRepository.OrderContext requireContext(UUID buyer,UUID id){return repository.context(buyer,id).orElseThrow(()->notFound("OWNED_MERCHANT_ORDER_NOT_FOUND"));}
    private static Action action(String t){String s=t.toLowerCase(Locale.ROOT);if(s.contains("cancel"))return Action.CANCEL_ORDER;if(s.contains("return"))return Action.RETURN_ORDER;if(s.contains("refund"))return Action.REFUND_ORDER;if(s.contains("again")||s.contains("reorder")||s.contains("same thing"))return Action.REORDER;if(s.contains("replace"))return Action.REPLACE_ITEM;return Action.TRACK_ORDER;}
    private static String capability(Action a){return switch(a){case CANCEL_ORDER->"CANCEL_ORDER";case RETURN_ORDER->"RETURN_ITEM";case REFUND_ORDER->"REFUND";default->"GET_ORDER_STATE";};}
    private static String policyAction(Action a){return switch(a){case CANCEL_ORDER->"CANCEL_ORDER";case RETURN_ORDER->"RETURN_ORDER";case REFUND_ORDER->"REFUND_ORDER";default->a.name();};}
    private static boolean eligible(Action a,String s){return switch(a){case CANCEL_ORDER->Set.of("PLACED","PROCESSING").contains(s);case RETURN_ORDER->"DELIVERED".equals(s);case REFUND_ORDER->Set.of("CANCELLED","RETURN_RECEIVED").contains(s);default->true;};}
    private static Instant min(Instant a,Instant b){return a.isBefore(b)?a:b;}
    private static LifecycleException bad(String c){return new LifecycleException(c,HttpStatus.BAD_REQUEST,"Lifecycle request is invalid");}private static LifecycleException conflict(String c){return new LifecycleException(c,HttpStatus.CONFLICT,"Lifecycle authority requirement was not satisfied");}private static LifecycleException notFound(String c){return new LifecycleException(c,HttpStatus.NOT_FOUND,"Lifecycle record was not found");}
}
