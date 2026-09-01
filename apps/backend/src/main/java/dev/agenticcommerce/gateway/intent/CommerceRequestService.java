package dev.agenticcommerce.gateway.intent;

import static dev.agenticcommerce.gateway.intent.BuyerModels.*;
import static dev.agenticcommerce.gateway.intent.CommerceRequestModels.*;

import dev.agenticcommerce.gateway.agentization.service.CanonicalJsonService;
import dev.agenticcommerce.gateway.catalogue.CatalogueModels.Product;
import dev.agenticcommerce.gateway.catalogue.CatalogueRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class CommerceRequestService {
    private static final int MAX_PROGRESS_STEPS=16;
    private final CommerceRequestRepository requests;private final BuyerThreadService threads;
    private final BuyerOrchestrationService orchestration;private final BuyerRepository buyers;
    private final CatalogueRepository catalogues;private final CanonicalJsonService canonical;private final ObjectMapper mapper;
    public CommerceRequestService(CommerceRequestRepository requests,BuyerThreadService threads,
            BuyerOrchestrationService orchestration,BuyerRepository buyers,CatalogueRepository catalogues,
            CanonicalJsonService canonical,ObjectMapper mapper){this.requests=requests;this.threads=threads;
        this.orchestration=orchestration;this.buyers=buyers;this.catalogues=catalogues;this.canonical=canonical;this.mapper=mapper;}

    @Transactional
    public CommerceRequestResult execute(UUID buyerId,UUID requestId,UUID existingThreadId,String input){
        if(requestId==null)throw new BuyerException("COMMERCE_REQUEST_ID_REQUIRED",HttpStatus.BAD_REQUEST,"requestId is required");
        String text=BuyerThreadService.normalize(input);if(existingThreadId!=null)threads.require(buyerId,existingThreadId);
        var material=mapper.createObjectNode().put("buyerActorId",buyerId.toString()).put("requestId",requestId.toString()).put("text",text);
        if(existingThreadId==null)material.putNull("threadId");else material.put("threadId",existingThreadId.toString());String hash=canonical.hash(material);
        var created=requests.create(buyerId,requestId,existingThreadId,text,hash);
        if(created.isEmpty()){CommerceRequestRecord prior=requests.findForUpdate(buyerId,requestId).orElseThrow();
            if(!prior.materialHash().equals(hash))throw new BuyerException("COMMERCE_REQUEST_IDEMPOTENCY_CONFLICT",HttpStatus.CONFLICT,"requestId was already used with different request material");
            if(prior.authoritativeResult()!=null)return mapper.treeToValue(prior.authoritativeResult(),CommerceRequestResult.class);
            throw new BuyerException("COMMERCE_REQUEST_RUNNING",HttpStatus.CONFLICT,"Commerce request is already running");}
        CommerceThread thread;if(existingThreadId==null)thread=threads.create(buyerId,text);else{threads.addMessage(buyerId,existingThreadId,text);thread=threads.require(buyerId,existingThreadId);}
        requests.attachThread(buyerId,requestId,thread.threadId());String failure=null;
        try{for(int step=0;step<MAX_PROGRESS_STEPS;step++){thread=threads.require(buyerId,thread.threadId());if(stable(thread.state()))break;thread=orchestration.advanceWithinRequest(buyerId,thread.threadId()).thread();}
            if(!stable(thread.state()))failure="COMMERCE_REQUEST_STEP_BOUND_EXCEEDED";
        }catch(BuyerException e){failure=e.code();thread=threads.require(buyerId,thread.threadId());}
        RequestStatus status=failure!=null?RequestStatus.FAILED:thread.state()==BuyerState.CONSTRAINTS_VERIFIED?RequestStatus.COMPLETED:
                thread.state()==BuyerState.WAITING_FOR_USER?RequestStatus.WAITING_FOR_USER:RequestStatus.FAILED;
        if(status==RequestStatus.FAILED&&failure==null)failure="COMMERCE_REQUEST_TERMINAL_FAILURE";
        CommerceRequestResult result=result(requestId,thread,status,failure);requests.complete(buyerId,requestId,status,result,failure);return result;
    }

    public CommerceRequestResult get(UUID buyerId,UUID requestId){CommerceRequestRecord record=requests.find(buyerId,requestId)
            .orElseThrow(()->new BuyerException("COMMERCE_REQUEST_NOT_FOUND",HttpStatus.NOT_FOUND,"Commerce request was not found"));
        if(record.authoritativeResult()==null)throw new BuyerException("COMMERCE_REQUEST_RUNNING",HttpStatus.CONFLICT,"Commerce request is still running");
        return mapper.treeToValue(record.authoritativeResult(),CommerceRequestResult.class);}

    private CommerceRequestResult result(UUID requestId,CommerceThread thread,RequestStatus status,String failure){
        BuyerIntent intent=buyers.latestIntent(thread.buyerActorId(),thread.threadId()).orElse(null);
        MerchantDiscovery discovery=intent==null?null:buyers.latestDiscovery(thread.buyerActorId(),thread.threadId(),intent.intentId()).orElse(null);
        CandidateCart cart=buyers.currentCart(thread.buyerActorId(),thread.threadId()).orElse(null);
        MerchantQuote quote=buyers.currentQuote(thread.buyerActorId(),thread.threadId()).orElse(null);
        ConstraintCertificate certificate=buyers.currentCertificate(thread.buyerActorId(),thread.threadId()).orElse(null);
        MerchantCandidate merchant=cart==null||discovery==null?null:discovery.eligibleMerchants().stream().filter(m->m.merchantId().equals(cart.merchantId())).findFirst().orElse(null);
        List<MaterialRequirement> hard=intent==null?List.of():intent.compiled().materialFields().stream()
                .filter(f->f.classification()!=ConstraintClassification.SOFT).limit(32)
                .map(f->new MaterialRequirement(f.field(),f.classification(),f.evidence().startOffset(),f.evidence().endOffset(),f.ambiguity())).toList();
        List<AuthoritativeProductLine> products=cart==null?List.of():cart.items().stream().limit(20).map(item->{
            Product product=catalogues.findProduct(cart.merchantId(),cart.catalogueVersionId(),item.productId()).orElseThrow();
            MerchantQuoteItem quoted=quote==null?null:quote.items().stream().filter(q->q.productId().equals(item.productId())&&q.merchantSku().equals(item.merchantSku())).findFirst().orElse(null);
            return new AuthoritativeProductLine(product.id(),product.merchantSku(),product.canonicalName(),product.brand(),product.variant(),product.sizeStorage(),product.colour(),product.category(),item.quantity(),
                    quoted==null?null:quoted.unitAmountMinor(),quoted==null?null:quoted.lineAmountMinor(),facts(product));}).toList();
        List<ConstraintSummary> constraints=certificate==null?List.of():certificate.results().stream().limit(32)
                .map(r->new ConstraintSummary(r.constraintKey(),r.result(),r.safetyCritical(),r.normalizedRequirement(),r.evidenceReferences().stream().limit(16).toList())).toList();
        List<String> refs=new ArrayList<>();if(intent!=null)refs.add("intent:"+intent.intentId()+":v"+intent.intentVersion()+":"+intent.intentHash());
        if(discovery!=null)refs.add("discovery:"+discovery.discoveryId()+":"+discovery.discoveryHash());if(cart!=null)refs.add("cart:"+cart.cartId()+":"+cart.cartHash());
        if(quote!=null)refs.add("quote:"+quote.quoteRecordId()+":"+quote.evidenceHash());if(certificate!=null)refs.add("certificate:"+certificate.certificateId()+":"+certificate.certificateHash());
        boolean clarification=thread.state()==BuyerState.WAITING_FOR_USER;String question=intent==null?null:intent.compiled().clarificationQuestion();
        if(clarification&&(question==null||question.isBlank()))question="No trustworthy product match satisfies the request; clarify the category or exact product identity.";
        return new CommerceRequestResult(requestId,thread.threadId(),thread.state(),status,clarification,question,
                intent==null?null:intent.intentVersion(),intent==null?null:intent.compiled().goal(),intent==null?null:intent.compiled().categoryRequest(),
                intent==null?null:intent.compiled().budgetAmountMinor(),intent==null?null:intent.compiled().currency(),hard,
                merchant==null?null:merchant.merchantId(),merchant==null?null:merchant.displayName(),cart==null?null:cart.catalogueVersionId(),merchant==null?null:merchant.catalogueVersion(),products,
                quote==null?null:quote.finalAmountMinor(),quote==null?null:quote.currency(),quote==null?null:quote.expiresAt(),
                certificate==null?null:certificate.overallResult(),constraints,refs.stream().distinct().limit(64).toList(),failure);
    }

    private List<AuthoritativeProductFact> facts(Product product){Instant now=Instant.now();Map<String,List<CatalogueRepository.FactValue>> grouped=new LinkedHashMap<>();
        for(var fact:catalogues.factsForProduct(product.merchantId(),product.catalogueVersionId(),product.id()))grouped.computeIfAbsent(fact.type(),ignored->new ArrayList<>()).add(fact);
        List<AuthoritativeProductFact> result=new ArrayList<>();for(var entry:grouped.entrySet()){List<CatalogueRepository.FactValue> values=entry.getValue();boolean primary=values.stream().anyMatch(f->"PRIMARY".equals(f.authority()));
            values.stream().filter(f->!primary||"PRIMARY".equals(f.authority())).filter(f->"ACTIVE".equals(f.state())&& (f.expiresAt()==null||f.expiresAt().isAfter(now))).limit(8)
                    .forEach(f->result.add(new AuthoritativeProductFact(f.factId(),f.type(),f.value(),f.authority(),f.source(),f.state(),f.observedAt(),f.expiresAt(),"fact:"+f.factId()+":"+f.factHash())));}
        result.sort(Comparator.comparing(AuthoritativeProductFact::type).thenComparing(f->f.factId().toString()));return result.stream().limit(32).toList();}
    private static boolean stable(BuyerState state){return state==BuyerState.CONSTRAINTS_VERIFIED||state==BuyerState.WAITING_FOR_USER;}
}
