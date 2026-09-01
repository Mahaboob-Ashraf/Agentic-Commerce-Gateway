package dev.agenticcommerce.gateway.lifecycle;

import static dev.agenticcommerce.gateway.lifecycle.LifecycleModels.*;
import static dev.agenticcommerce.gateway.commerce.TransactionModels.*;
import dev.agenticcommerce.gateway.commerce.*;
import dev.agenticcommerce.gateway.intent.*;
import dev.agenticcommerce.gateway.risk.*;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ReorderService {
    private final LifecycleService lifecycle;private final LifecycleRepository repository;
    private final BuyerThreadService threads;private final BuyerOrchestrationService orchestration;
    private final AuthoritativeRefreshService refresh;private final TransactionProposalService proposals;
    private final ReversibilityService risk;private final LifecycleAuditService audit;
    public ReorderService(LifecycleService lifecycle,LifecycleRepository repository,BuyerThreadService threads,
            BuyerOrchestrationService orchestration,AuthoritativeRefreshService refresh,
            TransactionProposalService proposals,ReversibilityService risk,LifecycleAuditService audit){this.lifecycle=lifecycle;this.repository=repository;
        this.threads=threads;this.orchestration=orchestration;this.refresh=refresh;this.proposals=proposals;this.risk=risk;this.audit=audit;}

    public ReorderResult reorder(UUID buyer,UUID intentId,String sessionBindingHash){
        Intent intent=lifecycle.requireIntent(buyer,intentId);
        if(intent.action()!=Action.REORDER||intent.resolution()!=Resolution.RESOLVED)
            throw new LifecycleException("REORDER_INTENT_INVALID",HttpStatus.CONFLICT,"A resolved reorder intent is required");
        var originalContext=repository.context(buyer,intent.finalizationId()).orElseThrow();
        var originalLines=repository.originalOrderLines(buyer,intent.finalizationId());
        String original=repository.originalThreadText(buyer,intent.threadId());
        ReorderResult result=freshPurchase(buyer,original,sessionBindingHash,intent.threadId());
        var fresh=proposals.require(buyer,result.newProposalId());
        boolean exactMerchant=originalContext.merchantId().equals(fresh.merchantId());
        boolean exactLines=originalLines.size()==fresh.lineItems().size()&&java.util.stream.IntStream.range(0,originalLines.size()).allMatch(index->{
            var old=originalLines.get(index);var current=fresh.lineItems().get(index);
            return old.productId().equals(current.productId())&&old.merchantSku().equals(current.merchantSku())
                    &&Objects.equals(old.variant(),current.variant())&&old.quantity()==current.quantity();});
        if(!exactMerchant||!exactLines)throw new LifecycleException("REORDER_EXACT_ITEM_MISMATCH",HttpStatus.CONFLICT,"Reorder requires the exact original merchant, item, variant, and quantity");
        audit.record(buyer,fresh.merchantId(),result.newThreadId(),"REORDER_FRESH_PURCHASE_CREATED",result.newProposalId().toString(),
                audit.reference("sourceThreadId",intent.threadId().toString()));
        return result;
    }
    public ReorderResult freshPurchase(UUID buyer,String normalizedIntent,String sessionBindingHash,UUID sourceThread){
        PreparedFreshPurchase prepared=prepareFreshPurchase(buyer,normalizedIntent,sourceThread);
        var evaluation=risk.evaluate(buyer,prepared.proposal().proposalId(),sessionBindingHash);
        return new ReorderResult(sourceThread,prepared.threadId(),prepared.proposal().proposalId(),prepared.proposal().proposalHash(),
                prepared.proposal().finalAmountMinor(),evaluation.outcome().name(),true);
    }
    public PreparedFreshPurchase prepareFreshPurchase(UUID buyer,String normalizedIntent,UUID sourceThread){
        BuyerModels.CommerceThread thread=threads.create(buyer,normalizedIntent);
        for(int i=0;i<6;i++)orchestration.advance(buyer,thread.threadId());
        refresh.refresh(buyer,thread.threadId());TransactionProposal proposal=proposals.create(buyer,thread.threadId());
        return new PreparedFreshPurchase(sourceThread,thread.threadId(),proposal);
    }
    public record ReorderResult(UUID sourceThreadId,UUID newThreadId,UUID newProposalId,String newProposalHash,
            long amountMinor,String riskOutcome,boolean freshFactsRequired){}
    public record PreparedFreshPurchase(UUID sourceThreadId,UUID threadId,TransactionProposal proposal){}
}
