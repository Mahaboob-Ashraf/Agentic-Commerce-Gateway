package dev.agenticcommerce.gateway.intent;

import static dev.agenticcommerce.gateway.intent.BuyerModels.*;

import dev.agenticcommerce.gateway.agentization.service.CanonicalJsonService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BuyerThreadService {
    private final BuyerAccessService access;private final BuyerRepository repository;private final CanonicalJsonService canonical;
    private final BuyerStateMachine states;
    public BuyerThreadService(BuyerAccessService access,BuyerRepository repository,CanonicalJsonService canonical,BuyerStateMachine states){this.access=access;this.repository=repository;this.canonical=canonical;this.states=states;}
    @Transactional public CommerceThread create(UUID buyerId,String input){access.requireBuyer(buyerId);String text=normalize(input);String title=text.length()<=80?text:text.substring(0,80);
        CommerceThread thread=repository.createThread(buyerId,title,Instant.now().plus(30,ChronoUnit.MINUTES));repository.appendMessage(thread,"TYPED_TEXT",text,canonical.hashText(text));return thread;}
    @Transactional public ThreadMessage addMessage(UUID buyerId,UUID threadId,String input){access.requireBuyer(buyerId);CommerceThread thread=requireForUpdate(buyerId,threadId);String text=normalize(input);
        if(thread.state()!=BuyerState.WAITING_FOR_USER&&thread.state()!=BuyerState.CONSTRAINTS_VERIFIED&&thread.state()!=BuyerState.SEARCHING&&thread.state()!=BuyerState.CART_PROPOSED
                &&thread.state()!=BuyerState.TRANSACTION_PROPOSED&&thread.state()!=BuyerState.RISK_EVALUATED&&thread.state()!=BuyerState.READY_TO_EXECUTE)
            throw new BuyerException("BUYER_MESSAGE_NOT_ALLOWED",HttpStatus.CONFLICT,"A clarification or correction is not accepted in the current state");
        states.require(thread.state(),BuyerState.UNDERSTANDING);ThreadMessage message=repository.appendMessage(thread,"TYPED_TEXT",text,canonical.hashText(text));repository.resumeForMessage(thread);return message;}
    public CommerceThread require(UUID buyerId,UUID threadId){access.requireBuyer(buyerId);return repository.findThread(buyerId,threadId).orElseThrow(()->new BuyerException("COMMERCE_THREAD_NOT_FOUND",HttpStatus.NOT_FOUND,"Buyer thread was not found"));}
    public CommerceThread requireForUpdate(UUID buyerId,UUID threadId){access.requireBuyer(buyerId);return repository.findThreadForUpdate(buyerId,threadId).orElseThrow(()->new BuyerException("COMMERCE_THREAD_NOT_FOUND",HttpStatus.NOT_FOUND,"Buyer thread was not found"));}
    public List<CommerceThread> list(UUID buyerId){access.requireBuyer(buyerId);return repository.listThreads(buyerId);}
    public List<ThreadMessage> messages(UUID buyerId,UUID threadId){require(buyerId,threadId);return repository.messages(buyerId,threadId);}
    public BuyerIntent intent(UUID buyerId,UUID threadId){require(buyerId,threadId);return repository.latestIntent(buyerId,threadId).orElseThrow(()->new BuyerException("BUYER_INTENT_NOT_FOUND",HttpStatus.NOT_FOUND,"Buyer intent was not found"));}
    public MerchantDiscovery discovery(UUID buyerId,UUID threadId){BuyerIntent intent=intent(buyerId,threadId);return repository.latestDiscovery(buyerId,threadId,intent.intentId()).orElseThrow(()->new BuyerException("MERCHANT_DISCOVERY_NOT_FOUND",HttpStatus.NOT_FOUND,"Merchant discovery evidence was not found"));}
    public CandidateCart cart(UUID buyerId,UUID threadId){require(buyerId,threadId);return repository.currentCart(buyerId,threadId).orElseThrow(()->new BuyerException("CANDIDATE_CART_NOT_FOUND",HttpStatus.NOT_FOUND,"Candidate cart was not found"));}
    public MerchantQuote quote(UUID buyerId,UUID threadId){require(buyerId,threadId);return repository.currentQuote(buyerId,threadId).orElseThrow(()->new BuyerException("MERCHANT_QUOTE_NOT_FOUND",HttpStatus.NOT_FOUND,"Authoritative merchant quote was not found"));}
    public ConstraintCertificate certificate(UUID buyerId,UUID threadId){require(buyerId,threadId);return repository.currentCertificate(buyerId,threadId).orElseThrow(()->new BuyerException("CONSTRAINT_CERTIFICATE_NOT_FOUND",HttpStatus.NOT_FOUND,"Constraint certificate was not found"));}
    public List<BuyerAgentAction> actions(UUID buyerId,UUID threadId){require(buyerId,threadId);return repository.actions(buyerId,threadId);}
    private static String normalize(String value){if(value==null)throw new BuyerException("BUYER_INPUT_REQUIRED",HttpStatus.BAD_REQUEST,"Typed input is required");String text=value.strip().replaceAll("\\s+"," ");
        if(text.isEmpty()||text.length()>4000)throw new BuyerException("BUYER_INPUT_INVALID",HttpStatus.BAD_REQUEST,"Typed input must contain 1 to 4000 characters");return text;}
}
