package dev.agenticcommerce.gateway.intent;

import static dev.agenticcommerce.gateway.intent.BuyerModels.*;

import dev.agenticcommerce.gateway.identity.authentication.VerifiedActorPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/buyer/threads")
public class BuyerApiController {
    private final BuyerThreadService threads;private final BuyerOrchestrationService orchestration;
    public BuyerApiController(BuyerThreadService threads,BuyerOrchestrationService orchestration){this.threads=threads;this.orchestration=orchestration;}
    @PostMapping public CommerceThread create(@AuthenticationPrincipal VerifiedActorPrincipal p,@Valid @RequestBody TypedInput request){return threads.create(p.actorId(),request.text());}
    @GetMapping public List<CommerceThread> list(@AuthenticationPrincipal VerifiedActorPrincipal p){return threads.list(p.actorId());}
    @GetMapping("/{threadId}") public CommerceThread get(@AuthenticationPrincipal VerifiedActorPrincipal p,@PathVariable UUID threadId){return threads.require(p.actorId(),threadId);}
    @PostMapping("/{threadId}/messages") public ThreadMessage message(@AuthenticationPrincipal VerifiedActorPrincipal p,@PathVariable UUID threadId,@Valid @RequestBody TypedInput request){return threads.addMessage(p.actorId(),threadId,request.text());}
    @PostMapping("/{threadId}/advance") public AdvanceResult advance(@AuthenticationPrincipal VerifiedActorPrincipal p,@PathVariable UUID threadId){return orchestration.advance(p.actorId(),threadId);}
    @GetMapping("/{threadId}/messages") public List<ThreadMessage> messages(@AuthenticationPrincipal VerifiedActorPrincipal p,@PathVariable UUID threadId){return threads.messages(p.actorId(),threadId);}
    @GetMapping("/{threadId}/intent") public BuyerIntent intent(@AuthenticationPrincipal VerifiedActorPrincipal p,@PathVariable UUID threadId){return threads.intent(p.actorId(),threadId);}
    @GetMapping("/{threadId}/merchant-discovery") public MerchantDiscovery discovery(@AuthenticationPrincipal VerifiedActorPrincipal p,@PathVariable UUID threadId){return threads.discovery(p.actorId(),threadId);}
    @GetMapping("/{threadId}/candidate-cart") public CandidateCart cart(@AuthenticationPrincipal VerifiedActorPrincipal p,@PathVariable UUID threadId){return threads.cart(p.actorId(),threadId);}
    @GetMapping("/{threadId}/quote") public MerchantQuote quote(@AuthenticationPrincipal VerifiedActorPrincipal p,@PathVariable UUID threadId){return threads.quote(p.actorId(),threadId);}
    @GetMapping("/{threadId}/constraint-certificate") public ConstraintCertificate certificate(@AuthenticationPrincipal VerifiedActorPrincipal p,@PathVariable UUID threadId){return threads.certificate(p.actorId(),threadId);}
    @GetMapping("/{threadId}/actions") public List<BuyerAgentAction> actions(@AuthenticationPrincipal VerifiedActorPrincipal p,@PathVariable UUID threadId){return threads.actions(p.actorId(),threadId);}
    public record TypedInput(@NotBlank @Size(max=4000) String text){}
}
