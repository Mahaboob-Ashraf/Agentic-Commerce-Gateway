package dev.agenticcommerce.gateway;

import static dev.agenticcommerce.gateway.lifecycle.AutoBuyModels.*;
import static dev.agenticcommerce.gateway.lifecycle.LifecycleModels.*;
import static dev.agenticcommerce.gateway.onboarding.OnboardingModels.*;
import static org.assertj.core.api.Assertions.*;
import dev.agenticcommerce.gateway.agentization.service.AgentizationGoalService;
import dev.agenticcommerce.gateway.lifecycle.*;
import dev.agenticcommerce.gateway.onboarding.*;
import java.time.Instant;import java.util.*;import java.util.concurrent.*;
import org.junit.jupiter.api.Test;import org.springframework.beans.factory.annotation.Autowired;import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import dev.agenticcommerce.gateway.agentization.service.CanonicalJsonService;

@Import(Task011BuyerLifecycleIntegrationTest.LifecycleFakes.class)
class Task011BuyerLifecycleIntegrationTest extends Task010PaymentControlIntegrationTest {
 @Autowired OnboardingRepository onboardingRepository;@Autowired LifecycleService lifecycle;@Autowired LifecycleRepository lifecycleRepository;
 @Autowired RefundService refunds;@Autowired AutoBuyService autobuy;@Autowired AgentizationGoalService goals;@Autowired DemoLifecycleGateway lifecycleGateway;
 @Autowired ReorderService reorders;@Autowired CanonicalJsonService canonical;

 @Test void onboardingIsOwnedVersionedAndNeverPersistsRawMerchantPassword(){
  Fixture fixture=fixture("onboarding");var status=onboarding.status(fixture.buyer().id());assertThat(status.ready()).isTrue();
  assertThat(onboarding.links(fixture.buyer().id())).singleElement().satisfies(link->{assertThat(link.status()).isEqualTo(LinkStatus.LINKED);assertThat(link.delegatedCredentialReference()).startsWith("credential_");});
  assertThat(jdbc.sql("SELECT merchant_account_link::text FROM merchant_account_link LIMIT 1").query(String.class).single()).doesNotContain("demo-password");
  assertThat(jdbc.sql("SELECT COALESCE(string_agg(evidence::text,''),'') FROM lifecycle_audit_event").query(String.class).single()).doesNotContain("demo-password");
  var other=actors.create("other-onboarding@test",dev.agenticcommerce.gateway.identity.model.PlatformRole.BUYER);
  assertThatThrownBy(()->onboarding.selectAddress(other.id(),status.selectedAddressId())).isInstanceOf(OnboardingException.class);
 }

 @Test void savedAddressEditDoesNotMutateSnapshotAndChangesNewProposalAuthority(){
  Ready first=ready("snapshot-a");UUID snapshot=jdbc.sql("SELECT fulfilment_snapshot_id FROM transaction_proposal_fulfilment WHERE proposal_id=:p").param("p",first.proposalId()).query(UUID.class).single();
  String before=jdbc.sql("SELECT snapshot_hash FROM fulfilment_snapshot WHERE fulfilment_snapshot_id=:id").param("id",snapshot).query(String.class).single();
  UUID address=jdbc.sql("SELECT source_address_id FROM fulfilment_snapshot WHERE fulfilment_snapshot_id=:id").param("id",snapshot).query(UUID.class).single();
  onboarding.updateAddress(first.buyerId(),address,new AddressInput("HOME","Changed Buyer","+919900000002","99 Changed Road",null,"Other","Bengaluru","Karnataka","560002"));
  assertThat(jdbc.sql("SELECT snapshot_hash FROM fulfilment_snapshot WHERE fulfilment_snapshot_id=:id").param("id",snapshot).query(String.class).single()).isEqualTo(before);
  assertThat(jdbc.sql("SELECT address_line_1 FROM fulfilment_snapshot WHERE fulfilment_snapshot_id=:id").param("id",snapshot).query(String.class).single()).isEqualTo("1 Demo Street");
 }

