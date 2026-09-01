package dev.agenticcommerce.gateway.lifecycle;

import static dev.agenticcommerce.gateway.lifecycle.LifecycleModels.*;
import dev.agenticcommerce.gateway.identity.authentication.VerifiedActorPrincipal;
import dev.agenticcommerce.gateway.authorization.AuthorizationService;
import jakarta.servlet.http.HttpSession;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/buyer")
public class LifecycleApiController {
    private final LifecycleService lifecycle;private final RefundService refunds;private final ReorderService reorders;
    private final AuthorizationService purchaseAuthorization;
    public LifecycleApiController(LifecycleService lifecycle,RefundService refunds,ReorderService reorders,
            AuthorizationService purchaseAuthorization){this.lifecycle=lifecycle;this.refunds=refunds;this.reorders=reorders;this.purchaseAuthorization=purchaseAuthorization;}
    @PostMapping("/threads/{threadId}/lifecycle/intents")
    public Intent compile(@AuthenticationPrincipal VerifiedActorPrincipal actor,@PathVariable UUID threadId,
            @RequestBody LifecycleRequest request){return lifecycle.compile(actor.actorId(),threadId,request.text());}
    @PostMapping("/lifecycle/intents/{intentId}/proposal")
    public Proposal propose(@AuthenticationPrincipal VerifiedActorPrincipal actor,@PathVariable UUID intentId){return lifecycle.propose(actor.actorId(),intentId);}
    @GetMapping("/lifecycle/proposals/{proposalId}")
    public Proposal proposal(@AuthenticationPrincipal VerifiedActorPrincipal actor,@PathVariable UUID proposalId){return lifecycle.requireProposal(actor.actorId(),proposalId);}
    @PostMapping("/lifecycle/proposals/{proposalId}/authorize")
    public Authorization authorize(@AuthenticationPrincipal VerifiedActorPrincipal actor,@PathVariable UUID proposalId,
            @RequestBody DecisionRequest request,HttpSession session){return lifecycle.authorize(actor.actorId(),proposalId,session.getId(),request.approve());}
    @PostMapping("/lifecycle/proposals/{proposalId}/execute")
    public Execution execute(@AuthenticationPrincipal VerifiedActorPrincipal actor,@PathVariable UUID proposalId,HttpSession session){return lifecycle.execute(actor.actorId(),proposalId,session.getId());}
    @PostMapping("/lifecycle/orders/{finalizationId}/return/advance")
    public TransitionResult advance(@AuthenticationPrincipal VerifiedActorPrincipal actor,@PathVariable UUID finalizationId){return new TransitionResult(lifecycle.advanceReturn(actor.actorId(),finalizationId));}
    @GetMapping("/lifecycle/orders/{finalizationId}/track")
    public OrderTracking track(@AuthenticationPrincipal VerifiedActorPrincipal actor,@PathVariable UUID finalizationId){return lifecycle.track(actor.actorId(),finalizationId);}
    @PostMapping("/lifecycle/proposals/{proposalId}/refund/reconcile")
    public RefundExecution reconcile(@AuthenticationPrincipal VerifiedActorPrincipal actor,@PathVariable UUID proposalId){return refunds.reconcile(actor.actorId(),proposalId);}
    @GetMapping("/lifecycle/intents/{intentId}")
    public LifecycleState state(@AuthenticationPrincipal VerifiedActorPrincipal actor,@PathVariable UUID intentId){return lifecycle.state(actor.actorId(),intentId);}
    @PostMapping("/lifecycle/intents/{intentId}/reorder")
    public ReorderService.ReorderResult reorder(@AuthenticationPrincipal VerifiedActorPrincipal actor,@PathVariable UUID intentId,HttpSession session){
        return reorders.reorder(actor.actorId(),intentId,purchaseAuthorization.bindSession(session.getId()));}
    public record DecisionRequest(boolean approve){}
    public record TransitionResult(String status){}
}
