package dev.agenticcommerce.gateway.intent;

import static dev.agenticcommerce.gateway.intent.BuyerModels.*;

import dev.agenticcommerce.gateway.agentization.service.CanonicalJsonService;
import dev.agenticcommerce.gateway.intent.BuyerDecisionProvider.BuyerDecisionContext;
import dev.agenticcommerce.gateway.intent.BuyerDecisionProvider.NextBuyerAction;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BuyerOrchestrationService {
    private final BuyerThreadService threads;private final BuyerRepository repository;private final BuyerStateMachine states;
    private final IntentCompilerService compiler;private final MerchantDiscoveryService discoveryService;
    private final CandidateCartService carts;private final AuthoritativeQuoteService quotes;
    private final ConstraintCertificateService constraints;private final BuyerDecisionProvider decisions;private final CanonicalJsonService canonical;
    public BuyerOrchestrationService(BuyerThreadService threads,BuyerRepository repository,BuyerStateMachine states,
            IntentCompilerService compiler,MerchantDiscoveryService discoveryService,CandidateCartService carts,
            AuthoritativeQuoteService quotes,ConstraintCertificateService constraints,BuyerDecisionProvider decisions,CanonicalJsonService canonical){
        this.threads=threads;this.repository=repository;this.states=states;this.compiler=compiler;this.discoveryService=discoveryService;
        this.carts=carts;this.quotes=quotes;this.constraints=constraints;this.decisions=decisions;this.canonical=canonical;}
    @Transactional public AdvanceResult advance(UUID buyerId,UUID threadId){return advanceWithinRequest(buyerId,threadId);}
    AdvanceResult advanceWithinRequest(UUID buyerId,UUID threadId){CommerceThread before=threads.requireForUpdate(buyerId,threadId);guard(before);
        BuyerIntent intent=repository.latestIntent(buyerId,threadId).orElse(null);MerchantDiscovery discovery=intent==null?null:repository.latestDiscovery(buyerId,threadId,intent.intentId()).orElse(null);
        BuyerAgentAction latest=repository.latestAction(buyerId,threadId).orElse(null);BuyerTool expected=expected(before,intent,discovery,latest);
        List<String> contextRefs=contextRefs(before,intent,discovery);NextBuyerAction selected=decision(before.state(),expected,contextRefs);
        String inputHash=canonical.hashText(before.threadId()+"|"+before.lockVersion()+"|"+expected+"|"+String.join("|",contextRefs));
        Execution execution=execute(before,intent,discovery,selected.action());states.require(before.state(),execution.state());
        CommerceThread after=repository.advance(before,execution.state(),execution.intentVersion(),execution.cartVersion(),execution.quoteId(),execution.certificateId(),execution.repeatedFailures());
        List<String> refs=execution.refs().stream().distinct().limit(64).toList();String signature=canonical.hashText(expected+"|"+inputHash+"|"+execution.outcome()+"|"+String.join("|",refs));
        BuyerAgentAction action=repository.createAction(before,after,expected,inputHash,refs,bounded(selected.conciseRationale()),execution.outcome(),signature,selected.provider(),selected.model());
        return new AdvanceResult(after,action);}
    private Execution execute(CommerceThread thread,BuyerIntent intent,MerchantDiscovery discovery,BuyerTool tool){return switch(tool){
        case COMPILE_INTENT->compile(thread);
        case DISCOVER_MERCHANTS->discover(thread,intent);
        case SEARCH_PRODUCTS->search(thread,intent,discovery);
        case BUILD_CANDIDATE_CART->build(thread,intent,discovery);
        case GET_QUOTE->quote(thread,intent,discovery);
        case VERIFY_CONSTRAINTS->verify(thread,intent);
        case REQUEST_CLARIFICATION->new Execution(BuyerState.WAITING_FOR_USER,thread.currentIntentVersion(),thread.currentCartVersion(),thread.currentQuoteId(),thread.currentCertificateId(),thread.repeatedFailureCount(),ActionOutcome.WAITING,List.of("clarification:required"));};}
    private Execution compile(CommerceThread thread){ThreadMessage message=repository.latestMessage(thread.buyerActorId(),thread.threadId()).orElseThrow();IntentCompilerService.Compiled result=compiler.compile(message);
        BuyerIntent intent=repository.createIntent(thread,message,result.intent(),result.modelOutputHash(),result.intentHash());MerchantDiscoveryService.GateResult gate=discoveryService.preRetrievalGate(intent);
        BuyerState state=gate.allowed()?BuyerState.SEARCHING:BuyerState.WAITING_FOR_USER;ActionOutcome outcome=gate.allowed()?ActionOutcome.SUCCESS:ActionOutcome.WAITING;
        return new Execution(state,intent.intentVersion(),null,null,null,0,outcome,List.of("message:"+message.messageId(),"intent:"+intent.intentId()+":v"+intent.intentVersion()+":"+intent.intentHash(),"gate:"+gate.reasonCode()));}
    private Execution discover(CommerceThread thread,BuyerIntent intent){MerchantDiscovery discovery=discoveryService.discover(thread,intent);boolean found=discovery.outcome()==DiscoveryOutcome.ELIGIBLE;
        return new Execution(found?BuyerState.SEARCHING:BuyerState.WAITING_FOR_USER,intent.intentVersion(),thread.currentCartVersion(),thread.currentQuoteId(),thread.currentCertificateId(),thread.repeatedFailureCount(),found?ActionOutcome.SUCCESS:ActionOutcome.WAITING,
                java.util.stream.Stream.concat(java.util.stream.Stream.of("discovery:"+discovery.discoveryId()+":"+discovery.discoveryHash()),discovery.evidenceReferences().stream()).toList());}
    private Execution search(CommerceThread thread,BuyerIntent intent,MerchantDiscovery discovery){CandidateCartService.MerchantSearch search=carts.search(intent,discovery);boolean match=search.results().stream().anyMatch(r->!r.response().matches().isEmpty());
        List<String> refs=new ArrayList<>(search.evidenceReferences());refs.add(match?"search:grounded-match":"search:no-trustworthy-match");
        return new Execution(match?BuyerState.SEARCHING:BuyerState.WAITING_FOR_USER,intent.intentVersion(),thread.currentCartVersion(),thread.currentQuoteId(),thread.currentCertificateId(),thread.repeatedFailureCount(),match?ActionOutcome.SUCCESS:ActionOutcome.WAITING,refs);}
    private Execution build(CommerceThread thread,BuyerIntent intent,MerchantDiscovery discovery){try{CandidateCart cart=carts.build(thread,intent,discovery);return new Execution(BuyerState.CART_PROPOSED,intent.intentVersion(),cart.cartVersion(),null,null,thread.repeatedFailureCount(),ActionOutcome.SUCCESS,List.of("cart:"+cart.cartId()+":v"+cart.cartVersion()+":"+cart.cartHash()));}
        catch(BuyerException e){return new Execution(BuyerState.WAITING_FOR_USER,intent.intentVersion(),thread.currentCartVersion(),thread.currentQuoteId(),thread.currentCertificateId(),thread.repeatedFailureCount()+1,ActionOutcome.WAITING,List.of("cartFailure:"+e.code()));}}
    private Execution quote(CommerceThread thread,BuyerIntent intent,MerchantDiscovery discovery){CandidateCart cart=repository.currentCart(thread.buyerActorId(),thread.threadId()).orElseThrow();MerchantCandidate merchant=discovery.eligibleMerchants().stream().filter(m->m.merchantId().equals(cart.merchantId())).findFirst().orElseThrow();
        MerchantQuote quote=quotes.quote(cart,merchant);return new Execution(BuyerState.CART_PROPOSED,intent.intentVersion(),cart.cartVersion(),quote.quoteRecordId(),null,thread.repeatedFailureCount(),ActionOutcome.SUCCESS,List.of("quote:"+quote.quoteRecordId()+":"+quote.evidenceHash(),"mapping:"+quote.executableMappingProposalId()));}
    private Execution verify(CommerceThread thread,BuyerIntent intent){CandidateCart cart=repository.currentCart(thread.buyerActorId(),thread.threadId()).orElseThrow();MerchantQuote quote=repository.currentQuote(thread.buyerActorId(),thread.threadId()).orElseThrow();
        ConstraintCertificate certificate=constraints.evaluate(thread,intent,cart,quote);if(certificate.overallResult()==ConstraintOutcome.PASS)return new Execution(BuyerState.CONSTRAINTS_VERIFIED,intent.intentVersion(),cart.cartVersion(),quote.quoteRecordId(),certificate.certificateId(),0,ActionOutcome.SUCCESS,List.of("certificate:"+certificate.certificateId()+":"+certificate.certificateHash()+":PASS"));
        int failures=thread.repeatedFailureCount()+1;BuyerState next=failures>=3?BuyerState.WAITING_FOR_USER:BuyerState.SEARCHING;
        return new Execution(next,intent.intentVersion(),cart.cartVersion(),quote.quoteRecordId(),certificate.certificateId(),failures,next==BuyerState.SEARCHING?ActionOutcome.FAILURE:ActionOutcome.WAITING,List.of("certificate:"+certificate.certificateId()+":"+certificate.certificateHash()+":"+certificate.overallResult()));}
    private BuyerTool expected(CommerceThread thread,BuyerIntent intent,MerchantDiscovery discovery,BuyerAgentAction latest){return switch(thread.state()){
        case UNDERSTANDING->BuyerTool.COMPILE_INTENT;
        case SEARCHING->{if(intent==null)yield BuyerTool.COMPILE_INTENT;if(discovery==null)yield BuyerTool.DISCOVER_MERCHANTS;if(latest==null||latest.selectedTool()==BuyerTool.DISCOVER_MERCHANTS||latest.selectedTool()==BuyerTool.VERIFY_CONSTRAINTS)yield BuyerTool.SEARCH_PRODUCTS;yield BuyerTool.BUILD_CANDIDATE_CART;}
        case CART_PROPOSED->thread.currentQuoteId()==null?BuyerTool.GET_QUOTE:BuyerTool.VERIFY_CONSTRAINTS;
        case WAITING_FOR_USER->throw new BuyerException("BUYER_WAITING_FOR_USER",HttpStatus.CONFLICT,"Buyer clarification is required before another agent step");
        case CONSTRAINTS_VERIFIED->throw new BuyerException("TASK_008_TERMINAL_STATE",HttpStatus.CONFLICT,"Task 008 stops at CONSTRAINTS_VERIFIED");
        default->throw new BuyerException("BUYER_LATER_STATE_NOT_IMPLEMENTED",HttpStatus.CONFLICT,"Later buyer states are not implemented in Task 008");};}
    private NextBuyerAction decision(BuyerState state,BuyerTool expected,List<String> refs){String feedback=null;for(int attempt=1;attempt<=2;attempt++)try{NextBuyerAction value=decisions.choose(new BuyerDecisionContext(state,List.of(expected),refs),feedback);
            if(value==null||value.action()!=expected||value.conciseRationale()==null||value.conciseRationale().isBlank()||value.conciseRationale().length()>512||value.evidenceReferences()==null||value.evidenceReferences().size()>32)throw new IllegalArgumentException("Action is outside permitted bounded schema");return value;
        }catch(RuntimeException e){feedback=bounded(e.getMessage());}throw new BuyerException("BUYER_DECISION_INVALID",HttpStatus.UNPROCESSABLE_ENTITY,"Buyer decision remained invalid after one retry");}
    private static List<String> contextRefs(CommerceThread t,BuyerIntent i,MerchantDiscovery d){List<String> refs=new ArrayList<>();refs.add("thread:"+t.threadId()+":step"+t.stepCount());if(i!=null)refs.add("intent:"+i.intentId()+":"+i.intentHash());if(d!=null)refs.add("discovery:"+d.discoveryId()+":"+d.discoveryHash());return refs;}
    private static void guard(CommerceThread thread){if(thread.stepCount()>=thread.maximumSteps())throw new BuyerException("BUYER_STEP_BUDGET_EXHAUSTED",HttpStatus.CONFLICT,"Buyer step budget is exhausted");if(!thread.wallClockDeadline().isAfter(Instant.now()))throw new BuyerException("BUYER_DEADLINE_EXPIRED",HttpStatus.CONFLICT,"Buyer thread deadline expired");}
    private static String bounded(String value){if(value==null)return "bounded buyer action";return value.length()<=512?value:value.substring(0,512);}
    private record Execution(BuyerState state,Integer intentVersion,Integer cartVersion,UUID quoteId,UUID certificateId,int repeatedFailures,ActionOutcome outcome,List<String> refs){}
}