 @Test void revokedLinkFailsClosedAtPurchaseExecutionGate(){Ready ready=ready("revoked");UUID link=jdbc.sql("SELECT merchant_account_link_id FROM transaction_proposal_fulfilment WHERE proposal_id=:p").param("p",ready.proposalId()).query(UUID.class).single();onboarding.revoke(ready.buyerId(),link);
  assertThat(gate.reserve(ready.buyerId(),ready.proposalId(),authorizations.bindSession("revoked-session")).reasonCode()).isEqualTo("PROPOSAL_HASH_MISMATCH");}

 @Test void partialReturnIsTypedUnsupportedAndForeignOrderCannotResolve(){Fulfilled f=fulfilled("partial");
  Intent partial=lifecycle.compile(f.ready().buyerId(),f.ready().threadId(),"Return the second item");assertThat(partial.resolution()).isEqualTo(Resolution.UNSUPPORTED);assertThat(partial.targetScope()).isEqualTo("PARTIAL_UNSUPPORTED");
  Intent foreign=lifecycle.compile(f.ready().buyerId(),f.ready().threadId(),"cancel order_not_owned");assertThat(foreign.finalizationId()).isEqualTo(f.finalizationId());
  assertThat(foreign.evidence().path("ownershipChecked").asBoolean()).isTrue();}

 @Test void fullReturnThenRefundRequiresVerifiedProcessedEvidenceAndStableIdentity(){Fulfilled f=fulfilled("refund");enableLifecycle(f.ready().merchantId());installPolicies(f.ready().merchantId());deliver(f);
  Intent returning=lifecycle.compile(f.ready().buyerId(),f.ready().threadId(),"Return this order");Proposal rp=lifecycle.propose(f.ready().buyerId(),returning.id());
  assertThat(canonical.hash(rp.material())).isEqualTo(rp.hash());assertThatThrownBy(()->jdbc.sql("UPDATE lifecycle_proposal SET currency='USD' WHERE lifecycle_proposal_id=:id").param("id",rp.id()).update()).isInstanceOf(RuntimeException.class);
  lifecycle.authorize(f.ready().buyerId(),rp.id(),"return-session",true);Execution re=lifecycle.execute(f.ready().buyerId(),rp.id(),"return-session");assertThat(re.state()).isEqualTo("SUCCEEDED");
  assertThat(lifecycle.advanceReturn(f.ready().buyerId(),f.finalizationId())).isEqualTo("RETURN_APPROVED");assertThat(lifecycle.advanceReturn(f.ready().buyerId(),f.finalizationId())).isEqualTo("RETURN_RECEIVED");
  Intent refundIntent=lifecycle.compile(f.ready().buyerId(),f.ready().threadId(),"Refund this purchase");Proposal refundProposal=lifecycle.propose(f.ready().buyerId(),refundIntent.id());assertThat(refundProposal.refundableAmountMinor()).isEqualTo(36_000);
  lifecycle.authorize(f.ready().buyerId(),refundProposal.id(),"refund-session",true);lifecycle.execute(f.ready().buyerId(),refundProposal.id(),"refund-session");RefundExecution reserved=lifecycle.state(f.ready().buyerId(),refundIntent.id()).refund();
  worker.dispatch();RefundExecution pending=lifecycle.state(f.ready().buyerId(),refundIntent.id()).refund();assertThat(pending.state()).isEqualTo(RefundState.REFUND_PENDING);assertThat(provider.lastRefundCommand.idempotencyKey()).isEqualTo(reserved.idempotencyKey());assertThat(provider.lastRefundCommand.amountMinor()).isEqualTo(36_000);
  provider.refundStatus="processed";RefundExecution done=refunds.reconcile(f.ready().buyerId(),refundProposal.id());assertThat(done.state()).isEqualTo(RefundState.REFUNDED);
  assertThat(jdbc.sql("SELECT reserved_amount_minor=0 AND completed_amount_minor=36000 FROM refund_ledger WHERE payment_control_id=:id").param("id",done.paymentControlId()).query(Boolean.class).single()).isTrue();
  assertThatThrownBy(()->{Intent again=lifecycle.compile(f.ready().buyerId(),f.ready().threadId(),"Refund this purchase");Proposal p=lifecycle.propose(f.ready().buyerId(),again.id());lifecycle.authorize(f.ready().buyerId(),p.id(),"again",true);lifecycle.execute(f.ready().buyerId(),p.id(),"again");}).isInstanceOf(LifecycleException.class);
 }

