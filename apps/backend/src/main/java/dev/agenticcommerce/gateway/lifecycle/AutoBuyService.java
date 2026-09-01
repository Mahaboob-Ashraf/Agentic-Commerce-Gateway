package dev.agenticcommerce.gateway.lifecycle;
import static dev.agenticcommerce.gateway.lifecycle.AutoBuyModels.*;
import dev.agenticcommerce.gateway.agentization.service.CanonicalJsonService;
import dev.agenticcommerce.gateway.onboarding.*;
import dev.agenticcommerce.gateway.risk.ReversibilityService;
import dev.agenticcommerce.gateway.intent.BuyerModels.*;import dev.agenticcommerce.gateway.intent.BuyerThreadService;
import java.time.Instant;import java.util.*;
import org.springframework.http.HttpStatus;import org.springframework.stereotype.Service;import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.*;
@Service
public class AutoBuyService {
 private final AutoBuyRepository repository;private final OnboardingRepository onboarding;private final CanonicalJsonService canonical;
 private final ObjectMapper mapper;private final ReorderService purchases;private final ReversibilityService risk;private final LifecycleAuditService audit;private final BuyerThreadService threads;
 public AutoBuyService(AutoBuyRepository r,OnboardingRepository o,CanonicalJsonService c,ObjectMapper m,ReorderService p,ReversibilityService risk,LifecycleAuditService audit,BuyerThreadService threads){repository=r;onboarding=o;canonical=c;mapper=m;purchases=p;this.risk=risk;this.audit=audit;this.threads=threads;}
 @Transactional public PlanView create(UUID buyer,PlanInput input){validate(buyer,input);return repository.create(buyer,input,hash(buyer,input,1),Instant.now());}
 @Transactional public PlanView update(UUID buyer,UUID id,PlanInput input){validate(buyer,input);PlanView current=require(buyer,id);return repository.update(buyer,id,input,hash(buyer,input,current.version().version()+1),Instant.now());}
 public PlanView require(UUID buyer,UUID id){return repository.get(buyer,id).orElseThrow(()->notFound());}public List<PlanView> list(UUID buyer){return repository.list(buyer);}
 public PlanView pause(UUID buyer,UUID id){return repository.status(buyer,id,"PAUSED","BUYER_PAUSED",Instant.now());}
 public PlanView resume(UUID buyer,UUID id){PlanView p=require(buyer,id);validateReferences(buyer,p.version());return repository.status(buyer,id,"ACTIVE",null,Instant.now());}
 public PlanView revoke(UUID buyer,UUID id){return repository.status(buyer,id,"REVOKED","BUYER_REVOKED",Instant.now());}
 public Evaluation evaluation(UUID buyer,UUID id,String trigger){require(buyer,id);return repository.evaluation(buyer,id,trigger).orElseThrow(()->new LifecycleException("AUTOBUY_EVALUATION_NOT_FOUND",HttpStatus.NOT_FOUND,"AutoBuy evaluation was not found"));}
 public Evaluation evaluate(UUID buyer,UUID id,String trigger,String sessionBindingHash){
  if(trigger==null||!trigger.matches("[A-Za-z0-9_-]{1,128}"))throw bad("AUTOBUY_TRIGGER_INVALID");
  Evaluation existing=repository.evaluation(buyer,id,trigger).orElse(null);if(existing!=null)return existing;
  PlanView plan=require(buyer,id);if(plan.plan().status()!=PlanStatus.ACTIVE)return save(plan,trigger,Outcome.BLOCKED,"PLAN_NOT_ACTIVE",null,null);
  String invalid=referenceFailure(buyer,plan.version());if(invalid!=null){repository.status(buyer,id,"PAUSED",invalid,Instant.now());return save(plan,trigger,Outcome.PAUSED,invalid,null,null);}
  String text=plan.version().productConstraints().path("intentText").asText("").strip();if(text.isBlank()){repository.status(buyer,id,"PAUSED","PLAN_INTENT_MISSING",Instant.now());return save(plan,trigger,Outcome.PAUSED,"PLAN_INTENT_MISSING",null,null);}
  try{
   var prepared=purchases.prepareFreshPurchase(buyer,text,null);var proposal=prepared.proposal();
   String bounded=boundedFailure(plan.version(),proposal,threads.certificate(buyer,prepared.threadId()));if(bounded!=null){repository.status(buyer,id,"PAUSED",bounded,Instant.now());
    return save(plan,trigger,Outcome.PAUSED,bounded,proposal.proposalId(),proposal.proposalHash());}
   if(proposal.finalAmountMinor()>plan.version().maximumAmountMinor()){repository.status(buyer,id,"PAUSED","PRICE_EXCEEDS_PLAN_MAXIMUM",Instant.now());
    return save(plan,trigger,Outcome.PAUSED,"PRICE_EXCEEDS_PLAN_MAXIMUM",proposal.proposalId(),proposal.proposalHash());}
   var result=risk.evaluate(buyer,proposal.proposalId(),sessionBindingHash);String out=result.outcome().name();
   if("AUTO_EXECUTE".equals(out))return save(plan,trigger,Outcome.AUTO_EXECUTE,"CHECKOUT_AUTHORIZATION_REQUIRED",proposal.proposalId(),proposal.proposalHash());
   if("EXPLICIT_CONFIRMATION".equals(out))return save(plan,trigger,Outcome.CONFIRM,"BUYER_CONFIRMATION_REQUIRED",proposal.proposalId(),proposal.proposalHash());
   String reason="FRESH_RISK_"+out;repository.status(buyer,id,"PAUSED",reason,Instant.now());return save(plan,trigger,Outcome.PAUSED,reason,proposal.proposalId(),proposal.proposalHash());
  }catch(RuntimeException failure){String reason=code(failure);repository.status(buyer,id,"PAUSED",reason,Instant.now());return save(plan,trigger,Outcome.PAUSED,reason,null,null);}
 }
 private Evaluation save(PlanView p,String trigger,Outcome o,String reason,UUID proposal,String proposalHash){var evidence=mapper.createObjectNode();evidence.put("planHash",p.version().planHash());evidence.put("freshEvaluation",true);evidence.put("reasonCode",reason);if(proposalHash!=null)evidence.put("proposalHash",proposalHash);Evaluation evaluation=repository.saveEvaluation(p,trigger,o,reason,evidence,proposal,canonical.hash(evidence),Instant.now());
  audit.record(p.plan().buyerId(),p.plan().merchantId(),null,"AUTOBUY_EVALUATED",evaluation.id().toString(),mapper.createObjectNode()
          .put("planId",p.plan().id().toString()).put("triggerId",trigger).put("outcome",o.name()).put("reasonCode",reason).put("evaluationHash",evaluation.evaluationHash()));return evaluation;}
 private void validate(UUID buyer,PlanInput i){if(i==null||i.merchantId()==null||i.merchantAccountLinkId()==null||i.addressId()==null||i.maximumAmountMinor()<=0||i.productConstraints()==null||!i.productConstraints().isObject()||i.hardSafetyConstraints()==null||!i.hardSafetyConstraints().isObject())throw bad("AUTOBUY_PLAN_INVALID");
  if(i.triggerDescription()==null||i.triggerDescription().isBlank()||i.triggerDescription().length()>256||!Set.of("NONE","EXACT_ONLY").contains(i.substitutionPolicy()))throw bad("AUTOBUY_PLAN_INVALID");validateReferences(buyer,new PlanVersion(null,0,buyer,i.merchantId(),i.merchantAccountLinkId(),i.addressId(),i.productConstraints(),i.maximumAmountMinor(),i.triggerDescription(),i.substitutionPolicy(),i.hardSafetyConstraints(),"",Instant.now()));}
 private void validateReferences(UUID buyer,PlanVersion v){String failure=referenceFailure(buyer,v);if(failure!=null)throw new LifecycleException(failure,HttpStatus.CONFLICT,"AutoBuy authority reference is unavailable");}
 private String referenceFailure(UUID buyer,PlanVersion v){var address=onboarding.address(buyer,v.addressId());if(address.isEmpty()||!address.orElseThrow().active())return "AUTOBUY_ADDRESS_INVALID";
  var selected=onboarding.selectedAddress(buyer);if(selected.isEmpty()||!selected.orElseThrow().id().equals(v.addressId()))return "AUTOBUY_ADDRESS_NOT_SELECTED";
  var active=onboarding.activeLink(buyer,v.merchantId(),Instant.now());if(active.isEmpty()||!active.orElseThrow().id().equals(v.linkId()))return "AUTOBUY_MERCHANT_LINK_INVALID";return null;}
 private String boundedFailure(PlanVersion plan,dev.agenticcommerce.gateway.commerce.TransactionModels.TransactionProposal proposal,ConstraintCertificate certificate){
  if(!plan.merchantId().equals(proposal.merchantId()))return "AUTOBUY_MERCHANT_MISMATCH";
  JsonNode constraints=plan.productConstraints();String sku=text(constraints,"exactMerchantSku","merchantSku");String variant=text(constraints,"exactVariant","variant");String product=text(constraints,"productId");
  if(sku!=null&&proposal.lineItems().stream().anyMatch(line->!sku.equalsIgnoreCase(line.merchantSku())))return "AUTOBUY_EXACT_PRODUCT_MISMATCH";
  if(variant!=null&&proposal.lineItems().stream().anyMatch(line->!variant.equalsIgnoreCase(Objects.toString(line.variant(),""))))return "AUTOBUY_EXACT_VARIANT_MISMATCH";
  if(product!=null){UUID expected;try{expected=UUID.fromString(product);}catch(IllegalArgumentException invalid){return "AUTOBUY_PRODUCT_CONSTRAINT_INVALID";}
   if(proposal.lineItems().stream().anyMatch(line->!expected.equals(line.productId())))return "AUTOBUY_EXACT_PRODUCT_MISMATCH";}
  Iterator<Map.Entry<String,JsonNode>> fields=plan.hardSafetyConstraints().properties().iterator();while(fields.hasNext()){var field=fields.next();String allergen;
   if("prohibitedAllergen".equals(field.getKey()))allergen=field.getValue().asText("");
   else if("PROHIBITED".equalsIgnoreCase(field.getValue().asText("")))allergen=field.getKey();else return "AUTOBUY_SAFETY_CONSTRAINT_UNSUPPORTED";
   String key="ALLERGEN_"+allergen.toUpperCase(Locale.ROOT);boolean pass=certificate.results().stream().anyMatch(result->result.safetyCritical()&&key.equalsIgnoreCase(result.constraintKey())&&result.result()==ConstraintOutcome.PASS);
   if(!pass)return "AUTOBUY_SAFETY_CONSTRAINT_UNKNOWN";}
  return null;}
 private static String text(JsonNode node,String... names){for(String name:names){String value=node.path(name).asText("").strip();if(!value.isBlank())return value;}return null;}
 private String hash(UUID buyer,PlanInput i,int version){var n=mapper.valueToTree(i);((tools.jackson.databind.node.ObjectNode)n).put("buyerActorId",buyer.toString()).put("version",version);return canonical.hash(n);}
 private static String code(RuntimeException e){try{var m=e.getClass().getMethod("code");Object c=m.invoke(e);if(c!=null)return "FRESH_"+c;}catch(ReflectiveOperationException ignored){}return "FRESH_EVALUATION_FAILED";}
 private static LifecycleException bad(String c){return new LifecycleException(c,HttpStatus.BAD_REQUEST,"AutoBuy request is invalid");}private static LifecycleException notFound(){return new LifecycleException("AUTOBUY_PLAN_NOT_FOUND",HttpStatus.NOT_FOUND,"AutoBuy plan was not found");}
}
