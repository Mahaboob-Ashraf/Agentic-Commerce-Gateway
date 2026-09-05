package dev.agenticcommerce.gateway.intent;

import static dev.agenticcommerce.gateway.intent.BuyerModels.*;
import static dev.agenticcommerce.gateway.intent.CommerceRequestModels.*;

import dev.agenticcommerce.gateway.agentization.service.CanonicalJsonService;
import dev.agenticcommerce.gateway.catalogue.CatalogueModels.Product;
import dev.agenticcommerce.gateway.catalogue.CatalogueRepository;
import dev.agenticcommerce.gateway.commerce.AuthoritativeRefreshService;
import dev.agenticcommerce.gateway.commerce.TransactionAuthorityException;
import dev.agenticcommerce.gateway.commerce.TransactionAuthorityRepository;
import dev.agenticcommerce.gateway.commerce.TransactionModels.*;
import dev.agenticcommerce.gateway.commerce.TransactionProposalService;
import dev.agenticcommerce.gateway.identity.model.Merchant;
import dev.agenticcommerce.gateway.identity.persistence.MerchantRepository;
import dev.agenticcommerce.gateway.risk.ReversibilityService;
import dev.agenticcommerce.gateway.intent.VisualCommerceModels.StoredVisionObservation;
import dev.agenticcommerce.gateway.intent.VisualCommerceModels.ValidatedImage;
import dev.agenticcommerce.gateway.intent.VisualCommerceModels.VisionObservationView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class CommerceRequestService {
    private static final Logger log=LoggerFactory.getLogger(CommerceRequestService.class);
    private static final int MAX_PROGRESS_STEPS=16;
    private static final Pattern STATUS_FOLLOW_UP=Pattern.compile("(?i)^(?:what(?:'s| is)? happening|what happened|what did you find|where are we|status|any updates?)[?.! ]*$");
    private final CommerceRequestRepository requests;private final BuyerThreadService threads;private final MerchantRepository merchants;
    private final BuyerOrchestrationService orchestration;private final BuyerRepository buyers;
    private final CatalogueRepository catalogues;private final AuthoritativeRefreshService refreshes;
    private final TransactionProposalService proposals;private final ReversibilityService risks;
    private final TransactionAuthorityRepository authority;private final CanonicalJsonService canonical;private final ObjectMapper mapper;
    private final BuyerImageValidator imageValidator;private final VisionObservationProvider vision;private final VisionObservationRepository observations;
    public CommerceRequestService(CommerceRequestRepository requests,BuyerThreadService threads,
            BuyerOrchestrationService orchestration,BuyerRepository buyers,CatalogueRepository catalogues,MerchantRepository merchants,
            AuthoritativeRefreshService refreshes,TransactionProposalService proposals,
            ReversibilityService risks,TransactionAuthorityRepository authority,
            CanonicalJsonService canonical,ObjectMapper mapper,BuyerImageValidator imageValidator,
            VisionObservationProvider vision,VisionObservationRepository observations){this.requests=requests;this.threads=threads;
        this.orchestration=orchestration;this.buyers=buyers;this.catalogues=catalogues;this.merchants=merchants;this.refreshes=refreshes;
        this.proposals=proposals;this.risks=risks;this.authority=authority;this.canonical=canonical;this.mapper=mapper;
        this.imageValidator=imageValidator;this.vision=vision;this.observations=observations;}

    public CommerceRequestResult execute(UUID buyerId,UUID requestId,UUID existingThreadId,String input){
        return executeInternal(buyerId,requestId,existingThreadId,input,null);
    }
    public CommerceRequestResult executeVisual(UUID buyerId,UUID requestId,UUID existingThreadId,String input,
            org.springframework.web.multipart.MultipartFile upload){
        ValidatedImage image=imageValidator.validate(upload);String text=input==null||input.isBlank()?"Find products like this":input;
        return executeInternal(buyerId,requestId,existingThreadId,text,image);
    }
    private CommerceRequestResult executeInternal(UUID buyerId,UUID requestId,UUID existingThreadId,String input,ValidatedImage image){
        long totalStarted=System.nanoTime();
        if(requestId==null)throw new BuyerException("COMMERCE_REQUEST_ID_REQUIRED",HttpStatus.BAD_REQUEST,"requestId is required");
        String text=BuyerThreadService.normalize(input);if(existingThreadId!=null)threads.require(buyerId,existingThreadId);
        var material=mapper.createObjectNode().put("buyerActorId",buyerId.toString()).put("requestId",requestId.toString()).put("text",text);
        if(image!=null)material.put("imageSha256",image.sha256()).put("imageMimeType",image.mimeType()).put("imageWidth",image.width()).put("imageHeight",image.height());
        if(existingThreadId==null)material.putNull("threadId");else material.put("threadId",existingThreadId.toString());String hash=canonical.hash(material);
        long requestPersistenceStarted=System.nanoTime();var created=requests.create(buyerId,requestId,existingThreadId,text,hash);long requestPersistenceElapsed=elapsedMillis(requestPersistenceStarted);
        if(created.isEmpty()){CommerceRequestRecord prior=requests.find(buyerId,requestId).orElseThrow();
            if(!prior.materialHash().equals(hash))throw new BuyerException("COMMERCE_REQUEST_IDEMPOTENCY_CONFLICT",HttpStatus.CONFLICT,"requestId was already used with different request material");
            if(prior.authoritativeResult()!=null)return mapper.treeToValue(prior.authoritativeResult(),CommerceRequestResult.class);
            throw new BuyerException("COMMERCE_REQUEST_RUNNING",HttpStatus.CONFLICT,"Commerce request is already running");}
        long threadPersistenceStarted=System.nanoTime();CommerceThread thread;if(existingThreadId==null)thread=image==null?threads.create(buyerId,text):threads.createVisual(buyerId,text);else if(image==null&&STATUS_FOLLOW_UP.matcher(text).matches()){threads.addContextMessage(buyerId,existingThreadId,text);thread=threads.require(buyerId,existingThreadId);}else{if(image==null)threads.addMessage(buyerId,existingThreadId,text);else threads.addVisualMessage(buyerId,existingThreadId,text);thread=threads.require(buyerId,existingThreadId);}
        requests.attachThread(buyerId,requestId,thread.threadId());long threadPersistenceElapsed=elapsedMillis(threadPersistenceStarted);String failure=null;
        if(existingThreadId!=null&&STATUS_FOLLOW_UP.matcher(text).matches()){
            CommerceRequestResult contextual=result(requestId,thread,RequestStatus.COMPLETED,null);
            requests.complete(buyerId,requestId,RequestStatus.COMPLETED,contextual,null);
            log.info("Commerce thread status follow-up completed state={} requestPersistenceMs={} threadPersistenceMs={} totalElapsedMs={}",
                    thread.state(),requestPersistenceElapsed,threadPersistenceElapsed,elapsedMillis(totalStarted));
            return contextual;
        }
        if(image!=null){try{ThreadMessage source=buyers.latestMessage(buyerId,thread.threadId()).orElseThrow();var observed=vision.observe(image,text);
                String observationHash=canonical.hash(mapper.valueToTree(observed.observation()));observations.create(buyerId,requestId,thread.threadId(),source.messageId(),image,observed,observationHash);
            }catch(BuyerException e){failure=e.code();}}
        long orchestrationStarted=System.nanoTime();int orchestrationSteps=0;
        try{if(failure==null)for(int step=0;step<MAX_PROGRESS_STEPS;step++){thread=threads.require(buyerId,thread.threadId());if(stable(thread.state()))break;thread=orchestration.advanceWithinRequest(buyerId,thread.threadId()).thread();orchestrationSteps++;}
            if(failure==null&&!stable(thread.state()))failure="COMMERCE_REQUEST_STEP_BOUND_EXCEEDED";
        }catch(BuyerException e){failure=e.code();thread=threads.require(buyerId,thread.threadId());}
        long orchestrationElapsed=elapsedMillis(orchestrationStarted);
        boolean preparationAttempted=false;
        long preparationStarted=System.nanoTime();
        long refreshElapsed=0,proposalElapsed=0,riskElapsed=0;
        if(failure==null&&thread.state()==BuyerState.CONSTRAINTS_VERIFIED){preparationAttempted=true;
            try{long refreshStarted=System.nanoTime();AuthorityRefresh refresh=refreshes.refresh(buyerId,thread.threadId());refreshElapsed=elapsedMillis(refreshStarted);
                if(refresh.outcome()!=EvidenceOutcome.PASS)throw new TransactionAuthorityException("EXECUTABLE_EVIDENCE_NOT_PASS",HttpStatus.CONFLICT,"Authoritative evidence did not pass");
                long proposalStarted=System.nanoTime();var proposal=proposals.create(buyerId,thread.threadId());proposalElapsed=elapsedMillis(proposalStarted);
                long riskStarted=System.nanoTime();risks.evaluateForCheckout(buyerId,proposal.proposalId());riskElapsed=elapsedMillis(riskStarted);
                thread=threads.require(buyerId,thread.threadId());
            }catch(TransactionAuthorityException e){failure=e.code();thread=threads.require(buyerId,thread.threadId());}
            catch(BuyerException e){failure=e.code();thread=threads.require(buyerId,thread.threadId());}}
        long preparationElapsed=elapsedMillis(preparationStarted);
        TransactionProposal proposal=authority.currentProposal(buyerId,thread.threadId()).orElse(null);
        ReversibilityEvaluation risk=proposal==null?null:authority.riskForProposal(buyerId,proposal.proposalId()).orElse(null);
        boolean paymentReady=failure==null&&proposal!=null&&risk!=null
                &&risk.outcome()==ReversibilityOutcome.EXPLICIT_CONFIRMATION&&proposal.proposalExpiresAt().isAfter(Instant.now());
        RequestStatus status=paymentReady?RequestStatus.COMPLETED:
                failure!=null?RequestStatus.FAILED:
                preparationAttempted?RequestStatus.COMPLETED:thread.state()==BuyerState.CONSTRAINTS_VERIFIED?RequestStatus.COMPLETED:
                thread.state()==BuyerState.WAITING_FOR_USER?RequestStatus.WAITING_FOR_USER:RequestStatus.FAILED;
        if(status==RequestStatus.FAILED&&failure==null)failure="COMMERCE_REQUEST_TERMINAL_FAILURE";
        long resultStarted=System.nanoTime();CommerceRequestResult result=result(requestId,thread,status,failure);long resultElapsed=elapsedMillis(resultStarted);
        long completionStarted=System.nanoTime();requests.complete(buyerId,requestId,status,result,failure);long completionElapsed=elapsedMillis(completionStarted);
        log.info("Commerce request completed status={} state={} failureCode={} requestPersistenceMs={} threadPersistenceMs={} orchestrationSteps={} orchestrationMs={} authorityRefreshMs={} proposalMs={} riskMs={} preparationMs={} resultPersistenceReadsMs={} completionPersistenceMs={} totalElapsedMs={}",
                status,thread.state(),failure,requestPersistenceElapsed,threadPersistenceElapsed,orchestrationSteps,orchestrationElapsed,
                refreshElapsed,proposalElapsed,riskElapsed,preparationElapsed,resultElapsed,completionElapsed,elapsedMillis(totalStarted));
        return result;
    }

    public CommerceRequestResult get(UUID buyerId,UUID requestId){CommerceRequestRecord record=requests.find(buyerId,requestId)
            .orElseThrow(()->new BuyerException("COMMERCE_REQUEST_NOT_FOUND",HttpStatus.NOT_FOUND,"Commerce request was not found"));
        if(record.authoritativeResult()==null){
            if(record.threadId()==null)throw new BuyerException("COMMERCE_REQUEST_ACCEPTING",HttpStatus.CONFLICT,"Commerce request is being accepted");
            return result(record.requestId(),threads.require(buyerId,record.threadId()),RequestStatus.RUNNING,null);
        }
        return mapper.treeToValue(record.authoritativeResult(),CommerceRequestResult.class);}

    public CommerceRequestResult latestForThread(UUID buyerId,UUID threadId){
        threads.require(buyerId,threadId);
        CommerceRequestRecord record=requests.latestForThread(buyerId,threadId)
                .orElseThrow(()->new BuyerException("COMMERCE_REQUEST_NOT_FOUND",HttpStatus.NOT_FOUND,"Commerce request was not found"));
        if(record.authoritativeResult()==null)return result(record.requestId(),threads.require(buyerId,threadId),RequestStatus.RUNNING,null);
        return mapper.treeToValue(record.authoritativeResult(),CommerceRequestResult.class);
    }

    private CommerceRequestResult result(UUID requestId,CommerceThread thread,RequestStatus status,String failure){
        BuyerIntent intent=buyers.latestIntent(thread.buyerActorId(),thread.threadId()).orElse(null);
        MerchantDiscovery discovery=intent==null?null:buyers.latestDiscovery(thread.buyerActorId(),thread.threadId(),intent.intentId()).orElse(null);
        CandidateCart cart=buyers.currentCart(thread.buyerActorId(),thread.threadId()).orElse(null);
        MerchantQuote quote=buyers.currentQuote(thread.buyerActorId(),thread.threadId()).orElse(null);
        ConstraintCertificate certificate=buyers.currentCertificate(thread.buyerActorId(),thread.threadId()).orElse(null);
        AuthorityRefresh refresh=authority.latestAuthorityRefresh(thread.buyerActorId(),thread.threadId()).orElse(null);
        TransactionProposal proposal=authority.currentProposal(thread.buyerActorId(),thread.threadId()).orElse(null);
        ReversibilityEvaluation risk=proposal==null?null:authority.riskForProposal(thread.buyerActorId(),proposal.proposalId()).orElse(null);
        MerchantCandidate merchant=cart==null||discovery==null?null:discovery.eligibleMerchants().stream().filter(m->m.merchantId().equals(cart.merchantId())).findFirst().orElse(null);
        Merchant merchantPresentation=cart==null?null:merchants.findById(cart.merchantId()).orElse(null);
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
        StoredVisionObservation persistedVisual=observations.findByRequest(thread.buyerActorId(),requestId).orElse(null);
        if(persistedVisual!=null)refs.add("vision:"+persistedVisual.observationId()+":"+persistedVisual.observationHash());
        if(discovery!=null)refs.add("discovery:"+discovery.discoveryId()+":"+discovery.discoveryHash());if(cart!=null)refs.add("cart:"+cart.cartId()+":"+cart.cartHash());
        if(quote!=null)refs.add("quote:"+quote.quoteRecordId()+":"+quote.evidenceHash());if(certificate!=null)refs.add("certificate:"+certificate.certificateId()+":"+certificate.certificateHash());
        boolean clarification=thread.state()==BuyerState.WAITING_FOR_USER&&proposal==null;String question=intent==null?null:intent.compiled().clarificationQuestion();
        if(clarification&&(question==null||question.isBlank()))question="No trustworthy product match satisfies the request; clarify the category or exact product identity.";
        boolean paymentReady=failure==null&&proposal!=null&&risk!=null
                &&risk.outcome()==ReversibilityOutcome.EXPLICIT_CONFIRMATION&&proposal.proposalExpiresAt().isAfter(Instant.now());
        String nextAction=paymentReady?"AUTHORIZE_RAZORPAY_CHECKOUT":proposal!=null&&proposal.proposalExpiresAt().isBefore(Instant.now())?"REQUOTE_REQUIRED":
                risk!=null&&risk.outcome()==ReversibilityOutcome.BLOCK?"BLOCKED":clarification?"CLARIFY_REQUEST":
                failure!=null&&failure.contains("EXPIRED")?"REQUOTE_REQUIRED":failure!=null?"RETRY_AFTER_AUTHORITY_RECOVERY":"NONE";
        String authorizationState=paymentReady?"WAITING_FOR_EXPLICIT_PAYMENT_AUTHORIZATION":
                proposal!=null&&proposal.proposalExpiresAt().isBefore(Instant.now())?"STALE":
                risk!=null&&risk.outcome()==ReversibilityOutcome.BLOCK?"BLOCKED":"NOT_PREPARED";
        StoredVisionObservation visual=persistedVisual;
        VisionObservationView visualView=visual==null?null:new VisionObservationView(visual.observation().category(),visual.observation().productType(),
                visual.observation().brandCandidate(),visual.observation().modelCandidate(),visual.observation().colors(),visual.observation().materials(),
                visual.observation().styleDescriptors(),visual.observation().visibleText(),visual.observation().confidence(),visual.observation().ambiguities(),
                visual.mimeType(),visual.originalFilename(),visual.sizeBytes(),visual.width(),visual.height(),visual.observationHash(),visual.provider(),visual.model());
        String visualMatchType=visual==null?null:exactGrounded(intent)?"EXACT_GROUNDED_MATCH":"VISUALLY_SIMILAR_GROUNDED_RESULT";
        List<String> visualReasons=visual==null||products.isEmpty()?List.of():visualReasons(visual,products.getFirst());
        return new CommerceRequestResult(requestId,thread.threadId(),thread.state(),status,clarification,question,
                intent==null?null:intent.intentVersion(),intent==null?null:intent.compiled().goal(),intent==null?null:intent.compiled().categoryRequest(),
                intent==null?null:intent.compiled().budgetAmountMinor(),intent==null?null:intent.compiled().currency(),hard,
                intent==null?List.of():intent.compiled().softPreferences(),
                merchant==null?cart==null?null:cart.merchantId():merchant.merchantId(),
                merchantPresentation==null?merchant==null?null:merchant.displayName():merchantPresentation.displayName(),
                merchantPresentation==null?null:merchantPresentation.logoUrl(),
                cart==null?null:cart.catalogueVersionId(),merchant==null?null:merchant.catalogueVersion(),cart==null?null:cart.cartId(),cart==null?null:cart.cartHash(),products,
                quote==null?null:quote.quoteRecordId(),quote==null?null:quote.merchantQuoteId(),quote==null?null:quote.merchantQuoteVersion(),
                quote==null?null:quote.subtotalMinor(),quote==null?null:quote.taxMinor(),quote==null?null:quote.deliveryMinor(),quote==null?null:quote.feesMinor(),
                quote==null?null:quote.finalAmountMinor(),quote==null?null:quote.currency(),quote==null?null:quote.expiresAt(),
                refresh==null?null:refresh.availability().availabilityRefreshId(),refresh==null?null:refresh.availability().outcome(),refresh==null?null:refresh.availability().reasonCode(),
                refresh==null?null:refresh.serviceability().serviceabilityEvidenceId(),refresh==null?null:refresh.serviceability().outcome(),refresh==null?null:refresh.serviceability().reasonCode(),
                certificate==null?null:certificate.certificateId(),certificate==null?null:certificate.certificateHash(),certificate==null?null:certificate.overallResult(),constraints,
                proposal==null?null:proposal.proposalId(),proposal==null?null:proposal.proposalHash(),proposal==null?null:proposal.proposalExpiresAt(),
                risk==null?null:risk.outcome(),risk==null?List.of():risk.reasonCodes(),proposal!=null&&risk!=null&&risk.paymentAuthorizationStillRequired(),paymentReady,authorizationState,nextAction,
                progress(visual,intent,discovery,cart,quote,refresh,certificate,proposal,risk),refs.stream().distinct().limit(64).toList(),failure,
                visualView,visualMatchType,visualReasons);
    }

    private static List<CommerceProgressStep> progress(StoredVisionObservation visual,BuyerIntent intent,MerchantDiscovery discovery,CandidateCart cart,
            MerchantQuote quote,AuthorityRefresh refresh,ConstraintCertificate certificate,TransactionProposal proposal,
            ReversibilityEvaluation risk){List<CommerceProgressStep> steps=new ArrayList<>();
        if(visual!=null)steps.add(new CommerceProgressStep("VISION_OBSERVED","Understood visual attributes",List.of("vision:"+visual.observationId()+":"+visual.observationHash())));
        if(intent!=null)steps.add(new CommerceProgressStep("INTENT_COMPILED","Understood request",List.of("intent:"+intent.intentId()+":"+intent.intentHash())));
        if(discovery!=null)steps.add(new CommerceProgressStep("MERCHANTS_CHECKED","Checked eligible merchants",List.of("discovery:"+discovery.discoveryId()+":"+discovery.discoveryHash())));
        if(cart!=null)steps.add(new CommerceProgressStep("PRODUCTS_COMPARED","Compared grounded products",List.of("cart:"+cart.cartId()+":"+cart.cartHash())));
        if(quote!=null)steps.add(new CommerceProgressStep("QUOTE_RECEIVED","Received merchant quote",List.of("quote:"+quote.quoteRecordId()+":"+quote.evidenceHash())));
        if(refresh!=null)steps.add(new CommerceProgressStep("AVAILABILITY_CHECKED","Checked availability and serviceability",List.of("availability:"+refresh.availability().availabilityRefreshId()+":"+refresh.availability().evidenceHash(),"serviceability:"+refresh.serviceability().serviceabilityEvidenceId()+":"+refresh.serviceability().evidenceHash())));
        if(certificate!=null)steps.add(new CommerceProgressStep("CONSTRAINTS_VERIFIED","Verified constraints",List.of("certificate:"+certificate.certificateId()+":"+certificate.certificateHash())));
        if(proposal!=null)steps.add(new CommerceProgressStep("PROPOSAL_PREPARED","Prepared transaction proposal",List.of("proposal:"+proposal.proposalId()+":"+proposal.proposalHash())));
        if(risk!=null)steps.add(new CommerceProgressStep("PAYMENT_CONFIRMATION_REQUIRED","Waiting for your payment confirmation",List.of("risk:"+risk.reversibilityEvaluationId()+":"+risk.inputHash())));
        return List.copyOf(steps);}

    private List<AuthoritativeProductFact> facts(Product product){Instant now=Instant.now();Map<String,List<CatalogueRepository.FactValue>> grouped=new LinkedHashMap<>();
        for(var fact:catalogues.factsForProduct(product.merchantId(),product.catalogueVersionId(),product.id()))grouped.computeIfAbsent(fact.type(),ignored->new ArrayList<>()).add(fact);
        List<AuthoritativeProductFact> result=new ArrayList<>();for(var entry:grouped.entrySet()){List<CatalogueRepository.FactValue> values=entry.getValue();boolean primary=values.stream().anyMatch(f->"PRIMARY".equals(f.authority()));
            values.stream().filter(f->!primary||"PRIMARY".equals(f.authority())).filter(f->"ACTIVE".equals(f.state())&& (f.expiresAt()==null||f.expiresAt().isAfter(now))).limit(8)
                    .forEach(f->result.add(new AuthoritativeProductFact(f.factId(),f.type(),f.value(),f.authority(),f.source(),f.state(),f.observedAt(),f.expiresAt(),"fact:"+f.factId()+":"+f.factHash())));}
        result.sort(Comparator.comparing(AuthoritativeProductFact::type).thenComparing(f->f.factId().toString()));return result.stream().limit(32).toList();}
    private static boolean stable(BuyerState state){return state==BuyerState.CONSTRAINTS_VERIFIED||state==BuyerState.WAITING_FOR_USER;}
    private static boolean exactGrounded(BuyerIntent intent){if(intent==null)return false;CompiledIntent value=intent.compiled();return value.exactMerchantSku()!=null||value.exactGtin()!=null
            ||(value.exactBrand()!=null&&value.exactVariant()!=null);}
    private static List<String> visualReasons(StoredVisionObservation visual,AuthoritativeProductLine product){List<String> reasons=new ArrayList<>();
        if(product.category()!=null&&containsEither(product.category(),visual.observation().category(),visual.observation().productType()))reasons.add("Grounded category aligns with the visual hypothesis");
        if(product.colour()!=null&&visual.observation().colors().stream().anyMatch(color->containsEither(product.colour(),color)))reasons.add("Grounded colour aligns with the image");
        if(reasons.isEmpty())reasons.add("Retrieved from merchant evidence using bounded visual attributes");return List.copyOf(reasons);}
    private static boolean containsEither(String first,String...others){String normalized=first.toLowerCase(java.util.Locale.ROOT);for(String other:others)if(other!=null&&(normalized.contains(other.toLowerCase(java.util.Locale.ROOT))||other.toLowerCase(java.util.Locale.ROOT).contains(normalized)))return true;return false;}
    private static long elapsedMillis(long startedNanos){return (System.nanoTime()-startedNanos)/1_000_000L;}
}