 @Test void concurrentFullRefundReservationsSerializeOnPostgresqlLedgerLock() throws Exception {Fulfilled f=fulfilled("refund-race");enableLifecycle(f.ready().merchantId());installPolicies(f.ready().merchantId());
  var context=lifecycleRepository.context(f.ready().buyerId(),f.finalizationId()).orElseThrow();lifecycleRepository.observe(context,"CANCELLED","TRUSTED_DEMO_FIXTURE","cancelled-for-race",hashForTask011("cancelled-for-race"),Instant.now());
  Proposal first=lifecycle.propose(f.ready().buyerId(),lifecycle.compile(f.ready().buyerId(),f.ready().threadId(),"Refund this purchase").id());
  Proposal second=lifecycle.propose(f.ready().buyerId(),lifecycle.compile(f.ready().buyerId(),f.ready().threadId(),"Refund this order").id());
  lifecycle.authorize(f.ready().buyerId(),first.id(),"refund-race-a",true);lifecycle.authorize(f.ready().buyerId(),second.id(),"refund-race-b",true);
  ExecutorService executor=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
  try{List<Future<Boolean>> attempts=List.of(
    executor.submit(()->{start.await();try{lifecycle.execute(f.ready().buyerId(),first.id(),"refund-race-a");return true;}catch(LifecycleException expected){return false;}}),
    executor.submit(()->{start.await();try{lifecycle.execute(f.ready().buyerId(),second.id(),"refund-race-b");return true;}catch(LifecycleException expected){return false;}}));
   start.countDown();assertThat(attempts.stream().map(v->{try{return v.get(10,TimeUnit.SECONDS);}catch(Exception e){throw new AssertionError(e);}}).filter(Boolean::booleanValue)).hasSize(1);
  }finally{executor.shutdownNow();}
  assertThat(jdbc.sql("SELECT count(*)::int FROM refund_execution WHERE payment_control_id=:id").param("id",context.paymentControlId()).query(Integer.class).single()).isOne();
  assertThat(jdbc.sql("SELECT reserved_amount_minor+completed_amount_minor<=captured_refundable_amount_minor FROM refund_ledger WHERE payment_control_id=:id").param("id",context.paymentControlId()).query(Boolean.class).single()).isTrue();
 }

 @Test void cancellationIsIdempotentAndDoesNotClaimRefund(){Fulfilled f=fulfilled("cancel");enableLifecycle(f.ready().merchantId());installPolicies(f.ready().merchantId());Intent i=lifecycle.compile(f.ready().buyerId(),f.ready().threadId(),"Cancel this order");Proposal p=lifecycle.propose(f.ready().buyerId(),i.id());lifecycle.authorize(f.ready().buyerId(),p.id(),"cancel-session",true);Execution first=lifecycle.execute(f.ready().buyerId(),p.id(),"cancel-session");Execution duplicate=lifecycle.execute(f.ready().buyerId(),p.id(),"cancel-session");assertThat(duplicate.id()).isEqualTo(first.id());assertThat(lifecycleGateway.cancelCalls).isOne();assertThat(lifecycle.state(f.ready().buyerId(),i.id()).refund()).isNull();}

 @Test void reorderCreatesAnEntirelyFreshProposal(){Fulfilled f=fulfilled("reorder");Intent i=lifecycle.compile(f.ready().buyerId(),f.ready().threadId(),"Order the same thing again");var result=reorders.reorder(f.ready().buyerId(),i.id(),authorizations.bindSession("reorder-session"));assertThat(result.newThreadId()).isNotEqualTo(f.ready().threadId());assertThat(result.newProposalId()).isNotEqualTo(f.ready().proposalId());assertThat(result.newProposalHash()).isNotBlank();}

 @Test void explicitAutoBuyTriggerIsIdempotentAndPriceGuardPausesWithoutMoneyExecution(){Fixture fixture=fixture("autobuy");UUID address=onboarding.status(fixture.buyer().id()).selectedAddressId();UUID link=onboarding.links(fixture.buyer().id()).getFirst().id();var products=mapper.createObjectNode().put("intentText",canonicalTextForTask011());PlanView plan=autobuy.create(fixture.buyer().id(),new PlanInput(fixture.merchant().id(),link,address,products,1,"Buyer presses evaluate","EXACT_ONLY",mapper.createObjectNode().put("peanut","PROHIBITED")));
  Evaluation first=autobuy.evaluate(fixture.buyer().id(),plan.plan().id(),"trigger-1",authorizations.bindSession("auto-session"));Evaluation duplicate=autobuy.evaluate(fixture.buyer().id(),plan.plan().id(),"trigger-1",authorizations.bindSession("auto-session"));assertThat(duplicate.id()).isEqualTo(first.id());assertThat(autobuy.evaluation(fixture.buyer().id(),plan.plan().id(),"trigger-1").id()).isEqualTo(first.id());assertThat(first.outcome()).isEqualTo(Outcome.PAUSED);assertThat(first.reasonCode()).isEqualTo("PRICE_EXCEEDS_PLAN_MAXIMUM");assertThat(first.executionId()).isNull();assertThat(jdbc.sql("SELECT count(*)::int FROM autobuy_evaluation").query(Integer.class).single()).isOne();}

 @Test void oneHighLevelAgentizationGoalOwnsIsolatedCapabilityTargets(){Fixture fixture=fixture("goal");UUID artifact=jdbc.sql("SELECT source_artifact_id FROM agentization_run WHERE merchant_id=:m LIMIT 1").param("m",fixture.merchant().id()).query(UUID.class).single();var goal=goals.start(fixture.admin().id(),fixture.merchant().id(),new AgentizationGoalService.StartGoal(artifact,80));assertThat(goal.targets()).hasSize(8);var advanced=goals.advance(fixture.admin().id(),fixture.merchant().id(),goal.goal().id());assertThat(advanced.targets().stream().filter(t->"READY".equals(t.status())).count()).isGreaterThanOrEqualTo(3);assertThat(advanced.targets().stream().filter(t->"IN_PROGRESS".equals(t.status())).count()).isEqualTo(1);}

 private Fulfilled fulfilled(String key){Ready ready=confirmed(key);worker.dispatch();UUID finalization=jdbc.sql("SELECT merchant_finalization_id FROM merchant_finalization WHERE execution_id=:e AND state='FULFILLED'").param("e",ready.executionId()).query(UUID.class).single();return new Fulfilled(ready,finalization);}
 private void deliver(Fulfilled f){var context=lifecycleRepository.context(f.ready().buyerId(),f.finalizationId()).orElseThrow();lifecycleRepository.observe(context,"DELIVERED","TRUSTED_DEMO_FIXTURE","delivery-"+f.finalizationId(),hashForTask011("delivered-"+f.finalizationId()),Instant.now());}
 private void enableLifecycle(UUID merchant){UUID manifest=jdbc.sql("SELECT manifest_id FROM agent_commerce_manifest WHERE merchant_id=:m ORDER BY manifest_version DESC LIMIT 1").param("m",merchant).query(UUID.class).single();UUID run=jdbc.sql("SELECT agentization_run_id FROM agent_commerce_manifest WHERE manifest_id=:id").param("id",manifest).query(UUID.class).single();UUID snapshot=jdbc.sql("SELECT policy_snapshot_id FROM agent_commerce_manifest WHERE manifest_id=:id").param("id",manifest).query(UUID.class).single();UUID mapping=jdbc.sql("SELECT executable_mapping_proposal_id FROM agent_commerce_manifest_capability WHERE manifest_id=:id AND capability='GET_QUOTE'").param("id",manifest).query(UUID.class).single();for(String capability:List.of("GET_ORDER_STATE","CANCEL_ORDER","RETURN_ITEM","REFUND")){UUID eval=jdbc.sql("INSERT INTO capability_readiness_evaluation(merchant_id,agentization_run_id,capability,readiness,mapping_proposal_id,mapping_version,mapping_content_hash,policy_snapshot_id,required_evidence,satisfied_evidence,missing_requirements,blocking_evidence,evidence_references,evaluation_hash) VALUES(:m,:r,:c,'READY',:mapping,1,:mappingHash,:snapshot,'[]','[]','[]','[]','[]',:hash) RETURNING readiness_evaluation_id").param("m",merchant).param("r",run).param("c",capability).param("mapping",mapping).param("mappingHash",hashForTask011("mapping"+capability)).param("snapshot",snapshot).param("hash",hashForTask011("ready"+capability+merchant)).query(UUID.class).single();jdbc.sql("INSERT INTO agent_commerce_manifest_capability(manifest_id,merchant_id,capability,advertised,readiness,executable_mapping_proposal_id,readiness_evaluation_id) VALUES(:manifest,:merchant,:capability,TRUE,'READY',:mapping,:eval) ON CONFLICT(manifest_id,capability) DO NOTHING").param("manifest",manifest).param("merchant",merchant).param("capability",capability).param("mapping",mapping).param("eval",eval).update();}}
 private void installPolicies(UUID merchant){UUID snapshot=jdbc.sql("SELECT policy_snapshot_id FROM agent_commerce_manifest WHERE merchant_id=:m ORDER BY manifest_version DESC LIMIT 1").param("m",merchant).query(UUID.class).single();UUID actor=jdbc.sql("SELECT published_by_actor_id FROM merchant_policy_snapshot WHERE policy_snapshot_id=:s").param("s",snapshot).query(UUID.class).single();int n=0;for(var rule:Map.of("CANCEL_ORDER","CANCELLATION_WINDOW","RETURN_ORDER","RETURN_WINDOW","REFUND_ORDER","REFUND_ELIGIBILITY").entrySet()){n++;String h=hashForTask011("policy"+rule.getKey()+merchant);UUID doc=jdbc.sql("INSERT INTO policy_document(merchant_id,document_type,document_version,title,normalized_content,content_hash,uploaded_by_actor_id) VALUES(:m,'GENERAL_COMMERCE',:v,:title,:content,:hash,:actor) RETURNING policy_document_id").param("m",merchant).param("v",n).param("title",rule.getKey()).param("content","Allows "+rule.getKey()).param("hash",hashForTask011("doc"+rule.getKey()+merchant)).param("actor",actor).query(UUID.class).single();UUID id=jdbc.sql("INSERT INTO proposed_policy_rule(merchant_id,policy_document_id,document_version,rule_version,rule_type,source_clause,applicability_conditions,outcome_effect,precedence_priority,extraction_provider,extraction_model,rule_content_hash,approval_state) VALUES(:m,:doc,:v,1,:type,'trusted demo policy',CAST(:condition AS jsonb),'{\"eligible\":true}',100,'TEST','fixture',:hash,'APPROVED') RETURNING policy_rule_id").param("m",merchant).param("doc",doc).param("v",n).param("type",rule.getValue()).param("condition","{\"action\":\""+rule.getKey()+"\"}").param("hash",h).query(UUID.class).single();jdbc.sql("INSERT INTO merchant_policy_snapshot_rule(policy_snapshot_id,merchant_id,policy_rule_id,rule_version,rule_content_hash) VALUES(:snapshot,:merchant,:rule,1,:hash)").param("snapshot",snapshot).param("merchant",merchant).param("rule",id).param("hash",h).update();}}
 private static String canonicalTextForTask011(){return "500 ke andar do logon ke liye high-protein vegetarian snacks order karo, peanuts bilkul nahi.";}private static String hashForTask011(String value){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
 record Fulfilled(Ready ready,UUID finalizationId){}
 @TestConfiguration static class LifecycleFakes{@Bean @Primary DemoLifecycleGateway merchantLifecycleGateway(){return new DemoLifecycleGateway();}}
 static class DemoLifecycleGateway implements MerchantLifecycleGateway {int cancelCalls;@Override public Result cancel(UUID m,String o,String op,String c){cancelCalls++;return new Result(true,false,"CANCELLED","cancelled",hashForTask011(op),null);}@Override public Result requestFullReturn(UUID m,String o,String op,String c){return new Result(true,false,"RETURN_REQUESTED","return",hashForTask011(op),null);}}
}
